package com.codex.pocketplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Pairing {
    private static final String ALIAS = "pocket-player-access";
    final String fingerprint;
    final List<String> addresses;
    final int port;
    final String secret;
    final String name;

    Pairing(String fingerprint, List<String> addresses, int port, String secret, String name) {
        this.fingerprint = fingerprint;
        this.addresses = java.util.Collections.unmodifiableList(new ArrayList<>(addresses));
        this.port = port;
        this.secret = secret;
        this.name = name;
    }

    static Pairing fromQr(String text) {
        if (text == null || text.length() > 900) throw new IllegalArgumentException();
        Uri uri = Uri.parse(text.trim());
        if (!"pocketplayer".equals(uri.getScheme()) || !"pair".equals(uri.getAuthority()) ||
            !"2".equals(uri.getQueryParameter("v"))) throw new IllegalArgumentException();
        String pin = uri.getQueryParameter("key");
        String code = uri.getQueryParameter("code");
        String peers = uri.getQueryParameter("ip");
        if (pin == null || !pin.matches("[0-9a-f]{64}") || code == null || !code.matches("[A-Za-z0-9_-]{32}")
            || peers == null || !"43198".equals(uri.getQueryParameter("port"))) throw new IllegalArgumentException();
        List<String> addresses = new ArrayList<>();
        for (String address : peers.split(",")) {
            if (addresses.size() < 8 && localAddress(address) && !addresses.contains(address)) addresses.add(address);
        }
        if (addresses.isEmpty()) throw new IllegalArgumentException();
        return new Pairing(pin, addresses, 43198, code, "");
    }

    static boolean localAddress(String address) {
        if (address == null || !address.matches("([0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        try {
            InetAddress ip = InetAddress.getByName(address);
            return ip.isSiteLocalAddress() || ip.isLinkLocalAddress();
        } catch (Exception ignored) { return false; }
    }

    String serviceName() { return "pp-" + fingerprint.substring(0, 16); }

    String lastAddress(Context context) {
        return prefs(context).getString("peer-" + fingerprint, "");
    }

    void rememberAddress(Context context, String address) {
        if (localAddress(address) && !address.equals(lastAddress(context)))
            prefs(context).edit().putString("peer-" + fingerprint, address).apply();
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("pocket-player", Context.MODE_PRIVATE);
    }

    static Pairing read(Context context) {
        String saved = prefs(context).getString("connection", null);
        if (saved == null) return null;
        try {
            JSONObject data = new JSONObject(saved);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false),
                new GCMParameterSpec(128, Base64.decode(data.getString("iv"), Base64.NO_WRAP)));
            String token = new String(cipher.doFinal(Base64.decode(data.getString("token"), Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
            JSONArray list = data.getJSONArray("addresses");
            List<String> addresses = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) addresses.add(list.getString(i));
            return new Pairing(data.getString("key"), addresses, data.getInt("port"), token, data.getString("name"));
        } catch (Exception exception) { return null; }
    }

    void save(Context context, String theme, String accent, boolean differentComputer) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        JSONObject data = new JSONObject();
        data.put("key", fingerprint);
        data.put("addresses", new JSONArray(addresses));
        data.put("port", port);
        data.put("name", name);
        data.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        data.put("token", Base64.encodeToString(cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        SharedPreferences.Editor edit = prefs(context).edit().putString("connection", data.toString())
            .putString("theme", theme).putString("accent", accent);
        if (differentComputer) edit.putBoolean(PhoneMedia.ENABLED, false);
        if (!edit.commit()) throw new java.io.IOException("Could not save credentials");
    }

    static void clear(Context context) {
        SharedPreferences stored = prefs(context);
        SharedPreferences.Editor edit = stored.edit().remove("connection").remove("source");
        for (String key : stored.getAll().keySet()) if (key.startsWith("peer-")) edit.remove(key);
        edit.apply();
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null || !create) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
