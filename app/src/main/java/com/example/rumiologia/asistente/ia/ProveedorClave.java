package com.example.rumiologia.asistente.ia;

/**
 * De dónde sale la clave de la API.
 *
 * <p>Es una interfaz de una sola función a propósito. Hoy la clave se compila con la
 * app; está previsto moverla a Supabase o pedírsela al usuario en una pantalla de
 * configuración. Cada una de esas opciones será otra implementación de esto, y el
 * resto del código no cambiará.
 *
 * <p>Devuelve {@code null} si no hay clave disponible: quien la use debe avisar al
 * usuario, no reventar.
 */
public interface ProveedorClave {
    String obtener();
}
