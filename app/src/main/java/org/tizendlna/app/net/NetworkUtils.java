package org.tizendlna.app.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public final class NetworkUtils {
    private NetworkUtils() {}

    /**
     * Returns the active Wi-Fi or Ethernet LAN IPv4 address for binding/advertising DLNA URLs.
     */
    public static String getLocalWifiIpv4(Context context) {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm != null) {
                for (Network net : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps == null) continue;
                    boolean isLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                    if (!isLan) continue;

                    LinkProperties lp = cm.getLinkProperties(net);
                    if (lp == null) continue;
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress addr = la.getAddress();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Fallback via NetworkInterface enumeration
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                String name = nif.getName();
                if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("dummy")) continue;

                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    public static NetworkInterface getWifiNetworkInterface(String ipv4) {
        if (ipv4 == null) return null;
        try {
            InetAddress target = InetAddress.getByName(ipv4);
            return NetworkInterface.getByInetAddress(target);
        } catch (Exception ignored) {
            return null;
        }
    }
}
