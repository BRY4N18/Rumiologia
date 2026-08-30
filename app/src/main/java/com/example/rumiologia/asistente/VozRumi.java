package com.example.rumiologia.asistente;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qué voz en español usa Rumi para hablar, y dónde queda guardada la que cada
 * persona elija en Ajustes.
 *
 * <p>Las voces disponibles varían de un teléfono a otro (paquete de idiomas
 * instalado, fabricante), así que la lista se arma en tiempo de ejecución
 * consultando {@link TextToSpeech#getVoices()} — nunca se asume una lista fija.
 */
public final class VozRumi {

    private static final String ARCHIVO = "ajustes_voz";
    private static final String CLAVE_VOZ = "nombre_voz";

    private VozRumi() {
    }

    /**
     * Voces en español disponibles, una por variante real (se descarta el
     * duplicado "-network" cuando ya existe la misma voz en "-local": es más
     * rápida y no depende de conexión).
     */
    @NonNull
    public static List<Voice> disponibles(@NonNull TextToSpeech tts) {
        if (tts.getVoices() == null) {
            return new ArrayList<>();
        }

        // Clave sin el sufijo -local/-network, para juntar la misma voz.
        Map<String, Voice> porVozBase = new LinkedHashMap<>();
        for (Voice v : tts.getVoices()) {
            if (!"es".equals(v.getLocale().getLanguage())) {
                continue;
            }
            String base = v.getName().replaceAll("-(local|network)$", "");
            Voice actual = porVozBase.get(base);
            boolean esLocal = v.getName().endsWith("-local");
            boolean actualEsLocal = actual != null && actual.getName().endsWith("-local");
            if (actual == null || (esLocal && !actualEsLocal)) {
                porVozBase.put(base, v);
            }
        }

        List<Voice> lista = new ArrayList<>(porVozBase.values());
        // Agrupa por país (es_ES, es_US...) para que la lista no salte de un
        // acento a otro sin orden.
        lista.sort(Comparator.comparing(v -> v.getLocale().toString()));
        return lista;
    }

    public static void guardar(@NonNull Context contexto, @NonNull String nombreVoz) {
        preferencias(contexto).edit().putString(CLAVE_VOZ, nombreVoz).apply();
    }

    @Nullable
    public static String leer(@NonNull Context contexto) {
        String nombre = preferencias(contexto).getString(CLAVE_VOZ, null);
        return TextUtils.isEmpty(nombre) ? null : nombre;
    }

    /** Aplica la voz guardada al sintetizador, si hay una y sigue existiendo. */
    public static void aplicarGuardada(@NonNull Context contexto, @NonNull TextToSpeech tts) {
        String nombre = leer(contexto);
        if (nombre == null) {
            return;
        }
        for (Voice v : tts.getVoices()) {
            if (v.getName().equals(nombre)) {
                tts.setVoice(v);
                return;
            }
        }
    }

    private static SharedPreferences preferencias(Context contexto) {
        return contexto.getApplicationContext().getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE);
    }
}
