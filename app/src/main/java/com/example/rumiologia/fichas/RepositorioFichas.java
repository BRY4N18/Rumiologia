package com.example.rumiologia.fichas;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Da acceso a las fichas técnicas en PDF que viajan dentro del APK.
 *
 * <p>Están en `assets/fichas/<slug>.pdf`, así que **funcionan sin internet**: es la
 * mitad de la app que sigue sirviendo cuando no hay conexión y el chat con Rumi no
 * está disponible.
 *
 * <p>Un asset no es un archivo del sistema —vive comprimido dentro del APK— y ninguna
 * aplicación externa puede abrirlo. Por eso hay que copiarlo a la caché antes de
 * compartirlo con un visor de PDF.
 */
public class RepositorioFichas {

    private static final String TAG = "RepositorioFichas";
    private static final String CARPETA = "fichas";

    private final Context contexto;

    public RepositorioFichas(@NonNull Context contexto) {
        this.contexto = contexto.getApplicationContext();
    }

    /** ¿Existe la ficha de este equipo? */
    public boolean existe(String slug) {
        try {
            contexto.getAssets().open(rutaAsset(slug)).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Deja la ficha como archivo real y devuelve su ubicación.
     *
     * <p>Se reutiliza la copia anterior si ya está y coincide en tamaño: abrir la
     * misma ficha dos veces no debería volver a escribir 200 KB en disco.
     *
     * @return el archivo listo para compartir, o {@code null} si algo falló
     */
    public File prepararParaAbrir(String slug) {
        File carpeta = new File(contexto.getCacheDir(), CARPETA);
        if (!carpeta.exists() && !carpeta.mkdirs()) {
            Log.e(TAG, "No se pudo crear la carpeta de caché");
            return null;
        }

        File destino = new File(carpeta, slug + ".pdf");

        try (InputStream entrada = contexto.getAssets().open(rutaAsset(slug))) {
            if (destino.exists() && destino.length() == entrada.available()) {
                return destino;
            }
        } catch (IOException e) {
            Log.w(TAG, "No existe la ficha de " + slug, e);
            return null;
        }

        try (InputStream entrada = contexto.getAssets().open(rutaAsset(slug));
             OutputStream salida = new FileOutputStream(destino)) {

            byte[] trozo = new byte[8192];
            int leidos;
            while ((leidos = entrada.read(trozo)) != -1) {
                salida.write(trozo, 0, leidos);
            }
            return destino;

        } catch (IOException e) {
            Log.e(TAG, "No se pudo copiar la ficha de " + slug, e);
            return null;
        }
    }

    private static String rutaAsset(String slug) {
        return CARPETA + "/" + slug + ".pdf";
    }
}
