package com.example.rumiologia.ajustes;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ImageViewCompat;

import com.example.rumiologia.R;
import com.example.rumiologia.TemaApp;
import com.example.rumiologia.asistente.VozRumi;
import com.example.rumiologia.asistente.ia.AlmacenClaves;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pantalla de ajustes: la clave de Gemini de cada persona y, aparte, qué voz usa
 * Rumi para hablar.
 *
 * <p>La clave se cifra en el dispositivo antes de guardarse ({@link AlmacenClaves})
 * y nunca se muestra ni se registra en texto plano fuera de este formulario.
 */
public class AjustesActivity extends AppCompatActivity {

    private EditText entradaClave;
    private TextView textoEstado;
    private View puntoEstado;

    private boolean claveVisible = false;

    private LinearLayout listaVoces;
    private TextToSpeech sintetizadorPrueba;
    private final List<RadioButton> botonesVoz = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ajustes);

        entradaClave = findViewById(R.id.ajustesEntradaClave);
        textoEstado = findViewById(R.id.ajustesTextoEstado);
        puntoEstado = findViewById(R.id.ajustesPuntoEstado);
        listaVoces = findViewById(R.id.ajustesListaVoces);
        ImageButton botonMostrar = findViewById(R.id.ajustesBotonMostrar);
        TextView botonGuardar = findViewById(R.id.ajustesBotonGuardar);
        TextView botonQuitar = findViewById(R.id.ajustesBotonQuitar);

        findViewById(R.id.ajustesBotonAtras).setOnClickListener(v -> finish());
        botonMostrar.setOnClickListener(v -> alternarVisibilidad());
        botonGuardar.setOnClickListener(v -> guardarClave());
        botonQuitar.setOnClickListener(v -> quitarClave());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.ajustesRaiz), (v, insets) -> {
            Insets barras = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(barras.left, barras.top, barras.right, barras.bottom);
            return insets;
        });

        actualizarEstado();
        prepararVoces();
        prepararTema();
    }

    // -------------------------------------------------------------- clave

    private void alternarVisibilidad() {
        claveVisible = !claveVisible;
        int cursor = entradaClave.getSelectionStart();
        entradaClave.setInputType(claveVisible
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        entradaClave.setSelection(Math.min(cursor, entradaClave.getText().length()));
    }

    private void guardarClave() {
        String texto = entradaClave.getText().toString().trim();
        if (TextUtils.isEmpty(texto)) {
            Toast.makeText(this, R.string.ajustes_clave_vacia, Toast.LENGTH_SHORT).show();
            return;
        }
        AlmacenClaves.guardarLocal(this, texto);
        entradaClave.setText("");
        Toast.makeText(this, R.string.ajustes_guardado_ok, Toast.LENGTH_SHORT).show();
        actualizarEstado();
    }

    private void quitarClave() {
        AlmacenClaves.borrarLocal(this);
        Toast.makeText(this, R.string.ajustes_quitada_ok, Toast.LENGTH_SHORT).show();
        actualizarEstado();
    }

    private void actualizarEstado() {
        boolean configurada = AlmacenClaves.tieneClaveLocal(this);
        textoEstado.setText(configurada
                ? R.string.ajustes_estado_configurada
                : R.string.ajustes_estado_sin_clave);
        puntoEstado.setBackgroundResource(configurada
                ? R.drawable.punto_estado_verde
                : R.drawable.punto_estado_gris);
    }

    // -------------------------------------------------------------- voz

    /**
     * Las voces disponibles dependen del teléfono, así que hace falta un
     * {@link TextToSpeech} propio ya inicializado antes de poder listarlas —
     * {@link TextToSpeech#getVoices()} no tiene nada hasta que el motor conecta.
     */
    private void prepararVoces() {
        sintetizadorPrueba = new TextToSpeech(this, estado -> {
            if (estado == TextToSpeech.SUCCESS) {
                sintetizadorPrueba.setLanguage(new Locale("es", "ES"));
                construirListaVoces();
            } else {
                mostrarSinVoces();
            }
        });
    }

    private void construirListaVoces() {
        List<Voice> voces = VozRumi.disponibles(sintetizadorPrueba);
        listaVoces.removeAllViews();
        botonesVoz.clear();

        if (voces.isEmpty()) {
            mostrarSinVoces();
            return;
        }

        String vozGuardada = VozRumi.leer(this);
        int numeroEspana = 1;
        int numeroLatam = 1;
        for (Voice voz : voces) {
            boolean esEspana = "ES".equals(voz.getLocale().getCountry());
            String etiqueta = esEspana
                    ? getString(R.string.ajustes_voz_espana, numeroEspana++)
                    : getString(R.string.ajustes_voz_latam, numeroLatam++);
            agregarFilaVoz(voz, etiqueta, voz.getName().equals(vozGuardada));
        }
    }

    private void agregarFilaVoz(Voice voz, String etiqueta, boolean seleccionada) {
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        fila.setGravity(Gravity.CENTER_VERTICAL);
        fila.setPadding(dp(4), dp(4), dp(4), dp(4));

        RadioButton boton = new RadioButton(this);
        boton.setText(etiqueta);
        boton.setChecked(seleccionada);
        boton.setTextColor(getColor(R.color.texto_principal));
        boton.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boton.setOnClickListener(v -> seleccionarVoz(voz, boton));
        botonesVoz.add(boton);

        ImageButton botonProbar = new ImageButton(this);
        botonProbar.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        botonProbar.setImageResource(android.R.drawable.ic_media_play);
        botonProbar.setBackgroundResource(0);
        botonProbar.setContentDescription(getString(R.string.ajustes_voz_desc_probar));
        ImageViewCompat.setImageTintList(botonProbar,
                ColorStateList.valueOf(getColor(R.color.verde_institucional)));
        botonProbar.setOnClickListener(v -> probarVoz(voz));

        fila.addView(boton);
        fila.addView(botonProbar);
        listaVoces.addView(fila);
    }

    private void seleccionarVoz(Voice voz, RadioButton botonElegido) {
        for (RadioButton b : botonesVoz) {
            b.setChecked(b == botonElegido);
        }
        VozRumi.guardar(this, voz.getName());
    }

    private void probarVoz(Voice voz) {
        sintetizadorPrueba.setVoice(voz);
        sintetizadorPrueba.speak(getString(R.string.ajustes_voz_muestra),
                TextToSpeech.QUEUE_FLUSH, null, "prueba_voz");
    }

    private void mostrarSinVoces() {
        listaVoces.removeAllViews();
        TextView aviso = new TextView(this);
        aviso.setText(R.string.ajustes_voz_sin_opciones);
        aviso.setTextColor(getColor(R.color.texto_secundario));
        aviso.setPadding(dp(8), dp(8), dp(8), dp(8));
        listaVoces.addView(aviso);
    }

    // -------------------------------------------------------------- tema

    private void prepararTema() {
        RadioGroup grupoTema = findViewById(R.id.ajustesGrupoTema);
        RadioButton temaClaro = findViewById(R.id.ajustesTemaClaro);
        RadioButton temaOscuro = findViewById(R.id.ajustesTemaOscuro);
        RadioButton temaSistema = findViewById(R.id.ajustesTemaSistema);

        int modoActual = TemaApp.leer(this);
        if (modoActual == AppCompatDelegate.MODE_NIGHT_NO) {
            temaClaro.setChecked(true);
        } else if (modoActual == AppCompatDelegate.MODE_NIGHT_YES) {
            temaOscuro.setChecked(true);
        } else {
            temaSistema.setChecked(true);
        }

        grupoTema.setOnCheckedChangeListener((grupo, idSeleccionado) -> {
            int modo;
            if (idSeleccionado == R.id.ajustesTemaClaro) {
                modo = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (idSeleccionado == R.id.ajustesTemaOscuro) {
                modo = AppCompatDelegate.MODE_NIGHT_YES;
            } else {
                modo = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
            TemaApp.guardar(this, modo);
            recreate();
        });
    }

    private int dp(int valor) {
        return Math.round(valor * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sintetizadorPrueba != null) {
            sintetizadorPrueba.stop();
            sintetizadorPrueba.shutdown();
        }
    }
}
