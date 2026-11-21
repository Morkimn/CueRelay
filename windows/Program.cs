using System.IO;
using System.Net;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.AspNetCore.Http.Features;

namespace PocketPlayer;

internal static class Program
{
    public const int Port = 43198;
    public const string Title = "CueRelay";
    public const string AppMutex = @"Local\CodexPocketPlayer-run";
    private const string OpenEvent = @"Local\CodexPocketPlayer-open";
    private const string StopEvent = @"Local\CodexPocketPlayer-stop";

    [STAThread]
    private static void Main(string[] args)
    {
        if (args.Contains("--stop"))
        {
            Signal(StopEvent);
            return;
        }
        using var mutex = new Mutex(true, AppMutex, out var first);
        if (!first)
        {
            Signal(OpenEvent);
            return;
        }

        ApplicationConfiguration.Initialize();
        bool test = args.Contains("--development");
        var directory = test ? Environment.GetEnvironmentVariable("POCKET_PLAYER_DEV_DATA") :
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CodexPocketPlayer");
        try
        {
            Diagnostics.Record("app", "started");
            using var identity = new Identity(directory ?? throw new InvalidOperationException("Set POCKET_PLAYER_DEV_DATA."));
            using var media = new Media(identity);
            var phone = new PhoneRemote(identity);
            using var cancellation = new CancellationTokenSource();
            var discovery = new Discovery(identity.ServiceName, Port);
            var server = CreateServer(identity, media, phone, test, new IPEndPoint(IPAddress.Any, Port));
            server.StartAsync().GetAwaiter().GetResult();
            Diagnostics.Record("server", "listening");
            var watcher = media.Watch(cancellation.Token, () => discovery.Refresh(Network.LocalAddresses()));
            try
            {
                using var open = new EventWaitHandle(false, EventResetMode.AutoReset, OpenEvent);
                using var stop = new EventWaitHandle(false, EventResetMode.AutoReset, StopEvent);
                var desktop = new System.Windows.Application { ShutdownMode = System.Windows.ShutdownMode.OnExplicitShutdown };
                using var tray = new Tray(desktop, identity, media, phone, open, stop, args.Contains("--background"));
                desktop.Run();
            }
            finally
            {
                // The WPF dispatcher is already stopped. Shutdown continuations belong on the pool.
                Task.Run(async () =>
                {
                    cancellation.Cancel();
                    discovery.Stop();
                    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                    try { await server.StopAsync(timeout.Token); }
                    catch (OperationCanceledException) { }
                    await server.DisposeAsync();
                    try { await watcher; }
                    catch (OperationCanceledException) { }
                }).GetAwaiter().GetResult();
            }
        }
        catch (Exception exception)
        {
            Diagnostics.Record("app", "startup_failed", exception);
            MessageBox.Show("Не удалось запустить CueRelay.\n" + exception.Message, Title,
                MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private static void Signal(string name)
    {
        if (!EventWaitHandle.TryOpenExisting(name, out var handle)) return;
        using (handle) handle.Set();
    }

    private static WebApplication CreateServer(Identity identity, Media media, PhoneRemote phone, bool development,
        IPEndPoint listenAddress)
    {
        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions { Args = [] });
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.AddServerHeader = false;
            options.Limits.MaxRequestBodySize = 1024;
            options.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(8);
            options.Listen(listenAddress, endpoint =>
            {
                endpoint.Protocols = HttpProtocols.Http1;
                endpoint.UseHttps(identity.Certificate);
            });
        });
        var app = builder.Build();
        app.Use(async (context, next) =>
        {
            context.Response.Headers.CacheControl = "no-store";
            var remote = context.Connection.RemoteIpAddress;
            if (context.Request.Headers.ContainsKey("Origin") || remote is null ||
                !(Network.IsLocalV4(remote) || development && IPAddress.IsLoopback(remote)))
            {
                context.Response.StatusCode = 403;
                return;
            }
            if (context.Request.Path != "/pair/request" && context.Request.Path != "/pair/result" &&
                !identity.Authenticate(Token(context)))
            {
                context.Response.StatusCode = 401;
                return;
            }
            if (context.Request.Path == "/phone/exchange" &&
                context.Features.Get<IHttpMaxRequestBodySizeFeature>() is { IsReadOnly: false } limit)
                limit.MaxRequestBodySize = 108000;
            await next(context);
        });
        app.MapPost("/pair/request", (PairRequest request) => PairReply(identity, identity.RequestPair(request.Code, request.Id, request.Device)));
        app.MapPost("/pair/result", (PairResult request) => PairReply(identity, identity.PairResult(request.Id)));
        app.MapPost("/phone/exchange", (PhoneRemote.PhoneReport report, HttpContext context) =>
            phone.Exchange(Token(context), report) is { } commands ? Results.Json(new { commands }) : Results.Unauthorized());
        app.MapGet("/phone/commands", async (HttpContext context, CancellationToken stop) =>
            await phone.ReadCommands(Token(context), stop) is { } commands ? Results.Json(new { commands }) : Results.Unauthorized());
        app.MapGet("/ping", () => Results.NoContent());
        app.MapGet("/state", async (long? revision, HttpContext context, CancellationToken stop) =>
        {
            var state = await media.Read(revision ?? 0, stop);
            return identity.Authenticate(Token(context)) ? Results.Json(state) : Results.Unauthorized();
        });
        app.MapGet("/art/{id}", (string id) => media.Artwork(id) is byte[] bytes ? Results.File(bytes, "image/jpeg") : Results.NotFound());
        app.MapPost("/command", async (Command request, HttpContext context, CancellationToken stop) =>
        {
            try
            {
                return await media.Send(request.Id, request.Action, request.PositionMs, Token(context)!, stop)
                    ? Results.NoContent() : Results.StatusCode(409);
            }
            catch (System.Runtime.InteropServices.COMException) { return Results.StatusCode(409); }
        });
        app.MapPost("/theme", (ThemeRequest request, HttpContext context) =>
        {
            if (!identity.SetTheme(request.Theme, Token(context))) return Results.BadRequest();
            media.RefreshAppearance();
            return Results.NoContent();
        });
        app.MapPost("/accent", (AccentRequest request, HttpContext context) =>
        {
            if (!identity.SetAccent(request.Accent, Token(context))) return Results.BadRequest();
            media.RefreshAppearance();
            return Results.NoContent();
        });
        app.MapPost("/disconnect", (HttpContext context) => identity.Forget(Token(context)) ? Results.NoContent() : Results.Unauthorized());
        return app;
    }

    private static IResult PairReply(Identity identity, Identity.PairAttempt? attempt) =>
        attempt is null ? Results.StatusCode(403) : Results.Json(new
        {
            attempt.Status, attempt.Check, attempt.Token, computer = Environment.MachineName,
            theme = identity.Theme, accent = identity.Accent
        });

    private static string? Token(HttpContext context)
    {
        var header = context.Request.Headers.Authorization.ToString();
        return header.StartsWith("Bearer ", StringComparison.Ordinal) ? header[7..] : null;
    }

    private sealed record PairRequest(string Code, string Id, string Device);
    private sealed record PairResult(string Id);
    private sealed record ThemeRequest(string Theme);
    private sealed record AccentRequest(string Accent);
    private sealed record Command(string Id, string Action, long? PositionMs = null);
}
