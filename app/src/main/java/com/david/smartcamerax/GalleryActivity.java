package com.david.smartcamerax;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.TextView;
import android.widget.ImageButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.david.smartcamerax.storage.ImageStore;

/**
 * GalleryActivity
 * <p>
 * Muestra una grilla de imágenes guardadas por la app (Pictures/SmartCameraX) consultando MediaStore.
 * - Usa GalleryAdapter para renderizar miniaturas.
 * - Al pulsar una miniatura abre ImageViewerActivity pasando la lista completa de URIs y la posición
 *   pulsada para permitir navegación por swipe.
 *
 * Consideraciones:
 * - En Android Q+ la consulta usa RELATIVE_PATH para filtrar por la carpeta de la app.
 * - En versiones anteriores se filtra por DISPLAY_NAME como heurística.
 * - Se recomienda usar una librería de carga de imágenes (Glide/Picasso) si la galería crece en tamaño.
 */
public class GalleryActivity extends AppCompatActivity {

    private static final String TAG = "GalleryActivity";

    private RecyclerView rvGallery;
    private TextView tvEmpty;
    private GalleryAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        rvGallery = findViewById(R.id.rv_gallery);
        tvEmpty = findViewById(R.id.tv_empty);

        // Botón volver: usar findViewById directo (el id existe en el layout)
        ImageButton btnBack = findViewById(R.id.btn_back_gallery);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                Intent intent = new Intent(GalleryActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            });
        }

        // Grid de 3 columnas para miniaturas
        rvGallery.setLayoutManager(new GridLayoutManager(this, 3));
        // cuando se hace click se crea una lista de strings (URIs) y se abre el visor en la posición
        adapter = new GalleryAdapter((item, position) -> {
            ArrayList<String> list = new ArrayList<>();
            boolean[] videoFlags = new boolean[adapter.getCurrentList().size()];
            int i = 0;
            for (MediaItem mi : adapter.getCurrentList()) {
                list.add(mi.uri.toString());
                videoFlags[i++] = mi.isVideo;
            }
            ImageViewerActivity.start(GalleryActivity.this, list, videoFlags, position);
        });
        rvGallery.setAdapter(adapter);

        loadImages();
    }

    /**
     * loadImages()
     * <p>
     * Consulta MediaStore y actualiza el adaptador. Si no hay imágenes muestra un mensaje.
     */
    private void loadImages() {
        List<MediaItem> items = queryAppMedia(this);
        if (items.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            rvGallery.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            rvGallery.setVisibility(View.VISIBLE);
            adapter.submitList(items);
        }
    }

    /**
     * queryAppMedia()
     * <p>
     * Consulta MediaStore para devolver URIs de imágenes y videos guardados por la app.
     * - En Android Q+ filtra por RELATIVE_PATH (más fiable).
     * - En versiones antiguas filtra por DISPLAY_NAME con un patrón (heurística).
     *
     * Nota: devuelve URIs content:// que pueden usarse con ImageView.setImageURI o con Glide.
     */
    public static List<MediaItem> queryAppMedia(Context ctx) {
        List<MediaItem> result = new ArrayList<>();
        // Fotos
        Uri imgCollection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] imgProjection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_ADDED
        };
        String imgSelection;
        String[] imgSelectionArgs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            imgSelection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            imgSelectionArgs = new String[]{"%" + ImageStore.RELATIVE_PATH + "%"};
        } else {
            imgSelection = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
            imgSelectionArgs = new String[]{"%SmartCameraX%"};
        }
        String sort = MediaStore.Images.Media.DATE_ADDED + " DESC";
        try (Cursor cursor = ctx.getContentResolver().query(imgCollection, imgProjection, imgSelection, imgSelectionArgs, sort)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    long date = cursor.getLong(dateCol);
                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    result.add(new MediaItem(contentUri, false, date));
                }
            }
        } catch (Exception e) { Log.w(TAG, "queryAppMedia images error", e); }

        // Videos (guardados en Movies/SmartCameraX)
        Uri vidCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] vidProjection = new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.DATE_ADDED
        };
        String vidSelection;
        String[] vidSelectionArgs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vidSelection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
            vidSelectionArgs = new String[]{"%Movies/SmartCameraX%"};
        } else {
            vidSelection = MediaStore.Video.Media.DISPLAY_NAME + " LIKE ?";
            vidSelectionArgs = new String[]{"%SmartCameraX%"};
        }
        try (Cursor cursor = ctx.getContentResolver().query(vidCollection, vidProjection, vidSelection, vidSelectionArgs, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    long date = cursor.getLong(dateCol);
                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                    result.add(new MediaItem(contentUri, true, date));
                }
            }
        } catch (Exception e) { Log.w(TAG, "queryAppMedia videos error", e); }

        // Orden combinado por fecha descendente
        Collections.sort(result, Comparator.comparingLong((MediaItem m) -> m.dateAdded).reversed());
        return result;
    }

}
