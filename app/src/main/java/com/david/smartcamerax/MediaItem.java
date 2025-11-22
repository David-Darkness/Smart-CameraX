package com.david.smartcamerax;

import android.net.Uri;
import androidx.annotation.NonNull;

/**
 * MediaItem representa una entrada de la galería (foto o video).
 */
public class MediaItem {
    public final Uri uri;
    public final boolean isVideo;
    public final long dateAdded; // segundos desde epoch (MediaStore.DATE_ADDED)

    public MediaItem(@NonNull Uri uri, boolean isVideo, long dateAdded) {
        this.uri = uri;
        this.isVideo = isVideo;
        this.dateAdded = dateAdded;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MediaItem)) return false;
        MediaItem other = (MediaItem) o;
        return uri.equals(other.uri) && isVideo == other.isVideo;
    }

    @Override
    public int hashCode() {
        int r = uri.hashCode();
        return 31 * r + (isVideo ? 1 : 0);
    }
}

