package com.david.smartcamerax;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialButton btnOpen = findViewById(R.id.btn_open_camera);
        btnOpen.setOnClickListener(v -> startActivity(new Intent(this, CameraActivity.class)));

        //botón para abrir la galería de imágenes guardadas por la app
        MaterialButton btnGallery = findViewById(R.id.btn_open_gallery);
        btnGallery.setOnClickListener(v -> startActivity(new Intent(this, GalleryActivity.class)));

        // botón para salir de la aplicación
        MaterialButton btnExit = findViewById(R.id.btn_exit);
        btnExit.setOnClickListener(v -> {
            finishAffinity();
            // Opcional: asegurar que el proceso termina en caso extremo
            System.exit(0);
        });
    }
}