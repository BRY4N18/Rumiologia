# Explicación detallada del código

Qué hace cada clase y cada función del proyecto, y **por qué existe**. No es una
lectura línea por línea del código: es la explicación de las decisiones que hay
detrás de cada pieza.

Para las decisiones de tecnología (qué librerías y por qué), ver
[`DecisionesMovil.md`](DecisionesMovil.md).

---

## Los dos recorridos de la app

Todo el diseño gira alrededor de estos dos flujos.

**Detección, entre 5 y 20 veces por segundo:**

```
 1. CameraX captura un frame                      (ImageProxy, RGBA_8888)
 2. MainActivity lo convierte a Bitmap y lo rota  (aBitmapRotado)
 3. Detector lo encaja en 640×640 con letterbox   (detect)
 4. Detector llena el buffer de entrada           (llenarBufferEntrada)
 5. LiteRT ejecuta la red neuronal                (interprete.run)
 6. Detector interpreta el tensor de salida       (interpretarEndToEnd)
 7. Detector deshace el letterbox                 (coordenadas del frame)
 8. OverlayView dibuja las cajas en pantalla      (onDraw → aVista)
```

**Consulta al asistente, cuando el usuario toca una caja:**

```
 1. OverlayView detecta el toque                  (onTouchEvent → deteccionEn)
 2. MainActivity abre la hoja del equipo          (ModalEquipo.mostrar)
 3a. Ficha técnica → PDF desde assets             (RepositorioFichas) — sin internet
 3b. Chat con Rumi → el usuario escribe o dicta   (SpeechRecognizer)
 4. La regla decide el alcance de la búsqueda     (ReglaDeAlcance)
 5. Retrofit llama a Gemini con File Search       (AsistenteGemini)
 6. La respuesta se muestra con sus fuentes       (ChatAdapter)
 7. Opcionalmente se lee en voz alta              (TextToSpeech)
```

No hay servidor propio: la app habla directamente con Google.

Varios pasos existen para resolver problemas que no son evidentes hasta que fallan.

---

# Parte 1 — La aplicación Android

## `Detection.java` — El dato que viaja entre capas

Una clase pequeña que representa **una detección**: un rectángulo, una clase y una
confianza.

### Por qué existe

Podría pasarse la información en arrays sueltos (`float[] cajas`, `int[] clases`,
`float[] scores`), como hace la salida cruda del modelo. Sería más rápido y mucho
peor: cualquiera que lea el código tendría que recordar que el índice `i` de un array
corresponde al del otro, y un desajuste produciría etiquetas cruzadas sin dar error.

### La decisión importante: coordenadas normalizadas

El campo `box` guarda valores **entre 0 y 1**, no píxeles. `0.5` significa "la mitad
del ancho", sin importar si el frame es de 640, 1280 o 4000 píxeles.

Esto resuelve un problema real: el frame de la cámara, la entrada del modelo y la
pantalla tienen tamaños distintos. Si las cajas viajaran en píxeles, habría que
recordar en cada punto del código *a qué* tamaño se refieren.

### Funciones

| Función | Qué hace | Por qué |
|---|---|---|
| `area()` | Superficie de la caja | Desempata cuando el usuario toca cajas superpuestas |
| `toString()` | Representación legible | Para logs y depuración |

---

## `Detector.java` — El cerebro

Envuelve el intérprete de LiteRT y traduce **un Bitmap a una lista de detecciones**.
Es la clase más compleja y la que concentra los detalles delicados.

### Por qué está separada de la Activity

La Activity no sabe nada de tensores, buffers ni cuantización. Si mañana se cambia de
modelo o de motor de inferencia, solo cambia esta clase. Y al no depender de la
cámara, puede ejecutarse sobre cualquier Bitmap.

### El constructor: adaptarse al modelo, no al revés

Cuando se escribió esta clase todavía no existía el `.tflite`, así que no se sabía qué
forma tendría. En lugar de adivinar, **inspecciona el modelo al cargarlo**.

**Detecta el orden de los canales:**

- `[1, 3, 640, 640]` → **NCHW**, canales primero (convención de PyTorch)
- `[1, 640, 640, 3]` → **NHWC**, canales al final (convención clásica de TensorFlow)

No es cosmético: cambia el orden en que hay que escribir los bytes. Si se equivoca, el
modelo recibe ruido y no detecta nada — **sin lanzar ningún error**. El modelo actual
resultó ser NCHW, así que esa flexibilidad se usó de verdad.

**Detecta el formato de la salida:**

- `[1, 300, 6]` → **end-to-end**: cada fila ya es una detección final. Es lo de YOLO26.
- `[1, 4+clases, 8400]` → **cruda**, estilo YOLOv8: miles de candidatas a filtrar.

**Lee los parámetros de cuantización**, por si el modelo fuera int8 en lugar de float32.

**Intenta usar la GPU.** Se crea el intérprete con el delegado GPU y, si el dispositivo
no lo soporta o falla, se reintenta en CPU. Con modelos float32 la GPU suele ser varias
veces más rápida, pero el soporte es irregular entre fabricantes: **siempre hay
respaldo**. El modo elegido se muestra en pantalla, para poder diagnosticar el
rendimiento sin conectar el depurador.

### `detect(Bitmap)` — La función principal

**1. Letterbox.** El modelo exige exactamente 640×640, pero el frame es rectangular.
Estirar la imagen deformaría los objetos y el modelo, entrenado con proporciones
reales, fallaría. En su lugar se escala manteniendo la proporción y se **rellena de
gris** lo que sobra, con el `(114,114,114)` que usa Ultralytics al entrenar.

**2. Llenar el buffer de entrada** con los píxeles normalizados.

**3. Ejecutar la red** — `interprete.run()`, la única línea donde ocurre la inferencia.

**4. Deshacer el letterbox.** El modelo devuelve coordenadas dentro del cuadrado, que
incluye las bandas grises. **Omitir este paso es el error clásico**: las cajas
aparecen desplazadas y encogidas.

### Las demás funciones

| Función | Qué hace | Por qué existe |
|---|---|---|
| `llenarBufferEntrada` | Copia los píxeles al buffer | Aquí se aplica la diferencia NCHW/NHWC: por planos completos o entrelazado |
| `cuantizar` | Convierte float a int8 | Solo para modelos cuantizados; el actual no lo usa, pero está listo |
| `leerSalida` | Convierte el buffer de salida a floats | Unifica float32, int8 y uint8 en un array, para que el resto no se entere del tipo |
| `interpretarEndToEnd` | Lee `[1, 300, 6]` | Camino del modelo actual: filtra por confianza y ya está |
| `interpretarCrudo` | Lee `[1, 4+clases, 8400]` | Camino alternativo: recorre miles de anclas |
| `nms` | Elimina cajas duplicadas | **Solo en el camino crudo.** Con YOLO26 no hace falta |
| `iou` | Cuánto se solapan dos cajas | Es la medida que usa `nms` |
| `recortarA01` | Recorta al rango 0..1 | Un objeto cortado por el borde puede dar coordenadas fuera de la imagen |
| `modeloDisponible` | ¿Existe el `.tflite`? | Permite que la app arranque y avise en vez de reventar |
| `cargarModelo` | Mapea el archivo en memoria | Usa `FileChannel.map`: el modelo **no se copia** a RAM. Por eso `noCompress 'tflite'` es obligatorio |
| `cargarEtiquetas` | Lee `labels.txt` | Traduce el índice numérico a nombre de clase |
| `describirModelo` | Resumen legible | Se muestra al arrancar: confirma qué modelo se cargó y si va por GPU o CPU |
| `close` | Libera intérprete y delegado | Recursos nativos que el recolector de Java no gestiona |

### El campo `pixeles`

Un array de 409.600 enteros reservado **una sola vez** en el constructor. Antes se
creaba en cada frame: unos 16 MB por segundo de basura. En un teléfono potente pasa
desapercibido; en uno lento, las pausas del recolector pesan más que la inferencia.

### Sobre `interpretarEndToEnd` y las coordenadas

Dentro hay una comprobación que parece un truco sucio:

```java
if (x2 > 1.5f || y2 > 1.5f) { /* dividir por el tamaño de entrada */ }
```

Es deliberado. Algunas exportaciones devuelven coordenadas normalizadas (0 a 1) y
otras en píxeles del tensor de entrada (0 a 640). Como una coordenada normalizada
nunca supera 1, un valor mayor que 1.5 solo puede ser píxeles.

---

## `OverlayView.java` — Dibujar y detectar toques

Una vista transparente sobre la cámara. No dibuja la imagen: **solo las cajas encima**.

### Por qué una vista aparte

La vista previa se refresca a la velocidad de la cámara mientras el overlay solo se
redibuja cuando hay detecciones nuevas. Y funciona igual sobre cualquier fondo.

### El problema central: la conversión de coordenadas

Hay tres espacios en juego: el **frame de la cámara** (640×480), la **pantalla**
(1080×2400) y las **coordenadas normalizadas** de cada `Detection`.

La `PreviewView` usa `FILL_CENTER`: escala el vídeo hasta **cubrir** toda la vista y
recorta lo que sobra. Por eso `aVista()` no puede hacer una regla de tres: escala por
el factor **mayor** de los dos ejes y centra. Con el factor menor —el error natural—
las cajas aparecerían desplazadas, con un desfase que crece hacia los bordes.

### Funciones

| Función | Qué hace | Por qué |
|---|---|---|
| `init` | Prepara pinceles y colores | Se ejecuta una vez, no en cada dibujado |
| `leerColoresClase` | Carga los colores por clase | Usa `obtainTypedArray`, **no** `getStringArray`: aapt2 compila los `#RRGGBB` como colores y pedirlos como texto devuelve nulos. Esto provocó un cierre inmediato de la app |
| `setResults` | Publica nuevas detecciones | Usa `postInvalidate()` porque **se llama desde el hilo de análisis** |
| `clear` | Borra las cajas | Para cuando no hay nada que mostrar |
| `onDraw` | Dibuja cajas y etiquetas | Reposiciona la etiqueta si no cabe arriba o si se saldría por un lado |
| `aVista` | Convierte normalizado → píxeles | Replica el recorte `FILL_CENTER` |
| `onTouchEvent` | Detecta el toque | Solo reacciona a `ACTION_UP`: al soltar, no al presionar |
| `deteccionEn` | Qué caja se tocó | Si hay varias superpuestas gana **la más pequeña**: suele ser la que el usuario quería señalar |
| `colorDe` | Color de una clase | Usa módulo, nunca se sale del array |
| `nombreLegible` | `ankom_estufa` → `Ankom estufa` | Respaldo si no hay nombre en `clases.json` |

### El ajuste de las etiquetas al borde

En las primeras pruebas reales, un equipo pegado al borde dejaba su nombre cortado
("...dor de colonias"). Ahora la etiqueta se desplaza hacia dentro cuando se saldría
de la pantalla. Es un detalle pequeño que solo aparece al probar con la cámara
apuntando a una escena real, no en el diseño.

### Un detalle de rendimiento

`onDraw` se ejecuta muchas veces por segundo, así que los `Paint` y el `RectF`
temporal se crean **una sola vez** como campos. Crear objetos en `onDraw` genera
basura constante y provoca tirones.

---

## `MainActivity.java` — El coordinador

Une todas las piezas: presentación, permisos, cámara, detector y overlay.

### `onCreate` — El orden importa

`SplashScreen.installSplashScreen()` va **antes** de `super.onCreate()`: instala la
pantalla de presentación y sustituye el tema de arranque por el normal. Después:
enlazar vistas, ajustar *insets*, registrar el toque sobre detecciones, crear el hilo
de análisis, cargar el detector y pedir permiso de cámara.

El detector se carga **antes** que la cámara a propósito: si falta el modelo, se avisa
de inmediato en vez de arrancar la cámara para nada.

### La presentación ligada a la carga del modelo

`splash.setKeepOnScreenCondition(() -> !detectorListo)` mantiene el logo hasta que el
`.tflite` está cargado. No es decoración: tapa una espera que en dispositivos lentos se
nota, y evita mostrar la cámara sin detecciones durante ese hueco.

`detectorListo` se pone a `true` en un bloque `finally`, así que **la presentación
desaparece incluso si el modelo falla**. Sin ese detalle, un error dejaría al usuario
ante un logo eterno.

### `prepararDetector` — Fallar de forma útil

Si no existe `model.tflite`, la app **no se cierra**: muestra la cámara y un mensaje
explicando qué falta. Eso permitió desarrollar toda la interfaz mientras el modelo aún
no estaba entrenado.

### `analizarFrame` — El corazón, y el punto peligroso

Se ejecuta **en el hilo de análisis**. Tres cosas críticas:

**El `finally` con `imagen.close()` no es opcional.** CameraX trabaja con un número
fijo de buffers. Si uno no se libera, la cámara **se congela** — sin error, sin log,
sin pista.

**Los resultados se publican con `runOnUiThread`.** Tocar vistas desde otro hilo lanza
una excepción.

**Todo está envuelto en `try/catch`.** Una excepción no capturada en ese hilo cerraría
la app entera.

### `aBitmapRotado` — Dos trampas resueltas

**El `rowStride`.** El buffer de la cámara puede traer **relleno al final de cada
fila**: una imagen de 640 px de ancho puede venir en filas de 672. Copiar asumiendo
640 produce una imagen **inclinada en diagonal**.

**La rotación.** El sensor suele estar montado girado 90°. Sin aplicar
`getRotationDegrees()`, el modelo analizaría la escena tumbada.

### Las demás

| Función | Qué hace | Por qué |
|---|---|---|
| `iniciarCamara` | Obtiene el proveedor de cámara | Es asíncrono: la cámara tarda en estar lista |
| `vincularCasosDeUso` | Configura Preview e ImageAnalysis | Aquí se fijan `KEEP_ONLY_LATEST` y `RGBA_8888` |
| `actualizarEstado` | Muestra FPS y latencia | Se actualiza **una vez por segundo**: refrescar un `TextView` 30 veces por segundo cuesta más que la inferencia |
| `onDestroy` | Apaga el hilo y el detector | Sin esto quedan un hilo vivo y memoria nativa sin liberar |

---

## `Equipos.java` — Del identificador al nombre

Traduce `ankom_estufa` → "Estufa de Secado ANKOM", leyendo `assets/clases.json`.

Existe para no duplicar los nombres. El orden y los nombres de las clases se definen
**una vez** en `ml/clases.json`; de ahí sale `labels.txt` y de ahí sale esta copia en
assets. Escribir los nombres a mano en Java garantizaría que tarde o temprano se
desincronicen con el modelo.

Si no encuentra el nombre, devuelve el propio slug: es preferible mostrar
`ankom_estufa` que fallar.

---

---

## `fichas/ModalEquipo.java` — La hoja del equipo detectado

Lo que aparece al tocar una caja. Ofrece los dos caminos del flujo y decide cuál está
disponible.

| Camino | Requiere internet | Color |
|---|---|---|
| Ficha técnica (PDF) | No | Verde |
| Chat con Rumi | Sí | Dorado |

El código de color no es decorativo: **verde = disponible siempre, dorado = necesita
conexión**. Se mantiene en toda la app.

Cuando no hay internet, el botón del chat se deshabilita **y explica por qué**. Un
botón que no responde sin decir nada se lee como un fallo de la aplicación.

Lo mismo si un equipo no tuviera ficha: en vez de ofrecer un botón que no hace nada,
se muestra "No hay ficha para este equipo".

## `fichas/RepositorioFichas.java` — Acceso a los PDF

Los PDF viven en `assets/fichas/<slug>.pdf`, dentro del APK. Eso es lo que hace que
la ficha **funcione sin conexión**: es la mitad de la app que sigue sirviendo cuando
Rumi no está disponible.

**Un asset no es un archivo del sistema.** Vive comprimido dentro del APK y ninguna
aplicación externa puede abrirlo. Por eso `prepararParaAbrir` lo copia a la caché
antes de compartirlo con el visor de PDF.

Reutiliza la copia anterior si ya existe y coincide en tamaño: abrir la misma ficha
dos veces no debería reescribir 200 KB en disco.

## `EstadoRed.java` — ¿Hay internet de verdad?

Comprueba `NET_CAPABILITY_VALIDATED`, no solo si hay una red activa.

El matiz importa: **estar conectado al WiFi no significa tener internet**. El
laboratorio puede tener una red sin salida, o un portal cautivo que exige aceptar
condiciones. Con la comprobación simple, el chat se mostraría habilitado y el usuario
se encontraría un error de conexión después.

## Paquete `asistente` — El chat con voz

### La estructura del paquete

```
asistente/
├─ ChatActivity, ChatAdapter, Mensaje      la pantalla
└─ ia/
   ├─ AsistenteIA          interfaz: qué es "un asistente"
   ├─ RespuestaAsistente   lo que devuelve, en términos del dominio
   ├─ ProveedorClave       interfaz: de dónde sale la API key
   ├─ ClaveCompilada       implementación actual (local.properties)
   ├─ ReglaDeAlcance       decide si filtrar (pura, sin Android)
   ├─ AlcanceConsulta      adapta la regla a los datos de la app
   ├─ FabricaAsistente     único sitio que sabe qué implementación se usa
   └─ gemini/
      ├─ GeminiApi         interfaz Retrofit
      ├─ GeminiDto         las clases del JSON
      └─ AsistenteGemini   implementa AsistenteIA
```

La división no es decorativa. **`ChatActivity` no menciona a Gemini en ninguna
línea**: pide un asistente a la fábrica y recibe uno. Ese diseño ya se puso a prueba —
el asistente vivió primero en un backend FastAPI y luego pasó a Gemini directo, y la
pantalla no cambió.

### `AsistenteIA.java` — El contrato

Define qué significa "preguntar algo": una pregunta, el equipo detectado, el historial
y un callback con la respuesta o el error. Cambiar de proveedor significa escribir
otra implementación de esta interfaz.

### `RespuestaAsistente.java` — El resultado en términos del dominio

Texto y fuentes. Existe para que la pantalla no manipule las clases del JSON de ningún
proveedor: si Gemini cambia la forma de su respuesta, se ajusta la traducción en un
solo sitio.

### `ProveedorClave` y `ClaveCompilada`

Una interfaz de una sola función. Hoy la clave se compila desde `local.properties`;
está previsto moverla a Supabase o pedírsela al usuario. Cada opción será otra
implementación, y nada más cambiará.

`ClaveCompilada` devuelve `null` si no hay clave, y quien la usa avisa al usuario en
vez de reventar.

### `ReglaDeAlcance.java` — Decidir dónde buscar

La pieza con más lógica del asistente, y la única con pruebas automatizadas.

**El problema:** si el usuario llegó tocando la estufa ANKOM, la búsqueda debe
acotarse a esa ficha. Sin filtrar, la pregunta *"¿a qué temperatura trabaja la
estufa?"* devuelve una respuesta que mezcla los 102 °C de la ANKOM con los 300 °C de
la MEMMERT — comprobado contra la API real.

**La excepción:** si la pregunta nombra otro equipo, el filtro se levanta. Si no,
alguien frente a la balanza que pregunte por la incubadora recibiría "esa información
no está en las fichas" cuando sí está.

**El detalle que hace que funcione:** solo se usan palabras que identifican a **un
único** equipo. "ankom" aparece en tres equipos y "estufa" en dos; aceptándolas,
preguntar "por la estufa" coincidiría con la MEMMERT, levantaría el filtro y volvería
a mezclarlas. Tras descartar las ambiguas, a `ankom_estufa` le queda "secado" y a
`memmert` le quedan "memmert" y "universal".

Está separada de Android a propósito —no usa `Context` ni assets— para poder probarla
con tests de JVM. `ReglaDeAlcanceTest` cubre 11 casos, incluidos los límite.

### `AlcanceConsulta.java` — El puente

Lee los equipos de `clases.json` y se los pasa a la regla. Nada más. Existe para que
la regla no dependa de Android.

### `FabricaAsistente.java` — Dónde se decide el proveedor

Único sitio del proyecto que menciona `AsistenteGemini`. Cambiar de proveedor, o
alternar entre varios según configuración, se resuelve aquí.

### `gemini/GeminiApi.java` — La interfaz Retrofit

Un solo método: `POST models/{modelo}:generateContent`.

Se usa REST y no el SDK de Android por un motivo comprobado, no por preferencia: se
inspeccionaron `com.google.firebase:firebase-ai` 17.16.0 y
`com.google.ai.client.generativeai` 0.9.0, y **ninguno expone File Search**. Su clase
`Tool` ofrece funciones, ejecución de código, contexto de URL, Google Search y Google
Maps. Sin File Search no hay RAG.

La clave viaja en la cabecera `x-goog-api-key`, no en la URL: en la URL acabaría
escrita en los registros de cualquier proxy intermedio.

### `gemini/GeminiDto.java` — La forma del JSON

Todas las clases anidadas en un fichero porque no son lógica, son la forma del JSON;
verlas juntas permite compararlas de un vistazo con la petición real.

Dos métodos hacen algo más que declarar campos: `primerTexto()` concatena las partes
de la respuesta, y `fuentes()` extrae los documentos citados de `groundingMetadata` —
lo que distingue una respuesta verificable de una afirmación suelta.

### `gemini/AsistenteGemini.java` — La implementación

Concentra todo lo específico del proveedor: la URL, el modelo `gemini-3.6-flash`, el
identificador del almacén, la instrucción del sistema y la construcción del filtro.

**La instrucción del sistema no es un adorno.** Se comprobó preguntando por la
incubadora con el filtro puesto en otro equipo: sin instrucción, el modelo respondió
con tiempos y un método de dos etapas con pepsina que no está en ninguna ficha; con
ella, reconoció que no tenía el dato.

| Aspecto | Decisión | Por qué |
|---|---|---|
| Tiempo de lectura | 90 segundos | El modelo tarda varios segundos; los 10 s por defecto cortarían respuestas válidas |
| Historial | Últimos 6 turnos | Más contexto encarece sin mejorar: lo útil lo aporta la búsqueda |
| `temperature` | 0.2 | Fidelidad a la ficha, no redacción creativa |
| Errores | Traducidos por código HTTP | "Se agotó la cuota" es accionable; "429" no |

### `Mensaje.java` — Un mensaje en pantalla

Origen (usuario, asistente o error), texto, fuentes citadas y una marca `cargando`
para el mensaje provisional de "escribiendo…".

### `ChatAdapter.java` — Pintar la conversación

Un **único layout** para los tres tipos de mensaje; el adaptador cambia la alineación
y el fondo según el origen. Tres layouts casi idénticos serían más código para el
mismo resultado.

Muestra las **fuentes** bajo cada respuesta del asistente. Eso es lo que distingue una
respuesta verificable de una afirmación suelta: el usuario puede comprobar de qué
ficha salió el dato.

### `ChatActivity.java` — La pantalla

| Función | Qué hace | Por qué |
|---|---|---|
| `intentPara` | Abre el chat para un equipo | Método estático: quien llama no necesita conocer los nombres de los extras |
| `enviar` | Manda la pregunta al backend | Arma el historial **antes** de añadir el marcador de carga, para no enviar un turno vacío |
| `mostrarBienvenida` | Primer mensaje | Cambia según se abra desde una detección o suelto |
| `añadir` / `quitar` | Gestionan la lista | Notifican al adaptador y hacen scroll al final |
| `habilitarEntrada` | Bloquea mientras espera | Evita disparar varias preguntas superpuestas |
| `pedirEscucha` | Permiso de micrófono | Solo se pide al usarlo, no al abrir la pantalla |
| `alternarEscucha` | Inicia o detiene el dictado | Comprueba antes que el dispositivo tenga reconocimiento |
| `EscuchaSimple` | Recibe lo reconocido | Escribe los resultados parciales en vivo y **envía al terminar**: hablar y preguntar es un solo gesto |
| `prepararSintetizador` | Inicia TextToSpeech | Si no hay voz en español, oculta el botón en vez de fallar al pulsarlo |
| `alternarLectura` | Activa la voz | La lectura es opcional: en un laboratorio con gente alrededor puede no interesar |
| `onDestroy` | Libera reconocedor y sintetizador | Mantienen recursos nativos y conexiones a servicios del sistema |

**Los errores del reconocimiento se filtran.** `NO_MATCH` y `SPEECH_TIMEOUT` son
normales si el usuario no dijo nada; avisar en esos casos sería ruido.

**Solo se envían los últimos turnos del historial.** Una conversación larga encarece
la llamada sin mejorar la respuesta, porque el contexto útil lo aporta la búsqueda.

---

# Parte 2 — La herramienta de gestión de almacenes

`gestion_almacenes/gestionar.py` se ejecuta **en el PC**, no en la app. Administra el
almacén de fichas de Gemini: crear, subir, listar, borrar y probar consultas.

La clase `GestorAlmacenes` concentra el trato con la API y no sabe nada de la línea de
comandos; las funciones `cmd_*` solo traducen argumentos y presentan resultados. Así
la lógica se puede reutilizar desde otra interfaz sin arrastrar el CLI.

| Comando | Para qué |
|---|---|
| `listar` | Almacenes de la cuenta |
| `documentos` | Qué hay indexado y con qué metadatos |
| `crear` | Almacén nuevo |
| `subir` | Sube las fichas etiquetadas con `equipo=<slug>` |
| `borrar-documento` / `borrar-almacen` | Limpieza |
| `probar` | Lanza una consulta real, con o sin filtro |

**`subir` borra antes de subir.** Sin eso quedarían dos copias del mismo contenido y
la búsqueda devolvería fragmentos duplicados. Y espera a que la indexación termine,
porque es asíncrona: consultar un documento aún no indexado no devuelve nada.

**`probar` envía la misma instrucción que la app.** Es deliberado: sin ella el modelo
se inventa las respuestas, así que probar sin instrucción no representaría el
comportamiento real.

# Parte 3 — Los scripts de `ml/`

Herramientas de un solo uso, en Python, que preparan los datos. No forman parte de la
app. Usan solo la librería estándar, para que funcionen sin instalar nada.

## `prepare_images.py` — Normalizar las fotos

1. **HEIC → JPG.** 144 de las 340 fotos eran HEIC. Dos clases eran 100 % HEIC: sin
   esto no habrían existido para el modelo, y sin dar ningún error.
2. **Aplica la rotación EXIF.** Sin ella, el modelo entrena con imágenes giradas 90°.
3. **Reduce a 1280 px.** De 1.2 GB a 73 MB.
4. **Renombra con el prefijo de la clase.** Al etiquetar se sabe de un vistazo qué es.

También avisa del desbalance entre clases: así se detectó que `ankom_estufa` tenía 11
fotos frente a las 101 de `ankom_200_fiber_analyzer`.

## `split_dataset.py` — Dividir en train/val/test

Reparte 70/20/10. Lo importante: **cada imagen viaja siempre con su `.txt`**. Separar
una imagen de su etiqueta es el error más común al dividir a mano, y produce un
entrenamiento silenciosamente incorrecto.

## `check_dataset.py` — Verificar antes de entrenar

Detecta imágenes sin etiqueta, etiquetas huérfanas, líneas mal formadas, coordenadas
fuera de `[0,1]`, cajas de área cero y clases inexistentes.

Existe por una razón económica: **un dataset roto no da error al entrenar**, da un
modelo malo, y eso se descubre dos horas de GPU más tarde.

## `make_labels_txt.py` — Sincronizar las clases

Genera `app/src/main/assets/labels.txt` desde `ml/data.yaml`. Función trivial que
evita un fallo grave: si los dos archivos se desincronizan, **la app etiqueta mal cada
equipo sin dar ningún error**.

## `reconstruir_export.py` — Reparar el export de Label Studio

El formato "YOLO" exporta solo los `.txt`; el que incluye fotos es "YOLO with Images".
Este script vuelve a emparejar cada etiqueta con su imagen y quita el prefijo
hexadecimal que Label Studio añade.

## `train_yolo26.ipynb` — Entrenar en Colab

Dos celdas merecen mención:

- **La celda 8** imprime la firma del `.tflite`. Reveló que la entrada era NCHW, y sin
  ella el `Detector` habría estado mal escrito sin que nadie lo notara.
- **La celda 7** carga una **instancia nueva** antes de exportar. Exportar un modelo
  que ya pasó por `.val()` falla con `KeyError: 'feats'`.

---

# Parte 4 — Ideas transversales

Cuatro principios que se repiten en todo el código:

**Fallar con un mensaje, no en silencio.** Los peores errores de este proyecto no
lanzan excepciones: un dataset mal dividido, un orden de canales equivocado, unas
etiquetas desincronizadas. Todos producen un sistema que *funciona* y da resultados
incorrectos. De ahí tanta verificación explícita: `check_dataset.py`, la celda que
imprime la firma del modelo, el aviso cuando falta el `.tflite`, y que el estado en
pantalla diga si va por GPU o CPU.

**Adaptarse en tiempo de ejecución en lugar de asumir.** El `Detector` inspecciona el
modelo en vez de dar por hecho su formato. Cuando la exportación resultó NCHW en lugar
de NHWC, esa decisión ahorró reescribir la clase.

**Degradar, no romper.** Sin modelo la app muestra la cámara y avisa. Sin GPU cae a
CPU. Sin voz en español se oculta el botón. Sin `clases.json` se muestran los slugs.
Ninguna de esas situaciones cierra la app.

**Un solo origen para cada dato.** El orden de las clases se define una vez en
`ml/data.yaml` y de ahí se genera `labels.txt`. Los nombres visibles se definen una
vez en `ml/clases.json` y se copian a assets. Los colores se definen una vez en
`colors.xml` y se comparten con la configuración de Label Studio. Los datos
duplicados se desincronizan; los generados, no.
