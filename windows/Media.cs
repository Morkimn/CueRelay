using System.Drawing.Drawing2D;
using System.IO;
using System.Drawing.Imaging;
using System.Security.Cryptography;
using System.Windows.Threading;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace PocketPlayer;

internal sealed record Source(string Id, string App, string Title, string Artist, bool Playing,
    bool Toggle, bool Previous, bool Next, string Art, TrackTimeline? Timeline, int Rating = 0, int RatingSelected = 0);

internal sealed record TrackTimeline(long DurationMs, long PositionMs, long UpdatedAtMs, double Rate,
    bool CanSeek, long MinSeekMs, long MaxSeekMs)
{
    [System.Text.Json.Serialization.JsonIgnore]
    public long PositionNow => (long)Math.Clamp(PositionMs + Math.Max(0, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - UpdatedAtMs) * Rate,
        0, DurationMs);
}

internal sealed record Snapshot(long Revision, string Computer, string Theme, string Accent, string Current, Source[] Sources,
    int? Volume = null, bool SyncAppearance = true);

internal sealed class Media : IDisposable
{
    private readonly Identity identity;
    private readonly Dispatcher dispatcher;
    private readonly SemaphoreSlim access = new(1, 1);
    private readonly SemaphoreSlim refreshRequested = new(0, 1);
    private readonly object stateGate = new();
    private readonly Dictionary<GlobalSystemMediaTransportControlsSession, Player> players = new();
    private Dictionary<string, byte[]> artwork = new();
    private TaskCompletionSource changed = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private Snapshot state;
    private GlobalSystemMediaTransportControlsSessionManager? manager;
    private long nextRevision = DateTime.UtcNow.Ticks;

    public Media(Identity identity)
    {
        this.identity = identity;
        state = new(++nextRevision, Environment.MachineName, identity.Theme, identity.Accent, "", [],
            SyncAppearance: identity.SyncAppearance);
        var started = new TaskCompletionSource<Dispatcher>(TaskCreationOptions.RunContinuationsAsynchronously);
        var worker = new Thread(() =>
        {
            var current = Dispatcher.CurrentDispatcher;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(current));
            started.SetResult(current);
            Dispatcher.Run();
        }) { IsBackground = true, Name = "Tempo media" };
        // Keep the Windows media session and its async results on the same COM apartment.
        worker.SetApartmentState(ApartmentState.STA);
        worker.Start();
        dispatcher = started.Task.GetAwaiter().GetResult();
    }

    public Task Watch(CancellationToken stop, Action updateNetwork) =>
        dispatcher.InvokeAsync(() => WatchMedia(stop, updateNetwork), DispatcherPriority.Normal, stop).Task.Unwrap();

    private async Task WatchMedia(CancellationToken stop, Action updateNetwork)
    {
        long networkUpdated = 0;
        do
        {
            try { await Refresh(stop); }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                manager = null;
                await access.WaitAsync(stop);
                try
                {
                    foreach (var player in players.Values) player.Dispose();
                    players.Clear();
                    Publish("", []);
                }
                finally { access.Release(); }
            }
            if (Environment.TickCount64 - networkUpdated >= 900 || networkUpdated == 0)
            {
                updateNetwork();
                networkUpdated = Environment.TickCount64;
            }
            await refreshRequested.WaitAsync(TimeSpan.FromMilliseconds(850), stop);
        } while (!stop.IsCancellationRequested);
    }

    private void RequestRefresh()
    {
        try { refreshRequested.Release(); }
        catch (SemaphoreFullException) { }
        catch (ObjectDisposedException) { }
    }

    public async Task<Snapshot> Read(long revision, CancellationToken stop)
    {
        Task signal;
        lock (stateGate)
        {
            if (revision != state.Revision) return state;
            signal = changed.Task;
        }
        try { await signal.WaitAsync(TimeSpan.FromSeconds(16), stop); }
        catch (TimeoutException) { }
        lock (stateGate) return state;
    }

    public byte[]? Artwork(string id) { lock (stateGate) return artwork.GetValueOrDefault(id); }

    public Snapshot Current { get { lock (stateGate) return state; } }

    public Task<bool> SetVolume(int level, Func<bool> allowed, CancellationToken stop) =>
        dispatcher.InvokeAsync(() =>
        {
            if (!allowed() || !EndpointVolume.Set(level)) return false;
            RefreshVolume();
            return true;
        }, DispatcherPriority.Normal, stop).Task;

    private void RefreshVolume()
    {
        var volume = EndpointVolume.Read();
        lock (stateGate)
        {
            if (volume != state.Volume) Advance(state.Current, state.Sources, state.Theme, state.Accent, volume);
        }
    }

    public void RefreshAppearance()
    {
        lock (stateGate)
        {
            var theme = identity.Theme;
            var accent = identity.Accent;
            if (state.Theme == theme && state.Accent == accent && state.SyncAppearance == identity.SyncAppearance) return;
            Advance(state.Current, state.Sources, theme, accent, state.Volume);
        }
    }

    public Task<bool> Send(string id, string action, long? positionMs, string token, CancellationToken stop) =>
        Dispatch(id, action, positionMs, () => identity.Authenticate(token), stop);

    public Task<bool> SendLocal(string id, string action, long? positionMs = null) =>
        Dispatch(id, action, positionMs, static () => true, CancellationToken.None);

    private Task<bool> Dispatch(string id, string action, long? positionMs, Func<bool> allowed, CancellationToken stop) =>
        dispatcher.InvokeAsync(() => DispatchMedia(id, action, positionMs, allowed, stop),
            DispatcherPriority.Normal, stop).Task.Unwrap();

    private async Task<bool> DispatchMedia(string id, string action, long? positionMs, Func<bool> allowed, CancellationToken stop)
    {
        await access.WaitAsync(stop);
        try
        {
            if (!allowed()) return false;
            var player = players.Values.FirstOrDefault(value => value.Id == id);
            if (player is null) return false;
            var playback = player.Session.GetPlaybackInfo();
            var info = playback?.Controls;
            var playing = playback?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            bool accepted = action switch
            {
                "toggle" when info?.IsPlayPauseToggleEnabled == true => await player.Session.TryTogglePlayPauseAsync().AsTask(stop),
                "play" when playing => true,
                "pause" when !playing => true,
                "toggle" or "pause" when playing && info?.IsPauseEnabled == true => await player.Session.TryPauseAsync().AsTask(stop),
                "toggle" or "play" when !playing && info?.IsPlayEnabled == true => await player.Session.TryPlayAsync().AsTask(stop),
                "play" or "pause" when info?.IsPlayPauseToggleEnabled == true => await player.Session.TryTogglePlayPauseAsync().AsTask(stop),
                "previous" when info?.IsPreviousEnabled == true => await player.Session.TrySkipPreviousAsync().AsTask(stop),
                "next" when info?.IsNextEnabled == true => await player.Session.TrySkipNextAsync().AsTask(stop),
                "seek" when info?.IsPlaybackPositionEnabled == true && positionMs is long position =>
                    await player.Seek(position, stop),
                _ => false
            };
            if (accepted)
            {
                if (action is "previous" or "next") player.InvalidateMedia();
                RequestRefresh();
            }
            return accepted;
        }
        finally { access.Release(); }
    }

    private async Task Refresh(CancellationToken stop)
    {
        await access.WaitAsync(stop);
        try
        {
            manager ??= await GlobalSystemMediaTransportControlsSessionManager.RequestAsync().AsTask(stop);
            var sessions = manager.GetSessions().ToArray();
            foreach (var session in players.Keys.Except(sessions).ToArray())
            {
                players[session].Dispose();
                players.Remove(session);
            }
            var sources = new List<Source>();
            foreach (var session in sessions)
            {
                if (!players.TryGetValue(session, out var player))
                {
                    player = new Player(session, RequestRefresh);
                    players.Add(session, player);
                }
                sources.Add(player.Read(stop));
            }
            var current = manager.GetCurrentSession();
            var activeId = current is not null && players.TryGetValue(current, out var selected) ? selected.Id : "";
            Publish(activeId, sources.ToArray());
        }
        finally { access.Release(); }
    }

    private void Publish(string current, Source[] sources)
    {
        var volume = EndpointVolume.Read();
        lock (stateGate)
        {
            var theme = identity.Theme;
            var accent = identity.Accent;
            bool sourcesChanged = !sources.SequenceEqual(state.Sources);
            if (current == state.Current && theme == state.Theme && accent == state.Accent &&
                state.SyncAppearance == identity.SyncAppearance && volume == state.Volume && !sourcesChanged) return;
            if (sourcesChanged)
                artwork = players.Values.Where(player => player.Art.Length != 0 && player.Bytes is not null)
                    .GroupBy(player => player.Art).ToDictionary(group => group.Key, group => group.First().Bytes!);
            Advance(current, sources, theme, accent, volume);
        }
    }

    private void Advance(string current, Source[] sources, string theme, string accent, int? volume)
    {
        state = new(++nextRevision, Environment.MachineName, theme, accent, current, sources, volume, identity.SyncAppearance);
        var previous = changed;
        changed = new(TaskCreationOptions.RunContinuationsAsynchronously);
        previous.TrySetResult();
    }

    public void Dispose()
    {
        dispatcher.Invoke(() =>
        {
            foreach (var player in players.Values) player.Dispose();
            access.Dispose();
            refreshRequested.Dispose();
            dispatcher.BeginInvokeShutdown(DispatcherPriority.Background);
        });
    }

    private sealed class Player : IDisposable
    {
        private int dirty = 1;
        private long nextMediaRead;
        private bool readingMetadata, disposed;
        private readonly Action wake;
        private string title = "", artist = "";
        public GlobalSystemMediaTransportControlsSession Session { get; }
        public string Id { get; } = Guid.NewGuid().ToString("N");
        public bool IsPlaying { get; private set; }
        public string Art { get; private set; } = "";
        public byte[]? Bytes { get; private set; }

        public Player(GlobalSystemMediaTransportControlsSession session, Action wake)
        {
            Session = session;
            this.wake = wake;
            Session.MediaPropertiesChanged += PropertiesChanged;
            Session.PlaybackInfoChanged += StatusChanged;
            Session.TimelinePropertiesChanged += TimelineChanged;
        }

        public Source Read(CancellationToken stop)
        {
            if (!readingMetadata && (Interlocked.Exchange(ref dirty, 0) == 1 || Environment.TickCount64 >= nextMediaRead))
            {
                readingMetadata = true;
                _ = ReadMetadata(stop);
            }
            var playback = Session.GetPlaybackInfo();
            IsPlaying = playback?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            var buttons = playback?.Controls;
            return new(Id, DisplayName(Session.SourceAppUserModelId), title, artist, IsPlaying,
                buttons?.IsPlayPauseToggleEnabled == true || (IsPlaying ? buttons?.IsPauseEnabled : buttons?.IsPlayEnabled) == true,
                buttons?.IsPreviousEnabled == true, buttons?.IsNextEnabled == true, Art, ReadTimeline(playback));
        }

        private async Task ReadMetadata(CancellationToken stop)
        {
            try
            {
                var info = await Session.TryGetMediaPropertiesAsync().AsTask(stop).WaitAsync(TimeSpan.FromSeconds(2), stop);
                if (disposed || stop.IsCancellationRequested || Volatile.Read(ref dirty) != 0) return;
                string readTitle = info?.Title ?? "";
                string readArtist = info?.Artist ?? "";
                bool sameTrack = title == readTitle && artist == readArtist;
                var thumbnail = info?.Thumbnail;
                title = readTitle;
                artist = readArtist;
                if (!sameTrack)
                {
                    Bytes = null;
                    Art = "";
                    wake();
                }
                var bytes = await Thumbnail(thumbnail, stop);
                if (disposed || stop.IsCancellationRequested || Volatile.Read(ref dirty) != 0) return;
                if (bytes is not null || thumbnail is null)
                {
                    Bytes = bytes;
                    Art = bytes is null ? "" : Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
                }
                // Some providers attach a thumbnail after they publish the track metadata.
                nextMediaRead = Environment.TickCount64 + (thumbnail is not null && bytes is null ? 1_000 : 3_000);
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
            catch (Exception) { nextMediaRead = Environment.TickCount64 + 1_000; }
            finally
            {
                readingMetadata = false;
                if (!disposed && !stop.IsCancellationRequested) wake();
            }
        }

        private TrackTimeline? ReadTimeline(GlobalSystemMediaTransportControlsSessionPlaybackInfo? playback)
        {
            try
            {
                var timeline = Session.GetTimelineProperties();
                if (timeline is null || timeline.EndTime <= timeline.StartTime) return null;
                var duration = (long)(timeline.EndTime - timeline.StartTime).TotalMilliseconds;
                if (duration <= 0) return null;
                var min = Math.Clamp((long)(timeline.MinSeekTime - timeline.StartTime).TotalMilliseconds, 0, duration);
                var max = Math.Clamp((long)(timeline.MaxSeekTime - timeline.StartTime).TotalMilliseconds, 0, duration);
                if (timeline.MaxSeekTime == TimeSpan.Zero && max == 0) max = duration;
                double rate = IsPlaying ? Math.Clamp(playback?.PlaybackRate ?? 1, 0, 16) : 0;
                long updated = timeline.LastUpdatedTime.ToUnixTimeMilliseconds();
                if (updated <= 0) rate = 0;
                return new(duration, Math.Clamp((long)(timeline.Position - timeline.StartTime).TotalMilliseconds, 0, duration),
                    updated, rate, playback?.Controls?.IsPlaybackPositionEnabled == true && max > min, min, max);
            }
            catch (System.Runtime.InteropServices.COMException) { return null; }
        }

        public async Task<bool> Seek(long relativePosition, CancellationToken stop)
        {
            if (relativePosition < 0) return false;
            var timeline = Session.GetTimelineProperties();
            if (timeline is null || timeline.EndTime <= timeline.StartTime) return false;
            var start = timeline.StartTime;
            var min = timeline.MinSeekTime > start ? timeline.MinSeekTime : start;
            var max = timeline.MaxSeekTime > min && timeline.MaxSeekTime < timeline.EndTime ? timeline.MaxSeekTime : timeline.EndTime;
            if (relativePosition > (timeline.EndTime - start).TotalMilliseconds + 1000) return false;
            long target = Math.Clamp((start + TimeSpan.FromMilliseconds(relativePosition)).Ticks, min.Ticks, max.Ticks);
            return await Session.TryChangePlaybackPositionAsync(target).AsTask(stop);
        }

        private static string DisplayName(string id)
        {
            var known = new (string Key, string Name)[]
            {
                ("chrome", "Chrome"), ("edge", "Edge"), ("spotify", "Spotify"), ("firefox", "Firefox"),
                ("vlc", "VLC"), ("zunemusic", "Медиаплеер"), ("youtubemusic", "YouTube Music"),
                ("yandexmusic", "Яндекс Музыка"), ("yandex.desktop.music", "Яндекс Музыка"),
                ("pocketplayer.smoke", "Тестовый плеер")
            };
            foreach (var (key, name) in known)
                if (id.Contains(key, StringComparison.OrdinalIgnoreCase)) return name;
            try { return Windows.ApplicationModel.AppInfo.GetFromAppUserModelId(id).DisplayInfo.DisplayName; }
            catch { return id.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? Path.GetFileNameWithoutExtension(id) : id; }
        }

        private static async Task<byte[]?> Thumbnail(IRandomAccessStreamReference? thumb, CancellationToken stop)
        {
            if (thumb is null) return null;
            try
            {
                using var buffer = await thumb.OpenReadAsync().AsTask(stop).WaitAsync(TimeSpan.FromSeconds(2), stop);
                if (buffer.Size is 0 or > 8_000_000) return null;
                using var reader = new DataReader(buffer);
                var bytes = new byte[(int)await reader.LoadAsync((uint)buffer.Size).AsTask(stop).WaitAsync(TimeSpan.FromSeconds(2), stop)];
                reader.ReadBytes(bytes);
                using var memory = new MemoryStream(bytes);
                using var original = Image.FromStream(memory);
                const int size = 160;
                var scale = Math.Max((double)size / original.Width, (double)size / original.Height);
                var width = (float)(original.Width * scale);
                var height = (float)(original.Height * scale);
                using var frame = new Bitmap(size, size);
                using var graphics = Graphics.FromImage(frame);
                graphics.Clear(Color.FromArgb(21, 24, 35));
                graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
                graphics.DrawImage(original, (size - width) / 2, (size - height) / 2, width, height);
                using var output = new MemoryStream();
                frame.Save(output, ImageFormat.Jpeg);
                return output.ToArray();
            }
            catch (Exception exception) when (exception is not OperationCanceledException) { return null; }
        }

        private void PropertiesChanged(GlobalSystemMediaTransportControlsSession session, MediaPropertiesChangedEventArgs args)
        {
            InvalidateMedia();
            wake();
        }

        public void InvalidateMedia() => Interlocked.Exchange(ref dirty, 1);

        private void StatusChanged(GlobalSystemMediaTransportControlsSession session, PlaybackInfoChangedEventArgs args) => wake();
        private void TimelineChanged(GlobalSystemMediaTransportControlsSession session, TimelinePropertiesChangedEventArgs args) => wake();

        public void Dispose()
        {
            disposed = true;
            Session.MediaPropertiesChanged -= PropertiesChanged;
            Session.PlaybackInfoChanged -= StatusChanged;
            Session.TimelinePropertiesChanged -= TimelineChanged;
        }
    }
}
