package com.example.rumiologia.asistente.ia;

import java.util.Collections;
import java.util.List;

/**
 * Lo que devuelve el asistente, en términos del dominio de la app.
 *
 * <p>Existe para que la pantalla de chat no manipule las clases del JSON de ningún
 * proveedor. Si mañana la respuesta de Gemini cambia de forma, se ajusta la
 * traducción en un solo sitio y nada más se entera.
 */
public class RespuestaAsistente {

    public final String texto;

    /** Fichas de las que salió la información, para que el usuario pueda verificarla. */
    public final List<String> fuentes;

    public RespuestaAsistente(String texto, List<String> fuentes) {
        this.texto = texto != null ? texto : "";
        this.fuentes = fuentes != null ? fuentes : Collections.<String>emptyList();
    }
}
