package com.codex.pocketplayer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import java.util.List;
import java.util.Map;

public final class TvHostActivity extends Activity {
    private enum Screen { CONNECTION, SETTINGS, REMOTES }
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Appearance.Palette colors;
    private Typeface regular, medium;
    private ScrollView connectionPage, settingsPage, remotesPage;
    private Button connectionTab, settingsTab, remotesTab, permission, pair, stop;
    private LinearLayout qrIntro, confirmation;
    private LinearLayout remoteList;
    private Screen currentScreen;
    private Map<String, String> visibleRemotes;
    private String removingRemote, appearanceFocus;
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
        colors = Appearance.palette(this);
        regular = getResources().getFont(R.font.google_sans_regular);
        medium = getResources().getFont(R.font.google_sans_medium);
        boolean compact = getResources().getConfiguration().screenWidthDp < 800;
        LinearLayout shell = new LinearLayout(this);
        shell.setPadding(dp(compact ? 24 : 38), dp(27), dp(compact ? 24 : 38), dp(24));
        shell.setBackgroundColor(colors.background);
        setContentView(shell);

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
        rail.addView(brand);
        spacer(rail, 27);
        connectionTab = button(rail, "Подключение", R.drawable.ic_tv, () -> showPage(Screen.CONNECTION));
        settingsTab = button(rail, "Настройки", R.drawable.ic_settings, () -> showPage(Screen.SETTINGS));
        remotesTab = button(rail, "Пульты", R.drawable.ic_phone, () -> showPage(Screen.REMOTES));

        FrameLayout pages = new FrameLayout(this);
        shell.addView(pages, new LinearLayout.LayoutParams(0, -1, 1));
        connectionPage = page(pages);
        settingsPage = page(pages);
        remotesPage = page(pages);
        buildConnection((LinearLayout) connectionPage.getChildAt(0), compact);
        buildSettings((LinearLayout) settingsPage.getChildAt(0));
        buildRemotes((LinearLayout) remotesPage.getChildAt(0));
        refresh();
        showPage(bundle == null ? Screen.CONNECTION : Screen.values()[bundle.getInt("screen")]);
        if (bundle != null && currentScreen == Screen.SETTINGS) {
            View focus = settingsPage.findViewWithTag(bundle.getString("appearanceFocus", ""));
            settingsPage.post(() -> {
                if (focus != null) focus.requestFocus();
                settingsPage.scrollTo(0, bundle.getInt("settingsScroll"));
            });
        }
    }

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
        int side = compact ? 134 : 182;
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
        page.addView(label(tr("Настройки телевизора"), 32, colors.text, true));
        spacer(page, 18);
        LinearLayout playback = card(16);
        page.addView(playback);
        playback.addView(label(tr("Воспроизведение"), 19, colors.text, true));
        Switch startup = option(playback, "Запускать пульт после включения ТВ", TvHostService.KEEP_AT_BOOT, true);
        settingsTab.setNextFocusRightId(startup.getId());
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
        spacer(page, 11);
        LinearLayout appearance = card(16);
        page.addView(appearance);
        appearance.addView(label(tr("Оформление"), 19, colors.text, true));
        TextView theme = label(tr("Тема"), 14, colors.dim, true);
        appearance.addView(theme);
        for (String value : new String[]{"system", "light", "dark"}) {
            String title = "system".equals(value) ? "Системная" : "light".equals(value) ? "Светлая" : "Тёмная";
            int glyph = "system".equals(value) ? R.drawable.ic_monitor : "light".equals(value) ? R.drawable.ic_sun : R.drawable.ic_moon;
            appearanceButton(appearance, title, glyph, "theme", value, Appearance.theme(this));
        }
        spacer(appearance, 12);
        appearance.addView(label(tr("Акцентный цвет"), 14, colors.dim, true));
        LinearLayout accents = new LinearLayout(this);
        appearance.addView(accents);
        for (Appearance.Colorway color : Appearance.Colorway.values()) {
            LinearLayout item = column();
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
            size.rightMargin = dp(4);
            accents.addView(item, size);
            Button swatch = appearanceButton(item, color.label, R.drawable.ic_selected, "accent", color.key, Appearance.accentKey(this));
            swatch.setCompoundDrawableTintList(ColorStateList.valueOf(Appearance.palette(color.key, Appearance.dark(this)).accent));
        }
    }

    private Button appearanceButton(LinearLayout parent, String title, int glyph, String setting, String value, String current) {
        Button choice = button(parent, title, glyph, () -> {
            if (value.equals(Pairing.prefs(this).getString(setting, setting.equals("theme") ? "system" : "green"))) return;
            appearanceFocus = setting + value;
            Pairing.prefs(this).edit().putString(setting, value).apply();
            recreate();
        });
        choice.setTag(setting + value);
        choice.setSelected(value.equals(current));
        return choice;
    }

    private void buildRemotes(LinearLayout page) {
        page.addView(label(tr("Привязанные пульты"), 32, colors.text, true));
        spacer(page, 18);
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
        if (entries.isEmpty()) remoteList.addView(label(tr("Нет привязанных пультов"), 16, colors.dim, false));
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
        if (screen == Screen.REMOTES) refreshRemotes();
        connectionPage.setVisibility(screen == Screen.CONNECTION ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(screen == Screen.SETTINGS ? View.VISIBLE : View.GONE);
        remotesPage.setVisibility(screen == Screen.REMOTES ? View.VISIBLE : View.GONE);
        connectionTab.setSelected(screen == Screen.CONNECTION);
        settingsTab.setSelected(screen == Screen.SETTINGS);
        remotesTab.setSelected(screen == Screen.REMOTES);
        Button tab = screen == Screen.CONNECTION ? connectionTab : screen == Screen.SETTINGS ? settingsTab : remotesTab;
        tab.requestFocus();
        if (changed) enter(screen == Screen.CONNECTION ? connectionPage : screen == Screen.SETTINGS ? settingsPage : remotesPage, 0, 15);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (currentScreen == null) return;
        state.putInt("screen", currentScreen.ordinal());
        state.putInt("settingsScroll", settingsPage.getScrollY());
        state.putString("appearanceFocus", appearanceFocus);
    }

    @Override public void onBackPressed() {
        if (currentScreen == Screen.REMOTES && removingRemote != null) {
            removingRemote = null;
            renderRemotes(true);
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
        if (currentScreen == Screen.REMOTES || visibleRemotes == null) refreshRemotes();
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
            if (android.animation.ValueAnimator.areAnimatorsEnabled())
                view.animate().scaleX(focused ? 1.025f : 1f).scaleY(focused ? 1.025f : 1f).setDuration(125).start();
        });
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(45));
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
        group.setBackground(shape(colors.panel, 24));
        return group;
    }

    private void spacer(LinearLayout box, int size) {
        box.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size)));
    }

    private void enter(View view, int y, int x) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationX(dp(x));
        view.setTranslationY(dp(y));
        view.animate().alpha(1f).translationX(0).translationY(0).setDuration(210)
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
