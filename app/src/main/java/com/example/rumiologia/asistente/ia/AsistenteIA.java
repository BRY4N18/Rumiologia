package com.example.rumiologia.asistente.ia;

import java.util.List;

/**
 * Contrato del asistente conversacional, independiente del proveedor.
 *
 * <p>La pantalla de chat depende de <b>esta interfaz</b>, no de Gemini. Cambiar de
 * proveedor —a OpenAI, a un servicio propio, o a lo que venga— significa escribir
 * otra implementación, sin tocar la interfaz de usuario.
 *
 * <p>Esa separación no es teórica en este proyecto: el asistente ya vivió en un
 * backend FastAPI antes de moverse a Gemini directo, y la pantalla no cambió.
 */
public interface AsistenteIA {

    /** Aviso del resultado. Se entrega siempre en el hilo principal. */
    interface Respuesta {
        void onExito(RespuestaAsistente respuesta);

        void onError(String mensaje);
    }

    /**
     * Envía una pregunta al asistente.
     *
     * @param pregunta  lo que escribió o dictó el usuario
     * @param equipo    slug del equipo detectado, o {@code null} si no hay ninguno
     * @param historial turnos previos de la conversación, del más antiguo al más nuevo
     */
    void preguntar(String pregunta, String equipo, List<Turno> historial, Respuesta callback);

    /** Un turno previo de la conversación. */
    class Turno {
        public final boolean esDelUsuario;
        public final String texto;

        public Turno(boolean esDelUsuario, String texto) {
            this.esDelUsuario = esDelUsuario;
            this.texto = texto;
        }
    }
}
