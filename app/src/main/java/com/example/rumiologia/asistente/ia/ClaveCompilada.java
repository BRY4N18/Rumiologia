package com.example.rumiologia.asistente.ia;

import android.text.TextUtils;

import com.example.rumiologia.BuildConfig;

/**
 * Clave leída de {@code local.properties} en tiempo de compilación.
 *
 * <p>Gradle la inyecta en {@code BuildConfig}. Es cómodo para desarrollar y mantiene
 * la clave fuera del repositorio, pero <b>no la protege</b>: sigue estando dentro del
 * APK y es extraíble descomprimiéndolo.
 *
 * <p>Decisión consciente y temporal. Ver la sección de decisiones futuras en
 * {@code documentacion/DecisionesMovil.md}.
 */
public class ClaveCompilada implements ProveedorClave {

    @Override
    public String obtener() {
        String clave = BuildConfig.GEMINI_API_KEY;
        return TextUtils.isEmpty(clave) ? null : clave;
    }
}
