package com.example.rumiologia.asistente;

import java.util.List;

/** Un mensaje de la conversación, tal como se muestra en pantalla. */
public class Mensaje {

    public enum Origen { USUARIO, ASISTENTE, ERROR }

    public final Origen origen;
    public String texto;

    /** Fichas citadas por el asistente. Vacío en los mensajes del usuario. */
    public List<String> fuentes;

    /** Marca el mensaje provisional "escribiendo…" mientras se espera respuesta. */
    public boolean cargando;

    public Mensaje(Origen origen, String texto) {
        this.origen = origen;
        this.texto = texto;
    }

    public static Mensaje deUsuario(String texto) {
        return new Mensaje(Origen.USUARIO, texto);
    }

    public static Mensaje cargando() {
        Mensaje m = new Mensaje(Origen.ASISTENTE, "");
        m.cargando = true;
        return m;
    }
}
