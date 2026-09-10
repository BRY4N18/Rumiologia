package com.example.rumiologia.asistente.ia;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.rumiologia.asistente.ia.openai.AsistenteOpenAI;

/**
 * Único punto donde se decide qué implementación de {@link AsistenteIA} se usa.
 *
 * <p>Gracias a esto, {@code ChatActivity} no menciona al proveedor de IA en ninguna línea:
 * pide un asistente y recibe uno. Cambiar de proveedor, o alternar entre varios
 * según configuración, se resuelve aquí sin tocar la pantalla.
 *
 * <p>La clave sale de {@link ClaveUsuario}: la que la persona ingresó en Ajustes.
 */
public final class FabricaAsistente {

    private static AsistenteIA instancia;

    private FabricaAsistente() {
    }

    public static synchronized AsistenteIA crear(@NonNull Context contexto) {
        if (instancia == null) {
            Context aplicacion = contexto.getApplicationContext();
            ProveedorClave clave = new ClaveUsuario(aplicacion);
            instancia = new AsistenteOpenAI(aplicacion, clave);
        }
        return instancia;
    }
}
