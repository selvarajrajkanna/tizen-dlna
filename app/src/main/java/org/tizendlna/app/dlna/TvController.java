package org.tizendlna.app.dlna;

import android.util.Log;

import org.tizendlna.app.model.MediaItem;
import org.tizendlna.app.model.TvDevice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Direct UPnP SOAP controller for Samsung Tizen TV AVTransport:1 and RenderingControl:1.
 */
public final class TvController {
    private static final String TAG = "TvController";
    private static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";

    public static final class PlaybackStatus {
        public final String transportState; // PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING
        public final long positionMs;
        public final long durationMs;

        public PlaybackStatus(String transportState, long positionMs, long durationMs) {
            this.transportState = transportState;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }

    private TvController() {}

    /**
     * Pushes a video (and same-directory subtitle via Samsung sec:CaptionInfoEx + CaptionInfo.sec)
     * to the selected Samsung TV and starts playback immediately.
     */
    public static void pushAndPlay(TvDevice tv, MediaItem item, TizenDlnaServer server) throws IOException {
        server.registerItem(item);
        String videoUrl = server.videoUrl(item);
        String didl = server.buildDidlLiteForItem(item);

        // Best-effort stop of any previous stream so Samsung AVPlay cleanly resets subtitle state
        try {
            stop(tv);
        } catch (IOException ignored) {
        }

        String setUriArgs = "<InstanceID>0</InstanceID>"
                + "<CurrentURI>" + TizenDlnaServer.xmlEscape(videoUrl) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + TizenDlnaServer.xmlEscape(didl) + "</CurrentURIMetaData>";
        sendSoap(tv.avTransportControlUrl, AV_TRANSPORT, "SetAVTransportURI", setUriArgs);

        play(tv);
    }

    public static void play(TvDevice tv) throws IOException {
        sendSoap(tv.avTransportControlUrl, AV_TRANSPORT, "Play",
                "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    public static void pause(TvDevice tv) throws IOException {
        sendSoap(tv.avTransportControlUrl, AV_TRANSPORT, "Pause",
                "<InstanceID>0</InstanceID>");
    }

    public static void stop(TvDevice tv) throws IOException {
        sendSoap(tv.avTransportControlUrl, AV_TRANSPORT, "Stop",
                "<InstanceID>0</InstanceID>");
    }

    public static void seek(TvDevice tv, long positionMs) throws IOException {
        long clamped = Math.max(0, positionMs);
        long totalSec = clamped / 1000;
        long hrs = totalSec / 3600;
        long mins = (totalSec % 3600) / 60;
        long secs = totalSec % 60;
        String target = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs);
        sendSoap(tv.avTransportControlUrl, AV_TRANSPORT, "Seek",
                "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>" + target + "</Target>");
    }

    public static PlaybackStatus pollStatus(TvDevice tv) throws IOException {
        String transportXml = sendSoap(tv.avTransportControlUrl, AV_TRANSPORT, "GetTransportInfo",
                "<InstanceID>0</InstanceID>");
        String state = extractTag(transportXml, "CurrentTransportState");
        if (state.isEmpty()) state = "STOPPED";

        String posXml = sendSoap(tv.avTransportControlUrl, AV_TRANSPORT, "GetPositionInfo",
                "<InstanceID>0</InstanceID>");
        long posMs = parseHmsToMs(extractTag(posXml, "RelTime"));
        long durMs = parseHmsToMs(extractTag(posXml, "TrackDuration"));
        return new PlaybackStatus(state, posMs, durMs);
    }

    public static int getVolume(TvDevice tv) throws IOException {
        if (tv.renderingControlUrl == null) return -1;
        String xml = sendSoap(tv.renderingControlUrl, RENDERING_CONTROL, "GetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>");
        String v = extractTag(xml, "CurrentVolume");
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static void setVolume(TvDevice tv, int volume) throws IOException {
        if (tv.renderingControlUrl == null) return;
        int clamped = Math.max(0, Math.min(100, volume));
        sendSoap(tv.renderingControlUrl, RENDERING_CONTROL, "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>" + clamped + "</DesiredVolume>");
    }

    private static String sendSoap(String controlUrl, String serviceType, String action, String argsXml) throws IOException {
        String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>"
                + "<u:" + action + " xmlns:u=\"" + serviceType + "\">"
                + argsXml
                + "</u:" + action + ">"
                + "</s:Body>"
                + "</s:Envelope>";

        byte[] payload = envelope.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(controlUrl).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
            conn.setRequestProperty("Connection", "close");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) {
                Log.w(TAG, action + " HTTP " + code + ": " + resp);
                throw new IOException("TV returned HTTP " + code + " for " + action);
            }
            return resp;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder(512);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String extractTag(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int i = xml.indexOf(open);
        if (i < 0) return "";
        int j = xml.indexOf(close, i + open.length());
        if (j < 0) return "";
        return xml.substring(i + open.length(), j).trim();
    }

    private static long parseHmsToMs(String hms) {
        if (hms == null || hms.isEmpty() || hms.contains("NOT_IMPLEMENTED")) return 0;
        String[] parts = hms.split(":");
        if (parts.length != 3) return 0;
        try {
            long h = Long.parseLong(parts[0].trim());
            long m = Long.parseLong(parts[1].trim());
            String secStr = parts[2].trim();
            int dot = secStr.indexOf('.');
            long s = Long.parseLong(dot >= 0 ? secStr.substring(0, dot) : secStr);
            return ((h * 3600) + (m * 60) + s) * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
