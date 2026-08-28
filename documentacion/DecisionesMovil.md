# Decisiones de tecnologías para la aplicación móvil

Documento de decisiones del proyecto **Detección de Equipos de Rumiología**: qué
tecnología se eligió en cada punto, contra qué alternativa, y por qué.

Está dividido en tres partes:

1. **Documento original** — las decisiones tomadas antes de programar.
2. **Stack tecnológico explicado** — qué se usa hoy y la razón de cada pieza.
3. **Actualizaciones durante la implementación** — lo que cambió al chocar con la
   realidad, y por qué.

Para el detalle de cada clase y función del código, ver
[`ExplicacionDetalle.md`](ExplicacionDetalle.md).

---

# Parte 1 — Documento original

> Conversión a Markdown de `DecisionesMovil.docx`, sin alterar el contenido.

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
4. Se leen las salidas: `boxes`, `scores`, `classes`.
5. Se filtran las detecciones con confianza > 0.5 o 0.6.
6. Se escalan las coordenadas al tamaño real de la vista.
7. Se dibujan rectángulos y etiquetas en el `OverlayView`.
8. Si el usuario toca un equipo, se abre su ficha técnica o el chat con el backend RAG.

## Speech-to-Text

*(Pendiente de definir en el documento original.)*

---

# Parte 2 — Stack tecnológico explicado

## Visión general

```
ENTRENAMIENTO (fuera del teléfono, una sola vez)
  Fotos → Label Studio → dataset → Colab + YOLO26 → model.tflite

EJECUCIÓN (en el teléfono, en cada frame)
  CameraX → Detector (LiteRT) → OverlayView → toque del usuario
                                                    │
                                                    ▼
                                          Ficha técnica / Asistente RAG
```

La idea de fondo: **todo el trabajo pesado ocurre una vez, en la nube; el teléfono
solo ejecuta el resultado.** Entrenar exige una GPU y horas de cómputo; usar el
modelo ya entrenado son unos milisegundos de CPU.

## Java en lugar de Kotlin

El proyecto venía en Java y se mantuvo. Kotlin es hoy el lenguaje recomendado para
Android y habría permitido escribir menos código, pero cambiar de lenguaje a mitad de
un proyecto añade riesgo sin aportar nada al objetivo. Todas las librerías usadas
funcionan igual en ambos.

## `minSdk 26` (Android 8.0)

El proyecto arrancó con `minSdk 34`, que restringe la app a Android 14 o superior —
una fracción pequeña de los teléfonos reales. Nada de lo que se usa lo exige, así que
se bajó a 26 para cubrir prácticamente cualquier dispositivo actual.

## CameraX en lugar de Camera2

La API `Camera2` de Android es potente pero brutalmente verbosa: sesiones, hilos,
superficies y estados que hay que gestionar a mano, con comportamientos distintos
según fabricante.

CameraX es una capa encima que resuelve eso:

| Componente | Para qué |
|---|---|
| `PreviewView` | Muestra el vídeo en pantalla |
| `ImageAnalysis` | Entrega cada frame como dato para procesar |
| `ProcessCameraProvider` | Ata la cámara al ciclo de vida de la Activity |

Ese último punto es más importante de lo que parece: al vincular la cámara al ciclo
de vida, se libera sola cuando la app pasa a segundo plano. Con Camera2 ese es un
origen clásico de fugas de recursos y de cámaras que se quedan bloqueadas.

Dos ajustes concretos que hace la app:

- **`STRATEGY_KEEP_ONLY_LATEST`** — si el modelo tarda más de lo que la cámara produce
  frames, se descartan los intermedios en vez de acumular una cola. Sin esto, el
  retraso crece hasta que la app parece congelada.
- **`OUTPUT_IMAGE_FORMAT_RGBA_8888`** — pedir los frames ya en RGBA evita tener que
  convertir manualmente desde YUV, que es un algoritmo largo y propenso a errores.

## LiteRT (antes TensorFlow Lite)

Es el motor que ejecuta la red neuronal dentro del teléfono. Dos decisiones aquí:

**Por qué en el dispositivo y no en un servidor.** Enviar cada frame a un servidor
sería imposible en tiempo real: latencia de red, consumo de datos y dependencia de la
conexión. El laboratorio puede no tener buena señal. En local, la inferencia son
milisegundos y funciona sin internet.

**Por qué el intérprete directo y no la Task Library.** La Task Library trae clases
listas (`ObjectDetector`) que esperan un formato de salida concreto. YOLO26 no lo
cumple: devuelve un tensor propio. Con el intérprete directo se lee el tensor tal cual
y se interpreta como haga falta, que es justo lo que necesita este modelo.

## Modelo *nano* a 640×640, sin cuantizar

- **Nano** es la variante más pequeña de YOLO26 (2.4M parámetros). Las mayores son más
  precisas pero varias veces más lentas; en un móvil, la velocidad manda.
- **640×640** es la resolución estándar de entrenamiento de YOLO. Bajar a 320 duplica
  la velocidad a costa de perder objetos pequeños o lejanos.
- **Sin cuantizar (float32)** no fue una elección sino una consecuencia: la
  cuantización int8 rompe la exportación de YOLO26 (ver Parte 3). El modelo pesa 9 MB
  en vez de ~3 MB.

## Label Studio para etiquetar

Se evaluó **Roboflow**, más cómodo, pero su plan gratuito **publica el dataset**. Las
fotos incluyen instalaciones y personas del laboratorio, así que quedó descartado.

Label Studio es open source, corre en local y los datos no salen de la máquina. Su
limitación: no se conecta con Google Drive (solo S3, GCS, Azure y Redis), así que el
flujo es descargar de Drive → etiquetar en local → volver a subir el dataset dividido.

## Google Colab para entrenar

Entrenar una red neuronal necesita una GPU NVIDIA. El equipo de desarrollo solo tiene
gráficos integrados AMD, donde el entrenamiento pasaría de minutos a muchas horas.
Colab ofrece una GPU **Tesla T4** gratuita: el entrenamiento completo tardó **18
minutos**.

Los checkpoints se guardan directamente en Drive para que una desconexión de Colab no
cueste el entrenamiento entero.

## Retrofit para el asistente

Cliente HTTP estándar en Android. Convierte una interfaz Java en llamadas de red y
serializa el JSON automáticamente con Gson. Alternativas como `HttpURLConnection`
obligan a escribir a mano el hilo, el parseo y el manejo de errores.

**El asistente no llamará al proveedor de IA directamente desde la app.** La API key
acabaría dentro del APK, y extraerla es trivial. Hará falta un servicio intermedio
que guarde la clave.

## Markdown para las fichas técnicas

Las fichas de los siete equipos están en `.md` y no en una base de datos ni en JSON
por tres razones: se leen bien sin herramientas, se muestran fácil en la app, y —la
decisiva— **se trocean de forma natural por secciones** (`## Seguridad`,
`## Procedimiento`), que es exactamente como conviene fragmentar documentos para un
sistema RAG.

## Decisiones aún abiertas

| Tema | Estado |
|---|---|
| RAG gestionado (File Search) vs implementación propia | Sin decidir |
| Proveedor: Gemini u OpenAI | Sin decidir |
| Speech-to-Text: `SpeechRecognizer` de Android vs enviar audio al servidor | Sin decidir |

---

# Parte 3 — Actualizaciones durante la implementación

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

## Las fotos venían en HEIC

144 de las 340 fotos originales estaban en formato HEIC (iPhone), que ni Label Studio
ni YOLO pueden leer. Dos clases completas (`ankom_daisy_incubator` y `ankom_estufa`)
eran 100 % HEIC: sin convertirlas, esas clases sencillamente no habrían existido para
el modelo, y sin dar ningún error.

Se añadió `ml/scripts/prepare_images.py`, que además aplica la rotación EXIF (sin ella
el modelo entrena con imágenes giradas 90°) y reduce a 1280 px (de 1.2 GB a 73 MB).

## Limitaciones conocidas del dataset

306 imágenes con 322 cajas: aproximadamente **una caja por foto**. Varias fotos son
tomas abiertas donde aparecen varios equipos, y los no etiquetados le enseñan al
modelo que ese aparato es "fondo".

`ankom_estufa` tiene 7 imágenes de entrenamiento y 1 de validación: no es detectable
de forma fiable, y su métrica no significa nada.

El mAP50 global de 0.985 está inflado: muchas fotos son ráfagas casi idénticas
repartidas entre entrenamiento y validación, así que el modelo reconoce imágenes casi
vistas en vez de generalizar.

## Pendiente

- Ficha técnica de cada equipo al tocar una detección.
- Asistente RAG por chat y por voz.
- Completar el contenido de las siete fichas técnicas.
