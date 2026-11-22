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
    private readonly RegisteredWaitHandle showSignal, stopSignal;
    private readonly Identity identity;
    private readonly Media media;
    private readonly PhoneRemote phone;
    private Dashboard? window;
    private readonly Desktop.Application desktop;
    private bool stopping;

    public Tray(Desktop.Application desktop, Identity identity, Media media, PhoneRemote phone, EventWaitHandle open, EventWaitHandle stop, bool background)
    {
        this.desktop = desktop;
        this.identity = identity;
        this.media = media;
        this.phone = phone;
        using (var stream = typeof(Tray).Assembly.GetManifestResourceStream("CueRelay.Icon")!)
        using (var iconResource = new Icon(stream))
            brand = (Icon)iconResource.Clone();
        menu = new Forms.ContextMenuStrip();
        menu.Items.Add(UiText.Text("Открыть CueRelay"), null, (_, _) => ShowWindow());
        menu.Items.Add(UiText.Text("Выйти"), null, (_, _) => Stop());
        menu.Opening += (_, _) =>
        {
            menu.Items[0].Text = UiText.Text("Открыть CueRelay");
            menu.Items[1].Text = UiText.Text("Выйти");
        };
        icon = new Forms.NotifyIcon { Text = Program.Title, Icon = brand, Visible = true, ContextMenuStrip = menu };
        icon.DoubleClick += (_, _) => ShowWindow();
        showSignal = ThreadPool.RegisterWaitForSingleObject(open,
            (_, _) => desktop.Dispatcher.BeginInvoke(new Action(ShowWindow)), null, Timeout.Infinite, false);
        stopSignal = ThreadPool.RegisterWaitForSingleObject(stop,
            (_, _) => desktop.Dispatcher.BeginInvoke(new Action(Stop)), null, Timeout.Infinite, true);
        if (!background) ShowWindow();
    }

    private void ShowWindow()
    {
        if (stopping) return;
        if (window is null)
        {
            var dashboard = new Dashboard(identity, media, phone)
            {
                Icon = Imaging.CreateBitmapSourceFromHIcon(brand.Handle, Desktop.Int32Rect.Empty,
                    System.Windows.Media.Imaging.BitmapSizeOptions.FromEmptyOptions())
            };
            window = dashboard;
            dashboard.IsVisibleChanged += (_, _) =>
            {
                if (!dashboard.IsVisible && identity.ReleaseWindow)
                    desktop.Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() =>
                    {
                        if (stopping || window != dashboard || dashboard.IsVisible) return;
                        window = null;
                        dashboard.CloseProgram();
                    }));
            };
        }
        window.Show();
        if (window.WindowState == Desktop.WindowState.Minimized) window.WindowState = Desktop.WindowState.Normal;
        window.Activate();
    }

    private void Stop()
    {
        if (stopping) return;
        stopping = true;
        icon.Visible = false;
        window?.CloseProgram();
        desktop.Shutdown();
    }

    public void Dispose()
    {
        showSignal.Unregister(null);
        stopSignal.Unregister(null);
        icon.Dispose();
        menu.Dispose();
        brand.Dispose();
    }

}
