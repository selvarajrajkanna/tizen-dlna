package org.tizendlna.app.media;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.tizendlna.app.model.MediaItem;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Discovers local video files on Android 16.
 * When All Files Access (MANAGE_EXTERNAL_STORAGE) is granted, walks external storage directly
 * so unindexed MKV/TS files and same-directory subtitles are always found immediately.
 * Falls back to MediaStore.Video when only READ_MEDIA_VIDEO is granted.
 */
public final class MediaScanner {
    private static final String TAG = "MediaScanner";
    private static final int MAX_DEPTH = 8;

    private static final Set<String> VIDEO_EXTS = Set.of(
            "mkv", "mp4", "m4v", "webm", "mov", "ts", "m2ts", "mts",
            "avi", "wmv", "mpg", "mpeg", "flv", "3gp"
    );

    private MediaScanner() {}

    public static List<MediaItem> scanVideos(Context context) {
        Map<String, MediaStoreMeta> storeMeta = queryMediaStoreMeta(context);

        if (Environment.isExternalStorageManager()) {
            File root = Environment.getExternalStorageDirectory();
            if (root != null && root.isDirectory()) {
                List<File> foundFiles = new ArrayList<>(64);
                walkDir(root, 0, foundFiles);
                foundFiles.sort(Comparator.comparingLong(File::lastModified).reversed());

                List<MediaItem> result = new ArrayList<>(foundFiles.size());
                Set<Long> usedIds = new HashSet<>(foundFiles.size() * 2);
                for (File f : foundFiles) {
                    String path = f.getAbsolutePath();
                    MediaStoreMeta meta = storeMeta.get(path);
                    long id = (meta != null && meta.id > 0) ? meta.id : stableIdForPath(path);
                    while (!usedIds.add(id)) {
                        id++;
                    }
                    long duration = (meta != null) ? meta.durationMs : 0L;
                    long size = f.length();
                    File parent = f.getParentFile();
                    String folder = (parent != null && !parent.equals(root)) ? parent.getName() : "Storage";

                    File sub = MediaItem.findMatchingSubtitle(f);
                    String subType = sub != null ? MediaItem.subtitleTypeOf(sub) : "";

                    result.add(new MediaItem(
                            id,
                            f,
                            f.getName(),
                            folder,
                            size,
                            duration,
                            MediaItem.dlnaMimeForVideo(f),
                            sub,
                            subType
                    ));
                }
                return result;
            }
        }

        // Fallback when All Files Access is not granted: use MediaStore rows directly
        List<MediaItem> fallback = new ArrayList<>(storeMeta.size());
        for (MediaStoreMeta m : storeMeta.values()) {
            File f = new File(m.path);
            if (!f.isFile() || !f.canRead()) continue;
            File sub = MediaItem.findMatchingSubtitle(f);
            String subType = sub != null ? MediaItem.subtitleTypeOf(sub) : "";
            fallback.add(new MediaItem(
                    m.id,
                    f,
                    m.displayName,
                    m.folder,
                    m.sizeBytes > 0 ? m.sizeBytes : f.length(),
                    m.durationMs,
                    MediaItem.dlnaMimeForVideo(f),
                    sub,
                    subType
            ));
        }
        fallback.sort(Comparator.comparingLong((MediaItem item) -> item.videoFile.lastModified()).reversed());
        return fallback;
    }

    private static void walkDir(File dir, int depth, List<File> out) {
        if (depth > MAX_DEPTH || dir == null) return;
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            String name = child.getName();
            if (name.startsWith(".")) continue;
            if (child.isDirectory()) {
                if (depth == 0 && "Android".equals(name)) continue;
                walkDir(child, depth + 1, out);
            } else if (child.isFile() && isVideoFile(name) && child.canRead()) {
                out.add(child);
            }
        }
    }

    private static boolean isVideoFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return false;
        return VIDEO_EXTS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static long stableIdForPath(String path) {
        long h = 1125899906842597L;
        for (int i = 0; i < path.length(); i++) {
            h = 31L * h + path.charAt(i);
        }
        return (h & 0x7FFF_FFFF_FFFF_FFFFL) | 1L;
    }

    private static Map<String, MediaStoreMeta> queryMediaStoreMeta(Context context) {
        Map<String, MediaStoreMeta> map = new HashMap<>(64);
        ContentResolver cr = context.getContentResolver();
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        };

        try (Cursor cursor = cr.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Video.Media.DATE_MODIFIED + " DESC"
        )) {
            if (cursor == null) return map;
            int idxId = cursor.getColumnIndex(MediaStore.Video.Media._ID);
            int idxData = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
            int idxName = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
            int idxDur = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
            int idxSize = cursor.getColumnIndex(MediaStore.Video.Media.SIZE);
            int idxBucket = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);

            while (cursor.moveToNext()) {
                String path = (idxData >= 0) ? cursor.getString(idxData) : null;
                if (path == null || path.isEmpty()) continue;
                long id = (idxId >= 0) ? cursor.getLong(idxId) : stableIdForPath(path);
                String name = (idxName >= 0) ? cursor.getString(idxName) : new File(path).getName();
                long dur = (idxDur >= 0) ? cursor.getLong(idxDur) : 0L;
                long size = (idxSize >= 0) ? cursor.getLong(idxSize) : 0L;
                String folder = (idxBucket >= 0) ? cursor.getString(idxBucket) : null;
                if (folder == null || folder.isEmpty()) {
                    File p = new File(path).getParentFile();
                    folder = p != null ? p.getName() : "Storage";
                }
                map.put(path, new MediaStoreMeta(id, path, name, folder, size, dur));
            }
        } catch (Exception e) {
            Log.d(TAG, "MediaStore query skipped: " + e.getMessage());
        }
        return map;
    }

    private static final class MediaStoreMeta {
        final long id;
        final String path;
        final String displayName;
        final String folder;
        final long sizeBytes;
        final long durationMs;

        MediaStoreMeta(long id, String path, String displayName, String folder, long sizeBytes, long durationMs) {
            this.id = id;
            this.path = path;
            this.displayName = displayName != null ? displayName : "";
            this.folder = folder != null ? folder : "Storage";
            this.sizeBytes = sizeBytes;
            this.durationMs = durationMs;
        }
    }
}
