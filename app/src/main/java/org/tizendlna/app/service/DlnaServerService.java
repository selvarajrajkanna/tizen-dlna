package org.tizendlna.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.tizendlna.app.MainActivity;
import org.tizendlna.app.R;
import org.tizendlna.app.dlna.SsdpAdvertiser;
import org.tizendlna.app.dlna.TizenDlnaServer;
import org.tizendlna.app.net.NetworkUtils;

/**
 * Foreground service keeping the zero-copy DLNA HTTP server and SSDP DMS alive
 * on Android 16, with live Wi-Fi IP monitoring, notification Play/Pause & Stop controls,
 * and real-time UI callbacks.
 */
public final class DlnaServerService extends Service {
    private static final String TAG = "DlnaServerService";
    private static final String CHANNEL_ID = "tizen_dlna_server";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_STOP_SERVER = "org.tizendlna.app.ACTION_STOP_SERVER";
    public static final String ACTION_TOGGLE_PLAY_PAUSE = "org.tizendlna.app.ACTION_TOGGLE_PLAY_PAUSE";

    public interface StateListener {
        void onServerStateChanged(boolean running);
        void onTogglePlayPauseRequested();
    }

    public final class LocalBinder extends Binder {
        public DlnaServerService getService() {
            return DlnaServerService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TizenDlnaServer dlnaServer = new TizenDlnaServer();

    private SsdpAdvertiser ssdpAdvertiser;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback wifiCallback;

    private volatile StateListener stateListener;
    private volatile String activeTitle;
    private volatile boolean activePlaying;
    private volatile boolean activeHasSub;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        registerWifiCallback();
        startServerInternal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP_SERVER.equals(action)) {
                stopServerAndService();
                return START_NOT_STICKY;
            }
            if (ACTION_TOGGLE_PLAY_PAUSE.equals(action)) {
                StateListener l = stateListener;
                if (l != null) {
                    mainHandler.post(l::onTogglePlayPauseRequested);
                }
                return START_NOT_STICKY;
            }
        }
        if (!dlnaServer.isRunning()) {
            startServerInternal();
        } else {
            refreshHostIp();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        unregisterWifiCallback();
        stopServerInternal();
        super.onDestroy();
    }

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    public TizenDlnaServer getServer() {
        return dlnaServer;
    }

    public boolean isServerRunning() {
        return dlnaServer.isRunning();
    }

    public void startServer() {
        if (!dlnaServer.isRunning()) {
            startServerInternal();
        }
    }

    public void stopServer() {
        activeTitle = null;
        activePlaying = false;
        activeHasSub = false;
        stopServerInternal();
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifyStateChanged(false);
    }

    public void stopServerAndService() {
        activeTitle = null;
        activePlaying = false;
        activeHasSub = false;
        stopServerInternal();
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifyStateChanged(false);
        stopSelf();
    }

    public void refreshHostIp() {
        String ip = NetworkUtils.getLocalWifiIpv4(this);
        if (ip != null && !ip.equals(dlnaServer.getHostIp())) {
            dlnaServer.setHostIp(ip);
            refreshNotification();
            notifyStateChanged(dlnaServer.isRunning());
        }
    }

    public void updateCastingState(String title, boolean playing, boolean hasSub) {
        this.activeTitle = title;
        this.activePlaying = playing;
        this.activeHasSub = hasSub;
        refreshNotification();
    }

    private void refreshNotification() {
        if (!dlnaServer.isRunning()) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void notifyStateChanged(boolean running) {
        StateListener l = stateListener;
        if (l != null) {
            mainHandler.post(() -> l.onServerStateChanged(running));
        }
    }

    private void startServerInternal() {
        try {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to enter foreground", e);
        }
        acquireLocks();
        Thread t = new Thread(() -> {
            String ip = NetworkUtils.getLocalWifiIpv4(this);
            if (ip != null) dlnaServer.setHostIp(ip);
            dlnaServer.setFriendlyName(Build.MODEL + " (TizenDLNA)");
            try {
                dlnaServer.start();
                ssdpAdvertiser = new SsdpAdvertiser(dlnaServer);
                ssdpAdvertiser.start();
                refreshNotification();
                notifyStateChanged(true);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start DLNA server", e);
                notifyStateChanged(false);
            }
        }, "dlna-boot");
        t.setDaemon(true);
        t.start();
    }

    private void stopServerInternal() {
        if (ssdpAdvertiser != null) {
            ssdpAdvertiser.stop();
            ssdpAdvertiser = null;
        }
        dlnaServer.stop();
        releaseLocks();
    }

    private void registerWifiCallback() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            wifiCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    refreshHostIp();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    refreshHostIp();
                }

                @Override
                public void onLost(Network network) {
                    notifyStateChanged(dlnaServer.isRunning());
                }
            };
            cm.registerNetworkCallback(req, wifiCallback);
        } catch (Exception e) {
            Log.d(TAG, "Wi-Fi callback registration skipped: " + e.getMessage());
        }
    }

    private void unregisterWifiCallback() {
        if (wifiCallback == null) return;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) cm.unregisterNetworkCallback(wifiCallback);
        } catch (Exception ignored) {
        }
        wifiCallback = null;
    }

    private void acquireLocks() {
        try {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "TizenDLNA:Wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TizenDLNA:Cpu");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(4 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        if (wifiLock != null && wifiLock.isHeld()) {
            try {
                wifiLock.release();
            } catch (Exception ignored) {
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "DLNA Media Server",
                NotificationManager.IMPORTANCE_LOW
        );
        ch.setDescription("Keeps local media and subtitle streaming active for Samsung TV");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, DlnaServerService.class);
        stopIntent.setAction(ACTION_STOP_SERVER);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String text = (activeTitle != null)
                ? (activePlaying ? "Playing: " : "Paused: ") + activeTitle + (activeHasSub ? " [SUB]" : "")
                : "Server running on " + dlnaServer.getHostIp() + ":" + dlnaServer.getPort();

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("TizenDLNA")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true);

        if (activeTitle != null) {
            Intent toggleIntent = new Intent(this, DlnaServerService.class);
            toggleIntent.setAction(ACTION_TOGGLE_PLAY_PAUSE);
            PendingIntent togglePi = PendingIntent.getService(
                    this, 2, toggleIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(
                    null, activePlaying ? "Pause" : "Play", togglePi).build());
        }

        builder.addAction(new Notification.Action.Builder(null, "Stop Server", stopPi).build());
        return builder.build();
    }
}
