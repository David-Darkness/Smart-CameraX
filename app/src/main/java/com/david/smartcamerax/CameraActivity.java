package com.david.smartcamerax;

import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.ImageView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.david.smartcamerax.analyzers.SmartAnalyzer;
import com.david.smartcamerax.storage.ImageStore;
import com.david.smartcamerax.utils.PermissionHelper;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CameraActivity
 * <p>
 * Actividad principal que gestiona la cámara, captura de imágenes, filtros simples y un "modo inteligente"
 * que usa SmartAnalyzer para detectar texto y códigos QR en el preview.
 *
 * Documentación en el propio archivo (comentarios) para que puedas entender rápidamente qué hace
 * cada campo y método, cómo se integran SmartAnalyzer y ImageStore, y cómo probar el flujo.
 *
 * Resumen rápido:
 * - startCamera() / bindCameraUseCases(): inicializa CameraX y enlaza los use cases (Preview, ImageCapture,
 *   opcionalmente ImageAnalysis cuando smartMode == true).
 * - SmartAnalyzer: se instancia una sola vez y se guarda en "smartAnalyzer" para poder cerrarla correctamente
 *   cuando se desactiva el modo inteligente o al destruir la Activity.
 * - takePhoto(): guarda la imagen usando MediaStore y ImageStore.buildContentValues() para que las fotos
 *   queden en Pictures/SmartCameraX en Android Q+.
 *
 * Cómo probar:
 * 1. Abrir la app y otorgar permisos de cámara.
 * 2. Pulsar el botón de alternar modo inteligente (fabSmart) y observar los resultados en el overlay tvResult.
 * 3. Tomar una foto y comprobar que se guarda en la galería (Pictures/SmartCameraX).
 */
public class CameraActivity extends AppCompatActivity {

    private static final String TAG = "CameraActivity";

    // PreviewView (vista que muestra la cámara en pantalla)
    private androidx.camera.view.PreviewView previewView;

    // ImageCapture: use case para capturar fotos (se configura en bindCameraUseCases)
    private ImageCapture imageCapture;

    // CameraSelector: mantiene si usamos cámara trasera o frontal
    private CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

    // Estado del modo inteligente (texto/QR)
    private boolean smartMode = false;

    // Filter state: 0 normal, 1 B/N, 2 Sepia. Se aplica también en preview ahora.
    private int currentFilter = 0; // 0 normal, 1 bw, 2 sepia

    // Executor para trabajo en background relacionado con la cámara (análisis)
    private ExecutorService cameraExecutor;

    // ProcessCameraProvider future (para inicializar CameraX)
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    // Overlays de UI para mostrar resultados del modo inteligente y el filtro actual.
    // Los TextView están en activity_camera.xml con ids tv_result y tv_filter.
    private TextView tvResult;
    private TextView tvFilter;

    // ImageView overlay para mostrar el preview filtrado
    private ImageView ivFilterOverlay;

    // Floating action buttons: promovidos a campos para calcular posiciones dinámicamente
    private FloatingActionButton fabSwitch;
    private FloatingActionButton fabFilter;
    private FloatingActionButton fabSmart;

    // Handler del hilo principal para mostrar/ocultar overlays con delay (auto-hide)
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideResultRunnable = () -> {
        if (tvResult != null) tvResult.setVisibility(View.GONE);
    };
    private final Runnable hideFilterRunnable = () -> {
        if (tvFilter != null) tvFilter.setVisibility(View.GONE);
    };

    // Runnable para actualizar el preview filtrado periódicamente (ejecutado desde mainHandler)
    private final Runnable filterLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!filterRunning) return;
            // Capturar bitmap del PreviewView en UI thread
            Bitmap bmp = previewView.getBitmap();
            if (bmp != null) {
                final Bitmap src = bmp.copy(Bitmap.Config.ARGB_8888, true);
                // Procesar en background
                cameraExecutor.execute(() -> {
                    Bitmap filtered = null;
                    try {
                        if (currentFilter == 1) {
                            filtered = com.david.smartcamerax.utils.Filters.toGrayscale(src);
                        } else if (currentFilter == 2) {
                            filtered = com.david.smartcamerax.utils.Filters.toSepia(src);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "filter processing failed", e);
                    }
                    if (filtered != null) {
                        final Bitmap finalFiltered = filtered;
                        mainHandler.post(() -> {
                            if (ivFilterOverlay != null) ivFilterOverlay.setImageBitmap(finalFiltered);
                        });
                    }
                });
            }

            // Repetir cada 120ms
            mainHandler.postDelayed(this, 120);
        }
    };

    // Flag para controlar el loop de preview filtrado
    private volatile boolean filterRunning = false;

    // OnGlobalLayoutListener guardado para poder removerlo en onDestroy
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;

    // Mantener referencia del SmartAnalyzer para cerrarlo correctamente cuando deje de usarse
    // (evita fugas de memoria y múltiples instancias recreadas cada frame).
    private SmartAnalyzer smartAnalyzer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        // Bind de vistas
        previewView = findViewById(R.id.preview_view);
        fabSwitch = findViewById(R.id.fab_switch);
        fabFilter = findViewById(R.id.fab_filter);
        fabSmart = findViewById(R.id.fab_smart);
        ImageButton btnCapture = findViewById(R.id.btn_capture);
        ImageButton btnBack = findViewById(R.id.btn_back_camera);

        // Botón volver a pantalla principal
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                Intent intent = new Intent(CameraActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            });
        }

        // Overlays
        tvResult = findViewById(R.id.tv_result);
        tvFilter = findViewById(R.id.tv_filter);

        // Crear ImageView overlay para mostrar preview filtrado y añadirlo encima del previewView
        try {
            ivFilterOverlay = new ImageView(this);
            ivFilterOverlay.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ivFilterOverlay.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ivFilterOverlay.setVisibility(View.GONE);
            // Añadir al parent del previewView para que quede encima
            View parent = (View) previewView.getParent();
            if (parent instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) parent;
                int index = vg.indexOfChild(previewView);
                vg.addView(ivFilterOverlay, index + 1);
            }
        } catch (Exception e) {
            Log.w(TAG, "No se pudo crear overlay de filtro", e);
            ivFilterOverlay = null;
        }

        // Executor dedicado para análisis y tareas relacionadas con la cámara
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Ajustar posiciones de los overlays después del layout para evitar solapamiento con FABs
        layoutListener = this::adjustOverlayPositions;
        previewView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);

        // Botón: cambiar cámara (frontal/trasera)
        fabSwitch.setOnClickListener(v -> {
            cameraSelector = cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA ?
                    CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
            startCamera(); // restart camera con nuevo selector
        });

        // Botón: cambiar filtro (se aplica ahora también al preview)
        fabFilter.setOnClickListener(v -> {
            currentFilter = (currentFilter + 1) % 3;
            String filterName = currentFilter == 0 ? getString(R.string.filter_normal) : currentFilter == 1 ? getString(R.string.filter_bw) : getString(R.string.filter_sepia);
            if (tvFilter != null) {
                tvFilter.setText(getString(R.string.msg_filter, filterName));
                tvFilter.setVisibility(View.VISIBLE);
                mainHandler.removeCallbacks(hideFilterRunnable);
                mainHandler.postDelayed(hideFilterRunnable, 1500);
            }

            // Activar/desactivar preview filtrado
            if (currentFilter == 0) {
                stopFilterPreview();
            } else {
                startFilterPreview();
            }
        });

        // Botón: activar/desactivar modo inteligente (análisis en tiempo real)
        fabSmart.setOnClickListener(v -> {
            smartMode = !smartMode;
            String msg = smartMode ? getString(R.string.msg_smart_on) : getString(R.string.msg_smart_off);
            Snackbar.make(previewView, msg, Snackbar.LENGTH_SHORT).show();
            startCamera(); // re-bind con o sin ImageAnalysis según smartMode
        });

        // Botón de captura
        if (btnCapture != null) {
            btnCapture.setOnClickListener(v -> takePhoto());
        }

        // Pedir permisos si no están concedidos
        if (PermissionHelper.hasCameraPermission(this)) {
            startCamera();
        } else {
            PermissionHelper.requestCameraPermission(this);
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionHelper.REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                // Si el usuario deniega permisos, mostramos mensaje y cerramos la Activity
                Snackbar.make(previewView, "Permiso de cámara denegado", Snackbar.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /**
     * startCamera()
     * <p>
     * Inicia CameraX obteniendo un ProcessCameraProvider y delegando a bindCameraUseCases().
     * Se ejecuta en el ejecutor principal de la app cuando el Future está listo.
     */
    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * bindCameraUseCases()
     * <p>
     * Construye y enlaza los use cases de CameraX según el estado de la UI:
     * - Preview (siempre)
     * - ImageCapture (siempre)
     * - ImageAnalysis (solo cuando smartMode == true)
     *
     * Notas importantes:
     * - Se reutiliza una única instancia de SmartAnalyzer y se cierra (close()) cuando se desactiva el modo
     *   inteligente, evitando recreaciones innecesarias y fugas.
     * - Si el binding falla se captura la excepción y se registra para diagnóstico.
     */
    private void bindCameraUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder().build();
        imageCapture = new ImageCapture.Builder().build();

        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // Si SmartMode está desactivado y existía un analyzer, lo cerramos para liberar recursos
        if (!smartMode && smartAnalyzer != null) {
            try {
                smartAnalyzer.close();
            } catch (Exception e) {
                Log.w(TAG, "Error cerrando SmartAnalyzer", e);
            }
            smartAnalyzer = null;
        }

        if (smartMode) {
            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            // Crear SmartAnalyzer una sola vez y mantener la referencia
            smartAnalyzer = new SmartAnalyzer(result -> runOnUiThread(() -> {
                if (result != null && !result.isEmpty()) {
                    if (tvResult != null) {
                        // Mostrar el texto/QR detectado en el overlay
                        tvResult.setText(result);
                        tvResult.setVisibility(View.VISIBLE);
                        mainHandler.removeCallbacks(hideResultRunnable);
                        mainHandler.postDelayed(hideResultRunnable, 3000);
                    }
                }
            }));

            imageAnalysis.setAnalyzer(cameraExecutor, smartAnalyzer);
            try {
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalysis);
            } catch (Exception e) {
                Log.e(TAG, "Use case binding failed", e);
            }
            return;
        }

        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }

    /**
     * takePhoto()
     * <p>
     * Toma una foto y la guarda usando MediaStore. Si se seleccionó un filtro, se aplica al bitmap
     * después de guardarlo (operación simple que reescribe la Uri resultante).
     *
     * Notas:
     * - Usamos ImageStore.buildContentValues() para garantizar que las imágenes se guarden en
     *   Pictures/SmartCameraX en Android Q+.
     * - En algunos dispositivos outputFileResults.getSavedUri() puede devolver null — en ese caso
     *   intentamos buscar la Uri por displayName con ImageStore.getImageContentUri().
     */
    private void takePhoto() {
        if (imageCapture == null) return;

        String filename = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentValues contentValues = ImageStore.buildContentValues(filename);

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        ).build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Uri savedUri = outputFileResults.getSavedUri();
                if (savedUri == null) {
                    // Algunos dispositivos devuelven null; intentar reconstruir la Uri
                    savedUri = ImageStore.getImageContentUri(CameraActivity.this, filename);
                }

                // Aplicar filtro (si corresponde) reescribiendo la imagen guardada
                if (currentFilter != 0 && savedUri != null) {
                    try {
                        Bitmap bmp = MediaStore.Images.Media.getBitmap(getContentResolver(), savedUri);
                        Bitmap filtered = currentFilter == 1 ? com.david.smartcamerax.utils.Filters.toGrayscale(bmp) : com.david.smartcamerax.utils.Filters.toSepia(bmp);
                        try (OutputStream out = getContentResolver().openOutputStream(savedUri)) {
                            if (out != null) filtered.compress(Bitmap.CompressFormat.JPEG, 90, out);
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Error applying filter", e);
                    }
                }

                // Feedback al usuario
                Snackbar.make(previewView, "Foto guardada", Snackbar.LENGTH_SHORT).show();
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Photo capture failed: " + exception.getMessage(), exception);
                Snackbar.make(previewView, "Error al guardar foto", Snackbar.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * onDestroy()
     * <p>
     * Limpiamos el executor y cerramos el SmartAnalyzer si sigue activo. También cancelamos callbacks
     * pendientes del handler para evitar leaks de memoria.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // remover listener para evitar fugas
        try {
            if (previewView != null && previewView.getViewTreeObserver() != null && layoutListener != null) {
                previewView.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
            }
        } catch (Exception e) {
            Log.w(TAG, "onDestroy: error removiendo layoutListener", e);
        }

        // Asegurar detener preview filtrado
        stopFilterPreview();
        // Limpiar executor
        try {
            cameraExecutor.shutdown();
        } catch (Exception e) {
            Log.w(TAG, "onDestroy: error cerrando cameraExecutor", e);
        }
        // Cerrar SmartAnalyzer si está activo
        if (smartAnalyzer != null) {
            try {
                smartAnalyzer.close();
            } catch (Exception e) {
                Log.w(TAG, "onDestroy: error cerrando SmartAnalyzer", e);
            }
        }
    }

    /**
     * Ajusta dinámicamente la posición de los overlays (tvResult, tvFilter) para que no queden
     * solapados por los FloatingActionButtons situados en la parte superior del preview.
     *
     * Estrategia:
     * - Calcular la coordenada Y máxima (bottom) entre los FABs superiores y desplazar los overlays
     *   para que su Y sea ese valor + un pequeño margen (8dp).
     * - Se utiliza setTranslationY para aplicar el desplazamiento en runtime sin editar el layout XML.
     */
    private void adjustOverlayPositions() {
        try {
            int maxBottom = 0;
            if (fabSwitch != null && fabSwitch.getVisibility() == View.VISIBLE) {
                maxBottom = Math.max(maxBottom, fabSwitch.getBottom());
            }
            if (fabFilter != null && fabFilter.getVisibility() == View.VISIBLE) {
                maxBottom = Math.max(maxBottom, fabFilter.getBottom());
            }
            if (fabSmart != null && fabSmart.getVisibility() == View.VISIBLE) {
                maxBottom = Math.max(maxBottom, fabSmart.getBottom());
            }

            // Añadir margen en dp
            int margin = dpToPx(8);
            float translation = (float) (maxBottom + margin);

            if (tvResult != null) {
                // Aplicamos translationY solo si es mayor que la posición actual para evitar movimientos hacia arriba extraños
                tvResult.setTranslationY(translation);
            }
            if (tvFilter != null) {
                tvFilter.setTranslationY(translation);
            }
            // También ajustar overlay del filtro para que no se mueva
            if (ivFilterOverlay != null) {
                ivFilterOverlay.setTranslationY(translation);
            }
        } catch (Exception e) {
            Log.w(TAG, "adjustOverlayPositions: fallo al ajustar overlays", e);
        }
    }

    /**
     * Convierte dp a píxeles.
     */
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void stopFilterPreview() {
        filterRunning = false;
        try {
            mainHandler.removeCallbacks(filterLoopRunnable);
            if (ivFilterOverlay != null) ivFilterOverlay.setImageBitmap(null);
            if (ivFilterOverlay != null) ivFilterOverlay.setVisibility(View.GONE);
        } catch (Exception e) {
            Log.w(TAG, "stopFilterPreview error", e);
        }
    }

    private void startFilterPreview() {
        if (ivFilterOverlay == null) return;
        filterRunning = true;
        ivFilterOverlay.setVisibility(View.VISIBLE);
        // arrancar loop en UI handler (captura del bitmap debe hacerse en UI thread)
        mainHandler.removeCallbacks(filterLoopRunnable);
        mainHandler.post(filterLoopRunnable);
    }
}
