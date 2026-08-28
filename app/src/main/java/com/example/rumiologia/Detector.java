package com.example.rumiologia;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.NonNull;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Envuelve el interprete de TFLite y traduce un frame de camara a una lista de
 * {@link Detection}.
 *
 * <p><b>Sobre la salida del modelo:</b> esta clase se adapta sola a las dos formas
 * que puede tener un YOLO exportado, porque no se sabe cual sera hasta que el
 * modelo exista (la celda 8 del notebook la imprime):
 *
 * <ul>
 *   <li><b>End-to-end</b> {@code [1, N, 6]} — YOLO26 sin NMS. Cada fila ya es una
 *       deteccion final: {@code x1, y1, x2, y2, score, clase}. No hay que filtrar
 *       duplicados.</li>
 *   <li><b>Cruda</b> {@code [1, 4+clases, 8400]} — estilo YOLOv8. Hay que recorrer
 *       todas las anclas, quedarse con la mejor clase de cada una y aplicar NMS
 *       para eliminar las cajas repetidas sobre el mismo objeto.</li>
 * </ul>
 *
 * <p>Tambien soporta modelos float32 y cuantizados a int8/uint8: lee los parametros
 * de cuantizacion de los tensores y convierte los valores.
 */
public class Detector {

    private static final String TAG = "Detector";

    public static final String MODELO_POR_DEFECTO = "model.tflite";
    public static final String ETIQUETAS_POR_DEFECTO = "labels.txt";

    /** Confianza minima para mostrar una deteccion. */
    private static final float UMBRAL_CONFIANZA = 0.5f;

    /** Solapamiento maximo permitido entre dos cajas de la misma clase (solo modo crudo). */
    private static final float UMBRAL_IOU = 0.45f;

    /** Maximo de detecciones devueltas por frame. */
    private static final int MAX_DETECCIONES = 30;

    /** Gris neutro del relleno del letterbox; es el que usa Ultralytics al entrenar. */
    private static final int GRIS_RELLENO = Color.rgb(114, 114, 114);

    private final Interpreter interprete;
    private final List<String> etiquetas;

    /** Delegado GPU, si el dispositivo lo soporta. Hay que cerrarlo a mano. */
    private GpuDelegate delegadoGpu;

    /** true si la inferencia corre en GPU; se muestra en pantalla para diagnosticar. */
    private final boolean usandoGpu;

    /**
     * Array de pixeles reutilizado entre frames.
     *
     * <p>Son 409.600 enteros (640x640). Crearlo en cada frame generaba unos 16 MB
     * de basura por segundo, y en dispositivos lentos las pausas del recolector se
     * notan mas que la propia inferencia.
     */
    private final int[] pixeles;

    private final int anchoEntrada;
    private final int altoEntrada;
    private final boolean entradaNCHW;
    private final DataType tipoEntrada;
    private final float escalaEntrada;
    private final int ceroEntrada;

    private final int[] formaSalida;
    private final DataType tipoSalida;
    private final float escalaSalida;
    private final int ceroSalida;
    private final boolean salidaEndToEnd;

    private final ByteBuffer bufferEntrada;
    private final ByteBuffer bufferSalida;

    private final Bitmap lienzoEntrada;
    private final Canvas canvasEntrada;
    private final Paint pinturaRelleno = new Paint();

    private long ultimaLatenciaMs = 0;

    public Detector(@NonNull Context context) throws IOException {
        this(context, MODELO_POR_DEFECTO, ETIQUETAS_POR_DEFECTO, 4);
    }

    public Detector(@NonNull Context context, String modeloAsset, String etiquetasAsset, int hilos)
            throws IOException {

        this.etiquetas = cargarEtiquetas(context, etiquetasAsset);

        MappedByteBuffer modelo = cargarModelo(context, modeloAsset);

        // Se intenta GPU primero: con modelos float32 como este suele ser varias
        // veces mas rapida que la CPU. No todos los dispositivos la soportan, y en
        // algunos falla al crear el interprete, asi que siempre hay respaldo a CPU.
        Interpreter interpretePrueba = null;
        boolean enGpu = false;
        try {
            CompatibilityList compatibilidad = new CompatibilityList();
            if (compatibilidad.isDelegateSupportedOnThisDevice()) {
                delegadoGpu = new GpuDelegate(compatibilidad.getBestOptionsForThisDevice());
                Interpreter.Options opcionesGpu = new Interpreter.Options();
                opcionesGpu.addDelegate(delegadoGpu);
                interpretePrueba = new Interpreter(modelo, opcionesGpu);
                enGpu = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "GPU no disponible, se usara CPU: " + t.getMessage());
            if (delegadoGpu != null) {
                delegadoGpu.close();
                delegadoGpu = null;
            }
            interpretePrueba = null;
        }

        if (interpretePrueba == null) {
            Interpreter.Options opciones = new Interpreter.Options();
            opciones.setNumThreads(hilos);
            interpretePrueba = new Interpreter(modelo, opciones);
        }

        this.interprete = interpretePrueba;
        this.usandoGpu = enGpu;

        Tensor entrada = interprete.getInputTensor(0);
        int[] formaEntrada = entrada.shape();

        // Dos convenciones posibles segun como se haya exportado el modelo:
        //   NCHW [1, 3, alto, ancho] -> conversor LiteRT-Torch (preserva el orden de PyTorch)
        //   NHWC [1, alto, ancho, 3] -> ruta clasica via ONNX/TensorFlow
        // Cambian el orden en que hay que llenar el buffer, no solo las dimensiones.
        this.entradaNCHW = formaEntrada.length == 4 && formaEntrada[1] == 3;
        if (entradaNCHW) {
            this.altoEntrada = formaEntrada[2];
            this.anchoEntrada = formaEntrada[3];
        } else {
            this.altoEntrada = formaEntrada[1];
            this.anchoEntrada = formaEntrada[2];
        }
        this.tipoEntrada = entrada.dataType();
        this.escalaEntrada = entrada.quantizationParams().getScale();
        this.ceroEntrada = entrada.quantizationParams().getZeroPoint();

        Tensor salida = interprete.getOutputTensor(0);
        this.formaSalida = salida.shape();
        this.tipoSalida = salida.dataType();
        this.escalaSalida = salida.quantizationParams().getScale();
        this.ceroSalida = salida.quantizationParams().getZeroPoint();

        // [1, N, 6] -> end-to-end.  [1, 4+clases, anclas] -> crudo.
        this.salidaEndToEnd = formaSalida.length == 3 && formaSalida[2] == 6;

        this.bufferEntrada = ByteBuffer
                .allocateDirect(anchoEntrada * altoEntrada * 3 * bytesPorElemento(tipoEntrada))
                .order(ByteOrder.nativeOrder());
        this.bufferSalida = ByteBuffer
                .allocateDirect(numElementos(formaSalida) * bytesPorElemento(tipoSalida))
                .order(ByteOrder.nativeOrder());

        this.pixeles = new int[anchoEntrada * altoEntrada];
        this.lienzoEntrada = Bitmap.createBitmap(anchoEntrada, altoEntrada, Bitmap.Config.ARGB_8888);
        this.canvasEntrada = new Canvas(lienzoEntrada);
        this.pinturaRelleno.setColor(GRIS_RELLENO);
        this.pinturaRelleno.setStyle(Paint.Style.FILL);

        Log.i(TAG, "Modelo cargado: entrada " + anchoEntrada + "x" + altoEntrada
                + " " + (entradaNCHW ? "NCHW" : "NHWC")
                + " (" + tipoEntrada + "), salida " + Arrays.toString(formaSalida)
                + " (" + tipoSalida + "), modo "
                + (salidaEndToEnd ? "end-to-end (sin NMS)" : "crudo (con NMS)")
                + ", " + etiquetas.size() + " clases"
                + ", ejecutando en " + (usandoGpu ? "GPU" : "CPU"));
    }

    /** Comprueba si el modelo esta presente antes de intentar cargarlo. */
    public static boolean modeloDisponible(@NonNull Context context, String asset) {
        try {
            context.getAssets().openFd(asset).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Ejecuta la deteccion sobre un frame ya rotado a la orientacion de la pantalla.
     *
     * @return detecciones con cajas normalizadas 0..1 respecto al frame recibido
     */
    public List<Detection> detect(@NonNull Bitmap frame) {
        long inicio = System.currentTimeMillis();

        // Letterbox: encaja el frame en el cuadrado del modelo sin deformarlo,
        // rellenando de gris lo que sobra. Deformar la imagen degrada la precision.
        float escala = Math.min(
                (float) anchoEntrada / frame.getWidth(),
                (float) altoEntrada / frame.getHeight());
        float anchoEscalado = frame.getWidth() * escala;
        float altoEscalado = frame.getHeight() * escala;
        float rellenoX = (anchoEntrada - anchoEscalado) / 2f;
        float rellenoY = (altoEntrada - altoEscalado) / 2f;

        canvasEntrada.drawRect(0, 0, anchoEntrada, altoEntrada, pinturaRelleno);
        canvasEntrada.drawBitmap(frame,
                null,
                new RectF(rellenoX, rellenoY, rellenoX + anchoEscalado, rellenoY + altoEscalado),
                null);

        llenarBufferEntrada(lienzoEntrada);

        bufferSalida.rewind();
        interprete.run(bufferEntrada, bufferSalida);

        float[] salida = leerSalida();
        List<Detection> detecciones = salidaEndToEnd
                ? interpretarEndToEnd(salida)
                : interpretarCrudo(salida);

        // Deshace el letterbox para que las cajas queden en coordenadas del frame.
        List<Detection> resultado = new ArrayList<>(detecciones.size());
        for (Detection d : detecciones) {
            RectF b = d.box;
            RectF ajustada = new RectF(
                    (b.left * anchoEntrada - rellenoX) / anchoEscalado,
                    (b.top * altoEntrada - rellenoY) / altoEscalado,
                    (b.right * anchoEntrada - rellenoX) / anchoEscalado,
                    (b.bottom * altoEntrada - rellenoY) / altoEscalado);
            recortarA01(ajustada);
            if (ajustada.width() > 0 && ajustada.height() > 0) {
                resultado.add(new Detection(ajustada, d.classId, d.label, d.score));
            }
        }

        ultimaLatenciaMs = System.currentTimeMillis() - inicio;
        return resultado;
    }

    // ---------------------------------------------------------------- entrada

    private void llenarBufferEntrada(Bitmap bitmap) {
        bitmap.getPixels(pixeles, 0, anchoEntrada, 0, 0, anchoEntrada, altoEntrada);

        bufferEntrada.rewind();
        boolean flotante = tipoEntrada == DataType.FLOAT32;

        if (entradaNCHW) {
            // Un plano completo por canal: todos los R, luego todos los G, luego todos los B.
            for (int canal = 0; canal < 3; canal++) {
                int desplazamiento = 16 - canal * 8;   // R=16, G=8, B=0
                for (int pixel : pixeles) {
                    float valor = ((pixel >> desplazamiento) & 0xFF) / 255f;
                    if (flotante) {
                        bufferEntrada.putFloat(valor);
                    } else {
                        bufferEntrada.put(cuantizar(valor));
                    }
                }
            }
        } else {
            // Canales entrelazados: R,G,B de cada pixel seguidos.
            for (int pixel : pixeles) {
                float r = ((pixel >> 16) & 0xFF) / 255f;
                float g = ((pixel >> 8) & 0xFF) / 255f;
                float b = (pixel & 0xFF) / 255f;

                if (flotante) {
                    bufferEntrada.putFloat(r);
                    bufferEntrada.putFloat(g);
                    bufferEntrada.putFloat(b);
                } else {
                    // Cuantizado: valor = real/escala + cero, con real = canal/255
                    bufferEntrada.put(cuantizar(r));
                    bufferEntrada.put(cuantizar(g));
                    bufferEntrada.put(cuantizar(b));
                }
            }
        }
        bufferEntrada.rewind();
    }

    private byte cuantizar(float valor) {
        if (escalaEntrada == 0f) {
            return (byte) Math.round(valor * 255f);
        }
        int q = Math.round(valor / escalaEntrada) + ceroEntrada;
        return (byte) Math.max(-128, Math.min(255, q));
    }

    // ---------------------------------------------------------------- salida

    private float[] leerSalida() {
        int n = numElementos(formaSalida);
        float[] valores = new float[n];
        bufferSalida.rewind();

        switch (tipoSalida) {
            case FLOAT32:
                bufferSalida.asFloatBuffer().get(valores);
                break;
            case UINT8:
                for (int i = 0; i < n; i++) {
                    valores[i] = ((bufferSalida.get(i) & 0xFF) - ceroSalida) * escalaSalida;
                }
                break;
            default: // INT8
                for (int i = 0; i < n; i++) {
                    valores[i] = (bufferSalida.get(i) - ceroSalida) * escalaSalida;
                }
                break;
        }
        return valores;
    }

    /** Salida [1, N, 6]: x1, y1, x2, y2, score, clase. Ya son detecciones finales. */
    private List<Detection> interpretarEndToEnd(float[] salida) {
        int filas = formaSalida[1];
        List<Detection> detecciones = new ArrayList<>();

        for (int i = 0; i < filas && detecciones.size() < MAX_DETECCIONES; i++) {
            int off = i * 6;
            float score = salida[off + 4];
            if (score < UMBRAL_CONFIANZA) {
                continue;   // las filas vienen ordenadas por score, pero no se asume
            }

            float x1 = salida[off];
            float y1 = salida[off + 1];
            float x2 = salida[off + 2];
            float y2 = salida[off + 3];

            // Algunas exportaciones dan pixeles del tensor de entrada en vez de 0..1.
            if (x2 > 1.5f || y2 > 1.5f) {
                x1 /= anchoEntrada;
                y1 /= altoEntrada;
                x2 /= anchoEntrada;
                y2 /= altoEntrada;
            }

            int clase = Math.round(salida[off + 5]);
            detecciones.add(new Detection(
                    new RectF(x1, y1, x2, y2), clase, nombreClase(clase), score));
        }
        return detecciones;
    }

    /** Salida [1, 4+clases, anclas]: cx, cy, w, h y un score por clase. Requiere NMS. */
    private List<Detection> interpretarCrudo(float[] salida) {
        int canales = formaSalida[1];
        int anclas = formaSalida[2];
        int numClases = canales - 4;

        List<Detection> candidatas = new ArrayList<>();

        for (int a = 0; a < anclas; a++) {
            int mejorClase = -1;
            float mejorScore = 0f;

            for (int c = 0; c < numClases; c++) {
                float score = salida[(4 + c) * anclas + a];
                if (score > mejorScore) {
                    mejorScore = score;
                    mejorClase = c;
                }
            }
            if (mejorClase < 0 || mejorScore < UMBRAL_CONFIANZA) {
                continue;
            }

            float cx = salida[a];
            float cy = salida[anclas + a];
            float w = salida[2 * anclas + a];
            float h = salida[3 * anclas + a];

            if (cx > 1.5f || w > 1.5f) {     // pixeles en vez de normalizado
                cx /= anchoEntrada;
                cy /= altoEntrada;
                w /= anchoEntrada;
                h /= altoEntrada;
            }

            candidatas.add(new Detection(
                    new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
                    mejorClase, nombreClase(mejorClase), mejorScore));
        }

        return nms(candidatas);
    }

    /**
     * Non-Maximum Suppression: cuando varias cajas de la misma clase se solapan
     * demasiado, conserva solo la de mayor confianza.
     */
    private List<Detection> nms(List<Detection> candidatas) {
        Collections.sort(candidatas, new Comparator<Detection>() {
            @Override
            public int compare(Detection a, Detection b) {
                return Float.compare(b.score, a.score);
            }
        });

        List<Detection> conservadas = new ArrayList<>();
        boolean[] descartada = new boolean[candidatas.size()];

        for (int i = 0; i < candidatas.size(); i++) {
            if (descartada[i]) {
                continue;
            }
            Detection actual = candidatas.get(i);
            conservadas.add(actual);
            if (conservadas.size() >= MAX_DETECCIONES) {
                break;
            }
            for (int j = i + 1; j < candidatas.size(); j++) {
                Detection otra = candidatas.get(j);
                if (!descartada[j]
                        && otra.classId == actual.classId
                        && iou(actual.box, otra.box) > UMBRAL_IOU) {
                    descartada[j] = true;
                }
            }
        }
        return conservadas;
    }

    private static float iou(RectF a, RectF b) {
        float izq = Math.max(a.left, b.left);
        float arr = Math.max(a.top, b.top);
        float der = Math.min(a.right, b.right);
        float aba = Math.min(a.bottom, b.bottom);

        float interseccion = Math.max(0f, der - izq) * Math.max(0f, aba - arr);
        if (interseccion <= 0f) {
            return 0f;
        }
        float union = a.width() * a.height() + b.width() * b.height() - interseccion;
        return union <= 0f ? 0f : interseccion / union;
    }

    // ---------------------------------------------------------------- utilidades

    private static void recortarA01(RectF r) {
        r.left = Math.max(0f, Math.min(1f, r.left));
        r.top = Math.max(0f, Math.min(1f, r.top));
        r.right = Math.max(0f, Math.min(1f, r.right));
        r.bottom = Math.max(0f, Math.min(1f, r.bottom));
    }

    private String nombreClase(int id) {
        return (id >= 0 && id < etiquetas.size()) ? etiquetas.get(id) : "clase_" + id;
    }

    private static int numElementos(int[] forma) {
        int n = 1;
        for (int dim : forma) {
            n *= dim;
        }
        return n;
    }

    private static int bytesPorElemento(DataType tipo) {
        return tipo == DataType.FLOAT32 ? 4 : 1;
    }

    private static MappedByteBuffer cargarModelo(Context context, String asset) throws IOException {
        try (AssetFileDescriptor fd = context.getAssets().openFd(asset);
             FileInputStream stream = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel canal = stream.getChannel();
            return canal.map(FileChannel.MapMode.READ_ONLY,
                    fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    private static List<String> cargarEtiquetas(Context context, String asset) throws IOException {
        List<String> etiquetas = new ArrayList<>();
        try (BufferedReader lector = new BufferedReader(
                new InputStreamReader(context.getAssets().open(asset)))) {
            String linea;
            while ((linea = lector.readLine()) != null) {
                linea = linea.trim();
                if (!linea.isEmpty()) {
                    etiquetas.add(linea);
                }
            }
        }
        return etiquetas;
    }

    public long getUltimaLatenciaMs() {
        return ultimaLatenciaMs;
    }

    public int getNumClases() {
        return etiquetas.size();
    }

    public String describirModelo() {
        return anchoEntrada + "x" + altoEntrada + " " + (entradaNCHW ? "NCHW" : "NHWC")
                + " " + (usandoGpu ? "GPU" : "CPU") + " " + tipoEntrada
                + " | salida " + Arrays.toString(formaSalida)
                + (salidaEndToEnd ? " e2e" : " crudo");
    }

    public void close() {
        interprete.close();
        if (delegadoGpu != null) {
            delegadoGpu.close();   // recursos nativos: el GC de Java no los libera
        }
    }
}
