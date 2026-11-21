using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace PocketPlayer;

internal static class Diagnostics
{
    private static readonly object Gate = new();
    private static readonly string Folder = Path.Combine(Path.GetTempPath(), "CueRelay", "logs");
    private static readonly string Current = Path.Combine(Folder, "diagnostics.jsonl");
    private static readonly string Previous = Path.Combine(Folder, "diagnostics.previous.jsonl");
    private static bool initialized;

    public static void Record(string component, string eventName, Exception? failure = null)
    {
        lock (Gate)
        {
            try
            {
                if (!initialized)
                {
                    Directory.CreateDirectory(Folder);
                    foreach (var file in new[] { Current, Previous })
                        if (File.Exists(file) && File.GetLastWriteTimeUtc(file) < DateTime.UtcNow.AddDays(-7))
                            File.Delete(file);
                    initialized = true;
                }
                if (File.Exists(Current) && new FileInfo(Current).Length >= 512 * 1024)
                    File.Move(Current, Previous, overwrite: true);
                File.AppendAllText(Current, JsonSerializer.Serialize(new
                {
                    ts = DateTimeOffset.UtcNow, platform = "windows", version = ReleaseUpdates.CurrentVersion.ToString(3),
                    arch = RuntimeInformation.ProcessArchitecture.ToString(), os = Environment.OSVersion.Version.Build,
                    component, @event = eventName, error = failure?.GetType().Name, code = failure?.HResult
                }) + "\n");
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
        }
    }

    public static string Read()
    {
        lock (Gate)
        {
            return string.Concat(new[] { Previous, Current }.Where(File.Exists).Select(File.ReadAllText));
        }
    }
}
