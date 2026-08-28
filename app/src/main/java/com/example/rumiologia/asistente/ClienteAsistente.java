package com.example.rumiologia.asistente;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Punto único de construcción del cliente HTTP.
 *
 * <p>Crear un {@link Retrofit} nuevo en cada pantalla desperdiciaría el pool de
 * conexiones y los hilos de OkHttp. Aquí se construye una sola vez y se reutiliza.
 */
public final class ClienteAsistente {

    /**
     * Dirección del backend.
     *
     * <p><b>10.0.2.2 es el atajo del emulador de Android para llegar al "localhost"
     * del PC anfitrión.</b> Desde el emulador, {@code 127.0.0.1} apunta al propio
     * teléfono virtual, no a tu máquina, y la conexión fallaría.
     *
     * <p>Para probar en un teléfono físico, cámbialo por la IP de tu PC en la red
     * local (por ejemplo {@code http://192.168.1.50:8000/}) y asegúrate de que
     * ambos estén en la misma red WiFi.
     */
    public static final String URL_BASE = "http://10.0.2.2:8000/";

    private static AsistenteApi instancia;

    private ClienteAsistente() {
    }

    public static synchronized AsistenteApi api() {
        if (instancia == null) {
            // El modelo tarda unos segundos en responder: los tiempos de espera
            // por defecto de OkHttp (10 s) se quedan cortos y cortarían respuestas
            // válidas a medio generar.
            OkHttpClient http = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();

            instancia = new Retrofit.Builder()
                    .baseUrl(URL_BASE)
                    .client(http)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(AsistenteApi.class);
        }
        return instancia;
    }
}
