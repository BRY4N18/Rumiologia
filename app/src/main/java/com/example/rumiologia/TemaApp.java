package com.example.rumiologia;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Modo claro/oscuro/sistema, elegido a mano en Ajustes. Se guarda aparte de
 * {@code AppCompatDelegate.setDefaultNightMode}, que solo vive en memoria: sin
 * esto, la app volvería a "seguir el sistema" cada vez que el proceso muere.
 */
public class TemaApp {

    private static final String ARCHIVO = "ajustes_tema";
    private static final String CLAVE_MODO = "modo_nocturno";

    private TemaApp() {
    }

    public static void guardar(Context contexto, int modo) {
        preferencias(contexto).edit().putInt(CLAVE_MODO, modo).apply();
        AppCompatDelegate.setDefaultNightMode(modo);
    }

    public static int leer(Context contexto) {
        return preferencias(contexto).getInt(CLAVE_MODO, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public static void aplicarGuardado(Context contexto) {
        AppCompatDelegate.setDefaultNightMode(leer(contexto));
    }

    private static SharedPreferences preferencias(Context contexto) {
        return contexto.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE);
    }
}
