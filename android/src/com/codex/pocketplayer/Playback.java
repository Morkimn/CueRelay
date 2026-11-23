package com.codex.pocketplayer;

import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class Playback {
    final long revision;
    final String theme;
    final String accent;
    final String computer;
    final String current;
    final List<Source> sources;
    final Source selected;
    final Bitmap art;
    final int volume;
    final boolean syncAppearance;

    Playback(long revision, String theme, String accent, String computer, String current, List<Source> sources,
        Source selected, Bitmap art, int volume, boolean syncAppearance) {
        this.revision = revision;
        this.theme = theme;
        this.accent = accent;
        this.computer = computer;
        this.current = current;
        this.sources = sources;
        this.selected = selected;
        this.art = art;
        this.volume = volume;
        this.syncAppearance = syncAppearance;
    }

    static Playback parse(JSONObject data) throws Exception {
        JSONArray items = data.getJSONArray("sources");
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) sources.add(new Source(items.getJSONObject(i)));
        return new Playback(data.getLong("revision"), data.getString("theme"), data.optString("accent", "green"), data.getString("computer"),
            data.optString("current"), sources, null, null, data.optInt("volume", -1), data.optBoolean("syncAppearance", true));
    }

    static Playback fromTv(JSONObject data, String preferred, Playback previous) throws org.json.JSONException {
        JSONArray items = data.getJSONArray("sources");
        List<Source> sources = new ArrayList<>();
        long now = System.currentTimeMillis();
        String firstPlaying = "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            long duration = item.optLong("duration");
            if (duration > 0) item.put("timeline", new JSONObject().put("durationMs", duration)
                .put("positionMs", item.optLong("position")).put("updatedAtMs", now)
                .put("rate", item.optDouble("rate", 0)).put("canSeek", item.optBoolean("seek")));
            item.put("art", item.optString("cover"));
            Source source = new Source(item);
            sources.add(source);
            if (source.playing && firstPlaying.isEmpty()) firstPlaying = source.id;
        }
        Playback result = new Playback(now, "", "", data.optString("device"), firstPlaying, sources, null, null,
            data.optInt("volume", -1), false).select(preferred, null);
        String artwork = result.artworkKey();
        if (artwork.isEmpty()) return result;
        if (previous != null && previous.art != null && artwork.equals(previous.artworkKey())) return result.withArtwork(previous.art);
        try {
            byte[] image = android.util.Base64.decode(artwork, android.util.Base64.DEFAULT);
            return result.withArtwork(android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length));
        } catch (IllegalArgumentException invalid) { return result; }
    }

    Playback select(String id, Bitmap art) {
        Source source = null;
        for (Source candidate : sources) if (candidate.id.equals(id)) { source = candidate; break; }
        if (source == null) {
            for (Source candidate : sources) if (candidate.id.equals(current)) { source = candidate; break; }
        }
        if (source == null && !sources.isEmpty()) source = sources.get(0);
        return new Playback(revision, theme, accent, computer, current, sources, source, art, volume, syncAppearance);
    }

    Playback withArtwork(Bitmap art) {
        return new Playback(revision, theme, accent, computer, current, sources, selected, art, volume, syncAppearance);
    }

    String artworkKey() { return selected == null ? "" : selected.artwork; }

    static final class Source {
        final String id, app, title, artist, artwork;
        final boolean playing, toggle, previous, next;
        final Timeline timeline;
        Source(JSONObject data) throws org.json.JSONException {
            id = data.getString("id");
            app = data.getString("app");
            title = data.optString("title");
            artist = data.optString("artist");
            artwork = data.optString("art");
            playing = data.optBoolean("playing");
            toggle = data.optBoolean("toggle");
            previous = data.optBoolean("previous");
            next = data.optBoolean("next");
            JSONObject rawTimeline = data.optJSONObject("timeline");
            timeline = rawTimeline == null || rawTimeline.optLong("durationMs") <= 0 ? null : new Timeline(rawTimeline);
        }
        String displayTitle() { return title.isEmpty() ? app : title; }
    }

    static final class Timeline {
        final long duration, position, updated, minimum, maximum;
        final double rate;
        final boolean canSeek;

        Timeline(JSONObject data) {
            duration = data.optLong("durationMs");
            position = data.optLong("positionMs");
            updated = data.optLong("updatedAtMs");
            minimum = data.optLong("minSeekMs");
            maximum = data.optLong("maxSeekMs", duration);
            rate = data.optDouble("rate", 0);
            canSeek = data.optBoolean("canSeek") && maximum > minimum;
        }

        long now() {
            return (long) Math.max(0, Math.min(duration, position + Math.max(0, System.currentTimeMillis() - updated) * rate));
        }

        long target(double fraction) { return Math.max(minimum, Math.min(maximum, Math.round(duration * fraction))); }
    }
}
