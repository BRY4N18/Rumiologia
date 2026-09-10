package com.example.rumiologia.asistente.ia.openai;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

/**
 * Objetos de transferencia de datos (DTO) para OpenAI Responses API (/v1/responses).
 */
public final class OpenAiDto {

    private OpenAiDto() {}

    public static class Peticion {
        @SerializedName("model")
        public String model;

        @SerializedName("instructions")
        public String instructions;

        @SerializedName("input")
        public List<MensajeInput> input = new ArrayList<>();

        @SerializedName("tools")
        public List<Herramienta> tools = new ArrayList<>();

        @SerializedName("reasoning")
        public Reasoning reasoning;
    }

    public static class Reasoning {
        @SerializedName("effort")
        public String effort;

        public Reasoning(String effort) {
            this.effort = effort;
        }
    }

    public static class MensajeInput {
        @SerializedName("role")
        public String role;

        @SerializedName("content")
        public String content;

        public MensajeInput(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class Herramienta {
        @SerializedName("type")
        public String type;

        @SerializedName("vector_store_ids")
        public List<String> vectorStoreIds;

        public static Herramienta fileSearch(String vectorStoreId) {
            Herramienta h = new Herramienta();
            h.type = "file_search";
            h.vectorStoreIds = new ArrayList<>();
            h.vectorStoreIds.add(vectorStoreId);
            return h;
        }

        public static Herramienta webSearch() {
            Herramienta h = new Herramienta();
            h.type = "web_search";
            return h;
        }
    }

    public static class Respuesta {
        @SerializedName("id")
        public String id;

        @SerializedName("status")
        public String status;

        @SerializedName("output")
        public List<ItemSalida> output;

        @SerializedName("output_text")
        public String outputText;

        @SerializedName("error")
        public ErrorDetalle error;

        public String obtenerTexto() {
            if (outputText != null && !outputText.trim().isEmpty()) {
                return outputText.trim();
            }
            if (output == null) return "";
            StringBuilder sb = new StringBuilder();
            for (ItemSalida item : output) {
                if (item.content != null) {
                    for (ContenidoSalida c : item.content) {
                        if (c.text != null) {
                            sb.append(c.text);
                        }
                    }
                }
            }
            return sb.toString().trim();
        }

        public List<String> obtenerFuentes() {
            List<String> fuentes = new ArrayList<>();
            if (output == null) return fuentes;
            for (ItemSalida item : output) {
                if (item.content != null) {
                    for (ContenidoSalida c : item.content) {
                        if (c.annotations != null) {
                            for (Anotacion a : c.annotations) {
                                String fuente = a.obtenerNombreFuente();
                                if (fuente != null && !fuente.isEmpty() && !fuentes.contains(fuente)) {
                                    fuentes.add(fuente);
                                }
                            }
                        }
                    }
                }
            }
            return fuentes;
        }
    }

    public static class ItemSalida {
        @SerializedName("type")
        public String type;

        @SerializedName("role")
        public String role;

        @SerializedName("content")
        public List<ContenidoSalida> content;
    }

    public static class ContenidoSalida {
        @SerializedName("type")
        public String type;

        @SerializedName("text")
        public String text;

        @SerializedName("annotations")
        public List<Anotacion> annotations;
    }

    public static class Anotacion {
        @SerializedName("type")
        public String type;

        @SerializedName("filename")
        public String filename;

        @SerializedName("file_citation")
        public FileCitation fileCitation;

        public String obtenerNombreFuente() {
            if (filename != null && !filename.isEmpty()) {
                return limpiarNombre(filename);
            }
            if (fileCitation != null && fileCitation.filename != null) {
                return limpiarNombre(fileCitation.filename);
            }
            return null;
        }

        private String limpiarNombre(String nombre) {
            if (nombre.endsWith(".md")) {
                nombre = nombre.substring(0, nombre.length() - 3);
            }
            return nombre.replace("_", " ");
        }
    }

    public static class FileCitation {
        @SerializedName("filename")
        public String filename;

        @SerializedName("file_id")
        public String fileId;
    }

    public static class ErrorDetalle {
        @SerializedName("message")
        public String message;

        @SerializedName("type")
        public String type;

        @SerializedName("code")
        public String code;
    }
}
