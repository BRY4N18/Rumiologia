package com.example.rumiologia;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Capa transparente sobre la PreviewView que dibuja las cajas detectadas
 * y detecta cuando el usuario toca una de ellas.
 *
 * <p>El punto delicado de esta clase es la conversion de coordenadas. El modelo
 * trabaja sobre el frame de la camara (por ejemplo 640x480), mientras que la
 * PreviewView tiene el tamano de la pantalla y usa FILL_CENTER: escala el frame
 * hasta cubrir toda la vista y recorta lo que sobra. Si se dibujara con una simple
 * regla de tres, las cajas apareceran desplazadas respecto al equipo real.
 */
public class OverlayView extends View {

    /** Aviso de que el usuario toco una deteccion. */
    public interface OnDetectionClickListener {
        void onDetectionClick(Detection detection);
    }

    private static final float GROSOR_CAJA_DP = 2.5f;
    private static final float TEXTO_SP = 13f;
    private static final float PADDING_ETIQUETA_DP = 4f;

    private final Paint pincelCaja = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pincelFondoTexto = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pincelTexto = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectTemporal = new RectF();

    private List<Detection> detecciones = Collections.emptyList();
    private int[] coloresClase = new int[0];

    /** Tamano del frame que produjo estas detecciones, ya rotado a vertical. */
    private int anchoFrame = 0;
    private int altoFrame = 0;

    private OnDetectionClickListener listener;
    private float densidad;

    public OverlayView(Context context) {
        this(context, null);
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        densidad = getResources().getDisplayMetrics().density;
        float tamanoTexto = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                TEXTO_SP, getResources().getDisplayMetrics());

        pincelCaja.setStyle(Paint.Style.STROKE);
        pincelCaja.setStrokeWidth(GROSOR_CAJA_DP * densidad);

        pincelFondoTexto.setStyle(Paint.Style.FILL);

        pincelTexto.setColor(Color.WHITE);
        pincelTexto.setTextSize(tamanoTexto);
        pincelTexto.setFakeBoldText(true);

        coloresClase = leerColoresClase();
        setClickable(true);
    }

    /**
     * Lee el array de colores por clase.
     *
     * <p>Se usa {@code obtainTypedArray} y no {@code getStringArray}: aapt2 compila
     * los valores {@code #RRGGBB} como recursos de tipo color, y pedirlos como
     * cadenas devuelve nulos.
     */
    private int[] leerColoresClase() {
        TypedArray tipado = getResources().obtainTypedArray(R.array.colores_clases);
        try {
            int[] colores = new int[tipado.length()];
            for (int i = 0; i < colores.length; i++) {
                colores[i] = tipado.getColor(i, Color.RED);
            }
            return colores;
        } finally {
            tipado.recycle();
        }
    }

    public void setOnDetectionClickListener(OnDetectionClickListener listener) {
        this.listener = listener;
    }

    /**
     * Publica un nuevo conjunto de detecciones para dibujar.
     *
     * @param detecciones cajas normalizadas 0..1 sobre el frame
     * @param anchoFrame  ancho del frame que las produjo, ya rotado
     * @param altoFrame   alto del frame que las produjo, ya rotado
     */
    public void setResults(List<Detection> detecciones, int anchoFrame, int altoFrame) {
        this.detecciones = detecciones != null ? detecciones : Collections.<Detection>emptyList();
        this.anchoFrame = anchoFrame;
        this.altoFrame = altoFrame;
        postInvalidate();
    }

    public void clear() {
        setResults(Collections.<Detection>emptyList(), 0, 0);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (detecciones.isEmpty() || anchoFrame <= 0 || altoFrame <= 0) {
            return;
        }

        float padding = PADDING_ETIQUETA_DP * densidad;
        Paint.FontMetrics fm = pincelTexto.getFontMetrics();
        float altoTexto = fm.descent - fm.ascent;

        for (Detection d : detecciones) {
            aVista(d.box, rectTemporal);

            int color = colorDe(d.classId);
            pincelCaja.setColor(color);
            canvas.drawRect(rectTemporal, pincelCaja);

            String texto = String.format(Locale.getDefault(), "%s  %.0f%%",
                    nombreLegible(d.label), d.score * 100);
            float anchoTexto = pincelTexto.measureText(texto);

            // La etiqueta va sobre la caja, salvo que no quepa arriba: entonces va dentro.
            float topEtiqueta = rectTemporal.top - altoTexto - padding * 2;
            if (topEtiqueta < 0) {
                topEtiqueta = rectTemporal.top;
            }

            pincelFondoTexto.setColor(color);
            canvas.drawRect(
                    rectTemporal.left,
                    topEtiqueta,
                    rectTemporal.left + anchoTexto + padding * 2,
                    topEtiqueta + altoTexto + padding * 2,
                    pincelFondoTexto);

            canvas.drawText(texto,
                    rectTemporal.left + padding,
                    topEtiqueta + padding - fm.ascent,
                    pincelTexto);
        }
    }

    /**
     * Convierte una caja normalizada del frame a pixeles de esta vista,
     * replicando el recorte FILL_CENTER que aplica la PreviewView.
     */
    private void aVista(RectF origen, RectF destino) {
        float escala = Math.max((float) getWidth() / anchoFrame, (float) getHeight() / altoFrame);
        float anchoEscalado = anchoFrame * escala;
        float altoEscalado = altoFrame * escala;
        float desplazamientoX = (getWidth() - anchoEscalado) / 2f;
        float desplazamientoY = (getHeight() - altoEscalado) / 2f;

        destino.set(
                origen.left * anchoEscalado + desplazamientoX,
                origen.top * altoEscalado + desplazamientoY,
                origen.right * anchoEscalado + desplazamientoX,
                origen.bottom * altoEscalado + desplazamientoY);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP || listener == null) {
            return super.onTouchEvent(event);
        }

        Detection tocada = deteccionEn(event.getX(), event.getY());
        if (tocada == null) {
            return super.onTouchEvent(event);
        }

        performClick();
        listener.onDetectionClick(tocada);
        return true;
    }

    /**
     * Busca que deteccion contiene el punto tocado. Si hay varias superpuestas,
     * gana la mas pequena: normalmente es la que el usuario queria senalar.
     */
    @Nullable
    private Detection deteccionEn(float x, float y) {
        List<Detection> candidatas = new ArrayList<>();
        for (Detection d : detecciones) {
            aVista(d.box, rectTemporal);
            if (rectTemporal.contains(x, y)) {
                candidatas.add(d);
            }
        }
        if (candidatas.isEmpty()) {
            return null;
        }
        Detection menor = candidatas.get(0);
        for (Detection d : candidatas) {
            if (d.area() < menor.area()) {
                menor = d;
            }
        }
        return menor;
    }

    private int colorDe(int classId) {
        if (coloresClase.length == 0) {
            return Color.RED;
        }
        return coloresClase[Math.abs(classId) % coloresClase.length];
    }

    /** "ankom_estufa" -> "Ankom estufa". Provisional hasta conectar clases.json. */
    private String nombreLegible(String slug) {
        if (slug == null || slug.isEmpty()) {
            return "?";
        }
        String conEspacios = slug.replace('_', ' ');
        return Character.toUpperCase(conEspacios.charAt(0)) + conEspacios.substring(1);
    }
}
