package org.tizendlna.app.dlna;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.util.Xml;

import org.tizendlna.app.model.TvDevice;
import org.tizendlna.app.net.NetworkUtils;
import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight SSDP discovery for Samsung Tizen TVs (DLNA MediaRenderers).
 */
public final class SsdpDiscovery {
    private static final String TAG = "SsdpDiscovery";
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    private static final String[] SEARCH_TARGETS = {
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1"
    };

    private SsdpDiscovery() {}

    public static List<TvDevice> discover(Context context, int timeoutMs) {
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wm = context.getApplicationContext().getSystemService(WifiManager.class);
            if (wm != null) {
                lock = wm.createMulticastLock("TizenDLNA-SSDP");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) {
        }

        Set<String> locations = new LinkedHashSet<>();
        try {
            String localIp = NetworkUtils.getLocalWifiIpv4(context);
            NetworkInterface nif = NetworkUtils.getWifiNetworkInterface(localIp);
            InetAddress group = InetAddress.getByName(SSDP_ADDR);

            try (MulticastSocket sock = new MulticastSocket()) {
                if (nif != null) {
                    try {
                        sock.setNetworkInterface(nif);
                    } catch (Exception ignored) {
                    }
                }
                sock.setTimeToLive(4);
                sock.setSoTimeout(350);

                for (String st : SEARCH_TARGETS) {
                    String query = "M-SEARCH * HTTP/1.1\r\n"
                            + "HOST: 239.255.255.250:1900\r\n"
                            + "MAN: \"ssdp:discover\"\r\n"
                            + "MX: 2\r\n"
                            + "ST: " + st + "\r\n"
                            + "USER-AGENT: Android/16 UPnP/1.1 TizenDLNA/1.0\r\n\r\n";
                    byte[] data = query.getBytes(StandardCharsets.US_ASCII);
                    sock.send(new DatagramPacket(data, data.length, group, SSDP_PORT));
                }

                long deadline = System.currentTimeMillis() + Math.max(1200, timeoutMs);
                byte[] buf = new byte[4096];
                while (System.currentTimeMillis() < deadline) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        sock.receive(pkt);
                        String resp = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.US_ASCII);
                        String loc = extractHeader(resp, "LOCATION");
                        if (loc != null && loc.startsWith("http")) {
                            locations.add(loc.trim());
                        }
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "SSDP scan error: " + e.getMessage());
        } finally {
            if (lock != null && lock.isHeld()) {
                try {
                    lock.release();
                } catch (Exception ignored) {
                }
            }
        }

        Map<String, TvDevice> unique = new LinkedHashMap<>();
        for (String loc : locations) {
            TvDevice dev = fetchDeviceDescription(loc);
            if (dev != null && dev.avTransportControlUrl != null) {
                unique.put(dev.udn, dev);
            }
        }

        List<TvDevice> result = new ArrayList<>(unique.values());
        // Samsung / Tizen TVs first, then alphabetical
        result.sort(Comparator.comparing((TvDevice d) -> !d.isSamsung)
                .thenComparing(d -> d.friendlyName.toLowerCase(Locale.ROOT)));
        return result;
    }

    private static TvDevice fetchDeviceDescription(String locationUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(locationUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return null;

            String udn = null;
            String friendlyName = null;
            String manufacturer = null;
            String modelName = null;
            String avTransportUrl = null;
            String renderingControlUrl = null;

            String currentServiceType = null;
            String currentControlUrl = null;
            boolean inService = false;
            boolean inRootDevice = false;

            try (InputStream is = conn.getInputStream()) {
                XmlPullParser p = Xml.newPullParser();
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
                p.setInput(is, "UTF-8");

                int event = p.getEventType();
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        String tag = p.getName();
                        if ("device".equalsIgnoreCase(tag) && !inRootDevice) {
                            inRootDevice = true;
                        } else if ("service".equalsIgnoreCase(tag)) {
                            inService = true;
                            currentServiceType = null;
                            currentControlUrl = null;
                        } else if (inService) {
                            if ("serviceType".equalsIgnoreCase(tag)) {
                                currentServiceType = safeNextText(p);
                            } else if ("controlURL".equalsIgnoreCase(tag)) {
                                currentControlUrl = safeNextText(p);
                            }
                        } else if (inRootDevice) {
                            if ("UDN".equalsIgnoreCase(tag) && udn == null) {
                                udn = safeNextText(p);
                            } else if ("friendlyName".equalsIgnoreCase(tag) && friendlyName == null) {
                                friendlyName = safeNextText(p);
                            } else if ("manufacturer".equalsIgnoreCase(tag) && manufacturer == null) {
                                manufacturer = safeNextText(p);
                            } else if ("modelName".equalsIgnoreCase(tag) && modelName == null) {
                                modelName = safeNextText(p);
                            }
                        }
                    } else if (event == XmlPullParser.END_TAG) {
                        if ("service".equalsIgnoreCase(p.getName()) && inService) {
                            if (currentServiceType != null && currentControlUrl != null) {
                                String resolved = resolveUrl(locationUrl, currentControlUrl.trim());
                                if (currentServiceType.contains("AVTransport")) {
                                    avTransportUrl = resolved;
                                } else if (currentServiceType.contains("RenderingControl")) {
                                    renderingControlUrl = resolved;
                                }
                            }
                            inService = false;
                        }
                    }
                    event = p.next();
                }
            }

            if (avTransportUrl == null) return null;
            return new TvDevice(
                    udn,
                    friendlyName,
                    manufacturer,
                    modelName,
                    url.getHost(),
                    avTransportUrl,
                    renderingControlUrl
            );
        } catch (Exception e) {
            Log.d(TAG, "Skip device " + locationUrl + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String safeNextText(XmlPullParser p) {
        try {
            return p.nextText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String resolveUrl(String baseUrl, String relative) {
        try {
            return URI.create(baseUrl).resolve(relative).toString();
        } catch (Exception e) {
            return relative;
        }
    }

    private static String extractHeader(String raw, String name) {
        String lowerName = name.toLowerCase(Locale.ROOT) + ":";
        String[] lines = raw.split("\r\n");
        for (String line : lines) {
            if (line.toLowerCase(Locale.ROOT).startsWith(lowerName)) {
                return line.substring(lowerName.length()).trim();
            }
        }
        return null;
    }
}
