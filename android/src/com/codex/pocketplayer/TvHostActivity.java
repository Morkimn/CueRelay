package com.codex.pocketplayer;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;

public final class TvHostActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView controllers;
    private Button permission, pair, stop;
    private ImageView code, badge;
    private Appearance.Palette colors;
    private TextView navigationStatus;
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
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER_VERTICAL);
        screen.setPadding(dp(42), dp(30), dp(42), dp(30));
        screen.setBackgroundColor(colors.background);
        setContentView(screen);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setColorFilter(colors.onAccent);
        logo.setPadding(dp(8), dp(8), dp(8), dp(8));
        logo.setBackground(shape(colors.accent, 13));
        header.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = label("CueRelay", 29, colors.text);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title);
        screen.addView(header);

        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
        row.topMargin = dp(19);
        screen.addView(layout, row);
        LinearLayout controls = card();
        layout.addView(controls, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(label(tr("Подключение"), 20, colors.text));
        status = label("", 15, colors.dim);
        status.setPadding(0, dp(9), 0, dp(10));
        controls.addView(status);
        controllers = label("", 13, colors.dim);
        controllers.setPadding(0, 0, 0, dp(9));
        controls.addView(controllers);
        LinearLayout qr = card();
        qr.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(223), dp(223));
        qrParams.leftMargin = dp(12);
        layout.addView(qr, qrParams);
        badge = new ImageView(this);
        badge.setImageResource(R.drawable.ic_tv);
        badge.setColorFilter(colors.accent);
        badge.setPadding(dp(28), dp(28), dp(28), dp(28));
        badge.setBackground(shape(colors.raised, 24));
        qr.addView(badge, new LinearLayout.LayoutParams(dp(112), dp(112)));
        code = new ImageView(this);
        code.setContentDescription(tr("QR-код привязки"));
        code.setVisibility(View.GONE);
        qr.addView(code, new LinearLayout.LayoutParams(dp(192), dp(192)));
        permission = button(controls, "Доступ к уведомлениям", () -> {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (android.content.ActivityNotFoundException ignored) {}
        });
        pair = button(controls, "Добавить пульт по QR", () -> {
            if (!PhoneMedia.hasAccess(this)) return;
            Pairing.prefs(this).edit().putBoolean(TvHostService.ENABLED, true).apply();
            issueWhenReady = true;
            if (TvHostService.instance == null) TvHostService.start(this);
            refresh();
        });
        stop = button(controls, "Отключить все пульты", () -> {
            issueWhenReady = false;
            TvHostService current = TvHostService.instance;
            if (current != null) current.revoke();
            Pairing.prefs(this).edit().putBoolean(TvHostService.ENABLED, false).apply();
            stopService(new Intent(this, TvHostService.class));
            refresh();
        });
        LinearLayout settings = card();
        settings.setVisibility(View.GONE);
        button(controls, "Настройки телевизора", () -> {
            boolean open = settings.getVisibility() == View.VISIBLE;
            settings.setVisibility(open ? View.GONE : View.VISIBLE);
            qr.setVisibility(open ? View.VISIBLE : View.GONE);
            if (!open && settings.getChildCount() > 1) settings.getChildAt(1).requestFocus();
        });
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(0, -2, 1);
        settingsParams.leftMargin = dp(12);
        layout.addView(settings, settingsParams);
        settings.addView(label(tr("Настройки"), 20, colors.text));
        option(settings, "Запускать пульт после включения ТВ", TvHostService.KEEP_AT_BOOT, true);
        option(settings, "Передавать обложки", TvHostService.INCLUDE_COVERS, true);
        option(settings, "Навигация по телевизору", TvNavigationService.ENABLED, false);
        navigationStatus = label("", 13, colors.dim);
        settings.addView(navigationStatus);
        button(settings, "Доступ к навигации", () -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (android.content.ActivityNotFoundException ignored) { }
        });
        if (PhoneMedia.hasAccess(this)) pair.requestFocus();
        else permission.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        if (TvHostService.instance == null && PhoneMedia.hasAccess(this) &&
            Pairing.prefs(this).getBoolean(TvHostService.ENABLED, false)) TvHostService.start(this);
        ui.post(update);
    }
    @Override protected void onPause() { ui.removeCallbacks(update); super.onPause(); }

    private void refresh() {
        boolean granted = PhoneMedia.hasAccess(this);
        TvHostService host = TvHostService.instance;
        String access = tr(TvNavigationService.instance != null ? "Разрешён" : "Разреши доступ к навигации на ТВ.");
        if (!access.contentEquals(navigationStatus.getText())) navigationStatus.setText(access);
        permission.setVisibility(granted ? View.GONE : View.VISIBLE);
        pair.setVisibility(granted ? View.VISIBLE : View.GONE);
        stop.setVisibility(host == null ? View.GONE : View.VISIBLE);
        if (host != null && issueWhenReady && host.issueCode()) issueWhenReady = false;
        String current = granted && host != null ? host.qr() : null;
        if (current == null) code.setVisibility(View.GONE);
        else if (!current.equals(shown)) {
            try {
                com.google.zxing.common.BitMatrix matrix = new QRCodeWriter().encode(current, BarcodeFormat.QR_CODE, 500, 500);
                int[] pixels = new int[500 * 500];
                for (int y = 0; y < 500; y++) for (int x = 0; x < 500; x++)
                    pixels[y * 500 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                code.setImageBitmap(Bitmap.createBitmap(pixels, 500, 500, Bitmap.Config.ARGB_8888));
                code.setVisibility(View.VISIBLE);
            } catch (com.google.zxing.WriterException error) { code.setVisibility(View.GONE); }
        }
        badge.setVisibility(code.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        shown = current;
        String message = tr(!granted ? "Разреши CueRelay доступ к уведомлениям на телевизоре." :
            host != null && host.failed ? "Не удалось открыть локальное подключение." :
            current != null ? "Отсканируй QR-код в пульте CueRelay на телефоне." :
            issueWhenReady ? "Подключаемся…" :
            host != null ? "Пульт готов. Можно вернуться к видео или музыке." :
            "Покажи QR-код, чтобы подключить телефон.");
        if (!message.contentEquals(status.getText())) status.setText(message);
        String count = tr("Привязано пультов: {0}").replace("{0}", Integer.toString(TvHostService.controllerCount(this)));
        if (!count.contentEquals(controllers.getText())) controllers.setText(count);
    }

    private Button button(LinearLayout actions, String title, Runnable run) {
        Button button = new Button(this);
        button.setText(tr(title));
        button.setAllCaps(false);
        button.setTypeface(getResources().getFont(R.font.google_sans_medium));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
            new int[]{colors.onAccent, colors.text}));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, shape(colors.accent, 16));
        background.addState(new int[0], shape(colors.raised, 16));
        button.setBackground(background);
        button.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(44));
        params.bottomMargin = dp(5);
        actions.addView(button, params);
        return button;
    }

    private void option(LinearLayout box, String title, String key, boolean fallback) {
        Switch control = new Switch(this);
        control.setText(tr(title));
        control.setTextColor(colors.text);
        control.setTextSize(14);
        control.setPadding(0, dp(6), 0, dp(6));
        control.setChecked(Pairing.prefs(this).getBoolean(key, fallback));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, shape(colors.raised, 12));
        background.addState(new int[0], shape(colors.panel, 12));
        control.setBackground(background);
        control.setOnCheckedChangeListener((view, enabled) -> Pairing.prefs(this).edit().putBoolean(key, enabled).apply());
        box.addView(control, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout card() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(15), dp(13), dp(15), dp(13));
        group.setBackground(shape(colors.panel, 23));
        return group;
    }

    private TextView label(String value, int size, int tint) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(tint);
        label.setTypeface(getResources().getFont(R.font.google_sans_medium));
        return label;
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
