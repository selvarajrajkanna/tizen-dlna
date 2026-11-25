package org.tizendlna.app.model;

import java.io.File;
import java.util.Locale;

public final class MediaItem {
    public final long id;
    public final File videoFile;
    public final String title;
    public final String folderName;
    public final long sizeBytes;
    public final long durationMs;
    public final String mimeType;
    public final File subtitleFile;
    public final String subtitleType; // e.g. "srt", "smi", "ass", "vtt"

    public MediaItem(
            long id,
            File videoFile,
            String title,
            String folderName,
            long sizeBytes,
            long durationMs,
            String mimeType,
            File subtitleFile,
            String subtitleType
    ) {
        this.id = id;
        this.videoFile = videoFile;
        this.title = title;
        this.folderName = folderName;
        this.sizeBytes = sizeBytes;
        this.durationMs = durationMs;
        this.mimeType = mimeType;
        this.subtitleFile = subtitleFile;
        this.subtitleType = subtitleType;
    }

    public boolean hasSubtitle() {
        return subtitleFile != null && subtitleFile.isFile() && subtitleFile.canRead();
    }

    private static final String[] SUB_EXTS = {"srt", "smi", "vtt", "ass", "ssa", "sub"};

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
        String fn = subFile.getName();
        int dot = fn.lastIndexOf('.');
        if (dot <= 0 || dot == fn.length() - 1) return "srt";
        return fn.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String dlnaMimeForVideo(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".mp4") || name.endsWith(".m4v")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".ts") || name.endsWith(".m2ts") || name.endsWith(".mts")) return "video/MP2T";
        if (name.endsWith(".avi")) return "video/x-msvideo";
        if (name.endsWith(".wmv")) return "video/x-ms-wmv";
        if (name.endsWith(".mpg") || name.endsWith(".mpeg")) return "video/mpeg";
        if (name.endsWith(".flv")) return "video/x-flv";
        if (name.endsWith(".3gp")) return "video/3gpp";
        return "video/mp4";
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
