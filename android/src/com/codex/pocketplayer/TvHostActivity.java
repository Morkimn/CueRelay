package com.codex.pocketplayer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class TvHostActivity extends Activity {
    private enum Screen { CONNECTION, DEVICES, SETTINGS, APPEARANCE, LANGUAGE, CONTROLS, DIAGNOSTICS }
    private static final int EXPORT_LOG = 71;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Appearance.Palette colors;
    private Typeface regular, medium;
    private final Map<Screen, ScrollView> screens = new EnumMap<>(Screen.class);
    private FrameLayout stage;
    private Button connectionTab, settingsTab, remotesTab, permission, pair, stop;
    private LinearLayout qrIntro, confirmation;
    private LinearLayout remoteList;
    private Screen currentScreen;
    private Map<String, String> visibleRemotes;
    private String removingRemote;
    private String logExport;
    private TextView status, controllers, qrTitle, qrHint, navigationStatus;
    private ImageView code;
    private String shown;
    private boolean issueWhenReady;
    private final Runnable update = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        if (!getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
            finish();
            return;
        }
        regular = getResources().getFont(R.font.google_sans_regular);
        medium = getResources().getFont(R.font.google_sans_medium);
        stage = new FrameLayout(this);
        setContentView(stage);
        if (bundle != null) logExport = bundle.getBoolean("exporting") ? readLog() : null;
        buildUi(bundle == null ? Screen.CONNECTION : Screen.values()[bundle.getInt("screen")], null, null);
    }

    private void buildUi(Screen screen, Point origin, String focusTag) {
        colors = Appearance.palette(this);
        visibleRemotes = null;
        removingRemote = null;
        shown = null;
        screens.clear();
        currentScreen = null;
        boolean compact = getResources().getConfiguration().screenWidthDp < 800;
        LinearLayout shell = new LinearLayout(this);
        shell.setPadding(dp(compact ? 24 : 38), dp(27), dp(compact ? 24 : 38), dp(24));
        shell.setBackgroundColor(colors.background);

        LinearLayout rail = column();
        LinearLayout.LayoutParams railSize = new LinearLayout.LayoutParams(dp(compact ? 142 : 178), -1);
        railSize.rightMargin = dp(compact ? 18 : 30);
        shell.addView(rail, railSize);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = icon(R.drawable.ic_logo, colors.onAccent, 8);
        logo.setBackground(shape(colors.accent, 12));
        brand.addView(logo, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView wordmark = label("CueRelay", compact ? 17 : 20, colors.text, true);
        wordmark.setPadding(dp(9), 0, 0, 0);
        brand.addView(wordmark);
        connectionTab = button(rail, "Подключение", R.drawable.ic_tv, () -> showPage(Screen.CONNECTION));
        remotesTab = button(rail, "Устройства", R.drawable.ic_phone, () -> showPage(Screen.DEVICES));
        settingsTab = button(rail, "Настройки", R.drawable.ic_settings, () -> showPage(Screen.SETTINGS));
        rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        rail.addView(brand);

        FrameLayout pages = new FrameLayout(this);
        shell.addView(pages, new LinearLayout.LayoutParams(0, -1, 1));
        for (Screen destination : Screen.values()) screens.put(destination, page(pages));
        buildConnection(body(Screen.CONNECTION), compact);
        buildSettings(body(Screen.SETTINGS));
        buildRemotes(body(Screen.DEVICES));
        buildControls(body(Screen.CONTROLS));
        buildAppearance(body(Screen.APPEARANCE));
        buildLanguage(body(Screen.LANGUAGE));
        refresh();
        CueReveal.replace(stage, shell, origin, -1);
        showPage(screen);
        if (focusTag != null) shell.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (!shell.isShown()) return true;
                shell.getViewTreeObserver().removeOnPreDrawListener(this);
                View focus = shell.findViewWithTag(focusTag);
                if (focus != null) focus.requestFocus();
                return true;
            }
        });
    }

    private LinearLayout body(Screen screen) { return (LinearLayout) screens.get(screen).getChildAt(0); }

    private ScrollView page(FrameLayout pages) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ScrollView.FOCUS_AFTER_DESCENDANTS);
        LinearLayout content = column();
        content.setPadding(dp(4), dp(1), dp(4), dp(6));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        pages.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        return scroll;
    }

    private void buildConnection(LinearLayout page, boolean compact) {
        page.addView(label(tr("Пульт для телевизора"), compact ? 28 : 36, colors.text, true));
        String deviceName = PhoneMedia.deviceName(this);
        if (!deviceName.equals(tr("Телевизор"))) page.addView(label(deviceName, 16, colors.dim, false));
        spacer(page, 17);
        LinearLayout grid = new LinearLayout(this);
        page.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout controls = card(19);
        grid.addView(controls, new LinearLayout.LayoutParams(0, -1, compact ? 6 : 5));

        controllers = label("", 13, colors.accent, true);
        controllers.setPadding(dp(10), dp(4), dp(10), dp(4));
        controllers.setBackground(shape(Appearance.mix(colors.panel, colors.accent, .13), 14));
        controls.addView(controllers, new LinearLayout.LayoutParams(-2, -2));
        spacer(controls, 13);
        controls.addView(label(tr("Управление с телефона"), 21, colors.text, true));
        status = label("", 15, colors.dim, false);
        status.setPadding(0, dp(4), 0, dp(12));
        controls.addView(status);
        permission = button(controls, "Доступ к уведомлениям", R.drawable.ic_bell,
            () -> openSystem(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        pair = button(controls, "Добавить пульт по QR", R.drawable.ic_qr, () -> {
            Pairing.prefs(this).edit().putBoolean(TvHostService.ENABLED, true).apply();
            issueWhenReady = true;
            if (TvHostService.instance == null) TvHostService.start(this);
            refresh();
        });
        stop = button(controls, "Отключить все пульты", R.drawable.ic_unlink, () -> {
            confirmation.setVisibility(View.VISIBLE);
            enter(confirmation, 8, 0);
            confirmation.getChildAt(1).requestFocus();
        });
        confirmation = column();
        confirmation.setVisibility(View.GONE);
        controls.addView(confirmation);
        confirmation.addView(label(tr("Отключить все пульты?"), 16, colors.text, true));
        button(confirmation, "Отмена", R.drawable.ic_close, this::cancelDisconnect);
        button(confirmation, "Отключить", R.drawable.ic_unlink, this::disconnect);

        LinearLayout ticket = card(compact ? 12 : 17);
        ticket.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
            Appearance.mix(colors.panel, colors.accent, .20), colors.panel});
        gradient.setCornerRadius(dp(24));
        ticket.setBackground(gradient);
        LinearLayout.LayoutParams ticketSize = new LinearLayout.LayoutParams(0, -1, 4);
        ticketSize.leftMargin = dp(12);
        grid.addView(ticket, ticketSize);

        qrTitle = label(tr("Привязка по QR"), 18, colors.text, true);
        qrTitle.setGravity(Gravity.CENTER);
        ticket.addView(qrTitle, new LinearLayout.LayoutParams(-1, -2));
        spacer(ticket, 12);
        int side = compact ? 126 : 160;
        qrIntro = column();
        qrIntro.setGravity(Gravity.CENTER);
        ImageView link = icon(R.drawable.ic_qr, colors.accent, compact ? 18 : 24);
        link.setBackground(shape(Appearance.mix(colors.panel, colors.accent, .16), 24));
        qrIntro.addView(link, new LinearLayout.LayoutParams(dp(compact ? 68 : 90), dp(compact ? 68 : 90)));
        ticket.addView(qrIntro, new LinearLayout.LayoutParams(-1, dp(side)));
        code = new ImageView(this);
        code.setContentDescription(tr("QR-код привязки"));
        code.setVisibility(View.GONE);
        code.setPadding(dp(6), dp(6), dp(6), dp(6));
        code.setBackground(shape(Color.WHITE, 10));
        ticket.addView(code, new LinearLayout.LayoutParams(dp(side), dp(side)));
        qrHint = label("", 13, colors.dim, false);
        qrHint.setGravity(Gravity.CENTER);
        qrHint.setPadding(0, dp(10), 0, 0);
        ticket.addView(qrHint, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildSettings(LinearLayout page) {
        heading(page, "Настройки телевизора");
        LinearLayout preferences = card(16);
        page.addView(preferences);
        Button appearance = settingsLink(preferences, "Оформление", R.drawable.ic_appearance, Screen.APPEARANCE);
        settingsTab.setNextFocusRightId(appearance.getId());
        settingsLink(preferences, "Язык", R.drawable.ic_language, Screen.LANGUAGE);
        spacer(page, 12);
        LinearLayout app = card(16);
        page.addView(app);
        settingsLink(app, "Управление", R.drawable.ic_settings, Screen.CONTROLS);
        settingsLink(app, "Диагностика", R.drawable.ic_source, Screen.DIAGNOSTICS);
    }

    private Button settingsLink(LinearLayout group, String title, int glyph, Screen screen) {
        Button link = button(group, title, glyph, () -> showPage(screen));
        link.setTag(screen);
        return link;
    }

    private void heading(LinearLayout page, String title) {
        page.addView(label(tr(title), 32, colors.text, true));
        spacer(page, 14);
    }

    private void buildControls(LinearLayout page) {
        heading(page, "Управление");
        LinearLayout playback = card(16);
        page.addView(playback);
        playback.addView(label(tr("Воспроизведение"), 19, colors.text, true));
        option(playback, "Запускать пульт после включения ТВ", TvHostService.KEEP_AT_BOOT, true);
        option(playback, "Передавать обложки", TvHostService.INCLUDE_COVERS, true);
        spacer(page, 11);
        LinearLayout navigation = card(16);
        page.addView(navigation);
        navigation.addView(label(tr("Навигация по телевизору"), 19, colors.text, true));
        option(navigation, "Разрешить навигацию с телефона", TvNavigationService.ENABLED, false);
        navigationStatus = label("", 13, colors.dim, false);
        navigationStatus.setPadding(dp(9), 0, 0, dp(7));
        navigation.addView(navigationStatus);
        button(navigation, "Доступ к навигации", R.drawable.ic_right,
            () -> openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void buildAppearance(LinearLayout page) {
        heading(page, "Оформление");
        LinearLayout preview = card(14);
        page.addView(preview, new LinearLayout.LayoutParams(-1, dp(91)));
        preview.setGravity(Gravity.CENTER);
        LinearLayout sample = new LinearLayout(this);
        sample.setGravity(Gravity.CENTER_VERTICAL);
        sample.setPadding(dp(13), 0, dp(13), 0);
        sample.setBackground(shape(colors.raised, 18));
        preview.addView(sample, new LinearLayout.LayoutParams(dp(215), dp(58)));
        ImageView art = icon(R.drawable.ic_logo, colors.onAccent, 8);
        art.setBackground(shape(colors.accent, 12));
        sample.addView(art, new LinearLayout.LayoutParams(dp(35), dp(35)));
        TextView title = label("CueRelay", 18, colors.text, true);
        title.setPadding(dp(10), 0, 0, 0);
        sample.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView play = icon(R.drawable.ic_play, colors.accent, 4);
        sample.addView(play, new LinearLayout.LayoutParams(dp(29), dp(29)));
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        spacer(page, 12);
        page.addView(label(tr("Тема"), 15, colors.dim, true));
        LinearLayout appearance = card(8);
        page.addView(appearance);
        for (String value : new String[]{"system", "light", "dark"}) {
            String mode = "system".equals(value) ? "Как на устройстве" : "light".equals(value) ? "Светлая" : "Тёмная";
            int glyph = "system".equals(value) ? R.drawable.ic_monitor : "light".equals(value) ? R.drawable.ic_sun : R.drawable.ic_moon;
            appearanceButton(appearance, mode, glyph, "theme", value, Appearance.theme(this), Screen.APPEARANCE);
        }
        spacer(page, 11);
        page.addView(label(tr("Акцентный цвет"), 15, colors.dim, true));
        LinearLayout accents = new LinearLayout(this);
        accents.setGravity(Gravity.CENTER);
        accents.setPadding(dp(12), dp(8), dp(12), dp(8));
        accents.setBackground(shape(colors.panel, 23));
        page.addView(accents, new LinearLayout.LayoutParams(-1, -2));
        for (Appearance.Colorway color : Appearance.Colorway.values()) {
            FrameLayout cell = new FrameLayout(this);
            accents.addView(cell, new LinearLayout.LayoutParams(0, dp(52), 1));
            boolean selected = color.key.equals(Appearance.accentKey(this));
            ImageView swatch = icon(selected ? R.drawable.ic_check : 0,
                Color.luminance(color.seed) > .179f ? Color.BLACK : Color.WHITE, 13);
            swatch.setContentDescription(tr(color.label));
            swatch.setTag("accent" + color.key);
            swatch.setSelected(selected);
            swatch.setFocusable(true);
            swatch.setOnClickListener(v -> choose(v, "accent", color.key, Screen.APPEARANCE, Appearance.accentKey(this)));
            swatch.setOnFocusChangeListener((v, focused) -> paintSwatch(v, color.seed, focused, selected));
            paintSwatch(swatch, color.seed, false, selected);
            cell.addView(swatch, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        }
    }

    private void paintSwatch(View swatch, int color, boolean focused, boolean selected) {
        GradientDrawable ring = shape(Color.TRANSPARENT, 26);
        if (focused || selected) ring.setStroke(dp(focused ? 3 : 2), focused ? colors.text : colors.accent);
        swatch.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{ring,
            new android.graphics.drawable.InsetDrawable(shape(color, 24), dp(6))}));
    }

    private void choose(View source, String setting, String value, Screen screen, String current) {
        if (value.equals(current)) return;
        Point origin = CueReveal.center(source);
        Pairing.prefs(this).edit().putString(setting, value).apply();
        buildUi(screen, origin, setting + value);
    }

    private Button appearanceButton(LinearLayout parent, String title, int glyph, String setting, String value, String current, Screen screen) {
        Button choice = button(parent, title, glyph, () -> { });
        choice.setOnClickListener(v -> choose(v, setting, value, screen, current));
        choice.setTag(setting + value);
        choice.setSelected(value.equals(current));
        return choice;
    }

    private void buildLanguage(LinearLayout page) {
        heading(page, "Язык");
        LinearLayout choices = card(12);
        page.addView(choices);
        for (String language : new String[]{"ru", "en"})
            appearanceButton(choices, "ru".equals(language) ? "Русский" : "English", R.drawable.ic_language,
                UiText.LANGUAGE, language, UiText.language(this), Screen.LANGUAGE);
    }

    private void buildRemotes(LinearLayout page) {
        heading(page, "Устройства");
        remoteList = card(16);
        page.addView(remoteList);
    }

    private void refreshRemotes() {
        Map<String, String> latest = TvHostService.controllerNames(this);
        if (latest.equals(visibleRemotes)) return;
        visibleRemotes = latest;
        if (removingRemote != null && !latest.containsKey(removingRemote)) removingRemote = null;
        renderRemotes(false);
    }

    private void renderRemotes(boolean focusContent) {
        remoteList.removeAllViews();
        List<Map.Entry<String, String>> entries = new ArrayList<>(visibleRemotes.entrySet());
        entries.sort(Map.Entry.<String, String>comparingByValue(String.CASE_INSENSITIVE_ORDER).thenComparing(Map.Entry.comparingByKey()));
        if (entries.isEmpty()) remoteList.addView(label(tr("Нет привязанных устройств"), 16, colors.dim, false));
        Button first = null;
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, String> entry = entries.get(i);
            String key = entry.getKey();
            String name = entry.getValue().isEmpty() ? tr("Пульт {0}").replace("{0}", Integer.toString(i + 1)) : entry.getValue();
            Button item = button(remoteList, name, R.drawable.ic_phone, () -> {
                removingRemote = key;
                renderRemotes(true);
            });
            item.setText(name);
            item.setSingleLine(true);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (first == null) first = item;
            if (!key.equals(removingRemote)) continue;
            LinearLayout confirm = card(10);
            remoteList.addView(confirm);
            confirm.addView(label(tr("Отвязать «{0}»?").replace("{0}", name), 16, colors.text, true));
            Button cancel = button(confirm, "Отмена", R.drawable.ic_close, () -> {
                removingRemote = null;
                renderRemotes(true);
            });
            button(confirm, "Отвязать", R.drawable.ic_unlink, () -> {
                TvHostService host = TvHostService.instance;
                if (host == null || !host.revokeController(key)) {
                    android.widget.Toast.makeText(this, tr("Не удалось отключить пульты."), android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                removingRemote = null;
                refreshRemotes();
                remotesTab.requestFocus();
            });
            if (focusContent) {
                enter(confirm, 7, 0);
                cancel.requestFocus();
            }
        }
        remotesTab.setNextFocusRightId(first == null ? View.NO_ID : first.getId());
        if (focusContent && removingRemote == null) (first == null ? remotesTab : first).requestFocus();
    }

    private void showPage(Screen screen) {
        boolean changed = currentScreen != screen;
        currentScreen = screen;
        if (screen == Screen.DEVICES) refreshRemotes();
        if (changed && screen == Screen.DIAGNOSTICS) buildDiagnostics();
        screens.forEach((destination, page) -> page.setVisibility(destination == screen ? View.VISIBLE : View.GONE));
        connectionTab.setSelected(screen == Screen.CONNECTION);
        settingsTab.setSelected(screen.ordinal() >= Screen.SETTINGS.ordinal());
        remotesTab.setSelected(screen == Screen.DEVICES);
        Button tab = screen == Screen.CONNECTION ? connectionTab : screen == Screen.DEVICES ? remotesTab : settingsTab;
        if (screen.ordinal() > Screen.SETTINGS.ordinal()) {
            ArrayList<View> focusable = body(screen).getFocusables(View.FOCUS_FORWARD);
            if (!focusable.isEmpty()) focusable.get(0).requestFocus();
        } else tab.requestFocus();
        if (changed) {
            ScrollView page = screens.get(screen);
            enter(page, 0, 0);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (currentScreen == null) return;
        state.putInt("screen", currentScreen.ordinal());
        state.putBoolean("exporting", logExport != null);
    }

    @Override public void onBackPressed() {
        if (currentScreen == Screen.DEVICES && removingRemote != null) {
            removingRemote = null;
            renderRemotes(true);
        } else if (currentScreen.ordinal() > Screen.SETTINGS.ordinal()) {
            Screen previous = currentScreen;
            showPage(Screen.SETTINGS);
            View link = body(Screen.SETTINGS).findViewWithTag(previous);
            if (link != null) link.requestFocus();
        } else if (currentScreen != Screen.CONNECTION) showPage(Screen.CONNECTION);
        else if (confirmation.getVisibility() == View.VISIBLE) cancelDisconnect();
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        if (TvHostService.instance == null && PhoneMedia.hasAccess(this) &&
            Pairing.prefs(this).getBoolean(TvHostService.ENABLED, false)) TvHostService.start(this);
        ui.post(update);
    }

    @Override protected void onPause() { ui.removeCallbacks(update); super.onPause(); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        boolean handled = super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        if (handled && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 &&
            (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER))
            TvFeedback.pulse(this);
        return handled;
    }

    private void openSystem(String action) {
        try { startActivity(new Intent(action)); }
        catch (android.content.ActivityNotFoundException ignored) { }
    }

    private String readLog() {
        try { return Diagnostics.read(this); }
        catch (java.io.IOException error) { return ""; }
    }

    private void buildDiagnostics() {
        LinearLayout page = body(Screen.DIAGNOSTICS);
        page.removeAllViews();
        heading(page, "Диагностика");
        Diagnostics.record(this, "tv", "diagnostics_opened", null);
        String report = readLog();
        LinearLayout actions = card(12);
        page.addView(actions);
        if (report.isEmpty()) {
            actions.addView(label(tr("Журнал пока пуст."), 16, colors.dim, false));
            return;
        }
        button(actions, "Отправить журнал", R.drawable.ic_source, () -> shareLog(report));
        button(actions, "Сохранить журнал", R.drawable.ic_right, () -> saveLog(report));
        spacer(page, 12);
        LinearLayout preview = card(12);
        page.addView(preview);
        String[] lines = report.split("\n");
        for (int i = lines.length - 1; i >= Math.max(0, lines.length - 4); i--) {
            TextView line = label(lines[i], 13, colors.dim, false);
            line.setTypeface(Typeface.MONOSPACE);
            preview.addView(line);
            if (i > Math.max(0, lines.length - 4)) spacer(preview, 7);
        }
    }

    private void shareLog(String report) {
        java.io.File file = new java.io.File(getCacheDir(), SharedFiles.TV_DIAGNOSTICS);
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
            stream.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            android.net.Uri uri = SharedFiles.uri(this, SharedFiles.TV_DIAGNOSTICS);
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(android.content.ClipData.newRawUri(SharedFiles.TV_DIAGNOSTICS, uri));
            startActivity(Intent.createChooser(share, tr("Отправить журнал")));
        } catch (android.content.ActivityNotFoundException error) { saveLog(report); }
        catch (java.io.IOException error) { exportError(); }
    }

    private void saveLog(String report) {
        logExport = report;
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, SharedFiles.TV_DIAGNOSTICS), EXPORT_LOG);
        } catch (android.content.ActivityNotFoundException error) {
            logExport = null;
            exportError();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_LOG) return;
        String report = logExport;
        logExport = null;
        if (result != RESULT_OK || data == null || data.getData() == null || report == null) return;
        try (java.io.OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
            if (stream == null) throw new java.io.IOException();
            stream.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            android.widget.Toast.makeText(this, tr("Журнал сохранён"), android.widget.Toast.LENGTH_SHORT).show();
        } catch (java.io.IOException | SecurityException error) { exportError(); }
    }

    private void exportError() {
        android.widget.Toast.makeText(this, tr("Не удалось сохранить журнал."), android.widget.Toast.LENGTH_SHORT).show();
    }

    private void cancelDisconnect() {
        confirmation.setVisibility(View.GONE);
        stop.requestFocus();
    }

    private void disconnect() {
        TvHostService current = TvHostService.instance;
        if (current == null) { cancelDisconnect(); return; }
        try { current.revoke(); }
        catch (IllegalStateException error) {
            android.widget.Toast.makeText(this, tr("Не удалось отключить пульты."), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        issueWhenReady = false;
        Pairing.prefs(this).edit().putBoolean(TvHostService.ENABLED, false).apply();
        stopService(new Intent(this, TvHostService.class));
        confirmation.setVisibility(View.GONE);
        pair.requestFocus();
        refresh();
    }

    private void refresh() {
        boolean granted = PhoneMedia.hasAccess(this);
        TvHostService host = TvHostService.instance;
        permission.setVisibility(granted ? View.GONE : View.VISIBLE);
        pair.setVisibility(granted ? View.VISIBLE : View.GONE);
        connectionTab.setNextFocusRightId((granted ? pair : permission).getId());
        stop.setVisibility(host == null ? View.GONE : View.VISIBLE);
        if (host != null && issueWhenReady && host.issueCode()) issueWhenReady = false;
        String current = granted && host != null ? host.qr() : null;
        showCode(current);
        updateText(status, !granted ? "Разреши CueRelay доступ к уведомлениям на телевизоре." :
            host != null && host.failed ? "Не удалось открыть локальное подключение." :
            issueWhenReady ? "Подключаемся…" :
            host == null ? "Покажи QR-код, чтобы подключить телефон." : "Пульт готов");
        String count = tr("Привязано пультов: {0}").replace("{0}", Integer.toString(TvHostService.controllerCount(this)));
        if (!count.contentEquals(controllers.getText())) controllers.setText(count);
        if (currentScreen == Screen.DEVICES || visibleRemotes == null) refreshRemotes();
        updateText(navigationStatus, hasNavigationAccess() ?
            "Системный доступ разрешён" : "Разреши доступ к навигации на ТВ.");
    }

    private boolean hasNavigationAccess() {
        String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null) return false;
        ComponentName navigation = new ComponentName(this, TvNavigationService.class);
        for (String service : services.split(":"))
            if (navigation.equals(ComponentName.unflattenFromString(service))) return true;
        return false;
    }

    private void showCode(String current) {
        if (!java.util.Objects.equals(shown, current)) {
            code.setImageDrawable(null);
            if (current != null) {
                try {
                    BitMatrix matrix = new QRCodeWriter().encode(current, BarcodeFormat.QR_CODE, 500, 500);
                    int[] pixels = new int[500 * 500];
                    for (int y = 0; y < 500; y++) for (int x = 0; x < 500; x++)
                        pixels[y * 500 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                    code.setImageBitmap(Bitmap.createBitmap(pixels, 500, 500, Bitmap.Config.ARGB_8888));
                    enter(code, 8, 0);
                } catch (WriterException ignored) { }
            }
            shown = current;
        }
        boolean visible = code.getDrawable() != null;
        code.setVisibility(visible ? View.VISIBLE : View.GONE);
        qrIntro.setVisibility(visible ? View.GONE : View.VISIBLE);
        updateText(qrTitle, visible ? "Отсканируй QR-код" : "Привязка по QR");
        updateText(qrHint, visible ? "Настройки → Подключение → Телевизор" : "Один QR-код на каждый телефон");
    }

    private Button button(LinearLayout group, String title, int glyph, Runnable action) {
        Button button = new Button(this);
        button.setId(View.generateViewId());
        button.setText(tr(title));
        button.setAllCaps(false);
        button.setTypeface(medium);
        button.setTextSize(15);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(12), 0, dp(10), 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        int[] focus = {android.R.attr.state_focused};
        ColorStateList tint = new ColorStateList(new int[][]{focus, new int[0]}, new int[]{colors.onAccent, colors.text});
        button.setTextColor(tint);
        button.setCompoundDrawableTintList(tint);
        Drawable image = getDrawable(glyph).mutate();
        image.setBounds(0, 0, dp(19), dp(19));
        button.setCompoundDrawablesRelative(image, null, null, null);
        button.setCompoundDrawablePadding(dp(8));
        StateListDrawable background = new StateListDrawable();
        background.addState(focus, shape(colors.accent, 14));
        background.addState(new int[]{android.R.attr.state_selected}, shape(Appearance.mix(colors.raised, colors.accent, .19), 14));
        background.addState(new int[0], shape(colors.raised, 14));
        button.setBackground(background);
        button.setStateListAnimator(null);
        button.setOnFocusChangeListener((view, focused) -> {
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                float grow = Math.min(.025f, dp(7) / (float) Math.max(1, view.getWidth()));
                view.animate().scaleX(focused ? 1f + grow : 1f).scaleY(focused ? 1.025f : 1f).setDuration(125).start();
            }
        });
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(12), dp(5), dp(10), dp(5));
        size.topMargin = dp(5);
        group.addView(button, size);
        return button;
    }

    private Switch option(LinearLayout box, String title, String key, boolean fallback) {
        Switch control = new Switch(this);
        control.setId(View.generateViewId());
        control.setText(tr(title));
        control.setTextColor(colors.text);
        control.setTypeface(regular);
        control.setTextSize(15);
        control.setPadding(dp(9), 0, dp(9), 0);
        control.setSwitchPadding(dp(8));
        control.setChecked(Pairing.prefs(this).getBoolean(key, fallback));
        CueSwitch.style(control, colors.accent, colors.onAccent, colors.raised, colors.dim);
        StateListDrawable background = new StateListDrawable();
        GradientDrawable selection = shape(colors.raised, 12);
        selection.setStroke(dp(2), colors.accent);
        background.addState(new int[]{android.R.attr.state_focused}, selection);
        background.addState(new int[0], shape(colors.panel, 12));
        control.setBackground(background);
        control.setOnCheckedChangeListener((view, enabled) -> Pairing.prefs(this).edit().putBoolean(key, enabled).apply());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(43));
        size.topMargin = dp(4);
        box.addView(control, size);
        return control;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout card(int padding) {
        LinearLayout group = column();
        int inset = dp(padding);
        group.setPadding(inset, inset, inset, inset);
        group.setClipChildren(false);
        group.setClipToPadding(false);
        group.setBackground(shape(colors.panel, 24));
        return group;
    }

    private void spacer(LinearLayout box, int size) {
        box.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size)));
    }

    private void enter(View view, int y, int x) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        view.animate().cancel();
        view.setAlpha(.20f);
        view.setTranslationX(dp(x));
        view.setTranslationY(dp(y));
        view.setScaleX(.98f);
        view.setScaleY(.98f);
        view.animate().alpha(1f).translationX(0).translationY(0).scaleX(1).scaleY(1).setDuration(290)
            .setInterpolator(new android.view.animation.PathInterpolator(.2f, .75f, .25f, 1f)).start();
    }

    private ImageView icon(int resource, int tint, int padding) {
        ImageView view = new ImageView(this);
        view.setImageResource(resource);
        view.setColorFilter(tint);
        int inset = dp(padding);
        view.setPadding(inset, inset, inset, inset);
        return view;
    }

    private TextView label(String value, int size, int tint, boolean emphasis) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(tint);
        label.setTypeface(emphasis ? medium : regular);
        return label;
    }

    private void updateText(TextView view, String key) {
        String value = tr(key);
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private String tr(String value) { return UiText.text(this, value); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
