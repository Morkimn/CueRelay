package com.codex.pocketplayer;

import android.content.Context;
import android.os.Build;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

final class Diagnostics {
    private static boolean initialized;

    static synchronized void record(Context context, String component, String event, Throwable error) {
        try {
            File folder = new File(context.getCacheDir(), "logs");
            if (!folder.isDirectory() && !folder.mkdirs()) return;
            File current = new File(folder, "diagnostics.jsonl");
            File previous = new File(folder, "diagnostics.previous.jsonl");
            if (!initialized) {
                long threshold = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
                for (File file : new File[]{current, previous}) {
                    if (file.isFile() && file.lastModified() < threshold) file.delete();
                }
                initialized = true;
            }
            if (current.length() >= 512 * 1024) {
                if (previous.exists()) previous.delete();
                if (!current.renameTo(previous)) return;
            }
            JSONObject row = new JSONObject().put("ts", Instant.now().toString()).put("platform", "android")
                .put("version", ReleaseUpdates.currentVersion(context)).put("os", Build.VERSION.SDK_INT)
                .put("arch", Build.SUPPORTED_ABIS[0]).put("component", component).put("event", event)
                .put("error", error == null ? JSONObject.NULL : error.getClass().getSimpleName());
            try (FileOutputStream stream = new FileOutputStream(current, true)) {
                stream.write((row.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    static synchronized String read(Context context) throws IOException {
        File folder = new File(context.getCacheDir(), "logs");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (String name : new String[]{"diagnostics.previous.jsonl", "diagnostics.jsonl"}) {
                File file = new File(folder, name);
                if (!file.isFile()) continue;
                try (FileInputStream stream = new FileInputStream(file)) {
                    int count;
                    while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                }
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
