package com.example.rumiologia.asistente.ia;

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Clave que el propio usuario ingresó en Ajustes, cifrada en el dispositivo con
 * {@link AlmacenClaves}. Si no hay ninguna guardada, devuelve {@code null}: quien la
 * use debe avisar al usuario que falta configurar su clave, no reventar.
 */
public class ClaveUsuario implements ProveedorClave {

    private final Context contexto;

    public ClaveUsuario(@NonNull Context contexto) {
        this.contexto = contexto.getApplicationContext();
    }

    @Override
    public String obtener() {
        return AlmacenClaves.leerLocal(contexto);
    }
}
