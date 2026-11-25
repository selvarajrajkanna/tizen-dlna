package org.tizendlna.app.dlna;

import android.os.Process;
import android.util.Log;

import org.tizendlna.app.model.MediaItem;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight, zero-copy (FileChannel.transferTo -> Linux sendfile64) HTTP/1.1 + DLNA server
 * tuned for Samsung Tizen 10 TVs and Snapdragon 7+ Gen 3 (ARM64) efficiency.
 *
 * Implements Samsung Tizen's DLNA requirements:
 * - HTTP Range (206 Partial Content) + HEAD for instant seeking
 * - transferMode.dlna.org: Streaming / Interactive
 * - contentFeatures.dlna.org with DLNA.ORG_OP=01 (byte-range seek enabled)
 * - CaptionInfo.sec HTTP header pointing to same-folder subtitle
 * - UTF-8 normalization for UTF-16 subtitles so Tizen never garbles text
 * - Built-in UPnP DMS endpoints (/dms/device.xml, /dms/cds.xml, /dms/control/cds)
 */
public final class TizenDlnaServer {
    private static final String TAG = "TizenDlnaServer";

    public static final String DLNA_VIDEO_FLAGS =
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";
    public static final String DLNA_SUB_FLAGS =
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=00D00000000000000000000000000000";

    private static final int DEFAULT_PORT = 8200;
    private static final int SOCKET_SEND_BUF = 262_144; // 256 KB for smooth 4K Wi-Fi 6 bursts
    private static final long TRANSFER_CHUNK = 512L * 1024L; // 512 KB sendfile slice

    private final String udn = "uuid:" + UUID.nameUUIDFromBytes("TizenDLNA-Android16-DMS".getBytes(StandardCharsets.UTF_8));
    private final ConcurrentHashMap<Long, MediaItem> catalog = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "dlna-http");
        t.setDaemon(true);
        return t;
    });

    private volatile ServerSocketChannel serverChannel;
    private volatile int port = DEFAULT_PORT;
    private volatile String hostIp = "127.0.0.1";
    private volatile String friendlyName = "Phone (TizenDLNA)";

    public void setHostIp(String ip) {
        if (ip != null && !ip.isEmpty()) {
            this.hostIp = ip;
        }
    }

    public void setFriendlyName(String name) {
        if (name != null && !name.isEmpty()) {
            this.friendlyName = name;
        }
    }

    public String getHostIp() {
        return hostIp;
    }

    public int getPort() {
        return port;
    }

    public String getUdn() {
        return udn;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void updateCatalog(List<MediaItem> items) {
        catalog.clear();
        if (items != null) {
            for (MediaItem item : items) {
                catalog.put(item.id, item);
            }
        }
    }

    public void registerItem(MediaItem item) {
        if (item != null) {
            catalog.put(item.id, item);
        }
    }

    public String videoUrl(MediaItem item) {
        return "http://" + hostIp + ":" + port + "/video/" + item.id + "/" + encodePathSegment(item.videoFile.getName());
    }

    public String subtitleUrl(MediaItem item) {
        if (!item.hasSubtitle()) return null;
        return "http://" + hostIp + ":" + port + "/sub/" + item.id + "/" + encodePathSegment(item.subtitleFile.getName());
    }

    public String deviceDescriptionUrl() {
        return "http://" + hostIp + ":" + port + "/dms/device.xml";
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;

        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.socket().setReuseAddress(true);
        try {
            ssc.bind(new InetSocketAddress(DEFAULT_PORT), 32);
            port = DEFAULT_PORT;
        } catch (IOException busy) {
            ssc.bind(new InetSocketAddress(0), 32);
            port = ssc.socket().getLocalPort();
        }
        serverChannel = ssc;

        Thread acceptThread = new Thread(this::acceptLoop, "dlna-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "DLNA HTTP server started on " + hostIp + ":" + port);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        ServerSocketChannel ssc = serverChannel;
        serverChannel = null;
        if (ssc != null) {
            try {
                ssc.close();
            } catch (IOException ignored) {
            }
        }
        Log.i(TAG, "DLNA HTTP server stopped");
    }

    private void acceptLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (running.get()) {
            try {
                ServerSocketChannel ssc = serverChannel;
                if (ssc == null || !ssc.isOpen()) break;
                SocketChannel client = ssc.accept();
                if (client == null) continue;
                workers.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(SocketChannel channel) {
        try (SocketChannel ch = channel) {
            ch.configureBlocking(true);
            ch.socket().setTcpNoDelay(true);
            ch.socket().setSendBufferSize(SOCKET_SEND_BUF);
            ch.socket().setSoTimeout(30_000);

            BufferedInputStream in = new BufferedInputStream(ch.socket().getInputStream(), 8192);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String rawPath = parts[1];
            int qIdx = rawPath.indexOf('?');
            String path = (qIdx >= 0) ? rawPath.substring(0, qIdx) : rawPath;

            String rangeHeader = null;
            int contentLength = 0;
            String soapAction = "";
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String v = line.substring(colon + 1).trim();
                if (k.equals("range")) {
                    rangeHeader = v;
                } else if (k.equals("content-length")) {
                    try {
                        contentLength = Integer.parseInt(v);
                    } catch (NumberFormatException ignored) {
                    }
                } else if (k.equals("soapaction")) {
                    soapAction = v;
                }
            }

            if (path.startsWith("/video/")) {
                serveVideo(ch, method, path, rangeHeader);
            } else if (path.startsWith("/sub/")) {
                serveSubtitle(ch, method, path, rangeHeader);
            } else if (path.equals("/dms/device.xml")) {
                sendXml(ch, method, buildDeviceXml());
            } else if (path.equals("/dms/cds.xml")) {
                sendXml(ch, method, buildCdsScpdXml());
            } else if (path.equals("/dms/cms.xml")) {
                sendXml(ch, method, buildCmsScpdXml());
            } else if (path.equals("/dms/control/cds") && method.equals("POST")) {
                String body = readBody(reader, contentLength);
                handleCdsSoap(ch, soapAction, body);
            } else if (path.equals("/dms/control/cms") && method.equals("POST")) {
                handleCmsSoap(ch, soapAction);
            } else {
                sendError(ch, 404, "Not Found");
            }
        } catch (IOException ignored) {
            // TV closed connection (normal on seek/stop)
        }
    }

    private void serveVideo(SocketChannel ch, String method, String path, String rangeHeader) throws IOException {
        Long id = parseMediaId(path, "/video/");
        MediaItem item = (id != null) ? catalog.get(id) : null;
        if (item == null || !item.videoFile.isFile()) {
            sendError(ch, 404, "Video Not Found");
            return;
        }

        long fileLen = item.videoFile.length();
        long start = 0;
        long end = fileLen - 1;
        boolean partial = false;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            long[] parsed = parseByteRange(rangeHeader, fileLen);
            if (parsed == null) {
                sendRangeNotSatisfiable(ch, fileLen);
                return;
            }
            start = parsed[0];
            end = parsed[1];
            partial = true;
        }

        long contentLen = Math.max(0, end - start + 1);
        StringBuilder hdr = new StringBuilder(512);
        hdr.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        hdr.append("Content-Type: ").append(item.mimeType).append("\r\n");
        hdr.append("Content-Length: ").append(contentLen).append("\r\n");
        if (partial) {
            hdr.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(fileLen).append("\r\n");
        }
        hdr.append("Accept-Ranges: bytes\r\n");
        hdr.append("transferMode.dlna.org: Streaming\r\n");
        hdr.append("contentFeatures.dlna.org: ").append(DLNA_VIDEO_FLAGS).append("\r\n");
        if (item.hasSubtitle()) {
            String subUrl = subtitleUrl(item);
            if (subUrl != null) {
                // Samsung Tizen proprietary header for automatic external subtitle loading
                hdr.append("CaptionInfo.sec: ").append(subUrl).append("\r\n");
            }
        }
        hdr.append("Server: Android/16 UPnP/1.0 TizenDLNA/1.0\r\n");
        hdr.append("EXT:\r\n");
        hdr.append("Connection: close\r\n\r\n");

        writeAll(ch, hdr.toString().getBytes(StandardCharsets.US_ASCII));
        if ("HEAD".equals(method) || contentLen == 0) return;

        // Zero-copy sendfile64 path on Android ARM64 kernel
        try (FileChannel fc = FileChannel.open(item.videoFile.toPath(), StandardOpenOption.READ)) {
            long pos = start;
            long remaining = contentLen;
            while (remaining > 0 && running.get()) {
                long sent = fc.transferTo(pos, Math.min(TRANSFER_CHUNK, remaining), ch);
                if (sent <= 0) break;
                pos += sent;
                remaining -= sent;
            }
        }
    }

    private void serveSubtitle(SocketChannel ch, String method, String path, String rangeHeader) throws IOException {
        Long id = parseMediaId(path, "/sub/");
        MediaItem item = (id != null) ? catalog.get(id) : null;
        if (item == null || !item.hasSubtitle()) {
            sendError(ch, 404, "Subtitle Not Found");
            return;
        }

        byte[] utf8Bytes = readSubtitleAsUtf8(item.subtitleFile);
        long totalLen = utf8Bytes.length;
        long start = 0;
        long end = totalLen - 1;
        boolean partial = false;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            long[] parsed = parseByteRange(rangeHeader, totalLen);
            if (parsed == null) {
                sendRangeNotSatisfiable(ch, totalLen);
                return;
            }
            start = parsed[0];
            end = parsed[1];
            partial = true;
        }

        long contentLen = Math.max(0, end - start + 1);
        String mime = MediaItem.dlnaMimeForSubtitle(item.subtitleType);

        StringBuilder hdr = new StringBuilder(384);
        hdr.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        hdr.append("Content-Type: ").append(mime).append("; charset=utf-8\r\n");
        hdr.append("Content-Length: ").append(contentLen).append("\r\n");
        if (partial) {
            hdr.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(totalLen).append("\r\n");
        }
        hdr.append("Accept-Ranges: bytes\r\n");
        hdr.append("transferMode.dlna.org: Interactive\r\n");
        hdr.append("contentFeatures.dlna.org: ").append(DLNA_SUB_FLAGS).append("\r\n");
        hdr.append("Server: Android/16 UPnP/1.0 TizenDLNA/1.0\r\n");
        hdr.append("Connection: close\r\n\r\n");

        writeAll(ch, hdr.toString().getBytes(StandardCharsets.US_ASCII));
        if ("HEAD".equals(method) || contentLen == 0) return;

        OutputStream out = ch.socket().getOutputStream();
        out.write(utf8Bytes, (int) start, (int) contentLen);
        out.flush();
    }

    /**
     * Reads subtitle file and converts UTF-16 LE/BE BOM files to UTF-8 for Samsung Tizen compatibility.
     */
    private static byte[] readSubtitleAsUtf8(File file) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(file.length(), 1024 * 1024));
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
        }
        byte[] raw = baos.toByteArray();
        if (raw.length >= 2) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                String text = new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
                return text.getBytes(StandardCharsets.UTF_8);
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                String text = new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE);
                return text.getBytes(StandardCharsets.UTF_8);
            }
        }
        return raw;
    }

    /**
     * Builds Samsung Tizen-compliant DIDL-Lite XML for a single video (used in both Push & DMS Browse).
     */
    public String buildDidlLiteForItem(MediaItem item) {
        String vUrl = xmlEscape(videoUrl(item));
        String title = xmlEscape(item.title);
        String dur = MediaItem.formatDlnaDuration(item.durationMs);
        String proto = "http-get:*:" + item.mimeType + ":" + DLNA_VIDEO_FLAGS;

        StringBuilder sb = new StringBuilder(1024);
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
          .append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
          .append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
          .append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\" ")
          .append("xmlns:sec=\"http://www.sec.co.kr/\">");
        appendDidlItem(sb, item, vUrl, title, dur, proto);
        sb.append("</DIDL-Lite>");
        return sb.toString();
    }

    private void appendDidlItem(StringBuilder sb, MediaItem item, String vUrl, String title, String dur, String proto) {
        String upnpClass = item.isAudio ? "object.item.audioItem.musicTrack" : "object.item.videoItem.movie";
        sb.append("<item id=\"").append(item.id).append("\" parentID=\"0\" restricted=\"1\">")
          .append("<dc:title>").append(title).append("</dc:title>")
          .append("<upnp:class>").append(upnpClass).append("</upnp:class>")
          .append("<res protocolInfo=\"").append(proto).append("\" ")
          .append("size=\"").append(item.sizeBytes).append("\" ")
          .append("duration=\"").append(dur).append("\">")
          .append(vUrl)
          .append("</res>");

        if (item.hasSubtitle()) {
            String sUrl = xmlEscape(subtitleUrl(item));
            String sType = xmlEscape(item.subtitleType.isEmpty() ? "srt" : item.subtitleType);
            String sMime = MediaItem.dlnaMimeForSubtitle(item.subtitleType);
            // Samsung Tizen checks sec:CaptionInfoEx, sec:CaptionInfo, and text/srt res element
            sb.append("<sec:CaptionInfo sec:type=\"").append(sType).append("\">").append(sUrl).append("</sec:CaptionInfo>");
            sb.append("<sec:CaptionInfoEx sec:type=\"").append(sType).append("\">").append(sUrl).append("</sec:CaptionInfoEx>");
            sb.append("<res protocolInfo=\"http-get:*:").append(sMime).append(":*\">").append(sUrl).append("</res>");
        }
        sb.append("</item>");
    }

    private void handleCdsSoap(SocketChannel ch, String soapAction, String body) throws IOException {
        if (soapAction.contains("Browse") || body.contains("Browse")) {
            List<MediaItem> all = new ArrayList<>(catalog.values());
            StringBuilder didl = new StringBuilder(Math.max(512, all.size() * 512));
            didl.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
                .append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
                .append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
                .append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\" ")
                .append("xmlns:sec=\"http://www.sec.co.kr/\">");
            for (MediaItem item : all) {
                String vUrl = xmlEscape(videoUrl(item));
                String title = xmlEscape(item.title);
                String dur = MediaItem.formatDlnaDuration(item.durationMs);
                String proto = "http-get:*:" + item.mimeType + ":" + DLNA_VIDEO_FLAGS;
                appendDidlItem(didl, item, vUrl, title, dur, proto);
            }
            didl.append("</DIDL-Lite>");

            String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body><u:BrowseResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">"
                    + "<Result>" + xmlEscape(didl.toString()) + "</Result>"
                    + "<NumberReturned>" + all.size() + "</NumberReturned>"
                    + "<TotalMatches>" + all.size() + "</TotalMatches>"
                    + "<UpdateID>1</UpdateID>"
                    + "</u:BrowseResponse></s:Body></s:Envelope>";
            sendSoapOk(ch, soap);
        } else if (soapAction.contains("GetSystemUpdateID")) {
            String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body><u:GetSystemUpdateIDResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">"
                    + "<Id>1</Id></u:GetSystemUpdateIDResponse></s:Body></s:Envelope>";
            sendSoapOk(ch, soap);
        } else {
            String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body><u:GetSearchCapabilitiesResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">"
                    + "<SearchCaps></SearchCaps></u:GetSearchCapabilitiesResponse></s:Body></s:Envelope>";
            sendSoapOk(ch, soap);
        }
    }

    private void handleCmsSoap(SocketChannel ch, String soapAction) throws IOException {
        String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:GetProtocolInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:ConnectionManager:1\">"
                + "<Source>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/webm:*,http-get:*:video/MP2T:*,http-get:*:text/srt:*</Source>"
                + "<Sink></Sink>"
                + "</u:GetProtocolInfoResponse></s:Body></s:Envelope>";
        sendSoapOk(ch, soap);
    }

    private String buildDeviceXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<device>"
                + "<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>"
                + "<dlna:X_DLNADOC xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">DMS-1.50</dlna:X_DLNADOC>"
                + "<friendlyName>" + xmlEscape(friendlyName) + "</friendlyName>"
                + "<manufacturer>TizenDLNA</manufacturer>"
                + "<modelName>TizenDLNA Android 16</modelName>"
                + "<modelNumber>1.0</modelNumber>"
                + "<UDN>" + udn + "</UDN>"
                + "<serviceList>"
                + "<service>"
                + "<serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>"
                + "<SCPDURL>/dms/cds.xml</SCPDURL>"
                + "<controlURL>/dms/control/cds</controlURL>"
                + "<eventSubURL></eventSubURL>"
                + "</service>"
                + "<service>"
                + "<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>"
                + "<SCPDURL>/dms/cms.xml</SCPDURL>"
                + "<controlURL>/dms/control/cms</controlURL>"
                + "<eventSubURL></eventSubURL>"
                + "</service>"
                + "</serviceList>"
                + "</device></root>";
    }

    private static String buildCdsScpdXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList><action><name>Browse</name></action></actionList>"
                + "<serviceStateTable/>"
                + "</scpd>";
    }

    private static String buildCmsScpdXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList><action><name>GetProtocolInfo</name></action></actionList>"
                + "<serviceStateTable/>"
                + "</scpd>";
    }

    private static void sendXml(SocketChannel ch, String method, String xml) throws IOException {
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        String hdr = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/xml; charset=\"utf-8\"\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Server: Android/16 UPnP/1.0 TizenDLNA/1.0\r\n"
                + "Connection: close\r\n\r\n";
        writeAll(ch, hdr.getBytes(StandardCharsets.US_ASCII));
        if (!"HEAD".equals(method)) {
            writeAll(ch, body);
        }
    }

    private static void sendSoapOk(SocketChannel ch, String soap) throws IOException {
        byte[] body = soap.getBytes(StandardCharsets.UTF_8);
        String hdr = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/xml; charset=\"utf-8\"\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "EXT:\r\n"
                + "Server: Android/16 UPnP/1.0 TizenDLNA/1.0\r\n"
                + "Connection: close\r\n\r\n";
        writeAll(ch, hdr.getBytes(StandardCharsets.US_ASCII));
        writeAll(ch, body);
    }

    private static void sendError(SocketChannel ch, int code, String msg) throws IOException {
        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
        String hdr = "HTTP/1.1 " + code + " " + msg + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        writeAll(ch, hdr.getBytes(StandardCharsets.US_ASCII));
        writeAll(ch, body);
    }

    private static void sendRangeNotSatisfiable(SocketChannel ch, long totalLen) throws IOException {
        String hdr = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                + "Content-Range: bytes */" + totalLen + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        writeAll(ch, hdr.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeAll(SocketChannel ch, byte[] data) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(data);
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    private static String readBody(BufferedReader reader, int contentLength) throws IOException {
        if (contentLength <= 0 || contentLength > 65536) return "";
        char[] cbuf = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = reader.read(cbuf, read, contentLength - read);
            if (n < 0) break;
            read += n;
        }
        return new String(cbuf, 0, read);
    }

    private static Long parseMediaId(String path, String prefix) {
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        String idStr = (slash >= 0) ? rest.substring(0, slash) : rest;
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long[] parseByteRange(String header, long totalLen) {
        if (totalLen <= 0) return null;
        String spec = header.substring("bytes=".length()).trim();
        int comma = spec.indexOf(',');
        if (comma >= 0) spec = spec.substring(0, comma).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;

        String sStart = spec.substring(0, dash).trim();
        String sEnd = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (sStart.isEmpty()) {
                long suffix = Long.parseLong(sEnd);
                if (suffix <= 0) return null;
                start = Math.max(0, totalLen - suffix);
                end = totalLen - 1;
            } else {
                start = Long.parseLong(sStart);
                end = sEnd.isEmpty() ? (totalLen - 1) : Math.min(Long.parseLong(sEnd), totalLen - 1);
            }
            if (start < 0 || start >= totalLen || end < start) return null;
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String xmlEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
