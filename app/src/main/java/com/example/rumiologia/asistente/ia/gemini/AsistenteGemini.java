package com.example.rumiologia.asistente.ia.gemini;

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
 * Implementación de {@link AsistenteIA} sobre la API REST de Gemini con File Search.
 *
 * <p>Esta clase concentra todo lo específico del proveedor: la URL, el modelo, el
 * almacén de fichas y la instrucción del sistema. El resto de la app no sabe que
 * detrás hay Gemini.
 *
 * <p>No hay servidor propio de por medio: la app habla directamente con Google. Eso
 * quita la dependencia de tener un PC encendido, a cambio de llevar la clave dentro
 * del APK — decisión consciente, documentada en {@code DecisionesMovil.md}.
 */
public class AsistenteGemini implements AsistenteIA {

    private static final String TAG = "AsistenteGemini";

    private static final String MODELO = "gemini-3.6-flash";

    /**
     * Almacén con las 7 fichas técnicas indexadas, cada una etiquetada con
     * {@code equipo=<slug>}. Se administra desde {@code gestion_almacenes/}.
     *
     * <p>Está fijo en el código a sabiendas: es un valor que no cambia. Si algún día
     * hace falta cambiarlo sin republicar la app, aquí es donde entraría una fuente
     * remota de configuración.
     */
    private static final String ALMACEN = "fileSearchStores/fichasrumiologia-keer2v9xyjnm";

    /** Clave del metadato con el que se etiquetaron las fichas al subirlas. */
    private static final String CLAVE_METADATO = "equipo";

    /** Turnos previos que se envían. Más historial encarece sin mejorar la respuesta. */
    private static final int MAX_TURNOS = 6;

    /**
     * Las reglas que impiden que el modelo invente.
     *
     * <p>No es un adorno: sin ellas, preguntando por la incubadora con el filtro
     * puesto en otro equipo, el modelo respondió con tiempos y un método de dos
     * etapas con pepsina que <b>no está en ninguna ficha</b>. Con ellas reconoce que
     * no tiene el dato.
     *
     * <p>Debe decir lo mismo que la de {@code gestion_almacenes/gestionar.py}, que se
     * usa para probar las consultas fuera de la app.
     */
    private static final String INSTRUCCION =
            "Eres el asistente del laboratorio de Rumiología. Ayudas a estudiantes a "
                    + "entender y operar los equipos del laboratorio.\n\n"
                    + "Respondes ÚNICAMENTE con la información de las fichas técnicas que la "
                    + "herramienta de búsqueda te proporciona.\n\n"
                    + "Reglas estrictas:\n"
                    + "- Si las fichas no contienen la respuesta, di: \"Esa información no está "
                    + "en las fichas técnicas del laboratorio.\" No la deduzcas, no la estimes, "
                    + "no recurras a tu conocimiento general sobre equipos parecidos.\n"
                    + "- Nunca inventes voltajes, temperaturas, capacidades, tiempos ni pasos de "
                    + "un procedimiento. Un dato erróneo sobre un equipo de laboratorio puede "
                    + "causar un accidente o arruinar un análisis.\n"
                    + "- Cuando la ficha incluya una advertencia de seguridad relacionada con lo "
                    + "que se pregunta, menciónala aunque no te la hayan pedido.\n"
                    + "- Di a qué equipo corresponde tu respuesta: el usuario puede no tenerlo "
                    + "delante.\n"
                    + "- Hay DOS estufas distintas (Estufa de Secado ANKOM y Estufa Universal "
                    + "MEMMERT). Si la pregunta dice solo \"la estufa\", pregunta a cuál se "
                    + "refiere antes de responder.\n"
                    + "- Responde en español, breve y directo, como a un estudiante.\n"
                    + "- Las respuestas se leen en voz alta: evita tablas y listas muy largas.";

    private final GeminiApi api;
    private final ProveedorClave proveedorClave;
    private final AlcanceConsulta alcance;
    private final Handler principal = new Handler(Looper.getMainLooper());

    public AsistenteGemini(@NonNull Context contexto, @NonNull ProveedorClave proveedorClave) {
        this.proveedorClave = proveedorClave;
        this.alcance = new AlcanceConsulta(contexto);

        // El modelo tarda varios segundos: los 10 s por defecto de OkHttp cortarían
        // respuestas válidas a medio generar.
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        this.api = new Retrofit.Builder()
                .baseUrl(GeminiApi.BASE_URL)
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GeminiApi.class);
    }

    @Override
    public void preguntar(String pregunta, String equipo, List<Turno> historial,
                          Respuesta callback) {

        String clave = proveedorClave.obtener();
        if (TextUtils.isEmpty(clave)) {
            callback.onError("Falta la clave de la API. Añádela en local.properties "
                    + "como GEMINI_API_KEY y vuelve a compilar.");
            return;
        }

        String equipoFiltro = alcance.equipoParaFiltrar(equipo, pregunta);
        Log.i(TAG, "Consulta" + (equipoFiltro != null
                ? " acotada a " + equipoFiltro
                : " sobre todas las fichas"));

        api.generar(MODELO, clave, construirPeticion(pregunta, equipoFiltro, historial))
                .enqueue(new Callback<GeminiDto.Respuesta>() {
                    @Override
                    public void onResponse(@NonNull Call<GeminiDto.Respuesta> c,
                                           @NonNull Response<GeminiDto.Respuesta> r) {
                        manejarRespuesta(r, callback);
                    }

                    @Override
                    public void onFailure(@NonNull Call<GeminiDto.Respuesta> c,
                                          @NonNull Throwable t) {
                        Log.e(TAG, "Fallo de red", t);
                        enHiloPrincipal(() -> callback.onError(
                                "No hay conexión con el asistente. Comprueba tu internet."));
                    }
                });
    }

    private GeminiDto.Peticion construirPeticion(String pregunta, @Nullable String equipoFiltro,
                                                 List<Turno> historial) {
        GeminiDto.Peticion peticion = new GeminiDto.Peticion();
        peticion.systemInstruction = GeminiDto.Contenido.sistema(INSTRUCCION);

        if (historial != null) {
            int desde = Math.max(0, historial.size() - MAX_TURNOS);
            for (int i = desde; i < historial.size(); i++) {
                Turno t = historial.get(i);
                peticion.contents.add(GeminiDto.Contenido.de(
                        t.esDelUsuario ? "user" : "model", t.texto));
            }
        }
        peticion.contents.add(GeminiDto.Contenido.de("user", pregunta));

        String filtro = equipoFiltro != null
                ? CLAVE_METADATO + "=\"" + equipoFiltro + "\""
                : null;
        peticion.tools.add(GeminiDto.Herramienta.fileSearch(ALMACEN, filtro));

        return peticion;
    }

    private void manejarRespuesta(Response<GeminiDto.Respuesta> r, Respuesta callback) {
        if (!r.isSuccessful() || r.body() == null) {
            String detalle = leerError(r);
            Log.e(TAG, "HTTP " + r.code() + ": " + detalle);
            enHiloPrincipal(() -> callback.onError(mensajeSegunCodigo(r.code(), detalle)));
            return;
        }

        GeminiDto.Respuesta cuerpo = r.body();
        if (cuerpo.error != null) {
            Log.e(TAG, "Error de la API: " + cuerpo.error.message);
            enHiloPrincipal(() -> callback.onError(cuerpo.error.message));
            return;
        }

        String texto = cuerpo.primerTexto();
        if (TextUtils.isEmpty(texto)) {
            enHiloPrincipal(() -> callback.onError("El asistente no devolvió respuesta."));
            return;
        }

        RespuestaAsistente respuesta = new RespuestaAsistente(texto, cuerpo.fuentes());
        enHiloPrincipal(() -> callback.onExito(respuesta));
    }

    /** Traduce el código HTTP a algo que el usuario pueda entender y accionar. */
    private String mensajeSegunCodigo(int codigo, String detalle) {
        switch (codigo) {
            case 400:
                return "La consulta fue rechazada. " + detalle;
            case 401:
            case 403:
                return "La clave de la API no es válida o no tiene permisos.";
            case 404:
                return "El modelo o el almacén de fichas no existe. " + detalle;
            case 429:
                return "Se agotó la cuota de la API. Inténtalo más tarde.";
            default:
                return codigo >= 500
                        ? "El servicio de Google no responde. Inténtalo de nuevo."
                        : "Error " + codigo + ". " + detalle;
        }
    }

    private String leerError(Response<?> r) {
        try {
            return r.errorBody() != null ? r.errorBody().string() : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Los callbacks de Retrofit ya llegan al hilo principal, pero no se da por hecho. */
    private void enHiloPrincipal(Runnable accion) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            accion.run();
        } else {
            principal.post(accion);
        }
    }
}
