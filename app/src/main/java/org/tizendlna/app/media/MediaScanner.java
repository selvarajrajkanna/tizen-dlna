package org.tizendlna.app.media;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;

import org.tizendlna.app.model.MediaItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class MediaScanner {
    private MediaScanner() {}

    public static List<MediaItem> scanVideos(Context context) {
        List<MediaItem> result = new ArrayList<>(64);
        ContentResolver cr = context.getContentResolver();

        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        };

        String sortOrder = MediaStore.Video.Media.DATE_MODIFIED + " DESC";

        try (Cursor cursor = cr.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
        )) {
            if (cursor == null) return result;

            int idxId = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int idxData = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
            int idxName = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int idxDur = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            int idxSize = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int idxBucket = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);

            while (cursor.moveToNext()) {
                String path = cursor.getString(idxData);
                if (path == null || path.isEmpty()) continue;

                File file = new File(path);
                if (!file.isFile() || !file.canRead()) continue;

                long id = cursor.getLong(idxId);
                String name = cursor.getString(idxName);
                if (name == null || name.isEmpty()) name = file.getName();

                long duration = cursor.getLong(idxDur);
                long size = cursor.getLong(idxSize);
                if (size <= 0) size = file.length();

                String folder = cursor.getString(idxBucket);
                if (folder == null || folder.isEmpty()) {
                    File parent = file.getParentFile();
                    folder = parent != null ? parent.getName() : "Storage";
                }

                File sub = MediaItem.findMatchingSubtitle(file);
                String subType = sub != null ? MediaItem.subtitleTypeOf(sub) : "";

                result.add(new MediaItem(
                        id,
                        file,
                        name,
                        folder,
                        size,
                        duration,
                        MediaItem.dlnaMimeForVideo(file),
                        sub,
                        subType
                ));
            }
        } catch (Exception ignored) {
            // Permission may not be granted yet
        }

        return result;
    }
}
