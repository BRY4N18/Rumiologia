package com.example.rumiologia.asistente;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.rumiologia.R;

/**
 * Círculo animado que representa a Rumi mientras se le habla: su avatar en el
 * centro, con anillos dorados alrededor cuyo radio crece con {@link #setNivel}, y
 * el propio avatar con un ligero rebote cuando es Rumi quien habla
 * ({@link #setEscalaAvatar}) — dos animaciones distintas para dos cosas distintas:
 * los anillos reaccionan a que TÚ hables, el rebote a que RUMI hable.
 *
 * <p>No sabe nada de reconocimiento de voz ni de síntesis — solo dibuja los
 * valores que le pasen. {@code ChatActivity} decide cuándo cambia cada uno.
 */
public class CirculoVozView extends View {

    private final Paint pincelAnillo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable avatar;
    private final Path recorteAvatar = new Path();
    private float nivel = 0f;
    private float escalaAvatar = 1f;
    private float radioAvatar;
    private float radioAnilloBase;

    public CirculoVozView(Context context) {
        this(context, null);
    }

    public CirculoVozView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        pincelAnillo.setStyle(Paint.Style.STROKE);
        pincelAnillo.setStrokeWidth(dp(3));
        pincelAnillo.setColor(ContextCompat.getColor(context, R.color.dorado));
        avatar = ContextCompat.getDrawable(context, R.drawable.ic_rumi);
    }

    /** 0 = en reposo, 1 = máximo. Fuera de ese rango se recorta. */
    public void setNivel(float nivel) {
        this.nivel = Math.max(0f, Math.min(1f, nivel));
        invalidate();
    }

    /** 1 = tamaño normal del avatar; se usa para el rebote mientras Rumi habla. */
    public void setEscalaAvatar(float escala) {
        this.escalaAvatar = escala;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Fraccion mas chica que antes a propósito: el espacio del círculo creció
        // (180dp -> 280dp), pero el propio ícono de Rumi solo un poco, no en la
        // misma proporción — que haya más aire alrededor, sin que Rumi se vea enorme.
        radioAvatar = Math.min(w, h) * 0.20f;
        radioAnilloBase = radioAvatar * 1.35f;
    }

    @Override
    protected void onDraw(Canvas lienzo) {
        super.onDraw(lienzo);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        for (int anillo = 0; anillo < 2; anillo++) {
            float separacion = radioAvatar * 0.45f * (anillo + 1);
            float radio = radioAnilloBase + separacion * nivel;
            pincelAnillo.setAlpha(anillo == 0 ? 160 : 90);
            lienzo.drawCircle(cx, cy, radio, pincelAnillo);
        }

        if (avatar != null) {
            // El radio (y por lo tanto el recorte) se recalculan en cada dibujado
            // porque escalaAvatar cambia con la animación del rebote al hablar.
            float radioEscalado = radioAvatar * escalaAvatar;
            int r = Math.round(radioEscalado);
            avatar.setBounds((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r));

            recorteAvatar.reset();
            recorteAvatar.addCircle(cx, cy, radioEscalado, Path.Direction.CW);

            int guardado = lienzo.save();
            lienzo.clipPath(recorteAvatar);
            avatar.draw(lienzo);
            lienzo.restoreToCount(guardado);
        }
    }

    private float dp(float valor) {
        return valor * getResources().getDisplayMetrics().density;
    }
}
