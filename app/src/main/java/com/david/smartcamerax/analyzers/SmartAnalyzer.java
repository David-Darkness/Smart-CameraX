package com.david.smartcamerax.analyzers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SmartAnalyzer
 * <p>
 * ImageAnalysis.Analyzer que ejecuta dos detectores de ML Kit en paralelo:
 * - Reconocimiento de texto (TextRecognizer)
 * - Escaneo de códigos de barras (BarcodeScanner)
 *
 * Diseño y decisiones importantes:
 * - Reusa instancias de TextRecognizer y BarcodeScanner para evitar recrearlas en cada frame
 *   (coste elevado y posibles fugas si no se cierran).
 * - Usa un AtomicBoolean (isProcessing) para evitar procesar más de un frame a la vez. Si llega
 *   otro frame mientras está procesando, se descarta (mejor que acumular cola y subir latencia).
 * - Llama a listener.onResult(...) en el hilo principal usando un Handler.
 * - Implementa Closeable: es responsabilidad de quien instancie este analizador llamar close()
 *   cuando deje de usarse (por ejemplo al desactivar smartMode o en onDestroy de la Activity).
 */
public class SmartAnalyzer implements ImageAnalysis.Analyzer, Closeable {

    public interface Listener {
        void onResult(String result);
    }

    private final Listener listener;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Reusar reconocedores para mejorar rendimiento
    private final TextRecognizer textRecognizer;
    private final BarcodeScanner barcodeScanner;

    /**
     * Constructor: crea los clientes de ML Kit una sola vez.
     * @param listener callback que recibe el texto/QR extraído (puede ser null)
     */
    public SmartAnalyzer(Listener listener) {
        this.listener = listener;
        this.textRecognizer = TextRecognition.getClient(new TextRecognizerOptions.Builder().build());
        this.barcodeScanner = BarcodeScanning.getClient();
    }

    /**
     * analyze()
     * <p>
     * Se ejecuta por CameraX en un thread del executor que se haya configurado.
     * - Si la imagen interna es null, cierra el imageProxy y retorna.
     * - Si ya se está procesando un frame, cierra el imageProxy y retorna (evita solapamiento).
     * - Lanza las tareas de ML Kit y, cuando ambas se completan, construye un String resultado
     *   con texto y/o valores de códigos y pasa el resultado al listener en el hilo principal.
     */
    @Override
    @ExperimentalGetImage
    public void analyze(@NonNull ImageProxy imageProxy) {
        // imageProxy no puede ser null según la firma, comprobar solo la imagen interna
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        // Evitar solapamiento de procesos: si ya estamos procesando, descartamos este frame.
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        // Crear tareas para texto y códigos de barras usando instancias reusables
        Task<Text> textTask = textRecognizer.process(image);
        Task<List<Barcode>> barcodeTask = barcodeScanner.process(image);

        // Esperar a que ambas tareas terminen (completadas) y luego procesar resultados y cerrar imageProxy
        Tasks.whenAllComplete(textTask, barcodeTask)
                .addOnSuccessListener(ignored -> {
                    StringBuilder sb = new StringBuilder();

                    if (textTask.isSuccessful()) {
                        try {
                            Text text = textTask.getResult();
                            if (text != null) {
                                String t = text.getText();
                                if (!t.trim().isEmpty()) {
                                    sb.append("TEXTO: ").append(t.trim()).append("\n");
                                }
                            }
                        } catch (Exception e) {
                            Log.w("SmartAnalyzer", "Error obteniendo texto", e);
                        }
                    }

                    if (barcodeTask.isSuccessful()) {
                        try {
                            List<Barcode> barcodes = barcodeTask.getResult();
                            if (barcodes != null && !barcodes.isEmpty()) {
                                List<String> vals = new ArrayList<>();
                                for (Barcode b : barcodes) {
                                    String raw = b.getRawValue();
                                    if (raw != null && !raw.isEmpty()) {
                                        vals.add(raw);
                                    }
                                }
                                if (!vals.isEmpty()) {
                                    sb.append("QR: ").append(String.join(" ", vals));
                                }
                            }
                        } catch (Exception e) {
                            Log.w("SmartAnalyzer", "Error procesando códigos de barras", e);
                        }
                    }

                    String finalResult = sb.length() > 0 ? sb.toString().trim() : null;
                    if (finalResult != null && !finalResult.isEmpty()) {
                        // Asegurar llamada en hilo principal
                        mainHandler.post(() -> listener.onResult(finalResult));
                    }
                })
                .addOnFailureListener(e -> Log.w("SmartAnalyzer", "Tarea falló", e))
                .addOnCompleteListener(task -> {
                    try {
                        imageProxy.close();
                    } catch (Exception e) {
                        Log.w("SmartAnalyzer", "Error cerrando imageProxy", e);
                    } finally {
                        // Restablecer flag para permitir procesar próximos frames
                        isProcessing.set(false);
                    }
                });
    }

    /**
     * close()
     * <p>
     * Cierra los reconocedores de ML Kit. Debe llamarse desde el lifecycle owning (e.g. Activity.onDestroy).
     */
    @Override
    public void close() {
        try {
            textRecognizer.close();
        } catch (Exception e) {
            Log.w("SmartAnalyzer", "Error cerrando textRecognizer", e);
        }
        try {
            barcodeScanner.close();
        } catch (Exception e) {
            Log.w("SmartAnalyzer", "Error cerrando barcodeScanner", e);
        }
    }
}
