package com.codex.pocketplayer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TvRemoteActivity extends Activity {
    private static final int SCAN = 1, CAMERA = 2;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Appearance.Palette colors;
    private Pairing paired;
    private volatile TvLink link;
    private TextView status, title, instructions;
    private Button pair, sources, unpair;
    private ImageButton previous, toggle, next;
    private LinearLayout player;
    private SeekBar seek, volume;
    private String selected = "";
    private JSONObject track;
    private long duration;
    private boolean resumed, pending;
    private final Runnable poll = () -> request("state", null);

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        colors = Appearance.palette(this);
        paired = Pairing.read(this, TvLink.STORAGE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(colors.background);
        setContentView(root);
        ScrollView scroll = new ScrollView(this);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(18), dp(28), dp(18), dp(100));
        scroll.addView(screen);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        ImageButton targetMode = glyph(R.drawable.ic_tv, "Переключиться на ПК", true, this::finish);
        FrameLayout.LayoutParams target = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.BOTTOM | Gravity.END);
        target.setMarginEnd(dp(22));
        target.bottomMargin = dp(24);
        root.addView(targetMode, target);
        heading(screen, "Телевизор", 34, colors.text);
        status = heading(screen, "", 15, colors.dim);
        instructions = heading(screen, "Открой CueRelay на телевизоре и покажи QR-код.", 15, colors.dim);
        pair = button(screen, "Сканировать QR-код", this::scan);
        player = new LinearLayout(this);
        player.setOrientation(LinearLayout.VERTICAL);
        player.setPadding(dp(15), dp(12), dp(15), dp(12));
        player.setBackground(shape(colors.panel, 25));
        LinearLayout.LayoutParams card = new LinearLayout.LayoutParams(-1, -2);
        card.topMargin = dp(15);
        screen.addView(player, card);
        title = heading(player, "", 21, colors.text);
        sources = button(player, "Источники", () -> {});
        sources.setEnabled(false);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        player.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        previous = glyph(R.drawable.ic_previous, "Предыдущий трек", false, () -> control("previous", 0));
        toggle = glyph(R.drawable.ic_play, "Воспроизвести", true, () -> control("toggle", 0));
        next = glyph(R.drawable.ic_next, "Следующий трек", false, () -> control("next", 0));
        for (ImageButton item : new ImageButton[]{previous, toggle, next}) {
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(item == toggle ? 62 : 46), dp(48));
            size.setMargins(dp(4), 0, dp(4), 0);
            controls.addView(item, size);
        }
        seek = slider(player, "Перемотка трека", 1000, value -> control("seek", Math.round(duration * value / 1000d)));
        heading(player, "Громкость", 14, colors.dim);
        volume = slider(player, "Громкость", 100, value -> control("volume", value));
        unpair = button(screen, "Отвязать телевизор", () -> {
            if (paired != null) request("forget", null);
        });
        paint(null);
    }

    @Override protected void onResume() { super.onResume(); resumed = true; if (paired != null) request("state", null); }
    @Override protected void onPause() { resumed = false; ui.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    private void scan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA);
        else startActivityForResult(new Intent(this, ScanActivity.class).putExtra("tv", true), SCAN);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == CAMERA && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) scan();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SCAN || result != RESULT_OK || data == null) return;
        try {
            Pairing invite = TvLink.invitation(data.getStringExtra("code"));
            status.setText(tr("Подключаемся…"));
            pair.setEnabled(false);
            worker.execute(() -> {
                try {
                    JSONObject response = new TvLink(this, invite).exchange("pair", null);
                    String token = response.optString("token");
                    if (!TvLink.validToken(token)) throw new IllegalStateException();
                    Pairing saved = new Pairing(invite.fingerprint, invite.addresses, invite.port, token, response.optString("device"));
                    if (!saved.stored(this, TvLink.STORAGE).commit()) throw new IllegalStateException();
                    ui.post(() -> {
                        if (isDestroyed()) return;
                        pair.setEnabled(true);
                        paired = saved;
                        link = null;
                        selected = "";
                        request("state", null);
                    });
                } catch (Exception failure) {
                    Diagnostics.record(this, "tv", "pair_failed", failure);
                    ui.post(() -> {
                        if (isDestroyed()) return;
                        pair.setEnabled(true);
                        status.setText(tr("Не удалось подключить телевизор."));
                    });
                }
            });
        } catch (IllegalArgumentException invalid) { status.setText(tr("Не удалось подключить телевизор.")); }
    }

    private void control(String action, long value) {
        if (track == null && !"volume".equals(action)) return;
        try {
            JSONObject command = new JSONObject().put("action", action).put("positionMs", value);
            if (track != null) command.put("id", track.optString("id")).put("title", track.optString("title"));
            request("command", command);
        } catch (org.json.JSONException ignored) {}
    }

    private void request(String operation, JSONObject command) {
        Pairing target = paired;
        if (target == null || isDestroyed() || ("state".equals(operation) && pending)) return;
        ui.removeCallbacks(poll);
        pending = true;
        worker.execute(() -> {
            JSONObject answer = null;
            try {
                if (link == null) link = new TvLink(this, target);
                answer = link.exchange(operation, command);
            } catch (Exception ignored) {}
            JSONObject state = answer;
            ui.post(() -> {
                if (isDestroyed()) return;
                pending = false;
                if (paired != target) return;
                if ("forget".equals(operation) && state != null && !state.has("error")) {
                    Pairing.prefs(this).edit().remove(TvLink.STORAGE).apply();
                    paired = null;
                    link = null;
                    paint(null);
                    return;
                }
                paint(state);
                if (resumed) ui.postDelayed(poll, 1200);
            });
        });
    }

    private void paint(JSONObject state) {
        instructions.setVisibility(paired == null ? View.VISIBLE : View.GONE);
        player.setVisibility(paired == null ? View.GONE : View.VISIBLE);
        unpair.setVisibility(paired == null ? View.GONE : View.VISIBLE);
        boolean ready = state != null && "ready".equals(state.optString("status"));
        status.setText(paired == null ? tr("Подключи телевизор") : ready ? paired.name :
            state != null && "permission".equals(state.optString("status")) ?
                tr("Разреши CueRelay доступ к уведомлениям на телевизоре.") : tr("Телевизор недоступен"));
        JSONArray items = ready ? state.optJSONArray("sources") : null;
        track = null;
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && (track == null || selected.equals(item.optString("id")))) track = item;
        }
        sources.setEnabled(items != null && items.length() > 0);
        sources.setOnClickListener(v -> {
            if (items == null) return;
            PopupMenu menu = new PopupMenu(this, sources);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) menu.getMenu().add(item.optString("app")).setOnMenuItemClickListener(row -> {
                    selected = item.optString("id");
                    paint(state);
                    return true;
                });
            }
            menu.show();
        });
        title.setText(track == null ? tr("Нет активных источников") :
            track.optString("title").isEmpty() ? track.optString("app") : track.optString("title"));
        previous.setEnabled(track != null && track.optBoolean("previous"));
        next.setEnabled(track != null && track.optBoolean("next"));
        toggle.setEnabled(track != null && track.optBoolean("toggle"));
        boolean playing = track != null && track.optBoolean("playing");
        toggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        toggle.setContentDescription(tr(playing ? "Пауза" : "Воспроизвести"));
        for (ImageButton button : new ImageButton[]{previous, toggle, next})
            button.setAlpha(button.isEnabled() ? 1f : .45f);
        duration = track == null ? 0 : track.optLong("duration");
        seek.setEnabled(duration > 0 && track.optBoolean("seek"));
        if (!seek.isPressed()) seek.setProgress(duration == 0 ? 0 : (int)(1000d * track.optLong("position") / duration));
        int level = ready ? state.optInt("volume", -1) : -1;
        volume.setEnabled(level >= 0);
        if (!volume.isPressed()) volume.setProgress(Math.max(0, level));
    }

    private SeekBar slider(LinearLayout parent, String title, int max, java.util.function.IntConsumer done) {
        SeekBar bar = new SeekBar(this);
        bar.setContentDescription(tr(title));
        bar.setMax(max);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(colors.accent));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(colors.accent));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) { done.accept(view.getProgress()); }
            @Override public void onProgressChanged(SeekBar view, int value, boolean fromUser) {}
        });
        parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));
        return bar;
    }

    private TextView heading(LinearLayout parent, String value, int size, int tint) {
        TextView view = new TextView(this);
        view.setText(tr(value));
        view.setTextSize(size);
        view.setTextColor(tint);
        view.setTypeface(getResources().getFont(R.font.google_sans_medium));
        view.setPadding(dp(6), dp(10), dp(6), dp(5));
        parent.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String caption, Runnable click) {
        Button button = new Button(this);
        button.setText(tr(caption));
        button.setAllCaps(false);
        button.setOnClickListener(v -> click.run());
        parent.addView(button);
        return button;
    }

    private ImageButton glyph(int resource, String name, boolean highlighted, Runnable click) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(resource);
        button.setColorFilter(highlighted ? colors.onAccent : colors.text);
        button.setContentDescription(tr(name));
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff),
            shape(highlighted ? colors.accent : colors.raised, 17), null));
        button.setOnClickListener(v -> click.run());
        return button;
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
