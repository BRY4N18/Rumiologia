package com.example.rumiologia.asistente;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.rumiologia.R;
import com.example.rumiologia.ajustes.AjustesActivity;
import com.example.rumiologia.asistente.ia.AlmacenClaves;
import com.example.rumiologia.asistente.ia.AsistenteIA;
import com.example.rumiologia.asistente.ia.FabricaAsistente;
import com.example.rumiologia.asistente.ia.RespuestaAsistente;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pantalla de chat con el asistente del laboratorio.
 *
 * <p>Admite dos formas de entrada sobre el mismo flujo: escribir y hablar. La voz
 * no es un camino aparte — es una capa fina encima: {@link SpeechRecognizer}
 * convierte lo dicho en texto y a partir de ahí todo es idéntico. La respuesta
 * puede leerse en voz alta con {@link TextToSpeech}.
 *
 * <p>Se puede abrir de dos maneras, y la diferencia importa:
 * <ul>
 *   <li><b>Desde una detección</b> — llega el slug del equipo, y el backend lo usa
 *       como pista para desambiguar preguntas como "¿cómo lo enciendo?".</li>
 *   <li><b>Suelta</b> — sin equipo. Es el caso que justifica la búsqueda semántica:
 *       el usuario pregunta sin tener el aparato delante.</li>
 * </ul>
 */
public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "ChatActivity";
    private static final String EXTRA_EQUIPO = "equipo";
    private static final String EXTRA_NOMBRE = "nombre";

    /** Abre el chat asociado a un equipo detectado. */
    public static Intent intentPara(Context contexto, String slugEquipo, String nombreEquipo) {
        Intent i = new Intent(contexto, ChatActivity.class);
        i.putExtra(EXTRA_EQUIPO, slugEquipo);
        i.putExtra(EXTRA_NOMBRE, nombreEquipo);
        return i;
    }

    private RecyclerView lista;
    private EditText entrada;
    private ImageButton botonEnviar;
    private ImageButton botonMicrofono;
    private ImageButton botonVoz;
    private TextView subtitulo;
    private View bannerSinClave;

    private final ActivityResultLauncher<Intent> lanzadorAjustes =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    resultado -> revisarClave());

    private final List<Mensaje> mensajes = new ArrayList<>();
    private ChatAdapter adaptador;

    private String slugEquipo;
    private String nombreEquipo;

    /** Se pide a la fábrica: esta pantalla no sabe qué proveedor hay detrás. */
    private AsistenteIA asistente;

    private SpeechRecognizer reconocedor;
    private TextToSpeech sintetizador;
    private boolean lecturaEnVozAlta = false;
    private boolean escuchando = false;

    /** Pantalla del círculo animado; solo existe mientras se está escuchando. */
    private DialogoVoz dialogoVoz;

    private final ActivityResultLauncher<String> pedirPermisoAudio =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), concedido -> {
                if (concedido) {
                    alternarEscucha();
                } else {
                    Toast.makeText(this, R.string.chat_permiso_audio, Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        slugEquipo = getIntent().getStringExtra(EXTRA_EQUIPO);
        nombreEquipo = getIntent().getStringExtra(EXTRA_NOMBRE);

        lista = findViewById(R.id.listaMensajes);
        entrada = findViewById(R.id.entradaTexto);
        botonEnviar = findViewById(R.id.botonEnviar);
        botonMicrofono = findViewById(R.id.botonMicrofono);
        botonVoz = findViewById(R.id.botonVoz);
        subtitulo = findViewById(R.id.chatSubtitulo);
        bannerSinClave = findViewById(R.id.chatBannerSinClave);
        findViewById(R.id.chatBannerBoton).setOnClickListener(v ->
                lanzadorAjustes.launch(new Intent(this, AjustesActivity.class)));

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chatRaiz), (v, insets) -> {
            Insets barras = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(barras.left, barras.top, barras.right, barras.bottom);
            return insets;
        });

        adaptador = new ChatAdapter(this, mensajes);
        LinearLayoutManager gestor = new LinearLayoutManager(this);
        gestor.setStackFromEnd(true);      // los mensajes nuevos quedan abajo, a la vista
        lista.setLayoutManager(gestor);
        lista.setAdapter(adaptador);

        subtitulo.setText(nombreEquipo != null
                ? getString(R.string.chat_sobre_equipo, nombreEquipo)
                : getString(R.string.chat_general));

        botonEnviar.setOnClickListener(v -> enviarLoEscrito());
        botonMicrofono.setOnClickListener(v -> pedirEscucha());
        botonVoz.setOnClickListener(v -> alternarLectura());

        asistente = FabricaAsistente.crear(this);

        prepararSintetizador();
        mostrarBienvenida();
        revisarClave();
    }

    @Override
    protected void onResume() {
        super.onResume();
        revisarClave();
    }

    /**
     * Muestra el aviso para configurar la clave en Ajustes y bloquea la entrada si
     * no hay ninguna clave de Gemini guardada — desde que se quitó la clave
     * compilada, esa es la única fuente. Se revisa también al volver de Ajustes,
     * por si se acaba de guardar una.
     */
    private void revisarClave() {
        boolean hayClave = AlmacenClaves.hayClaveDisponible(this);
        bannerSinClave.setVisibility(hayClave ? View.GONE : View.VISIBLE);
        habilitarEntrada(hayClave);
    }

    // ------------------------------------------------------------------ envío

    private void enviarLoEscrito() {
        String texto = entrada.getText().toString().trim();
        if (TextUtils.isEmpty(texto)) {
            return;
        }
        entrada.setText("");
        enviar(texto);
    }

    private void enviar(String pregunta) {
        añadir(Mensaje.deUsuario(pregunta));

        // El historial se arma ANTES de meter el marcador de carga y excluye la
        // pregunta actual, que viaja aparte.
        List<AsistenteIA.Turno> historial = new ArrayList<>();
        for (Mensaje m : mensajes) {
            if (m.cargando || m.origen == Mensaje.Origen.ERROR || m.texto.isEmpty()) {
                continue;
            }
            historial.add(new AsistenteIA.Turno(m.origen == Mensaje.Origen.USUARIO, m.texto));
        }
        if (!historial.isEmpty()) {
            historial.remove(historial.size() - 1);
        }

        Mensaje cargando = Mensaje.cargando();
        añadir(cargando);
        habilitarEntrada(false);

        asistente.preguntar(pregunta, slugEquipo, historial, new AsistenteIA.Respuesta() {
            @Override
            public void onExito(RespuestaAsistente resultado) {
                quitar(cargando);
                habilitarEntrada(true);

                Mensaje respuesta = new Mensaje(Mensaje.Origen.ASISTENTE, resultado.texto);
                respuesta.fuentes = resultado.fuentes;
                añadir(respuesta);

                // Dentro del modo de voz, Rumi siempre responde hablado: es la razón de
                // estar ahí. Fuera de él, solo si el usuario activó "leer en voz alta".
                if (dialogoVoz != null) {
                    dialogoVoz.mostrarTextoReconocido(respuesta.texto);
                    dialogoVoz.mostrarEstado(R.string.voz_hablando);
                    dialogoVoz.iniciarAnimacionHabla();
                    leer(respuesta.texto);
                } else if (lecturaEnVozAlta) {
                    leer(respuesta.texto);
                }
            }

            @Override
            public void onError(String mensaje) {
                quitar(cargando);
                habilitarEntrada(true);
                mostrarError(mensaje);
                if (dialogoVoz != null) {
                    dialogoVoz.detenerPulsoAutomatico();
                    dialogoVoz.mostrarEstado(R.string.voz_toca_para_hablar);
                }
            }
        });
    }

    private void mostrarBienvenida() {
        Mensaje bienvenida = new Mensaje(Mensaje.Origen.ASISTENTE,
                nombreEquipo != null
                        ? getString(R.string.chat_bienvenida_equipo, nombreEquipo)
                        : getString(R.string.chat_bienvenida));
        añadir(bienvenida);
    }

    private void mostrarError(String texto) {
        añadir(new Mensaje(Mensaje.Origen.ERROR, texto));
    }

    private void añadir(Mensaje m) {
        mensajes.add(m);
        adaptador.notifyItemInserted(mensajes.size() - 1);
        lista.scrollToPosition(mensajes.size() - 1);
    }

    private void quitar(Mensaje m) {
        int i = mensajes.indexOf(m);
        if (i >= 0) {
            mensajes.remove(i);
            adaptador.notifyItemRemoved(i);
        }
    }

    private void habilitarEntrada(boolean habilitada) {
        entrada.setEnabled(habilitada);
        botonEnviar.setEnabled(habilitada);
        botonMicrofono.setEnabled(habilitada);
    }

    // ------------------------------------------------------------- voz: entrada

    private void pedirEscucha() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            alternarEscucha();
        } else {
            pedirPermisoAudio.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void alternarEscucha() {
        if (escuchando) {
            detenerReconocimiento();
            return;
        }
        iniciarEscucha();
    }

    /**
     * Empieza a escuchar. Se llama al tocar el micrófono la primera vez, y también
     * al tocar el círculo del diálogo de voz para seguir la conversación sin volver
     * a la pantalla de texto — el diálogo ya existente se reutiliza, no se recrea.
     */
    private void iniciarEscucha() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.chat_sin_reconocimiento, Toast.LENGTH_LONG).show();
            return;
        }

        if (reconocedor == null) {
            reconocedor = SpeechRecognizer.createSpeechRecognizer(this);
            reconocedor.setRecognitionListener(new EscuchaSimple());
        } else {
            // Si la sesión anterior no llegó a cerrarse del todo, arrancar sin
            // limpiar primero produce "no se pudo reconocer la voz" con un
            // reconocedor ocupado. cancel() descarta cualquier resto pendiente.
            reconocedor.cancel();
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        escuchando = true;
        botonMicrofono.setSelected(true);
        entrada.setHint(R.string.chat_escuchando);
        mostrarDialogoVoz();
        reconocedor.startListening(intent);
    }

    /**
     * Deja de escuchar, pero el diálogo de voz sigue abierto: ahí sigue la conversación.
     *
     * <p>La guarda de {@code !escuchando} al principio no es cosmética: en algunos
     * dispositivos (confirmado con logcat en uno Infinix/Transsion), llamar a
     * {@code stopListening()} cuando no hay una sesión activa hace que el propio
     * reconocedor dispare {@code onError()} de inmediato — y como {@code onError()}
     * también llama aquí, sin la guarda se entra en una recursión que satura el hilo
     * principal con cientos de toasts por segundo y cierra la app
     * (visto como "#stopListening called with no preceding #startListening" repetido
     * sin parar en el log). Con la guarda, la segunda llamada no hace nada.
     */
    private void detenerReconocimiento() {
        if (!escuchando) {
            return;
        }
        escuchando = false;
        botonMicrofono.setSelected(false);
        entrada.setHint(R.string.chat_hint);
        if (reconocedor != null) {
            reconocedor.stopListening();
        }
    }

    /** Cierra por completo el modo de voz: botón "Cancelar" del diálogo, o volver atrás. */
    private void cerrarModoVoz() {
        detenerReconocimiento();
        if (sintetizador != null) {
            sintetizador.stop();
        }
        if (dialogoVoz != null) {
            dialogoVoz.cerrar();
            dialogoVoz = null;
        }
    }

    /**
     * Crea el diálogo del círculo animado solo la primera vez; en los turnos
     * siguientes de la misma conversación se reutiliza el mismo, para que la
     * persona se quede ahí en vez de volver a la pantalla de texto cada vez.
     */
    private void mostrarDialogoVoz() {
        if (dialogoVoz == null) {
            dialogoVoz = new DialogoVoz();
            dialogoVoz.setOyente(new DialogoVoz.Oyente() {
                @Override
                public void onCancelarVoz() {
                    cerrarModoVoz();
                }

                @Override
                public void onTocarCirculo() {
                    if (!escuchando) {
                        iniciarEscucha();
                    }
                }
            });
            dialogoVoz.show(getSupportFragmentManager(), "DialogoVoz");
        }
        dialogoVoz.mostrarEstado(R.string.chat_escuchando);
    }

    /** Escribe en el cuadro de texto lo que se va reconociendo y envía al terminar. */
    private class EscuchaSimple implements RecognitionListener {

        @Override
        public void onPartialResults(Bundle parcial) {
            List<String> textos = parcial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (textos != null && !textos.isEmpty()) {
                entrada.setText(textos.get(0));
                if (dialogoVoz != null) {
                    dialogoVoz.mostrarTextoReconocido(textos.get(0));
                }
            }
        }

        @Override
        public void onResults(Bundle resultados) {
            detenerReconocimiento();
            List<String> textos = resultados.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (textos != null && !textos.isEmpty()) {
                entrada.setText(textos.get(0));
                if (dialogoVoz != null) {
                    dialogoVoz.mostrarEstado(R.string.voz_pensando);
                    dialogoVoz.iniciarPulsoAutomatico();
                }
                enviarLoEscrito();     // hablar y enviar es un solo gesto
            } else if (dialogoVoz != null) {
                dialogoVoz.mostrarEstado(R.string.voz_toca_para_hablar);
            }
        }

        @Override
        public void onError(int codigo) {
            detenerReconocimiento();
            // NO_MATCH y SPEECH_TIMEOUT son normales si el usuario no dijo nada:
            // avisar en esos casos sería ruido.
            if (codigo != SpeechRecognizer.ERROR_NO_MATCH
                    && codigo != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                Toast.makeText(ChatActivity.this,
                        getString(R.string.chat_error_voz, codigo), Toast.LENGTH_SHORT).show();
            }
            if (dialogoVoz != null) {
                dialogoVoz.mostrarEstado(R.string.voz_toca_para_hablar);
            }
        }

        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }

        @Override
        public void onRmsChanged(float rms) {
            // rms suele moverse entre -2 (silencio) y 10 (fuerte); se normaliza a 0..1
            // para que el circulo sepa cuanto separar los anillos.
            if (dialogoVoz != null) {
                dialogoVoz.actualizarNivel((rms + 2f) / 10f);
            }
        }

        @Override public void onBufferReceived(byte[] buffer) { }

        @Override
        public void onEndOfSpeech() {
            if (dialogoVoz != null) {
                dialogoVoz.mostrarEstado(R.string.voz_analizando);
            }
        }

        @Override public void onEvent(int tipo, Bundle params) { }
    }

    // -------------------------------------------------------------- voz: salida

    private void prepararSintetizador() {
        sintetizador = new TextToSpeech(this, estado -> {
            if (estado == TextToSpeech.SUCCESS) {
                int r = sintetizador.setLanguage(new Locale("es", "ES"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Voz en español no disponible en este dispositivo");
                    botonVoz.setVisibility(View.GONE);
                }
                // Si la persona eligió una voz en Ajustes, se usa esa en vez de la
                // que el sistema puso por defecto al fijar el idioma.
                VozRumi.aplicarGuardada(this, sintetizador);
            } else {
                Log.w(TAG, "No se pudo iniciar TextToSpeech");
                botonVoz.setVisibility(View.GONE);
            }
        });

        // Cuando Rumi termina de hablar dentro del modo de voz, el círculo vuelve a
        // reposo con la invitación a tocarlo para seguir. Los callbacks de
        // UtteranceProgressListener llegan en un hilo aparte, no en el principal.
        sintetizador.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }

            @Override
            public void onDone(String utteranceId) {
                runOnUiThread(ChatActivity.this::volverAReposoDeVoz);
            }

            @Override
            public void onError(String utteranceId) {
                runOnUiThread(ChatActivity.this::volverAReposoDeVoz);
            }
        });
    }

    private void volverAReposoDeVoz() {
        if (dialogoVoz != null) {
            dialogoVoz.detenerAnimacionHabla();
            dialogoVoz.mostrarEstado(R.string.voz_toca_para_hablar);
        }
    }

    private void alternarLectura() {
        lecturaEnVozAlta = !lecturaEnVozAlta;
        botonVoz.setSelected(lecturaEnVozAlta);
        Toast.makeText(this,
                lecturaEnVozAlta ? R.string.chat_voz_activada : R.string.chat_voz_desactivada,
                Toast.LENGTH_SHORT).show();

        if (!lecturaEnVozAlta && sintetizador != null) {
            sintetizador.stop();
        }
    }

    private void leer(String texto) {
        if (sintetizador != null && texto != null && !texto.isEmpty()) {
            sintetizador.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "respuesta");
        }
    }

    // ------------------------------------------------------------- ciclo de vida

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Ambos mantienen recursos nativos y conexiones a servicios del sistema
        // que no libera el recolector de basura de Java.
        if (reconocedor != null) {
            reconocedor.destroy();
        }
        if (sintetizador != null) {
            sintetizador.stop();
            sintetizador.shutdown();
        }
    }
}
