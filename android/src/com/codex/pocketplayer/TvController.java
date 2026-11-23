package com.codex.pocketplayer;

import android.content.Context;
import android.os.Handler;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class TvController implements AutoCloseable {
    private final Context context;
    private final Handler ui;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TvLink link;
    private Pairing destination;
    private boolean polling, closed;
    private int queued;
    private final Runnable tick = () -> request("state", null, null);
    Pairing paired;
    Playback playback;
    boolean navigation;
    String status = "", selected = "";

    TvController(Context context, Handler ui) {
        this.context = context.getApplicationContext();
        this.ui = ui;
        paired = Pairing.read(context, TvLink.STORAGE);
    }

    void polling(boolean enabled) {
        polling = enabled;
        ui.removeCallbacks(tick);
        if (enabled && queued == 0) ui.post(tick);
    }

    void select(String id) {
        selected = id;
        if (playback != null) playback = playback.select(id, null);
        refresh();
    }

    void refresh() { request("state", null, null); }

    void pair(String code, Consumer<Boolean> result) {
        final Pairing invite;
        try { invite = TvLink.invitation(code); }
        catch (IllegalArgumentException invalid) { result.accept(false); return; }
        worker.execute(() -> {
            Pairing saved = null;
            try {
                JSONObject reply = new TvLink(context, invite).exchange("pair", null);
                String token = reply.optString("token");
                if (!TvLink.validToken(token)) throw new IllegalStateException();
                Pairing candidate = new Pairing(invite.fingerprint, invite.addresses, invite.port, token, reply.optString("device"));
                if (candidate.stored(context, TvLink.STORAGE).commit()) saved = candidate;
            } catch (Exception error) { Diagnostics.record(context, "tv", "pair_failed", error); }
            Pairing target = saved;
            ui.post(() -> {
                if (closed) return;
                if (target != null) {
                    paired = target;
                    playback = null;
                    selected = "";
                    navigation = false;
                }
                result.accept(target != null);
                if (target != null) refresh();
            });
        });
    }

    void forget(Consumer<Boolean> result) { request("forget", null, result); }

    void send(String operation, Playback.Source source, long position) {
        if (source == null && !"volume".equals(operation)) return;
        try {
            JSONObject command = new JSONObject().put("action", operation).put("positionMs", position);
            if (source != null) command.put("id", source.id).put("title", source.title);
            request("command", command, null);
        } catch (org.json.JSONException ignored) { }
    }

    void navigate(String key) {
        try { request("navigation", new JSONObject().put("key", key), null); }
        catch (org.json.JSONException ignored) { }
    }

    private void request(String operation, JSONObject command, Consumer<Boolean> finished) {
        Pairing target = paired;
        if (closed || target == null || queued >= 8 || ("state".equals(operation) && queued > 0)) return;
        queued++;
        ui.removeCallbacks(tick);
        worker.execute(() -> {
            JSONObject reply = null;
            try {
                if (destination != target) {
                    link = new TvLink(context, target);
                    destination = target;
                }
                reply = link.exchange(operation, command);
            } catch (Exception ignored) { }
            JSONObject state = reply;
            ui.post(() -> {
                if (closed) return;
                queued--;
                boolean valid = state != null && !state.has("error");
                if (paired == target) {
                    if ("forget".equals(operation) && (valid || (state != null && "unauthorized".equals(state.optString("error"))))) {
                        Pairing.prefs(context).edit().remove(TvLink.STORAGE).apply();
                        paired = null;
                        playback = null;
                        navigation = false;
                        valid = true;
                    } else if (state != null && state.has("status")) {
                        status = state.optString("status");
                        navigation = state.optBoolean("navigation");
                        try { playback = Playback.fromTv(state, selected, playback); }
                        catch (org.json.JSONException ignored) { playback = null; }
                    } else if (state == null || "unauthorized".equals(state.optString("error"))) {
                        playback = null;
                        navigation = false;
                        status = state == null ? "" : "unauthorized";
                    }
                }
                if (finished != null) finished.accept(valid);
                if (polling && paired != null && queued == 0)
                    ui.postDelayed(tick, "state".equals(operation) ? 1200 : 250);
            });
        });
    }

    @Override public void close() {
        closed = true;
        ui.removeCallbacks(tick);
        worker.shutdownNow();
    }
}
