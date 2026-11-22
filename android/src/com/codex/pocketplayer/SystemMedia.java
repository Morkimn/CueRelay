package com.codex.pocketplayer;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import java.util.function.BiConsumer;

final class SystemMedia implements AutoCloseable {
    private static final String CYCLE = "tempo.cycle-source";
    private final Context context;
    private final MediaSession session;
    private Playback.Source current;

    SystemMedia(Context context, Handler main, PendingIntent open, BiConsumer<String, String> command) {
        this.context = context;
        session = new MediaSession(context, "Tempo on Windows");
        session.setSessionActivity(open);
        android.os.Bundle slots = new android.os.Bundle();
        slots.putBoolean("android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_PREVIOUS", true);
        slots.putBoolean("android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_NEXT", true);
        session.setExtras(slots);
        session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_FIXED, 1, 1) { });
        session.setCallback(new MediaSession.Callback() {
            private void send(String action) {
                if (current != null) command.accept(action, current.id);
            }
            @Override public void onPlay() { send("play"); }
            @Override public void onPause() { send("pause"); }
            @Override public void onStop() { send("pause"); }
            @Override public void onSkipToPrevious() { send("previous"); }
            @Override public void onSkipToNext() { send("next"); }
            @Override public void onSeekTo(long position) {
                if (current != null && current.timeline != null && current.timeline.canSeek)
                    command.accept("seek", current.id + ":" + position);
            }
            @Override public void onCustomAction(String action, android.os.Bundle extras) {
                if (CYCLE.equals(action)) command.accept("cycle-source", null);
            }
        }, main);
    }

    boolean apply(Notification.Builder builder, Playback playback) {
        if (!Appearance.systemMedia(context) || playback == null || playback.selected == null) {
            current = null;
            session.setActive(false);
            return false;
        }
        current = playback.selected;
        long actions = 0;
        if (current.toggle) actions |= PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE;
        if (current.previous) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if (current.next) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        Playback.Timeline timeline = current.timeline;
        if (timeline != null && timeline.canSeek) actions |= PlaybackState.ACTION_SEEK_TO;
        PlaybackState.Builder state = new PlaybackState.Builder().setActions(actions)
            .setState(current.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                timeline == null ? PlaybackState.PLAYBACK_POSITION_UNKNOWN : timeline.now(),
                timeline == null ? current.playing ? 1f : 0f : (float) timeline.rate);
        if (playback.sources.size() > 1)
            state.addCustomAction(new PlaybackState.CustomAction.Builder(CYCLE, UiText.text(context, "Сменить источник"), R.drawable.ic_source).build());
        session.setPlaybackState(state.build());
        String byline = current.artist.isEmpty() ? current.app : current.artist;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, current.displayTitle())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, byline);
        if (timeline != null) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, timeline.duration);
        if (playback.art != null && Appearance.showArtwork(context)) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, playback.art);
            builder.setLargeIcon(playback.art);
        }
        session.setMetadata(metadata.build());
        session.setActive(true);

        if (current.previous) add(builder, R.drawable.ic_previous, UiText.text(context, "Предыдущий трек"), "previous");
        if (current.toggle) add(builder, current.playing ? R.drawable.ic_pause : R.drawable.ic_play,
            current.playing ? UiText.text(context, "Пауза") : UiText.text(context, "Воспроизвести"), current.playing ? "pause" : "play");
        if (current.next) add(builder, R.drawable.ic_next, UiText.text(context, "Следующий трек"), "next");
        int count = (current.previous ? 1 : 0) + (current.toggle ? 1 : 0) + (current.next ? 1 : 0);
        int[] visible = new int[count];
        for (int i = 0; i < count; i++) visible[i] = i;
        if (playback.sources.size() > 1) add(builder, R.drawable.ic_source, UiText.text(context, "Сменить источник"), "cycle-source");
        builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(visible))
            .setContentTitle(current.displayTitle()).setContentText(byline)
            .setSubText("CueRelay · " + playback.computer)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(Appearance.palette(context).accent)
            .setColorized(playback.art == null);
        return true;
    }

    private void add(Notification.Builder builder, int icon, String name, String action) {
        Intent intent = new Intent(context, PhoneLink.class).setAction(action)
            .setData(android.net.Uri.parse("pocketplayer-media://" + action + "/" + current.id))
            .putExtra("argument", current.id);
        PendingIntent target = PendingIntent.getForegroundService(context, 20, intent, PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, icon), name, target).build());
    }

    @Override public void close() { session.release(); }
}
