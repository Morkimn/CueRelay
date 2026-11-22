package com.codex.pocketplayer;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ViewFlipper;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int SCAN = 10, CAMERA = 11, EXPORT_LOG = 13;
    private static final int SHEET_SIDE_INSET = 18, SHEET_BOTTOM_INSET = 9;
    private static final int DOCK_HEIGHT = 64, DOCK_BOTTOM = 8;
    private static final int WIDGET_BUTTON_SIZE = 54, WIDGET_BUTTON_GAP = 24;
    private Float dockReleaseX;
    private enum Page {
        PLAYER("Плеер", R.drawable.ic_note),
        SOURCES("Источники", R.drawable.ic_source),
        SETTINGS("Настройки", R.drawable.ic_settings);

        final String label;
        final int icon;
        Page(String label, int icon) { this.label = label; this.icon = icon; }
    }
    private enum SettingsSheet { MAIN, APPEARANCE }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService updatesExecutor = Executors.newSingleThreadExecutor();
    private ReleaseUpdates.Release availableUpdate;
    private Button updateNotice, checkUpdateButton, installUpdateButton;
    private TextView updateDetails;
    private Dialog updateSheet;
    private String updateStatus = "", logExport;
    private long nextUpdateCheck;
    private boolean checkingUpdates, installingUpdate;
    private LinearLayout content, sourcesGrid;
    private TextView status, title, sourceLabel;
    private ScrollView pageScroll;
    private Page page = Page.PLAYER, previousPage;
    private String sourceSignature;
    private ImageView previous, next, artwork;
    private ViewFlipper toggle;
    private SeekBar seek;
    private LinearLayout timelinePanel;
    private TextView elapsed, duration;
    private Playback.Source seeking;
    private boolean dark, paired, pairing;
    private int background, panel, raised, text, dim, accent, onAccent;
    private String renderedTheme;
    private String renderedAccent;
    private Typeface regularFont, mediumFont, boldFont;
    private Playback last;
    private Dialog sheet;
    private FrameLayout activitySurface, sheetSurface;
    private SettingsSheet settingsSheet;
    private PairFlow activePair;
    private Dialog pairDialog;
    private Point tapOrigin, appearanceOrigin;
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 130); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        regularFont = getResources().getFont(R.font.google_sans_regular);
        mediumFont = getResources().getFont(R.font.google_sans_medium);
        boldFont = getResources().getFont(R.font.google_sans_bold);
        Diagnostics.record(this, "app", "started", null);
        render(null);
    }

    @Override public void onResume() {
        super.onResume();
        if (!BuildProfile.DEV && android.os.SystemClock.elapsedRealtime() >= nextUpdateCheck) checkUpdates();
        if (PhoneLink.connection(this) != null) {
            PhoneLink.start(this, page == Page.SETTINGS ? "phone-settings" : "refresh", null);
            if (page == Page.SETTINGS && sheet == null) render(null);
        }
        ui.post(ticker);
    }

    @Override public void onPause() { ui.removeCallbacks(ticker); super.onPause(); }
    @Override public void onDestroy() {
        if (sheet != null) sheet.dismiss();
        if (pairDialog != null) pairDialog.dismiss();
        if (activePair != null) activePair.cancelled = true;
        executor.shutdownNow();
        updatesExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        if ((PhoneLink.connection(this) != null) != paired || Appearance.dark(this) != dark ||
            !Appearance.theme(this).equals(renderedTheme) || !Appearance.accentKey(this).equals(renderedAccent)) {
            Point origin = appearanceOrigin;
            appearanceOrigin = null;
            render(origin);
            if (settingsSheet != null && sheetSurface != null) {
                View previous = sheetSurface.getChildAt(sheetSurface.getChildCount() - 1);
                int scrollY = previous.getTag() instanceof ScrollView ? ((ScrollView) previous.getTag()).getScrollY() : 0;
                boolean appearance = settingsSheet == SettingsSheet.APPEARANCE;
                FrameLayout next = sheetFrame(appearance ? "Оформление" : "Настройки",
                    appearance ? this::appearanceContent : this::settingsContent,
                    appearance ? this::settings : this::closePanel, appearance);
                ScrollView scroll = (ScrollView) next.getTag();
                reveal(sheetSurface, next, origin, -2);
                scroll.post(() -> scroll.scrollTo(0, scrollY));
            }
        }
        Playback state = PhoneLink.playback;
        if (state != last) updatePlayback(state);
        else if (paired && page == Page.PLAYER) {
            if (state == null) connectionStatus(null);
            refreshTimeline(state == null ? null : state.selected);
        }
    }

    @Override public void onBackPressed() {
        if (page != Page.PLAYER) navigate(Page.PLAYER);
        else super.onBackPressed();
    }

    private void navigate(Page target) {
        if (page == target) return;
        closePanel();
        previousPage = page;
        page = target;
        render(null);
    }

    private void render(Point origin) {
        dark = Appearance.dark(this);
        renderedTheme = Appearance.theme(this);
        renderedAccent = Appearance.accentKey(this);
        Pairing connection = PhoneLink.connection(this);
        paired = connection != null;
        if (!paired) page = Page.PLAYER;
        Page from = previousPage;
        previousPage = null;
        int scrollY = from == null && pageScroll != null ? pageScroll.getScrollY() : 0;
        Appearance.Palette colors = Appearance.palette(this);
        background = colors.background;
        panel = colors.panel;
        raised = colors.raised;
        text = colors.text;
        dim = colors.dim;
        accent = colors.accent;
        onAccent = colors.onAccent;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(background);
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        } else {
            root.setFitsSystemWindows(true);
            window.getDecorView().setSystemUiVisibility(dark ? 0 :
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        ScrollView scroll = new ScrollView(this);
        pageScroll = scroll;
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        content = column();
        int dockSpace = DOCK_HEIGHT + DOCK_BOTTOM;
        int bottomSpace = !paired ? 24 : page == Page.PLAYER
            ? dockSpace + WIDGET_BUTTON_GAP + WIDGET_BUTTON_SIZE + 14 : dockSpace + 19;
        content.setPadding(dp(18), dp(18), dp(18), dp(bottomSpace));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = row();
        boolean playerPage = page == Page.PLAYER;
        if (playerPage) {
            ImageView logo = icon(R.drawable.ic_logo, null, onAccent, 7);
            logo.setBackground(shape(accent, 13));
            logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams logoSize = new LinearLayout.LayoutParams(dp(42), dp(42));
            logoSize.setMarginEnd(dp(10));
            header.addView(logo, logoSize);
        }
        TextView name = label(playerPage ? BuildProfile.TITLE : page.label, playerPage ? 34 : 36, text, true);
        name.setLetterSpacing(-0.027f);
        header.addView(name, paired && playerPage ? new LinearLayout.LayoutParams(-2, -2)
            : new LinearLayout.LayoutParams(0, -2, 1));
        status = label("", 13, dim, true);
        status.setPadding(dp(11), dp(7), dp(11), dp(7));
        status.setBackground(shape(Appearance.mix(panel, accent, .10), 20));
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        if (paired && playerPage) {
            FrameLayout statusSlot = new FrameLayout(this);
            statusSlot.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
            LinearLayout.LayoutParams statusSize = new LinearLayout.LayoutParams(0, -2, 1);
            statusSize.setMarginStart(dp(10));
            header.addView(statusSlot, statusSize);
        }
        if (!paired) {
            ImageView settings = icon(R.drawable.ic_settings, "Настройки", text, 11);
            settings.setBackground(ripple(panel, 22));
            settings.setOnClickListener(v -> settings());
            header.addView(settings, new LinearLayout.LayoutParams(dp(46), dp(46)));
        }
        content.addView(header);
        updateNotice = button("", true, this::updatesPanel);
        LinearLayout.LayoutParams updateSize = new LinearLayout.LayoutParams(-1, dp(44));
        updateSize.topMargin = dp(12);
        content.addView(updateNotice, updateSize);
        paintUpdateNotice();

        if (!paired) {
            gap(content, 8);
            content.addView(status, new LinearLayout.LayoutParams(-2, -2));
        }
        gap(content, 17);
        if (!paired) {
            renderPairing();
        } else if (page == Page.PLAYER) {
            renderPlayer();
            ImageView add = icon(R.drawable.ic_add, "Добавить виджет", onAccent, 15);
            add.setBackground(ripple(accent, 20));
            add.setElevation(dp(4));
            add.setOnClickListener(v -> gallery());
            springPress(add);
            FrameLayout.LayoutParams anchor = new FrameLayout.LayoutParams(dp(WIDGET_BUTTON_SIZE), dp(WIDGET_BUTTON_SIZE),
                Gravity.END | Gravity.BOTTOM);
            anchor.setMarginEnd(dp(18));
            anchor.bottomMargin = dp(dockSpace + WIDGET_BUTTON_GAP);
            root.addView(add, anchor);
        } else if (page == Page.SOURCES) {
            sourceSignature = null;
            sourcesGrid = column();
            content.addView(sourcesGrid, new LinearLayout.LayoutParams(-1, -2));
        } else {
            settingsContent(content);
        }
        if (paired) addDock(root, from);
        if (activitySurface == null) {
            activitySurface = new FrameLayout(this);
            setContentView(activitySurface);
        }
        reveal(activitySurface, root, origin, -1);
        root.requestApplyInsets();
        if (scrollY != 0) scroll.post(() -> scroll.scrollTo(0, scrollY));
        if (from != null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            scroll.setAlpha(.40f);
            scroll.setTranslationX(dp(17) * Integer.signum(page.ordinal() - from.ordinal()));
            scroll.animate().alpha(1).translationX(0).setDuration(280)
                .setInterpolator(new PathInterpolator(.18f, .8f, .24f, 1f)).start();
        }
        updatePlayback(PhoneLink.playback);
    }

    private void addDock(FrameLayout root, Page from) {
        FrameLayout dock = new FrameLayout(this);
        dock.setBackground(shape(raised, 30));
        dock.setElevation(dp(6));
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-1, dp(DOCK_HEIGHT), Gravity.BOTTOM);
        position.setMargins(dp(15), 0, dp(15), dp(DOCK_BOTTOM));
        root.addView(dock, position);
        LinearLayout tabs = row();
        tabs.setPadding(dp(5), 0, dp(5), 0);
        dock.addView(tabs, new FrameLayout.LayoutParams(-1, -1));
        View indicator = new View(this);
        GradientDrawable highlight = shape(Appearance.mix(raised, accent, dark ? .20 : .23), 13);
        indicator.setBackground(highlight);
        dock.addView(indicator, 0, new FrameLayout.LayoutParams(0, dp(53), Gravity.LEFT | Gravity.CENTER_VERTICAL));
        for (Page destination : Page.values()) {
            boolean selected = destination == page;
            int tint = selected ? (dark ? accent : text) : dim;
            LinearLayout button = column();
            button.setGravity(Gravity.CENTER);
            button.setSelected(selected);
            button.setContentDescription(destination.label);
            boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            boolean first = destination.ordinal() == 0, last = destination.ordinal() == Page.values().length - 1;
            button.setBackground(ripple(corners(Color.TRANSPARENT, (rtl ? last : first) ? 24 : 13,
                (rtl ? first : last) ? 24 : 13, (rtl ? first : last) ? 24 : 13, (rtl ? last : first) ? 24 : 13)));
            button.addView(icon(destination.icon, null, tint, 0), new LinearLayout.LayoutParams(dp(21), dp(21)));
            gap(button, 2);
            TextView caption = label(destination.label, 12, tint, selected);
            caption.setGravity(Gravity.CENTER);
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            button.addView(caption, new LinearLayout.LayoutParams(-1, -2));
            button.setOnClickListener(view -> navigate(destination));
            tabs.addView(button, new LinearLayout.LayoutParams(0, dp(53), 1));
        }
        Float release = dockReleaseX;
        dockReleaseX = null;
        DockTouch touch = new DockTouch(tabs, indicator, highlight);
        for (int i = 0; i < tabs.getChildCount(); i++) tabs.getChildAt(i).setOnTouchListener(touch);
        tabs.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            View destination = tabs.getChildAt(page.ordinal());
            if (destination.getWidth() == 0) return;
            boolean animate = indicator.getLayoutParams().width == 0 && from != null &&
                android.animation.ValueAnimator.areAnimatorsEnabled();
            if (indicator.getLayoutParams().width != destination.getWidth()) {
                indicator.getLayoutParams().width = destination.getWidth();
                indicator.requestLayout();
            }
            if (animate) {
                float start = release == null ? tabs.getChildAt(from.ordinal()).getLeft() : release;
                touch.place(start);
                if (release != null) touch.lift(true);
                touch.settle(destination);
            } else if (right - left != oldRight - oldLeft) {
                touch.place(destination.getLeft());
            }
        });
    }

    private final class DockTouch implements View.OnTouchListener {
        private final LinearLayout tabs;
        private final View indicator;
        private final GradientDrawable highlight;
        private final int[] location = new int[2];
        private android.animation.ValueAnimator slide;
        private int pointer = -1, hovered = -1;

        DockTouch(LinearLayout tabs, View indicator, GradientDrawable highlight) {
            this.tabs = tabs;
            this.indicator = indicator;
            this.highlight = highlight;
        }

        void place(float x) {
            float start = tabs.getPaddingLeft(), end = tabs.getWidth() - tabs.getPaddingRight() - indicator.getLayoutParams().width;
            x = Math.max(start, Math.min(end, x));
            indicator.setTranslationX(x);
            float left = dp(13) + dp(11) * Math.max(0, 1 - (x - start) / dp(32));
            float right = dp(13) + dp(11) * Math.max(0, 1 - (end - x) / dp(32));
            highlight.setCornerRadii(new float[]{left, left, right, right, right, right, left, left});
        }

        void lift(boolean lifted) {
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
            indicator.animate().scaleX(lifted ? 1.055f : 1f).scaleY(lifted ? 1.15f : 1f)
                .setDuration(lifted ? 115 : 290)
                .setInterpolator(lifted ? new android.view.animation.DecelerateInterpolator() : new OvershootInterpolator(.65f)).start();
        }

        void settle(View target) {
            if (slide != null) slide.cancel();
            lift(false);
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { place(target.getLeft()); return; }
            slide = android.animation.ValueAnimator.ofFloat(indicator.getTranslationX(), target.getLeft());
            slide.setDuration(285);
            slide.setInterpolator(new OvershootInterpolator(.62f));
            slide.addUpdateListener(value -> place((Float) value.getAnimatedValue()));
            slide.start();
        }

        @Override public boolean onTouch(View view, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pointer = event.getPointerId(0);
                hovered = -1;
                tabs.getLocationOnScreen(location);
                tabs.requestDisallowInterceptTouchEvent(true);
                if (slide != null) slide.cancel();
                lift(true);
            }
            if (action == MotionEvent.ACTION_CANCEL ||
                (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer)) {
                pointer = -1;
                tintTabs(page.ordinal());
                settle(tabs.getChildAt(page.ordinal()));
                return true;
            }
            if (event.findPointerIndex(pointer) != 0) return true;
            float x = event.getRawX() - location[0];
            place(x - indicator.getLayoutParams().width / 2f);
            int closest = 0;
            for (int i = 1; i < tabs.getChildCount(); i++)
                if (distance(tabs.getChildAt(i), x) < distance(tabs.getChildAt(closest), x)) closest = i;
            if (closest != hovered) {
                tabs.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                hovered = closest;
                tintTabs(closest);
            }
            if (action == MotionEvent.ACTION_UP) {
                pointer = -1;
                if (closest == page.ordinal()) settle(tabs.getChildAt(closest));
                else {
                    dockReleaseX = indicator.getTranslationX();
                    tabs.getChildAt(closest).performClick();
                }
            }
            return true;
        }

        private float distance(View tab, float x) { return Math.abs(x - (tab.getLeft() + tab.getRight()) / 2f); }

        private void tintTabs(int chosen) {
            for (int i = 0; i < tabs.getChildCount(); i++) {
                LinearLayout tab = (LinearLayout) tabs.getChildAt(i);
                int tint = i == chosen ? (dark ? accent : text) : dim;
                ((ImageView)tab.getChildAt(0)).setColorFilter(tint);
                ((TextView)tab.getChildAt(2)).setTextColor(tint);
            }
        }
    }

    private void renderPairing() {
        LinearLayout card = card(content);
        ImageView logo = icon(R.drawable.ic_app, "CueRelay", 0, 0);
        card.addView(logo, new LinearLayout.LayoutParams(dp(62), dp(62)));
        gap(card, 18);
        card.addView(label("Подключи компьютер", 24, text, true));
        gap(card, 8);
        card.addView(label("Открой CueRelay в Windows и отсканируй QR-код. Подключи оба устройства к одной сети.", 16, dim, false));
        gap(card, 22);
        card.addView(button("Сканировать QR-код", true, this::requestScan), full(52));
        gap(card, 8);
        card.addView(button("Вставить код", false, this::pasteCode), full(48));
    }

    private void renderPlayer() {
        LinearLayout card = card(content);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        artwork = new ImageView(this);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] {Appearance.mix(raised, accent, .23), raised}));
        ((GradientDrawable) artwork.getBackground()).setCornerRadius(dp(27));
        artwork.setClipToOutline(true);
        artwork.setContentDescription("Обложка");
        card.addView(artwork, new LinearLayout.LayoutParams(dp(166), dp(166)));

        gap(card, 15);
        title = label("", 24, text, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);
        gap(card, 4);
        sourceLabel = label("", 14, dim, false);
        sourceLabel.setPadding(dp(10), dp(7), dp(10), dp(7));
        sourceLabel.setGravity(Gravity.CENTER);
        sourceLabel.setMaxLines(2);
        sourceLabel.setEllipsize(TextUtils.TruncateAt.END);
        sourceLabel.setOnClickListener(v -> sources());
        card.addView(sourceLabel);
        addTimeline(card);
        gap(card, 9);

        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        ImageView source = icon(R.drawable.ic_source, "Выбрать источник", text, 11);
        source.setBackground(ripple(raised, 19));
        source.setOnClickListener(v -> sources());
        controls.addView(source, new LinearLayout.LayoutParams(dp(44), dp(47)));
        previous = icon(R.drawable.ic_previous, "Предыдущий трек", text, 13);
        previous.setBackground(ripple(raised, 17));
        previous.setOnClickListener(v -> action("previous"));
        LinearLayout.LayoutParams previousSize = new LinearLayout.LayoutParams(dp(46), dp(48));
        previousSize.setMarginStart(dp(6));
        controls.addView(previous, previousSize);
        toggle = (ViewFlipper) getLayoutInflater().inflate(R.layout.widget_toggle, controls, false);
        GradientDrawable playShape = shape(accent, 26);
        playShape.setCornerRadii(new float[]{dp(27), dp(27), dp(20), dp(20), dp(26), dp(26), dp(20), dp(20)});
        toggle.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26000000), playShape, null));
        for (int id : new int[]{R.id.play_glyph, R.id.pause_glyph, R.id.retry_glyph}) {
            ImageView glyph = toggle.findViewById(id);
            glyph.setPadding(dp(14), dp(14), dp(14), dp(14));
            glyph.setColorFilter(onAccent);
        }
        toggle.setOnClickListener(v -> action("toggle"));
        springPress(toggle);
        LinearLayout.LayoutParams main = new LinearLayout.LayoutParams(dp(70), dp(61));
        main.setMargins(dp(7), 0, dp(7), 0);
        controls.addView(toggle, main);
        next = icon(R.drawable.ic_next, "Следующий трек", text, 13);
        next.setBackground(ripple(raised, 17));
        next.setOnClickListener(v -> action("next"));
        LinearLayout.LayoutParams nextSize = new LinearLayout.LayoutParams(dp(46), dp(48));
        nextSize.setMarginEnd(dp(6));
        controls.addView(next, nextSize);
        controls.addView(new View(this), new LinearLayout.LayoutParams(dp(44), dp(47)));
        card.addView(controls);
    }

    private void addTimeline(LinearLayout card) {
        timelinePanel = column();
        seeking = null;
        seek = (SeekBar) getLayoutInflater().inflate(R.layout.tempo_seek, timelinePanel, false);
        seek.setBackground(null);
        seek.setProgressDrawable(new WaveDrawable(accent, Appearance.mix(raised, dim, .35)));
        GradientDrawable thumb = shape(accent, 7);
        thumb.setSize(dp(12), dp(12));
        seek.setThumb(thumb);
        seek.setThumbOffset(dp(12) / 2);
        timelinePanel.addView(seek);
        LinearLayout times = row();
        elapsed = label("", 12, dim, false);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        duration = label("", 12, dim, false);
        times.addView(duration);
        times.setPadding(dp(7), 0, dp(7), 0);
        timelinePanel.addView(times);
        card.addView(timelinePanel, new LinearLayout.LayoutParams(-1, -2));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = selectedSource(); }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (seeking != null) sendSeek(seeking, bar.getProgress());
                seeking = null;
            }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                Playback.Source source = seeking == null ? selectedSource() : seeking;
                if (source == null || source.timeline == null) return;
                elapsed.setText(formatTime(source.timeline.target(progress / 1000.0)));
                if (seeking == null) sendSeek(source, progress);
            }
        });
    }

    private static Playback.Source selectedSource() {
        Playback state = PhoneLink.playback;
        return state == null ? null : state.selected;
    }

    private void sendSeek(Playback.Source source, int progress) {
        Playback.Source current = selectedSource();
        if (current == null || !source.id.equals(current.id) || !source.title.equals(current.title) ||
            current.timeline == null || !current.timeline.canSeek) return;
        PhoneLink.start(this, "seek", current.id + ":" + current.timeline.target(progress / 1000.0));
    }

    private void refreshTimeline(Playback.Source active) {
        Playback.Timeline timeline = active == null ? null : active.timeline;
        timelinePanel.setVisibility(timeline == null ? View.GONE : View.VISIBLE);
        if (timeline == null || seeking != null) return;
        long position = timeline.now();
        seek.setEnabled(timeline.canSeek);
        int nextPosition = (int)(1000.0 * position / timeline.duration);
        if (seek.getProgress() != nextPosition) seek.setProgress(nextPosition);
        elapsed.setText(formatTime(position));
        duration.setText(formatTime(timeline.duration));
    }

    private static String formatTime(long ms) {
        long seconds = ms / 1000;
        return seconds >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            : String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void updatePlayback(Playback value) {
        last = value;
        if (!paired) {
            status.setText(pairing ? "Подключаемся…" : "ПК не подключён");
            return;
        }
        connectionStatus(value);
        if (page == Page.SOURCES) renderSources(value);
        if (page != Page.PLAYER) return;
        Playback.Source active = value == null ? null : value.selected;
        refreshTimeline(active);
        boolean playing = active != null && active.playing;
        title.setText(value == null ? "ПК недоступен" : active == null ? "Нет воспроизведения" : active.displayTitle());
        sourceLabel.setText(active == null ? "" :
            active.artist.isEmpty() ? active.app + "  ›" : active.artist + "  ·  " + active.app + "  ›");
        sourceLabel.setEnabled(active != null);
        enabled(previous, active != null && active.previous);
        enabled(next, active != null && active.next);
        enabled(toggle, value == null || active != null && active.toggle);
        showPlayIcon(value == null ? 2 : playing ? 1 : 0);
        if (value != null && value.art != null && Appearance.showArtwork(this)) {
            artwork.setPadding(0, 0, 0, 0);
            artwork.clearColorFilter();
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.setImageBitmap(value.art);
        } else {
            artwork.setPadding(dp(39), dp(39), dp(39), dp(39));
            artwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
            artwork.setColorFilter(accent);
            artwork.setImageResource(R.drawable.ic_note);
        }
    }

    private void connectionStatus(Playback value) {
        Pairing connection = PhoneLink.connection(this);
        String computer = connection == null ? "" : connection.name;
        String state = pairing ? "Подключаемся…" : value != null ? "Подключён" :
            PhoneLink.accessDenied ? "Нужна новая привязка" : "Нет связи";
        String caption = state + (computer.isEmpty() ? "" : " · " + computer);
        if (!caption.contentEquals(status.getText())) status.setText(caption);
    }

    private void showPlayIcon(int frame) {
        if (toggle.getDisplayedChild() != frame) {
            boolean animate = android.animation.ValueAnimator.areAnimatorsEnabled() && hasWindowFocus();
            toggle.setInAnimation(animate ? android.view.animation.AnimationUtils.loadAnimation(this, R.anim.icon_in) : null);
            toggle.setOutAnimation(animate ? android.view.animation.AnimationUtils.loadAnimation(this, R.anim.icon_out) : null);
            toggle.setDisplayedChild(frame);
        }
        toggle.setContentDescription(frame == 2 ? "Восстановить связь" : frame == 1 ? "Пауза" : "Воспроизвести");
    }

    private void action(String command) {
        Playback now = PhoneLink.playback;
        if (now == null) PhoneLink.start(this, "refresh", null);
        else if (now.selected != null) {
            if ("toggle".equals(command)) {
                showPlayIcon(toggle.getDisplayedChild() == 1 ? 0 : 1);
                ui.postDelayed(() -> {
                    if (paired && PhoneLink.playback == now) updatePlayback(now);
                }, 550);
            }
            PhoneLink.start(this, command, now.selected.id);
        }
    }

    private void sources() {
        navigate(Page.SOURCES);
    }

    private void renderSources(Playback state) {
        String selected = Pairing.prefs(this).getString("source", "");
        StringBuilder signature = new StringBuilder(selected).append(state == null ? "|offline" : "|online");
        if (state != null) for (Playback.Source source : state.sources)
            signature.append('|').append(source.id).append('|').append(source.displayTitle());
        if (signature.toString().equals(sourceSignature)) return;
        sourceSignature = signature.toString();
        sourcesGrid.removeAllViews();
        LinearLayout automatic = settingsGroup(sourcesGrid);
        menuRow(automatic, R.drawable.ic_auto, "Автовыбор", "Текущая сессия Windows", selected.isEmpty(),
            () -> selectSource(""));
        gap(sourcesGrid, 15);
        if (state == null || state.sources.isEmpty()) {
            LinearLayout empty = card(sourcesGrid);
            empty.addView(label(state == null ? "Нет связи с компьютером" : "Нет активных источников", 19, text, true));
            gap(empty, 5);
            empty.addView(label(state == null ? "Проверь, что CueRelay запущен на ПК." :
                "Открой музыку или видео на компьютере.", 15, dim, false));
            return;
        }
        for (int i = 0; i < state.sources.size(); i += 2) {
            LinearLayout line = row();
            sourcesGrid.addView(line, new LinearLayout.LayoutParams(-1, -2));
            addSourceTile(line, state.sources.get(i), selected);
            View space = new View(this);
            line.addView(space, new LinearLayout.LayoutParams(dp(9), 1));
            if (i + 1 < state.sources.size()) addSourceTile(line, state.sources.get(i + 1), selected);
            else line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            gap(sourcesGrid, 9);
        }
    }

    private void addSourceTile(LinearLayout line, Playback.Source source, String selected) {
        boolean active = source.id.equals(selected);
        LinearLayout tile = column();
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(13), dp(12), dp(13), dp(12));
        tile.setBackground(ripple(active ? Appearance.mix(panel, accent, .20) : panel, 22));
        tile.setSelected(active);
        tile.addView(icon(active ? R.drawable.ic_check : R.drawable.ic_note, null, active ? accent : dim, 0),
            new LinearLayout.LayoutParams(dp(20), dp(20)));
        gap(tile, 8);
        TextView app = label(source.app, 16, text, true);
        app.setSingleLine();
        app.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(app);
        TextView title = label(source.displayTitle(), 13, dim, false);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(title);
        tile.setOnClickListener(v -> selectSource(source.id));
        springPress(tile);
        line.addView(tile, new LinearLayout.LayoutParams(0, dp(110), 1));
    }

    private void selectSource(String id) {
        if (PhoneLink.playback != null) PhoneLink.start(this, "source", id);
        closePanel();
    }

    private void gallery() {
        panel("Добавить виджет", list -> {
            menuRow(list, R.drawable.ic_note, "Квадрат · 2 × 2", "Обложка и три крупные кнопки", false,
                () -> pin(PlayerWidget.Format.SQUARE));
            menuRow(list, R.drawable.ic_play, "Строка · 4 × 1", "Обложка, название и управление", false,
                () -> pin(PlayerWidget.Format.COMPACT));
            menuRow(list, R.drawable.ic_source, "С обложкой · 4 × 2", "Управление и быстрый доступ к источникам", false,
                () -> pin(PlayerWidget.Format.MINI));
            menuRow(list, R.drawable.ic_previous, "Только кнопки", "Источники, предыдущий, пауза, следующий", false,
                () -> pin(PlayerWidget.Format.BUTTONS));
        });
    }

    private void pin(PlayerWidget.Format format) {
        closePanel();
        AppWidgetManager manager = getSystemService(AppWidgetManager.class);
        if (manager != null && manager.isRequestPinAppWidgetSupported() &&
            manager.requestPinAppWidget(new ComponentName(this, format.provider), null, null)) return;
        showError("Открой меню виджетов рабочего стола и найди CueRelay.");
    }

    private void settings() {
        if (paired) {
            closePanel();
            navigate(Page.SETTINGS);
            return;
        }
        panel("Настройки", this::settingsContent);
        settingsSheet = SettingsSheet.MAIN;
    }

    private void settingsContent(LinearLayout list) {
        menuRow(settingsGroup(list), R.drawable.ic_appearance, "Оформление", "Тема, цвет, плеер и виджеты",
            false, this::appearanceSettings);
        if (paired) {
            section(list, "Подключение");
            LinearLayout connection = settingsGroup(list);
            menuRow(connection, R.drawable.ic_signal, "Проверить связь", "Время ответа ПК", false,
                () -> PhoneLink.start(this, "check-link", null));
            menuRow(connection, R.drawable.ic_qr, "Перепривязать по QR", "", false, this::requestScan);
            menuRow(connection, R.drawable.ic_keyboard, "Ввести код привязки", "", false, this::pasteCode);
            menuRow(connection, R.drawable.ic_unlink, "Отвязать компьютер", "", false, this::forget);
            sectionPhoneControl(list);
        }
        section(list, "Приложение");
        LinearLayout app = settingsGroup(list);
        menuRow(app, R.drawable.ic_retry, "Обновления", "Версия " + ReleaseUpdates.currentVersion(this), false, this::updatesPanel);
        menuRow(app, R.drawable.ic_source, "Диагностика", "Просмотреть или сохранить журнал", false, this::diagnosticsPanel);
    }

    private void paintUpdateNotice() {
        updateNotice.setVisibility(availableUpdate == null ? View.GONE : View.VISIBLE);
        if (availableUpdate != null) updateNotice.setText("Доступно обновление " + availableUpdate.version);
    }

    private void checkUpdates() {
        if (checkingUpdates || installingUpdate) return;
        checkingUpdates = true;
        nextUpdateCheck = android.os.SystemClock.elapsedRealtime() + 6L * 60 * 60 * 1000;
        updateStatus = "Проверяем GitHub…";
        refreshUpdatePanel();
        updatesExecutor.execute(() -> {
            ReleaseUpdates.Release release = null;
            String status = "";
            boolean checked = false;
            try {
                release = ReleaseUpdates.check(this);
                checked = true;
                if (release == null) status = "Версия актуальна";
                Diagnostics.record(this, "updates", release == null ? "up_to_date" : "update_available", null);
            } catch (Exception error) {
                status = "Не удалось проверить обновления. Попробуй позже.";
                Diagnostics.record(this, "updates", "check_failed", error);
            }
            ReleaseUpdates.Release result = release;
            String message = status;
            boolean succeeded = checked;
            ui.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                checkingUpdates = false;
                if (succeeded) availableUpdate = result;
                updateStatus = message;
                paintUpdateNotice();
                refreshUpdatePanel();
            });
        });
    }

    private void refreshUpdatePanel() {
        if (sheet != null && sheet == updateSheet) paintUpdatePanel();
    }

    private void paintUpdatePanel() {
        ReleaseUpdates.Release release = availableUpdate;
        updateDetails.setText(!updateStatus.isEmpty() ? updateStatus :
            release == null ? "Проверка через GitHub Releases" : "Доступна версия " + release.version);
        updateDetails.setTextColor(release != null && updateStatus.isEmpty() ? accent : dim);
        enabled(checkUpdateButton, !checkingUpdates && !installingUpdate);
        enabled(installUpdateButton, !checkingUpdates && !installingUpdate);
        installUpdateButton.setVisibility(release == null ? View.GONE : View.VISIBLE);
    }

    private void updatesPanel() {
        if (BuildProfile.DEV) {
            panel("Обновления", body -> body.addView(label("Локальная сборка Dev", 15, text, true)),
                this::settings, true);
            return;
        }
        panel("Обновления", body -> {
            LinearLayout summary = column();
            summary.setPadding(dp(13), dp(12), dp(13), dp(12));
            summary.setBackground(shape(Appearance.mix(panel, raised, .72), 20));
            LinearLayout heading = row();
            ImageView logo = icon(R.drawable.ic_logo, null, onAccent, 8);
            logo.setBackground(shape(accent, 11));
            heading.addView(logo, new LinearLayout.LayoutParams(dp(35), dp(35)));
            TextView name = label("CueRelay", 16, text, true);
            name.setPadding(dp(10), 0, dp(7), 0);
            heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView version = label("v" + ReleaseUpdates.currentVersion(this), 12, text, true);
            version.setPadding(dp(10), dp(5), dp(10), dp(5));
            version.setBackground(shape(panel, 13));
            heading.addView(version);
            summary.addView(heading);
            gap(summary, 8);
            updateDetails = label("", 13, dim, false);
            summary.addView(updateDetails);
            body.addView(summary);
            gap(body, 12);
            installUpdateButton = button("Скачать и установить", true, () -> {
                if (availableUpdate != null) installUpdate(availableUpdate);
            });
            LinearLayout.LayoutParams installSize = full(49);
            installSize.bottomMargin = dp(6);
            body.addView(installUpdateButton, installSize);
            checkUpdateButton = button("Проверить обновления", false, this::checkUpdates);
            LinearLayout.LayoutParams checkSize = full(46);
            checkSize.bottomMargin = dp(SHEET_SIDE_INSET - SHEET_BOTTOM_INSET);
            body.addView(checkUpdateButton, checkSize);
            paintUpdatePanel();
        }, this::settings, true);
        updateSheet = sheet;
    }

    private void installUpdate(ReleaseUpdates.Release release) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            updateStatus = "Разреши установку для CueRelay в Android, затем снова нажми «Скачать и установить».";
            refreshUpdatePanel();
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(android.net.Uri.parse("package:" + getPackageName())));
            return;
        }
        installingUpdate = true;
        updateStatus = "Загружаем и проверяем APK…";
        refreshUpdatePanel();
        updatesExecutor.execute(() -> {
            Intent installer = null;
            try { installer = ReleaseUpdates.downloadInstaller(this, release); }
            catch (Exception error) { Diagnostics.record(this, "updates", "download_failed", error); }
            Intent verified = installer;
            ui.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                installingUpdate = false;
                updateStatus = "Не удалось подготовить обновление.";
                if (verified != null) {
                    try {
                        startActivity(verified);
                        updateStatus = "Подтверди установку в Android.";
                        Diagnostics.record(this, "updates", "installer_started", null);
                    } catch (RuntimeException error) { Diagnostics.record(this, "updates", "installer_failed", error); }
                }
                refreshUpdatePanel();
            });
        });
    }

    private void diagnosticsPanel() {
        panel("Диагностика", body -> {
            String log;
            try { log = Diagnostics.read(this); }
            catch (java.io.IOException error) { log = ""; }
            if (log.isEmpty()) {
                body.addView(label("Журнал пока пуст.", 15, dim, false));
                return;
            }
            final String snapshot = log;
            body.addView(button("Сохранить журнал", true, () -> {
                logExport = snapshot;
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "CueRelay-Diagnostics.jsonl"), EXPORT_LOG);
            }), full(48));
            gap(body, 10);
            String[] lines = log.split("\n");
            StringBuilder tail = new StringBuilder();
            for (int i = Math.max(0, lines.length - 80); i < lines.length; i++) tail.append(lines[i]).append('\n');
            TextView preview = label(tail.toString(), 12, dim, false);
            preview.setTextIsSelectable(true);
            body.addView(preview);
        }, this::settings, true);
    }

    private void appearanceSettings() {
        panel("Оформление", this::appearanceContent, this::settings, true);
        settingsSheet = SettingsSheet.APPEARANCE;
    }

    private void appearanceContent(LinearLayout list) {
        String mode = Appearance.theme(this);
        addThemePreview(list);
        section(list, "Тема");
        LinearLayout theme = settingsGroup(list);
        appearanceRow(theme, "system", "Как на устройстве", mode, R.drawable.ic_monitor);
        appearanceRow(theme, "light", "Светлая", mode, R.drawable.ic_sun);
        appearanceRow(theme, "dark", "Тёмная", mode, R.drawable.ic_moon);
        section(list, "Акцентный цвет");
        accentChoices(list);
        section(list, "Плеер и виджеты");
        LinearLayout playback = settingsGroup(list);
        settingSwitch(playback, R.drawable.ic_image, "Показывать обложки", "show-art", Appearance.showArtwork(this));
        settingSwitch(playback, R.drawable.ic_bell, "Управление в шторке и на экране блокировки", "system-media",
            Appearance.systemMedia(this));
    }

    private void addThemePreview(LinearLayout list) {
        View preview = new View(this) {
            private final android.graphics.Paint ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final WaveDrawable wave = new WaveDrawable(accent, dim);
            private final android.graphics.drawable.Drawable play = getDrawable(R.drawable.ic_play).mutate();
            @Override protected void onDraw(Canvas canvas) {
                float x = (getWidth() - dp(167)) / 2f;
                float y = (getHeight() - dp(94)) / 2f;
                box(canvas, x, y, 167, 94, 20, background);
                box(canvas, x + dp(10), y + dp(14), 147, 49, 16, raised);
                box(canvas, x + dp(19), y + dp(30), 58, 5, 3, text);
                box(canvas, x + dp(19), y + dp(41), 36, 4, 2, dim);
                box(canvas, x + dp(117), y + dp(23), 31, 31, 13, accent);
                play.setTint(onAccent);
                play.setBounds(Math.round(x) + dp(124), Math.round(y) + dp(30),
                    Math.round(x) + dp(141), Math.round(y) + dp(47));
                play.draw(canvas);
                wave.setBounds(Math.round(x) + dp(17), Math.round(y) + dp(72),
                    Math.round(x) + dp(150), Math.round(y) + dp(81));
                wave.setLevel(5800);
                wave.draw(canvas);
                ink.setColor(accent);
                canvas.drawCircle(x + dp(17) + dp(133) * .58f, y + dp(72) + dp(9) / 2f, dp(3), ink);
            }
            private void box(Canvas canvas, float x, float y, int w, int h, int r, int color) {
                ink.setColor(color);
                canvas.drawRoundRect(x, y, x + dp(w), y + dp(h), dp(r), dp(r), ink);
            }
        };
        preview.setBackground(shape(Appearance.mix(panel, raised, .36), 27));
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        list.addView(preview, full(116));
    }

    private void accentChoices(LinearLayout list) {
        LinearLayout colors = row();
        colors.setBackground(shape(panel, 25));
        colors.setPadding(dp(7), dp(10), dp(7), dp(10));
        for (Appearance.Colorway color : Appearance.Colorway.values()) {
            boolean selected = color.key.equals(renderedAccent);
            ImageView swatch = swatch(colors, color.label, color.seed, selected, 0);
            swatch.setOnClickListener(v -> applyAppearance(v, "accent", color.key, renderedAccent));
        }
        boolean custom = renderedAccent.startsWith("#");
        ImageView add = swatch(colors, custom ? "Свой цвет " + renderedAccent : "Свой цвет",
            custom ? Appearance.seed(renderedAccent) : raised, custom, R.drawable.ic_add);
        add.setOnClickListener(v -> customColor());
        list.addView(colors, new LinearLayout.LayoutParams(-1, -2));
    }

    private ImageView swatch(LinearLayout colors, String name, int fill, boolean selected, int glyph) {
        FrameLayout cell = new FrameLayout(this);
        int tint = Color.luminance(fill) > .179f ? Color.BLACK : Color.WHITE;
        ImageView button = icon(glyph, name, tint, 11);
        GradientDrawable disk = shape(fill, 20);
        GradientDrawable ring = shape(Color.TRANSPARENT, 24);
        if (selected) ring.setStroke(dp(2), accent);
        android.graphics.drawable.LayerDrawable swatch = new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[]{ring, new android.graphics.drawable.InsetDrawable(disk, dp(5))});
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x28ffffff : 0x23000000),
            swatch, shape(Color.WHITE, 24)));
        button.setSelected(selected);
        cell.addView(button, new FrameLayout.LayoutParams(dp(47), dp(47), Gravity.CENTER));
        colors.addView(cell, new LinearLayout.LayoutParams(0, dp(48), 1));
        return button;
    }

    private void sectionPhoneControl(LinearLayout list) {
        section(list, "Управление телефоном");
        LinearLayout group = settingsGroup(list);
        boolean granted = PhoneMedia.hasAccess(this);
        menuRow(group, R.drawable.ic_bell, "Доступ к уведомлениям",
            granted ? "Разрешён" : "Нужен для чтения медиасессий Android", granted, () ->
                startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        menuRow(group, R.drawable.ic_apps, "Видимые приложения", "Выбрать, какие плееры видны на ПК", false, this::phoneApps);
        settingSwitch(group, R.drawable.ic_monitor, "Разрешить управление с ПК", PhoneMedia.ENABLED, PhoneMedia.enabled(this));
        settingSwitch(group, R.drawable.ic_image, "Передавать обложки на ПК", PhoneMedia.COVERS,
            Pairing.prefs(this).getBoolean(PhoneMedia.COVERS, true));
    }

    private void phoneApps() {
        panel("Видимые приложения", list -> {
            java.util.Map<String, String> apps = PhoneMedia.visibleApps(this);
            if (apps.isEmpty()) {
                list.addView(label(PhoneMedia.hasAccess(this) ? "Запусти плеер на телефоне, чтобы он появился в списке." :
                    "Сначала разреши CueRelay доступ к уведомлениям.", 15, dim, false));
                return;
            }
            java.util.Set<String> blocked = new java.util.HashSet<>(Pairing.prefs(this).getStringSet(PhoneMedia.BLOCKED,
                java.util.Collections.emptySet()));
            for (java.util.Map.Entry<String, String> item : apps.entrySet()) {
                String key = item.getKey();
                menuRow(list, R.drawable.ic_note, item.getValue(), blocked.contains(key) ? "Скрыто на ПК" : "Видно на ПК",
                    !blocked.contains(key), () -> {
                        if (!blocked.add(key)) blocked.remove(key);
                        Pairing.prefs(this).edit().putStringSet(PhoneMedia.BLOCKED, new java.util.HashSet<>(blocked)).apply();
                        PhoneLink.start(this, "phone-settings", null);
                        phoneApps();
                    });
            }
        });
    }

    private LinearLayout settingsGroup(LinearLayout parent) {
        LinearLayout group = column();
        group.setPadding(dp(4), dp(4), dp(4), dp(4));
        group.setTag(Boolean.TRUE);
        group.setBackground(shape(Appearance.mix(panel, raised, .36), 25));
        parent.addView(group, new LinearLayout.LayoutParams(-1, -2));
        return group;
    }

    private void settingSwitch(LinearLayout list, int glyph, String label, String key, boolean value) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextColor(text);
        control.setTextSize(15);
        control.setTypeface(mediumFont);
        android.graphics.drawable.Drawable icon = getDrawable(glyph).mutate();
        icon.setTint(accent);
        android.graphics.drawable.LayerDrawable badge = new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[]{shape(raised, 12), new android.graphics.drawable.InsetDrawable(icon, dp(9))});
        badge.setBounds(0, 0, dp(37), dp(37));
        control.setCompoundDrawablesRelative(badge, null, null, null);
        control.setCompoundDrawablePadding(dp(11));
        android.graphics.drawable.StateListDrawable thumb = new android.graphics.drawable.StateListDrawable();
        thumb.addState(new int[]{android.R.attr.state_checked}, switchThumb(onAccent));
        thumb.addState(new int[0], switchThumb(dim));
        control.setThumbDrawable(thumb);
        control.setTrackDrawable(switchPiece(50, 30, accent, Appearance.mix(raised, dim, .2)));
        control.setThumbTintList(null);
        control.setTrackTintList(null);
        control.setShowText(false);
        control.setSplitTrack(false);
        control.setSwitchMinWidth(dp(50));
        control.setSwitchPadding(dp(12));
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setPadding(dp(8), dp(5), dp(10), dp(5));
        control.setChecked(value);
        control.setOnCheckedChangeListener((view, checked) -> {
            Pairing.prefs(this).edit().putBoolean(key, checked).apply();
            updatePlayback(PhoneLink.playback);
            PlayerWidget.updateAll(this);
            if (paired) PhoneLink.start(this,
                PhoneMedia.ENABLED.equals(key) || PhoneMedia.COVERS.equals(key) ? "phone-settings" : "surface", null);
            if (checked && PhoneMedia.ENABLED.equals(key) && !PhoneMedia.hasAccess(this))
                panel("Доступ к уведомлениям", body -> {
                    body.addView(label("Разреши CueRelay находить активные плееры Android, чтобы управлять ими с ПК.", 15, dim, false));
                    gap(body, 12);
                    body.addView(button("Открыть настройки", true, () -> {
                        closePanel();
                        startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    }), full(49));
                    gap(body, 5);
                    body.addView(button("Позже", false, this::closePanel), full(46));
                });
        });
        control.setMinHeight(dp(58));
        addSettingsRow(list, control, panel);
    }

    private android.graphics.drawable.Drawable switchThumb(int color) {
        return new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override public int getIntrinsicWidth() { return dp(30); }
            @Override public int getIntrinsicHeight() { return dp(30); }
            @Override public void draw(Canvas canvas) {
                paint.setColor(color);
                canvas.drawCircle(getBounds().exactCenterX(), getBounds().exactCenterY(), dp(12), paint);
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private android.graphics.drawable.StateListDrawable switchPiece(int width, int height, int checked, int unchecked) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        GradientDrawable on = shape(checked, height / 2);
        GradientDrawable off = shape(unchecked, height / 2);
        on.setSize(dp(width), dp(height));
        off.setSize(dp(width), dp(height));
        states.addState(new int[]{android.R.attr.state_checked}, on);
        states.addState(new int[0], off);
        return states;
    }

    private void addSettingsRow(LinearLayout list, View row, int color) {
        row.setTag(color);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        if (list.getChildCount() != 0) size.topMargin = dp(2);
        list.addView(row, size);
        for (int i = Math.max(0, list.getChildCount() - 2); i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            int top = i == 0 ? 21 : 8;
            int bottom = i == list.getChildCount() - 1 ? 21 : 8;
            child.setBackground(ripple(corners((Integer) child.getTag(), top, top, bottom, bottom)));
        }
    }

    private void appearanceRow(LinearLayout list, String key, String title, String active, int glyph) {
        LinearLayout row = menuRow(list, glyph, title, "", key.equals(active), R.drawable.ic_unselected, () -> { });
        row.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP)
                tapOrigin = new Point(Math.round(event.getRawX()), Math.round(event.getRawY()));
            return false;
        });
        row.setOnClickListener(view -> applyAppearance(view, "theme", key, active));
    }

    private void applyAppearance(View view, String type, String key, String active) {
        Point pressed = tapOrigin;
        tapOrigin = null;
        if (key.equals(active)) return;
        if (paired && PhoneLink.playback == null) {
            showError("Подключись к ПК, чтобы изменить оформление на обоих устройствах.");
            return;
        }
        if (pressed == null) {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            pressed = new Point(location[0] + view.getWidth() / 2, location[1] + view.getHeight() / 2);
        }
        appearanceOrigin = pressed;
        if (paired) PhoneLink.start(this, type, key);
        else Pairing.prefs(this).edit().putString(type, key).apply();
        refresh();
    }

    private void customColor() {
        panel("Свой цвет", list -> {
            int initial = Appearance.seed(renderedAccent);
            View swatch = new View(this);
            swatch.setBackground(shape(initial, 21));
            list.addView(swatch, full(58));
            gap(list, 10);
            EditText hex = inputField("#4A9A65");
            hex.setText(hex(initial));
            hex.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(7) });
            list.addView(hex, full(47));
            float[] hsv = new float[3];
            Color.colorToHSV(initial, hsv);
            for (int i = 0; i < hsv.length; i++) {
                final int component = i;
                String[] labels = {"Тон", "Насыщенность", "Яркость"};
                list.addView(label(labels[i], 13, dim, true));
                SeekBar slider = new SeekBar(this);
                slider.setContentDescription(labels[i]);
                slider.setMax(i == 0 ? 359 : 100);
                slider.setProgress(Math.round(i == 0 ? hsv[i] : hsv[i] * 100));
                slider.setProgressTintList(ColorStateList.valueOf(accent));
                slider.setThumbTintList(ColorStateList.valueOf(accent));
                slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onStartTrackingTouch(SeekBar bar) { }
                    @Override public void onStopTrackingTouch(SeekBar bar) { }
                    @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                        if (!fromUser) return;
                        hsv[component] = component == 0 ? value : value / 100f;
                        int chosen = Color.HSVToColor(hsv);
                        hex.setText(hex(chosen));
                        swatch.setBackground(shape(chosen, 21));
                    }
                });
                list.addView(slider, full(42));
            }
            Button apply = button("Применить", true, () -> { });
            apply.setOnClickListener(view -> {
                String key = hex.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);
                if (!key.matches("#[0-9A-F]{6}")) { hex.setError("Введи цвет в формате #RRGGBB"); return; }
                if (paired && PhoneLink.playback == null) {
                    showError("Подключись к компьютеру, чтобы синхронизировать цвет.");
                    return;
                }
                applyAppearance(view, "accent", key, renderedAccent);
                appearanceSettings();
            });
            list.addView(apply, full(47));
        }, this::appearanceSettings, true);
    }

    private static String hex(int color) {
        return String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private void section(LinearLayout list, String text) {
        TextView heading = label(text, 13, accent, true);
        heading.setPadding(dp(11), dp(15), 0, dp(5));
        list.addView(heading);
    }

    private void panel(String title, Consumer<LinearLayout> body) {
        panel(title, body, null);
    }

    private void panel(String title, Consumer<LinearLayout> body, Runnable cancel) {
        panel(title, body, cancel, false);
    }

    private void panel(String title, Consumer<LinearLayout> body, Runnable cancel, boolean back) {
        closePanel();
        Dialog dialog = new Dialog(this);
        sheet = dialog;
        settingsSheet = null;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sheetSurface = new FrameLayout(this);
        sheetSurface.addView(sheetFrame(title, body, cancel == null ? this::closePanel : cancel, back),
            new FrameLayout.LayoutParams(-1, -2));
        dialog.setContentView(sheetSurface);
        if (cancel != null) dialog.setOnCancelListener(ignored -> cancel.run());
        dialog.setOnDismissListener(ignored -> {
            if (sheet == dialog) {
                sheet = null;
                sheetSurface = null;
                settingsSheet = null;
            }
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(0.42f);
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) window.setWindowAnimations(R.style.SheetMotion);
        }
        dialog.show();
        if (window != null) window.setLayout(-1, -2);
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            View surface = sheetSurface;
            surface.setTranslationY(dp(72));
            surface.setScaleX(.965f);
            surface.setAlpha(.38f);
            surface.animate().translationY(0).scaleX(1).setDuration(355)
                .setInterpolator(new OvershootInterpolator(.9f)).start();
            android.animation.ObjectAnimator.ofFloat(surface, View.ALPHA, .38f, 1f).setDuration(175).start();
        }
    }

    private FrameLayout sheetFrame(String title, Consumer<LinearLayout> body, Runnable closeAction, boolean back) {
        LinearLayout frame = column();
        frame.setPadding(dp(SHEET_SIDE_INSET), dp(13), dp(SHEET_SIDE_INSET), dp(SHEET_BOTTOM_INSET));
        frame.setBackground(shape(panel, 27));
        View handle = new View(this);
        handle.setBackground(shape(Appearance.mix(raised, dim, .35), 2));
        FrameLayout dragArea = new FrameLayout(this);
        dragArea.addView(handle, new FrameLayout.LayoutParams(dp(28), dp(4), Gravity.CENTER));
        frame.addView(dragArea, full(17));
        dragArea.setOnTouchListener(new View.OnTouchListener() {
            private float start;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        frame.animate().cancel();
                        start = event.getRawY() - frame.getTranslationY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        frame.setTranslationY(Math.max(0, event.getRawY() - start));
                        break;
                    case MotionEvent.ACTION_UP:
                        if (frame.getTranslationY() > dp(42)) { closeAction.run(); break; }
                    case MotionEvent.ACTION_CANCEL:
                        frame.animate().translationY(0).setDuration(250).setInterpolator(new OvershootInterpolator(.8f)).start();
                        break;
                    default: break;
                }
                return true;
            }
        });
        LinearLayout top = row();
        ImageView close = icon(back ? R.drawable.ic_left : R.drawable.ic_close, back ? "Назад" : "Закрыть", text, 9);
        close.setBackground(ripple(raised, 17));
        close.setOnClickListener(v -> closeAction.run());
        LinearLayout.LayoutParams closeSize = new LinearLayout.LayoutParams(dp(39), dp(39));
        if (back) {
            closeSize.setMarginEnd(dp(11));
            top.addView(close, closeSize);
        }
        top.addView(label(title, 24, text, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (!back) top.addView(close, closeSize);
        frame.addView(top);
        gap(frame, 10);
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int width, int height) {
                int max = Math.min(MeasureSpec.getSize(height), (int) (getResources().getDisplayMetrics().heightPixels * 0.62f));
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        LinearLayout list = column();
        body.accept(list);
        scroll.addView(list);
        frame.addView(scroll);
        FrameLayout margin = new FrameLayout(this);
        margin.setTag(scroll);
        margin.setPadding(dp(9), 0, dp(9), dp(8));
        margin.addView(frame);
        return margin;
    }

    private void reveal(FrameLayout host, View next, Point origin, int height) {
        View old = host.getChildCount() == 0 ? null : host.getChildAt(host.getChildCount() - 1);
        if (origin == null || old == null || old.getWidth() == 0 || old.getHeight() == 0 ||
            !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            host.removeAllViews();
            host.addView(next, new FrameLayout.LayoutParams(-1, height));
            return;
        }
        Bitmap oldFrame = Bitmap.createBitmap(old.getWidth(), old.getHeight(), Bitmap.Config.ARGB_8888);
        old.draw(new Canvas(oldFrame));
        ImageView previous = new ImageView(this);
        previous.setImageBitmap(oldFrame);
        previous.setScaleType(ImageView.ScaleType.FIT_XY);
        host.removeAllViews();
        host.addView(previous, new FrameLayout.LayoutParams(old.getWidth(), old.getHeight()));
        next.setVisibility(View.INVISIBLE);
        host.addView(next, new FrameLayout.LayoutParams(-1, height));
        next.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (!next.isAttachedToWindow()) return true;
                next.getViewTreeObserver().removeOnPreDrawListener(this);
                int[] location = new int[2];
                next.getLocationOnScreen(location);
                int x = Math.max(0, Math.min(next.getWidth(), origin.x - location[0]));
                int y = Math.max(0, Math.min(next.getHeight(), origin.y - location[1]));
                float radius = (float) Math.hypot(Math.max(x, next.getWidth() - x), Math.max(y, next.getHeight() - y));
                next.setVisibility(View.VISIBLE);
                android.animation.Animator animation = ViewAnimationUtils.createCircularReveal(next, x, y, 0, radius);
                animation.setDuration(520);
                animation.setInterpolator(new android.view.animation.DecelerateInterpolator(1.7f));
                animation.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animator) { host.removeView(previous); }
                });
                animation.start();
                return true;
            }
        });
    }

    private LinearLayout menuRow(LinearLayout list, int glyph, String name, String details, boolean checked, Runnable click) {
        return menuRow(list, glyph, name, details, checked, R.drawable.ic_right, click);
    }

    private LinearLayout menuRow(LinearLayout list, int glyph, String name, String details, boolean checked,
                                 int unselectedGlyph, Runnable click) {
        LinearLayout row = row();
        boolean grouped = Boolean.TRUE.equals(list.getTag());
        row.setMinimumHeight(dp(grouped ? 58 : details.isEmpty() ? 48 : 62));
        row.setPadding(dp(8), dp(5), dp(10), dp(5));
        row.setBackground(ripple(checked ? raised : panel, 15));
        ImageView leading = icon(glyph, null, accent, 9);
        leading.setBackground(shape(raised, 12));
        row.addView(leading, new LinearLayout.LayoutParams(dp(37), dp(37)));
        LinearLayout labels = column();
        labels.setPadding(dp(11), 0, dp(6), 0);
        labels.addView(label(name, 15, text, true));
        if (!details.isEmpty()) {
            TextView subtitle = label(details, 12, dim, false);
            subtitle.setMaxLines(2);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(subtitle);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(icon(checked ? R.drawable.ic_selected : unselectedGlyph, null, checked ? accent : dim, 0),
            new LinearLayout.LayoutParams(dp(20), dp(20)));
        row.setSelected(checked);
        row.setOnClickListener(v -> click.run());
        if (grouped) addSettingsRow(list, row, checked ? raised : panel);
        else {
            list.addView(row);
            gap(list, 4);
        }
        return row;
    }

    private void closePanel() { if (sheet != null) sheet.dismiss(); }

    private void requestScan() {
        if (pairing) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openScanner();
        else requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA);
    }

    private void openScanner() { startActivityForResult(new Intent(this, ScanActivity.class), SCAN); }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == CAMERA && grants.length > 0) {
            if (grants[0] == PackageManager.PERMISSION_GRANTED) openScanner();
            else showError("Разреши доступ к камере в настройках или вставь код вручную.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SCAN && result == RESULT_OK && data != null) pair(data.getStringExtra("code"));
        if (request == EXPORT_LOG) {
            String snapshot = logExport;
            logExport = null;
            if (result != RESULT_OK || data == null || data.getData() == null || snapshot == null) return;
            try (java.io.OutputStream file = getContentResolver().openOutputStream(data.getData())) {
                if (file == null) throw new java.io.IOException("No document stream");
                file.write(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                android.widget.Toast.makeText(this, "Журнал сохранён", android.widget.Toast.LENGTH_SHORT).show();
            } catch (java.io.IOException | SecurityException error) {
                Diagnostics.record(this, "diagnostics", "export_failed", error);
                showError("Не удалось сохранить журнал.");
            }
        }
    }

    private void pasteCode() {
        if (pairing) return;
        panel("Код привязки", list -> {
            EditText field = inputField("Вставь код из CueRelay на ПК");
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
            field.setTypeface(regularFont);
            field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
            field.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(900) });
            list.addView(field, full(54));
            Runnable connect = () -> {
                String code = field.getText().toString().trim();
                if (code.isEmpty()) { field.setError("Вставь код привязки"); return; }
                pair(code);
            };
            field.setOnEditorActionListener((view, action, event) -> {
                if (action != android.view.inputmethod.EditorInfo.IME_ACTION_GO) return false;
                connect.run();
                return true;
            });
            gap(list, 12);
            list.addView(button("Подключить", true, connect), full(49));
            gap(list, 5);
            list.addView(button("Отмена", false, this::closePanel), full(46));
        });
        if (sheet != null && sheet.getWindow() != null)
            sheet.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private EditText inputField(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setTypeface(regularFont);
        field.setTextSize(15);
        field.setTextColor(text);
        field.setHintTextColor(dim);
        field.setHint(hint);
        field.setPadding(dp(15), 0, dp(15), 0);
        field.setBackground(shape(raised, 17));
        return field;
    }

    private void pair(String code) {
        if (pairing) return;
        final Pairing invitation;
        try { invitation = Pairing.fromQr(code); }
        catch (IllegalArgumentException error) { pairMessage("Открой новый QR-код в обновлённом CueRelay на Windows."); return; }
        closePanel();
        PairFlow flow = new PairFlow(invitation);
        activePair = flow;
        pairing = true;
        Diagnostics.record(this, "pairing", "invite_scanned", null);
        updatePlayback(null);
        executor.execute(() -> {
            try {
                DeskClient client = new DeskClient(invitation);
                JSONObject body = new JSONObject().put("code", invitation.secret).put("id", flow.id)
                    .put("device", PhoneMedia.deviceName(this));
                for (String address : invitation.addresses) {
                    if (flow.cancelled) return;
                    try {
                        JSONObject answer = client.json(address, "/pair/request", body, false, 2200);
                        String check = answer.getString("check");
                        if (!check.matches("[0-9]{6}")) throw new java.io.IOException();
                        ui.post(() -> confirmPair(flow, client, address, answer.optString("computer"), check));
                        return;
                    } catch (DeskClient.Status status) {
                        Diagnostics.record(this, "pairing", status.code == 403 ? "invitation_rejected" : "http_error", status);
                        if (status.code == 403) { pairFailed(flow, "Этот QR-код больше не действует. Обнови его на ПК."); return; }
                    } catch (javax.net.ssl.SSLException error) {
                        Diagnostics.record(this, "pairing", "tls_failed", error);
                    } catch (java.net.SocketTimeoutException error) {
                        Diagnostics.record(this, "pairing", "peer_timeout", error);
                    } catch (Exception error) {
                        Diagnostics.record(this, "pairing", "peer_request_failed", error);
                    }
                }
                pairFailed(flow, "Не удалось связаться с ПК. В домашней сети проверь частный профиль Windows и подключение телефона к основной сети Wi-Fi. Затем обнови QR-код.");
            } catch (Exception ignored) { pairFailed(flow, "Не удалось создать защищённое подключение."); }
        });
    }

    private void confirmPair(PairFlow flow, DeskClient client, String address, String computer, String check) {
        if (!isCurrentPair(flow)) return;
        pairSheet(flow, computer, check, () -> {
            pairSheet(flow, computer, check, null);
            executor.execute(() -> waitForPair(flow, client, address));
        });
    }

    private void pairSheet(PairFlow flow, String computer, String code, Runnable confirm) {
        boolean checking = confirm != null;
        panel(checking ? "Это твой компьютер?" : "Подтверди на ПК", list -> {
            list.addView(deviceCard(computer, R.drawable.ic_logo));
            gap(list, 10);
            LinearLayout check = column();
            check.setGravity(Gravity.CENTER_HORIZONTAL);
            check.setPadding(dp(14), dp(11), dp(14), dp(10));
            check.setBackground(shape(Appearance.mix(raised, accent, .16), 19));
            TextView codeLabel = label("Код проверки", 13, text, true);
            codeLabel.setGravity(Gravity.CENTER);
            check.addView(codeLabel);
            TextView digits = label(code.substring(0, 3) + " " + code.substring(3), 34, text, true);
            digits.setTypeface(boldFont);
            digits.setFontFeatureSettings("tnum");
            digits.setGravity(Gravity.CENTER);
            digits.setLetterSpacing(.035f);
            digits.setContentDescription("Код проверки " + code);
            check.addView(digits);
            list.addView(check);
            gap(list, 12);
            list.addView(label(checking ? "Сверь цифры и нажми «Код совпадает». На ПК нажми «Это мой телефон»." :
                "Нажми «Это мой телефон» на компьютере, если ещё не нажал. Дождись подключения.", 15, dim, false));
            gap(list, 12);
            if (checking) {
                list.addView(button("Код совпадает", true, confirm), full(49));
                gap(list, 5);
            }
            list.addView(button(checking ? "Это не мой ПК" : "Отмена", false, () -> cancelPair(flow)), full(46));
        }, () -> cancelPair(flow));
        pairDialog = sheet;
    }

    private LinearLayout deviceCard(String name, int deviceIcon) {
        LinearLayout machine = row();
        machine.setPadding(dp(12), dp(10), dp(12), dp(10));
        machine.setBackground(shape(raised, 19));
        ImageView glyph = icon(deviceIcon, null, onAccent, 7);
        glyph.setBackground(shape(accent, 11));
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(35), dp(35));
        iconSize.setMarginEnd(dp(11));
        machine.addView(glyph, iconSize);
        TextView deviceName = label(name.isEmpty() ? "Компьютер" : name, 17, text, true);
        deviceName.setMaxLines(2);
        deviceName.setEllipsize(TextUtils.TruncateAt.END);
        machine.addView(deviceName, new LinearLayout.LayoutParams(0, -2, 1));
        return machine;
    }

    private void waitForPair(PairFlow flow, DeskClient client, String address) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 180_000;
        java.util.List<String> addresses = new java.util.ArrayList<>(flow.invitation.addresses);
        addresses.remove(address);
        addresses.add(0, address);
        boolean reachedPc = false;
        try {
            JSONObject request = new JSONObject().put("id", flow.id);
            while (!flow.cancelled && android.os.SystemClock.elapsedRealtime() < deadline) {
                JSONObject answer = null;
                String respondingAddress = null;
                for (String peer : addresses) {
                    if (flow.cancelled || android.os.SystemClock.elapsedRealtime() >= deadline) break;
                    try {
                        answer = client.json(peer, "/pair/result", request, false, 2500);
                        respondingAddress = peer;
                        break;
                    } catch (DeskClient.Status error) {
                        if (error.code == 403) {
                            pairFailed(flow, "Запрос закрыт или истёк. Открой новый QR-код.");
                            return;
                        }
                    } catch (java.io.IOException ignored) {
                        // The QR can contain several LAN addresses for the same pinned computer.
                    }
                }
                if (respondingAddress != null) {
                    reachedPc = true;
                    addresses.remove(respondingAddress);
                    addresses.add(0, respondingAddress);
                }
                if (answer != null && "denied".equals(answer.optString("status"))) {
                    pairFailed(flow, "Запрос отклонён на компьютере.");
                    return;
                }
                if (answer != null && "approved".equals(answer.optString("status"))) {
                    Pairing invitation = flow.invitation;
                    Pairing saved = new Pairing(invitation.fingerprint, invitation.addresses, invitation.port,
                        answer.getString("token"), answer.getString("computer"));
                    if (!saved.secret.matches("[A-Za-z0-9_-]{43}")) throw new java.io.IOException();
                    synchronized (flow) {
                        if (flow.cancelled) return;
                        Pairing previous = PhoneLink.connection(this);
                        boolean differentComputer = previous == null || !previous.fingerprint.equals(saved.fingerprint);
                        try {
                            saved.save(this, answer.getString("theme"), answer.optString("accent", "green"), differentComputer);
                        } catch (Exception error) {
                            pairFailed(flow, "Не удалось сохранить защищённую привязку на телефоне. Разблокируй телефон и попробуй снова.");
                            return;
                        }
                        saved.rememberAddress(this, respondingAddress);
                        PhoneLink.setConnection(this, saved);
                        flow.saved = true;
                        Diagnostics.record(this, "pairing", "credentials_saved", null);
                    }
                    ui.post(() -> {
                        if (!isCurrentPair(flow)) return;
                        cancelPair(flow);
                        render(null);
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED)
                            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 12);
                    });
                    return;
                }
                Thread.sleep(800);
            }
            if (!flow.cancelled) pairFailed(flow, reachedPc ? "Время подтверждения истекло. Открой новый QR-код." :
                "Во время подтверждения телефон потерял связь с компьютером. Проверь локальную сеть и открой новый QR-код.");
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        catch (Exception error) { pairFailed(flow, "Не удалось завершить привязку."); }
    }

    private boolean isCurrentPair(PairFlow flow) {
        return activePair == flow && !flow.cancelled && !isFinishing() && !isDestroyed();
    }

    private void cancelPair(PairFlow flow) {
        if (activePair != flow) return;
        synchronized (flow) { flow.cancelled = true; }
        activePair = null;
        if (pairDialog != null) { pairDialog.dismiss(); pairDialog = null; }
        pairing = false;
        updatePlayback(PhoneLink.playback);
        if (flow.saved) PhoneLink.start(this, "refresh", null);
    }

    private void pairFailed(PairFlow flow, String message) {
        Diagnostics.record(this, "pairing", "failed", null);
        ui.post(() -> {
            if (!isCurrentPair(flow)) return;
            cancelPair(flow);
            pairMessage(message);
        });
    }

    private void pairMessage(String message) {
        panel("Привязка не завершена", list -> {
            list.addView(label(message, 16, dim, false));
            gap(list, 14);
            list.addView(button("Понятно", true, this::closePanel), full(49));
        });
    }

    private static final class PairFlow {
        final Pairing invitation;
        final String id = java.util.UUID.randomUUID().toString().replace("-", "");
        volatile boolean cancelled;
        boolean saved;
        PairFlow(Pairing invitation) { this.invitation = invitation; }
    }

    private void forget() {
        Pairing connection = PhoneLink.connection(this);
        if (connection == null) return;
        panel("Отвязать компьютер?", list -> {
            list.addView(deviceCard(connection.name, R.drawable.ic_unlink));
            gap(list, 12);
            boolean online = PhoneLink.playback != null;
            list.addView(label(online ? "Чтобы подключиться снова, понадобится новая привязка." :
                "Чтобы отозвать доступ на ПК, также нажми «Отвязать телефон» в Windows.", 15, dim, false));
            gap(list, 14);
            list.addView(button("Отвязать", true, () -> {
                closePanel();
                if (PhoneLink.connection(this) == connection) PhoneLink.start(this, "forget", null);
            }), full(49));
            gap(list, 5);
            list.addView(button("Отмена", false, this::closePanel), full(46));
        });
    }


    private void showError(String message) {
        boolean returnToAppearance = settingsSheet == SettingsSheet.APPEARANCE;
        panel("CueRelay", list -> {
            list.addView(label(message, 15, dim, false));
            gap(list, 12);
            list.addView(button("Понятно", true, returnToAppearance ? this::appearanceSettings : this::closePanel), full(49));
        }, returnToAppearance ? this::appearanceSettings : null);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private void gap(LinearLayout container, int size) { container.addView(new View(this), full(size)); }
    private LinearLayout.LayoutParams full(int height) { return new LinearLayout.LayoutParams(-1, dp(height)); }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        card.setBackground(shape(panel, 29));
        card.setPadding(dp(18), dp(19), dp(18), dp(18));
        parent.addView(card);
        return card;
    }
    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(bold ? mediumFont : regularFont);
        return view;
    }
    private ImageView icon(int resource, String description, int tint, int padding) {
        ImageView view = new ImageView(this);
        if (resource != 0) view.setImageResource(resource);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        if (tint != 0) view.setColorFilter(tint);
        if (description != null) view.setContentDescription(description);
        return view;
    }
    private Button button(String value, boolean primary, Runnable click) {
        Button view = new Button(this);
        view.setTypeface(mediumFont);
        view.setText(value);
        view.setAllCaps(false);
        view.setTextSize(15);
        view.setTextColor(primary ? onAccent : text);
        view.setBackground(ripple(primary ? accent : raised, 15));
        view.setOnClickListener(v -> click.run());
        return view;
    }
    private void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : .45f);
    }
    private void springPress(View button) {
        button.setOnTouchListener((view, event) -> {
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(.93f).scaleY(.93f).setDuration(85)
                        .setInterpolator(new PathInterpolator(.2f, .8f, .2f, 1)).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1).scaleY(1).setDuration(260)
                        .setInterpolator(new OvershootInterpolator(1.05f)).start();
                    break;
                default: break;
            }
            return false;
        });
    }
    private RippleDrawable ripple(int color, int radius) {
        return ripple(shape(color, radius));
    }
    private RippleDrawable ripple(GradientDrawable surface) {
        GradientDrawable mask = (GradientDrawable) surface.getConstantState().newDrawable().mutate();
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x28ffffff : 0x23000000), surface, mask);
    }
    private GradientDrawable corners(int color, int topLeft, int topRight, int bottomRight, int bottomLeft) {
        GradientDrawable surface = shape(color, 0);
        surface.setCornerRadii(new float[]{dp(topLeft), dp(topLeft), dp(topRight), dp(topRight),
            dp(bottomRight), dp(bottomRight), dp(bottomLeft), dp(bottomLeft)});
        return surface;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
}
