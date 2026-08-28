package com.example.rumiologia;

import android.graphics.RectF;

import androidx.annotation.NonNull;

/**
 * Una deteccion devuelta por el modelo.
 *
 * <p>Las coordenadas de {@link #box} estan NORMALIZADAS (0..1) respecto al frame
 * original de la camara, ya sin el relleno del letterbox. Se guardan asi para que
 * no dependan ni del tamano del frame ni del tamano de la pantalla: cada vista las
 * escala a su propio espacio cuando las dibuja.
 */
public class Detection {

    /** Caja normalizada 0..1 sobre el frame de la camara. */
    public final RectF box;

    /** Indice de clase: el mismo orden de labels.txt y de data.yaml. */
    public final int classId;

    /** Nombre tecnico de la clase (por ejemplo "ankom_estufa"). */
    public final String label;

    /** Confianza 0..1. */
    public final float score;

    public Detection(RectF box, int classId, String label, float score) {
        this.box = box;
        this.classId = classId;
        this.label = label;
        this.score = score;
    }

    /** Area de la caja, usada para desempatar cuando el usuario toca cajas superpuestas. */
    public float area() {
        return Math.max(0f, box.width()) * Math.max(0f, box.height());
    }

    @NonNull
    @Override
    public String toString() {
        return String.format("%s %.0f%% [%.3f, %.3f, %.3f, %.3f]",
                label, score * 100, box.left, box.top, box.right, box.bottom);
    }
}
