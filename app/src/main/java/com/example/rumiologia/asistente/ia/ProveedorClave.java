package com.example.rumiologia.asistente.ia;

/**
 * De dónde sale la clave de la API.
 *
 * <p>Devuelve {@code null} si no hay clave disponible: quien la use debe avisar al
 * usuario, no reventar.
 */
public interface ProveedorClave {
    String obtener();
}
