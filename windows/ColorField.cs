using W = System.Windows;
using I = System.Windows.Input;
using M = System.Windows.Media;

namespace PocketPlayer;

internal sealed class ColorField(bool hueStrip) : W.FrameworkElement
{
    private static readonly M.Brush WhiteToClear = Fade(M.Colors.White, M.Color.FromArgb(0, 255, 255, 255), new W.Point(1, 0));
    private static readonly M.Brush ClearToBlack = Fade(M.Color.FromArgb(0, 0, 0, 0), M.Colors.Black, new W.Point(0, 1));
    private static readonly M.Brush Spectrum = MakeSpectrum();
    private static readonly M.Pen Edge = new(M.Brushes.Black, 4);
    private static readonly M.Pen Ring = new(M.Brushes.White, 2);
    private double hue, saturation, value;

    public event Action<double, double>? Picked;

    public void Show(double h, double s, double v)
    {
        (hue, saturation, value) = (h, s, v);
        InvalidateVisual();
    }

    protected override void OnRender(M.DrawingContext drawing)
    {
        var area = new W.Rect(1, 1, Math.Max(0, ActualWidth - 2), Math.Max(0, ActualHeight - 2));
        if (area.IsEmpty || area.Width < 16 || area.Height < 16) return;
        const double radius = 12;
        drawing.PushClip(new M.RectangleGeometry(area, radius, radius));
        if (hueStrip) drawing.DrawRectangle(Spectrum, null, area);
        else
        {
            drawing.DrawRectangle(new M.SolidColorBrush(Rgb(hue, 1, 1)), null, area);
            drawing.DrawRectangle(WhiteToClear, null, area);
            drawing.DrawRectangle(ClearToBlack, null, area);
        }
        drawing.Pop();
        var marker = new W.Point(area.X + 8 + (area.Width - 16) * (hueStrip ? hue : saturation),
            hueStrip ? area.Y + area.Height / 2 : area.Y + 8 + (area.Height - 16) * (1 - value));
        var swatch = new M.SolidColorBrush(hueStrip ? Rgb(hue, 1, 1) : Rgb(hue, saturation, value));
        drawing.DrawEllipse(swatch, Edge, marker, 6, 6);
        drawing.DrawEllipse(swatch, Ring, marker, 6, 6);
        if (IsKeyboardFocused) drawing.DrawRoundedRectangle(null, Ring, area, radius, radius);
    }

    protected override void OnGotKeyboardFocus(I.KeyboardFocusChangedEventArgs e)
    {
        base.OnGotKeyboardFocus(e);
        InvalidateVisual();
    }

    protected override void OnLostKeyboardFocus(I.KeyboardFocusChangedEventArgs e)
    {
        base.OnLostKeyboardFocus(e);
        InvalidateVisual();
    }

    protected override void OnMouseLeftButtonDown(I.MouseButtonEventArgs e)
    {
        Focus();
        CaptureMouse();
        Pick(e.GetPosition(this));
        e.Handled = true;
    }

    protected override void OnMouseMove(I.MouseEventArgs e)
    {
        if (IsMouseCaptured && e.LeftButton == I.MouseButtonState.Pressed) Pick(e.GetPosition(this));
    }

    protected override void OnMouseLeftButtonUp(I.MouseButtonEventArgs e)
    {
        if (!IsMouseCaptured) return;
        Pick(e.GetPosition(this));
        ReleaseMouseCapture();
        e.Handled = true;
    }

    protected override void OnKeyDown(I.KeyEventArgs e)
    {
        var step = e.Key is I.Key.Left or I.Key.Down ? -.02 : .02;
        if (e.Key is I.Key.Left or I.Key.Right)
            Picked?.Invoke(Math.Clamp((hueStrip ? hue : saturation) + step, 0, 1), value);
        else if (!hueStrip && e.Key is I.Key.Up or I.Key.Down)
            Picked?.Invoke(saturation, Math.Clamp(value + step, 0, 1));
        else return;
        e.Handled = true;
    }

    private void Pick(W.Point point) => Picked?.Invoke(
        Math.Clamp((point.X - 9) / Math.Max(1, ActualWidth - 18), 0, 1),
        1 - Math.Clamp((point.Y - 9) / Math.Max(1, ActualHeight - 18), 0, 1));

    internal static M.Color Rgb(double h, double s, double v)
    {
        byte Channel(double shift)
        {
            double sector = (shift + 6 * h) % 6;
            return (byte)Math.Round(255 * v * (1 - s * Math.Max(0, Math.Min(Math.Min(sector, 4 - sector), 1))));
        }
        return M.Color.FromRgb(Channel(5), Channel(3), Channel(1));
    }

    private static M.LinearGradientBrush Fade(M.Color start, M.Color end, W.Point direction)
    {
        var brush = new M.LinearGradientBrush(start, end, new W.Point(), direction);
        brush.Freeze();
        return brush;
    }

    private static M.Brush MakeSpectrum()
    {
        var brush = new M.LinearGradientBrush { StartPoint = new W.Point(), EndPoint = new W.Point(1, 0) };
        for (int stop = 0; stop <= 6; stop++)
            brush.GradientStops.Add(new M.GradientStop(Rgb(stop / 6d, 1, 1), stop / 6d));
        brush.Freeze();
        return brush;
    }
}
