package org.tizendlna.app.model;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class MediaItem {
    public final long id;
    public final File videoFile; // Media file (video or audio)
    public final String title;
    public final String folderName;
    public final long sizeBytes;
    public final long durationMs;
    public final String mimeType;
    public final boolean isAudio;
    public final File subtitleFile;
    public final String subtitleType; // e.g. "srt", "smi", "ass", "vtt"

    private static final String[] SUB_EXTS = {"srt", "smi", "vtt", "ass", "ssa", "sub"};

    private static final Set<String> VIDEO_EXTS = new HashSet<>(Arrays.asList(
            "mkv", "mp4", "m4v", "webm", "mov", "ts", "m2ts", "mts",
            "avi", "wmv", "mpg", "mpeg", "flv", "3gp"
    ));

    private static final Set<String> AUDIO_EXTS = new HashSet<>(Arrays.asList(
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma"
    ));

    public MediaItem(
            long id,
            File mediaFile,
            String title,
            String folderName,
            long sizeBytes,
            long durationMs,
            String mimeType,
            boolean isAudio,
            File subtitleFile,
            String subtitleType
    ) {
        this.id = id;
        this.videoFile = mediaFile;
        this.title = title;
        this.folderName = folderName;
        this.sizeBytes = sizeBytes;
        this.durationMs = durationMs;
        this.mimeType = mimeType;
        this.isAudio = isAudio;
        this.subtitleFile = subtitleFile;
        this.subtitleType = subtitleType;
    }

    public static MediaItem fromFile(File file) {
        boolean audio = isAudioFile(file.getName());
        File sub = audio ? null : findMatchingSubtitle(file);
        String subType = sub != null ? subtitleTypeOf(sub) : "";
        File parent = file.getParentFile();
        String folder = parent != null ? parent.getName() : "Storage";
        return new MediaItem(
                stableIdForPath(file.getAbsolutePath()),
                file,
                file.getName(),
                folder,
                file.length(),
                0L,
                dlnaMimeForFile(file),
                audio,
                sub,
                subType
        );
    }

    public boolean hasSubtitle() {
        return !isAudio && subtitleFile != null && subtitleFile.isFile() && subtitleFile.canRead();
    }

    public static boolean isVideoFile(String name) {
        String ext = extensionOf(name);
        return !ext.isEmpty() && VIDEO_EXTS.contains(ext);
    }

    public static boolean isAudioFile(String name) {
        String ext = extensionOf(name);
        return !ext.isEmpty() && AUDIO_EXTS.contains(ext);
    }

    public static boolean isPlayableMedia(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName();
        return isVideoFile(name) || isAudioFile(name);
    }

    public static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static long stableIdForPath(String path) {
        long h = 1125899906842597L;
        for (int i = 0; i < path.length(); i++) {
            h = 31L * h + path.charAt(i);
        }
        return (h & 0x7FFF_FFFF_FFFF_FFFFL) | 1L;
    }

    /**
     * Finds a matching subtitle in the exact same directory as {@code videoFile}.
     * Priority:
     * 1. Exact basename match (Movie.srt before Movie.vtt / Movie.ass, etc.)
     * 2. English-tagged match (Movie.en.srt, Movie.eng.srt, Movie.english.srt)
     * 3. Any language-tagged match (Movie.<lang>.srt)
     */
    public static File findMatchingSubtitle(File videoFile) {
        if (videoFile == null) return null;
        File dir = videoFile.getParentFile();
        if (dir == null || !dir.isDirectory()) return null;

        String name = videoFile.getName();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0) ? name.substring(0, dot) : name;
        String baseLower = base.toLowerCase(Locale.ROOT);

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return null;

        File bestExact = null;
        int bestExactRank = Integer.MAX_VALUE;

        File bestLangEn = null;
        int bestLangEnRank = Integer.MAX_VALUE;

        File bestLangAny = null;
        int bestLangAnyRank = Integer.MAX_VALUE;

        for (File f : files) {
            if (!f.isFile() || !f.canRead()) continue;
            String fn = f.getName();
            int fdot = fn.lastIndexOf('.');
            if (fdot <= 0 || fdot == fn.length() - 1) continue;

            String ext = fn.substring(fdot + 1).toLowerCase(Locale.ROOT);
            int rank = subtitleExtRank(ext);
            if (rank < 0) continue;

            String fbaseLower = fn.substring(0, fdot).toLowerCase(Locale.ROOT);
            if (fbaseLower.equals(baseLower)) {
                if (rank < bestExactRank) {
                    bestExact = f;
                    bestExactRank = rank;
                }
            } else if (fbaseLower.startsWith(baseLower + ".")) {
                String suffix = fbaseLower.substring(baseLower.length() + 1);
                if (suffix.equals("en") || suffix.equals("eng") || suffix.equals("english")
                        || suffix.startsWith("en.") || suffix.startsWith("eng.")) {
                    if (rank < bestLangEnRank) {
                        bestLangEn = f;
                        bestLangEnRank = rank;
                    }
                } else if (rank < bestLangAnyRank) {
                    bestLangAny = f;
                    bestLangAnyRank = rank;
                }
            }
        }

        if (bestExact != null) return bestExact;
        if (bestLangEn != null) return bestLangEn;
        return bestLangAny;
    }

    private static int subtitleExtRank(String ext) {
        for (int i = 0; i < SUB_EXTS.length; i++) {
            if (SUB_EXTS[i].equals(ext)) return i;
        }
        return -1;
    }

    public static String subtitleTypeOf(File subFile) {
        if (subFile == null) return "";
        String ext = extensionOf(subFile.getName());
        return ext.isEmpty() ? "srt" : ext;
    }

    public static String dlnaMimeForFile(File file) {
        String ext = extensionOf(file.getName());
        return switch (ext) {
            case "mkv" -> "video/x-matroska";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "ts", "m2ts", "mts" -> "video/MP2T";
            case "avi" -> "video/x-msvideo";
            case "wmv" -> "video/x-ms-wmv";
            case "mpg", "mpeg" -> "video/mpeg";
            case "flv" -> "video/x-flv";
            case "3gp" -> "video/3gpp";
            case "mp3" -> "audio/mpeg";
            case "flac" -> "audio/flac";
            case "m4a", "aac" -> "audio/mp4";
            case "ogg", "opus" -> "audio/ogg";
            case "wav" -> "audio/wav";
            case "wma" -> "audio/x-ms-wma";
            default -> "application/octet-stream";
        };
    }

    public static String dlnaMimeForSubtitle(String subType) {
        if ("smi".equalsIgnoreCase(subType)) return "smi/caption";
        if ("vtt".equalsIgnoreCase(subType)) return "text/vtt";
        return "text/srt";
    }

    public static String formatDlnaDuration(long durationMs) {
        if (durationMs <= 0) return "0:00:00.000";
        long totalSec = durationMs / 1000;
        long ms = durationMs % 1000;
        long hrs = totalSec / 3600;
        long mins = (totalSec % 3600) / 60;
        long secs = totalSec % 60;
        return String.format(Locale.US, "%d:%02d:%02d.%03d", hrs, mins, secs, ms);
    }

    public static String formatHumanDuration(long durationMs) {
        if (durationMs <= 0) return "";
        long totalSec = durationMs / 1000;
        long hrs = totalSec / 3600;
        long mins = (totalSec % 3600) / 60;
        long secs = totalSec % 60;
        if (hrs > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs);
        }
        return String.format(Locale.US, "%d:%02d", mins, secs);
    }

    public static String formatHumanSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
