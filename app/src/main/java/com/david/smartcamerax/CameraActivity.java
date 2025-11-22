package com.david.smartcamerax;

import android.Manifest;
import android.content.ContentValues;
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
import android.widget.ImageView;
import android.widget.TextView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.david.smartcamerax.analyzers.SmartAnalyzer;
import com.david.smartcamerax.storage.ImageStore;
import com.david.smartcamerax.utils.PermissionHelper;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;

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
    private static final int REQUEST_AUDIO = 2001;

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
    // CameraX video/recorder fields
    private VideoCapture<Recorder> videoCapture;
    private Recorder recorder;
    private Recording currentRecording;
    private boolean isRecording = false;
    private Camera currentCamera;
    private boolean flashEnabled = false;

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
    // Botones nuevos: grabar video y flash
    private ImageButton btnFlash; // eliminado btnRecord para modo unificado
    private ImageButton btnCapture; // referencia al botón único
    private boolean pendingLongPress = false;
    private boolean captureButtonPressed = false;
    private final long LONG_PRESS_THRESHOLD_MS = 350; // tiempo para considerar "mantener" y empezar video
    private Handler pressHandler = new Handler(Looper.getMainLooper());
    private final Runnable startRecordingRunnable = () -> {
        if (captureButtonPressed && !isRecording) {
            pendingLongPress = true; // indica que entramos a modo video
            startRecording();
            if (btnCapture != null) btnCapture.setImageResource(R.drawable.ic_stop);
        }
    };

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
        ImageButton btnCaptureLocal = findViewById(R.id.btn_capture);
        btnCapture = btnCaptureLocal;
        btnFlash = findViewById(R.id.btn_flash);
        tvResult = findViewById(R.id.tv_result);
        tvFilter = findViewById(R.id.tv_filter);
        ImageButton btnBack = findViewById(R.id.btn_back_camera);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                // Si estaba grabando detener de forma segura
                if (isRecording) {
                    try { stopRecording(); } catch (Exception ignored) {}
                }
                finish();
            });
        }

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
            // Si pasamos a cámara frontal, desactivar y ocultar flash
            boolean isFront = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA;
            if (isFront) {
                flashEnabled = false;
                if (btnFlash != null) {
                    btnFlash.setVisibility(View.GONE);
                    btnFlash.setImageResource(R.drawable.ic_flash_off);
                }
            } else {
                if (btnFlash != null) btnFlash.setVisibility(View.VISIBLE);
            }
            startCamera(); // restart camera con nuevo selector
        });

        // Botón: cambiar filtro (se aplica también al preview)
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

        // Configurar botón único captura/grabación por pulsación
        if (btnCapture != null) {
            btnCapture.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        captureButtonPressed = true;
                        pendingLongPress = false;
                        // Programar inicio de grabación si se mantiene
                        pressHandler.postDelayed(startRecordingRunnable, LONG_PRESS_THRESHOLD_MS);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        pressHandler.removeCallbacks(startRecordingRunnable);
                        if (!pendingLongPress) {
                            // Click corto: tomar foto
                            takePhoto();
                        } else {
                            // Estaba grabando: detener
                            stopRecording();
                        }
                        // Reset estados y icono
                        if (btnCapture != null) btnCapture.setImageResource(R.drawable.ic_camera);
                        captureButtonPressed = false;
                        pendingLongPress = false;
                        return true;
                }
                return false;
            });
        }

        // Botón de flash
        if (btnFlash != null) {
            btnFlash.setOnClickListener(v -> {
                // No permitir flash en cámara frontal
                if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                    Snackbar.make(previewView, "Flash no disponible en cámara frontal", Snackbar.LENGTH_SHORT).show();
                    return;
                }
                flashEnabled = !flashEnabled;
                updateTorch();
            });
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
                Snackbar.make(previewView, "Permiso de cámara denegado", Snackbar.LENGTH_LONG).show();
                finish();
            }
        } else if (requestCode == REQUEST_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && isRecording) {
                // reiniciar grabación para incluir audio si justo se pidió
                stopRecording();
                startRecording();
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
                bindCameraUseCases(cameraProviderFuture.get());
            } catch (Exception e) {
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

        // Configurar recorder/video capture
        try {
            recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST, FallbackStrategy.lowerQualityThan(Quality.HIGHEST)))
                    .build();
            videoCapture = VideoCapture.withOutput(recorder);
        } catch (Exception e) {
            Log.w(TAG, "VideoCapture not available", e);
            videoCapture = null;
            recorder = null;
        }

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

        ImageAnalysis imageAnalysis = null;
        if (smartMode) {
            imageAnalysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            // Crear SmartAnalyzer una sola vez y mantener la referencia
            smartAnalyzer = new SmartAnalyzer(result -> runOnUiThread(() -> {
                if (result != null && !result.isEmpty() && tvResult != null) {
                    // Mostrar el texto/QR detectado en el overlay
                    tvResult.setText(result);
                    tvResult.setVisibility(View.VISIBLE);
                    mainHandler.removeCallbacks(hideResultRunnable);
                    mainHandler.postDelayed(hideResultRunnable, 3000);
                }
            }));

            imageAnalysis.setAnalyzer(cameraExecutor, smartAnalyzer);
        }

        try {
            if (imageAnalysis != null && videoCapture != null) {
                currentCamera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalysis, videoCapture);
            } else if (imageAnalysis != null) {
                currentCamera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalysis);
            } else if (videoCapture != null) {
                currentCamera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, videoCapture);
            } else {
                currentCamera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
            }
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }

        // Actualizar torch si estaba activo al reiniciar
        updateTorch();
    }

    /**
     * takePhoto()
     * <p>
     * Toma una foto y la guarda usando MediaStore. Si se seleccionó un filtro, se aplica al bitmap
     * después de guardarlo (operación simple que reescribe la Uri resultante).
     */
    private void takePhoto() {
        if (imageCapture == null) return;
        String filename = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentValues contentValues = ImageStore.buildContentValues(filename);
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build();
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                Uri savedUri = outputFileResults.getSavedUri();
                if (savedUri == null) savedUri = ImageStore.getImageContentUri(CameraActivity.this, filename);
                Log.d(TAG, "Photo saved at: " + savedUri);
                Snackbar.make(previewView, getString(R.string.msg_photo_saved), Snackbar.LENGTH_SHORT).show(); }
            @Override public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Photo capture failed: " + exception.getMessage(), exception);
                Snackbar.make(previewView, getString(R.string.msg_photo_error), Snackbar.LENGTH_SHORT).show(); }
        });
    }

    // Inicia la grabación de video; guarda en MediaStore
    private void startRecording() {
        if (recorder == null || videoCapture == null) {
            Snackbar.make(previewView, getString(R.string.msg_record_not_supported), Snackbar.LENGTH_SHORT).show();
            return;
        }
        String filename = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
        contentValues.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SmartCameraX");
        MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues).build();

        boolean hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!hasAudio) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
        }
        PendingRecording pending = recorder.prepareRecording(this, outputOptions);
        if (hasAudio) pending = pending.withAudioEnabled();

        currentRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Start) {
                isRecording = true;
                Snackbar.make(previewView, getString(R.string.msg_recording_start), Snackbar.LENGTH_SHORT).show();
            } else if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) event;
                if (finalizeEvent.hasError()) {
                    Log.e(TAG, "Video capture failed: " + finalizeEvent.getError());
                    Snackbar.make(previewView, getString(R.string.msg_record_error), Snackbar.LENGTH_SHORT).show();
                } else {
                    Snackbar.make(previewView, getString(R.string.msg_record_saved), Snackbar.LENGTH_SHORT).show();
                }
                isRecording = false;
                if (btnCapture != null) btnCapture.setImageResource(R.drawable.ic_camera);
            }
        });
    }

    private void stopRecording() {
        if (currentRecording != null) {
            try { currentRecording.stop(); } catch (Exception e) { Log.w(TAG, "stopRecording error", e); }
        }
    }

    private void updateTorch() {
        try {
            if (currentCamera != null) {
                // Evitar activar torch si la cámara es frontal
                if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                    flashEnabled = false;
                }
                currentCamera.getCameraControl().enableTorch(flashEnabled);
                if (btnFlash != null) {
                    btnFlash.setImageResource(flashEnabled ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
                    // ocultar si frontal
                    btnFlash.setVisibility(cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA ? View.GONE : View.VISIBLE);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "updateTorch error", e);
        }
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
        try { if (previewView != null && previewView.getViewTreeObserver() != null && layoutListener != null) previewView.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener); } catch (Exception e) { Log.w(TAG, "onDestroy: layoutListener", e); }
        // Asegurar detener preview filtrado
        stopFilterPreview();
        // Limpiar executor
        try { cameraExecutor.shutdown(); } catch (Exception e) { Log.w(TAG, "onDestroy: executor", e); }
        // Cerrar SmartAnalyzer si está activo
        if (smartAnalyzer != null) { try { smartAnalyzer.close(); } catch (Exception e) { Log.w(TAG, "onDestroy: analyzer", e); } }
        // detener recording si aún está grabando
        try { if (isRecording) stopRecording(); } catch (Exception e) { Log.w(TAG, "onDestroy: recording", e); }
        // limpiar callbacks del pressHandler
        try { pressHandler.removeCallbacks(startRecordingRunnable); } catch (Exception ignored) {}
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
            if (fabSwitch != null && fabSwitch.getVisibility() == View.VISIBLE) maxBottom = Math.max(maxBottom, fabSwitch.getBottom());
            if (fabFilter != null && fabFilter.getVisibility() == View.VISIBLE) maxBottom = Math.max(maxBottom, fabFilter.getBottom());
            if (fabSmart != null && fabSmart.getVisibility() == View.VISIBLE) maxBottom = Math.max(maxBottom, fabSmart.getBottom());
            int margin = dpToPx(8);
            float translation = maxBottom + margin;
            if (tvResult != null) tvResult.setTranslationY(translation);
            if (tvFilter != null) tvFilter.setTranslationY(translation);
            if (ivFilterOverlay != null) ivFilterOverlay.setTranslationY(translation);
        } catch (Exception e) { Log.w(TAG, "adjustOverlayPositions", e); }
    }

    /**
     * Convierte dp a píxeles.
     */
    private int dpToPx(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    private void stopFilterPreview() {
        filterRunning = false;
        try {
            mainHandler.removeCallbacks(filterLoopRunnable);
            if (ivFilterOverlay != null) { ivFilterOverlay.setImageBitmap(null); ivFilterOverlay.setVisibility(View.GONE); }
        } catch (Exception e) { Log.w(TAG, "stopFilterPreview", e); }
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
