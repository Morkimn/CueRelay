package com.codex.pocketplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PhoneLink extends Service {
    static volatile Playback playback;
    static volatile boolean accessDenied;
    private static volatile Pairing connection;
    private static volatile boolean readCredentials;
    private static final String CHANNEL = "connection";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService poller = Executors.newSingleThreadExecutor();
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final ExecutorService covers = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService reverse = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService reverseCommands = Executors.newSingleThreadExecutor();
    private final AtomicBoolean phoneUpdateQueued = new AtomicBoolean();
    private volatile boolean running;
    private volatile String discovered, reachable;
    private Pairing pairing;
    private DeskClient client;
    private SystemMedia systemMedia;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicast;
    private String artKey = "", failedArtKey = "";
    private boolean loadingArt;
    private long retryArtAt;
    private Bitmap art;
    private long lastPhoneReport;
    private PhoneMedia.Exchange phone;

    static Pairing connection(Context context) {
        if (!readCredentials) {
            synchronized (PhoneLink.class) {
                if (!readCredentials) {
                    connection = Pairing.read(context);
                    readCredentials = true;
                }
            }
        }
        return connection;
    }

    static void setConnection(Context context, Pairing value) {
        if (value == null) Pairing.clear(context);
        connection = value;
        accessDenied = false;
        readCredentials = true;
    }

    static void start(Context context, String action, String argument) {
        Intent intent = new Intent(context, PhoneLink.class).setAction(action);
        if (argument != null) intent.putExtra("argument", argument);
        context.startForegroundService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Связь с компьютером",
            NotificationManager.IMPORTANCE_LOW));
        systemMedia = new SystemMedia(this, main, openApp(), (verb, source) -> {
            if (running) commands.execute(() -> execute(verb, source));
        });
        pairing = connection(this);
        if (pairing == null) return;
        try { client = new DeskClient(pairing); } catch (Exception exception) { return; }
        running = true;
        phone = new PhoneMedia.Exchange(this, main, this::schedulePhoneUpdate);
        advertiseListener();
        poller.execute(this::watch);
        reverse.scheduleWithFixedDelay(() -> reportPhone(false), 0, 1900, TimeUnit.MILLISECONDS);
        reverseCommands.execute(this::watchPhoneCommands);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        startForeground(21, notification());
        if (running && pairing != connection(this)) {
            running = false;
            stopSelf();
            Context app = getApplicationContext();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (connection(app) != null) start(app, "refresh", null);
            }, 250);
            return START_NOT_STICKY;
        }
        if (!running || (intent != null && "stop".equals(intent.getAction()))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getAction() != null && !"refresh".equals(intent.getAction()) &&
            !"surface".equals(intent.getAction())) {
            String action = intent.getAction();
            String argument = intent.getStringExtra("argument");
            if ("phone-settings".equals(action)) reverse.execute(() -> reportPhone(true));
            else commands.execute(() -> execute(action, argument));
        }
        return START_STICKY;
    }

    private Notification notification() {
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, PhoneLink.class).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_play)
            .setContentTitle("CueRelay").setContentText("Виджеты синхронизируются с компьютером")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp());
        if (systemMedia == null || !systemMedia.apply(builder, playback))
            builder.addAction(new Notification.Action.Builder(null, "Остановить", stop).build());
        return builder.build();
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void updateSurfaces() {
        PlayerWidget.updateAll(this);
        main.post(() -> {
            if (running) getSystemService(NotificationManager.class).notify(21, notification());
        });
    }

    private void advertiseListener() {
        nsd = getSystemService(NsdManager.class);
        if (nsd == null) return;
        if (Build.VERSION.SDK_INT <= 33) {
            WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
            if (wifi != null) {
                multicast = wifi.createMulticastLock("pocket-player-discovery");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
        }
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int error) { }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
            @Override public void onServiceLost(NsdServiceInfo info) {
                if (pairing.serviceName().equalsIgnoreCase(info.getServiceName())) discovered = null;
            }
            @Override public void onServiceFound(NsdServiceInfo info) {
                if (!pairing.serviceName().equalsIgnoreCase(info.getServiceName())) return;
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo service, int code) { }
                    @Override public void onServiceResolved(NsdServiceInfo service) {
                        if (!running || service.getPort() != pairing.port) return;
                        if (Build.VERSION.SDK_INT >= 34) {
                            for (java.net.InetAddress address : service.getHostAddresses()) {
                                String host = address.getHostAddress();
                                if (Pairing.localAddress(host)) { discovered = host; return; }
                            }
                        } else if (service.getHost() != null) {
                            String host = service.getHost().getHostAddress();
                            if (Pairing.localAddress(host)) discovered = host;
                        }
                    }
                });
            }
        };
        try { nsd.discoverServices("_pocketplayer._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (RuntimeException ignored) { discovery = null; }
    }

    private void watch() {
        while (running) {
            try {
                if (reachable == null) connect();
                else {
                    Playback state = playback;
                    long revision = state == null ? 0 : state.revision;
                    String address = reachable;
                    apply(client.json(address, "/state?revision=" + revision, null, true, 20000), address);
                }
            } catch (DeskClient.Status status) {
                if (status.code == 401) accessDenied = true;
                offline();
            } catch (Exception error) { offline(); }
        }
    }

    private void connect() throws Exception {
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        if (discovered != null) addresses.add(discovered);
        String remembered = pairing.lastAddress(this);
        if (Pairing.localAddress(remembered)) addresses.add(remembered);
        addresses.addAll(pairing.addresses);
        for (String address : addresses) {
            if (!running) return;
            try {
                JSONObject state = client.json(address, "/state", null, true, 2200);
                reachable = address;
                pairing.rememberAddress(this, address);
                apply(state, address);
                return;
            } catch (DeskClient.Status error) {
                if (error.code == 401) throw error;
            } catch (Exception ignored) { }
        }
        Thread.sleep(2600);
    }

    private void apply(JSONObject data, String address) throws Exception {
        Playback updated = Playback.parse(data);
        main.post(() -> {
            if (!running || !address.equals(reachable)) return;
            boolean reconnected = playback == null;
            accessDenied = false;
            if (!updated.theme.equals(Appearance.theme(this)) || !updated.accent.equals(Appearance.accentKey(this)))
                Pairing.prefs(this).edit().putString("theme", updated.theme).putString("accent", updated.accent).apply();
            showPlayback(updated, Pairing.prefs(this).getString("source", ""));
            if (reconnected) {
                Diagnostics.record(this, "connection", "connected", null);
                schedulePhoneUpdate();
            }
        });
    }

    private void showPlayback(Playback state, String sourceId) {
        Playback selected = state.select(sourceId, null);
        String key = selected.artworkKey();
        if (key.equals(artKey) && art != null) selected = selected.withArtwork(art);
        publishPlayback(selected);
        loadArtwork(key);
    }

    private void publishPlayback(Playback next) {
        Playback current = playback;
        if (current != null && current.revision == next.revision && current.art == next.art &&
            Objects.equals(current.selected == null ? null : current.selected.id,
                next.selected == null ? null : next.selected.id)) return;
        playback = next;
        updateSurfaces();
    }

    private void loadArtwork(String key) {
        String address = reachable;
        if (key.isEmpty() || key.equals(artKey) || loadingArt || address == null ||
            key.equals(failedArtKey) && SystemClock.elapsedRealtime() < retryArtAt) return;
        loadingArt = true;
        covers.execute(() -> {
            Bitmap image = null;
            try {
                byte[] bytes = client.art(address, key);
                image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception ignored) { }
            Bitmap result = image;
            main.post(() -> finishArtwork(key, result));
        });
    }

    private void finishArtwork(String key, Bitmap image) {
        if (!running) return;
        loadingArt = false;
        Playback current = playback;
        if (current == null) return;
        if (!key.equals(current.artworkKey())) {
            loadArtwork(current.artworkKey());
        } else if (image != null) {
            art = image;
            artKey = key;
            publishPlayback(current.withArtwork(image));
        } else {
            failedArtKey = key;
            retryArtAt = SystemClock.elapsedRealtime() + 2500;
            main.postDelayed(() -> {
                Playback latest = playback;
                if (running && latest != null && key.equals(latest.artworkKey())) loadArtwork(key);
            }, 2500);
        }
    }

    private void offline() {
        if (reachable != null) Diagnostics.record(this, "connection", "lost", null);
        reachable = null;
        main.post(() -> {
            if (running && playback != null) {
                playback = null;
                updateSurfaces();
            }
        });
        try { Thread.sleep(800); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    private void execute(String action, String argument) {
        String address = reachable;
        try {
            if ("forget".equals(action)) {
                if (address != null) {
                    try { client.json(address, "/disconnect", new JSONObject(), true, 4000); }
                    catch (Exception ignored) { }
                }
                main.post(this::revokeLocally);
                return;
            }
            if ("check-link".equals(action)) {
                if (address == null) {
                    main.post(() -> Toast.makeText(this, "ПК сейчас недоступен", Toast.LENGTH_SHORT).show());
                    return;
                }
                long started = SystemClock.elapsedRealtime();
                client.json(address, "/ping", null, true, 2500);
                long delay = SystemClock.elapsedRealtime() - started;
                main.post(() -> Toast.makeText(this, "ПК отвечает · " + delay + " мс", Toast.LENGTH_LONG).show());
                return;
            }
            if (address == null) return;
            if ("cycle-source".equals(action)) {
                Playback state = playback;
                if (state == null || state.sources.size() < 2) return;
                int current = state.sources.indexOf(state.selected);
                argument = state.sources.get((current + 1) % state.sources.size()).id;
                action = "source";
            }
            if ("source".equals(action)) {
                String preferred = argument == null ? "" : argument;
                main.post(() -> {
                    if (!running) return;
                    Pairing.prefs(this).edit().putString("source", preferred).apply();
                    if (playback != null) showPlayback(playback, preferred);
                });
                return;
            }
            if ("theme".equals(action)) {
                client.json(address, "/theme", new JSONObject().put("theme", argument), true, 4000);
            } else if ("accent".equals(action)) {
                client.json(address, "/accent", new JSONObject().put("accent", argument), true, 4000);
            } else if ("seek".equals(action)) {
                if (argument == null || !argument.matches("[0-9a-f]{32}:[0-9]{1,14}")) return;
                int split = argument.indexOf(':');
                client.json(address, "/command", new JSONObject().put("id", argument.substring(0, split))
                    .put("action", "seek").put("positionMs", Long.parseLong(argument.substring(split + 1))), true, 4000);
            } else if ("next".equals(action) || "previous".equals(action) || "toggle".equals(action) ||
                "play".equals(action) || "pause".equals(action)) {
                if (argument != null && argument.matches("[0-9a-f]{32}"))
                    client.json(address, "/command", new JSONObject().put("id", argument).put("action", action), true, 4000);
            }
        } catch (DeskClient.Status error) {
            if (error.code == 401) accessDenied = true;
            else main.post(() -> Toast.makeText(this, "Источник не выполнил команду", Toast.LENGTH_SHORT).show());
        } catch (Exception error) {
            main.post(() -> Toast.makeText(this, "Связь с компьютером потеряна", Toast.LENGTH_SHORT).show());
        }
    }

    private void reportPhone(boolean changed) {
        String address = reachable;
        long now = SystemClock.elapsedRealtime();
        if (!running || address == null || !changed && !PhoneMedia.enabled(this) && now - lastPhoneReport < 4000) return;
        lastPhoneReport = now;
        try {
            JSONObject reply = client.json(address, "/phone/exchange", phone.report(), true, 2300);
            if (running && address.equals(reachable) && pairing == connection(this) && phone.apply(reply))
                schedulePhoneUpdate();
        } catch (Exception ignored) { }
    }

    private void schedulePhoneUpdate() {
        if (!running || !phoneUpdateQueued.compareAndSet(false, true)) return;
        try {
            reverse.schedule(() -> {
                phoneUpdateQueued.set(false);
                reportPhone(true);
            }, 55, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) { phoneUpdateQueued.set(false); }
    }

    private void watchPhoneCommands() {
        while (running && !Thread.currentThread().isInterrupted()) {
            int retry = 450;
            String address = reachable;
            if (address != null && PhoneMedia.enabled(this)) {
                try {
                    long started = SystemClock.elapsedRealtime();
                    JSONObject reply = client.json(address, "/phone/commands", null, true, 12500);
                    if (running && address.equals(reachable) && pairing == connection(this) && phone.apply(reply)) {
                        schedulePhoneUpdate();
                        retry = 0;
                    } else if (SystemClock.elapsedRealtime() - started > 2000) retry = 0;
                } catch (Exception ignored) { retry = 1000; }
            }
            try { Thread.sleep(retry); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        }
    }

    private void revokeLocally() {
        setConnection(this, null);
        playback = null;
        PlayerWidget.updateAll(this);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        poller.shutdownNow();
        commands.shutdownNow();
        covers.shutdownNow();
        reverse.shutdownNow();
        reverseCommands.shutdownNow();
        if (phone != null) phone.close();
        main.removeCallbacksAndMessages(null);
        if (nsd != null && discovery != null) {
            try { nsd.stopServiceDiscovery(discovery); } catch (RuntimeException ignored) { }
        }
        if (multicast != null && multicast.isHeld()) multicast.release();
        if (systemMedia != null) systemMedia.close();
        playback = null;
        PlayerWidget.updateAll(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateSurfaces();
    }
}
