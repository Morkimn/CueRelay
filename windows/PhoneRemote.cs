namespace PocketPlayer;

internal sealed class PhoneRemote(Identity identity)
{
    private readonly object gate = new();
    private readonly Queue<(PhoneCommand Command, long Expires)> commands = new();
    private string? pairKey;
    private long heardAt;
    private long revision;
    private PhoneView view = new(0, "offline", [], new Dictionary<string, byte[]>());
    private TaskCompletionSource commandReady = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public PhoneView Current
    {
        get
        {
            lock (gate)
            {
                if (pairKey != identity.PairKey || Environment.TickCount64 - heardAt > 7000)
                    Reset();
                return view;
            }
        }
    }

    public PhoneCommand[]? Exchange(string? token, PhoneReport report)
    {
        lock (gate)
        {
            var key = identity.RegisterPhone(token, report.Device);
            if (key is null) return null;
            if (key != pairKey || Environment.TickCount64 - heardAt > 7000) Reset();
            pairKey = key;
            heardAt = Environment.TickCount64;
            var status = report.Status is "ready" or "permission" ? report.Status : "off";
            var images = new Dictionary<string, byte[]>();
            var sources = new List<Source>();
            if (status == "ready")
            {
                foreach (var item in (report.Sources ?? []).Take(4))
                {
                    if (item?.Id is not { Length: 32 } || !item.Id.All(Uri.IsHexDigit) ||
                        sources.Any(source => source.Id == item.Id)) continue;
                    string imageKey = "";
                    if (item.Cover is { Length: > 0 and <= 24000 } cover)
                    {
                        try
                        {
                            byte[] bytes = Convert.FromBase64String(cover);
                            if (bytes is [0xff, 0xd8, ..])
                            {
                                imageKey = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(bytes));
                                images[imageKey] = bytes;
                            }
                        }
                        catch (FormatException) { }
                    }
                    TrackTimeline? timeline = null;
                    if (item.Duration is > 0 and <= 604800000)
                        timeline = new(item.Duration, Math.Clamp(item.Position, 0, item.Duration),
                            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), item.Playing && double.IsFinite(item.Rate)
                                ? Math.Clamp(item.Rate, 0, 4) : 0, item.Seek, 0, item.Duration);
                    sources.Add(new(item.Id, Limit(item.App, 80), Limit(item.Title, 160), Limit(item.Artist, 120),
                        item.Playing, item.Toggle, item.Previous, item.Next, imageKey, timeline,
                        item.Rating & 3, item.RatingSelected & item.Rating & 3));
                }
            }
            int? volume = status == "ready" && report.Volume is >= 0 and <= 100 ? report.Volume : null;
            view = new(++revision, status, sources.ToArray(), images, report.PushOnly, volume);
            if (status != "ready") { commands.Clear(); WakeCommands(); }
            return report.PushOnly ? [] : TakeCommands();
        }
    }

    public async Task<PhoneCommand[]?> ReadCommands(string? token, CancellationToken stop)
    {
        Task signal;
        lock (gate)
        {
            if (!identity.Authenticate(token)) return null;
            if (Current.Status != "ready") return [];
            var ready = TakeCommands();
            if (ready.Length > 0) return ready;
            signal = commandReady.Task;
        }
        try { await signal.WaitAsync(TimeSpan.FromSeconds(10), stop); }
        catch (TimeoutException) { }
        lock (gate)
        {
            if (!identity.Authenticate(token)) return null;
            return Current.Status == "ready" ? TakeCommands() : [];
        }
    }

    private PhoneCommand[] TakeCommands()
    {
        var now = Environment.TickCount64;
        var ready = commands.Where(entry => entry.Expires >= now &&
            (entry.Command.Action == "volume" ? view.Volume.HasValue : view.Sources.Any(source => source.Id == entry.Command.Id)))
            .Select(entry => entry.Command).ToArray();
        commands.Clear();
        return ready;
    }

    private void WakeCommands()
    {
        var signal = commandReady;
        commandReady = new(TaskCreationOptions.RunContinuationsAsynchronously);
        signal.TrySetResult();
    }

    public bool Send(string id, string action, long? positionMs = null)
    {
        lock (gate)
        {
            var source = Current.Sources.FirstOrDefault(source => source.Id == id);
            bool supported = action switch
            {
                "toggle" or "play" or "pause" => source?.Toggle == true,
                "previous" => source?.Previous == true,
                "next" => source?.Next == true,
                "seek" => source?.Timeline is { CanSeek: true } time && positionMs >= 0 && positionMs <= time.DurationMs,
                "like" => source is not null && (source.Rating & 1) != 0,
                "dislike" => source is not null && (source.Rating & 2) != 0,
                _ => false
            };
            if (!supported || source is null || commands.Count >= 8) return false;
            string verb = !view.FastCommands && action is ("play" or "pause") ? "toggle" : action;
            commands.Enqueue((new(id, verb, positionMs, source.Title), Environment.TickCount64 + 2400));
            WakeCommands();
            return true;
        }
    }

    public bool SendVolume(int level)
    {
        lock (gate)
        {
            if (level is < 0 or > 100 || !Current.Volume.HasValue) return false;
            int queued = commands.Count;
            for (int i = 0; i < queued; i++)
            {
                var previous = commands.Dequeue();
                if (previous.Command.Action != "volume") commands.Enqueue(previous);
            }
            if (commands.Count >= 8) return false;
            commands.Enqueue((new("", "volume", level, ""), Environment.TickCount64 + 2400));
            WakeCommands();
            return true;
        }
    }

    private void Reset()
    {
        pairKey = null;
        commands.Clear();
        if (view.Status != "offline")
        {
            view = new(++revision, "offline", [], new Dictionary<string, byte[]>());
            WakeCommands();
        }
    }

    private static string Limit(string? value, int length) =>
        string.IsNullOrEmpty(value) ? "" : value[..Math.Min(value.Length, length)];

    internal sealed record PhoneView(long Revision, string Status, Source[] Sources, IReadOnlyDictionary<string, byte[]> Covers,
        bool FastCommands = false, int? Volume = null);
    internal sealed record PhoneReport(string Device, string Status, PhoneTrack[]? Sources, bool PushOnly = false, int? Volume = null);
    internal sealed record PhoneTrack(string Id, string App, string Title, string Artist, bool Playing, bool Toggle,
        bool Previous, bool Next, bool Seek, long Duration, long Position, double Rate, string? Cover, int Rating = 0,
        int RatingSelected = 0);
    internal sealed record PhoneCommand(string Id, string Action, long? PositionMs, string Title);
}
