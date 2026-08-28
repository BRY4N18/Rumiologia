package com.example.rumiologia.asistente;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;

/**
 * Contrato con el backend del asistente RAG.
 *
 * <p>Retrofit convierte esta interfaz en llamadas HTTP reales: se declara qué se
 * envía y qué se espera recibir, y la librería se encarga del hilo, la
 * serialización JSON y el manejo de errores.
 *
 * <p>La app <b>nunca</b> llama a Gemini directamente. Si lo hiciera, la API key
 * viajaría dentro del APK y cualquiera podría extraerla descomprimiéndolo. Por eso
 * existe el backend: es el único que conoce la clave.
 */
public interface AsistenteApi {

    /** Comprobación de que el servidor está vivo y configurado. */
    @GET("health")
    Call<Salud> salud();

    /** Envía una pregunta y devuelve la respuesta del asistente con sus fuentes. */
    @POST("chat")
    Call<Respuesta> preguntar(@Body Consulta consulta);

    // ---------------------------------------------------------------- modelos

    /** Lo que se envía al backend. Los nombres deben coincidir con el JSON. */
    class Consulta {
        public final String pregunta;

        /**
         * Slug del equipo si el usuario llegó tocando una detección en la cámara.
         * Es opcional: el chat también se usa sin el equipo delante, que es
         * justamente el caso que necesita búsqueda semántica.
         */
        public final String equipo;

        public final List<Turno> historial;

        public Consulta(String pregunta, String equipo, List<Turno> historial) {
            this.pregunta = pregunta;
            this.equipo = equipo;
            this.historial = historial;
        }
    }

    /** Un turno previo de la conversación, para que el asistente tenga contexto. */
    class Turno {
        public final String rol;      // "usuario" o "asistente"
        public final String texto;

        public Turno(String rol, String texto) {
            this.rol = rol;
            this.texto = texto;
        }
    }

    /** Lo que devuelve el backend. */
    class Respuesta {
        public String respuesta;

        /** Fichas de las que salió la información. Permite verificar la respuesta. */
        public List<String> fuentes;
    }

    class Salud {
        public boolean ok;
        public String modelo;
        public boolean clave_configurada;
        public boolean almacen_configurado;
    }
}
