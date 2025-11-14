package com.david.smartcamerax.analyzers;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

public class TextAnalyzer implements ImageAnalysis.Analyzer {

    public interface Listener {
        void onTextDetected(String text);
    }

    private final Listener listener;

    public TextAnalyzer(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        // Stub simple: cerramos el imageProxy inmediatamente. Use SmartAnalyzer para ML Kit.
        if (imageProxy != null) imageProxy.close();
    }
}
