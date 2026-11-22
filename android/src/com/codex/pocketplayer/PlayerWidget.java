package com.codex.pocketplayer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public abstract class PlayerWidget extends AppWidgetProvider {
    private static final String CONTROL = "com.codex.pocketplayer.control";
    private static final Map<Integer, Integer> panels = new HashMap<>();
    private static final int[] rows = {R.id.choice_0, R.id.choice_1, R.id.choice_2};
    private static final int[] shortcuts = {R.id.shortcut_0, R.id.shortcut_1, R.id.shortcut_2};

    enum Format {
        COMPACT(CompactWidget.class, R.layout.compact, 1, true, true),
        BUTTONS(ControlsWidget.class, R.layout.controls, 1, false, false),
        MINI(LargeWidget.class, R.layout.large, 3, true, true),
        SQUARE(FullWidget.class, R.layout.player, 3, false, true);

        final Class<? extends PlayerWidget> provider;
        final int layout, pageSize;
        final boolean metadata, artwork;
        Format(Class<? extends PlayerWidget> provider, int layout, int pageSize, boolean metadata, boolean artwork) {
            this.provider = provider;
            this.layout = layout;
            this.pageSize = pageSize;
            this.metadata = metadata;
            this.artwork = artwork;
        }
    }

    protected abstract Format format();

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, views(context, format(), id));
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id,
        android.os.Bundle options) {
        manager.updateAppWidget(id, views(context, format(), id));
    }

    @Override public void onDeleted(Context context, int[] ids) {
        synchronized (panels) { for (int id : ids) panels.remove(id); }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!CONTROL.equals(intent.getAction())) {
            super.onReceive(context, intent);
            return;
        }
        Uri target = intent.getData();
        String action = target == null ? null : target.getHost();
        String argument = null;
        int id;
        try {
            if (target == null || target.getPathSegments().isEmpty()) return;
            id = Integer.parseInt(target.getPathSegments().get(0));
            if (target.getPathSegments().size() > 1) argument = target.getPathSegments().get(1);
        } catch (NumberFormatException ignored) { return; }
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || action == null) return;
        switch (action) {
            case "open-sources": changePage(context, id, 0, true); break;
            case "page-up": changePage(context, id, -1, false); break;
            case "page-down": changePage(context, id, 1, false); break;
            case "close-sources": closePanel(context, id); break;
            case "source":
                closePanel(context, id);
                if (PhoneLink.playback != null) PhoneLink.start(context, action, argument);
                break;
            case "toggle": case "previous": case "next": case "refresh":
                PhoneLink.start(context, action, argument);
                break;
        }
    }

    private void closePanel(Context context, int id) {
        synchronized (panels) { panels.remove(id); }
        AppWidgetManager.getInstance(context).updateAppWidget(id, views(context, format(), id));
    }

    private void changePage(Context context, int id, int step, boolean opening) {
        Playback playback = PhoneLink.playback;
        if (playback == null || playback.sources.isEmpty()) return;
        Format format = format();
        synchronized (panels) {
            int target = opening ? activeIndex(context, playback) / format.pageSize : panels.getOrDefault(id, 0) + step;
            panels.put(id, Math.max(0, Math.min(target, playback.sources.size() / format.pageSize)));
        }
        AppWidgetManager.getInstance(context).updateAppWidget(id, views(context, format, id));
    }

    private static int activeIndex(Context context, Playback playback) {
        String preferred = Pairing.prefs(context).getString("source", "");
        for (int i = 0; i < playback.sources.size(); i++)
            if (playback.sources.get(i).id.equals(preferred)) return i + 1;
        return 0;
    }

    static boolean hasWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (Format format : Format.values())
            if (manager.getAppWidgetIds(new ComponentName(context, format.provider)).length > 0) return true;
        return false;
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (Format format : Format.values())
            for (int id : manager.getAppWidgetIds(new ComponentName(context, format.provider)))
                manager.updateAppWidget(id, views(context, format, id));
    }

    private static RemoteViews views(Context context, Format format, int id) {
        Playback playback = PhoneLink.playback;
        Playback.Source session = playback == null ? null : playback.selected;
        Pairing pairing = PhoneLink.connection(context);
        boolean dark = Appearance.dark(context);
        Appearance.Palette color = Appearance.palette(context);
        int text = color.text;
        int dim = color.dim;
        int subdued = Appearance.mix(color.raised, dim, .52);
        boolean hasSources = playback != null && !playback.sources.isEmpty();
        Integer page;
        synchronized (panels) {
            if (!hasSources) panels.remove(id);
            page = panels.get(id);
        }
        int layout = page == null ? format.layout : format.pageSize > 1 ? R.layout.source_full : R.layout.source_small;
        RemoteViews view = new RemoteViews(context.getPackageName(), layout);
        view.setInt(R.id.widget_root, "setBackgroundResource", dark ? R.drawable.widget_dark : R.drawable.widget_light);
        tint(view, R.id.widget_root, color.panel);
        if (page != null) {
            sourcePanel(context, view, format, id, playback, page, color.accent, text, dim, subdued);
            return view;
        }
        view.setInt(R.id.toggle, "setBackgroundResource", dark ? R.drawable.play_green_dark : R.drawable.play_green_light);
        tint(view, R.id.toggle, color.accent);
        PendingIntent open = openApp(context);
        PendingIntent select = hasSources ? command(context, format, id, "open-sources", null) : null;
        if (format.metadata) {
            view.setTextColor(R.id.title, text);
            view.setTextViewText(R.id.title, session != null ? session.displayTitle() :
                pairing == null ? UiText.text(context, "Подключить ПК") : playback == null ? UiText.text(context, "ПК недоступен") : UiText.text(context, "Нет воспроизведения"));
            view.setOnClickPendingIntent(R.id.title, pairing == null ? open : select);
            view.setTextColor(R.id.subtitle, dim);
            view.setTextViewText(R.id.subtitle, session != null ?
                (session.artist.isEmpty() ? session.app : session.artist) :
                pairing == null ? "CueRelay" : pairing.name);
            view.setOnClickPendingIntent(R.id.subtitle, pairing == null ? open : select);
        }
        if (format.artwork) {
            view.setInt(R.id.artwork, "setBackgroundResource", dark ? R.drawable.art_dark : R.drawable.art_light);
            tint(view, R.id.artwork, color.raised);
            if (playback != null && playback.art != null && Appearance.showArtwork(context))
                view.setImageViewBitmap(R.id.artwork, playback.art);
            else view.setImageViewResource(R.id.artwork, R.drawable.ic_note);
            view.setContentDescription(R.id.artwork, session == null ? UiText.text(context, "Обложка") :
                UiText.text(context, "Обложка: ") + session.displayTitle());
            view.setOnClickPendingIntent(R.id.artwork, pairing == null ? open : select);
        }
        boolean playing = session != null && session.playing;
        for (int idButton : format == Format.BUTTONS ? new int[]{R.id.previous, R.id.next, R.id.source} :
                new int[]{R.id.next, R.id.source}) {
            view.setInt(idButton, "setBackgroundResource", dark ? R.drawable.button_dark : R.drawable.button_light);
            tint(view, idButton, color.raised);
        }
        button(view, R.id.source, select, text, subdued);
        view.setContentDescription(R.id.source, UiText.text(context, "Выбрать источник"));
        if (format == Format.BUTTONS) {
            button(view, R.id.previous, session != null && session.previous ?
                command(context, format, id, "previous", session.id) : null, text, subdued);
            view.setContentDescription(R.id.previous, UiText.text(context, "Предыдущий трек"));
        }
        button(view, R.id.next, session != null && session.next ?
            command(context, format, id, "next", session.id) : null, text, subdued);
        view.setContentDescription(R.id.next, UiText.text(context, "Следующий трек"));

        int glyphColor = color.onAccent;
        for (int glyph : new int[]{R.id.play_glyph, R.id.pause_glyph, R.id.retry_glyph})
            view.setInt(glyph, "setColorFilter", glyphColor);
        boolean offline = playback == null && pairing != null;
        view.setDisplayedChild(R.id.toggle, offline ? 2 : playing ? 1 : 0);
        view.setContentDescription(R.id.toggle, pairing == null ? UiText.text(context, "Подключить ПК") :
            offline ? UiText.text(context, "Восстановить связь") : playing ? UiText.text(context, "Пауза") : UiText.text(context, "Воспроизвести"));
        view.setOnClickPendingIntent(R.id.toggle, pairing == null ? open : offline ?
            command(context, format, id, "refresh", null) : session != null && session.toggle ?
            command(context, format, id, "toggle", session.id) : null);
        if (format == Format.MINI) quickSources(context, view, id, playback, color, dark);
        return view;
    }

    private static void quickSources(Context context, RemoteViews view, int id, Playback playback,
        Appearance.Palette colors, boolean dark) {
        if (playback == null || playback.sources.size() < 2) {
            view.setViewVisibility(R.id.source_shortcuts, View.GONE);
            return;
        }
        view.setViewVisibility(R.id.source_shortcuts, View.VISIBLE);
        ArrayList<Playback.Source> visible = new ArrayList<>();
        if (playback.selected != null) visible.add(playback.selected);
        for (Playback.Source source : playback.sources) {
            if (visible.size() == shortcuts.length) break;
            if (source != playback.selected) visible.add(source);
        }
        for (int n = 0; n < shortcuts.length; n++) {
            int button = shortcuts[n];
            view.setViewVisibility(button, n < visible.size() ? View.VISIBLE : View.GONE);
            if (n >= visible.size()) continue;
            Playback.Source item = visible.get(n);
            view.setTextViewText(button, item.app);
            view.setContentDescription(button, UiText.text(context, "Источник: ") + item.app);
            view.setTextColor(button, item == playback.selected ? colors.accent : colors.text);
            view.setInt(button, "setBackgroundResource", dark ? R.drawable.button_dark : R.drawable.button_light);
            tint(view, button, item == playback.selected ? Appearance.mix(colors.raised, colors.accent, .20) : colors.raised);
            view.setOnClickPendingIntent(button, command(context, Format.MINI, id, "source", item.id));
        }
    }

    private static void tint(RemoteViews view, int id, int color) {
        if (Build.VERSION.SDK_INT >= 31)
            view.setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(color));
    }

    private static void sourcePanel(Context context, RemoteViews view, Format format, int id, Playback playback,
        int requested, int accent, int text, int dim, int subdued) {
        int count = playback.sources.size() + 1;
        int page = Math.min(requested, (count - 1) / format.pageSize);
        int first = page * format.pageSize;
        int active = activeIndex(context, playback);
        if (format.pageSize > 1) {
            view.setTextColor(R.id.source_heading, text);
            view.setTextViewText(R.id.source_heading, UiText.text(context, "Источники"));
        }
        for (int i = 0; i < format.pageSize; i++) {
            int row = rows[i], index = first + i;
            view.setViewVisibility(row, index < count ? View.VISIBLE : View.GONE);
            if (index >= count) continue;
            Playback.Source item = index == 0 ? null : playback.sources.get(index - 1);
            String label = item == null ? UiText.text(context, "Автовыбор") : item.app +
                (item.title.isEmpty() || item.title.equals(item.app) ? "" : " · " + item.title);
            if (format.pageSize == 1) label += "  ·  " + (index + 1) + "/" + count;
            view.setTextViewText(row, (index == active ? "✓  " : "") + label);
            view.setTextColor(row, index == active ? accent : dim);
            view.setOnClickPendingIntent(row, command(context, format, id, "source", item == null ? "" : item.id));
        }
        button(view, R.id.page_previous, page > 0 ? command(context, format, id, "page-up", null) : null, text, subdued);
        button(view, R.id.page_next, first + format.pageSize < count ?
            command(context, format, id, "page-down", null) : null, text, subdued);
        button(view, R.id.panel_close, command(context, format, id, "close-sources", null), text, subdued);
        view.setContentDescription(R.id.page_previous, UiText.text(context,
            format.pageSize > 1 ? "Предыдущая страница" : "Предыдущий источник"));
        view.setContentDescription(R.id.page_next, UiText.text(context,
            format.pageSize > 1 ? "Следующая страница" : "Следующий источник"));
        view.setContentDescription(R.id.panel_close, UiText.text(context, "Закрыть список"));
    }

    private static void button(RemoteViews view, int id, PendingIntent command, int normal, int disabled) {
        view.setInt(id, "setColorFilter", command == null ? disabled : normal);
        view.setOnClickPendingIntent(id, command);
    }

    private static PendingIntent command(Context context, Format format, int id, String action, String argument) {
        Intent intent = new Intent(context, format.provider).setAction(CONTROL)
            .setData(Uri.parse("pocketplayer-control://" + action + "/" + id + "/" + (argument == null ? "" : argument)));
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
