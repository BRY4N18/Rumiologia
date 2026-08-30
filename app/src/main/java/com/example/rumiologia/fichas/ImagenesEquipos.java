package com.example.rumiologia.fichas;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Carga las fotos representativas de cada equipo desde {@code assets/equipos/}.
 *
 * <p>Son solo 7 imágenes de unos 60-120 KB: se mantienen todas en memoria una vez
 * cargadas en vez de complicar esto con una caché con límite de tamaño.
 *
 * <p>Si falta la foto de una clase, devuelve {@code null}. Quien la use debe ocultar
 * el {@code ImageView}, no fallar — mismo principio que ya sigue
 * {@link RepositorioFichas} con los PDF.
 */
public final class ImagenesEquipos {

    private static final String TAG = "ImagenesEquipos";
    private static final String CARPETA = "equipos";

    private static final Map<String, Bitmap> cache = new HashMap<>();

    private ImagenesEquipos() {
    }

    @Nullable
    public static synchronized Bitmap obtener(@NonNull Context contexto, @NonNull String slug) {
        if (cache.containsKey(slug)) {
            return cache.get(slug);
        }

        Bitmap bitmap = null;
        AssetManager assets = contexto.getApplicationContext().getAssets();
        try (InputStream entrada = assets.open(CARPETA + "/" + slug + ".jpg")) {
            bitmap = BitmapFactory.decodeStream(entrada);
        } catch (IOException e) {
            Log.w(TAG, "Sin foto para " + slug);
        }

        cache.put(slug, bitmap);
        return bitmap;
    }

    /** Versión recortada en círculo, para la tira de equipos detectados. */
    @Nullable
    public static Drawable obtenerCircular(@NonNull Context contexto, @NonNull String slug) {
        Bitmap bitmap = obtener(contexto, slug);
        if (bitmap == null) {
            return null;
        }
        RoundedBitmapDrawable drawable =
                RoundedBitmapDrawableFactory.create(contexto.getResources(), bitmap);
        drawable.setCircular(true);
        return drawable;
    }
}
