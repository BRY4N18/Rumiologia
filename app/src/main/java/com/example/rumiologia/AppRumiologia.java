package com.example.rumiologia;

import android.app.Application;

import com.example.rumiologia.diagnostico.RegistroDeFallos;

/**
 * Punto de arranque de la aplicacion.
 *
 * <p>Su unica tarea es instalar el registro de fallos antes de que exista ninguna
 * pantalla, para que capture tambien los errores que ocurran durante el arranque.
 */
public class AppRumiologia extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        RegistroDeFallos.instalar(this);
    }
}
