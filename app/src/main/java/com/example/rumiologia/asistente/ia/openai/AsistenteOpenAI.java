package com.example.rumiologia.asistente.ia.openai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.rumiologia.asistente.ia.AlcanceConsulta;
import com.example.rumiologia.asistente.ia.AsistenteIA;
import com.example.rumiologia.asistente.ia.ProveedorClave;
import com.example.rumiologia.asistente.ia.RespuestaAsistente;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Implementación de {@link AsistenteIA} usando OpenAI Responses API (/v1/responses).
 *
 * <p>Integra el modelo o4-mini con esfuerzo de razonamiento bajo, Vector Store
 * con búsqueda semántica en las fichas técnicas (file_search) y búsqueda web oficial
 * como respaldo (web_search).
 */
public class AsistenteOpenAI implements AsistenteIA {

    private static final String TAG = "AsistenteOpenAI";
    private static final String MODELO = "o4-mini";
    private static final String VECTOR_STORE_ID = "vs_6aa204ead5088191bbf9db1aad9ce209";
    private static final int MAX_TURNOS = 6;

    private static final String INSTRUCCION_BASE =
            "Eres el asistente del laboratorio de Rumiología de la Universidad Técnica Estatal de Quevedo (UTEQ). "
                    + "Ayudas a estudiantes y docentes a entender y operar los equipos del laboratorio.\n\n"
                    + "Prioridad de búsqueda y respuestas:\n"
                    + "1. Busca primero en las fichas técnicas del laboratorio (file_search).\n"
                    + "2. Si la información técnica o especificación no se encuentra en las fichas, utiliza la "
                    + "búsqueda web oficial de los fabricantes (web_search) para complementar la respuesta.\n"
                    + "3. Seguridad primero: Si la consulta involucra temperaturas altas, solventes, reactivos o "
                    + "partes móviles, menciona siempre las precauciones de seguridad pertinentes.\n"
                    + "4. Responde en español, de forma clara, directa y estructurada para ser leída por voz (TTS). "
                    + "Evita tablas complejas o listas excesivamente largas.\n"
                    + "5. Menciona a qué equipo corresponde tu respuesta.";

    private static final String INSTRUCCION_DESAMBIGUAR_ESTUFAS =
            "\n- Hay DOS estufas distintas en el laboratorio: la 'Estufa de Secado ANKOM' y la 'Estufa Universal "
                    + "MEMMERT'. Si la pregunta se refiere genéricamente a 'la estufa' sin especificar cuál, "
                    + "pregunta a cuál se refiere o resume brevemente la distinción.";

    private static final String INSTRUCCION_EQUIPO_YA_SELECCIONADO =
            "\n- La consulta está enfocada en el equipo seleccionado: %s. Responde directamente sobre este equipo "
                    + "sin preguntar a cuál se refiere, incluso si el usuario dice 'este equipo' o 'el aparato'.";

    private static final String INSTRUCCION_MODO_VOZ =
            "\n\n--- INSTRUCCIONES ESTRICTAS PARA MODO VOZ HABLADA (TTS) ---\n"
                    + "- La respuesta será reproducida por voz (TTS). El usuario está escuchando la conversación, NO leyendo un documento largo.\n"
                    + "- REGLA FUNDAMENTAL DE BREVEDAD: Responde de forma ULTRA CONCISA, directa y conversacional en máximo 2 a 3 oraciones cortas (menos de 45 palabras en total).\n"
                    + "- NUNCA recites manuales completos, listas largas de pasos, tablas ni texto denso. Ve directo al grano con lo que se preguntó.\n"
                    + "- Si el usuario pide información general de un equipo o para qué sirve, describe su función esencial en una oración y pregunta amablemente: '¿Deseas conocer su modo de uso o precauciones de seguridad?'.\n"
                    + "- Prohibido usar asteriscos de markdown (**), encabezados (##) o viñetas, ya que entorpecen la pronunciación por voz.";

    private static final String INSTRUCCION_MODO_TEXTO =
            "\n\n--- MODO CHAT ESCRITO ---\n"
                    + "- Responde de forma clara, directa y estructurada con Markdown.\n"
                    + "- Sé conciso y no abrumes con texto excesivo, pero puedes usar viñetas breves si la explicación lo amerita.";

    private final OpenAiApi api;
    private final ProveedorClave proveedorClave;
    private final AlcanceConsulta alcance;
    private final Handler principal = new Handler(Looper.getMainLooper());

    public AsistenteOpenAI(@NonNull Context contexto, @NonNull ProveedorClave proveedorClave) {
        this.proveedorClave = proveedorClave;
        this.alcance = new AlcanceConsulta(contexto);

        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        this.api = new Retrofit.Builder()
                .baseUrl(OpenAiApi.BASE_URL)
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OpenAiApi.class);
    }

    @Override
    public void preguntar(String pregunta, String equipo, List<Turno> historial, Respuesta callback) {
        preguntar(pregunta, equipo, historial, false, callback);
    }

    @Override
    public void preguntar(String pregunta, String equipo, List<Turno> historial, boolean modoVoz, Respuesta callback) {
        String clave = proveedorClave.obtener();
        if (TextUtils.isEmpty(clave)) {
            callback.onError("Falta la clave de OpenAI. Configúrala en Ajustes para poder hablar con Rumi.");
            return;
        }

        String equipoFiltro = alcance.equipoParaFiltrar(equipo, pregunta);
        Log.i(TAG, "Consulta con OpenAI (" + (modoVoz ? "MODO VOZ" : "MODO TEXTO") + ")"
                + (equipoFiltro != null ? " enfocada en " + equipoFiltro : " general"));

        String bearer = "Bearer " + clave.trim();
        OpenAiDto.Peticion peticion = construirPeticion(pregunta, equipoFiltro, historial, modoVoz);

        api.generarRespuesta(bearer, peticion).enqueue(new Callback<OpenAiDto.Respuesta>() {
            @Override
            public void onResponse(@NonNull Call<OpenAiDto.Respuesta> call,
                                   @NonNull Response<OpenAiDto.Respuesta> response) {
                manejarRespuesta(response, callback);
            }

            @Override
            public void onFailure(@NonNull Call<OpenAiDto.Respuesta> call, @NonNull Throwable t) {
                Log.e(TAG, "Fallo de conexión", t);
                enHiloPrincipal(() -> callback.onError("No hay conexión con OpenAI. Comprueba tu internet."));
            }
        });
    }

    private OpenAiDto.Peticion construirPeticion(String pregunta, @Nullable String equipoFiltro,
                                                 List<Turno> historial, boolean modoVoz) {
        OpenAiDto.Peticion peticion = new OpenAiDto.Peticion();
        peticion.model = MODELO;
        peticion.reasoning = new OpenAiDto.Reasoning("low");

        String instruccion = INSTRUCCION_BASE
                + (equipoFiltro != null
                        ? String.format(INSTRUCCION_EQUIPO_YA_SELECCIONADO, equipoFiltro)
                        : INSTRUCCION_DESAMBIGUAR_ESTUFAS)
                + (modoVoz ? INSTRUCCION_MODO_VOZ : INSTRUCCION_MODO_TEXTO);
        peticion.instructions = instruccion;

        if (historial != null) {
            int desde = Math.max(0, historial.size() - MAX_TURNOS);
            for (int i = desde; i < historial.size(); i++) {
                Turno t = historial.get(i);
                peticion.input.add(new OpenAiDto.MensajeInput(t.esDelUsuario ? "user" : "assistant", t.texto));
            }
        }
        peticion.input.add(new OpenAiDto.MensajeInput("user", pregunta));

        // Herramientas: file_search con vector store y web_search de respaldo
        peticion.tools.add(OpenAiDto.Herramienta.fileSearch(VECTOR_STORE_ID));
        peticion.tools.add(OpenAiDto.Herramienta.webSearch());

        return peticion;
    }

    private void manejarRespuesta(Response<OpenAiDto.Respuesta> r, Respuesta callback) {
        if (!r.isSuccessful() || r.body() == null) {
            String detalle = leerError(r);
            Log.e(TAG, "HTTP " + r.code() + ": " + detalle);
            enHiloPrincipal(() -> callback.onError(mensajeSegunCodigo(r.code(), detalle)));
            return;
        }

        OpenAiDto.Respuesta cuerpo = r.body();
        if (cuerpo.error != null) {
            Log.e(TAG, "Error de OpenAI: " + cuerpo.error.message);
            enHiloPrincipal(() -> callback.onError(cuerpo.error.message));
            return;
        }

        String texto = cuerpo.obtenerTexto();
        if (TextUtils.isEmpty(texto)) {
            enHiloPrincipal(() -> callback.onError("Rumi no devolvió respuesta."));
            return;
        }

        RespuestaAsistente respuesta = new RespuestaAsistente(texto, cuerpo.obtenerFuentes());
        enHiloPrincipal(() -> callback.onExito(respuesta));
    }

    private String mensajeSegunCodigo(int codigo, String detalle) {
        switch (codigo) {
            case 400:
                return "La consulta fue rechazada por OpenAI. " + detalle;
            case 401:
            case 403:
                return "La clave de OpenAI no es válida o está vencida. Configúrala en Ajustes.";
            case 404:
                return "El modelo o el Vector Store de OpenAI no fue encontrado.";
            case 429:
                return "Se agotó la cuota de OpenAI o se excedió el límite de peticiones.";
            default:
                return codigo >= 500
                        ? "Los servidores de OpenAI no responden en este momento. Inténtalo más tarde."
                        : "Error " + codigo + " de OpenAI. " + detalle;
        }
    }

    private String leerError(Response<?> r) {
        try {
            return r.errorBody() != null ? r.errorBody().string() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void enHiloPrincipal(Runnable accion) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            accion.run();
        } else {
            principal.post(accion);
        }
    }
}
