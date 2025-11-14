package com.david.smartcamerax.storage;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class ImageStore {

    private static final String TAG = "ImageStore";
    public static final String RELATIVE_PATH = "Pictures/SmartCameraX";

    // Construye ContentValues para guardar una imagen con un nombre
    public static ContentValues buildContentValues(String displayName) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        contentValues.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH);
        }
        return contentValues;
    }

    // Obtener Uri del último archivo con ese displayName
    public static Uri getImageContentUri(Context ctx, String displayName) {
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME};
        String selection = MediaStore.Images.Media.DISPLAY_NAME + "=?";
        String[] selectionArgs = new String[]{displayName};
        Uri result = null;
        try (Cursor cursor = ctx.getContentResolver().query(collection, projection, selection, selectionArgs, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                result = ContentUris.withAppendedId(collection, id);
            }
        } catch (Exception e) {
            Log.w(TAG, "getImageContentUri: error buscando uri", e);
        }
        return result;
    }

    // Busca imágenes guardadas por la app en Pictures/SmartCameraX
    public static List<Uri> queryAppImages(Context ctx) {
        List<Uri> result = new ArrayList<>();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_ADDED
        };

        String selection;
        String[] selectionArgs = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            selectionArgs = new String[]{"%" + RELATIVE_PATH + "%"};
        } else {
            selection = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
            selectionArgs = new String[]{"%SmartCameraX%"};
        }

        String sort = MediaStore.Images.Media.DATE_ADDED + " DESC";

        try (Cursor cursor = ctx.getContentResolver().query(collection, projection, selection, selectionArgs, sort)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    result.add(contentUri);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "queryAppImages: error", e);
        }

        return result;
    }
}

