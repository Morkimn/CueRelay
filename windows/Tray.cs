using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Windows.Interop;
using System.Windows.Threading;
using Desktop = System.Windows;
using Forms = System.Windows.Forms;

namespace PocketPlayer;

internal sealed class Tray : IDisposable
{
    private readonly Forms.NotifyIcon icon;
    private readonly Icon brand;
    private readonly Forms.ContextMenuStrip menu;
    private readonly DispatcherTimer timer;
    private readonly Dashboard window;
    private readonly Desktop.Application desktop;

    public Tray(Desktop.Application desktop, Identity identity, Media media, PhoneRemote phone, EventWaitHandle open, EventWaitHandle stop, bool background)
    {
        this.desktop = desktop;
        brand = MakeIcon();
        window = new Dashboard(identity, media, phone)
        {
            Icon = Imaging.CreateBitmapSourceFromHIcon(brand.Handle, Desktop.Int32Rect.Empty,
                System.Windows.Media.Imaging.BitmapSizeOptions.FromEmptyOptions())
        };
        menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Открыть CueRelay", null, (_, _) => ShowWindow());
        menu.Items.Add("Выйти", null, (_, _) => Stop());
        icon = new Forms.NotifyIcon { Text = Program.Title, Icon = brand, Visible = true, ContextMenuStrip = menu };
        icon.DoubleClick += (_, _) => ShowWindow();
        timer = new() { Interval = TimeSpan.FromMilliseconds(260) };
        timer.Tick += (_, _) =>
        {
            if (stop.WaitOne(0)) Stop();
            else if (open.WaitOne(0)) ShowWindow();
        };
        timer.Start();
        if (!background) ShowWindow();
    }

    private void ShowWindow()
    {
        window.Show();
        if (window.WindowState == Desktop.WindowState.Minimized) window.WindowState = Desktop.WindowState.Normal;
        window.Activate();
    }

    private void Stop()
    {
        timer.Stop();
        icon.Visible = false;
        window.CloseProgram();
        desktop.Shutdown();
    }

    public void Dispose()
    {
        timer.Stop();
        icon.Dispose();
        menu.Dispose();
        brand.Dispose();
    }

    private static Icon MakeIcon()
    {
        using var bitmap = new Bitmap(32, 32);
        using var canvas = Graphics.FromImage(bitmap);
        using var pill = new SolidBrush(Color.FromArgb(220, 225, 232));
        using var glyph = new SolidBrush(Color.FromArgb(47, 53, 64));
        canvas.SmoothingMode = SmoothingMode.AntiAlias;
        canvas.FillRoundedRectangle(pill, new Rectangle(1, 1, 30, 30), new Size(14, 14));
        canvas.FillRectangle(glyph, 7, 14, 3, 5);
        canvas.FillRectangle(glyph, 12, 10, 3, 13);
        canvas.FillRectangle(glyph, 17, 7, 3, 18);
        canvas.FillRectangle(glyph, 22, 12, 3, 9);
        var handle = bitmap.GetHicon();
        try { return (Icon)Icon.FromHandle(handle).Clone(); }
        finally { DestroyIcon(handle); }
    }

    [DllImport("user32.dll")] private static extern bool DestroyIcon(IntPtr icon);
}
