package com.example.rumiologia.asistente.ia.gemini;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Interfaz Retrofit contra la API REST de Gemini.
 *
 * <p>Se usa REST y no el SDK de Android por un motivo comprobado: ni
 * {@code com.google.firebase:firebase-ai} ni
 * {@code com.google.ai.client.generativeai} exponen la herramienta File Search.
 * Su clase {@code Tool} solo ofrece funciones, ejecución de código, contexto de URL,
 * Google Search y Google Maps. Sin File Search no hay RAG, y sin RAG el asistente
 * respondería sobre equipos genéricos de internet en vez de sobre las fichas del
 * laboratorio.
 *
 * <p>La clave viaja en la cabecera {@code x-goog-api-key} y no en la URL: en la URL
 * acabaría escrita en los registros de cualquier proxy intermedio.
 */
public interface GeminiApi {

    String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/";

    @POST("models/{modelo}:generateContent")
    Call<GeminiDto.Respuesta> generar(
            @Path("modelo") String modelo,
            @Header("x-goog-api-key") String clave,
            @Body GeminiDto.Peticion peticion);
}
