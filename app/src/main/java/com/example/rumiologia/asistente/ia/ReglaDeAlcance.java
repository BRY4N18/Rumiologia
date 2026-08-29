package com.example.rumiologia.asistente.ia;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * La regla que decide si una pregunta habla de un equipo distinto al que se ve.
 *
 * <p>Se separó de {@link AlcanceConsulta} para que no dependa de Android: aquí no
 * hay {@code Context} ni assets, solo texto. Eso permite probarla con tests de JVM
 * corrientes, que es justo lo que necesita una regla con casos límite como esta.
 */
public final class ReglaDeAlcance {

    /** Palabras más cortas aparecerían en cualquier frase ("de", "200"). */
    private static final int LONGITUD_MINIMA = 4;

    private final Map<String, List<String>> palabrasPorEquipo;

    /**
     * @param equipos slug → nombre visible, tal como están en clases.json
     */
    public ReglaDeAlcance(Map<String, String> equipos) {
        this.palabrasPorEquipo = calcularDiscriminantes(equipos);
    }

    /**
     * Decide el alcance de la búsqueda.
     *
     * @return el slug por el que filtrar, o {@code null} para buscar en todas las fichas
     */
    public String equipoParaFiltrar(String equipoDetectado, String pregunta) {
        if (equipoDetectado == null || equipoDetectado.isEmpty()) {
            return null;                        // chat general: sin filtro
        }
        return equipoMencionado(pregunta, equipoDetectado) != null ? null : equipoDetectado;
    }

    /** Slug del otro equipo que menciona la pregunta, o null si no menciona ninguno. */
    public String equipoMencionado(String pregunta, String equipoActual) {
        String texto = normalizar(pregunta);
        for (Map.Entry<String, List<String>> entrada : palabrasPorEquipo.entrySet()) {
            if (entrada.getKey().equals(equipoActual)) {
                continue;
            }
            for (String palabra : entrada.getValue()) {
                if (texto.contains(palabra)) {
                    return entrada.getKey();
                }
            }
        }
        return null;
    }

    /** Palabras clave de un equipo, para inspección y pruebas. */
    public List<String> palabrasDe(String slug) {
        List<String> palabras = palabrasPorEquipo.get(slug);
        return palabras != null ? palabras : new ArrayList<String>();
    }

    /**
     * Se queda solo con las palabras que identifican a UN equipo.
     *
     * <p>Descartar las ambiguas es el detalle que hace que esto funcione. "ankom"
     * aparece en tres equipos y "estufa" en dos: aceptándolas, mirando la estufa
     * ANKOM y preguntando "¿a qué temperatura trabaja la estufa?", la palabra
     * "estufa" coincidiría con la MEMMERT, se levantaría el filtro y volverían a
     * mezclarse las dos estufas — justo lo que el filtro existe para evitar.
     *
     * <p>Tras el descarte, a {@code ankom_estufa} le queda "secado" y a
     * {@code memmert} le quedan "memmert" y "universal". Nombrar expresamente la
     * otra estufa sí levanta el filtro; hablar de "la estufa" en general, no.
     */
    private static Map<String, List<String>> calcularDiscriminantes(Map<String, String> equipos) {
        Map<String, List<String>> brutas = new LinkedHashMap<>();
        Map<String, Integer> veces = new HashMap<>();

        for (Map.Entry<String, String> equipo : equipos.entrySet()) {
            List<String> palabras = new ArrayList<>();
            String fuente = equipo.getKey().replace('_', ' ') + " " + normalizar(equipo.getValue());

            for (String palabra : fuente.split("\\s+")) {
                if (palabra.length() >= LONGITUD_MINIMA && !palabras.contains(palabra)) {
                    palabras.add(palabra);
                    Integer previas = veces.get(palabra);
                    veces.put(palabra, previas == null ? 1 : previas + 1);
                }
            }
            brutas.put(equipo.getKey(), palabras);
        }

        Map<String, List<String>> discriminantes = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : brutas.entrySet()) {
            List<String> unicas = new ArrayList<>();
            for (String palabra : e.getValue()) {
                if (veces.get(palabra) == 1) {
                    unicas.add(palabra);
                }
            }
            discriminantes.put(e.getKey(), unicas);
        }
        return discriminantes;
    }

    /** Minúsculas y sin tildes, para que "AQUASEARCHER" y "aquasearcher" coincidan. */
    static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return sinTildes.toLowerCase(Locale.ROOT);
    }
}
