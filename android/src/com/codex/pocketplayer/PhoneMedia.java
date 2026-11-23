package com.codex.pocketplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PhoneMedia extends NotificationListenerService {
    static final String ENABLED = "phone-remote";
    static final String COVERS = "phone-covers";
    static final String BLOCKED = "phone-blocked";

    static String deviceName(Context context) {
        String name = null;
        try { name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME); }
        catch (RuntimeException ignored) { }
        return clean(name == null || name.trim().isEmpty() ? Build.MODEL : name, 64);
    }

    static boolean enabled(Context context) { return Pairing.prefs(context).getBoolean(ENABLED, false); }

    static List<MediaController> sessions(Context context) {
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return Collections.emptyList();
        List<MediaController> visible = new ArrayList<>();
        for (MediaController controller : manager.getActiveSessions(new ComponentName(context, PhoneMedia.class))) {
            // Both builds can publish a proxy for the same Windows player on this phone.
            String owner = controller.getPackageName();
            if (!owner.equals(context.getPackageName()) &&
                !owner.equals("com.codex.pocketplayer") && !owner.equals("com.codex.pocketplayer.dev"))
                visible.add(controller);
        }
        return visible;
    }

    static boolean hasAccess(Context context) {
        try { sessions(context); return true; }
        catch (SecurityException denied) { return false; }
    }

    static Map<String, String> visibleApps(Context context) {
        Map<String, String> apps = new LinkedHashMap<>();
        try {
            for (MediaController controller : sessions(context))
                apps.put(controller.getPackageName(), appLabel(context, controller.getPackageName()));
        } catch (SecurityException ignored) { }
        return apps;
    }

    private static String appLabel(Context context, String packageName) {
        PackageManager manager = context.getPackageManager();
        try { return clean(manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)), 80); }
        catch (PackageManager.NameNotFoundException ignored) { return clean(packageName, 80); }
    }

    private static String clean(CharSequence value, int limit) {
        if (value == null) return "";
        String text = value.toString().trim();
        int end = Math.min(text.length(), limit);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    static final class Exchange {
        private final Context context;
        private final Handler callbacks;
        private final Runnable changed;
        private final boolean tv;
        private final MediaSessionManager manager;
        private final MediaSessionManager.OnActiveSessionsChangedListener activeChanged;
        private final Map<MediaSession.Token, String> ids = new HashMap<>();
        private final Map<MediaSession.Token, Cover> covers = new HashMap<>();
        private final Map<String, MediaController> sent = new HashMap<>();
        private final Map<MediaSession.Token, Observation> observed = new HashMap<>();
        private final Set<MediaSession.Token> coverChanged = ConcurrentHashMap.newKeySet();
        private boolean monitoring, closed;

        Exchange(Context context, Handler callbacks, Runnable changed) {
            this(context, callbacks, changed, false);
        }

        Exchange(Context context, Handler callbacks, Runnable changed, boolean tv) {
            this.context = context;
            this.callbacks = callbacks;
            this.changed = changed;
            this.tv = tv;
            manager = context.getSystemService(MediaSessionManager.class);
            activeChanged = ignored -> changed.run();
        }

        synchronized JSONObject report() throws Exception {
            sent.clear();
            JSONArray sources = new JSONArray();
            JSONObject result = new JSONObject().put("device", deviceName(context)).put("status", "off")
                .put("sources", sources).put("pushOnly", true);
            if (closed) return result;
            if (!tv && !enabled(context)) {
                ids.clear();
                covers.clear();
                stopObserving();
                return result;
            }
            List<MediaController> active;
            try { active = sessions(context); }
            catch (SecurityException denied) { stopObserving(); return result.put("status", "permission"); }
            if (!monitoring && manager != null) {
                try {
                    manager.addOnActiveSessionsChangedListener(activeChanged,
                        new ComponentName(context, PhoneMedia.class), callbacks);
                    monitoring = true;
                } catch (SecurityException ignored) { }
            }
            Set<String> blocked = Pairing.prefs(context).getStringSet(BLOCKED, Collections.emptySet());
            boolean includeCovers = Pairing.prefs(context).getBoolean(tv ? TvHostService.INCLUDE_COVERS : COVERS, true);
            if (!includeCovers) covers.clear();
            List<MediaSession.Token> kept = new ArrayList<>();
            for (MediaController item : active) {
                if (sources.length() == 4) break;
                if (blocked.contains(item.getPackageName())) continue;
                PlaybackState state = item.getPlaybackState();
                MediaMetadata info = item.getMetadata();
                if (state == null && info == null) continue;
                MediaSession.Token token = item.getSessionToken();
                kept.add(token);
                observe(token, item);
                String id = ids.computeIfAbsent(token, key -> UUID.randomUUID().toString().replace("-", ""));
                String title = title(info);
                long duration = info == null ? 0 : info.getLong(MediaMetadata.METADATA_KEY_DURATION);
                int mode = state == null ? PlaybackState.STATE_NONE : state.getState();
                boolean playing = mode == PlaybackState.STATE_PLAYING;
                long actions = state == null ? 0 : state.getActions();
                long position = state == null ? 0 : state.getPosition();
                double rate = playing && Float.isFinite(state.getPlaybackSpeed()) ? Math.max(0, state.getPlaybackSpeed()) : 0;
                if (state != null && state.getLastPositionUpdateTime() > 0)
                    position += (long) (Math.max(0, SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * rate);
                JSONObject source = new JSONObject().put("id", id).put("app", appLabel(context, item.getPackageName()))
                    .put("title", title).put("artist", info == null ? "" : clean(info.getText(MediaMetadata.METADATA_KEY_ARTIST), 120))
                    .put("playing", playing).put("toggle", has(actions, PlaybackState.ACTION_PLAY_PAUSE |
                        (playing ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY)))
                    .put("previous", has(actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS))
                    .put("next", has(actions, PlaybackState.ACTION_SKIP_TO_NEXT))
                    .put("seek", duration > 0 && has(actions, PlaybackState.ACTION_SEEK_TO))
                    .put("duration", Math.max(0, duration)).put("position", Math.max(0, position)).put("rate", rate);
                RatingActions ratings = ratingActions(item, state);
                source.put("rating", ratings.supported).put("ratingSelected", ratings.selected);
                if (includeCovers) {
                    String encoded = cover(token, item.getPackageName(), title, info);
                    if (!tv || encoded.length() <= 12000) source.put("cover", encoded);
                }
                sources.put(source);
                sent.put(id, item);
            }
            ids.keySet().retainAll(kept);
            covers.keySet().retainAll(kept);
            java.util.Iterator<Map.Entry<MediaSession.Token, Observation>> listeners = observed.entrySet().iterator();
            while (listeners.hasNext()) {
                Map.Entry<MediaSession.Token, Observation> listener = listeners.next();
                if (!ids.containsKey(listener.getKey())) {
                    listener.getValue().detach();
                    coverChanged.remove(listener.getKey());
                    listeners.remove();
                }
            }
            android.media.AudioManager audio = context.getSystemService(android.media.AudioManager.class);
            if (audio != null && !audio.isVolumeFixed()) {
                int stream = android.media.AudioManager.STREAM_MUSIC;
                int maximum = audio.getStreamMaxVolume(stream);
                if (maximum > 0) result.put("volume", Math.round(100f * audio.getStreamVolume(stream) / maximum));
            }
            return result.put("status", "ready");
        }

        synchronized void close() {
            closed = true;
            stopObserving();
        }

        private void stopObserving() {
            if (monitoring) manager.removeOnActiveSessionsChangedListener(activeChanged);
            monitoring = false;
            for (Observation item : observed.values()) item.detach();
            observed.clear();
            coverChanged.clear();
        }

        private void observe(MediaSession.Token token, MediaController controller) {
            if (observed.containsKey(token)) return;
            MediaController.Callback callback = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState state) { changed.run(); }
                @Override public void onSessionDestroyed() { changed.run(); }
                @Override public void onMetadataChanged(MediaMetadata metadata) {
                    coverChanged.add(token);
                    changed.run();
                }
            };
            controller.registerCallback(callback, callbacks);
            observed.put(token, new Observation(controller, callback));
        }

        synchronized boolean apply(JSONObject answer) {
            JSONArray commands = answer.optJSONArray("commands");
            if (closed || (!tv && !enabled(context)) || commands == null || commands.length() == 0 || !hasAccess(context)) return false;
            Set<String> blocked = Pairing.prefs(context).getStringSet(BLOCKED, Collections.emptySet());
            for (int i = 0; i < Math.min(commands.length(), 8); i++) {
                JSONObject command = commands.optJSONObject(i);
                if (command == null) continue;
                if ("volume".equals(command.optString("action"))) {
                    try { setVolume(command.optInt("positionMs", -1)); }
                    catch (RuntimeException ignored) { }
                    continue;
                }
                MediaController controller = sent.get(command.optString("id"));
                if (controller == null || blocked.contains(controller.getPackageName())) continue;
                try { dispatch(controller, command); }
                catch (RuntimeException ignored) { }
            }
            return true;
        }

        private void setVolume(int level) {
            android.media.AudioManager audio = context.getSystemService(android.media.AudioManager.class);
            if (level < 0 || level > 100 || audio == null || audio.isVolumeFixed()) return;
            int stream = android.media.AudioManager.STREAM_MUSIC;
            audio.setStreamVolume(stream, Math.round(level * audio.getStreamMaxVolume(stream) / 100f), 0);
        }

        private RatingActions ratingActions(MediaController controller, PlaybackState state) {
            RatingActions result = new RatingActions();
            if (state == null) return result;
            if (has(state.getActions(), PlaybackState.ACTION_SET_RATING)) {
                int style = controller.getRatingType();
                if (style == Rating.RATING_THUMB_UP_DOWN) result.standard = 3;
                else if (style == Rating.RATING_HEART) result.standard = 1;
                MediaMetadata info = controller.getMetadata();
                Rating user = info == null ? null : info.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
                if (user != null && user.isRated() && user.getRatingStyle() == style) {
                    if (style == Rating.RATING_THUMB_UP_DOWN) result.selected = user.isThumbUp() ? 1 : 2;
                    else if (style == Rating.RATING_HEART && user.hasHeart()) result.selected = 1;
                }
            }
            result.supported = result.standard;
            String owner = controller.getPackageName();
            if ("com.soundcloud.android".equals(owner)) {
                for (PlaybackState.CustomAction action : state.getCustomActions()) {
                    if ("LikeRatingAction".contentEquals(action.getName()) ||
                        "UnLikeRatingAction".contentEquals(action.getName())) {
                        result.like = action;
                        result.supported |= 1;
                        if (soundCloudLiked(owner, action.getIcon())) result.selected |= 1;
                        break;
                    }
                }
            } else if ("ru.yandex.music".equals(owner)) {
                // Yandex Music includes the current selection in each custom action label.
                for (PlaybackState.CustomAction action : state.getCustomActions()) {
                    String label = action.getName().toString();
                    if (label.equalsIgnoreCase("Like Selected") || label.equalsIgnoreCase("Like Not selected")) {
                        result.like = action;
                        result.supported |= 1;
                        result.selected = (result.selected & ~1) | (label.equalsIgnoreCase("Like Selected") ? 1 : 0);
                    } else if (label.equalsIgnoreCase("Dislike Selected") || label.equalsIgnoreCase("Dislike Not selected")) {
                        result.dislike = action;
                        result.supported |= 2;
                        result.selected = (result.selected & ~2) | (label.equalsIgnoreCase("Dislike Selected") ? 2 : 0);
                    }
                }
            }
            return result;
        }

        private boolean soundCloudLiked(String owner, int icon) {
            try {
                Resources resources = context.getPackageManager().getResourcesForApplication(owner);
                String name = resources.getResourceEntryName(icon);
                return name.equals("ic_actions_heart_active") || name.startsWith("ic_actions_heart_active_");
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
                return false;
            }
        }

        private static final class RatingActions {
            int standard, supported, selected;
            PlaybackState.CustomAction like, dislike;
        }

        private void dispatch(MediaController controller, JSONObject command) {
            PlaybackState state = controller.getPlaybackState();
            if (state == null) return;
            long actions = state.getActions();
            MediaController.TransportControls controls = controller.getTransportControls();
            switch (command.optString("action")) {
                case "like":
                case "dislike":
                    boolean like = "like".equals(command.optString("action"));
                    if (!title(controller.getMetadata()).equals(command.optString("title"))) break;
                    RatingActions ratings = ratingActions(controller, state);
                    PlaybackState.CustomAction custom = like ? ratings.like : ratings.dislike;
                    if (custom != null) controls.sendCustomAction(custom, custom.getExtras());
                    else if ((ratings.standard & (like ? 1 : 2)) != 0)
                        controls.setRating(ratings.standard == 3 ? Rating.newThumbRating(like) : Rating.newHeartRating(true));
                    break;
                case "play":
                    if (has(actions, PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) controls.play();
                    break;
                case "pause":
                    if (has(actions, PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)) controls.pause();
                    break;
                case "toggle":
                    boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
                    if (has(actions, PlaybackState.ACTION_PLAY_PAUSE |
                        (playing ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY))) {
                        if (playing) controls.pause();
                        else controls.play();
                    }
                    break;
                case "previous":
                    if (has(actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS)) controls.skipToPrevious();
                    break;
                case "next":
                    if (has(actions, PlaybackState.ACTION_SKIP_TO_NEXT)) controls.skipToNext();
                    break;
                case "seek":
                    MediaMetadata info = controller.getMetadata();
                    long position = command.optLong("positionMs", -1);
                    if (info != null && title(info).equals(command.optString("title")) && position >= 0 &&
                        position <= info.getLong(MediaMetadata.METADATA_KEY_DURATION) &&
                        has(actions, PlaybackState.ACTION_SEEK_TO)) controls.seekTo(position);
                    break;
                default: break;
            }
        }

        private String cover(MediaSession.Token token, String owner, String title, MediaMetadata metadata) {
            long now = SystemClock.elapsedRealtime();
            Cover previous = covers.get(token);
            if (!coverChanged.remove(token) && previous != null && title.equals(previous.title) && now - previous.checked < 3000)
                return previous.data;
            Bitmap bitmap = artwork(owner, metadata);
            if (bitmap == null) bitmap = appIcon(owner);
            String encoded = "";
            if (bitmap != null) {
                float ratio = 128f / Math.max(bitmap.getWidth(), bitmap.getHeight());
                Bitmap scaled = ratio < 1 ? Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * ratio)),
                    Math.max(1, Math.round(bitmap.getHeight() * ratio)), true) : bitmap;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (scaled.compress(Bitmap.CompressFormat.JPEG, 67, bytes) && bytes.size() <= 18000)
                    encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
            }
            covers.put(token, new Cover(title, now, encoded));
            return encoded;
        }

        private Bitmap artwork(String owner, MediaMetadata metadata) {
            if (metadata == null) return null;
            MediaDescription description = metadata.getDescription();
            Bitmap bitmap = description.getIconBitmap();
            if (bitmap != null) return bitmap;
            Uri uri = description.getIconUri();
            if (uri == null || !"content".equals(uri.getScheme())) return null;
            ProviderInfo provider = context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
            if (provider == null || !owner.equals(provider.packageName)) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) return null;
                BitmapFactory.decodeStream(stream, null, options);
            } catch (IOException | RuntimeException ignored) { return null; }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            int dimension = Math.max(options.outWidth, options.outHeight);
            while (dimension / options.inSampleSize > 256) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                return stream == null ? null : BitmapFactory.decodeStream(stream, null, options);
            } catch (IOException | RuntimeException ignored) { return null; }
        }

        private Bitmap appIcon(String owner) {
            try {
                Drawable drawable = context.getPackageManager().getApplicationIcon(owner);
                Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
                drawable.draw(new Canvas(bitmap));
                return bitmap;
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                return null;
            }
        }
    }

    private static String title(MediaMetadata info) {
        return info == null ? "" : clean(info.getDescription().getTitle(), 160);
    }

    private static boolean has(long actions, long flag) { return (actions & flag) != 0; }

    private static final class Observation {
        final MediaController controller;
        final MediaController.Callback callback;
        Observation(MediaController controller, MediaController.Callback callback) {
            this.controller = controller;
            this.callback = callback;
        }
        void detach() { controller.unregisterCallback(callback); }
    }

    private static final class Cover {
        final String title, data;
        final long checked;
        Cover(String title, long checked, String data) {
            this.title = title;
            this.checked = checked;
            this.data = data;
        }
    }
}
