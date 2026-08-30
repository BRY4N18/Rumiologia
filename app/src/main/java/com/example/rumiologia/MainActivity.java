package com.example.rumiologia;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
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
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.rumiologia.ajustes.AjustesActivity;
import com.example.rumiologia.fichas.ModalEquipo;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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
    private ImageButton botonFlash;
    private RecyclerView listaEquipos;
    private AdaptadorChipsEquipo adaptadorChips;

    /** Último conjunto de clases mostrado en la tira, para no repintar cada frame. */
    private List<String> ultimosSlugsMostrados = Collections.emptyList();

    private ExecutorService ejecutorAnalisis;
    private Detector detector;

    /** La cámara ya vinculada; se guarda para poder encender/apagar el flash. */
    private Camera camara;
    private boolean flashActiva = false;

    /** Se guardan para poder avisarles el nuevo ángulo cuando gira el teléfono. */
    private Preview preview;
    private ImageAnalysis analisis;

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
        botonFlash = findViewById(R.id.botonFlash);

        ImageButton botonAjustes = findViewById(R.id.botonAjustes);
        botonAjustes.setOnClickListener(v ->
                startActivity(new Intent(this, AjustesActivity.class)));
        botonFlash.setOnClickListener(v -> alternarFlash());

        // Márgenes fijados en el XML: se guardan antes de que el listener de insets
        // los modifique, para no ir sumando la barra de estado cada vez que se
        // dispare (p. ej. al rotar la pantalla).
        int margenBaseAjustes =
                ((ViewGroup.MarginLayoutParams) botonAjustes.getLayoutParams()).topMargin;
        int margenBaseFlash =
                ((ViewGroup.MarginLayoutParams) botonFlash.getLayoutParams()).topMargin;
        int margenBaseEstado =
                ((ViewGroup.MarginLayoutParams) statusText.getLayoutParams()).bottomMargin;

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            desplazarMargenSuperior(botonAjustes, margenBaseAjustes, systemBars.top);
            desplazarMargenSuperior(botonFlash, margenBaseFlash, systemBars.top);

            ViewGroup.MarginLayoutParams parametrosEstado =
                    (ViewGroup.MarginLayoutParams) statusText.getLayoutParams();
            parametrosEstado.bottomMargin = margenBaseEstado + systemBars.bottom;
            statusText.setLayoutParams(parametrosEstado);
            return insets;
        });

        // Tocar una caja, o su chip en la tira de abajo, abre la hoja con los dos
        // caminos: ficha tecnica (sin internet) o chat con Rumi (con internet).
        overlayView.setOnDetectionClickListener(this::mostrarModalEquipo);

        listaEquipos = findViewById(R.id.listaEquiposDetectados);
        listaEquipos.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        adaptadorChips = new AdaptadorChipsEquipo(this::mostrarModalEquipo);
        listaEquipos.setAdapter(adaptadorChips);

        ejecutorAnalisis = Executors.newSingleThreadExecutor();
        prepararDetector();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            iniciarCamara();
        } else {
            pedirPermisoCamara.launch(Manifest.permission.CAMERA);
        }
    }

    private void mostrarModalEquipo(Detection deteccion) {
        ModalEquipo.mostrar(getSupportFragmentManager(), deteccion.label, deteccion.score);
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
        preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        analisis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analisis.setAnalyzer(ejecutorAnalisis, this::analizarFrame);

        proveedor.unbindAll();
        camara = proveedor.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analisis);

        // No todos los dispositivos tienen flash trasero: el botón solo aparece si
        // el que se está usando lo tiene.
        if (camara.getCameraInfo().hasFlashUnit()) {
            botonFlash.setVisibility(View.VISIBLE);
        }
    }

    /**
     * La Activity no se recrea al girar el teléfono (ver {@code android:configChanges}
     * en el manifiesto: recargar el modelo de 9 MB y volver a atar la cámara en cada
     * giro sería lento y notorio). En su lugar, CameraX necesita que se le avise del
     * nuevo ángulo para que la vista previa y el análisis de frames se orienten con
     * la pantalla — {@code Detector.aBitmapRotado} ya sabe rotar según lo que reporte
     * {@code ImageInfo.getRotationDegrees()}, que es justo lo que cambia aquí.
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration nuevaConfiguracion) {
        super.onConfigurationChanged(nuevaConfiguracion);
        if (previewView.getDisplay() == null) {
            return;
        }
        int rotacion = previewView.getDisplay().getRotation();
        if (preview != null) {
            preview.setTargetRotation(rotacion);
        }
        if (analisis != null) {
            analisis.setTargetRotation(rotacion);
        }
    }

    private void alternarFlash() {
        if (camara == null || !camara.getCameraInfo().hasFlashUnit()) {
            return;
        }
        flashActiva = !flashActiva;
        camara.getCameraControl().enableTorch(flashActiva);
        ImageViewCompat.setImageTintList(botonFlash, ColorStateList.valueOf(
                getColor(flashActiva ? R.color.dorado : android.R.color.white)));
    }

    /**
     * Suma la barra de sistema al margen superior de un botón, sin ir acumulando el
     * valor cada vez que el listener de insets se vuelve a disparar.
     */
    private void desplazarMargenSuperior(View vista, int margenBase, int insetSuperior) {
        ViewGroup.MarginLayoutParams parametros =
                (ViewGroup.MarginLayoutParams) vista.getLayoutParams();
        parametros.topMargin = margenBase + insetSuperior;
        vista.setLayoutParams(parametros);
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
                actualizarChipsEquipo(detecciones);
            });
        } catch (Exception e) {
            Log.e(TAG, "Fallo analizando el frame", e);
        } finally {
            imagen.close();   // imprescindible: sin esto la camara se congela
        }
    }

    /**
     * Refresca la tira de equipos detectados, estilo Historias.
     *
     * <p>Se llama en cada frame, así que primero reduce las detecciones a una por
     * clase (la de mayor confianza) y solo repinta si el conjunto de clases cambió
     * respecto al frame anterior — de lo contrario la tira parpadearía 20-30 veces
     * por segundo por pequeñas variaciones de confianza, aunque sean los mismos
     * equipos. El orden es alfabético por slug (vía {@link TreeMap}), no por
     * confianza, para que los círculos no se reordenen solos.
     */
    private void actualizarChipsEquipo(List<Detection> detecciones) {
        Map<String, Detection> porSlug = new TreeMap<>();
        for (Detection d : detecciones) {
            Detection actual = porSlug.get(d.label);
            if (actual == null || d.score > actual.score) {
                porSlug.put(d.label, d);
            }
        }

        List<String> slugsActuales = new ArrayList<>(porSlug.keySet());
        if (slugsActuales.equals(ultimosSlugsMostrados)) {
            return;
        }
        ultimosSlugsMostrados = slugsActuales;

        List<Detection> distintos = new ArrayList<>(porSlug.values());
        listaEquipos.setVisibility(distintos.isEmpty() ? View.GONE : View.VISIBLE);
        adaptadorChips.actualizar(distintos);
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
