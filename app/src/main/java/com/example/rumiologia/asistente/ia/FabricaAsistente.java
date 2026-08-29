package com.example.rumiologia.asistente.ia;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.rumiologia.asistente.ia.gemini.AsistenteGemini;

/**
 * Único punto donde se decide qué implementación de {@link AsistenteIA} se usa.
 *
 * <p>Gracias a esto, {@code ChatActivity} no menciona a Gemini en ninguna línea:
 * pide un asistente y recibe uno. Cambiar de proveedor, o alternar entre varios
 * según configuración, se resuelve aquí sin tocar la pantalla.
 *
 * <p>Lo mismo con la clave: hoy viene compilada desde {@code local.properties};
 * cuando se implemente la pantalla de configuración o Supabase, será otra
 * implementación de {@link ProveedorClave} en esta misma línea.
 */
public final class FabricaAsistente {

    private static AsistenteIA instancia;

    private FabricaAsistente() {
    }

    public static synchronized AsistenteIA crear(@NonNull Context contexto) {
        if (instancia == null) {
            ProveedorClave clave = new ClaveCompilada();
            instancia = new AsistenteGemini(contexto, clave);
        }
        return instancia;
    }
}
