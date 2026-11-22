using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Automation;
using System.Windows.Interop;
using System.Windows.Markup;
using System.Windows.Threading;
using Microsoft.Win32;
using QRCoder;
using W = System.Windows;
using C = System.Windows.Controls;
using I = System.Windows.Input;
using M = System.Windows.Media;
using A = System.Windows.Media.Animation;
using B = System.Windows.Media.Imaging;

namespace PocketPlayer;

internal sealed class Dashboard : W.Window
{
    private const string Play = "ic_play";
    private const string Pause = "ic_pause";
    private const string Previous = "ic_previous";
    private const string Next = "ic_next";
    private const string Logo = "ic_logo";
    private const string Gear = "ic_settings";
    private const string CloseGlyph = "ic_close";
    private const string Phone = "ic_phone";
    private const string Computer = "ic_monitor";
    private const string SourcesGlyph = "ic_source";
    private const string StartupKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string SettingsKey = @"Software\CodexPocketPlayer";
    private const string RunName = "CodexPocketPlayer";

    private readonly Identity identity;
    private readonly Media media;
    private readonly PhoneRemote phone;
    private readonly DispatcherTimer timer;
    private readonly W.Style buttonStyle;
    private readonly C.ControlTemplate seekTemplate;
    private readonly C.ControlTemplate drawerScrollTemplate;
    private readonly C.ControlTemplate settingSwitchTemplate;
    private Palette palette;
    private C.Grid root = null!;
    private C.Grid? overlay;
    private C.Button updateNotice = null!;
    private C.Button checkUpdateButton = null!, installUpdateButton = null!;
    private C.TextBlock updateDetails = null!;
    private ReleaseUpdates.Release? availableUpdate;
    private string updateStatus = "";
    private bool checkingUpdates, installingUpdate;
    private long nextUpdateCheck;
    private uint pairClipboardSequence;
    private C.Border? drawer;
    private C.ScrollViewer? drawerScroll;
    private readonly Dictionary<Panel, double> settingsOffsets = new();
    private C.TextBlock song = null!, details = null!, deviceStatus = null!;
    private C.TextBlock deviceName = null!;
    private C.Button targetMode = null!;
    private C.Image artwork = null!;
    private W.FrameworkElement artworkPlaceholder = null!;
    private C.Button previous = null!, toggle = null!, next = null!, device = null!;
    private C.Slider seek = null!;
    private C.StackPanel timelinePanel = null!;
    private C.TextBlock elapsed = null!, duration = null!;
    private W.FrameworkElement playGlyph = null!;
    private readonly Dictionary<Panel, C.Button> dockButtons = new();
    private Source? current;
    private Source? seeking;
    private Panel panel;
    private string? payload, artKey;
    private string? chosenSource;
    private string? chosenPhoneSource;
    private Identity.PairAttempt? displayedAttempt;
    private (string PairKey, string DeviceName)? unpairTarget;
    private W.Point? appearanceOrigin;
    private long revision = -1;
    private bool paired, playing, firstShow = true, exiting;
    private bool usePhone;
    private (string Id, bool Playing, long Expires)? pendingPhonePlay;
    private (string Id, string Title, long Position, long Expires)? pendingPhoneSeek;

    public Dashboard(Identity identity, Media media, PhoneRemote phone)
    {
        this.identity = identity;
        this.media = media;
        this.phone = phone;
        usePhone = identity.StartWithPhone;
        Title = Program.Title;
        Width = 765;
        Height = 582;
        WindowStartupLocation = W.WindowStartupLocation.CenterScreen;
        ResizeMode = W.ResizeMode.CanResize;
        MinWidth = 580;
        MinHeight = 540;
        FontFamily = new M.FontFamily(new Uri("pack://application:,,,/"), "./Fonts/#Google Sans");
        UseLayoutRounding = true;
        buttonStyle = (W.Style)XamlReader.Parse("""
            <Style xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                   xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml" TargetType="{x:Type Button}">
              <Setter Property="Cursor" Value="Hand"/>
              <Setter Property="HorizontalContentAlignment" Value="Center"/>
              <Setter Property="Template">
                <Setter.Value>
                  <ControlTemplate TargetType="{x:Type Button}">
                    <Border x:Name="Surface" Background="{TemplateBinding Background}"
                            CornerRadius="{Binding Tag, RelativeSource={RelativeSource TemplatedParent}}"
                            Padding="{TemplateBinding Padding}">
                      <ContentPresenter HorizontalAlignment="{TemplateBinding HorizontalContentAlignment}"
                                        VerticalAlignment="Center" RecognizesAccessKey="True"/>
                    </Border>
                    <ControlTemplate.Triggers>
                      <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="Surface" Property="Opacity" Value=".87"/></Trigger>
                      <Trigger Property="IsPressed" Value="True"><Setter TargetName="Surface" Property="Opacity" Value=".68"/></Trigger>
                      <Trigger Property="IsEnabled" Value="False"><Setter TargetName="Surface" Property="Opacity" Value=".46"/></Trigger>
                    </ControlTemplate.Triggers>
                  </ControlTemplate>
                </Setter.Value>
              </Setter>
            </Style>
            """);
        settingSwitchTemplate = (C.ControlTemplate)XamlReader.Parse("""
            <ControlTemplate xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml" TargetType="{x:Type CheckBox}">
                <Border Background="{TemplateBinding Background}" CornerRadius="21" Padding="8,5,10,5">
                    <DockPanel LastChildFill="True">
                        <Border x:Name="Track" DockPanel.Dock="Right" Width="48" Height="28" CornerRadius="14"
                                Background="{TemplateBinding BorderBrush}">
                            <Ellipse x:Name="Knob" Width="22" Height="22" HorizontalAlignment="Left"
                                     Margin="3,0" Fill="#96929D"/>
                        </Border>
                        <ContentPresenter VerticalAlignment="Center"/>
                    </DockPanel>
                </Border>
                <ControlTemplate.Triggers>
                    <Trigger Property="IsChecked" Value="True">
                        <Setter TargetName="Track" Property="Background" Value="{Binding Tag, RelativeSource={RelativeSource TemplatedParent}}"/>
                        <Setter TargetName="Knob" Property="HorizontalAlignment" Value="Right"/>
                        <Setter TargetName="Knob" Property="Fill" Value="{Binding Foreground, RelativeSource={RelativeSource TemplatedParent}}"/>
                    </Trigger>
                    <Trigger Property="IsEnabled" Value="False">
                        <Setter Property="Opacity" Value=".62"/>
                    </Trigger>
                </ControlTemplate.Triggers>
            </ControlTemplate>
            """);
        seekTemplate = (C.ControlTemplate)XamlReader.Parse("""
            <ControlTemplate xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml" xmlns:p="clr-namespace:PocketPlayer;assembly=CueRelay"
                TargetType="{x:Type Slider}">
                <Grid Background="Transparent">
                    <p:WaveTrack Fraction="{TemplateBinding Value}" Played="{TemplateBinding Foreground}"
                        Rest="{TemplateBinding Background}" IsHitTestVisible="False"/>
                    <Track x:Name="PART_Track" VerticalAlignment="Center">
                        <Track.DecreaseRepeatButton>
                            <RepeatButton Command="{x:Static Slider.DecreaseLarge}" Opacity="0"/>
                        </Track.DecreaseRepeatButton>
                        <Track.Thumb>
                            <Thumb Width="14" Height="27">
                                <Thumb.Template>
                                    <ControlTemplate TargetType="{x:Type Thumb}">
                                        <Border Background="Transparent">
                                            <Ellipse Width="10" Height="10"
                                              Fill="{Binding Foreground, RelativeSource={RelativeSource AncestorType=Slider}}"/>
                                        </Border>
                                    </ControlTemplate>
                                </Thumb.Template>
                            </Thumb>
                        </Track.Thumb>
                        <Track.IncreaseRepeatButton>
                            <RepeatButton Command="{x:Static Slider.IncreaseLarge}" Opacity="0"/>
                        </Track.IncreaseRepeatButton>
                    </Track>
                </Grid>
            </ControlTemplate>
            """);
        drawerScrollTemplate = (C.ControlTemplate)XamlReader.Parse("""
            <ControlTemplate xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml" TargetType="{x:Type ScrollViewer}">
                <Grid>
                    <Grid.ColumnDefinitions>
                        <ColumnDefinition Width="*"/>
                        <ColumnDefinition Width="Auto"/>
                    </Grid.ColumnDefinitions>
                    <ScrollContentPresenter x:Name="PART_ScrollContentPresenter" Content="{TemplateBinding Content}"
                        ContentTemplate="{TemplateBinding ContentTemplate}" CanContentScroll="{TemplateBinding CanContentScroll}"/>
                    <ScrollBar x:Name="PART_VerticalScrollBar" Grid.Column="1" Orientation="Vertical"
                        Width="15" Margin="4,0,0,0" Focusable="False"
                        Visibility="{TemplateBinding ComputedVerticalScrollBarVisibility}"
                        Maximum="{TemplateBinding ScrollableHeight}" ViewportSize="{TemplateBinding ViewportHeight}"
                        Value="{Binding VerticalOffset, RelativeSource={RelativeSource TemplatedParent}, Mode=OneWay}"
                        Background="{TemplateBinding Background}" Foreground="{TemplateBinding Foreground}">
                        <ScrollBar.Template>
                            <ControlTemplate TargetType="{x:Type ScrollBar}">
                                <Grid Background="Transparent">
                                    <Border Width="4" Background="{TemplateBinding Background}" CornerRadius="2"/>
                                    <Track x:Name="PART_Track" IsDirectionReversed="True">
                                        <Track.DecreaseRepeatButton>
                                            <RepeatButton Command="{x:Static ScrollBar.PageUpCommand}" Focusable="False">
                                                <RepeatButton.Template>
                                                    <ControlTemplate TargetType="{x:Type RepeatButton}"><Border Background="Transparent"/></ControlTemplate>
                                                </RepeatButton.Template>
                                            </RepeatButton>
                                        </Track.DecreaseRepeatButton>
                                        <Track.Thumb>
                                            <Thumb MinHeight="24">
                                                <Thumb.Template>
                                                    <ControlTemplate TargetType="{x:Type Thumb}">
                                                        <Grid Background="Transparent">
                                                            <Border x:Name="Handle" Width="6" CornerRadius="3" Opacity=".52"
                                                                Background="{Binding Foreground, RelativeSource={RelativeSource AncestorType=ScrollBar}}"/>
                                                        </Grid>
                                                        <ControlTemplate.Triggers>
                                                            <Trigger Property="IsMouseOver" Value="True">
                                                                <Setter TargetName="Handle" Property="Opacity" Value=".84"/>
                                                            </Trigger>
                                                            <Trigger Property="IsDragging" Value="True">
                                                                <Setter TargetName="Handle" Property="Opacity" Value="1"/>
                                                            </Trigger>
                                                        </ControlTemplate.Triggers>
                                                    </ControlTemplate>
                                                </Thumb.Template>
                                            </Thumb>
                                        </Track.Thumb>
                                        <Track.IncreaseRepeatButton>
                                            <RepeatButton Command="{x:Static ScrollBar.PageDownCommand}" Focusable="False">
                                                <RepeatButton.Template>
                                                    <ControlTemplate TargetType="{x:Type RepeatButton}"><Border Background="Transparent"/></ControlTemplate>
                                                </RepeatButton.Template>
                                            </RepeatButton>
                                        </Track.IncreaseRepeatButton>
                                    </Track>
                                </Grid>
                            </ControlTemplate>
                        </ScrollBar.Template>
                    </ScrollBar>
                </Grid>
            </ControlTemplate>
            """);
        palette = Theme();
        Build();
        timer = new() { Interval = TimeSpan.FromMilliseconds(110) };
        timer.Tick += (_, _) => Refresh();
        IsVisibleChanged += (_, _) =>
        {
            if (IsVisible)
            {
                Refresh();
                timer.Start();
                if (firstShow && !identity.Paired) StartPairing();
                firstShow = false;
            }
            else
            {
                timer.Stop();
                ClosePanel(animate: false);
            }
        };
        Closing += (_, args) =>
        {
            if (exiting) return;
            args.Cancel = true;
            Hide();
        };
        PreviewKeyDown += (_, args) =>
        {
            if (args.Key != I.Key.Escape || panel == Panel.None) return;
            DismissPanel();
            args.Handled = true;
        };
        SourceInitialized += (_, _) => ApplyTitleBar();
    }

    private void Build()
    {
        root = new C.Grid { Background = palette.Background };
        Content = root;
        var body = new C.StackPanel { Margin = new W.Thickness(29, 22, 29, 22) };
        root.Children.Add(body);

        var header = new C.DockPanel { LastChildFill = true, Height = 61 };
        C.Border phoneCard = BuildDeviceCard();
        C.DockPanel.SetDock(phoneCard, C.Dock.Right);
        header.Children.Add(phoneCard);
        header.SizeChanged += (_, args) => deviceName.MaxWidth = Math.Clamp(args.NewSize.Width - 350, 130, 240);
        targetMode = IconButton("Переключить устройство", Computer, 48, 48, false, () => SelectTarget(!usePhone));
        targetMode.VerticalAlignment = W.VerticalAlignment.Center;
        targetMode.Margin = new W.Thickness(12, 0, 0, 0);
        SpringPress(targetMode);
        PaintTarget();
        C.DockPanel.SetDock(targetMode, C.Dock.Right);
        header.Children.Add(targetMode);

        var brand = new C.StackPanel { Orientation = C.Orientation.Horizontal, VerticalAlignment = W.VerticalAlignment.Center };
        brand.Children.Add(new C.Border
        {
            Width = 49, Height = 49, CornerRadius = new W.CornerRadius(15), Background = palette.Accent,
            Child = Glyph(Logo, palette.OnAccent, 27),
        });
        var brandText = new C.StackPanel { Margin = new W.Thickness(13, 0, 0, 0), VerticalAlignment = W.VerticalAlignment.Center };
        brandText.Children.Add(Text(Program.Title, 30, palette.Text, true));
        brandText.Children.Add(Text(Environment.MachineName, 12, palette.Muted));
        brand.Children.Add(brandText);
        header.Children.Add(brand);
        body.Children.Add(header);

        var playerCard = Card(19);
        playerCard.CornerRadius = new W.CornerRadius(29);
        playerCard.Margin = new W.Thickness(0, 23, 0, 0);
        var playerRow = new C.DockPanel { LastChildFill = true };
        var artFrame = new C.Border { Width = 151, Height = 151, CornerRadius = new W.CornerRadius(27),
            Margin = new W.Thickness(0, 0, 22, 0), Background = palette.Raised };
        var artGrid = new C.Grid();
        artGrid.SizeChanged += (_, _) => artGrid.Clip = new M.RectangleGeometry(
            new W.Rect(0, 0, artGrid.ActualWidth, artGrid.ActualHeight), 27, 27);
        artworkPlaceholder = Glyph("ic_note", palette.Accent, 66);
        artwork = new C.Image { Stretch = M.Stretch.UniformToFill };
        AutomationProperties.SetName(artwork, "Обложка");
        artGrid.Children.Add(artworkPlaceholder);
        artGrid.Children.Add(artwork);
        artFrame.Child = artGrid;
        C.DockPanel.SetDock(artFrame, C.Dock.Left);
        playerRow.Children.Add(artFrame);
        var playerDetails = new C.StackPanel { VerticalAlignment = W.VerticalAlignment.Center };
        song = Text("", 24, palette.Text, true);
        song.TextTrimming = W.TextTrimming.CharacterEllipsis;
        song.Margin = new W.Thickness(0, 7, 0, 0);
        playerDetails.Children.Add(song);
        details = Text("", 13, palette.Muted);
        details.TextTrimming = W.TextTrimming.CharacterEllipsis;
        details.Margin = new W.Thickness(0, 4, 0, 0);
        playerDetails.Children.Add(details);

        playerDetails.Children.Add(BuildTimeline());
        var controls = new C.StackPanel { Orientation = C.Orientation.Horizontal, HorizontalAlignment = W.HorizontalAlignment.Center,
            Margin = new W.Thickness(0, 9, 0, 0) };
        var sources = IconButton("Выбрать источник", SourcesGlyph, 44, 47, false, () => OpenPanel(Panel.Sources));
        sources.Tag = new W.CornerRadius(19);
        sources.Margin = new W.Thickness(0, 0, 7, 0);
        controls.Children.Add(sources);
        previous = IconButton("Предыдущий трек", Previous, 46, 48, false, () => Command("previous"));
        previous.Tag = new W.CornerRadius(17);
        toggle = IconButton("Воспроизвести", Play, 70, 61, true, () => Command("toggle"));
        toggle.Tag = new W.CornerRadius(27, 20, 26, 20);
        SpringPress(toggle);
        playGlyph = (W.FrameworkElement)toggle.Content;
        playGlyph.RenderTransformOrigin = new W.Point(.5, .5);
        toggle.Margin = new W.Thickness(8, 0, 8, 0);
        next = IconButton("Следующий трек", Next, 46, 48, false, () => Command("next"));
        next.Tag = new W.CornerRadius(17);
        controls.Children.Add(previous);
        controls.Children.Add(toggle);
        controls.Children.Add(next);
        controls.Children.Add(new C.Border { Width = 51 });
        playerDetails.Children.Add(controls);
        playerRow.Children.Add(playerDetails);
        playerCard.Child = playerRow;
        body.Children.Add(playerCard);
        body.Children.Add(BuildDock());
        updateNotice = Button("", () => OpenPanel(Panel.Update), primary: true);
        updateNotice.Margin = new W.Thickness(0, 12, 0, 0);
        updateNotice.HorizontalAlignment = W.HorizontalAlignment.Center;
        body.Children.Add(updateNotice);
        PaintUpdateNotice();

        revision = -1;
        artKey = null;
        ReadMedia(animate: false);
        ReadDevice();
        ApplyTitleBar();
    }

    private C.Border BuildDeviceCard()
    {
        var phoneCard = Card(8);
        phoneCard.Margin = new W.Thickness(8, 0, 0, 0);
        phoneCard.Padding = new W.Thickness(14, 8, 8, 8);
        phoneCard.VerticalAlignment = W.VerticalAlignment.Center;
        var phoneRow = new C.DockPanel();
        device = Button("", () => { if (identity.Paired) OpenPanel(Panel.Device); else StartPairing(); });
        device.MinWidth = 103;
        device.MinHeight = 34;
        device.Tag = new W.CornerRadius(10, 17, 17, 10);
        C.DockPanel.SetDock(device, C.Dock.Right);
        phoneRow.Children.Add(device);
        var phoneText = new C.StackPanel { VerticalAlignment = W.VerticalAlignment.Center, Margin = new W.Thickness(0, 0, 12, 0) };
        deviceName = Text("", 14, palette.Text, true);
        deviceName.MaxWidth = 170;
        deviceName.TextTrimming = W.TextTrimming.CharacterEllipsis;
        phoneText.Children.Add(deviceName);
        deviceStatus = Text("", 12, palette.Muted);
        deviceStatus.Margin = new W.Thickness(0, 2, 0, 0);
        phoneText.Children.Add(deviceStatus);
        phoneRow.Children.Add(phoneText);
        phoneCard.Child = phoneRow;
        return phoneCard;
    }

    private C.Border BuildDock()
    {
        dockButtons.Clear();
        var tabs = new C.Primitives.UniformGrid { Rows = 1 };
        var actions = new W.Style(typeof(C.Button), buttonStyle);
        actions.Setters.Add(new W.Setter(C.Control.BackgroundProperty, M.Brushes.Transparent));
        var hover = new W.Trigger { Property = IsMouseOverProperty, Value = true };
        hover.Setters.Add(new W.Setter(C.Control.BackgroundProperty, palette.Card));
        actions.Triggers.Add(hover);
        var items = new (string Label, string Icon, Panel Destination)[]
        {
            ("Плеер", "ic_note", Panel.None), ("Источники", SourcesGlyph, Panel.Sources), ("Настройки", Gear, Panel.Settings)
        };
        for (int i = 0; i < items.Length; i++)
        {
            var item = items[i];
            var tab = Button(item.Label, () =>
            {
                if (item.Destination != Panel.None) OpenPanel(item.Destination);
                else toggle.Focus();
            });
            tab.ClearValue(C.Control.BackgroundProperty);
            tab.Style = actions;
            tab.Margin = new W.Thickness(i == 0 ? 0 : 1, 0, i == items.Length - 1 ? 0 : 1, 0);
            var left = i == 0 ? 24 : 13;
            var right = i == items.Length - 1 ? 24 : 13;
            tab.Tag = new W.CornerRadius(left, right, right, left);
            var caption = new C.StackPanel { Tag = item.Icon };
            caption.Children.Add(Glyph(item.Icon, palette.Muted, 21));
            var label = Text(item.Label, 12, palette.Muted, false);
            label.HorizontalAlignment = W.HorizontalAlignment.Center;
            label.Margin = new W.Thickness(0, 3, 0, 0);
            caption.Children.Add(label);
            tab.Content = caption;
            SpringPress(tab);
            tabs.Children.Add(tab);
            dockButtons[item.Destination] = tab;
        }
        PaintDock();
        return new C.Border
        {
            Background = palette.Raised, CornerRadius = new W.CornerRadius(30), Padding = new W.Thickness(5),
            Height = 64, Width = 369, Margin = new W.Thickness(0, 18, 0, 0), Child = tabs
        };
    }

    private void PaintDock()
    {
        var active = panel is Panel.Appearance or Panel.Color or Panel.Update or Panel.Diagnostics ? Panel.Settings : panel;
        foreach (var (destination, tab) in dockButtons)
        {
            bool selected = destination == active;
            if (selected) tab.Background = palette.DockSelection;
            else tab.ClearValue(C.Control.BackgroundProperty);
            var caption = (C.StackPanel)tab.Content;
            var ink = selected ? (palette.IsDark ? palette.Accent : palette.Text) : palette.Muted;
            caption.Children.RemoveAt(0);
            caption.Children.Insert(0, Glyph((string)caption.Tag, ink, 21));
            ((C.TextBlock)caption.Children[1]).Foreground = ink;
            ((C.TextBlock)caption.Children[1]).FontWeight = selected ? W.FontWeights.SemiBold : W.FontWeights.Normal;
            AutomationProperties.SetItemStatus(tab, selected ? "Выбрано" : "");
        }
    }

    private void SpringPress(C.Button button)
    {
        if (!Motion) return;
        var scale = new M.ScaleTransform(1, 1);
        button.RenderTransform = scale;
        button.RenderTransformOrigin = new W.Point(.5, .5);
        button.PreviewMouseLeftButtonDown += (_, _) => Resize(false);
        button.PreviewMouseLeftButtonUp += (_, _) => Resize(true);
        button.LostMouseCapture += (_, _) => Resize(true);
        void Resize(bool release)
        {
            scale.BeginAnimation(M.ScaleTransform.ScaleXProperty,
                release ? Bounce(scale.ScaleX, 1, 265) : Ease(scale.ScaleX, .94, 85));
            scale.BeginAnimation(M.ScaleTransform.ScaleYProperty,
                release ? Bounce(scale.ScaleY, 1, 265) : Ease(scale.ScaleY, .94, 85));
        }
    }

    private void Refresh()
    {
        if (!Program.IsDevelopment && Environment.TickCount64 >= nextUpdateCheck) _ = CheckUpdates();
        var colors = Theme();
        if (colors != palette)
        {
            var open = panel;
            var origin = appearanceOrigin;
            appearanceOrigin = null;
            var old = origin is not null && Motion ? Capture() : null;
            palette = colors;
            overlay = null;
            drawer = null;
            Build();
            if (open != Panel.None) OpenPanel(open, animate: false);
            if (old is not null && origin is W.Point hit) Reveal(old, hit);
        }
        if (paired != identity.Paired || (paired && DeviceTitle() != deviceName.Text)) ReadDevice();
        if (panel == Panel.Pairing && payload is not null && !identity.Pairing)
        {
            payload = null;
            if (identity.Paired) ClosePanel();
            else OpenPanel(Panel.Pairing, animate: false);
        }
        else if (panel == Panel.Pairing && displayedAttempt != identity.PendingPair)
            OpenPanel(Panel.Pairing, animate: false);
        if (pendingPhonePlay is { } pending && Environment.TickCount64 >= pending.Expires)
        {
            pendingPhonePlay = null;
            revision = -1;
        }
        ReadMedia(animate: true);
        RefreshTimeline();
    }

    private void ReadDevice()
    {
        paired = identity.Paired;
        deviceName.Text = DeviceTitle();
        deviceName.ToolTip = deviceName.Text;
        deviceStatus.Text = paired ? "Привязан по сети" : "Привязка по QR";
        device.Content = paired ? "Управлять" : "Подключить";
        AutomationProperties.SetName(device, (string)device.Content);
    }

    private string DeviceTitle() => identity.PhoneName is { Length: > 0 } name ? name : "Телефон";

    private void PaintTarget()
    {
        targetMode.Background = usePhone ? palette.Accent : palette.Raised;
        var foreground = usePhone ? palette.OnAccent : palette.Text;
        targetMode.Content = Glyph(usePhone ? Phone : Computer, foreground, 22);
        var action = usePhone ? "Переключиться на ПК" : "Переключиться на телефон";
        targetMode.ToolTip = action;
        AutomationProperties.SetName(targetMode, action);
        AutomationProperties.SetItemStatus(targetMode, usePhone ? "Телефон" : "ПК");
    }

    private void SelectTarget(bool remote)
    {
        if (usePhone == remote) return;
        usePhone = remote;
        seeking = null;
        pendingPhonePlay = null;
        pendingPhoneSeek = null;
        revision = -1;
        artKey = "\0";
        PaintTarget();
        ReadMedia(animate: false);
    }

    private void ReadMedia(bool animate)
    {
        var state = media.Current;
        var remote = usePhone ? phone.Current : null;
        long updated = remote?.Revision ?? state.Revision;
        if (revision == updated) return;
        revision = updated;
        var sources = remote?.Sources ?? state.Sources;
        current = sources.FirstOrDefault(source => source.Id == (usePhone ? chosenPhoneSource : chosenSource))
            ?? (usePhone ? null : sources.FirstOrDefault(source => source.Id == state.Current)) ?? sources.FirstOrDefault();
        var nextPlaying = current?.Playing == true;
        if (pendingPhonePlay is { } pending)
        {
            if (current?.Id != pending.Id || nextPlaying == pending.Playing) pendingPhonePlay = null;
            else nextPlaying = pending.Playing;
        }
        song.Text = current is null ? "Сейчас ничего не играет" : string.IsNullOrWhiteSpace(current.Title) ? current.App : current.Title;
        details.Text = current is null ? remote?.Status switch
        {
            "ready" => "Запусти музыку или видео на телефоне",
            "permission" => "Разреши доступ к медиасессиям в настройках Android",
            "off" => "",
            "offline" => paired ? "Ждём связи с телефоном" : "Сначала привяжи телефон",
            _ => "Запусти музыку или видео на компьютере"
        } :
            string.IsNullOrWhiteSpace(current.Artist) ? current.App : $"{current.Artist}  ·  {current.App}";
        details.Visibility = details.Text.Length == 0 ? W.Visibility.Collapsed : W.Visibility.Visible;
        previous.IsEnabled = current?.Previous == true;
        next.IsEnabled = current?.Next == true;
        toggle.IsEnabled = current?.Toggle == true;
        if (current?.Art != artKey)
        {
            artKey = current?.Art;
            var data = string.IsNullOrEmpty(artKey) ? null : remote is null
                ? media.Artwork(artKey) : remote.Covers.GetValueOrDefault(artKey);
            try { artwork.Source = data is null ? null : Bitmap(data); }
            catch (Exception error) when (error is IOException or NotSupportedException or FileFormatException) { artwork.Source = null; }
            artworkPlaceholder.Visibility = artwork.Source is null ? W.Visibility.Visible : W.Visibility.Collapsed;
        }
        SetPlayIcon(nextPlaying, animate);
        RefreshTimeline();
    }

    private void SetPlayIcon(bool nextPlaying, bool animate)
    {
        AutomationProperties.SetName(toggle, nextPlaying ? "Пауза" : "Воспроизвести");
        toggle.ToolTip = nextPlaying ? "Пауза" : "Воспроизвести";
        string icon = nextPlaying ? Pause : Play;
        if (!Equals(playGlyph.Tag, icon))
        {
            playGlyph = Glyph(icon, palette.OnAccent, 25);
            playGlyph.Tag = icon;
            playGlyph.RenderTransformOrigin = new W.Point(.5, .5);
            toggle.Content = playGlyph;
        }
        if (playing != nextPlaying && animate && Motion)
        {
            var scale = new M.ScaleTransform();
            playGlyph.RenderTransform = scale;
            scale.BeginAnimation(M.ScaleTransform.ScaleXProperty, Bounce(.79, 1, 140));
            scale.BeginAnimation(M.ScaleTransform.ScaleYProperty, Bounce(.79, 1, 140));
            playGlyph.BeginAnimation(OpacityProperty, Ease(.58, 1, 90));
        }
        playing = nextPlaying;
    }

    private async void Command(string action)
    {
        var source = current;
        if (source is null) return;
        if (usePhone)
        {
            bool desired = !playing;
            if (phone.Send(source.Id, action == "toggle" ? (desired ? "play" : "pause") : action) && action == "toggle")
            {
                pendingPhonePlay = (source.Id, desired, Environment.TickCount64 + 1100);
                SetPlayIcon(desired, true);
            }
            return;
        }
        bool flip = action == "toggle";
        if (flip) SetPlayIcon(!source.Playing, true);
        try
        {
            if (!await media.SendLocal(source.Id, action) && flip) SetPlayIcon(source.Playing, false);
        }
        catch (COMException) { if (flip) SetPlayIcon(source.Playing, false); }
    }

    private C.StackPanel BuildTimeline()
    {
        var wrap = new C.StackPanel { Margin = new W.Thickness(0, 7, 0, 0) };
        timelinePanel = wrap;
        seeking = null;
        seek = new C.Slider { Minimum = 0, Maximum = 1, SmallChange = .01, LargeChange = .06, IsMoveToPointEnabled = true,
            Height = 27, Template = seekTemplate, Foreground = palette.Accent, Background = palette.Raised };
        AutomationProperties.SetName(seek, "Перемотка трека");
        seek.AddHandler(PreviewMouseLeftButtonDownEvent, new I.MouseButtonEventHandler((_, _) => seeking = current), true);
        seek.AddHandler(PreviewMouseLeftButtonUpEvent, new I.MouseButtonEventHandler((_, _) =>
        {
            var source = seeking;
            seeking = null;
            if (source is not null) SeekFrom(source);
        }), true);
        seek.LostMouseCapture += (_, _) => seeking = null;
        seek.PreviewKeyUp += (_, args) =>
        {
            if (current is Source source && args.Key is I.Key.Left or I.Key.Right or I.Key.Home or I.Key.End
                or I.Key.PageUp or I.Key.PageDown) SeekFrom(source);
        };
        seek.ValueChanged += (_, _) =>
        {
            if (seeking?.Timeline is TrackTimeline track) elapsed.Text = TimeLabel((long)(seek.Value * track.DurationMs));
        };
        wrap.Children.Add(seek);
        var ends = new C.DockPanel { Margin = new W.Thickness(7, -1, 7, 0) };
        duration = Text("", 11, palette.Muted);
        C.DockPanel.SetDock(duration, C.Dock.Right);
        ends.Children.Add(duration);
        elapsed = Text("", 11, palette.Muted);
        ends.Children.Add(elapsed);
        wrap.Children.Add(ends);
        return wrap;
    }

    private void RefreshTimeline()
    {
        var track = current?.Timeline;
        timelinePanel.Visibility = track is null ? W.Visibility.Collapsed : W.Visibility.Visible;
        if (track is null || seeking is not null) return;
        seek.IsEnabled = track.CanSeek;
        var position = track.PositionNow;
        if (pendingPhoneSeek is { } pending)
        {
            if (current?.Id != pending.Id || current.Title != pending.Title ||
                Environment.TickCount64 >= pending.Expires || Math.Abs(position - pending.Position) < 800)
                pendingPhoneSeek = null;
            else position = Math.Clamp(pending.Position, 0, track.DurationMs);
        }
        seek.Value = (double)position / track.DurationMs;
        elapsed.Text = TimeLabel(position);
        duration.Text = TimeLabel(track.DurationMs);
    }

    private async void SeekFrom(Source start)
    {
        if (current?.Id != start.Id || current.Title != start.Title || current.Timeline is not { CanSeek: true } track) return;
        long position = Math.Clamp((long)Math.Round(seek.Value * track.DurationMs), track.MinSeekMs, track.MaxSeekMs);
        if (usePhone)
        {
            if (phone.Send(current.Id, "seek", position))
                pendingPhoneSeek = (current.Id, current.Title, position, Environment.TickCount64 + 1100);
            return;
        }
        try { await media.SendLocal(current.Id, "seek", position); }
        catch (COMException) { }
    }

    private static string TimeLabel(long position)
    {
        var time = TimeSpan.FromMilliseconds(position);
        return time.TotalHours >= 1 ? $"{(int)time.TotalHours}:{time.Minutes:D2}:{time.Seconds:D2}" : $"{time.Minutes}:{time.Seconds:D2}";
    }

    private void StartPairing()
    {
        ClearPairClipboard();
        var addresses = Network.LocalAddresses();
        if (addresses.Count == 0)
        {
            OpenPanel(Panel.Pairing);
            return;
        }
        try { payload = identity.OpenPairing(addresses, Program.Port); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            payload = null;
            Diagnostics.Record("pairing", "open_failed", error);
        }
        OpenPanel(Panel.Pairing);
    }

    private void OpenPanel(Panel value, bool animate = true)
    {
        bool returnFromConfirmation = panel == Panel.Unpair && value == Panel.Device;
        if (panel is Panel.Settings or Panel.Appearance && drawerScroll is not null)
            settingsOffsets[panel] = drawerScroll.VerticalOffset;
        if (panel == Panel.Pairing && value != Panel.Pairing) ClearInvitation();
        if (value != Panel.Unpair) unpairTarget = null;
        displayedAttempt = value == Panel.Pairing ? identity.PendingPair : null;
        bool pairingConfirmation = displayedAttempt is not null;
        if (overlay is not null) root.Children.Remove(overlay);
        panel = value;
        PaintDock();
        overlay = new C.Grid { Background = new M.SolidColorBrush(M.Color.FromArgb(86, 8, 9, 25)) };
        overlay.MouseLeftButtonDown += (_, args) =>
        {
            if (ReferenceEquals(args.OriginalSource, overlay)) DismissPanel();
        };
        if (value == Panel.Unpair && unpairTarget is { } target)
        {
            drawerScroll = null;
            drawer = UnpairConfirmation(target.PairKey, target.DeviceName, animate);
            overlay.Children.Add(drawer);
            root.Children.Add(overlay);
            return;
        }
        drawer = new C.Border
        {
            Width = pairingConfirmation ? 407 : 355, Background = palette.Card, CornerRadius = new W.CornerRadius(25),
            Padding = new W.Thickness(pairingConfirmation ? 18 : 17),
            HorizontalAlignment = pairingConfirmation ? W.HorizontalAlignment.Center : W.HorizontalAlignment.Right,
            VerticalAlignment = pairingConfirmation ? W.VerticalAlignment.Bottom : W.VerticalAlignment.Stretch,
            Margin = pairingConfirmation ? new W.Thickness(16) : new W.Thickness(0, 12, 12, 12)
        };
        var scroll = new C.ScrollViewer
        {
            VerticalScrollBarVisibility = C.ScrollBarVisibility.Auto,
            Template = drawerScrollTemplate, Foreground = palette.Accent, Background = palette.Raised
        };
        drawerScroll = scroll;
        var content = new C.StackPanel();
        scroll.Content = content;
        var layout = new C.DockPanel();
        drawer.Child = layout;
        overlay.Children.Add(drawer);
        root.Children.Add(overlay);
        var title = value switch
        {
            Panel.Settings => "Настройки", Panel.Appearance => "Оформление", Panel.Color => "Свой цвет", Panel.Sources => "Источники",
            Panel.Update => "Обновления", Panel.Diagnostics => "Диагностика",
            Panel.Pairing when displayedAttempt?.Status == "pending" => "Это твой телефон?",
            Panel.Pairing when displayedAttempt?.Status == "approved" => "Подтверждено",
            Panel.Pairing when displayedAttempt?.Status == "denied" => "Запрос отклонён",
            Panel.Device => "Телефон", _ => "Привязка телефона"
        };
        var titleRow = new C.DockPanel();
        bool hasParent = value is Panel.Appearance or Panel.Color or Panel.Update or Panel.Diagnostics;
        var close = IconButton(hasParent ? "Назад" : "Закрыть", hasParent ? "ic_left" : CloseGlyph, 39, 39, false, DismissPanel);
        if (hasParent) close.Margin = new W.Thickness(0, 0, 11, 0);
        C.DockPanel.SetDock(close, hasParent ? C.Dock.Left : C.Dock.Right);
        titleRow.Children.Add(close);
        titleRow.Children.Add(Text(title, 23, palette.Text, true));
        titleRow.Margin = new W.Thickness(0, 0, 0, 15);
        C.DockPanel.SetDock(titleRow, C.Dock.Top);
        layout.Children.Add(titleRow);
        layout.Children.Add(scroll);
        switch (value)
        {
            case Panel.Settings: Settings(content); break;
            case Panel.Update: ShowUpdates(content); break;
            case Panel.Diagnostics: ShowDiagnostics(content); break;
            case Panel.Appearance: AppearanceSettings(content); break;
            case Panel.Color: CustomColor(content); break;
            case Panel.Sources: ShowSources(content); break;
            case Panel.Device: ManageDevice(content, returnFromConfirmation); break;
            case Panel.Pairing: Pairing(content); break;
        }
        if (settingsOffsets.TryGetValue(value, out double offset) && offset > 0)
        {
            scroll.UpdateLayout();
            scroll.ScrollToVerticalOffset(offset);
            scroll.UpdateLayout();
        }
        if (!animate || !Motion) return;
        var motion = new M.TranslateTransform();
        drawer.RenderTransform = motion;
        motion.BeginAnimation(pairingConfirmation ? M.TranslateTransform.YProperty : M.TranslateTransform.XProperty,
            Bounce(60, 0, 375));
        drawer.BeginAnimation(OpacityProperty, Ease(.24, 1, 220));
    }

    private void Settings(C.StackPanel content)
    {
        content.Children.Add(SettingsGroup(SettingsRow("Оформление", "Тема, цвет и плеер", "ic_appearance", "ic_right",
            () => OpenPanel(Panel.Appearance))));
        Section(content, "Windows");
        var installed = File.Exists(Path.Combine(AppContext.BaseDirectory, "unins000.exe"));
        var start = new C.CheckBox
        {
            Template = settingSwitchTemplate, MinHeight = 58, Cursor = I.Cursors.Hand,
            Background = palette.Card, Foreground = palette.OnAccent, BorderBrush = palette.Raised,
            Tag = palette.Accent, IsEnabled = installed, IsChecked = installed && StartsAtLogin(),
            Content = SettingsLabel("Автозапуск", installed ? "" : "Доступен после установки", "ic_monitor")
        };
        AutomationProperties.SetName(start, "Автозапуск");
        var errorNote = Text("Не удалось изменить автозапуск.", 13, palette.Muted);
        errorNote.Margin = new W.Thickness(8, 8, 0, 0);
        errorNote.Visibility = W.Visibility.Collapsed;
        start.Click += (_, _) =>
        {
            try
            {
                SetStartup(start.IsChecked == true);
                errorNote.Visibility = W.Visibility.Collapsed;
            }
            catch (Exception error) when (error is UnauthorizedAccessException or IOException)
            {
                start.IsChecked = StartsAtLogin();
                errorNote.Visibility = W.Visibility.Visible;
            }
        };
        content.Children.Add(SettingsGroup(start));
        content.Children.Add(errorNote);
        Section(content, "Приложение");
        content.Children.Add(SettingsGroup(SettingsRow("Обновления", $"Версия {ReleaseUpdates.CurrentVersion.ToString(3)}",
            "ic_retry", "ic_right", () => OpenPanel(Panel.Update))));
        content.Children.Add(SettingsGroup(SettingsRow("Диагностика", "Просмотреть или сохранить журнал",
            "ic_source", "ic_right", () => OpenPanel(Panel.Diagnostics))));
    }

    private void PaintUpdateNotice()
    {
        updateNotice.Visibility = availableUpdate is null ? W.Visibility.Collapsed : W.Visibility.Visible;
        if (availableUpdate is { } release)
        {
            updateNotice.Content = $"Доступно обновление {release.Version}";
            AutomationProperties.SetName(updateNotice, (string)updateNotice.Content);
        }
    }

    private async Task CheckUpdates()
    {
        if (checkingUpdates || installingUpdate) return;
        checkingUpdates = true;
        nextUpdateCheck = Environment.TickCount64 + (long)TimeSpan.FromHours(6).TotalMilliseconds;
        updateStatus = "Проверяем GitHub…";
        PaintUpdatePanel();
        try
        {
            availableUpdate = await ReleaseUpdates.Check(CancellationToken.None);
            updateStatus = availableUpdate is null ? "Версия актуальна" : "";
            Diagnostics.Record("updates", availableUpdate is null ? "up_to_date" : "update_available");
        }
        catch (Exception error)
        {
            updateStatus = "Не удалось проверить обновления. Попробуй позже.";
            Diagnostics.Record("updates", "check_failed", error);
        }
        finally
        {
            checkingUpdates = false;
            PaintUpdateNotice();
            PaintUpdatePanel();
        }
    }

    private void ShowUpdates(C.StackPanel content)
    {
        if (Program.IsDevelopment)
        {
            content.Children.Add(Text("Локальная сборка Dev", 15, palette.Text, true));
            return;
        }
        var summary = new C.StackPanel();
        var heading = new C.DockPanel();
        var version = new C.Border
        {
            Child = Text($"v{ReleaseUpdates.CurrentVersion.ToString(3)}", 12, palette.Text, true),
            Background = palette.Card, CornerRadius = new W.CornerRadius(13),
            Padding = new W.Thickness(10, 5, 10, 5), VerticalAlignment = W.VerticalAlignment.Center
        };
        C.DockPanel.SetDock(version, C.Dock.Right);
        heading.Children.Add(version);
        var logo = new C.Border
        {
            Width = 35, Height = 35, CornerRadius = new W.CornerRadius(11), Background = palette.Accent,
            Margin = new W.Thickness(0, 0, 10, 0), Child = Glyph(Logo, palette.OnAccent, 20)
        };
        C.DockPanel.SetDock(logo, C.Dock.Left);
        heading.Children.Add(logo);
        heading.Children.Add(Text(Program.Title, 16, palette.Text, true));
        summary.Children.Add(heading);
        updateDetails = Text("", 13, palette.Muted);
        updateDetails.TextWrapping = W.TextWrapping.Wrap;
        updateDetails.Margin = new W.Thickness(0, 8, 0, 0);
        summary.Children.Add(updateDetails);
        content.Children.Add(new C.Border
        {
            Child = summary, Background = palette.GroupInset, CornerRadius = new W.CornerRadius(20),
            Padding = new W.Thickness(13, 12, 13, 12), Margin = new W.Thickness(0, 0, 0, 12)
        });
        installUpdateButton = Button("Скачать и установить", () =>
        {
            if (availableUpdate is { } update) _ = InstallUpdate(update);
        }, primary: true);
        installUpdateButton.MinHeight = 49;
        installUpdateButton.Margin = new W.Thickness(0, 0, 0, 6);
        content.Children.Add(installUpdateButton);
        checkUpdateButton = Button("Проверить обновления", () => _ = CheckUpdates());
        checkUpdateButton.MinHeight = 46;
        content.Children.Add(checkUpdateButton);
        PaintUpdatePanel();
    }

    private void PaintUpdatePanel()
    {
        if (panel != Panel.Update) return;
        updateDetails.Text = updateStatus.Length > 0 ? updateStatus : availableUpdate is { } update
            ? $"Доступна версия {update.Version}" : "Проверка через GitHub Releases";
        updateDetails.Foreground = availableUpdate is not null && updateStatus.Length == 0 ? palette.Accent : palette.Muted;
        checkUpdateButton.IsEnabled = installUpdateButton.IsEnabled = !checkingUpdates && !installingUpdate;
        installUpdateButton.Visibility = availableUpdate is null ? W.Visibility.Collapsed : W.Visibility.Visible;
    }

    private async Task InstallUpdate(ReleaseUpdates.Release update)
    {
        if (installingUpdate) return;
        installingUpdate = true;
        updateStatus = "Загружаем и проверяем установщик…";
        PaintUpdatePanel();
        try
        {
            await ReleaseUpdates.Install(update, CancellationToken.None);
            updateStatus = "Продолжи в установщике Windows.";
            Diagnostics.Record("updates", "installer_started");
        }
        catch (Exception error)
        {
            updateStatus = "Не удалось запустить обновление.";
            Diagnostics.Record("updates", "install_failed", error);
        }
        finally
        {
            installingUpdate = false;
            PaintUpdatePanel();
        }
    }

    private void ShowDiagnostics(C.StackPanel content)
    {
        string log;
        try { log = Diagnostics.Read(); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            log = "";
        }
        var snapshot = Text(log.Length == 0 ? "Журнал пока пуст." : "Последние события", 14, palette.Muted);
        content.Children.Add(snapshot);
        if (log.Length == 0) return;
        content.Children.Add(new C.TextBox
        {
            Text = string.Join('\n', log.Split('\n').TakeLast(100)), IsReadOnly = true, TextWrapping = W.TextWrapping.Wrap,
            VerticalScrollBarVisibility = C.ScrollBarVisibility.Auto, MaxHeight = 275, FontSize = 12,
            Background = palette.Background, Foreground = palette.Text, BorderThickness = new W.Thickness(0),
            Margin = new W.Thickness(0, 8, 0, 10), Padding = new W.Thickness(8)
        });
        content.Children.Add(Button("Сохранить журнал", () =>
        {
            var save = new Microsoft.Win32.SaveFileDialog { FileName = "CueRelay-Diagnostics.jsonl", Filter = "JSON Lines (*.jsonl)|*.jsonl" };
            if (save.ShowDialog(this) != true) return;
            try { File.WriteAllText(save.FileName, log); snapshot.Text = "Журнал сохранён."; }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                snapshot.Text = "Не удалось сохранить журнал.";
                Diagnostics.Record("diagnostics", "export_failed", error);
            }
        }));
    }

    private void AppearanceSettings(C.StackPanel content)
    {
        content.Children.Add(ThemePreview());
        Section(content, "Тема");
        var modes = new C.StackPanel();
        modes.Children.Add(ThemeOption("system", "Как на устройстве", "ic_monitor", new W.CornerRadius(21, 21, 8, 8)));
        modes.Children.Add(ThemeOption("light", "Светлая", "ic_sun", new W.CornerRadius(8)));
        modes.Children.Add(ThemeOption("dark", "Тёмная", "ic_moon", new W.CornerRadius(8, 8, 21, 21)));
        content.Children.Add(SettingsGroup(modes));
        Section(content, "Акцентный цвет");
        var colors = new C.Primitives.UniformGrid { Rows = 1 };
        foreach (var (key, name) in new[] { ("green", "Зелёный"), ("blue", "Голубой"),
            ("violet", "Сиреневый"), ("amber", "Янтарный") })
        {
            int fill = Palette.Seed(key);
            colors.Children.Add(ColorChoice(name, Palette.Brush(fill), identity.Accent == key, false,
                () => ChooseAppearance(() => identity.SetAccent(key))));
        }
        bool custom = identity.Accent.StartsWith('#');
        colors.Children.Add(ColorChoice("Свой цвет", custom ? Palette.Brush(Palette.Seed(identity.Accent)) : palette.Raised,
            custom, true, () => OpenPanel(Panel.Color)));
        content.Children.Add(new C.Border { Background = palette.GroupInset, CornerRadius = new W.CornerRadius(24),
            Padding = new W.Thickness(7, 11, 7, 11), Child = colors });
        Section(content, "Плеер");
        content.Children.Add(SettingsGroup(SettingsRow("Устройство при запуске", identity.StartWithPhone ? "Телефон" : "ПК",
            "ic_monitor", "ic_right", () =>
            {
                identity.SetStartWithPhone(!identity.StartWithPhone);
                OpenPanel(Panel.Appearance, animate: false);
            })));
    }

    private C.Button ThemeOption(string key, string name, string icon, W.CornerRadius radius)
    {
        bool selected = identity.Theme == key;
        var row = SettingsRow(name, "", icon, selected ? "ic_selected" : "ic_unselected",
            () => ChooseAppearance(() => identity.SetTheme(key)), selected);
        row.Tag = radius;
        row.Margin = new W.Thickness(0, key == "system" ? 0 : 2, 0, 0);
        AutomationProperties.SetItemStatus(row, selected ? "Выбрано" : "");
        return row;
    }

    private C.Border SettingsGroup(W.UIElement content) => new()
    {
        Background = palette.GroupInset, CornerRadius = new W.CornerRadius(25),
        Padding = new W.Thickness(4), Child = content
    };

    private C.Button SettingsRow(string title, string detail, string icon, string indicator, Action choose, bool selected = false)
    {
        var row = Button(title, choose);
        row.Tag = new W.CornerRadius(21);
        row.MinHeight = 58;
        row.Padding = new W.Thickness(8, 5, 10, 5);
        row.HorizontalContentAlignment = W.HorizontalAlignment.Stretch;
        row.Background = selected ? palette.Raised : palette.Card;
        var line = new C.DockPanel();
        var trailing = Glyph(indicator, selected ? palette.Accent : palette.Muted, 20);
        C.DockPanel.SetDock(trailing, C.Dock.Right);
        line.Children.Add(trailing);
        line.Children.Add(SettingsLabel(title, detail, icon));
        row.Content = line;
        return row;
    }

    private C.DockPanel SettingsLabel(string title, string detail, string icon)
    {
        var line = new C.DockPanel();
        var leading = new C.Border { Width = 37, Height = 37, Background = palette.Raised,
            CornerRadius = new W.CornerRadius(12), Margin = new W.Thickness(0, 0, 11, 0),
            Child = Glyph(icon, palette.Accent, 20) };
        C.DockPanel.SetDock(leading, C.Dock.Left);
        line.Children.Add(leading);
        var labels = new C.StackPanel { VerticalAlignment = W.VerticalAlignment.Center };
        labels.Children.Add(Text(title, 15, palette.Text, true));
        if (detail.Length > 0) labels.Children.Add(Text(detail, 12, palette.Muted));
        line.Children.Add(labels);
        return line;
    }

    private C.Button ColorChoice(string name, M.Brush surface, bool selected, bool custom, Action choose)
    {
        var button = Button(name, choose);
        button.Width = button.Height = 48;
        button.Padding = new W.Thickness(0);
        button.Background = M.Brushes.Transparent;
        button.Tag = new W.CornerRadius(24);
        button.ToolTip = name;
        var fill = ((M.SolidColorBrush)surface).Color;
        var ink = Palette.Contrast(fill);
        var disk = new C.Border { Width = 37, Height = 37, CornerRadius = new W.CornerRadius(19),
            Background = surface, Child = custom ? Glyph("ic_add", ink, 19) : null };
        button.Content = new C.Border { Width = 47, Height = 47, CornerRadius = new W.CornerRadius(24),
            BorderThickness = new W.Thickness(selected ? 2 : 0), BorderBrush = palette.Accent, Child = disk };
        AutomationProperties.SetItemStatus(button, selected ? "Выбрано" : "");
        SpringPress(button);
        return button;
    }

    private C.Border ThemePreview()
    {
        var rows = new C.StackPanel { VerticalAlignment = W.VerticalAlignment.Center };
        var player = new C.DockPanel();
        var play = new C.Border { Width = 31, Height = 31, CornerRadius = new W.CornerRadius(13),
            Background = palette.Accent, Child = Glyph(Play, palette.OnAccent, 16) };
        C.DockPanel.SetDock(play, C.Dock.Right);
        player.Children.Add(play);
        var lines = new C.StackPanel { VerticalAlignment = W.VerticalAlignment.Center };
        lines.Children.Add(new C.Border { Width = 58, Height = 5, CornerRadius = new W.CornerRadius(3),
            Background = palette.Text, HorizontalAlignment = W.HorizontalAlignment.Left });
        lines.Children.Add(new C.Border { Width = 36, Height = 4, CornerRadius = new W.CornerRadius(2),
            Background = palette.Muted, HorizontalAlignment = W.HorizontalAlignment.Left, Margin = new W.Thickness(0, 6, 0, 0) });
        player.Children.Add(lines);
        rows.Children.Add(new C.Border { Background = palette.Raised, CornerRadius = new W.CornerRadius(16),
            Padding = new W.Thickness(9), Child = player });
        var rail = new C.Grid { Width = 147, Height = 15, Margin = new W.Thickness(0, 6, 0, 0) };
        rail.Children.Add(new WaveTrack { Fraction = .58, Played = palette.Accent, Rest = palette.Muted });
        rail.Children.Add(new System.Windows.Shapes.Ellipse { Width = 6, Height = 6, Fill = palette.Accent,
            HorizontalAlignment = W.HorizontalAlignment.Left, VerticalAlignment = W.VerticalAlignment.Center,
            Margin = new W.Thickness(4 + 133 * .58, 0, 0, 0) });
        rows.Children.Add(rail);
        return new C.Border { Background = palette.GroupInset, CornerRadius = new W.CornerRadius(27), Height = 116,
            Child = new C.Border { Background = palette.Background, CornerRadius = new W.CornerRadius(20),
                Width = 167, Height = 94, Padding = new W.Thickness(10), Child = rows },
            IsHitTestVisible = false };
    }

    private void ChooseAppearance(Action change)
    {
        appearanceOrigin = I.Mouse.GetPosition(root);
        change();
        media.RefreshAppearance();
        var previous = palette;
        Refresh();
        if (previous != palette) return;
        appearanceOrigin = null;
        OpenPanel(Panel.Appearance, animate: false);
    }

    private void CustomColor(C.StackPanel content)
    {
        int seed = Palette.Seed(identity.Accent);
        int selected = seed;
        double hue = 0, saturation = 0, value = 0;
        bool fromPicker = false;
        var plane = new ColorField(false) { Height = 155, Focusable = true, Cursor = I.Cursors.Cross };
        var strip = new ColorField(true) { Height = 28, Focusable = true, Cursor = I.Cursors.Hand,
            Margin = new W.Thickness(0, 9, 0, 0) };
        AutomationProperties.SetName(plane, "Насыщенность и яркость");
        AutomationProperties.SetHelpText(plane, "Стрелки влево и вправо меняют насыщенность, вверх и вниз — яркость.");
        AutomationProperties.SetName(strip, "Тон");
        AutomationProperties.SetHelpText(strip, "Стрелки влево и вправо меняют тон.");
        var picker = new C.StackPanel();
        picker.Children.Add(plane);
        picker.Children.Add(strip);
        content.Children.Add(new C.Border { Child = picker, Background = palette.GroupInset,
            CornerRadius = new W.CornerRadius(22), Padding = new W.Thickness(9) });

        var sample = new C.Border { Width = 36, Height = 36, Background = Palette.Brush(seed),
            CornerRadius = new W.CornerRadius(11), Margin = new W.Thickness(0, 0, 10, 0) };
        var hex = new C.TextBox { Text = $"#{seed:X6}", MaxLength = 7, FontSize = 16,
            Foreground = palette.Text, Background = M.Brushes.Transparent, BorderThickness = new W.Thickness(0),
            VerticalContentAlignment = W.VerticalAlignment.Center, CaretBrush = palette.Text };
        AutomationProperties.SetName(hex, "Цвет HEX");
        var hexRow = new C.DockPanel();
        C.DockPanel.SetDock(sample, C.Dock.Left);
        hexRow.Children.Add(sample);
        var hexLabel = Text("HEX", 12, palette.Muted, true);
        hexLabel.Margin = new W.Thickness(12, 0, 2, 0);
        C.DockPanel.SetDock(hexLabel, C.Dock.Right);
        hexRow.Children.Add(hexLabel);
        hexRow.Children.Add(hex);
        var input = new C.Border { Child = hexRow, Background = palette.Raised, CornerRadius = new W.CornerRadius(16),
            Margin = new W.Thickness(0, 12, 0, 0), Padding = new W.Thickness(7) };
        content.Children.Add(input);

        void Apply()
        {
            OpenPanel(Panel.Appearance, animate: false);
            ChooseAppearance(() => identity.SetAccent($"#{selected:X6}"));
        }
        var apply = Button("Применить", Apply, primary: true);
        apply.MinHeight = 44;
        apply.Margin = new W.Thickness(0, 10, 0, 0);
        content.Children.Add(apply);

        void Paint()
        {
            sample.Background = Palette.Brush(selected);
            plane.Show(hue, saturation, value);
            strip.Show(hue, saturation, value);
        }

        void Pick()
        {
            var rgb = ColorField.Rgb(hue, saturation, value);
            selected = rgb.R << 16 | rgb.G << 8 | rgb.B;
            fromPicker = true;
            hex.Text = $"#{selected:X6}";
            fromPicker = false;
            apply.IsEnabled = true;
            Paint();
        }

        void ReadHex()
        {
            string raw = hex.Text.Trim();
            apply.IsEnabled = TryColor(raw.StartsWith('#') ? raw : "#" + raw, out int rgb);
            if (!apply.IsEnabled) return;
            selected = rgb;
            var color = Color.FromArgb(rgb | unchecked((int)0xff000000));
            int max = Math.Max(color.R, Math.Max(color.G, color.B));
            int min = Math.Min(color.R, Math.Min(color.G, color.B));
            if (max != min) hue = color.GetHue() / 360d;
            saturation = max == 0 ? 0 : (max - min) / (double)max;
            value = max / 255d;
            Paint();
        }

        plane.Picked += (s, v) => { (saturation, value) = (s, v); Pick(); };
        strip.Picked += (h, _) => { hue = h; Pick(); };
        hex.TextChanged += (_, _) => { if (!fromPicker) ReadHex(); };
        hex.KeyDown += (_, args) =>
        {
            if (args.Key != I.Key.Enter || !apply.IsEnabled) return;
            args.Handled = true;
            Apply();
        };
        ReadHex();
    }

    private static bool TryColor(string value, out int rgb)
    {
        value = value.Trim();
        rgb = 0;
        return value is { Length: 7 } && value[0] == '#' && int.TryParse(value.AsSpan(1), System.Globalization.NumberStyles.HexNumber,
            System.Globalization.CultureInfo.InvariantCulture, out rgb);
    }

    private void ShowSources(C.StackPanel content)
    {
        void Set(string? id)
        {
            if (usePhone) chosenPhoneSource = id;
            else chosenSource = id;
            revision = -1;
            ReadMedia(animate: false);
            ClosePanel();
        }
        var selected = usePhone ? chosenPhoneSource : chosenSource;
        content.Children.Add(Row("Автовыбор", usePhone ? "Активная сессия Android" : "Активная сессия Windows",
            selected is null, () => Set(null)));
        foreach (var source in usePhone ? phone.Current.Sources : media.Current.Sources)
            content.Children.Add(Row(source.App, source.Title, selected == source.Id, () => Set(source.Id)));
    }

    private void ManageDevice(C.StackPanel content, bool focusUnpair)
    {
        content.Children.Add(Text(DeviceTitle(), 17, palette.Text, true));
        content.Children.Add(Text("Доступ к воспроизведению по локальной сети.", 14, palette.Muted));
        Section(content, "Доступ");
        content.Children.Add(Row("Плеер телефона", "Открыть управление Android", false, () => { ClosePanel(); SelectTarget(true); }));
        content.Children.Add(Row("Заменить телефон", "После новой привязки прежний доступ будет отозван", false, StartPairing));
        var unpair = Row("Отвязать телефон", "", false, () =>
        {
            if (identity.PairKey is not { } pairKey) return;
            unpairTarget = (pairKey, DeviceTitle());
            OpenPanel(Panel.Unpair);
        });
        if (focusUnpair) unpair.Loaded += (_, _) => { if (panel == Panel.Device) unpair.Focus(); };
        content.Children.Add(unpair);
    }

    private C.Border UnpairConfirmation(string pairKey, string deviceTitle, bool animate)
    {
        var card = Card(23);
        card.Width = 390;
        card.HorizontalAlignment = W.HorizontalAlignment.Center;
        card.VerticalAlignment = W.VerticalAlignment.Center;
        I.KeyboardNavigation.SetTabNavigation(card, I.KeyboardNavigationMode.Cycle);
        AutomationProperties.SetName(card, "Подтверждение отвязки телефона");
        var content = new C.StackPanel();
        card.Child = content;
        content.Children.Add(new C.Border
        {
            Width = 44, Height = 44, CornerRadius = new W.CornerRadius(14), Background = palette.Raised,
            HorizontalAlignment = W.HorizontalAlignment.Left,
            Child = Glyph("ic_unlink", palette.Accent, 23)
        });
        var heading = Text("Отвязать телефон?", 23, palette.Text, true);
        heading.Margin = new W.Thickness(0, 12, 0, 6);
        content.Children.Add(heading);
        var message = Text($"«{deviceTitle}» потеряет доступ к управлению воспроизведением. Для повторного подключения понадобится новая привязка.",
            14, palette.Muted);
        message.TextWrapping = W.TextWrapping.Wrap;
        content.Children.Add(message);

        var actions = new C.Primitives.UniformGrid { Rows = 1, Margin = new W.Thickness(0, 20, 0, 0) };
        var cancel = Button("Отмена", DismissPanel);
        cancel.Margin = new W.Thickness(0, 0, 4, 0);
        cancel.Loaded += (_, _) => { if (panel == Panel.Unpair && ReferenceEquals(drawer, card)) cancel.Focus(); };
        var confirm = Button("Отвязать", () =>
        {
            if (panel != Panel.Unpair) return;
            if (!identity.Forget(expectedPairKey: pairKey)) { DismissPanel(); return; }
            ReadDevice();
            ClosePanel();
        }, primary: true);
        confirm.Margin = new W.Thickness(4, 0, 0, 0);
        SpringPress(cancel);
        SpringPress(confirm);
        actions.Children.Add(cancel);
        actions.Children.Add(confirm);
        content.Children.Add(actions);

        if (animate && Motion)
        {
            card.RenderTransformOrigin = new W.Point(.5, .5);
            var scale = new M.ScaleTransform();
            card.RenderTransform = scale;
            scale.BeginAnimation(M.ScaleTransform.ScaleXProperty, Bounce(.95, 1, 260));
            scale.BeginAnimation(M.ScaleTransform.ScaleYProperty, Bounce(.95, 1, 260));
            card.BeginAnimation(OpacityProperty, Ease(.35, 1, 160));
        }
        return card;
    }

    private void Pairing(C.StackPanel content)
    {
        if (displayedAttempt is { } pending)
        {
            PairConfirmation(content, pending);
            return;
        }
        var note = Text(payload is null ? "Подключи ПК к домашней сети или обнови код." :
            "Открой приложение CueRelay на телефоне и отсканируй код. Код действует три минуты.",
            14, palette.Muted);
        note.TextWrapping = W.TextWrapping.Wrap;
        content.Children.Add(note);
        if (payload is not null)
        {
            using var generator = new QRCodeGenerator();
            using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
            using var png = new PngByteQRCode(data);
            var image = new C.Image { Source = Bitmap(png.GetGraphic(6)), Width = 209, Height = 209 };
            AutomationProperties.SetName(image, "QR-код привязки");
            M.RenderOptions.SetBitmapScalingMode(image, M.BitmapScalingMode.NearestNeighbor);
            content.Children.Add(new C.Border
            {
                Background = M.Brushes.White, CornerRadius = new W.CornerRadius(17), Width = 225, Height = 225,
                Margin = new W.Thickness(0, 14, 0, 12), Child = image,
            });
            content.Children.Add(Row("Скопировать временный код", "", false, () =>
            {
                if (payload is null) return;
                try
                {
                    var invite = new W.DataObject(W.DataFormats.UnicodeText, payload);
                    invite.SetData("ExcludeClipboardContentFromMonitorProcessing", new MemoryStream(new byte[4]));
                    W.Clipboard.SetDataObject(invite, copy: true);
                    pairClipboardSequence = GetClipboardSequenceNumber();
                    note.Text = "Временный код скопирован на 60 секунд.";
                    var clear = new DispatcherTimer { Interval = TimeSpan.FromMinutes(1) };
                    uint sequence = pairClipboardSequence;
                    clear.Tick += (_, _) =>
                    {
                        clear.Stop();
                        if (pairClipboardSequence == sequence) ClearPairClipboard();
                    };
                    clear.Start();
                }
                catch (ExternalException) { note.Text = "Не удалось скопировать код. Попробуй ещё раз."; }
            }));
        }
        content.Children.Add(Row("Обновить QR-код", "", false, StartPairing));
    }

    private void PairConfirmation(C.StackPanel content, Identity.PairAttempt attempt)
    {
        var deviceRow = new C.DockPanel();
        var icon = new C.Border { Width = 35, Height = 35, CornerRadius = new W.CornerRadius(11), Background = palette.Accent,
            Margin = new W.Thickness(0, 0, 11, 0), Child = Glyph(Phone, palette.OnAccent, 21) };
        C.DockPanel.SetDock(icon, C.Dock.Left);
        deviceRow.Children.Add(icon);
        var name = Text(attempt.Device, 17, palette.Text, true);
        name.TextWrapping = W.TextWrapping.Wrap;
        deviceRow.Children.Add(name);
        content.Children.Add(new C.Border { Child = deviceRow, Background = palette.Raised,
            CornerRadius = new W.CornerRadius(19), Padding = new W.Thickness(12, 10, 12, 10) });

        var code = new C.StackPanel();
        var caption = Text("Код проверки", 13, palette.Text, true);
        caption.TextAlignment = W.TextAlignment.Center;
        code.Children.Add(caption);
        var digits = Text(attempt.Check.Insert(3, " "), 34, palette.Text, true);
        digits.TextAlignment = W.TextAlignment.Center;
        code.Children.Add(digits);
        content.Children.Add(new C.Border { Child = code, Background = palette.CodeSurface, CornerRadius = new W.CornerRadius(19),
            Margin = new W.Thickness(0, 10, 0, 12), Padding = new W.Thickness(14, 11, 14, 10) });

        var message = Text(attempt.Status switch
        {
            "approved" => "На телефоне нажми «Код совпадает», если ещё не нажал, и дождись подключения.",
            "denied" => "Можно начать привязку заново.",
            _ => "Сверь цифры с кодом в CueRelay на телефоне."
        }, 15, palette.Muted);
        message.TextWrapping = W.TextWrapping.Wrap;
        content.Children.Add(message);

        if (attempt.Status == "pending")
        {
            var approve = Button("Это мой телефон", () =>
            {
                try { identity.DecidePair(attempt.Id, true); }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException)
                {
                    message.Text = "Windows не смогла сохранить привязку. Проверь доступ к папке данных CueRelay и повтори.";
                }
            }, primary: true);
            approve.Margin = new W.Thickness(0, 12, 0, 5);
            approve.MinHeight = 49;
            approve.Tag = new W.CornerRadius(17);
            var reject = Button("Это не мой телефон", () => identity.DecidePair(attempt.Id, false));
            reject.MinHeight = 46;
            reject.Tag = new W.CornerRadius(17);
            SpringPress(approve);
            SpringPress(reject);
            content.Children.Add(approve);
            content.Children.Add(reject);
        }
        else
        {
            var retry = Button("Новый QR-код", StartPairing);
            retry.Margin = new W.Thickness(0, 12, 0, 0);
            retry.MinHeight = 46;
            retry.Tag = new W.CornerRadius(17);
            content.Children.Add(retry);
        }
    }

    private void DismissPanel()
    {
        if (panel == Panel.Unpair && identity.Paired) OpenPanel(Panel.Device, animate: false);
        else if (panel is Panel.Appearance or Panel.Update or Panel.Diagnostics) OpenPanel(Panel.Settings, animate: false);
        else if (panel == Panel.Color) OpenPanel(Panel.Appearance, animate: false);
        else ClosePanel();
    }

    private void ClosePanel(bool animate = true)
    {
        bool centered = panel == Panel.Unpair;
        bool pairingConfirmation = panel == Panel.Pairing && displayedAttempt is not null;
        if (panel == Panel.Pairing) ClearInvitation();
        panel = Panel.None;
        unpairTarget = null;
        PaintDock();
        drawerScroll = null;
        settingsOffsets.Clear();
        var closing = overlay;
        var surface = drawer;
        overlay = null;
        drawer = null;
        if (closing is null) return;
        if (!animate || !Motion || surface is null) { root.Children.Remove(closing); return; }
        var owner = root;
        closing.IsHitTestVisible = false;
        if (!centered)
        {
            var motion = new M.TranslateTransform();
            surface.RenderTransform = motion;
            motion.BeginAnimation(pairingConfirmation ? M.TranslateTransform.YProperty : M.TranslateTransform.XProperty,
                Ease(0, 30, 145));
        }
        var fade = Ease(1, 0, 145);
        fade.Completed += (_, _) => owner.Children.Remove(closing);
        closing.BeginAnimation(OpacityProperty, fade);
    }

    private void ClearInvitation()
    {
        payload = null;
        ClearPairClipboard();
        try { identity.CancelPairing(); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            Diagnostics.Record("pairing", "cancel_failed", error);
        }
    }

    private void ClearPairClipboard()
    {
        try
        {
            if (pairClipboardSequence != 0 && pairClipboardSequence == GetClipboardSequenceNumber()) W.Clipboard.Clear();
        }
        catch (ExternalException) { }
        finally { pairClipboardSequence = 0; }
    }

    [DllImport("user32.dll")] private static extern uint GetClipboardSequenceNumber();

    public void CloseProgram() { exiting = true; Close(); }

    private static bool StartsAtLogin() =>
        Registry.GetValue(@"HKEY_CURRENT_USER\" + StartupKey, RunName, null) is string;

    private static void SetStartup(bool enabled)
    {
        using var run = Registry.CurrentUser.CreateSubKey(StartupKey);
        using var settings = Registry.CurrentUser.CreateSubKey(SettingsKey);
        if (enabled)
        {
            run.SetValue(RunName, $"\"{Environment.ProcessPath}\" --background");
            settings.DeleteValue("AutoStartDisabled", throwOnMissingValue: false);
        }
        else
        {
            run.DeleteValue(RunName, throwOnMissingValue: false);
            settings.SetValue("AutoStartDisabled", 1, RegistryValueKind.DWord);
        }
    }

    private Palette Theme()
    {
        var mode = identity.Theme;
        var systemLight = Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize",
            "AppsUseLightTheme", 1) is not 0;
        return Palette.Get(identity.Accent, mode == "dark" || mode == "system" && !systemLight);
    }

    private void ApplyTitleBar()
    {
        var handle = new WindowInteropHelper(this).Handle;
        int dark = palette.IsDark ? 1 : 0;
        if (handle == IntPtr.Zero) return;
        DwmSetWindowAttribute(handle, 20, ref dark, sizeof(int));
        int background = CaptionColor(palette.Background), foreground = CaptionColor(palette.Text);
        DwmSetWindowAttribute(handle, 34, ref background, sizeof(int));
        DwmSetWindowAttribute(handle, 35, ref background, sizeof(int));
        DwmSetWindowAttribute(handle, 36, ref foreground, sizeof(int));
    }

    private static int CaptionColor(M.Brush brush)
    {
        var color = ((M.SolidColorBrush)brush).Color;
        return color.R | color.G << 8 | color.B << 16;
    }

    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    private bool Motion => W.SystemParameters.ClientAreaAnimation;
    private static A.DoubleAnimation Ease(double from, double to, int milliseconds) =>
        new(from, to, new W.Duration(TimeSpan.FromMilliseconds(milliseconds))) { EasingFunction = new A.CubicEase { EasingMode = A.EasingMode.EaseOut } };

    private static A.DoubleAnimation Bounce(double from, double to, int milliseconds) =>
        new(from, to, new W.Duration(TimeSpan.FromMilliseconds(milliseconds)))
        {
            EasingFunction = new A.BackEase { EasingMode = A.EasingMode.EaseOut, Amplitude = .57 }
        };

    private B.BitmapSource? Capture()
    {
        if (root.ActualWidth <= 0 || root.ActualHeight <= 0) return null;
        var dpi = M.VisualTreeHelper.GetDpi(root);
        var image = new B.RenderTargetBitmap((int)Math.Ceiling(root.ActualWidth * dpi.DpiScaleX),
            (int)Math.Ceiling(root.ActualHeight * dpi.DpiScaleY), dpi.PixelsPerInchX, dpi.PixelsPerInchY, M.PixelFormats.Pbgra32);
        image.Render(root);
        image.Freeze();
        return image;
    }

    private void Reveal(B.BitmapSource previous, W.Point origin)
    {
        var target = root;
        var stage = new C.Grid();
        stage.Children.Add(new C.Image { Source = previous, Stretch = M.Stretch.Fill });
        var clip = new M.EllipseGeometry(origin, 0, 0);
        target.Clip = clip;
        Content = null;
        stage.Children.Add(target);
        Content = stage;
        stage.UpdateLayout();
        var radius = Math.Sqrt(Math.Pow(Math.Max(origin.X, stage.ActualWidth - origin.X), 2) +
            Math.Pow(Math.Max(origin.Y, stage.ActualHeight - origin.Y), 2));
        clip.BeginAnimation(M.EllipseGeometry.RadiusYProperty, Ease(0, radius, 520));
        var ending = Ease(0, radius, 520);
        ending.Completed += (_, _) =>
        {
            target.Clip = null;
            if (!ReferenceEquals(Content, stage)) return;
            stage.Children.Remove(target);
            Content = target;
        };
        clip.BeginAnimation(M.EllipseGeometry.RadiusXProperty, ending);
    }

    private static B.BitmapImage Bitmap(byte[] data)
    {
        using var stream = new MemoryStream(data);
        var image = new B.BitmapImage();
        image.BeginInit();
        image.CacheOption = B.BitmapCacheOption.OnLoad;
        image.StreamSource = stream;
        image.EndInit();
        image.Freeze();
        return image;
    }

    private C.Border Card(int padding) => new()
    {
        CornerRadius = new W.CornerRadius(25), Background = palette.Card, Padding = new W.Thickness(padding)
    };

    private void Section(C.StackPanel host, string label)
    {
        var header = Text(label, 13, palette.Accent, true);
        header.Margin = new W.Thickness(6, 12, 0, 7);
        host.Children.Add(header);
    }

    private C.Button Row(string title, string detail, bool selected, Action execute)
    {
        var button = Button(title, execute, selected);
        button.Margin = new W.Thickness(0, 0, 0, 7);
        button.MinHeight = detail.Length == 0 ? 42 : 57;
        var label = new C.StackPanel { Width = 260 };
        label.Children.Add(Text((selected ? "✓  " : "") + title, 14, selected ? palette.OnAccent : palette.Text, true));
        if (detail.Length != 0)
        {
            var second = Text(detail, 12, palette.Muted);
            second.TextWrapping = W.TextWrapping.Wrap;
            label.Children.Add(second);
        }
        button.Content = label;
        return button;
    }

    private C.Button Button(string name, Action execute, bool primary = false)
    {
        var button = new C.Button
        {
            Content = name, Style = buttonStyle, Tag = new W.CornerRadius(14), MinHeight = 38, Padding = new W.Thickness(11, 6, 11, 6),
            Background = primary ? palette.Accent : palette.Raised, Foreground = primary ? palette.OnAccent : palette.Text,
            FontSize = 14, FontWeight = W.FontWeights.SemiBold
        };
        AutomationProperties.SetName(button, name);
        button.Click += (_, _) => execute();
        return button;
    }

    private C.Button IconButton(string name, string geometry, int width, int height, bool primary, Action execute)
    {
        var button = Button(name, execute, primary);
        button.Content = Glyph(geometry, primary ? palette.OnAccent : palette.Text, primary ? 21 : 18);
        button.Width = width;
        button.Height = height;
        button.ToolTip = name;
        return button;
    }

    private static W.FrameworkElement Glyph(string icon, M.Brush fill, double size) => TempoIcons.Draw(icon, fill, size);

    private static C.TextBlock Text(string text, int size, M.Brush color, bool weight = false) => new()
    {
        Text = text, FontSize = size, Foreground = color, FontWeight = weight ? W.FontWeights.SemiBold : W.FontWeights.Normal,
        VerticalAlignment = W.VerticalAlignment.Center,
    };

    private enum Panel { None, Settings, Appearance, Pairing, Device, Unpair, Color, Sources, Update, Diagnostics }
    private sealed record Palette(bool IsDark, M.Brush Background, M.Brush Card, M.Brush Raised, M.Brush Text, M.Brush Muted,
        M.Brush Accent, M.Brush OnAccent)
    {
        public M.Brush GroupInset => Between(Card, Raised, .36);
        public M.Brush CodeSurface => Between(Raised, Accent, .16);
        public M.Brush DockSelection => Between(Raised, Accent, IsDark ? .20 : .23);
        private static M.Brush Between(M.Brush one, M.Brush other, double amount) =>
            Brush(Mix(Rgb(((M.SolidColorBrush)one).Color), Rgb(((M.SolidColorBrush)other).Color), amount));
        public static M.Brush Contrast(M.Color fill) => Luminance(Rgb(fill)) > .179 ? M.Brushes.Black : M.Brushes.White;
        private static int Rgb(M.Color color) => color.R << 16 | color.G << 8 | color.B;
        private static readonly Dictionary<string, int> Seeds = new()
        {
            ["green"] = 0x4a9a65, ["blue"] = 0x468cc6, ["violet"] = 0x865ac8, ["amber"] = 0xb48430
        };
        private static readonly Dictionary<(int, bool), Palette> Cache = new();
        public static int Seed(string key) => TryColor(key, out int rgb) ? rgb : Seeds.GetValueOrDefault(key, Seeds["green"]);
        public static Palette Get(string key, bool dark)
        {
            int seed = Seed(key);
            if (Cache.TryGetValue((seed, dark), out var palette)) return palette;
            var primary = Mix(seed, 0x15151a, .37);
            while (Luminance(primary) > .178) primary = Mix(primary, 0, .1);
            var value = dark
                ? new Palette(true, Brush(Mix(0x15151a, seed, .18)), Brush(Mix(0x202126, seed, .24)), Brush(Mix(0x303036, seed, .30)),
                    Brush(Mix(0xf8f8fb, seed, .055)), Brush(Mix(0xd0d0d7, seed, .17)), Brush(Mix(seed, 0xffffff, .59)), Brush(Mix(0x101015, seed, .26)))
                : new Palette(false, Brush(Mix(0xfcfcff, seed, .065)), Brush(Mix(0xf7f7fc, seed, .14)), Brush(Mix(0xf1f1f7, seed, .20)),
                    Brush(Mix(0x181822, seed, .075)), Brush(Mix(0x343442, seed, .23)), Brush(primary), M.Brushes.White);
            if (Cache.Count > 30) Cache.Clear();
            return Cache[(seed, dark)] = value;
        }
        private static int Mix(int a, int b, double amount)
        {
            int Channel(int shift) => (int)Math.Round(((a >> shift) & 255) * (1 - amount) + ((b >> shift) & 255) * amount,
                MidpointRounding.AwayFromZero);
            return Channel(16) << 16 | Channel(8) << 8 | Channel(0);
        }
        private static double Luminance(int value)
        {
            double Channel(int shift)
            {
                double component = ((value >> shift) & 255) / 255d;
                return component <= .04045 ? component / 12.92 : Math.Pow((component + .055) / 1.055, 2.4);
            }
            return .2126 * Channel(16) + .7152 * Channel(8) + .0722 * Channel(0);
        }
        public static M.Brush Brush(int rgb)
        {
            var value = new M.SolidColorBrush(M.Color.FromRgb((byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb));
            value.Freeze();
            return value;
        }
    }
}
