package com.example.rumiologia.asistente.ia;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.rumiologia.Equipos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decide sobre qué fichas debe buscar el asistente en cada pregunta.
 *
 * <p>Esta clase solo se ocupa de leer los equipos de {@code clases.json} y pasárselos
 * a {@link ReglaDeAlcance}, que es quien tiene la lógica. La división permite probar
 * la regla con tests de JVM, sin emulador ni assets.
 *
 * <p><b>La política actual:</b> si el usuario llegó desde una detección, la búsqueda
 * se acota a ese equipo. Resuelve un problema medido: el laboratorio tiene dos
 * estufas (ANKOM de secado y MEMMERT universal) y sin filtrar, la pregunta "¿a qué
 * temperatura trabaja la estufa?" devuelve una respuesta que mezcla 102 °C y 300 °C.
 *
 * <p><b>La excepción:</b> si la pregunta nombra otro equipo, el filtro se levanta.
 * Sin eso, alguien frente a la balanza que pregunte por la incubadora recibiría "esa
 * información no está en las fichas" — cuando sí está, pero la habríamos excluido.
 */
public class AlcanceConsulta {

    private final Context contexto;
    private ReglaDeAlcance regla;

    public AlcanceConsulta(@NonNull Context contexto) {
        this.contexto = contexto.getApplicationContext();
    }

    /**
     * @param equipoDetectado slug del equipo que el usuario tenía en cámara, o null
     * @param pregunta        texto de la pregunta
     * @return slug por el que filtrar, o {@code null} para buscar en todas las fichas
     */
    @Nullable
    public String equipoParaFiltrar(@Nullable String equipoDetectado, String pregunta) {
        return regla().equipoParaFiltrar(equipoDetectado, pregunta);
    }

    /** Se construye una vez: las clases no cambian mientras la app vive. */
    private synchronized ReglaDeAlcance regla() {
        if (regla == null) {
            Map<String, String> equipos = new LinkedHashMap<>();
            for (String slug : Equipos.slugs(contexto)) {
                equipos.put(slug, Equipos.nombreDe(contexto, slug));
            }
            regla = new ReglaDeAlcance(equipos);
        }
        return regla;
    }
}
