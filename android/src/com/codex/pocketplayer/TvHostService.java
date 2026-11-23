package com.codex.pocketplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TvHostService extends Service {
    private static final String ALIAS = "cuerelay-tv-ec";
    private static final String CHANNEL = "cuerelay-tv-control";
    private static final String HASH = "tv-controller-hash";
    private static final String CONTROLLERS = "tv-controller-hashes";
    private static final int MAX_CONTROLLERS = 16;
    static final String ENABLED = "tv-host-enabled";
    static final String KEEP_AT_BOOT = "tv-start-at-boot";
    static final String INCLUDE_COVERS = "tv-include-artwork";
    static volatile TvHostService instance;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile SSLServerSocket server;
    private volatile SSLSocket active;
    private volatile boolean running = true;
    private PhoneMedia.Exchange media;
    private String address, fingerprint, invitation;
    private long expires;
    volatile boolean failed;

    static void start(Context context) {
        context.startForegroundService(new Intent(context, TvHostService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        media = new PhoneMedia.Exchange(this, ui, () -> {}, true);
        worker.execute(this::serve);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, UiText.text(this, "Пульт Android TV"),
            NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 31, new Intent(this, TvHostActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(31, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle("CueRelay").setContentText(UiText.text(this, "Управление телевизором включено"))
            .setContentIntent(open).setOnlyAlertOnce(true).build());
        return START_STICKY;
    }

    synchronized boolean issueCode() {
        if (address == null || fingerprint == null || failed) return false;
        invitation = TvLink.randomToken();
        expires = SystemClock.elapsedRealtime() + 180_000;
        return true;
    }

    synchronized String qr() {
        if (invitation == null || SystemClock.elapsedRealtime() >= expires) return null;
        return new Uri.Builder().scheme("pocketplayer").authority("tv")
            .appendQueryParameter("v", "1").appendQueryParameter("ip", address)
            .appendQueryParameter("key", fingerprint).appendQueryParameter("code", invitation)
            .appendQueryParameter("port", Integer.toString(TvLink.PORT)).build().toString();
    }

    synchronized void revoke() {
        invitation = null;
        if (!Pairing.prefs(this).edit().remove(HASH).remove(CONTROLLERS).commit())
            throw new IllegalStateException("Could not revoke TV controllers");
    }

    static int controllerCount(Context context) { return controllers(context).size(); }

    private static java.util.Set<String> controllers(Context context) {
        android.content.SharedPreferences prefs = Pairing.prefs(context);
        java.util.Set<String> keys = new java.util.HashSet<>(prefs.getStringSet(CONTROLLERS, java.util.Collections.emptySet()));
        String old = prefs.getString(HASH, null);
        if (old != null) keys.add(old);
        return keys;
    }

    private void saveControllers(java.util.Set<String> keys) {
        if (!Pairing.prefs(this).edit().putStringSet(CONTROLLERS, keys).remove(HASH).commit())
            throw new IllegalStateException("Could not save TV controllers");
    }

    private void serve() {
        try {
            String ip = null;
            for (int i = 0; running && ip == null && i < 30; i++) {
                ip = localIp();
                if (ip == null) Thread.sleep(1000);
            }
            if (!running) return;
            if (ip == null) throw new IllegalStateException("No local address");
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
            keys.load(null);
            if (!keys.containsAlias(ALIAS)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
                generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateNotAfter(new java.util.Date(System.currentTimeMillis() + 315_360_000_000L)).build());
                generator.generateKeyPair();
            }
            X509Certificate certificate = (X509Certificate) keys.getCertificate(ALIAS);
            String pin = TvLink.digest(certificate.getEncoded());
            PrivateKey key = (PrivateKey) keys.getKey(ALIAS, null);
            X509ExtendedKeyManager manager = new X509ExtendedKeyManager() {
                @Override public String[] getClientAliases(String type, Principal[] issuers) { return null; }
                @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) { return null; }
                @Override public String[] getServerAliases(String type, Principal[] issuers) {
                    return "EC".equals(type) ? new String[]{ALIAS} : null;
                }
                @Override public String chooseServerAlias(String type, Principal[] issuers, Socket socket) {
                    return "EC".equals(type) ? ALIAS : null;
                }
                @Override public X509Certificate[] getCertificateChain(String alias) {
                    return ALIAS.equals(alias) ? new X509Certificate[]{certificate} : null;
                }
                @Override public PrivateKey getPrivateKey(String alias) { return ALIAS.equals(alias) ? key : null; }
            };
            SSLContext tls = SSLContext.getInstance(TvLink.TLS);
            tls.init(new KeyManager[]{manager}, null, null);
            try (SSLServerSocket listener = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket()) {
                server = listener;
                listener.setEnabledProtocols(new String[]{TvLink.TLS});
                listener.bind(new InetSocketAddress(InetAddress.getByName(ip), TvLink.PORT), 8);
                synchronized (this) { address = ip; fingerprint = pin; }
                while (running) {
                    try (SSLSocket peer = (SSLSocket) listener.accept()) {
                        active = peer;
                        if (!running) break;
                        peer.setSoTimeout(2200);
                        try { peer.startHandshake(); }
                        catch (javax.net.ssl.SSLException handshake) {
                            if (BuildProfile.DEV) android.util.Log.d("CueRelayTv", "TLS negotiation failed", handshake);
                            continue;
                        }
                        JSONObject request = new JSONObject(new DataInputStream(peer.getInputStream()).readUTF());
                        JSONObject answer = handle(request);
                        DataOutputStream out = new DataOutputStream(peer.getOutputStream());
                        out.writeUTF(answer.toString());
                        out.flush();
                    } catch (Exception ignored) {
                        if (running && listener.isClosed()) throw ignored;
                    } finally { active = null; }
                }
            }
        } catch (Exception error) {
            if (running) {
                failed = true;
                Diagnostics.record(this, "tv", "host_failed", error);
            }
        }
    }

    private String localIp() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return null;
        for (Network candidate : manager.getAllNetworks()) {
            if (!TvLink.physical(manager, candidate)) continue;
            LinkProperties network = manager.getLinkProperties(candidate);
            if (network == null) continue;
            for (LinkAddress item : network.getLinkAddresses()) {
                String ip = item.getAddress().getHostAddress();
                if (Pairing.localAddress(ip)) return ip;
            }
        }
        return null;
    }

    private JSONObject handle(JSONObject request) throws Exception {
        String operation = request.optString("op"), token = request.optString("token");
        JSONObject denied = new JSONObject().put("error", "unauthorized");
        if (!TvLink.validToken(token)) return denied;
        synchronized (this) {
            java.util.Set<String> keys = controllers(this);
            if ("pair".equals(operation)) {
                if (SystemClock.elapsedRealtime() >= expires || !TvLink.matches(invitation, token)) return denied;
                if (keys.size() >= MAX_CONTROLLERS) return new JSONObject().put("error", "controller_limit");
                String credential = TvLink.randomToken();
                keys.add(hash(credential));
                saveControllers(keys);
                invitation = null;
                return new JSONObject().put("token", credential).put("device", PhoneMedia.deviceName(this));
            }
            String key = hash(token);
            if (keys.stream().noneMatch(saved -> TvLink.matches(saved, key))) return denied;
            if ("forget".equals(operation)) {
                keys.remove(key);
                saveControllers(keys);
                return new JSONObject();
            }
        }
        boolean navigation = TvNavigationService.instance != null &&
            Pairing.prefs(this).getBoolean(TvNavigationService.ENABLED, false);
        if ("navigation".equals(operation)) {
            JSONObject command = request.optJSONObject("command");
            if (!navigation || command == null || !navigate(command.optString("key")))
                return new JSONObject().put("error", "unavailable");
        }
        JSONObject state = media.report().put("navigation", navigation);
        if ("command".equals(operation)) {
            JSONObject command = request.optJSONObject("command");
            if (command == null || !media.apply(new JSONObject().put("commands", new JSONArray().put(command))))
                return new JSONObject().put("error", "unavailable");
        } else if (!"state".equals(operation) && !"navigation".equals(operation)) return denied;
        return state;
    }

    private boolean navigate(String key) {
        java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
        ui.post(() -> {
            if (result.isDone()) return;
            TvNavigationService service = TvNavigationService.instance;
            try { result.complete(service != null && service.press(key)); }
            catch (RuntimeException error) { result.complete(false); }
        });
        try { return result.get(1200, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (Exception error) { result.cancel(false); return false; }
    }

    private static String hash(String token) throws Exception {
        return TvLink.digest(token.getBytes(StandardCharsets.US_ASCII));
    }

    @Override public void onDestroy() {
        running = false;
        instance = null;
        try { if (server != null) server.close(); } catch (java.io.IOException ignored) {}
        try { if (active != null) active.close(); } catch (java.io.IOException ignored) {}
        worker.shutdownNow();
        if (media != null) media.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
