package com.example.rumiologia;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Primera pantalla de la app: logo, un botón para entrar a la cámara y los
 * créditos del equipo. Ahora es la actividad de lanzamiento; {@link MainActivity}
 * se abre desde aquí.
 */
public class BienvenidaActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bienvenida);

        findViewById(R.id.bienvenidaBoton).setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
    }
}
