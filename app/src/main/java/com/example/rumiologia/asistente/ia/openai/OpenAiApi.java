package com.example.rumiologia.asistente.ia.openai;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;

/**
 * Interfaz Retrofit para interactuar con OpenAI Responses API.
 */
public interface OpenAiApi {

    String BASE_URL = "https://api.openai.com/";

    @POST("v1/responses")
    Call<OpenAiDto.Respuesta> generarRespuesta(
            @Header("Authorization") String autorizacion,
            @Body OpenAiDto.Peticion peticion);
}
