# Explicación detallada del código

Qué hace cada clase y cada función del proyecto, y **por qué existe**. No es una
lectura línea por línea del código: es la explicación de las decisiones que hay
detrás de cada pieza.

Para las decisiones de tecnología (qué librerías y por qué), ver
[`DecisionesMovil.md`](DecisionesMovil.md).

---

## El recorrido de un frame

Antes de entrar en cada clase, conviene tener el flujo completo en la cabeza, porque
todo el diseño gira alrededor de él:

```
 1. CameraX captura un frame                      (ImageProxy, RGBA_8888)
 2. MainActivity lo convierte a Bitmap y lo rota  (aBitmapRotado)
 3. Detector lo encaja en 640×640 con letterbox   (detect)
 4. Detector llena el buffer de entrada           (llenarBufferEntrada)
 5. LiteRT ejecuta la red neuronal                (interprete.run)
 6. Detector interpreta el tensor de salida       (interpretarEndToEnd)
 7. Detector deshace el letterbox                 (coordenadas del frame)
 8. OverlayView dibuja las cajas en pantalla      (onDraw → aVista)
 9. El usuario toca una caja                      (onTouchEvent → deteccionEn)
```

Esto ocurre entre 5 y 20 veces por segundo. Cada paso tiene un motivo, y varios de
ellos existen para resolver un problema que no es evidente hasta que falla.

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

Una clase con nombres explícitos hace imposible esa confusión.

### La decisión importante: coordenadas normalizadas

El campo `box` guarda valores **entre 0 y 1**, no píxeles. Es decir, `0.5` significa
"la mitad del ancho", sin importar si el frame es de 640, 1280 o 4000 píxeles.

Esto resuelve un problema real: el frame de la cámara, la entrada del modelo y la
pantalla tienen tamaños distintos. Si las cajas viajaran en píxeles, habría que
recordar en cada punto del código *a qué* tamaño se refieren. Normalizadas, cada vista
las convierte a su propio espacio cuando las necesita y no hay ambigüedad posible.

### Funciones

| Función | Qué hace | Por qué |
|---|---|---|
| `area()` | Superficie de la caja | Sirve para desempatar cuando el usuario toca cajas superpuestas |
| `toString()` | Representación legible | Para logs y depuración; hoy también alimenta el Toast provisional |

---

## `Detector.java` — El cerebro

Envuelve el intérprete de LiteRT y traduce **un Bitmap a una lista de detecciones**.
Es la clase más compleja y la que concentra los detalles delicados.

### Por qué está separada de la Activity

Aislar aquí todo lo relativo al modelo tiene un efecto práctico: la Activity no sabe
nada de tensores, buffers ni cuantización. Si mañana se cambia de modelo o de motor de
inferencia, solo cambia esta clase. Y al no depender de la cámara, puede ejecutarse
sobre cualquier Bitmap: una foto de galería, una imagen de prueba, lo que sea.

### El constructor: adaptarse al modelo, no al revés

Cuando se escribió esta clase todavía no existía el `.tflite`, así que no se sabía qué
forma tendría. En lugar de adivinar, el constructor **inspecciona el modelo al
cargarlo** y se configura solo:

**Detecta el orden de los canales.** Un modelo puede esperar la imagen de dos formas:

- `[1, 3, 640, 640]` → **NCHW**, canales primero (convención de PyTorch)
- `[1, 640, 640, 3]` → **NHWC**, canales al final (convención clásica de TensorFlow)

No es un detalle cosmético: cambia el orden en que hay que escribir los bytes. Si se
equivoca, el modelo recibe ruido y no detecta nada — **sin lanzar ningún error**. El
modelo actual resultó ser NCHW.

**Detecta el formato de la salida.** También hay dos posibilidades:

- `[1, 300, 6]` → **end-to-end**: cada fila ya es una detección final
  (`x1, y1, x2, y2, score, clase`). Es lo que entrega YOLO26.
- `[1, 4+clases, 8400]` → **cruda**, estilo YOLOv8: miles de candidatas que hay que
  decodificar y filtrar.

**Lee los parámetros de cuantización**, por si el modelo fuera int8 en lugar de
float32.

El resultado es una clase que funciona con el modelo actual y seguiría funcionando si
se reentrenara con otra arquitectura o se exportara por otra ruta. Esa flexibilidad no
fue un lujo: se usó de verdad cuando la exportación resultó ser NCHW.

### `detect(Bitmap)` — La función principal

Cuatro pasos:

**1. Letterbox.** El modelo exige exactamente 640×640, pero el frame de la cámara es
rectangular. Hay dos formas de encajarlo:

- *Estirar* la imagen hasta el cuadrado → deforma los objetos. Un equipo ancho se ve
  achatado y el modelo, entrenado con proporciones reales, falla.
- *Letterbox*: escalar manteniendo la proporción y **rellenar de gris** lo que sobra.

Se usa letterbox, con el gris `(114,114,114)` — exactamente el que usa Ultralytics al
entrenar. Coincidir con las condiciones de entrenamiento importa.

**2. Llenar el buffer de entrada** con los píxeles normalizados.

**3. Ejecutar la red** — `interprete.run()`, la única línea donde ocurre la inferencia.

**4. Deshacer el letterbox.** El modelo devuelve coordenadas dentro del cuadrado de
640×640, que incluye las bandas grises. Hay que restarlas para obtener coordenadas
respecto a la imagen real. **Omitir este paso es el error clásico**: las cajas
aparecen desplazadas y encogidas, y cuesta entender por qué.

### Las demás funciones

| Función | Qué hace | Por qué existe |
|---|---|---|
| `llenarBufferEntrada` | Copia los píxeles al buffer | Aquí se aplica la diferencia NCHW/NHWC: por planos completos o entrelazado |
| `cuantizar` | Convierte float a int8 | Solo para modelos cuantizados; el actual no lo usa, pero está listo |
| `leerSalida` | Convierte el buffer de salida a floats | Unifica float32, int8 y uint8 en un solo array, para que el resto del código no se entere del tipo |
| `interpretarEndToEnd` | Lee `[1, 300, 6]` | Camino del modelo actual: filtra por confianza y ya está |
| `interpretarCrudo` | Lee `[1, 4+clases, 8400]` | Camino alternativo: recorre miles de anclas y elige la mejor clase de cada una |
| `nms` | Elimina cajas duplicadas | **Solo se usa en el camino crudo.** Con YOLO26 no hace falta |
| `iou` | Cuánto se solapan dos cajas | Es la medida que usa `nms` para decidir si dos cajas son el mismo objeto |
| `recortarA01` | Recorta al rango 0..1 | Un objeto cortado por el borde puede dar coordenadas fuera de la imagen |
| `modeloDisponible` | ¿Existe el `.tflite`? | Permite que la app arranque y muestre un aviso claro en vez de reventar |
| `cargarModelo` | Mapea el archivo en memoria | Usa `FileChannel.map`: el modelo **no se copia** a RAM, se lee del APK. Por eso `noCompress 'tflite'` es obligatorio |
| `cargarEtiquetas` | Lee `labels.txt` | Traduce el índice numérico a nombre de clase |
| `describirModelo` | Resumen legible | Se muestra en pantalla al arrancar: confirma de un vistazo qué modelo se cargó |
| `close` | Libera el intérprete | Sin esto se filtra memoria nativa, que el recolector de basura de Java no gestiona |

### Sobre `interpretarEndToEnd` y las coordenadas

Dentro hay una comprobación que parece un truco sucio:

```java
if (x2 > 1.5f || y2 > 1.5f) { /* dividir por el tamaño de entrada */ }
```

Es deliberado. Algunas exportaciones devuelven coordenadas normalizadas (0 a 1) y
otras en píxeles del tensor de entrada (0 a 640). Como una coordenada normalizada
nunca supera 1, un valor mayor que 1.5 solo puede ser píxeles. Es una heurística, pero
resistente, y evita que la app se rompa si el modelo se reexporta de otra forma.

---

## `OverlayView.java` — Dibujar y detectar toques

Una vista transparente que se superpone a la cámara. No dibuja la imagen: **solo las
cajas encima**.

### Por qué una vista aparte

Se podría dibujar sobre la propia `PreviewView`, pero separarlas tiene dos ventajas:
la vista previa se refresca a la velocidad de la cámara mientras el overlay solo se
redibuja cuando hay detecciones nuevas, y el overlay funciona igual sobre cualquier
fondo — cámara, foto o imagen de prueba.

### El problema central: la conversión de coordenadas

Es lo más delicado de la clase. Hay tres espacios distintos en juego:

1. El **frame de la cámara** (por ejemplo 640×480)
2. La **pantalla** (por ejemplo 1080×2400)
3. Las **coordenadas normalizadas** que trae cada `Detection`

La `PreviewView` usa `FILL_CENTER`: escala el vídeo hasta **cubrir** toda la vista y
recorta lo que sobra. No lo encaja dentro dejando bandas.

Por eso `aVista()` no puede hacer una simple regla de tres. Replica el mismo recorte:
escala por el factor **mayor** de los dos ejes, y centra. Si se usara el factor menor
—el error natural— las cajas aparecerían desplazadas respecto al equipo real, con un
desfase que crece hacia los bordes.

### Funciones

| Función | Qué hace | Por qué |
|---|---|---|
| `init` | Prepara pinceles y colores | Se ejecuta en el constructor, una vez, no en cada dibujado |
| `leerColoresClase` | Carga los colores por clase | Usa `obtainTypedArray`, **no** `getStringArray`: aapt2 compila los `#RRGGBB` como colores, y pedirlos como texto devuelve nulos. Esto provocó un cierre inmediato de la app |
| `setResults` | Publica nuevas detecciones | Usa `postInvalidate()` porque **se llama desde el hilo de análisis**, no del principal |
| `clear` | Borra las cajas | Para cuando no hay nada que mostrar |
| `onDraw` | Dibuja cajas y etiquetas | Si la etiqueta no cabe encima de la caja, la coloca dentro: en objetos pegados al borde superior se saldría de la pantalla |
| `aVista` | Convierte normalizado → píxeles | Replica el recorte `FILL_CENTER` (explicado arriba) |
| `onTouchEvent` | Detecta el toque | Solo reacciona a `ACTION_UP`: al soltar, no al presionar |
| `deteccionEn` | Qué caja se tocó | Si hay varias superpuestas gana **la más pequeña**: normalmente es la que el usuario quería señalar, no el equipo grande del fondo |
| `colorDe` | Color de una clase | Usa módulo, así nunca se sale del array aunque haya más clases que colores |
| `nombreLegible` | `ankom_estufa` → `Ankom estufa` | Provisional, hasta conectar los nombres reales de `clases.json` |

### Un detalle de rendimiento

`onDraw` se ejecuta muchas veces por segundo. Por eso los objetos `Paint` y el `RectF`
temporal se crean **una sola vez** como campos de la clase, no dentro del método.
Crear objetos en `onDraw` genera basura constante y provoca tirones al dibujar.

---

## `MainActivity.java` — El coordinador

Une todas las piezas: permisos, cámara, detector y overlay.

### `onCreate` — El orden importa

1. Enlaza las vistas
2. Ajusta los *insets* del sistema (barra de estado)
3. Registra qué hacer cuando se toca una detección
4. Crea el hilo de análisis
5. Carga el detector
6. Pide permiso de cámara **o** la inicia si ya lo tiene

El detector se carga **antes** que la cámara a propósito: si falta el modelo, se
muestra el aviso de inmediato en vez de arrancar la cámara para nada.

### `prepararDetector` — Fallar de forma útil

Si no existe `model.tflite`, la app **no se cierra**: muestra la cámara y un mensaje
explicando qué falta. Eso permitió desarrollar toda la interfaz durante los días en
que el modelo aún no estaba entrenado. Un fallo silencioso o un cierre habrían
bloqueado el trabajo.

### `analizarFrame` — El corazón, y el punto peligroso

Se ejecuta **en el hilo de análisis**, no en el principal. Tres cosas críticas:

**El `finally` con `imagen.close()` no es opcional.** CameraX trabaja con un número
fijo de buffers. Si uno no se libera, la cámara se queda sin buffers disponibles y
**se congela** — sin error, sin log, sin pista. Está en un `finally` para que se
ejecute incluso si el detector lanza una excepción.

**Los resultados se publican con `runOnUiThread`.** Tocar vistas desde otro hilo lanza
una excepción en Android.

**Todo está envuelto en `try/catch`.** Una excepción no capturada en el hilo de
análisis cerraría la app entera. Aquí se registra el fallo y se sigue con el
siguiente frame.

### `aBitmapRotado` — Dos trampas resueltas

**El `rowStride`.** El buffer de la cámara puede tener **relleno al final de cada
fila**: por eficiencia de memoria, una imagen de 640 píxeles de ancho puede venir en
filas de 672. Si se copia asumiendo 640, cada fila queda desplazada un poco más que la
anterior y la imagen sale **inclinada en diagonal**. Por eso se crea el Bitmap con el
ancho del buffer y después se recorta al ancho real.

**La rotación.** El sensor de la cámara suele estar montado girado 90° respecto a la
pantalla. Sin aplicar `getRotationDegrees()`, el modelo analizaría la escena tumbada —
y al haber sido entrenado con fotos derechas, fallaría casi siempre.

### Las demás

| Función | Qué hace | Por qué |
|---|---|---|
| `iniciarCamara` | Obtiene el proveedor de cámara | Es asíncrono: la cámara tarda en estar lista |
| `vincularCasosDeUso` | Configura Preview e ImageAnalysis | Aquí se fijan `KEEP_ONLY_LATEST` y `RGBA_8888` |
| `actualizarEstado` | Muestra FPS y latencia | Se actualiza **una vez por segundo**, no por frame: refrescar un `TextView` 30 veces por segundo cuesta más que la propia inferencia |
| `onDestroy` | Apaga el hilo y el detector | Sin esto quedan un hilo vivo y memoria nativa sin liberar |

### El campo `bufferFrame`

Es un Bitmap reutilizado entre frames. Crear uno nuevo 30 veces por segundo generaría
decenas de megabytes de basura por segundo y provocaría pausas del recolector muy
visibles. Solo se recrea si cambia el tamaño del frame.

---

# Parte 2 — Los scripts de `ml/`

Herramientas de un solo uso, en Python, que preparan los datos. No forman parte de la
app: se ejecutan en el PC antes de entrenar. Usan solo la librería estándar, para que
funcionen sin instalar nada.

## `prepare_images.py` — Normalizar las fotos

Convierte `ml/imagenes_crudas/<Equipo>/` en una carpeta plana lista para etiquetar.
Hace cuatro cosas, todas necesarias por un motivo concreto:

1. **HEIC → JPG.** 144 de las 340 fotos eran HEIC (iPhone), formato que ni Label
   Studio ni YOLO leen. Dos clases eran 100 % HEIC: sin esto no habrían existido para
   el modelo, y sin dar ningún error.
2. **Aplica la rotación EXIF.** Las fotos de móvil guardan la orientación como
   metadato. Sin aplicarla, el modelo entrena con imágenes giradas 90° respecto a lo
   que ve el usuario.
3. **Reduce a 1280 px.** De 1.2 GB a 73 MB. YOLO entrena a 640 de todas formas.
4. **Renombra con el prefijo de la clase.** Nombres únicos, sin espacios ni símbolos
   raros, y al etiquetar se sabe de un vistazo qué equipo es.

También avisa del desbalance entre clases, que es como se detectó que `ankom_estufa`
tenía solo 11 fotos frente a las 101 de `ankom_200_fiber_analyzer`.

## `split_dataset.py` — Dividir en train/val/test

Reparte 70/20/10. Lo importante: **cada imagen viaja siempre con su `.txt`**. Separar
una imagen de su etiqueta es el error más común al dividir a mano, y produce un
entrenamiento silenciosamente incorrecto.

Otras protecciones: detiene el proceso si hay nombres de archivo repetidos (romperían
la correspondencia), admite `--seed` para reproducir el mismo reparto, y trata las
imágenes sin etiqueta como negativos solo si se pide explícitamente.

## `check_dataset.py` — Verificar antes de entrenar

Se ejecuta antes de subir nada a Colab. Detecta imágenes sin etiqueta, etiquetas
huérfanas, líneas mal formadas, coordenadas fuera del rango `[0,1]`, cajas de área
cero y clases que no existen en `data.yaml`.

Existe por una razón económica: **un dataset roto no da error al entrenar**, da un
modelo malo. Y eso se descubre dos horas de GPU más tarde. Este script convierte ese
fallo tardío en un mensaje inmediato.

También avisa de clases con pocas cajas o ausentes en validación — así se supo que la
métrica de `ankom_estufa` se calculaba sobre una sola instancia y por tanto no
significaba nada.

## `make_labels_txt.py` — Sincronizar las clases

Genera `app/src/main/assets/labels.txt` desde `ml/data.yaml`.

Es una función trivial que evita un fallo grave. El modelo devuelve índices (0, 1, 2…)
y `labels.txt` los traduce a nombres. Si los dos archivos se editan a mano y se
desincronizan, **la app etiqueta mal cada equipo sin dar ningún error**. Generando uno
desde el otro, eso no puede ocurrir.

## `reconstruir_export.py` — Reparar el export de Label Studio

Label Studio ofrece dos formatos: "YOLO" (solo los `.txt`) y "YOLO with Images". Con el
primero, las imágenes no vienen. Este script vuelve a emparejar cada etiqueta con su
imagen original y quita el prefijo hexadecimal que Label Studio añade al subir.

## `train_yolo26.ipynb` — Entrenar en Colab

El notebook completo: montar Drive, entrenar, validar, exportar e inspeccionar el
modelo. Dos celdas merecen mención:

- **La celda 8** imprime la firma del `.tflite` exportado. Es la que reveló que la
  entrada era NCHW, y sin ella el `Detector` habría estado mal escrito sin que nadie
  lo notara.
- **La celda 7** carga una **instancia nueva** del modelo antes de exportar. Exportar
  un modelo que ya pasó por `.val()` falla con `KeyError: 'feats'`.

---

# Parte 3 — Ideas transversales

Tres principios que se repiten en todo el código y explican muchas decisiones
concretas:

**Fallar con un mensaje, no en silencio.** Los errores peores de este proyecto no
lanzan excepciones: un dataset mal dividido, un orden de canales equivocado, unas
etiquetas desincronizadas. Todos producen un sistema que *funciona* y da resultados
incorrectos. Por eso hay tanta verificación explícita: `check_dataset.py`, la celda
que imprime la firma del modelo, el aviso en pantalla cuando falta el `.tflite`.

**Adaptarse en tiempo de ejecución en lugar de asumir.** El `Detector` inspecciona el
modelo en vez de dar por hecho su formato. Cuando la exportación resultó ser NCHW en
lugar de NHWC, esa decisión ahorró tener que reescribir la clase.

**Un solo origen para cada dato.** El orden de las clases se define **una vez** en
`ml/data.yaml` y de ahí se genera `labels.txt`. Los colores se definen una vez en
`colors.xml` y se comparten con la configuración de Label Studio. Los datos duplicados
se desincronizan; los generados, no.
