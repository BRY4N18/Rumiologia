# Decisiones de tecnologías para la aplicación móvil

> Conversión a Markdown de `DecisionesMovil.docx`. El contenido original se conserva
> tal cual; al final se añade una sección con las decisiones que cambiaron durante la
> implementación, indicando el motivo.

---

## Modelo de IA para detección de objetos

Candidatos evaluados:

- **YOLO26** — hallazgo propio
- **YOLOv8** — recomendación de DeepSeek

**Decisión: YOLO26.**

YOLO26 es superior a YOLOv8 para móviles Android gracias a su diseño **sin NMS**
(Non-Maximum Suppression) y **sin DFL** (Distribution Focal Loss), lo que reduce
drásticamente la latencia en CPU y simplifica la exportación a TFLite. YOLOv8 ofrece
mayor madurez y compatibilidad con herramientas antiguas, pero YOLO26 es más rápido y
preciso en dispositivos de borde.

## Librerías Android principales

### Cámara

```groovy
def camerax_version = "1.3.4"
implementation "androidx.camera:camera-camera2:$camerax_version"
implementation "androidx.camera:camera-lifecycle:$camerax_version"
implementation "androidx.camera:camera-view:$camerax_version"
```

Con CameraX se usará:

- `PreviewView` para mostrar la cámara.
- `ImageAnalysis` para obtener frames en tiempo real.
- `ImageAnalysis.Analyzer` para procesar cada frame con TensorFlow Lite.

### TensorFlow Lite

Se usa el **intérprete directo** de TFLite, no la Task Library, porque da más control
sobre las salidas de YOLO:

```groovy
implementation 'org.tensorflow:tensorflow-lite:2.14.0'
implementation 'org.tensorflow:tensorflow-lite-gpu:2.14.0'
```

### Red para el asistente RAG

Retrofit para comunicarse con el backend:

```groovy
implementation 'com.squareup.retrofit2:retrofit:2.11.0'
implementation 'com.squareup.retrofit2:converter-gson:2.11.0'
```

## Arquitectura dentro de la app

El flujo será:

1. CameraX entrega frames desde `ImageAnalysis`.
2. Se convierte cada frame a un `ByteBuffer` de tamaño `640x640x3`, normalizado según
   el modelo.
3. Se ejecuta `interpreter.run(inputBuffer, outputMap)`.
4. Se leen las salidas:
   - `boxes` → coordenadas normalizadas `[x1, y1, x2, y2]`
   - `scores` → confianza
   - `classes` → índice de clase
5. Se filtran las detecciones con confianza > 0.5 o 0.6.
6. Se escalan las coordenadas al tamaño real de la vista.
7. Se dibujan rectángulos y etiquetas en el `OverlayView`.
8. Si el usuario toca un equipo, se abre su ficha técnica o el chat con el backend RAG
   *(por definir)*.

## Speech-to-Text

*(Pendiente de definir en el documento original.)*

---

# Actualizaciones durante la implementación

Cambios respecto al documento original, con el motivo de cada uno.

## TensorFlow Lite → LiteRT 1.4.2

**No es opcional: `tensorflow-lite:2.14.0` no compila con AGP 9.3.2.**

```
Namespace 'org.tensorflow.lite' is used in multiple modules and/or libraries:
org.tensorflow:tensorflow-lite, tensorflow-lite-gpu, tensorflow-lite-api
```

Los tres artefactos comparten namespace y las versiones nuevas de AGP lo rechazan.
La solución es LiteRT, el sucesor oficial del mismo runtime mantenido por Google:

```groovy
implementation 'com.google.ai.edge.litert:litert:1.4.2'
implementation 'com.google.ai.edge.litert:litert-gpu:1.4.2'
```

Conserva el paquete `org.tensorflow.lite`, así que el código Java es idéntico.

## CameraX 1.3.4 → 1.4.2

La 1.3.4 incluye `libimage_processing_util_jni.so` sin alineación de 16 KB, lo que
dispara un aviso de incompatibilidad en dispositivos e imágenes con página de 16 KB
(y será un requisito de Google Play). La 1.4.2 lo corrige.

## `minSdk` 34 → 26

Con `minSdk 34` la app solo funcionaría en Android 14 o superior. Nada de lo que se
usa lo requiere.

## El post-procesado resultó más simple de lo previsto

El documento describía leer tres salidas (`boxes`, `scores`, `classes`) y filtrar
duplicados. El modelo exportado entrega **un solo tensor `[1, 300, 6]`**, donde cada
fila ya es una detección final: `x1, y1, x2, y2, score, clase`. Es la salida
end-to-end de YOLO26: **no hay que implementar NMS en Java**.

## La entrada es NCHW, no NHWC

El conversor LiteRT-Torch preserva el orden de PyTorch: la entrada es
`[1, 3, 640, 640]`, no `[1, 640, 640, 3]`. El buffer debe llenarse **por planos
completos** (todos los R, luego los G, luego los B), no píxel a píxel. `Detector.java`
detecta ambas convenciones al cargar el modelo y actúa en consecuencia.

## Trampas al exportar a TFLite (Ultralytics 8.4.131)

Dos fallos reproducibles, ambos con el mismo síntoma (`KeyError: 'feats'`):

1. **No exportes un modelo que ya pasó por `.val()` o `.predict()`.** Hay que cargar
   una instancia nueva desde `best.pt`.
2. **No uses `int8=True`.** La cuantización desactiva la rama end-to-end y rompe la
   exportación. Sin cuantizar, el modelo pesa ~9 MB en float32 en vez de ~3 MB.

Además, `format='tflite'` está obsoleto desde la 8.4.83; se usa `format='litert'`.

## Etiquetado: Label Studio

Se descartó Roboflow porque su plan gratuito publica el dataset, y las fotos incluyen
instalaciones y personas del laboratorio. Label Studio es open source y corre en local.

No se conecta con Google Drive (solo S3, GCS, Azure y Redis), así que el flujo es:
descargar las fotos de Drive → etiquetar en local → volver a subir el dataset dividido.

## Pendiente

- Ficha técnica de cada equipo al tocar una detección.
- Asistente RAG por chat y por voz.
- Speech-to-Text: sin decidir entre `SpeechRecognizer` de Android (offline, gratis,
  calidad variable) y enviar el audio al backend (mejor calidad, requiere conexión).
