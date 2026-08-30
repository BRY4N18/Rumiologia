package com.example.rumiologia.asistente.ia;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Guarda la clave de Gemini que el propio usuario ingresa en Ajustes, cifrada con
 * {@link CifradorClave}. Nunca se guarda en texto plano.
 *
 * <p>Es el único sitio del proyecto que sabe dónde vive la clave en disco; tanto la
 * pantalla de Ajustes como {@link ClaveUsuario} pasan por aquí.
 */
public final class AlmacenClaves {

    private static final String TAG = "AlmacenClaves";
    private static final String ARCHIVO = "ajustes_clave";
    private static final String CLAVE_CIFRADO = "clave_cifrada";
    private static final String CLAVE_IV = "clave_iv";

    private AlmacenClaves() {
    }

    public static void guardarLocal(@NonNull Context contexto, @NonNull String claveTexto) {
        try {
            CifradorClave.Resultado resultado = new CifradorClave().cifrar(claveTexto);
            preferencias(contexto).edit()
                    .putString(CLAVE_CIFRADO, resultado.cifradoBase64)
                    .putString(CLAVE_IV, resultado.ivBase64)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "No se pudo cifrar la clave", e);
        }
    }

    @Nullable
    public static String leerLocal(@NonNull Context contexto) {
        SharedPreferences prefs = preferencias(contexto);
        String cifrado = prefs.getString(CLAVE_CIFRADO, null);
        String iv = prefs.getString(CLAVE_IV, null);
        if (TextUtils.isEmpty(cifrado) || TextUtils.isEmpty(iv)) {
            return null;
        }
        try {
            return new CifradorClave().descifrar(cifrado, iv);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo descifrar la clave guardada", e);
            return null;
        }
    }

    public static boolean tieneClaveLocal(@NonNull Context contexto) {
        SharedPreferences prefs = preferencias(contexto);
        return !TextUtils.isEmpty(prefs.getString(CLAVE_CIFRADO, null))
                && !TextUtils.isEmpty(prefs.getString(CLAVE_IV, null));
    }

    public static void borrarLocal(@NonNull Context contexto) {
        preferencias(contexto).edit().clear().apply();
    }

    /** Hay una clave guardada con la que se pueda hablar con Gemini ahora mismo. */
    public static boolean hayClaveDisponible(@NonNull Context contexto) {
        return !TextUtils.isEmpty(new ClaveUsuario(contexto).obtener());
    }

    private static SharedPreferences preferencias(Context contexto) {
        return contexto.getApplicationContext()
                .getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE);
    }
}
