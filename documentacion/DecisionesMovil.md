# Decisiones de tecnologías para la aplicación móvil

Documento de decisiones del proyecto **Detección de Equipos de Rumiología**: qué
tecnología se eligió en cada punto, contra qué alternativa, y por qué.

Está dividido en tres partes:

1. **Documento original** — las decisiones tomadas antes de programar.
2. **Stack tecnológico explicado** — qué se usa hoy y la razón de cada pieza.
3. **Actualizaciones durante la implementación** — lo que cambió al chocar con la
   realidad, y por qué.

Para el detalle de cada clase y función del código, ver
[`ExplicacionDetalle.md`](ExplicacionDetalle.md). Para lo que falta de cara a la
entrega, ver [`PENDIENTES.md`](PENDIENTES.md).

> **Cómo leer la Parte 3.** Es un registro cronológico: cada entrada cuenta una
> decisión en el momento en que se tomó. Una entrada posterior puede revertir a una
> anterior (pasó con la copia en la nube y con la clave compilada), y las entradas
> viejas se dejan tal cual a propósito, porque el razonamiento descartado también es
> parte de la documentación. **Lo que vale hoy es siempre la entrada más reciente
> sobre el tema.**

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

Lo mismo con `ProveedorClave`. Cuando se escribió esta sección la clave venía
compilada desde `local.properties`; hoy la única implementación es `ClaveUsuario`, que
lee la que cada persona guarda cifrada en Ajustes (ver las entradas del 2026-08-30 más
abajo). El punto se mantiene: cambiar de dónde sale la clave fue exactamente eso,
sustituir una implementación en la misma línea de `FabricaAsistente`, sin tocar el
chat.

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

Esa consulta **exige declarar `ACCESS_NETWORK_STATE`** en el manifiesto. Es un
permiso normal —no lo acepta el usuario, basta con declararlo— pero olvidarlo no da
error de compilación: lanza `SecurityException` en ejecución, y solo en el momento
exacto de abrir el modal. Pasó, y cerraba la app.

Además, `hayInternet` captura `SecurityException` y devuelve `false`. Una
comprobación de diagnóstico no debe poder tumbar la aplicación bajo ninguna
circunstancia, aunque algún fabricante restrinja la consulta.

## Tema propio para el modal

`ModalEquipo` declara `Theme.Material3.DayNight.BottomSheetDialog` en lugar de
heredar el de la Activity.

El motivo: `MainActivity` arranca con `Theme.Rumiologia.Splash`, cuyo padre
`Theme.SplashScreen` **no desciende de Material**. Los componentes de Material
validan eso al inflarse y lanzan *"The style on this component requires your app
theme to be Theme.MaterialComponents (or a descendant)"*.

Tiene que ser un tema **completo**, no un `ThemeOverlay`: un overlay se aplica encima
de un tema Material y da por hechos sus atributos, así que sobre una base no Material
tampoco basta.

## Lección de método: conseguir el dato antes de arreglar

El cierre al tocar un equipo se atribuyó primero al tema Material, por una hipótesis
plausible construida sobre evidencia indirecta. **Se corrigió dos veces sin acertar.**

La causa real —el permiso `ACCESS_NETWORK_STATE` sin declarar— apareció en cuanto
hubo una traza del error. Estaba en una línea.

Para obtenerla se añadió temporalmente un registro de fallos que guardaba la traza y
la mostraba al reabrir la app, porque las pruebas se hacían con el APK instalado a
mano y sin PC conectado. **Ya se eliminó**: cumplió su función y no tenía sentido
dejarlo en la app.

Si vuelve a hacer falta diagnosticar sin PC, las opciones son conectar el teléfono
por USB con depuración activada, usar la depuración inalámbrica de Android 11+, o
volver a añadir un registro como aquel.

## Clave de la API: pantalla de Ajustes en vez de compilada (2026-08-30)

**Decisión: cada persona pone su propia clave desde la app, cifrada con el
Android Keystore.**

La clave compilada en `local.properties` seguía siendo el mayor riesgo de
seguridad pendiente: cualquiera que descomprima el APK la extrae. La
alternativa evaluada primero fue subir la clave a Supabase sin más, pero eso
solo mueve el problema: si se sube en texto plano, cualquiera con acceso al
volcado de la base de datos la lee igual.

Se implementó `CifradorClave` (AES-256-GCM con una llave no exportable del
Android Keystore) y `AlmacenClaves` (persistencia del cifrado+iv en
`SharedPreferences`, nunca en texto plano). `ClaveUsuario`, la nueva
implementación de `ProveedorClave`, prioriza esa clave y solo cae a
`ClaveCompilada` si no hay ninguna guardada — así `local.properties` sigue
sirviendo para desarrollar sin abrir Ajustes cada vez.

**Sobre la copia en la nube (Supabase), aún sin implementar**: se descartó
reimplementar el login anónimo y las llamadas a PostgREST a mano por REST,
por el mismo motivo que se descartó el SDK de Android para Gemini pero al
revés — aquí el SDK oficial (`auth-kt` + `postgrest-kt`) sí cubre exactamente
lo que hace falta (sesión, refresco de token, RLS), y reconstruir ese
protocolo a mano es más riesgo que beneficio. La consecuencia es que entra
**un único archivo Kotlin** al proyecto (`SupabaseClaveSync.kt`), el resto
sigue en Java.

**Identidad de cada fila sin login propio**: login anónimo de Supabase
(`auth.signInAnonymously()`). Da un `auth.uid()` estable por instalación sin
construir una pantalla de login — coherente con la decisión ya tomada de no
copiar el login institucional del mockup de referencia. La fila en
`claves_api` queda protegida con RLS (`auth.uid() = user_id`), y lo que se
sube es el mismo cifrado+iv que ya vive en el dispositivo: Supabase nunca ve
la clave de Gemini en texto plano.

**Límite honesto que hay que comunicar en la propia UI**: la llave que
descifra el respaldo vive en el Keystore de un dispositivo concreto y no
sale de ahí. Sirve para recuperar la clave si se borran los datos de la app
en el mismo teléfono, **no** como sincronización entre dispositivos — eso
exigiría una identidad real (login con correo, por ejemplo), que sigue fuera
de alcance.

## Rediseño visual: identidad del mockup aplicada a lo que ya existía (2026-08-30)

Se tomaron elementos concretos de `Ideas de interfaced (1).pdf` (referencia
visual, no catálogo de pantallas — ver la aclaración de más arriba) y se
aplicaron a las pantallas ya existentes, sin agregar ninguna pantalla nueva
de las que el mockup sí tiene y que quedaron fuera de alcance (login,
catálogo, notificaciones, historial):

- **Botón de flash** en la cámara, junto al de Ajustes: mismo par de íconos
  circulares translúcidos que el mockup muestra arriba de la pantalla de
  escaneo. A diferencia del resto del rediseño, esto es funcional, no solo
  estético — usa `Camera.getCameraControl().enableTorch()` de CameraX, y se
  oculta solo si el dispositivo no tiene flash trasero (`hasFlashUnit()`).
- **`MarcoEscaneoView`**: marco decorativo de esquinas doradas sobre la
  cámara, como el recuadro de encuadre del mockup. Es una vista nueva,
  puramente visual — no toca `Detector` ni `OverlayView`, y no intercepta
  toques (los sigue resolviendo `OverlayView`).
- El indicador de FPS/latencia pasó de una caja recta en la esquina a una
  píldora redondeada centrada abajo, más cerca del indicador "Analizando
  objeto…" del mockup.
- Anillo dorado alrededor del avatar de Rumi en la cabecera del chat.

**Lo que se revisó y se dejó igual, a propósito:**

- `modal_equipo.xml` ya seguía de cerca el lenguaje del mockup (chip de
  confianza, tarjetas con icono); no había un dato de categoría/ubicación
  en `clases.json` que agregar sin inventarlo.
- La pantalla de presentación (splash) no se tocó: la API nativa de
  Android (`androidx.core.splashscreen`) solo admite ícono y color de
  fondo, no un subtítulo de texto como el que muestra el mockup. Agregarlo
  exigiría reemplazar el splash nativo por uno hecho a mano, lo que
  contradice la razón por la que se eligió el nativo (queda ligado a que
  el modelo termine de cargar, ver más arriba). No se hizo el cambio.

## Bienvenida, tira de equipos, foto en el modal y círculo de voz (2026-08-30)

Cuatro pedidos de interfaz sobre lo ya construido, después de que el equipo
probó la app instalada:

- **`BienvenidaActivity`** pasa a ser la actividad de lanzamiento; `MainActivity`
  ya no lo es. Una sola pantalla (logo, botón "Analizar equipo", créditos del
  equipo abajo) — no el onboarding de varias diapositivas del PDF de
  referencia, que sigue fuera de alcance.
- **Tira de equipos detectados estilo Historias**, sobre la cámara: no se pudo
  ver el TikTok de referencia (contenido dinámico, WebFetch no trae nada
  legible ni con la URL original ni con la redirigida), así que se implementó
  la interpretación acordada con el usuario — un círculo por clase detectada,
  se abre el mismo modal que tocar la caja. Se recalcula una detección por
  clase (la de mayor confianza) y solo se repinta si el conjunto de clases
  cambia, para no parpadear 20-30 veces por segundo.
- **Foto del equipo en el modal**: 7 fotos reales de `ml/imagenes_listas/`,
  elegidas a mano revisando 2-3 candidatas por clase, no la primera o la del
  medio. Fue necesario: la clase `ankom_estufa` tiene datos de mala calidad
  ya documentados, y varias fotos "del medio" no mostraban el equipo (una
  era una persona caminando por el laboratorio). `ImagenesEquipos` las carga
  desde `assets/equipos/` con el mismo principio de `RepositorioFichas`:
  si falta una, se oculta el hueco, no se rompe nada.
- **Círculo animado al hablar con Rumi** (`CirculoVozView` + `DialogoVoz`):
  el avatar de Rumi con anillos dorados que crecen con el volumen real del
  micrófono (`onRmsChanged`, que antes estaba vacío). Deliberadamente **no
  se tocó la lógica de reconocimiento que ya funcionaba** — el diálogo solo
  se entera de lo que `ChatActivity` ya estaba haciendo (texto parcial,
  resultado final, error) y lo muestra; es una capa visual aditiva, no un
  reemplazo. Animar el círculo durante la respuesta hablada (TTS) quedó
  fuera de esta tanda.

## Se quitó la copia en la nube: el equipo no le vio sentido (2026-08-30)

**Reversión de la decisión anterior.** Se implementó por completo (Keystore
local + login anónimo de Supabase + tabla `claves_api` con RLS + el único
archivo Kotlin del proyecto) y funcionaba, pero al probarlo el equipo decidió
quitarlo: no le vieron sentido a un respaldo que, tal como quedó diseñado
(sin login real), solo se puede recuperar en el mismo teléfono — no resolvía
un problema que realmente tuvieran.

Se quitó por completo: `SupabaseClaveSync.kt`, la carpeta `supabase/`, las
dependencias de Supabase/Ktor/kotlinx-serialization y el plugin de Kotlin de
`build.gradle` y `libs.versions.toml`, la sección "Copia en la nube" de
Ajustes, y `SUPABASE_URL`/`SUPABASE_ANON_KEY` de `local.properties`. El
proyecto vuelve a ser 100 % Java. Queda la clave por usuario cifrada
**localmente** (Parte 1 de esa tanda), que sí se quedó — es la que resuelve
el problema real (no depender de una clave compilada y compartida).

## Se quitó también la clave compilada de respaldo (2026-08-30)

Con la pantalla de Ajustes ya probada y funcionando, `ClaveCompilada` (leía
`GEMINI_API_KEY` de `local.properties` vía `BuildConfig`) dejó de tener
motivo para existir: era solo una comodidad para desarrollar sin abrir
Ajustes, pero significaba que el APK podía llevar una clave dentro
extraíble descomprimiéndolo — justo el riesgo que la pantalla de Ajustes se
creó para eliminar.

Se quitó `ClaveCompilada.java`; `ClaveUsuario` (única implementación de
`ProveedorClave` que queda) ya no cae a ningún respaldo — devuelve `null` si
no hay clave guardada, y `ChatActivity` ya sabe mostrar el aviso de "añade tu
clave" en ese caso. También se quitó el bloque que leía `local.properties` y
el `buildConfigField`/`buildFeatures.buildConfig` de `build.gradle`, ya sin
uso. Para desarrollar ahora hace falta guardar la clave una vez desde
Ajustes, igual que hará cualquier persona que use la app.

## "No se pudo reconocer la voz" cerraba la app — causa real, con teléfono conectado

La corrección de `reconocedor.cancel()` antes de cada `startListening()` (ver
más abajo, se mantiene, no está de más) **no era la causa real**. Se
confirmó conectando el teléfono del usuario por USB y viendo `adb logcat`
en vivo mientras se reproducía el fallo:

```
W/RemoteSpeechRecognitionService: #stopListening called with no preceding
    #startListening - ignoring
```

repetido **cientos de veces en poco más de un segundo**, seguido de un
aluvión de `E/NotificationService: Package has already queued 5 toasts.
Not showing more.` — un bucle descontrolado que satura el hilo principal y
tumba la app. Causa exacta: `detenerReconocimiento()` llamaba a
`reconocedor.stopListening()` sin comprobar si de verdad había una sesión
activa. En este teléfono (Infinix/Transsion, `RemoteSpeechRecognitionService`
de `com.google.android.as`), llamar a `stopListening()` sin sesión activa
dispara `onError()` de inmediato — y como `onError()` también llama a
`detenerReconocimiento()`, se entra en una recursión: parar → error → parar
→ error… cientos de veces por segundo, cada una con su propio `Toast`.

**Corrección real**: `detenerReconocimiento()` ahora empieza con
`if (!escuchando) return;` — una guarda de idempotencia. La primera llamada
sí para el reconocedor; cualquier llamada repetida (incluida la que viene
del `onError()` disparado por la propia llamada a `stopListening()`) no hace
nada. Se probó en el dispositivo real después del arreglo: el mismo
`ERROR_CLIENT` (código 5) que antes tumbaba la app ahora se queda en un
solo evento — la pantalla de voz pasa a "Toca el círculo para seguir
hablando" y la app sigue viva (mismo PID antes y después).

**Lección**: la hipótesis inicial (la corregida con `cancel()`) sonaba
razonable pero no era la causa — hizo falta el log real del dispositivo
para encontrarla. Vale la pena recordarlo la próxima vez que aparezca un
error intermitente de voz: pedir el `adb logcat` antes de adivinar.

## `cancel()` antes de cada `startListening()` (se mantiene, aunque no era la causa)

Sigue siendo una limpieza defensiva razonable: descarta cualquier resto de
una sesión anterior antes de empezar una nueva, sin costo real.

## La cámara sigue la rotación del teléfono

`MainActivity` estaba fijada a `portrait`. Pasó a `android:screenOrientation
="fullSensor"`, con `android:configChanges="orientation|screenSize|
screenLayout|keyboardHidden"` para que la Activity **no se recree** al girar
—recargar el modelo de 9 MB y volver a atar la cámara en cada giro sería
lento y notorio, y interrumpiría un análisis de frame a medio camino.

Con la Activity persistente, CameraX no se entera sola del nuevo ángulo: hay
que avisarle. `onConfigurationChanged` llama a
`preview.setTargetRotation(...)` y `analisis.setTargetRotation(...)` con la
rotación de la pantalla en ese momento. `Detector`/`aBitmapRotado` ya sabían
rotar el bitmap según `ImageInfo.getRotationDegrees()` —ese valor es
justamente lo que cambia al avisarle a `ImageAnalysis` la nueva rotación—,
así que no hizo falta tocar la lógica de rotación del bitmap ni la del
letterbox, solo mantener a CameraX al día del ángulo actual.

## La referencia real del menú tipo Instagram, y por qué se dejó la tira igual

La captura que mandó el usuario mostraba un patrón distinto al que se había
interpretado: una barra de navegación inferior de 5 iconos con un botón
central elevado (CTA), pensada para una app de catálogo/venta (Inicio,
Favoritos, botón central, Órdenes, Perfil).

**Decisión (2026-08-30): se deja la tira de equipos estilo Historias tal
como está.** Ese patrón de 5 pestañas no encaja aquí — la app no tiene
catálogo, favoritos ni perfil, y forzarlo habría significado inventar
pantallas fuera de alcance solo para llenar los espacios. La tira actual ya
resuelve el caso real sin depender de tener varios equipos a la vez: se
oculta sola si no hay detecciones y muestra un solo círculo cuando solo hay
uno enfocado, que es el uso más común.

## Modo de voz persistente: quedarse en el diálogo toda la conversación

El círculo de voz cerraba el diálogo apenas terminaba de reconocer una
frase, así que cada pregunta obligaba a volver a la pantalla de texto y
tocar el micrófono de nuevo. Se cambió para que el diálogo se quede abierto
durante toda la conversación hablada:

`escuchando` → `Rumi está pensando…` (pulso automático, no hay volumen real
que mostrar mientras se espera la respuesta) → `Hablando…` (Rumi lee la
respuesta, mismo pulso automático porque `TextToSpeech` de Android no
expone su amplitud) → reposo con "Toca el círculo para seguir hablando".
Tocar el círculo en reposo vuelve a escuchar sin cerrar nada.

Para saber cuándo Rumi termina de hablar (y volver a reposo) se le añadió
un `UtteranceProgressListener` al `TextToSpeech`, que antes no tenía
ninguno — sin eso no había forma de saber cuándo el círculo debía dejar de
pulsar. `detenerEscucha()` se separó en dos: `detenerReconocimiento()`
(deja de escuchar, el diálogo se queda) y `cerrarModoVoz()` (cierra todo,
solo para "Cancelar" o salir de la pantalla).

**Sobre usar la voz nativa de Gemini (Live API) para esto en vez de
`SpeechRecognizer`+`TextToSpeech`:** se investigó porque el usuario
preguntó si ya se usaba el SDK de Gemini para la voz. No se usa — hoy toda
la voz es nativa de Android, Gemini solo entra para el texto (REST + File
Search). El Live API de Gemini existe y da conversación de voz en tiempo
real mucho más natural, pero **no soporta File Search** (confirmado en la
documentación oficial: "File Search is not yet supported in the Live
API"). Cambiarse a él dejaría a Rumi sin el RAG que le impide inventar
datos de los equipos — literalmente el problema que la instrucción del
sistema y el filtro por metadatos ya resolvieron y que está probado que
pasa sin ellos (ver "La instrucción del sistema es lo que impide
inventar"). Por eso se descarta, no por preferencia: es la misma clase de
decisión que ya se tomó con el SDK de Android para el chat de texto.

## Rumi preguntaba "¿a qué equipo te refieres?" con un equipo ya seleccionado

El usuario notó que, hablando con Rumi desde un equipo ya detectado, las
respuestas "se salían de contexto". Probado en el teléfono conectado: la
pregunta genérica "para que sirve este equipo" (sin nombrar ningún equipo),
con el filtro ya puesto en `ankom_estufa`, devolvía:

> "¿A qué equipo te refieres? Por favor, indícame el nombre del equipo... (si
> se trata de una estufa, especifica si es la Estufa de Secado ANKOM o la
> Estufa Universal MEMMERT)..."

El log confirmó que el filtro por metadatos **sí se estaba mandando bien**
(`Consulta acotada a ankom_estufa`) — la búsqueda por vectores/ID de
documento nunca falló. El problema estaba en un lugar completamente aparte:
la instrucción del sistema (texto fijo que se manda en cada llamada, al
margen de qué documentos recupera la búsqueda) decía sin condición *"hay dos
estufas, si preguntan por 'la estufa' pregunta cuál es"*. El modelo obedecía
esa instrucción al pie de la letra sin importar que la búsqueda ya viniera
acotada a una sola ficha — como si a alguien le dieras el libro correcto en
la mano pero igual le insistieras "confirma cuál libro es" antes de dejarlo
leer.

**Corrección**: la instrucción ahora se arma en dos partes
(`INSTRUCCION_BASE` + un cierre que cambia según `equipoFiltro`):
- **Sin filtro** (chat general): se mantiene "hay dos estufas, pregunta cuál"
  — ahí el modelo de verdad no sabe a cuál se refiere la persona.
- **Con filtro activo**: en su lugar, "la búsqueda ya está acotada al equipo
  seleccionado; aunque digan 'este equipo' o no lo nombren, NO preguntes cuál
  es, respóndelo directamente."

Se corrigió en `AsistenteGemini.java` y en `gestion_almacenes/gestionar.py`
(misma instrucción en los dos, por convención ya documentada). **Verificado
de punta a punta en el dispositivo real**, reintentando tras un par de 503
transitorios de Google: la misma pregunta ahora responde *"Esta respuesta
corresponde a la Estufa de Secado ANKOM"* con datos reales y su fuente
citada, sin volver a preguntar cuál equipo es.

## Voz de Rumi elegible en Ajustes

El usuario pidió una voz "más tierna" para Rumi. En vez de que se elija una
sola voz a mano para todo el mundo (el gusto es personal y las voces
instaladas varían por teléfono), se armó una sección nueva en Ajustes donde
cada persona prueba y elige la suya.

`VozRumi` arma la lista consultando `TextToSpeech.getVoices()` **en tiempo
de ejecución** — nunca una lista fija: en el teléfono de prueba salieron 6
voces de España y 5 de Latinoamérica, más de las que se habían probado a
mano al principio. Se descartan los duplicados "-network" cuando ya existe
el mismo "-local" (más rápida, no depende de conexión). La elegida se
guarda en `SharedPreferences` y `ChatActivity` la aplica al iniciar el
sintetizador; si no hay ninguna guardada, se queda con la que el sistema
puso por defecto.

## Las respuestas de Rumi mostraban el Markdown crudo

Gemini responde en Markdown (negrita con `**así**`, listas con `- `), y el
`TextView` del chat lo mostraba tal cual, asteriscos incluidos. Se agregó
Markwon (`io.noties.markwon:core`), una librería hecha para esto en
Android: convierte el Markdown a `Spannable` nativo sin WebView. Cambio
mínimo: `ChatAdapter` recibe un `Markwon` (creado una vez, no por mensaje) y
usa `markwon.setMarkdown(texto, m.texto)` en vez de `texto.setText(...)`.
Verificado en el dispositivo real: la negrita y las viñetas ya se ven bien.

## Nuevo avatar de Rumi, recortado en círculo y con rebote al hablar

El avatar (un matraz blanco sobre un círculo verde) se reemplazó por
`icono_chatbot.jpg.jpeg` (en la raíz del proyecto, fuente de `ic_rumi.png`)
— una carita de chatbot en una burbuja de chat, mucho más acorde a "Rumi es
un asistente de IA tierno". Venía en celeste; se recoloreó rotando el tono
(HSV, -45°) hacia el verde institucional con un script de un solo uso, sin
tocar el degradado ni las sombras del diseño original.

**El icono nuevo trae su propio fondo cuadrado** (a diferencia del anterior,
que ya era un círculo con transparencia alrededor), así que hubo que
recortarlo a círculo en cada sitio donde se usa — si no, se verían las
esquinas color crema asomando detrás del anillo dorado. En XML
(`activity_chat.xml`, `item_mensaje.xml`, `modal_equipo.xml`) con
`clipToOutline="true"` + `scaleType="centerCrop"` sobre un fondo circular.
En `CirculoVozView`, que dibuja el drawable directo en el `Canvas` sin pasar
por ningún `ImageView`, el recorte se hizo a mano con `Canvas.clipPath`.

**Rebote al hablar** (pedido del usuario: "que se mueva... para dar un
poco de realismo"): en vez de un GIF con fotogramas nuevos —fragil de hacer
bien partiendo de una sola imagen plana, sin capas separadas para ojos o
boca—, se animó la propia imagen con `ValueAnimator` nativo de Android:
`CirculoVozView.setEscalaAvatar()` escala el ícono entero alrededor de su
centro. Se activa solo mientras Rumi habla de verdad
(`iniciarAnimacionHabla`, ligado al `UtteranceProgressListener` del
`TextToSpeech`), no mientras "piensa" — así el movimiento significa algo
("Rumi está hablando"), no es decoración sin motivo. Los anillos, aparte,
siguen respondiendo al volumen real cuando quien habla es el usuario.

## Modo oscuro con interruptor manual en Ajustes

Se agregó una sección "Apariencia" en Ajustes con tres opciones (Claro /
Oscuro / Seguir el sistema), pedido explícito del usuario tras confirmarlo
("interruptor manual en Ajustes con las tres opciones").

**Qué se tocó y qué no**: solo se sobreescriben los tokens de superficie y
texto en `values-night/colors.xml` (`fondo`, `superficie`, `borde`,
`texto_principal`, `texto_secundario`, `splash_fondo`). El verde
institucional, el dorado y las tarjetas de acento (verde claro, dorado
claro) se dejan igual en ambos modos a propósito: esos colores hoy cumplen
doble función —de relleno (botones, cabecera) y de texto/ícono sobre
tarjetas claras (chips, tarjetas de acento)— así que invertirlos de golpe
sin auditar cada uso habría arriesgado combinaciones ilegibles. El costo
aceptado es que algunos chips de acento se ven "claros" flotando sobre el
fondo oscuro, un patrón común y más seguro que rediseñar cada combinación.

**Persistencia**: `AppCompatDelegate.setDefaultNightMode()` solo vive en
memoria — sin guardarlo aparte, la app volvería a "seguir el sistema" cada
vez que el proceso muriera. Se agregó `TemaApp` (guarda/lee el modo en
`SharedPreferences`) y `RumiologiaApp` (subclase de `Application`,
registrada en el manifest con `android:name=".RumiologiaApp"`) que aplica
el modo guardado en `onCreate()`, antes de que se infle cualquier
`Activity`.

En `AjustesActivity`, el `RadioGroup` nuevo llama a `TemaApp.guardar()` y
luego a `recreate()` para que el cambio se vea al instante, sin tener que
volver a entrar a la pantalla. **Verificado en el dispositivo real**: con
"Claro" fuerza modo claro sin importar el tema del sistema (que en ese
momento estaba en oscuro), y con "Oscuro" fuerza modo oscuro también sin
importar el sistema — confirmando que las tres opciones son independientes
entre sí y no solo un reflejo del ajuste del teléfono.

## Pendiente

- Ajustar el presupuesto de razonamiento: entre 403 y 638 tokens por consulta que no
  aportan nada cuando la respuesta sale de un documento.
- Streaming de respuestas.
- Medir GPU vs CPU y milisegundos en los dispositivos de prueba.
- Más fotos de `ankom_estufa` y etiquetado de todos los equipos en cada foto.
