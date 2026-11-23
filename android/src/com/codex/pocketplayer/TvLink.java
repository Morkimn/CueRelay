package com.codex.pocketplayer;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.util.Base64;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONObject;

final class TvLink {
    static final int PORT = BuildProfile.PORT + 10;
    static final String STORAGE = "tv-connection";
    static final String TLS = "TLSv1.2";
    private static final SecureRandom RANDOM = new SecureRandom();

    static Pairing invitation(String text) {
        if (text == null || text.length() > 900) throw new IllegalArgumentException();
        Uri uri = Uri.parse(text.trim());
        String address = uri.getQueryParameter("ip"), pin = uri.getQueryParameter("key"), code = uri.getQueryParameter("code");
        if (!"pocketplayer".equals(uri.getScheme()) || !"tv".equals(uri.getAuthority()) ||
            !"1".equals(uri.getQueryParameter("v")) || !Integer.toString(PORT).equals(uri.getQueryParameter("port")) ||
            !Pairing.localAddress(address) || pin == null || !pin.matches("[0-9a-f]{64}") || !validToken(code))
            throw new IllegalArgumentException();
        return new Pairing(pin, Collections.singletonList(address), PORT, code, "");
    }

    static boolean validToken(String value) { return value != null && value.matches("[A-Za-z0-9_-]{32}"); }

    static String randomToken() {
        byte[] key = new byte[24];
        RANDOM.nextBytes(key);
        return Base64.encodeToString(key, Base64.NO_PADDING | Base64.NO_WRAP | Base64.URL_SAFE);
    }

    static String digest(byte[] data) throws Exception {
        char[] result = new char[64];
        String hex = "0123456789abcdef";
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        for (int i = 0; i < hash.length; i++) {
            result[i * 2] = hex.charAt((hash[i] & 255) >>> 4);
            result[i * 2 + 1] = hex.charAt(hash[i] & 15);
        }
        return new String(result);
    }

    static boolean matches(String stored, String actual) {
        return stored != null && MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
            actual.getBytes(StandardCharsets.US_ASCII));
    }

    private final Pairing connection;
    private final SSLSocketFactory factory;
    private final ConnectivityManager connectivity;
    private final String deviceName;

    TvLink(Context context, Pairing connection) throws Exception {
        this.connection = connection;
        connectivity = context.getSystemService(ConnectivityManager.class);
        deviceName = PhoneMedia.deviceName(context);
        factory = DeskClient.sockets(DeskClient.pinnedTrust(connection.fingerprint));
    }

    static boolean physical(ConnectivityManager manager, Network network) {
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        return caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private Network networkFor(String address) throws IOException {
        if (connectivity == null) return null;
        byte[] destination = java.net.InetAddress.getByName(address).getAddress();
        for (Network candidate : connectivity.getAllNetworks()) {
            if (!physical(connectivity, candidate)) continue;
            LinkProperties properties = connectivity.getLinkProperties(candidate);
            if (properties == null) continue;
            for (LinkAddress local : properties.getLinkAddresses()) {
                byte[] source = local.getAddress().getAddress();
                if (source.length != 4) continue;
                int remaining = local.getPrefixLength(), index = 0;
                while (remaining > 0) {
                    int bits = Math.min(8, remaining);
                    if (((source[index] ^ destination[index]) & (255 << (8 - bits))) != 0) break;
                    index++;
                    remaining -= bits;
                }
                if (remaining == 0) return candidate;
            }
        }
        return null;
    }

    JSONObject exchange(String operation, JSONObject command) throws Exception {
        String address = connection.addresses.get(0);
        if (!Pairing.localAddress(address) || connection.port != PORT) throw new IOException("Invalid TV");
        Network network = networkFor(address);
        try (Socket tcp = network == null ? new Socket() : network.getSocketFactory().createSocket()) {
            tcp.connect(new InetSocketAddress(address, PORT), 1600);
            try (SSLSocket tls = (SSLSocket) factory.createSocket(tcp, address, PORT, true)) {
                tls.setEnabledProtocols(new String[]{TLS});
                tls.setSoTimeout(2400);
                tls.startHandshake();
                JSONObject request = new JSONObject().put("op", operation).put("token", connection.secret);
                if ("pair".equals(operation) || "state".equals(operation)) request.put("device", deviceName);
                if (command != null) request.put("command", command);
                DataOutputStream out = new DataOutputStream(tls.getOutputStream());
                out.writeUTF(request.toString());
                out.flush();
                return new JSONObject(new DataInputStream(tls.getInputStream()).readUTF());
            }
        }
    }
}
