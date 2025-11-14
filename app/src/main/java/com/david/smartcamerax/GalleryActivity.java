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
        adapter = new GalleryAdapter((uri, position) -> {
            ArrayList<String> list = new ArrayList<>();
            for (Uri u : adapter.getCurrentList()) {
                list.add(u.toString());
            }
            ImageViewerActivity.start(GalleryActivity.this, list, position);
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
        List<Uri> items = queryAppImages(this);
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
     * queryAppImages()
     * <p>
     * Consulta MediaStore para devolver URIs de imágenes guardadas por la app.
     * - En Android Q+ filtra por RELATIVE_PATH (más fiable).
     * - En versiones antiguas filtra por DISPLAY_NAME con un patrón (heurística).
     *
     * Nota: devuelve URIs content:// que pueden usarse con ImageView.setImageURI o con Glide.
     */
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
        String[] selectionArgs;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            selectionArgs = new String[]{"%" + ImageStore.RELATIVE_PATH + "%"};
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
            // Logueamos el error para diagnóstico; no lanzamos excepción para no romper la UI
            Log.w(TAG, "queryAppImages: error", e);
        }

        return result;
    }

}
