package com.codex.pocketplayer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import javax.net.ssl.HttpsURLConnection;

final class ReleaseUpdates {
    static final String APK = "CueRelay.apk";
    private static final String PATTERN = "(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})";

    static String currentVersion(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException impossible) { return "0.0.0"; }
    }

    static Release check(Context context) throws Exception {
        String[] channel;
        try (InputStream input = context.getAssets().open("Update-Channel.txt")) {
            channel = new String(read(input, 2048), StandardCharsets.UTF_8).split("\\s+");
        }
        String root = "https://github.com/" + channel[0] + "/releases";
        byte[] manifest = download(root + "/latest/download/CueRelay-Update.json", 16384);
        byte[] signature = download(root + "/latest/download/CueRelay-Update.sig", 512);
        Signature publicKey = Signature.getInstance("SHA256withRSA");
        publicKey.initVerify(KeyFactory.getInstance("RSA").generatePublic(
            new X509EncodedKeySpec(Base64.decode(channel[1], Base64.DEFAULT))));
        publicKey.update(manifest);
        if (!publicKey.verify(signature)) throw new java.security.SignatureException("Invalid release signature");
        JSONObject data = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
        String version = data.getString("version");
        if (!version.matches(PATTERN)) throw new IOException("Invalid release version");
        if (!newer(version, currentVersion(context))) return null;
        JSONObject asset = data.getJSONObject("assets").optJSONObject(APK);
        if (asset == null) return null;
        String hash = asset.getString("sha256");
        long length = asset.getLong("size");
        if (!hash.matches("[A-Fa-f0-9]{64}") || length < 1 || length > 25_000_000)
            throw new IOException("Invalid release asset");
        return new Release(version, root + "/download/v" + version + "/" + APK, hash, length);
    }

    static Intent downloadInstaller(Context context, Release release) throws Exception {
        File folder = new File(context.getCacheDir(), "updates");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Update cache unavailable");
        File temporary = File.createTempFile("update-", ".apk", folder);
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            HttpsURLConnection connection = connect(release.url);
            long length = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    length += count;
                    if (length > release.length) throw new IOException("Unexpected release size");
                    output.write(buffer, 0, count);
                    hash.update(buffer, 0, count);
                }
            } finally { connection.disconnect(); }
            byte[] expected = new byte[32];
            for (int i = 0; i < expected.length; i++)
                expected[i] = (byte) Integer.parseInt(release.hash.substring(i * 2, i * 2 + 2), 16);
            if (length != release.length || !MessageDigest.isEqual(expected, hash.digest()))
                throw new java.security.SignatureException("Invalid release payload");
            android.content.pm.PackageInfo candidate = context.getPackageManager().getPackageArchiveInfo(temporary.getPath(), 0);
            if (candidate == null || !context.getPackageName().equals(candidate.packageName) ||
                !release.version.equals(candidate.versionName)) throw new IOException("Unexpected Android package");
            if (!temporary.renameTo(new File(folder, APK))) throw new IOException("Could not stage installer");
            Uri uri = SharedFiles.uri(context, APK);
            Intent installer = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installer.setClipData(android.content.ClipData.newRawUri("CueRelay installer", uri));
            return installer;
        } finally { temporary.delete(); }
    }

    private static boolean newer(String candidate, String installed) {
        if (!installed.matches(PATTERN)) return false;
        String[] left = candidate.split("\\."), right = installed.split("\\.");
        for (int i = 0; i < 3; i++) {
            int delta = Integer.parseInt(left[i]) - Integer.parseInt(right[i]);
            if (delta != 0) return delta > 0;
        }
        return false;
    }

    private static byte[] download(String url, int size) throws IOException {
        HttpsURLConnection connection = connect(url);
        try (InputStream stream = connection.getInputStream()) { return read(stream, size); }
        finally { connection.disconnect(); }
    }

    private static HttpsURLConnection connect(String url) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(12000);
        connection.setUseCaches(false);
        return connection;
    }

    private static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > limit) throw new IOException("Release metadata too large");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    static final class Release {
        final String version, url, hash;
        final long length;
        Release(String version, String url, String hash, long length) {
            this.version = version;
            this.url = url;
            this.hash = hash;
            this.length = length;
        }
    }
}
