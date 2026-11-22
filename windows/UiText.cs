using System.IO;

namespace PocketPlayer;

internal static class UiText
{
    private static readonly Lazy<Dictionary<string, string>> English = new(Load);
    public static string Language { get; set; } = "ru";
    public static string Text(string value) => Language == "en" ? English.Value.GetValueOrDefault(value, value) : value;

    private static Dictionary<string, string> Load()
    {
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        using var stream = typeof(UiText).Assembly.GetManifestResourceStream("CueRelay.English")!;
        using var reader = new StreamReader(stream);
        while (reader.ReadLine() is { } line)
        {
            int split = line.IndexOf('|');
            if (split > 0) result[line[..split]] = line[(split + 1)..].Replace("\\s", " ", StringComparison.Ordinal);
        }
        return result;
    }
}
