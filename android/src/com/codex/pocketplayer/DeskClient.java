package com.codex.pocketplayer;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class DeskClient {
    private final Pairing pairing;
    private final X509TrustManager trust;
    private final SSLSocketFactory sockets;

    DeskClient(Pairing pairing) throws Exception {
        this.pairing = pairing;
        byte[] expected = new byte[32];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) Integer.parseInt(pairing.fingerprint.substring(i * 2, i * 2 + 2), 16);
        }
        trust = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificate");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    if (chain == null || chain.length == 0) throw new CertificateException("Missing certificate");
                    X509Certificate certificate = chain[0];
                    certificate.checkValidity();
                    byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
                    if (!MessageDigest.isEqual(expected, fingerprint)) throw new CertificateException("Unknown computer");
                } catch (Exception exception) { throw new CertificateException(exception); }
            }
        };
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[] { trust }, null);
        sockets = ssl.getSocketFactory();
    }

    JSONObject json(String address, String path, JSONObject body, boolean authenticated, int timeout) throws Exception {
        byte[] bytes = request(address, path, body, authenticated, timeout, 96 * 1024);
        return bytes.length == 0 ? new JSONObject() : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    byte[] art(String address, String key) throws Exception {
        if (!key.matches("[0-9a-f]{64}")) throw new IOException("Invalid artwork");
        return request(address, "/art/" + key, null, true, 3000, 180 * 1024);
    }

    private byte[] request(String address, String path, JSONObject body, boolean authenticated, int timeout, int limit)
        throws Exception {
        if (!Pairing.localAddress(address)) throw new IOException("Address must be local");
        URL url = new URL("https", address, pairing.port, path);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection(Proxy.NO_PROXY);
        try {
            connection.setSSLSocketFactory(sockets);
            // The QR fingerprint identifies the peer even when its DHCP address changes.
            connection.setHostnameVerifier((host, session) -> {
                try {
                    trust.checkServerTrusted(new X509Certificate[] { (X509Certificate) session.getPeerCertificates()[0] }, "");
                    return true;
                } catch (Exception exception) { return false; }
            });
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(1400);
            connection.setReadTimeout(timeout);
            connection.setUseCaches(false);
            if (authenticated) connection.setRequestProperty("Authorization", "Bearer " + pairing.secret);
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(data.length);
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            }
            int status = connection.getResponseCode();
            if (status != 200 && status != 204) throw new Status(status);
            if (status == 204) return new byte[0];
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (out.size() + count > limit) throw new IOException("Response too large");
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    static final class Status extends IOException {
        final int code;
        Status(int code) { super("HTTP " + code); this.code = code; }
    }
}
