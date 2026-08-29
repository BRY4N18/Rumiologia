package com.example.rumiologia;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Traduce el identificador técnico de una clase al nombre que ve el usuario.
 *
 * <p>El modelo devuelve índices, y {@code labels.txt} los convierte en slugs como
 * {@code ankom_estufa}. Eso sirve para el código, pero no para mostrarlo en
 * pantalla: el usuario debe leer "Estufa de Secado ANKOM".
 *
 * <p>Los nombres salen de {@code assets/clases.json}, que es una copia de
 * {@code ml/clases.json} — la única fuente de verdad del proyecto. Duplicar los
 * nombres aquí a mano garantizaría que tarde o temprano se desincronicen.
 */
public final class Equipos {

    private static final String TAG = "Equipos";
    private static final String ASSET = "clases.json";

    private static Map<String, String> nombres;

    private Equipos() {
    }

    /** Nombre legible del equipo; si no se encuentra, devuelve el propio slug. */
    public static synchronized String nombreDe(@NonNull Context contexto, String slug) {
        if (nombres == null) {
            nombres = cargar(contexto);
        }
        String nombre = nombres.get(slug);
        return nombre != null ? nombre : slug;
    }

    /** Todos los slugs conocidos, en el orden de clases.json. */
    public static synchronized List<String> slugs(@NonNull Context contexto) {
        if (nombres == null) {
            nombres = cargar(contexto);
        }
        return new ArrayList<>(nombres.keySet());
    }

    private static Map<String, String> cargar(Context contexto) {
        Map<String, String> mapa = new java.util.LinkedHashMap<>();
        try (InputStream in = contexto.getAssets().open(ASSET)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] trozo = new byte[4096];
            int leidos;
            while ((leidos = in.read(trozo)) != -1) {
                buffer.write(trozo, 0, leidos);
            }

            JSONObject raiz = new JSONObject(buffer.toString(StandardCharsets.UTF_8.name()));
            JSONArray clases = raiz.getJSONArray("clases");
            for (int i = 0; i < clases.length(); i++) {
                JSONObject clase = clases.getJSONObject(i);
                mapa.put(clase.getString("slug"), clase.getString("nombre"));
            }
        } catch (IOException | org.json.JSONException e) {
            Log.w(TAG, "No se pudo leer " + ASSET + "; se usarán los slugs", e);
        }
        return mapa;
    }
}
