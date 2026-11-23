package com.codex.pocketplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;

public final class TvHostActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button permission, pair, stop;
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
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER);
        screen.setPadding(dp(42), dp(28), dp(42), dp(28));
        screen.setBackgroundColor(0xff17191d);
        setContentView(screen);
        TextView title = new TextView(this);
        title.setText("CueRelay · Android TV");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        screen.addView(title);
        status = new TextView(this);
        status.setTextColor(0xffe6e9ed);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        screen.addView(status);
        code = new ImageView(this);
        code.setContentDescription(tr("QR-код привязки"));
        code.setVisibility(View.GONE);
        screen.addView(code, new LinearLayout.LayoutParams(dp(192), dp(192)));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        screen.addView(actions);
        permission = button(actions, "Доступ к уведомлениям", () -> {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (android.content.ActivityNotFoundException ignored) {}
        });
        pair = button(actions, "Показать QR-код", () -> {
            if (!PhoneMedia.hasAccess(this)) return;
            Pairing.prefs(this).edit().putBoolean(TvHostService.ENABLED, true).apply();
            issueWhenReady = true;
            if (TvHostService.instance == null) TvHostService.start(this);
            refresh();
        });
        stop = button(actions, "Отключить пульт", () -> {
            issueWhenReady = false;
            Pairing.prefs(this).edit().putBoolean(TvHostService.ENABLED, false).apply();
            TvHostService current = TvHostService.instance;
            if (current != null) current.revoke();
            stopService(new Intent(this, TvHostService.class));
            refresh();
        });
        if (PhoneMedia.hasAccess(this)) pair.requestFocus();
        else permission.requestFocus();
    }

    @Override protected void onResume() { super.onResume(); if (!isFinishing()) ui.post(update); }
    @Override protected void onPause() { ui.removeCallbacks(update); super.onPause(); }

    private void refresh() {
        boolean granted = PhoneMedia.hasAccess(this);
        TvHostService host = TvHostService.instance;
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
        shown = current;
        String message = tr(!granted ? "Разреши CueRelay доступ к уведомлениям на телевизоре." :
            host != null && host.failed ? "Не удалось открыть локальное подключение." :
            current != null ? "Отсканируй QR-код в пульте CueRelay на телефоне." :
            issueWhenReady ? "Подключаемся…" :
            host != null ? "Пульт готов. Можно вернуться к видео или музыке." :
            "Покажи QR-код, чтобы подключить телефон.");
        if (!message.contentEquals(status.getText())) status.setText(message);
    }

    private Button button(LinearLayout actions, String title, Runnable run) {
        Button button = new Button(this);
        button.setText(tr(title));
        button.setOnClickListener(v -> run.run());
        actions.addView(button);
        return button;
    }

    private String tr(String value) { return UiText.text(this, value); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
