package com.example.rumiologia;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Marco decorativo de esquinas doradas sobre la vista de cámara, para guiar dónde
 * apuntar. Puramente visual: no participa en la detección ni en los toques —eso
 * sigue resolviéndose en {@link OverlayView}— y por defecto no consume eventos de
 * toque, así que puede ir encima sin bloquear nada.
 */
public class MarcoEscaneoView extends View {

    private final Paint pincel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float largoBrazo;
    private float margen;

    public MarcoEscaneoView(Context context) {
        this(context, null);
    }

    public MarcoEscaneoView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        pincel.setColor(ContextCompat.getColor(context, R.color.dorado));
        pincel.setStyle(Paint.Style.STROKE);
        pincel.setStrokeCap(Paint.Cap.ROUND);
        pincel.setStrokeWidth(dp(3));
        largoBrazo = dp(24);
        margen = dp(28);
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(Canvas lienzo) {
        super.onDraw(lienzo);
        float ancho = getWidth();
        float alto = getHeight();
        if (ancho <= 0 || alto <= 0) {
            return;
        }

        // Esquina superior izquierda
        lienzo.drawLine(margen, margen, margen + largoBrazo, margen, pincel);
        lienzo.drawLine(margen, margen, margen, margen + largoBrazo, pincel);

        // Esquina superior derecha
        lienzo.drawLine(ancho - margen, margen, ancho - margen - largoBrazo, margen, pincel);
        lienzo.drawLine(ancho - margen, margen, ancho - margen, margen + largoBrazo, pincel);

        // Esquina inferior izquierda
        lienzo.drawLine(margen, alto - margen, margen + largoBrazo, alto - margen, pincel);
        lienzo.drawLine(margen, alto - margen, margen, alto - margen - largoBrazo, pincel);

        // Esquina inferior derecha
        lienzo.drawLine(ancho - margen, alto - margen, ancho - margen - largoBrazo, alto - margen, pincel);
        lienzo.drawLine(ancho - margen, alto - margen, ancho - margen, alto - margen - largoBrazo, pincel);
    }

    private float dp(float valor) {
        return valor * getResources().getDisplayMetrics().density;
    }
}
