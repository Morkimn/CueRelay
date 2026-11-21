using W = System.Windows;
using M = System.Windows.Media;

namespace PocketPlayer;

public sealed class WaveTrack : W.FrameworkElement
{
    public static readonly W.DependencyProperty FractionProperty = W.DependencyProperty.Register(
        nameof(Fraction), typeof(double), typeof(WaveTrack), new W.FrameworkPropertyMetadata(0d, W.FrameworkPropertyMetadataOptions.AffectsRender));
    public static readonly W.DependencyProperty PlayedProperty = W.DependencyProperty.Register(
        nameof(Played), typeof(M.Brush), typeof(WaveTrack), new W.FrameworkPropertyMetadata(M.Brushes.Black, W.FrameworkPropertyMetadataOptions.AffectsRender));
    public static readonly W.DependencyProperty RestProperty = W.DependencyProperty.Register(
        nameof(Rest), typeof(M.Brush), typeof(WaveTrack), new W.FrameworkPropertyMetadata(M.Brushes.Gray, W.FrameworkPropertyMetadataOptions.AffectsRender));

    public double Fraction { get => (double)GetValue(FractionProperty); set => SetValue(FractionProperty, value); }
    public M.Brush Played { get => (M.Brush)GetValue(PlayedProperty); set => SetValue(PlayedProperty, value); }
    public M.Brush Rest { get => (M.Brush)GetValue(RestProperty); set => SetValue(RestProperty, value); }

    protected override void OnRender(M.DrawingContext drawing)
    {
        double center = ActualHeight / 2, width = ActualWidth - 14;
        if (width <= 0) return;
        double end = width * Math.Clamp(Fraction, 0, 1);
        const double gap = 9;
        if (end + gap < width)
            drawing.DrawLine(RoundPen(Rest, 3), new W.Point(7 + end + gap, center), new W.Point(7 + width, center));
        double waveEnd = end - gap;
        if (waveEnd <= 0) return;
        var path = new M.StreamGeometry();
        using (var points = path.Open())
        {
            points.BeginFigure(new W.Point(7, center), false, false);
            for (double x = 1; x < waveEnd; x += 1.5)
            {
                var fade = Math.Min(1, Math.Min(x, waveEnd - x) / 5);
                points.LineTo(new W.Point(7 + x, center + 2.6 * fade * Math.Sin(x * Math.Tau / 21)), true, true);
            }
            points.LineTo(new W.Point(7 + waveEnd, center), true, true);
        }
        drawing.DrawGeometry(null, RoundPen(Played, 3.5), path);
    }

    private static M.Pen RoundPen(M.Brush brush, double width) =>
        new(brush, width) { StartLineCap = M.PenLineCap.Round, EndLineCap = M.PenLineCap.Round };
}
