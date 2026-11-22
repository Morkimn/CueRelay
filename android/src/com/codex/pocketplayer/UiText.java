package com.codex.pocketplayer;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class UiText {
    static final String LANGUAGE = "language";
    private static volatile Map<String, String> english;

    static String language(Context context) {
        return Pairing.prefs(context).getString(LANGUAGE, "ru");
    }

    static String text(Context context, String value) {
        return "en".equals(language(context)) ? english(context).getOrDefault(value, value) : value;
    }

    private static synchronized Map<String, String> english(Context context) {
        if (english != null) return english;
        Map<String, String> translations = new HashMap<>();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(
            context.getAssets().open("en.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                int split = line.indexOf('|');
                if (split > 0) translations.put(line.substring(0, split), line.substring(split + 1).replace("\\s", " "));
            }
        } catch (IOException ignored) { }
        return english = translations;
    }

    private UiText() { }
}
