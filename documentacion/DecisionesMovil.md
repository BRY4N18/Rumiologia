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

EJECUCIÓN EN EL TELÉFONO
  CameraX → Detector (LiteRT) → OverlayView        sin internet
                                     │ toque
                                     ▼
                              ChatActivity → AsistenteIA ──HTTP──► Gemini
                              (texto y voz)   (interfaz)      + File Search
                                                                   │
                                                          fichas técnicas .md
                                                        (filtradas por equipo)
```

La idea de fondo: **el trabajo pesado ocurre una vez, fuera del teléfono.** Entrenar
exige GPU y horas de cómputo; usar el modelo entrenado son milisegundos de CPU. Y el
conocimiento del asistente vive en documentos, no en el modelo.

## Java en lugar de Kotlin

El proyecto venía en Java y se mantuvo. Kotlin es hoy el lenguaje recomendado para
Android y habría permitido escribir menos código, pero cambiar de lenguaje a mitad de
un proyecto añade riesgo sin aportar nada al objetivo.

## `minSdk 26` (Android 8.0)

El proyecto arrancó con `minSdk 34`, que restringe la app a Android 14 o superior —
una fracción pequeña de los teléfonos reales. Nada de lo que se usa lo exige.

## CameraX en lugar de Camera2

La API `Camera2` es potente pero brutalmente verbosa: sesiones, hilos, superficies y
estados a mano, con comportamientos distintos según fabricante.

| Componente | Para qué |
|---|---|
| `PreviewView` | Muestra el vídeo en pantalla |
| `ImageAnalysis` | Entrega cada frame como dato para procesar |
| `ProcessCameraProvider` | Ata la cámara al ciclo de vida de la Activity |

Ese último punto importa más de lo que parece: la cámara se libera sola al pasar la
app a segundo plano. Con Camera2 es un origen clásico de fugas.

Dos ajustes concretos:

- **`STRATEGY_KEEP_ONLY_LATEST`** — si el modelo tarda más de lo que la cámara produce
  frames, se descartan los intermedios en vez de acumular cola.
- **`OUTPUT_IMAGE_FORMAT_RGBA_8888`** — evita convertir manualmente desde YUV.

## LiteRT (antes TensorFlow Lite)

El motor que ejecuta la red neuronal dentro del teléfono.

**Por qué en el dispositivo y no en un servidor.** Enviar cada frame por red sería
imposible en tiempo real, y el laboratorio puede no tener buena señal. En local son
milisegundos y funciona sin internet.

**Por qué el intérprete directo y no la Task Library.** La Task Library espera un
formato de salida concreto que YOLO26 no cumple. Con el intérprete directo se lee el
tensor tal cual.

## Modelo *nano* a 640×640, sin cuantizar

- **Nano** (2.4M parámetros) es la variante más pequeña. Las mayores son más precisas
  pero varias veces más lentas.
- **640×640** es la resolución de entrenamiento estándar de YOLO.
- **Sin cuantizar (float32)** no fue elección sino consecuencia: la cuantización int8
  rompe la exportación de YOLO26 (ver Parte 3). Pesa 9 MB en vez de ~3 MB, y esa es la
  causa principal del problema de rendimiento documentado abajo.

## Label Studio para etiquetar

Se evaluó **Roboflow**, más cómodo, pero su plan gratuito **publica el dataset**. Las
fotos incluyen instalaciones y personas del laboratorio, así que quedó descartado.

Label Studio es open source y corre en local. Su limitación: no se conecta con Google
Drive (solo S3, GCS, Azure y Redis), así que el flujo es descargar de Drive →
etiquetar en local → volver a subir el dataset dividido.

## Google Colab para entrenar

Entrenar necesita GPU NVIDIA. El equipo de desarrollo solo tiene gráficos integrados
AMD. Colab ofrece una **Tesla T4** gratuita: el entrenamiento completo tardó **18
minutos**. Los checkpoints se guardan en Drive para que una desconexión no cueste el
entrenamiento entero.

## Gemini con File Search para el asistente

**Decisión: RAG gestionado, no propio.**

Se implementó primero un RAG a mano (trocear documentos, calcular embeddings, guardar
vectores, similitud coseno). Funcionaba, pero se descartó: para siete documentos de
una página no aporta nada frente a lo que ya resuelve la herramienta del proveedor.

Con **File Search**, Google trocea, calcula embeddings, indexa y busca. El código se
reduce a subir las fichas y activar la herramienta en cada llamada.

Se eligió **Gemini** sobre OpenAI por razones prácticas: la clave ya estaba creada,
hay capa gratuita más 20 USD en créditos, y OpenAI cobra desde la primera llamada. La
ventaja de OpenAI —gestionar el almacén desde su interfaz— equivale a un script que se
ejecuta una vez.

**Modelo: `gemini-3.6-flash`.** Los *flash* son los rápidos y económicos; para
responder sobre fichas técnicas no hace falta un *pro*.

## Sin servidor propio: la app llama a Gemini directamente

Hubo un backend FastAPI intermedio, y se **eliminó**. Servía para que la API key no
viajara en el APK, pero tenía un problema práctico mayor: corría en una laptop, y
apagarla dejaba la app sin asistente. Para una demostración en el laboratorio eso no
es aceptable.

La app llama ahora a `generativelanguage.googleapis.com` con Retrofit. El precio es
que la clave va dentro del APK — decisión consciente, con plan de migrarla a Supabase
(ver decisiones futuras).

**Se descartó el SDK de Android**, y no por preferencia. Se inspeccionaron los dos
paquetes reales y **ninguno expone File Search**:

| SDK | Versión | ¿FileSearch? |
|---|---|---|
| `com.google.firebase:firebase-ai` | 17.16.0 | No |
| `com.google.ai.client.generativeai` | 0.9.0 | No |

La clase `Tool` de Firebase AI Logic declara `functionDeclarations`, `codeExecution`,
`urlContext`, `googleSearch` y `googleMaps`. Sin `fileSearch` no hay RAG, y el
asistente respondería sobre equipos genéricos de internet.

También se descartó la **API de Agentes** (`/v1beta/agents`): el SDK la marca como
experimental y sin tipar, no se puede verificar si admite File Search, y no aporta
nada que este proyecto necesite.

## Retrofit para hablar con Gemini

Convierte una interfaz Java en llamadas de red y serializa el JSON con Gson.
`HttpURLConnection` obligaría a escribir a mano el hilo, el parseo y los errores.

La clave viaja en la cabecera `x-goog-api-key` y no en la URL: en la URL acabaría
escrita en los registros de cualquier proxy intermedio.

## Filtro por metadatos: acotar la búsqueda al equipo detectado

Cada ficha se sube al almacén etiquetada con `equipo: <slug>`, y la consulta filtra
con `metadata_filter`. **No es un adorno**, resuelve un problema medido:

| Pregunta: *"¿a qué temperatura trabaja la estufa?"* | Fuentes | Respuesta |
|---|---|---|
| Sin filtro | `memmert`, `ankom_estufa` | Mezcla 300 °C, 37 °C **y** 102 °C |
| Filtrado a `ankom_estufa` | `ankom_estufa` | 100–105 °C, estándar 102 °C ± 2 °C |

El laboratorio tiene dos estufas y sin filtro el asistente las confunde.

**El filtro se levanta si la pregunta nombra otro equipo**, para no perder el caso de
consultar sin el aparato delante. La regla usa solo palabras que identifican a un
único equipo: "ankom" (tres equipos) y "estufa" (dos) se descartan, o preguntar "por
la estufa" levantaría el filtro y volvería a mezclarlas.

## Voz: `SpeechRecognizer` y `TextToSpeech` de Android

**Decisión: reconocimiento en el dispositivo, no en el servidor.**

Son nativos, gratuitos y funcionan sin cambios en el backend. La alternativa —enviar
el audio a un modelo de transcripción— daría mejor calidad con términos técnicos como
"AQUASEARCHER" o "digestibilidad", pero añade latencia, coste y dependencia de la
conexión.

La voz quedó como una capa fina sobre el chat: mismo endpoint, misma lógica. Por eso
soportar texto **y** voz no duplicó el trabajo.

## Identidad visual tomada de las guías del proyecto

La paleta y el estilo salen de `documentacion/fichas/MOVIL APP EXAM/Ideas de
interfaced.pdf`. **Se tomó la identidad, no el catálogo de pantallas**: esos mockups
describen una app con login institucional, onboarding, catálogo, historial, perfil y
notificaciones, que es un proyecto distinto del que hay aquí.

| Elemento | Valor |
|---|---|
| Verde institucional | `#1B5E3F` |
| Verde claro | `#E8F3EC` |
| Dorado | `#C9A227` |
| Fondo | `#F5F6F7` |
| Tarjetas | Blancas, esquinas 14 dp |

El código de color es consistente en toda la app: **verde = información disponible
siempre, dorado = acción que necesita conexión, rojo = error**. Por eso en el modal
la ficha técnica es verde y el chat con Rumi es dorado.

## Rumi: avatar derivado del logo

El asistente no tenía imagen propia. En lugar de inventar una que desentonara, se
deriva del logo de la app: la silueta del matraz en blanco sobre un círculo verde
institucional, generada con un script desde `logo.png`. Así Rumi se lee como parte de
la misma aplicación.

## Las fichas en PDF dentro del APK

Los 7 PDF viajan en `assets/fichas/`, renombrados al slug de cada equipo. Ocupan
1.5 MB sobre los 45 MB del APK.

**Van dentro y no se descargan** por una razón concreta: así la consulta de la ficha
**funciona sin internet**. En un laboratorio la señal puede ser mala, y el
procedimiento de operación es justo lo que alguien necesita con el equipo delante.

Se abren con un visor externo mediante `FileProvider`. Se asumió que el dispositivo
tiene un lector de PDF instalado; si no lo tuviera, la app avisa en lugar de no hacer
nada.

## Markdown para las fichas técnicas

Las fichas están en `.md` y no en base de datos ni JSON por tres razones: se leen sin
herramientas, se muestran fácil en la app, y —la decisiva— **se trocean de forma
natural por secciones** (`## Seguridad`, `## Procedimiento`), que es como conviene
fragmentar documentos para RAG.

## RecyclerView y core-splashscreen

- **RecyclerView** para la lista de mensajes: recicla las vistas al desplazarse, en
  lugar de mantener una por mensaje.
- **core-splashscreen** para la pantalla de presentación: la API oficial de splash es
  de Android 12, y esta librería la lleva hasta Android 6.

## Decisiones aún abiertas

| Tema | Estado |
|---|---|
| Proteger la clave: Supabase, o pantalla donde el usuario ponga la suya | Sin decidir |
| Streaming de respuestas (`streamGenerateContent`) | Sin decidir |
| Configuración remota del identificador del almacén | Sin decidir |
| Reexportar el modelo a 320×320 por rendimiento | Pendiente de medir |
| Nombre definitivo de la app (¿"UTEQ Lab Lens"?) | Sin decidir |
| Visor de PDF integrado como respaldo | Descartado por ahora |

---

# Parte 3 — Actualizaciones durante la implementación

Cambios respecto al documento original, con el motivo de cada uno.

## TensorFlow Lite → LiteRT 1.4.2

**No es opcional: `tensorflow-lite:2.14.0` no compila con AGP 9.3.2.**

```
Namespace 'org.tensorflow.lite' is used in multiple modules and/or libraries:
org.tensorflow:tensorflow-lite, tensorflow-lite-gpu, tensorflow-lite-api
```

Los tres artefactos comparten namespace y las versiones nuevas de AGP lo rechazan. La
solución es LiteRT, sucesor oficial del mismo runtime:

```groovy
implementation 'com.google.ai.edge.litert:litert:1.4.2'
implementation 'com.google.ai.edge.litert:litert-gpu:1.4.2'
```

Conserva el paquete `org.tensorflow.lite`, así que el código Java es idéntico.

## CameraX 1.3.4 → 1.4.2

La 1.3.4 incluye `libimage_processing_util_jni.so` sin alineación de 16 KB, lo que
dispara un aviso de incompatibilidad en dispositivos e imágenes con página de 16 KB
(y será requisito de Google Play). La 1.4.2 lo corrige.

## `minSdk` 34 → 26

Con `minSdk 34` la app solo funcionaría en Android 14 o superior.

## El post-procesado resultó más simple de lo previsto

El documento describía leer tres salidas (`boxes`, `scores`, `classes`) y filtrar
duplicados. El modelo exportado entrega **un solo tensor `[1, 300, 6]`**, donde cada
fila ya es una detección final: `x1, y1, x2, y2, score, clase`. Es la salida
end-to-end de YOLO26: **no hay que implementar NMS en Java**.

## La entrada es NCHW, no NHWC

El conversor LiteRT-Torch preserva el orden de PyTorch: la entrada es
`[1, 3, 640, 640]`, no `[1, 640, 640, 3]`. El buffer debe llenarse **por planos
completos** (todos los R, luego los G, luego los B), no píxel a píxel.
`Detector.java` detecta ambas convenciones al cargar el modelo.

## Trampas al exportar a TFLite (Ultralytics 8.4.131)

Dos fallos reproducibles, ambos con el mismo síntoma (`KeyError: 'feats'`):

1. **No exportes un modelo que ya pasó por `.val()` o `.predict()`.** Hay que cargar
   una instancia nueva desde `best.pt`.
2. **No uses `int8=True`.** La cuantización desactiva la rama end-to-end y rompe la
   exportación.

Además, `format='tflite'` está obsoleto desde la 8.4.83; se usa `format='litert'`.

## Las fotos venían en HEIC

144 de las 340 fotos originales estaban en HEIC (iPhone), formato que ni Label Studio
ni YOLO leen. Dos clases completas (`ankom_daisy_incubator` y `ankom_estufa`) eran
100 % HEIC: sin convertirlas, esas clases no habrían existido para el modelo, y sin
dar ningún error.

Se añadió `ml/scripts/prepare_images.py`, que además aplica la rotación EXIF (sin ella
el modelo entrena con imágenes giradas 90°) y reduce a 1280 px (de 1.2 GB a 73 MB).

## `gemini-2.5-flash` retirado para cuentas nuevas

Aparece en el listado de modelos pero devuelve 404 al invocarlo:

```
This model models/gemini-2.5-flash is no longer available to new users.
Please update your code to use models/gemini-3.6-flash
```

Esto explicó también por qué el Playground de AI Studio fallaba con "permission
denied": estaba seleccionado ese modelo.

**Lección práctica:** que un modelo aparezca en `/models` no significa que tu cuenta
pueda usarlo. Y `Invoke-RestMethod` de PowerShell oculta el cuerpo del error — con
`curl.exe` sí se ve el mensaje real, que es donde estaba la explicación.

## El SDK `google-genai` debe ser 2.20.0 o superior

Las versiones anteriores no tienen `file_search_stores` y fallan con `AttributeError`.

## Las fichas técnicas ya existían

Se generaron plantillas vacías antes de descubrir que había siete instructivos en
Word en `documentacion/fichas/MOVIL APP EXAM/`, elaborados por la Ing. Nathaly Mera
Macías (Técnico de Laboratorio). Las fichas `.md` se rehicieron con ese contenido
real, y de paso se identificó el equipo MEMMERT: es una **Estufa Universal**, series
UN/UF e IN/IF.

Eso reveló algo relevante para la detección: **hay dos estufas** en el laboratorio, la
ANKOM de secado y la MEMMERT universal. Son visualmente parecidas, y la ANKOM es
justo la clase con menos fotos de entrenamiento.

## Rendimiento en dispositivos lentos

Medición real en un teléfono de gama baja: **0.8 FPS, 1305 ms por inferencia**. En un
teléfono moderno un *nano* a 640 px debería estar entre 100 y 300 ms.

Causa principal: el modelo es **float32** porque la cuantización int8 rompe la
exportación de YOLO26. Un int8 sería unas tres veces más rápido.

Medidas aplicadas:

1. **Delegado GPU** con respaldo automático a CPU. El estado en pantalla indica cuál
   está en uso, para poder diagnosticarlo sin conectar el depurador.
2. **Array de píxeles reutilizado.** Se creaban 409.600 enteros por frame — unos 16 MB
   por segundo de basura. En un teléfono lento, las pausas del recolector pesan más
   que la propia inferencia.

Pendiente de medir: reexportar a **320×320**, cuatro veces menos píxeles. No requiere
reentrenar, solo volver a exportar desde `best.pt`, y `Detector` lee el tamaño del
propio modelo — no hay que tocar Android.

## Identidad visual: icono y pantalla de presentación

El logo se entregó como JPEG de 2048×2048 con fondo degradado, no vectorial.

**No se convirtió a SVG**, por dos motivos: Android no usa archivos `.svg` (usa vector
drawables en XML), y el logo tiene degradados, textura granulada y un filete dorado
fino que un vectorizado automático reproduce mal y con más peso que el PNG.

Se generó un **icono adaptativo** en las cinco densidades, con el logo al 62 % del
lienzo: el sistema recorta el icono según el launcher, y solo el 66 % central está
garantizado.

El fondo se quitó con un **relleno por inundación desde los bordes**, no con un umbral
global de blanco: los blancos del interior del matraz son parte del dibujo y un
umbral los habría vaciado.

La **pantalla de presentación se mantiene hasta que el modelo termina de cargar**. No
es decoración: tapa una espera real que en dispositivos lentos se nota. Si el modelo
falla, la presentación desaparece igual, para no dejar al usuario ante un logo eterno.

## Limitaciones conocidas del dataset

306 imágenes con 322 cajas: aproximadamente **una caja por foto**. Varias son tomas
abiertas donde aparecen varios equipos, y los no etiquetados le enseñan al modelo que
ese aparato es "fondo".

`ankom_estufa` tiene 7 imágenes de entrenamiento y 1 de validación: no es detectable
de forma fiable, y su métrica no significa nada.

El mAP50 global de 0.985 está inflado: muchas fotos son ráfagas casi idénticas
repartidas entre entrenamiento y validación, así que el modelo reconoce imágenes casi
vistas en vez de generalizar.

En pruebas reales sí detecta correctamente varios equipos a la vez (Ohaus PR224 85 %,
contador de colonias 82 %, AQUASEARCHER 96 %), con las cajas bien posicionadas.

## El backend se eliminó

Existió un servicio FastAPI intermedio y se retiró. La app llama ahora directamente a
la API REST de Gemini. Motivo: el backend corría en una laptop, y apagarla dejaba la
app sin asistente.

Lo que se conserva del backend: la instrucción del sistema (validada contra casos
reales) y el contrato de la conversación. Lo que se pierde: poder cambiar el prompt
sin republicar el APK.

En su lugar quedó `gestion_almacenes/`, una herramienta de PC para administrar el
almacén de fichas — crear, subir, listar, borrar y probar consultas sin compilar.

## Detalles de la API de File Search que costaron descubrir

- **Borrar un documento indexado exige `force: true`**, o devuelve
  `400 Cannot delete non-empty Document`.
- **La indexación es asíncrona**: hay que esperar a que la operación termine antes de
  consultar, o el documento aún no aparece en las búsquedas.
- **`google-genai` debe ser 2.20.0 o superior**; las anteriores no tienen
  `file_search_stores`.
- La API devuelve **503 transitorios** ("high demand") con cierta frecuencia. La app
  los traduce a un mensaje comprensible y el usuario puede reintentar.

## La instrucción del sistema es lo que impide inventar

Comprobado con una prueba deliberada: preguntando por la incubadora DAISY con el
filtro puesto en la balanza —es decir, sin acceso a la ficha correcta—

- **Sin instrucción del sistema**: respondió con tiempos de incubación y un método de
  dos etapas con pepsina y HCl. Suena plausible y **no está en ninguna ficha**.
- **Con instrucción**: *"Esa información no está en las fichas técnicas del
  laboratorio."*

Por eso está duplicada en `AsistenteGemini.java` y en `gestion_almacenes/gestionar.py`:
la segunda permite probar consultas fuera de la app, pero ambas deben decir lo mismo.

## Arquitectura del asistente pensada para cambiar de proveedor

La pantalla de chat depende de la interfaz `AsistenteIA`, no de Gemini. Cambiar de
proveedor significa escribir otra implementación, sin tocar la interfaz de usuario.
No es teórico: el asistente ya vivió en un backend FastAPI antes de moverse a Gemini
directo, y la pantalla no cambió.

Lo mismo con `ProveedorClave`: hoy la clave viene compilada desde `local.properties`;
cuando se implemente Supabase o la pantalla de configuración, será otra implementación
en la misma línea de `FabricaAsistente`.

## El flujo al tocar un equipo

Tocar una detección ya no abre el chat directamente: aparece una hoja inferior con
los dos caminos.

```
Cámara detecta → toque → ┌──────────────────────────┐
                         │ Identificado con 85%     │
                         │ Estufa de Secado ANKOM   │
                         │ ──────────────────────── │
                         │ 📄 Ficha técnica         │ verde  · siempre
                         │ 💬 Chat con Rumi         │ dorado · con internet
                         └──────────────────────────┘
```

El botón del chat se deshabilita **con la explicación visible** cuando no hay
conexión. Un botón que no responde sin decir por qué se lee como un fallo de la app.

## Detectar internet: conectado no es lo mismo que con salida

`EstadoRed` consulta `NET_CAPABILITY_VALIDATED` y no solo si hay una red activa.
Estar conectado al WiFi no significa tener internet: el laboratorio puede tener una
red sin salida o un portal cautivo. Con la comprobación simple, el chat aparecería
habilitado para luego fallar.

## Pendiente

- Proteger la clave de la API (hoy compilada en el APK).
- Streaming de respuestas.
- Más fotos de `ankom_estufa` y etiquetado de todos los equipos en cada foto.
