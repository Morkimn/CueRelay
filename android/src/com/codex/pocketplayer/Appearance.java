package com.codex.pocketplayer;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

final class Appearance {
    static final String SYNC = "sync-appearance";
    private Appearance() { }

    static String theme(Context context) {
        return Pairing.prefs(context).getString("theme", "system");
    }

    static boolean dark(Context context) {
        String theme = theme(context);
        if ("dark".equals(theme)) return true;
        if ("light".equals(theme)) return false;
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }

    static boolean showArtwork(Context context) {
        return Pairing.prefs(context).getBoolean("show-art", true);
    }

    static boolean systemMedia(Context context) {
        return Pairing.prefs(context).getBoolean("system-media", true);
    }

    static boolean sync(Context context) {
        return !context.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
            && Pairing.prefs(context).getBoolean(SYNC, true);
    }

    static void takeRemote(Context context, Playback state) {
        if (state == null || !state.syncAppearance || !sync(context)) return;
        if (!state.theme.equals(theme(context)) || !state.accent.equals(accentKey(context)))
            Pairing.prefs(context).edit().putString("theme", state.theme).putString("accent", state.accent).apply();
    }

    static String accentKey(Context context) {
        return Pairing.prefs(context).getString("accent", "green");
    }

    static Palette palette(Context context) { return palette(accentKey(context), dark(context)); }

    static int seed(String key) {
        if (key.matches("#[0-9a-fA-F]{6}")) return Color.parseColor(key);
        for (Colorway preset : Colorway.values()) if (preset.key.equals(key)) return preset.seed;
        return Colorway.GREEN.seed;
    }

    static Palette palette(String key, boolean night) { return new Palette(seed(key), night); }

    static int mix(int first, int second, double amount) {
        return Color.rgb((int)Math.round(Color.red(first) * (1 - amount) + Color.red(second) * amount),
            (int)Math.round(Color.green(first) * (1 - amount) + Color.green(second) * amount),
            (int)Math.round(Color.blue(first) * (1 - amount) + Color.blue(second) * amount));
    }

    static final class Palette {
        final int background, panel, raised, text, dim, accent, onAccent;
        Palette(int seed, boolean dark) {
            if (dark) {
                background = mix(0xff15151a, seed, .18);
                panel = mix(0xff202126, seed, .24);
                raised = mix(0xff303036, seed, .30);
                text = mix(0xfff8f8fb, seed, .055);
                dim = mix(0xffd0d0d7, seed, .17);
                accent = mix(seed, Color.WHITE, .59);
                onAccent = mix(0xff101015, seed, .26);
            } else {
                background = mix(0xfffcfcff, seed, .065);
                panel = mix(0xfff7f7fc, seed, .14);
                raised = mix(0xfff1f1f7, seed, .20);
                text = mix(0xff181822, seed, .075);
                dim = mix(0xff343442, seed, .23);
                int primary = mix(seed, 0xff15151a, .37);
                while (Color.luminance(primary) > .178) primary = mix(primary, Color.BLACK, .1);
                accent = primary;
                onAccent = Color.WHITE;
            }
        }
    }

    enum Colorway {
        GREEN("green", "Зелёный", 0xff4a9a65),
        BLUE("blue", "Голубой", 0xff468cc6),
        VIOLET("violet", "Сиреневый", 0xff865ac8),
        AMBER("amber", "Янтарный", 0xffb48430);

        final String key, label;
        final int seed;
        Colorway(String key, String label, int seed) { this.key = key; this.label = label; this.seed = seed; }
    }
}
