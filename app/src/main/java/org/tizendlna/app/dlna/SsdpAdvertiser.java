package org.tizendlna.app.dlna;

import android.os.Process;
import android.util.Log;

import org.tizendlna.app.net.NetworkUtils;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Announces this phone as a DLNA MediaServer:1 (DMS) via SSDP so Samsung Tizen TVs
 * can also browse the phone directly from Connected Devices / Sources.
 */
public final class SsdpAdvertiser {
    private static final String TAG = "SsdpAdvertiser";
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    private static final String[] ADVERTISED_NT = {
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1"
    };

    private final TizenDlnaServer server;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile MulticastSocket socket;

    public SsdpAdvertiser(TizenDlnaServer server) {
        this.server = server;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        Thread t = new Thread(this::runLoop, "dlna-ssdp-dms");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        sendNotify("ssdp:byebye");
        MulticastSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        try {
            InetAddress group = InetAddress.getByName(SSDP_ADDR);
            NetworkInterface nif = NetworkUtils.getWifiNetworkInterface(server.getHostIp());

            MulticastSocket s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(SSDP_PORT));
            if (nif != null) {
                s.joinGroup(new InetSocketAddress(group, SSDP_PORT), nif);
            } else {
                s.joinGroup(group);
            }
            s.setSoTimeout(2000);
            this.socket = s;

            sendNotify("ssdp:alive");
            long lastAlive = System.currentTimeMillis();

            byte[] buf = new byte[2048];
            while (running.get()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    s.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.US_ASCII);
                    if (msg.startsWith("M-SEARCH") && (msg.contains("ssdp:all")
                            || msg.contains("upnp:rootdevice")
                            || msg.contains("MediaServer:1")
                            || msg.contains("ContentDirectory:1"))) {
                        respondSearch(s, pkt.getAddress(), pkt.getPort());
                    }
                } catch (SocketTimeoutException ignored) {
                }

                long now = System.currentTimeMillis();
                if (now - lastAlive > 60_000) {
                    sendNotify("ssdp:alive");
                    lastAlive = now;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "DMS SSDP loop ended: " + e.getMessage());
        }
    }

    private void sendNotify(String nts) {
        MulticastSocket s = socket;
        if (s == null || s.isClosed()) return;
        try {
            InetAddress group = InetAddress.getByName(SSDP_ADDR);
            String loc = server.deviceDescriptionUrl();
            String udn = server.getUdn();

            for (String nt : ADVERTISED_NT) {
                String msg = "NOTIFY * HTTP/1.1\r\n"
                        + "HOST: 239.255.255.250:1900\r\n"
                        + "CACHE-CONTROL: max-age=1800\r\n"
                        + "LOCATION: " + loc + "\r\n"
                        + "NT: " + nt + "\r\n"
                        + "NTS: " + nts + "\r\n"
                        + "SERVER: Android/16 UPnP/1.0 TizenDLNA/1.0\r\n"
                        + "USN: " + udn + "::" + nt + "\r\n\r\n";
                byte[] b = msg.getBytes(StandardCharsets.US_ASCII);
                s.send(new DatagramPacket(b, b.length, group, SSDP_PORT));
            }
        } catch (Exception ignored) {
        }
    }

    private void respondSearch(MulticastSocket s, InetAddress target, int port) {
        try {
            String loc = server.deviceDescriptionUrl();
            String udn = server.getUdn();
            String resp = "HTTP/1.1 200 OK\r\n"
                    + "CACHE-CONTROL: max-age=1800\r\n"
                    + "EXT:\r\n"
                    + "LOCATION: " + loc + "\r\n"
                    + "SERVER: Android/16 UPnP/1.0 TizenDLNA/1.0\r\n"
                    + "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n"
                    + "USN: " + udn + "::urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n";
            byte[] b = resp.getBytes(StandardCharsets.US_ASCII);
            s.send(new DatagramPacket(b, b.length, target, port));
        } catch (Exception ignored) {
        }
    }
}
