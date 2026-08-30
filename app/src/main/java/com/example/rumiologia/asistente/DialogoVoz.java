package com.example.rumiologia.asistente;

import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.DialogFragment;

import com.example.rumiologia.R;

import io.noties.markwon.Markwon;

/**
 * Pantalla completa que aparece al tocar el micrófono en el chat: un círculo
 * animado (el avatar de Rumi, ver {@link CirculoVozView}) que se mueve con el
 * nivel de voz, más el texto que se va reconociendo.
 *
 * <p>No contiene lógica de reconocimiento de voz — eso lo sigue manejando
 * {@code ChatActivity} exactamente igual que antes de que existiera este diálogo.
 * Esta clase solo se entera de lo que ya está pasando (mediante estos métodos) y
 * lo muestra; así no se arriesga el reconocimiento ya probado a mano.
 */
public class DialogoVoz extends DialogFragment {

    /** Avisa de las dos acciones que la persona puede iniciar desde el propio diálogo. */
    public interface Oyente {
        void onCancelarVoz();

        /** Se tocó el círculo estando en reposo: seguir la conversación por voz. */
        void onTocarCirculo();
    }

    private TextView estado;
    private TextView texto;
    private CirculoVozView circulo;
    private Oyente oyente;
    private ValueAnimator pulso;
    private ValueAnimator rebote;
    private Markwon markwon;

    public void setOyente(Oyente oyente) {
        this.oyente = oyente;
    }

    @Override
    public int getTheme() {
        return R.style.Theme_Rumiologia_DialogoVoz;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflador, @Nullable ViewGroup contenedor,
                             @Nullable Bundle savedInstanceState) {
        return inflador.inflate(R.layout.dialogo_voz, contenedor, false);
    }

    @Override
    public void onViewCreated(@NonNull View vista, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(vista, savedInstanceState);
        estado = vista.findViewById(R.id.vozEstado);
        texto = vista.findViewById(R.id.vozTexto);
        circulo = vista.findViewById(R.id.circuloVoz);
        markwon = Markwon.create(requireContext());

        vista.findViewById(R.id.vozCancelar).setOnClickListener(v -> {
            if (oyente != null) {
                oyente.onCancelarVoz();
            }
            dismissAllowingStateLoss();
        });

        // Tocar el círculo en reposo retoma la conversación sin volver a la pantalla
        // de texto — es lo que permite quedarse en el modo de voz turno tras turno.
        circulo.setOnClickListener(v -> {
            if (oyente != null) {
                oyente.onTocarCirculo();
            }
        });
    }

    public void mostrarEstado(@StringRes int idTexto) {
        if (estado != null) {
            estado.setText(idTexto);
        }
    }

    public void mostrarTextoReconocido(String texto) {
        if (this.texto != null) {
            // La respuesta de Rumi viene en Markdown; sin esto se verían los
            // asteriscos de la negrita tal cual, igual que pasaba en el chat de texto.
            markwon.setMarkdown(this.texto, texto);
        }
    }

    /** 0 = en reposo, 1 = máximo — normalmente el volumen del micrófono ya normalizado. */
    public void actualizarNivel(float nivel) {
        if (circulo != null) {
            circulo.setNivel(nivel);
        }
    }

    /**
     * Pulso automático para cuando no hay un volumen real que mostrar: mientras Rumi
     * "piensa" (esperando la respuesta del servidor) o mientras habla (el
     * {@code TextToSpeech} de Android no expone su amplitud, a diferencia del
     * micrófono).
     */
    public void iniciarPulsoAutomatico() {
        detenerPulsoAutomatico();
        pulso = ValueAnimator.ofFloat(0.25f, 1f);
        pulso.setDuration(650);
        pulso.setRepeatMode(ValueAnimator.REVERSE);
        pulso.setRepeatCount(ValueAnimator.INFINITE);
        pulso.addUpdateListener(a -> actualizarNivel((float) a.getAnimatedValue()));
        pulso.start();
    }

    public void detenerPulsoAutomatico() {
        if (pulso != null) {
            pulso.cancel();
            pulso = null;
        }
        actualizarNivel(0f);
    }

    /**
     * El avatar "rebota" mientras Rumi habla de verdad (a diferencia del pulso de
     * los anillos, que es sobre si TÚ hablas). Es un efecto simple —escalar el
     * propio ícono, sin fotogramas nuevos— pero le da vida sin necesitar un GIF ni
     * dibujar una boca aparte, que con el arte actual (una sola imagen plana) sería
     * mucho más frágil de hacer bien.
     */
    public void iniciarAnimacionHabla() {
        detenerAnimacionHabla();
        rebote = ValueAnimator.ofFloat(0.88f, 1.08f);
        rebote.setDuration(220);
        rebote.setRepeatMode(ValueAnimator.REVERSE);
        rebote.setRepeatCount(ValueAnimator.INFINITE);
        rebote.addUpdateListener(a -> {
            if (circulo != null) {
                circulo.setEscalaAvatar((float) a.getAnimatedValue());
            }
        });
        rebote.start();
        // Los anillos acompañan con un pulso más suave que el del rebote del avatar.
        iniciarPulsoAutomatico();
    }

    public void detenerAnimacionHabla() {
        if (rebote != null) {
            rebote.cancel();
            rebote = null;
        }
        if (circulo != null) {
            circulo.setEscalaAvatar(1f);
        }
        detenerPulsoAutomatico();
    }

    /** Cierra el diálogo si sigue mostrado; no falla si ya se cerró solo. */
    public void cerrar() {
        detenerPulsoAutomatico();
        detenerAnimacionHabla();
        if (isAdded()) {
            dismissAllowingStateLoss();
        }
    }

    @Override
    public void onDestroyView() {
        detenerPulsoAutomatico();
        detenerAnimacionHabla();
        super.onDestroyView();
    }
}
