package org.tizendlna.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.tizendlna.app.MainActivity;
import org.tizendlna.app.R;
import org.tizendlna.app.dlna.SsdpAdvertiser;
import org.tizendlna.app.dlna.TizenDlnaServer;
import org.tizendlna.app.net.NetworkUtils;

/**
 * Foreground service keeping the zero-copy DLNA HTTP server and SSDP DMS alive
 * while the phone screen is off on Android 16.
 */
public final class DlnaServerService extends Service {
    private static final String TAG = "DlnaServerService";
    private static final String CHANNEL_ID = "tizen_dlna_server";
    private static final int NOTIFICATION_ID = 1001;

    public final class LocalBinder extends Binder {
        public DlnaServerService getService() {
            return DlnaServerService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final TizenDlnaServer dlnaServer = new TizenDlnaServer();
    private SsdpAdvertiser ssdpAdvertiser;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireLocks();
        startServer();
        startForeground(
                NOTIFICATION_ID,
                buildNotification("DLNA server ready on " + dlnaServer.getHostIp() + ":" + dlnaServer.getPort()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        refreshHostIp();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (ssdpAdvertiser != null) {
            ssdpAdvertiser.stop();
            ssdpAdvertiser = null;
        }
        dlnaServer.stop();
        releaseLocks();
        super.onDestroy();
    }

    public TizenDlnaServer getServer() {
        return dlnaServer;
    }

    public void refreshHostIp() {
        String ip = NetworkUtils.getLocalWifiIpv4(this);
        if (ip != null) {
            dlnaServer.setHostIp(ip);
        }
    }

    public void updateNotification(String statusText) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(statusText));
        }
    }

    private void startServer() {
        refreshHostIp();
        dlnaServer.setFriendlyName(Build.MODEL + " (TizenDLNA)");
        try {
            dlnaServer.start();
            ssdpAdvertiser = new SsdpAdvertiser(dlnaServer);
            ssdpAdvertiser.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start DLNA server", e);
        }
    }

    private void acquireLocks() {
        try {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "TizenDLNA:Wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TizenDLNA:Cpu");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(4 * 60 * 60 * 1000L); // 4h max safety timeout
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
        ch.setDescription("Keeps local video and subtitle streaming active for Samsung TV");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("TizenDLNA")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
