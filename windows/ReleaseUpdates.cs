using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PocketPlayer;

internal static partial class ReleaseUpdates
{
    private static readonly HttpClient Client = new() { Timeout = TimeSpan.FromMinutes(8) };
    private static readonly string Folder = Path.Combine(Path.GetTempPath(), "CueRelay", "updates");
    private static readonly string AssetName = RuntimeInformation.ProcessArchitecture == Architecture.Arm64
        ? "CueRelay-Setup-arm64.exe" : "CueRelay-Setup-x64.exe";
    public static Version CurrentVersion { get; } = typeof(ReleaseUpdates).Assembly.GetName().Version!;

    public static async Task<Release?> Check(CancellationToken cancellation)
    {
        var (repo, key) = Channel();
        var root = $"https://github.com/{repo}/releases";
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(TimeSpan.FromSeconds(15));
        byte[] manifest = await ReadLimited($"{root}/latest/download/CueRelay-Update.json", 16384, timeout.Token);
        byte[] signature = await ReadLimited($"{root}/latest/download/CueRelay-Update.sig", 512, timeout.Token);
        using var rsa = RSA.Create();
        rsa.ImportSubjectPublicKeyInfo(key, out _);
        if (!rsa.VerifyData(manifest, signature, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1))
            throw new CryptographicException("Invalid release signature");
        using var json = JsonDocument.Parse(manifest);
        var version = json.RootElement.GetProperty("version").GetString();
        if (version is null || !VersionPattern().IsMatch(version) || !Version.TryParse(version, out var parsed))
            throw new InvalidDataException("Invalid release version");
        if (parsed <= new Version(CurrentVersion.Major, CurrentVersion.Minor, CurrentVersion.Build)) return null;
        if (!json.RootElement.GetProperty("assets").TryGetProperty(AssetName, out var target)) return null;
        var hash = target.GetProperty("sha256").GetString();
        var size = target.GetProperty("size").GetInt64();
        if (hash is not { Length: 64 } || !hash.All(Uri.IsHexDigit) || size is <= 0 or > 200_000_000)
            throw new InvalidDataException("Invalid release asset");
        return new(version, new Uri($"{root}/download/v{version}/{AssetName}"), hash, size);
    }

    public static async Task Install(Release release, CancellationToken cancellation)
    {
        Directory.CreateDirectory(Folder);
        foreach (var old in Directory.EnumerateDirectories(Folder))
        {
            if (!Guid.TryParseExact(Path.GetFileName(old), "N", out _) ||
                Directory.GetLastWriteTimeUtc(old) >= DateTime.UtcNow.AddDays(-2)) continue;
            var oldInstaller = Path.Combine(old, AssetName);
            try { File.Delete(oldInstaller); Directory.Delete(old); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
        }
        var staging = Directory.CreateDirectory(Path.Combine(Folder, Guid.NewGuid().ToString("N")));
        var installerPath = Path.Combine(staging.FullName, AssetName);
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            timeout.CancelAfter(TimeSpan.FromMinutes(8));
            using (var response = await Client.GetAsync(release.Url, HttpCompletionOption.ResponseHeadersRead, timeout.Token))
            {
                response.EnsureSuccessStatusCode();
                await using var source = await response.Content.ReadAsStreamAsync(timeout.Token);
                await using var file = new FileStream(installerPath, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None);
                await CopyLimited(source, file, release.Size, timeout.Token);
                if (file.Length != release.Size) throw new InvalidDataException("Unexpected release size");
                file.Position = 0;
                var hash = await SHA256.HashDataAsync(file, timeout.Token);
                if (!CryptographicOperations.FixedTimeEquals(hash, Convert.FromHexString(release.Hash)))
                    throw new CryptographicException("Invalid release payload");
            }
            using var process = Process.Start(new ProcessStartInfo(installerPath)
            {
                UseShellExecute = true,
                Arguments = "/SILENT /NORESTART /SP- /CueRelayUpdate=1"
            }) ?? throw new IOException("Installer did not start");
        }
        catch
        {
            try { File.Delete(installerPath); Directory.Delete(staging.FullName); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
            throw;
        }
    }

    private static (string Repository, byte[] PublicKey) Channel()
    {
        using var stream = typeof(ReleaseUpdates).Assembly.GetManifestResourceStream("CueRelay.UpdateChannel")!;
        using var reader = new StreamReader(stream);
        return (reader.ReadLine()!, Convert.FromBase64String(reader.ReadLine()!));
    }

    private static async Task<byte[]> ReadLimited(string url, int limit, CancellationToken cancellation)
    {
        using var response = await Client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellation);
        response.EnsureSuccessStatusCode();
        await using var body = await response.Content.ReadAsStreamAsync(cancellation);
        using var memory = new MemoryStream();
        await CopyLimited(body, memory, limit, cancellation);
        return memory.ToArray();
    }

    private static async Task CopyLimited(Stream from, Stream to, long limit, CancellationToken cancellation)
    {
        var buffer = new byte[65536];
        long length = 0;
        while (await from.ReadAsync(buffer, cancellation) is int read && read > 0)
        {
            length += read;
            if (length > limit) throw new InvalidDataException("Release content exceeded size limit");
            await to.WriteAsync(buffer.AsMemory(0, read), cancellation);
        }
    }

    [GeneratedRegex(@"^(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})$")]
    private static partial Regex VersionPattern();

    internal sealed record Release(string Version, Uri Url, string Hash, long Size);
}
