package com.example.rumiologia.asistente;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.rumiologia.R;

import java.util.Random;

/**
 * Orbe animado y reactivo estilo Gemini Live que representa a Rumi durante el modo de voz.
 *
 * <p>Características visuales:
 * <ul>
 *   <li><b>Orbe fluido y orgánico (Morphing Blob)</b>: Deformaciones armónicas sinusoidales
 *       continuas que reaccionan a la amplitud del micrófono y al habla de Rumi.</li>
 *   <li><b>Gradientes multicapa</b>: Fusión de verde institucional, esmeralda luminoso y destellos
 *       dorados bioluminiscentes.</li>
 *   <li><b>Sistema de partículas flotantes</b>: Partículas luminosas que orbitan, flotan y se
 *       dispersan con mayor energía según el volumen de la voz.</li>
 *   <li><b>Avatar de Rumi integrado</b>: Núcleo protegido y halo de energía que acompaña el ritmo
 *       de la conversación hablada.</li>
 * </ul>
 *
 * <p>Se ejecuta a la tasa de refresco nativa de la pantalla (60/90/120 Hz) mediante
 * {@link #postInvalidateOnAnimation()} y se desactiva limpiamente al salir de pantalla.
 */
public class CirculoVozView extends View {

    public enum Estado {
        REPOSO,
        ESCUCHANDO,
        PENSANDO,
        HABLANDO
    }

    private static final int CANTIDAD_PARTICULAS = 28;
    private static final int PUNTOS_CONTORNO = 48;

    // --- Pinceles y dibujo ---
    private final Paint pincelAura = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pincelCuerpo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pincelBordeOrbe = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pincelParticula = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pincelHaloAvatar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pincelOndaVocal = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path caminoOrbe = new Path();
    private final Path recorteAvatar = new Path();
    private final Drawable avatar;

    // --- Geometría y dimensiones ---
    private float centroX;
    private float centroY;
    private float radioBaseOrbe;
    private float radioAvatar;
    private final float[] puntosX = new float[PUNTOS_CONTORNO];
    private final float[] puntosY = new float[PUNTOS_CONTORNO];

    // --- Estados y reactividad ---
    private Estado estado = Estado.REPOSO;
    private float nivelObjetivo = 0f;
    private float nivelFiltrado = 0f;
    private float escalaAvatar = 1f;

    // --- Animación temporal ---
    private boolean animando = false;
    private long tiempoUltimoFrame = 0;
    private float faseOnda1 = 0f;
    private float faseOnda2 = 0f;
    private float faseOnda3 = 0f;
    private float faseRotacion = 0f;
    private float tiempoEstado = 0f;

    // --- Partículas ---
    private final Particula[] particulas = new Particula[CANTIDAD_PARTICULAS];
    private final Random aleatorio = new Random();

    // --- Colores de la paleta ---
    private int colorVerdeInstitucional;
    private int colorVerdeEsmeralda;
    private int colorVerdeClaro;
    private int colorDorado;
    private int colorDoradoClaro;

    public CirculoVozView(Context context) {
        this(context, null);
    }

    public CirculoVozView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        inicializarColores(context);

        avatar = ContextCompat.getDrawable(context, R.drawable.ic_rumi);

        pincelAura.setStyle(Paint.Style.FILL);

        pincelCuerpo.setStyle(Paint.Style.FILL);

        pincelBordeOrbe.setStyle(Paint.Style.STROKE);
        pincelBordeOrbe.setStrokeWidth(dp(2.2f));
        pincelBordeOrbe.setColor(colorDorado);

        pincelParticula.setStyle(Paint.Style.FILL);

        pincelHaloAvatar.setStyle(Paint.Style.STROKE);
        pincelHaloAvatar.setStrokeWidth(dp(2.5f));
        pincelHaloAvatar.setColor(colorDorado);

        pincelOndaVocal.setStyle(Paint.Style.STROKE);
        pincelOndaVocal.setStrokeWidth(dp(1.8f));

        inicializarParticulas();
    }

    private void inicializarColores(Context context) {
        colorVerdeInstitucional = ContextCompat.getColor(context, R.color.verde_institucional);
        colorVerdeEsmeralda = 0xFF2E7D32;
        colorVerdeClaro = 0xFF81C784;
        colorDorado = ContextCompat.getColor(context, R.color.dorado);
        colorDoradoClaro = 0xFFFFE082;
    }

    private void inicializarParticulas() {
        int[] coloresParticulas = {
                colorDorado,
                colorDoradoClaro,
                colorVerdeClaro,
                0xFFE0F2F1,
                0xFFFFFFFF
        };
        for (int i = 0; i < CANTIDAD_PARTICULAS; i++) {
            particulas[i] = new Particula();
            reiniciarParticula(particulas[i], true, coloresParticulas);
        }
    }

    private void reiniciarParticula(Particula p, boolean primeraVez, int[] paletaColores) {
        float angulo = aleatorio.nextFloat() * (float) (2 * Math.PI);
        p.angulo = angulo;
        // Distancia radial: algunas cerca del orbe, otras más alejadas
        float fraccionMin = 0.65f;
        float fraccionMax = 1.35f;
        p.distanciaFraccion = primeraVez
                ? fraccionMin + aleatorio.nextFloat() * (fraccionMax - fraccionMin)
                : fraccionMin + aleatorio.nextFloat() * 0.2f;

        p.velocidadRadial = 0.08f + aleatorio.nextFloat() * 0.16f;
        p.velocidadAngular = (aleatorio.nextBoolean() ? 1 : -1) * (0.4f + aleatorio.nextFloat() * 0.8f);
        p.radio = dp(1.5f + aleatorio.nextFloat() * 2.8f);
        p.alfaMaximo = 0.35f + aleatorio.nextFloat() * 0.60f;
        p.alfaActual = primeraVez ? aleatorio.nextFloat() * p.alfaMaximo : 0f;
        p.color = paletaColores[aleatorio.nextInt(paletaColores.length)];
        p.vidaTotal = 1.8f + aleatorio.nextFloat() * 2.5f;
        p.edad = primeraVez ? aleatorio.nextFloat() * p.vidaTotal : 0f;
    }

    /** Estado actual de la conversación por voz. */
    public void setEstado(@NonNull Estado nuevoEstado) {
        if (this.estado != nuevoEstado) {
            this.estado = nuevoEstado;
            this.tiempoEstado = 0f;
            invalidate();
        }
    }

    @NonNull
    public Estado getEstado() {
        return estado;
    }

    /** Nivel de amplitud de entrada (0 = silencio, 1 = máximo). */
    public void setNivel(float nivel) {
        this.nivelObjetivo = Math.max(0f, Math.min(1f, nivel));
        invalidate();
    }

    /** Escala de micro-rebote del avatar mientras Rumi habla. */
    public void setEscalaAvatar(float escala) {
        this.escalaAvatar = Math.max(0.7f, Math.min(1.4f, escala));
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animando = true;
        tiempoUltimoFrame = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        animando = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        animando = (visibility == VISIBLE);
        if (animando) {
            tiempoUltimoFrame = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centroX = w / 2f;
        centroY = h / 2f;

        float dimensionMin = Math.min(w, h);
        radioBaseOrbe = dimensionMin * 0.30f;
        radioAvatar = dimensionMin * 0.17f;

        actualizarShaders();
    }

    private void actualizarShaders() {
        if (radioBaseOrbe <= 0) return;

        // Aura translúcida bioluminiscente exterior
        RadialGradient gradienteAura = new RadialGradient(
                centroX, centroY, radioBaseOrbe * 1.65f,
                new int[]{0x662E7D32, 0x33C9A227, 0x001B5E3F},
                new float[]{0.0f, 0.65f, 1.0f},
                Shader.TileMode.CLAMP
        );
        pincelAura.setShader(gradienteAura);

        // Cuerpo fluido con resplandor esmeralda a verde institucional
        RadialGradient gradienteCuerpo = new RadialGradient(
                centroX, centroY, radioBaseOrbe * 1.25f,
                new int[]{0xEE4CAF50, 0xDD2E7D32, 0xF014472F},
                new float[]{0.0f, 0.55f, 1.0f},
                Shader.TileMode.CLAMP
        );
        pincelCuerpo.setShader(gradienteCuerpo);
    }

    @Override
    protected void onDraw(Canvas lienzo) {
        super.onDraw(lienzo);

        long tiempoActual = SystemClock.uptimeMillis();
        if (tiempoUltimoFrame == 0) {
            tiempoUltimoFrame = tiempoActual;
        }
        float dt = Math.min(0.066f, (tiempoActual - tiempoUltimoFrame) / 1000f);
        tiempoUltimoFrame = tiempoActual;

        actualizarFisica(dt);

        // 1. Dibujar aura exterior expansiva
        dibujarAura(lienzo);

        // 2. Dibujar partículas flotantes y orbitantes
        dibujarParticulas(lienzo);

        // 3. Dibujar orbe fluido morphing (Gemini Blob)
        dibujarOrbeFluido(lienzo);

        // 4. Dibujar ondas vocales reactivas (anillos de energía)
        dibujarOndasVocales(lienzo);

        // 5. Dibujar el avatar de Rumi en el centro con su halo protector
        dibujarAvatarCentral(lienzo);

        if (animando) {
            postInvalidateOnAnimation();
        }
    }

    private void actualizarFisica(float dt) {
        tiempoEstado += dt;

        // Suavizado del nivel de audio (filtro paso bajo para movimiento elástico y orgánico)
        float factorVelocidad = nivelObjetivo > nivelFiltrado ? 14f : 8f;
        nivelFiltrado += (nivelObjetivo - nivelFiltrado) * Math.min(1f, dt * factorVelocidad);

        // Velocidad de las ondas armónicas según el estado
        float multiplicadorVelocidad = 1f;
        switch (estado) {
            case ESCUCHANDO:
                multiplicadorVelocidad = 1.4f + nivelFiltrado * 2.2f;
                break;
            case PENSANDO:
                multiplicadorVelocidad = 2.0f;
                break;
            case HABLANDO:
                multiplicadorVelocidad = 2.4f;
                break;
            case REPOSO:
            default:
                multiplicadorVelocidad = 0.8f;
                break;
        }

        faseOnda1 += dt * 2.2f * multiplicadorVelocidad;
        faseOnda2 += dt * 1.7f * multiplicadorVelocidad;
        faseOnda3 += dt * 2.9f * multiplicadorVelocidad;
        faseRotacion += dt * 0.4f * multiplicadorVelocidad;

        actualizarParticulas(dt);
    }

    private void actualizarParticulas(float dt) {
        float energiaVocal = (estado == Estado.HABLANDO) ? 0.7f : nivelFiltrado;
        int[] paleta = { colorDorado, colorDoradoClaro, colorVerdeClaro, 0xFFFFFFFF };

        for (Particula p : particulas) {
            p.edad += dt;
            if (p.edad >= p.vidaTotal) {
                reiniciarParticula(p, false, paleta);
                continue;
            }

            // Aceleran y se expanden cuando hay energía de voz
            float factorImpulso = 1f + energiaVocal * 1.6f;
            p.distanciaFraccion += p.velocidadRadial * dt * factorImpulso;
            p.angulo += p.velocidadAngular * dt * factorImpulso;

            // Fade in al nacer, fade out al envejecer
            float progresoVida = p.edad / p.vidaTotal;
            if (progresoVida < 0.25f) {
                p.alfaActual = (progresoVida / 0.25f) * p.alfaMaximo;
            } else if (progresoVida > 0.70f) {
                p.alfaActual = ((1f - progresoVida) / 0.30f) * p.alfaMaximo;
            } else {
                p.alfaActual = p.alfaMaximo;
            }
        }
    }

    private void dibujarAura(Canvas lienzo) {
        float expansion = 1f + nivelFiltrado * 0.35f;
        if (estado == Estado.HABLANDO) {
            expansion += (float) Math.sin(tiempoEstado * 9f) * 0.08f;
        } else if (estado == Estado.PENSANDO) {
            expansion += (float) Math.sin(tiempoEstado * 3.5f) * 0.06f;
        }

        lienzo.save();
        lienzo.scale(expansion, expansion, centroX, centroY);
        lienzo.drawCircle(centroX, centroY, radioBaseOrbe * 1.55f, pincelAura);
        lienzo.restore();
    }

    private void dibujarParticulas(Canvas lienzo) {
        for (Particula p : particulas) {
            float dist = radioBaseOrbe * p.distanciaFraccion;
            float px = centroX + (float) Math.cos(p.angulo) * dist;
            float py = centroY + (float) Math.sin(p.angulo) * dist;

            pincelParticula.setColor(p.color);
            pincelParticula.setAlpha(Math.round(p.alfaActual * 255));
            lienzo.drawCircle(px, py, p.radio, pincelParticula);
        }
    }

    private void dibujarOrbeFluido(Canvas lienzo) {
        // Amplitud de la deformación armónica
        float ampBase = 0.05f;
        if (estado == Estado.ESCUCHANDO) {
            ampBase = 0.08f + nivelFiltrado * 0.30f;
        } else if (estado == Estado.PENSANDO) {
            ampBase = 0.12f + (float) Math.sin(tiempoEstado * 4f) * 0.06f;
        } else if (estado == Estado.HABLANDO) {
            ampBase = 0.18f + (float) Math.sin(tiempoEstado * 12f) * 0.09f;
        }

        float pasoAngular = (float) (2 * Math.PI / PUNTOS_CONTORNO);

        for (int i = 0; i < PUNTOS_CONTORNO; i++) {
            float th = i * pasoAngular + faseRotacion;

            // Fusión armónica sinusoidal de 3 frecuencias
            float armonica1 = (float) Math.sin(2 * th + faseOnda1);
            float armonica2 = (float) Math.cos(3 * th - faseOnda2);
            float armonica3 = (float) Math.sin(5 * th + faseOnda3);

            float deformacion = ampBase * (0.50f * armonica1 + 0.32f * armonica2 + 0.18f * armonica3);
            float r = radioBaseOrbe * (1f + deformacion);

            puntosX[i] = centroX + (float) Math.cos(th) * r;
            puntosY[i] = centroY + (float) Math.sin(th) * r;
        }

        // Construcción de la curva suave con puntos de control Bézier
        caminoOrbe.reset();
        caminoOrbe.moveTo((puntosX[0] + puntosX[PUNTOS_CONTORNO - 1]) / 2f,
                          (puntosY[0] + puntosY[PUNTOS_CONTORNO - 1]) / 2f);

        for (int i = 0; i < PUNTOS_CONTORNO; i++) {
            int siguiente = (i + 1) % PUNTOS_CONTORNO;
            float puntoMedioX = (puntosX[i] + puntosX[siguiente]) / 2f;
            float puntoMedioY = (puntosY[i] + puntosY[siguiente]) / 2f;
            caminoOrbe.quadTo(puntosX[i], puntosY[i], puntoMedioX, puntoMedioY);
        }
        caminoOrbe.close();

        // Dibujar el cuerpo bioluminiscente del orbe
        lienzo.drawPath(caminoOrbe, pincelCuerpo);

        // Borde fino brillante con destello dorado
        pincelBordeOrbe.setAlpha(estado == Estado.REPOSO ? 120 : 210);
        lienzo.drawPath(caminoOrbe, pincelBordeOrbe);
    }

    private void dibujarOndasVocales(Canvas lienzo) {
        // Ondas concéntricas de pulso que se expanden hacia afuera
        if (estado == Estado.ESCUCHANDO && nivelFiltrado > 0.08f) {
            float radioOnda1 = radioBaseOrbe * (1.15f + nivelFiltrado * 0.45f);
            pincelOndaVocal.setColor(colorDorado);
            pincelOndaVocal.setAlpha(Math.round(180 * (1f - nivelFiltrado * 0.4f)));
            lienzo.drawCircle(centroX, centroY, radioOnda1, pincelOndaVocal);

            float radioOnda2 = radioBaseOrbe * (1.30f + nivelFiltrado * 0.65f);
            pincelOndaVocal.setAlpha(Math.round(110 * (1f - nivelFiltrado * 0.6f)));
            lienzo.drawCircle(centroX, centroY, radioOnda2, pincelOndaVocal);
        } else if (estado == Estado.HABLANDO) {
            float pulsoHabla = (float) (Math.sin(tiempoEstado * 8f) * 0.5f + 0.5f);
            float radioHabla = radioBaseOrbe * (1.18f + pulsoHabla * 0.35f);
            pincelOndaVocal.setColor(colorVerdeClaro);
            pincelOndaVocal.setAlpha(Math.round(140 * (1f - pulsoHabla * 0.5f)));
            lienzo.drawCircle(centroX, centroY, radioHabla, pincelOndaVocal);
        }
    }

    private void dibujarAvatarCentral(Canvas lienzo) {
        if (avatar == null) return;

        // El avatar responde a la escala de habla y al nivel de voz
        float escala = escalaAvatar;
        if (estado == Estado.ESCUCHANDO) {
            escala = 1f + nivelFiltrado * 0.07f;
        } else if (estado == Estado.HABLANDO) {
            escala *= (1f + (float) Math.sin(tiempoEstado * 10f) * 0.05f);
        }

        float radioActual = radioAvatar * escala;
        int r = Math.round(radioActual);

        avatar.setBounds(
                (int) (centroX - r),
                (int) (centroY - r),
                (int) (centroX + r),
                (int) (centroY + r)
        );

        // Recorte circular impecable para avatar con esquinas o fondo plano
        recorteAvatar.reset();
        recorteAvatar.addCircle(centroX, centroY, radioActual, Path.Direction.CW);

        int estadoLienzo = lienzo.save();
        lienzo.clipPath(recorteAvatar);
        avatar.draw(lienzo);
        lienzo.restoreToCount(estadoLienzo);

        // Halo dorado/esmeralda rodeando al avatar
        pincelHaloAvatar.setColor(estado == Estado.HABLANDO ? colorVerdeClaro : colorDorado);
        pincelHaloAvatar.setAlpha(estado == Estado.REPOSO ? 160 : 240);
        lienzo.drawCircle(centroX, centroY, radioActual, pincelHaloAvatar);
    }

    private float dp(float valor) {
        return valor * getResources().getDisplayMetrics().density;
    }

    /** Estado interno de cada partícula del sistema. */
    private static class Particula {
        float angulo;
        float distanciaFraccion;
        float velocidadRadial;
        float velocidadAngular;
        float radio;
        float alfaMaximo;
        float alfaActual;
        int color;
        float vidaTotal;
        float edad;
    }
}
