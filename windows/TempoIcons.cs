using System.Xml.Linq;
using W = System.Windows;
using C = System.Windows.Controls;
using M = System.Windows.Media;
using S = System.Windows.Shapes;

namespace PocketPlayer;

internal static class TempoIcons
{
    private static readonly Dictionary<string, Part[]> icons = new();
    private static readonly XNamespace android = "http://schemas.android.com/apk/res/android";

    public static W.FrameworkElement Draw(string name, M.Brush color, double size)
    {
        if (!icons.TryGetValue(name, out var parts))
        {
            using var stream = typeof(TempoIcons).Assembly.GetManifestResourceStream("Tempo.Icons." + name + ".xml")
                ?? throw new InvalidOperationException("Missing Tempo icon: " + name);
            parts = XDocument.Load(stream).Root!.Elements("path").Select(path =>
            {
                var winding = (string?)path.Attribute(android + "fillType") == "evenOdd" ? "F0 " : "F1 ";
                var geometry = M.Geometry.Parse(winding + (string?)path.Attribute(android + "pathData"));
                geometry.Freeze();
                return new Part(geometry, path.Attribute(android + "fillColor") is not null,
                    (double?)path.Attribute(android + "strokeWidth") ?? 0);
            }).ToArray();
            icons[name] = parts;
        }
        var viewport = new C.Canvas { Width = 24, Height = 24 };
        foreach (var part in parts)
        {
            viewport.Children.Add(new S.Path
            {
                Data = part.Geometry, Fill = part.Fill ? color : null, Stroke = part.Width > 0 ? color : null,
                StrokeThickness = part.Width, StrokeStartLineCap = M.PenLineCap.Round,
                StrokeEndLineCap = M.PenLineCap.Round, StrokeLineJoin = M.PenLineJoin.Round
            });
        }
        return new C.Viewbox { Width = size, Height = size, Child = viewport, IsHitTestVisible = false };
    }

    private sealed record Part(M.Geometry Geometry, bool Fill, double Width);
}
