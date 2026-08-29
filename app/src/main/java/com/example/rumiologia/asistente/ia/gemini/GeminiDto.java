package com.example.rumiologia.asistente.ia.gemini;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Las clases que Gson convierte a y desde el JSON de Gemini.
 *
 * <p>Están todas anidadas aquí y no repartidas en ficheros sueltos porque no son
 * lógica: son la forma del JSON. Verlas juntas permite comparar de un vistazo con la
 * petición que se envía. Fuera de este paquete nadie las usa —
 * {@code AsistenteGemini} las traduce a los tipos del dominio.
 *
 * <p>Los nombres de campo respetan los de la API ({@code systemInstruction},
 * {@code fileSearchStoreNames}...) para que la correspondencia sea directa.
 */
public final class GeminiDto {

    private GeminiDto() {
    }

    // ------------------------------------------------------------------ petición

    public static class Peticion {
        public List<Contenido> contents = new ArrayList<>();

        @SerializedName("systemInstruction")
        public Contenido systemInstruction;

        public List<Herramienta> tools = new ArrayList<>();

        @SerializedName("generationConfig")
        public Configuracion generationConfig = new Configuracion();
    }

    public static class Contenido {
        /** "user" o "model". Las respuestas previas del asistente van como "model". */
        public String role;
        public List<Parte> parts = new ArrayList<>();

        public static Contenido de(String rol, String texto) {
            Contenido c = new Contenido();
            c.role = rol;
            c.parts.add(new Parte(texto));
            return c;
        }

        /** La instrucción del sistema no lleva rol: es una excepción de la API. */
        public static Contenido sistema(String texto) {
            Contenido c = new Contenido();
            c.parts.add(new Parte(texto));
            return c;
        }
    }

    public static class Parte {
        public String text;

        public Parte(String text) {
            this.text = text;
        }
    }

    public static class Herramienta {
        @SerializedName("file_search")
        public BusquedaArchivos fileSearch;

        public static Herramienta fileSearch(String almacen, String filtroMetadatos) {
            Herramienta h = new Herramienta();
            h.fileSearch = new BusquedaArchivos();
            h.fileSearch.fileSearchStoreNames = Collections.singletonList(almacen);
            h.fileSearch.metadataFilter = filtroMetadatos;   // null = sin filtro
            return h;
        }
    }

    public static class BusquedaArchivos {
        @SerializedName("file_search_store_names")
        public List<String> fileSearchStoreNames;

        /**
         * Filtro por metadatos, con la forma {@code equipo="ankom_estufa"}.
         * Si es null, Gson lo omite y la búsqueda abarca todas las fichas.
         */
        @SerializedName("metadata_filter")
        public String metadataFilter;
    }

    public static class Configuracion {
        /** Baja a propósito: queremos fidelidad a la ficha, no redacción creativa. */
        public float temperature = 0.2f;
    }

    // ------------------------------------------------------------------ respuesta

    public static class Respuesta {
        public List<Candidato> candidates;

        /** Presente cuando la API rechaza la petición; trae el motivo real. */
        public Error error;

        public String primerTexto() {
            if (candidates == null || candidates.isEmpty()) {
                return "";
            }
            Candidato c = candidates.get(0);
            if (c.content == null || c.content.parts == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Parte p : c.content.parts) {
                if (p.text != null) {
                    sb.append(p.text);
                }
            }
            return sb.toString().trim();
        }

        /**
         * Fichas citadas por el modelo.
         *
         * <p>Es lo que distingue una respuesta verificable de una afirmación suelta:
         * el usuario puede comprobar de qué documento salió el dato.
         */
        public List<String> fuentes() {
            List<String> fuentes = new ArrayList<>();
            if (candidates == null) {
                return fuentes;
            }
            for (Candidato c : candidates) {
                if (c.groundingMetadata == null || c.groundingMetadata.groundingChunks == null) {
                    continue;
                }
                for (Fragmento f : c.groundingMetadata.groundingChunks) {
                    if (f.retrievedContext != null && f.retrievedContext.title != null
                            && !fuentes.contains(f.retrievedContext.title)) {
                        fuentes.add(f.retrievedContext.title);
                    }
                }
            }
            return fuentes;
        }
    }

    public static class Candidato {
        public Contenido content;

        @SerializedName("finishReason")
        public String finishReason;

        @SerializedName("groundingMetadata")
        public MetadatosGrounding groundingMetadata;
    }

    public static class MetadatosGrounding {
        @SerializedName("groundingChunks")
        public List<Fragmento> groundingChunks;
    }

    public static class Fragmento {
        @SerializedName("retrievedContext")
        public ContextoRecuperado retrievedContext;
    }

    public static class ContextoRecuperado {
        /** Nombre del documento en el almacén: coincide con el slug del equipo. */
        public String title;
    }

    public static class Error {
        public int code;
        public String message;
        public String status;
    }
}
