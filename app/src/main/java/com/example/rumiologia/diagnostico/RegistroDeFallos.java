package com.example.rumiologia.diagnostico;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Guarda en un archivo el error que cierra la aplicación.
 *
 * <p>Existe por una necesidad concreta: para leer el Logcat hace falta conectar el
 * teléfono a un PC con depuración USB. Probando con el APK instalado a mano eso no
 * siempre es posible, y sin la traza del error solo quedan las conjeturas.
 *
 * <p>Con esto, la app anota lo que la mató y lo enseña al volver a abrirla, con un
 * botón para compartirlo. El diagnóstico deja de depender de tener un PC delante.
 *
 * <p>Se encadena al manejador anterior de Android para que el sistema siga haciendo
 * lo suyo: si se lo tragara, el teléfono no mostraría el aviso de "la app se ha
 * detenido" y el proceso podría quedarse colgado.
 */
public final class RegistroDeFallos implements Thread.UncaughtExceptionHandler {

    private static final String ARCHIVO = "ultimo_fallo.txt";

    private final Context contexto;
    private final Thread.UncaughtExceptionHandler anterior;

    private RegistroDeFallos(Context contexto, Thread.UncaughtExceptionHandler anterior) {
        this.contexto = contexto.getApplicationContext();
        this.anterior = anterior;
    }

    /** Se llama una vez, al arrancar la aplicación. */
    public static void instalar(@NonNull Context contexto) {
        Thread.UncaughtExceptionHandler anterior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new RegistroDeFallos(contexto, anterior));
    }

    @Override
    public void uncaughtException(@NonNull Thread hilo, @NonNull Throwable error) {
        try {
            guardar(hilo, error);
        } catch (Throwable ignorado) {
            // Si falla el registro, no se puede hacer nada más: lo importante es
            // que el manejador original siga ejecutándose.
        }
        if (anterior != null) {
            anterior.uncaughtException(hilo, error);
        }
    }

    private void guardar(Thread hilo, Throwable error) throws Exception {
        StringWriter traza = new StringWriter();
        error.printStackTrace(new PrintWriter(traza));

        String contenido = "Fecha    : "
                + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                        .format(new Date())
                + "\nDispositivo: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid  : " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nHilo     : " + hilo.getName()
                + "\n\n" + traza;

        File archivo = new File(contexto.getFilesDir(), ARCHIVO);
        try (java.io.OutputStream salida = new java.io.FileOutputStream(archivo)) {
            salida.write(contenido.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Devuelve el último fallo registrado, o {@code null} si no hubo ninguno. */
    @Nullable
    public static String leerUltimo(@NonNull Context contexto) {
        File archivo = new File(contexto.getFilesDir(), ARCHIVO);
        if (!archivo.isFile()) {
            return null;
        }
        try {
            byte[] datos = new byte[(int) archivo.length()];
            try (java.io.InputStream entrada = new java.io.FileInputStream(archivo)) {
                int leidos = entrada.read(datos);
                return leidos > 0 ? new String(datos, 0, leidos, StandardCharsets.UTF_8) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Se llama tras mostrarlo, para no repetir el aviso en cada arranque. */
    public static void limpiar(@NonNull Context contexto) {
        File archivo = new File(contexto.getFilesDir(), ARCHIVO);
        if (archivo.exists() && !archivo.delete()) {
            archivo.deleteOnExit();
        }
    }
}
