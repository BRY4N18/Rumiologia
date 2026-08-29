package com.example.rumiologia;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Comprueba si el dispositivo tiene internet de verdad.
 *
 * <p>El matiz importante: **estar conectado al WiFi no significa tener internet**. El
 * laboratorio puede tener una red sin salida, o un portal cautivo que exige aceptar
 * unas condiciones. Preguntar solo si hay una red activa daría un falso positivo, y
 * el usuario vería el chat habilitado para luego encontrarse un error de conexión.
 *
 * <p>Por eso se consulta {@code NET_CAPABILITY_VALIDATED}: Android comprueba por su
 * cuenta que la red llega a internet y marca el resultado en esa capacidad.
 */
public final class EstadoRed {

    private static final String TAG = "EstadoRed";

    private EstadoRed() {
    }

    public static boolean hayInternet(@NonNull Context contexto) {
        try {
            ConnectivityManager gestor =
                    (ConnectivityManager) contexto.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (gestor == null) {
                return false;
            }

            Network red = gestor.getActiveNetwork();
            if (red == null) {
                return false;
            }

            NetworkCapabilities capacidades = gestor.getNetworkCapabilities(red);
            return capacidades != null
                    && capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);

        } catch (SecurityException e) {
            // Ocurrio de verdad: sin ACCESS_NETWORK_STATE declarado, esta consulta
            // lanza SecurityException y cerraba la app al abrir el modal. El permiso
            // ya esta en el manifiesto, pero una comprobacion de diagnostico no debe
            // poder tumbar la aplicacion bajo ninguna circunstancia.
            Log.w(TAG, "Sin permiso para consultar el estado de la red", e);
            return false;
        }
    }
}
