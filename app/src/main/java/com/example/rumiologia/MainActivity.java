package com.example.rumiologia;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.WindowInsetsCompat;

import com.example.rumiologia.diagnostico.RegistroDeFallos;
import com.example.rumiologia.fichas.ModalEquipo;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pantalla principal: muestra la camara en vivo y dibuja encima los equipos detectados.
 *
 * <p>Flujo de un frame:
 * <ol>
 *   <li>CameraX entrega un {@link ImageProxy} en formato RGBA_8888.</li>
 *   <li>Se copia a un Bitmap y se rota segun la orientacion del sensor.</li>
 *   <li>{@link Detector} lo redimensiona, ejecuta el modelo y devuelve las cajas.</li>
 *   <li>{@link OverlayView} las dibuja sobre la vista previa.</li>
 * </ol>
 *
 * <p>El analisis corre en su propio hilo con STRATEGY_KEEP_ONLY_LATEST: si el modelo
 * tarda mas de lo que la camara produce frames, se descartan los intermedios en vez
 * de acumular retraso.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView statusText;

    private ExecutorService ejecutorAnalisis;
    private Detector detector;

    /** Bitmap reutilizado entre frames para no crear basura en cada iteracion. */
    private Bitmap bufferFrame;

    private long framesContados = 0;
    private long ultimoReporteMs = 0;

    /** Mientras sea false, la pantalla de presentacion sigue visible. */
    private boolean detectorListo = false;

    private final ActivityResultLauncher<String> pedirPermisoCamara =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), concedido -> {
                if (concedido) {
                    iniciarCamara();
                } else {
                    statusText.setText(R.string.permiso_camara_necesario);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Debe ir ANTES de super.onCreate: instala la pantalla de presentacion y
        // sustituye el tema de arranque por el normal cuando termina.
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        splash.setKeepOnScreenCondition(() -> !detectorListo);

        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        statusText = findViewById(R.id.statusText);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            statusText.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });

        // Tocar una caja abre la hoja con los dos caminos: ficha tecnica (sin
        // internet) o chat con Rumi (con internet).
        overlayView.setOnDetectionClickListener(deteccion ->
                ModalEquipo.mostrar(getSupportFragmentManager(),
                        deteccion.label, deteccion.score));

        ejecutorAnalisis = Executors.newSingleThreadExecutor();
        prepararDetector();
        mostrarUltimoFalloSiLoHubo();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            iniciarCamara();
        } else {
            pedirPermisoCamara.launch(Manifest.permission.CAMERA);
        }
    }

    /**
     * Carga el modelo si esta presente. La app arranca igual sin el: muestra la camara
     * y avisa en pantalla, para poder trabajar la interfaz antes de tener el .tflite.
     */
    private void prepararDetector() {
        if (!Detector.modeloDisponible(this, Detector.MODELO_POR_DEFECTO)) {
            statusText.setText(R.string.estado_sin_modelo);
            Log.w(TAG, "Falta assets/" + Detector.MODELO_POR_DEFECTO);
            detectorListo = true;      // sin modelo no hay nada que esperar
            return;
        }
        try {
            detector = new Detector(this);
            statusText.setText(detector.describirModelo());
        } catch (Exception e) {
            Log.e(TAG, "No se pudo cargar el modelo", e);
            statusText.setText(getString(R.string.estado_sin_modelo));
        } finally {
            // Pase lo que pase, la presentacion debe desaparecer: si el modelo
            // falla, el usuario tiene que ver el aviso, no un logo eterno.
            detectorListo = true;
        }
    }

    private void iniciarCamara() {
        ListenableFuture<ProcessCameraProvider> futuro = ProcessCameraProvider.getInstance(this);
        futuro.addListener(() -> {
            try {
                vincularCasosDeUso(futuro.get());
            } catch (Exception e) {
                Log.e(TAG, "Error al iniciar la camara", e);
                statusText.setText(R.string.error_camara);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void vincularCasosDeUso(@NonNull ProcessCameraProvider proveedor) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analisis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analisis.setAnalyzer(ejecutorAnalisis, this::analizarFrame);

        proveedor.unbindAll();
        proveedor.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analisis);
    }

    /** Se ejecuta en el hilo de analisis, no en el principal. */
    private void analizarFrame(@NonNull ImageProxy imagen) {
        try {
            if (detector == null) {
                return;
            }

            Bitmap frame = aBitmapRotado(imagen);
            List<Detection> detecciones = detector.detect(frame);

            final int ancho = frame.getWidth();
            final int alto = frame.getHeight();
            runOnUiThread(() -> {
                overlayView.setResults(detecciones, ancho, alto);
                actualizarEstado(detecciones.size());
            });
        } catch (Exception e) {
            Log.e(TAG, "Fallo analizando el frame", e);
        } finally {
            imagen.close();   // imprescindible: sin esto la camara se congela
        }
    }

    /**
     * Convierte el ImageProxy RGBA_8888 en un Bitmap con la orientacion correcta.
     *
     * <p>El buffer puede traer relleno al final de cada fila (rowStride mayor que
     * ancho*4), asi que primero se copia con el ancho del buffer y despues se recorta
     * al ancho real. Ignorarlo produce una imagen inclinada en diagonal.
     */
    private Bitmap aBitmapRotado(@NonNull ImageProxy imagen) {
        ByteBuffer buffer = imagen.getPlanes()[0].getBuffer();
        int rowStride = imagen.getPlanes()[0].getRowStride();
        int anchoBuffer = rowStride / 4;

        if (bufferFrame == null
                || bufferFrame.getWidth() != anchoBuffer
                || bufferFrame.getHeight() != imagen.getHeight()) {
            bufferFrame = Bitmap.createBitmap(anchoBuffer, imagen.getHeight(),
                    Bitmap.Config.ARGB_8888);
        }

        buffer.rewind();
        bufferFrame.copyPixelsFromBuffer(buffer);

        Matrix rotacion = new Matrix();
        rotacion.postRotate(imagen.getImageInfo().getRotationDegrees());

        return Bitmap.createBitmap(bufferFrame, 0, 0,
                imagen.getWidth(), imagen.getHeight(), rotacion, true);
    }

    private void actualizarEstado(int numDetecciones) {
        framesContados++;
        long ahora = System.currentTimeMillis();
        if (ultimoReporteMs == 0) {
            ultimoReporteMs = ahora;
            return;
        }
        long transcurrido = ahora - ultimoReporteMs;
        if (transcurrido >= 1000) {
            float fps = framesContados * 1000f / transcurrido;
            statusText.setText(String.format(Locale.getDefault(),
                    "%.1f FPS · %d ms · %d detecciones",
                    fps, detector != null ? detector.getUltimaLatenciaMs() : 0, numDetecciones));
            framesContados = 0;
            ultimoReporteMs = ahora;
        }
    }

    /**
     * Si la app se cerró por un error, lo enseña al volver a abrirla.
     *
     * <p>Es la alternativa al Logcat cuando se prueba con el APK instalado a mano y
     * no hay un PC conectado: el propio teléfono muestra la traza y permite
     * compartirla.
     *
     * <p>Usa el AlertDialog del sistema y no el de Material a propósito: si el
     * problema fuese justamente de temas, un diálogo de Material fallaría también y
     * el aviso nunca llegaría a verse.
     */
    private void mostrarUltimoFalloSiLoHubo() {
        String fallo = RegistroDeFallos.leerUltimo(this);
        if (fallo == null) {
            return;
        }
        RegistroDeFallos.limpiar(this);

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.fallo_titulo)
                .setMessage(fallo)
                .setPositiveButton(R.string.fallo_compartir, (d, w) -> {
                    android.content.Intent envio =
                            new android.content.Intent(android.content.Intent.ACTION_SEND);
                    envio.setType("text/plain");
                    envio.putExtra(android.content.Intent.EXTRA_SUBJECT, "Fallo en Rumiologia");
                    envio.putExtra(android.content.Intent.EXTRA_TEXT, fallo);
                    startActivity(android.content.Intent.createChooser(
                            envio, getString(R.string.fallo_compartir)));
                })
                .setNegativeButton(R.string.fallo_cerrar, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ejecutorAnalisis != null) {
            ejecutorAnalisis.shutdown();
        }
        if (detector != null) {
            detector.close();
        }
    }
}
