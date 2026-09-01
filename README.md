# Detección de Equipos de Rumiología

Aplicación Android que identifica en tiempo real los equipos del laboratorio de
Rumiología apuntando la cámara, usando un modelo YOLO26 entrenado a medida y
ejecutado en el dispositivo con LiteRT (TensorFlow Lite).

## Equipos reconocidos

| id | Clase | Equipo |
|---|---|---|
| 0 | `ankom_200_fiber_analyzer` | ANKOM 200 Fiber Analyzer |
| 1 | `ankom_daisy_incubator` | ANKOM DAISY Incubator |
| 2 | `ankom_estufa` | ANKOM Estufa |
| 3 | `aquasearcher_ab33m1` | AQUASEARCHER AB33M1 (OHAUS) |
| 4 | `contador_de_colonias` | Contador de colonias |
| 5 | `memmert` | MEMMERT |
| 6 | `ohaus_pr224` | Ohaus PR224 |

El orden es el contrato del proyecto: define los índices que devuelve el modelo y
debe coincidir en `ml/data.yaml`, `ml/clases.json` y `app/src/main/assets/labels.txt`.

## Arquitectura

```
BienvenidaActivity ──► MainActivity          (pantalla de lanzamiento)

DETECCIÓN — en el teléfono, sin internet
  CameraX ──► Detector (LiteRT) ──► [1, 300, 6] ──► OverlayView
                                          │             │ toque
                                          └──► tira de equipos (chips)
                                                        │
                                                   ModalEquipo
                                                   ├── Ficha técnica (PDF)  sin internet
                                                   └── Chat con Rumi        con internet
                                                            │
ASISTENTE — sin servidor propio                             ▼
  ChatActivity ──► AsistenteIA ──► Gemini + File Search ──► fichas .md
     (texto y voz)   (interfaz)      (filtrado por equipo)
        │
        └──► DialogoVoz: modo de voz persistente, turno tras turno
```

El modelo se exporta **end-to-end** (sin NMS): la salida ya son detecciones
finales, así que no hay post-procesado de supresión de duplicados en Java.
`Detector` igualmente soporta salidas crudas estilo YOLOv8 por si se cambia de
modelo, y detecta automáticamente si la entrada es NCHW o NHWC.

## Estructura

| Ruta | Contenido |
|---|---|
| `app/` | Aplicación Android (Java) |
| `app/src/main/assets/` | `model.tflite`, `labels.txt`, `clases.json`, fichas en PDF y fotos de los equipos |
| `ml/` | Pipeline de datos y entrenamiento |
| `ml/train_yolo26.ipynb` | Notebook de Colab: entrenar, validar, exportar |
| `ml/scripts/` | Preparación de imágenes, división y verificación del dataset |
| `gestion_almacenes/` | Herramienta de PC para administrar el almacén de fichas |
| `documentacion/DecisionesMovil.md` | Qué tecnología se eligió en cada punto y por qué |
| `documentacion/ExplicacionDetalle.md` | Qué hace cada clase y cada función, y por qué existe |
| `documentacion/PENDIENTES.md` | Lo que falta para la entrega, contra los lineamientos |
| `documentacion/fichas/` | Fichas técnicas de los 7 equipos (fuente en Markdown) |

Las imágenes del laboratorio y el dataset **no están versionados** aquí: viven en
Google Drive. Ver [`ml/README.md`](ml/README.md) para el flujo completo.

## Stack

- **Android**: Java, minSdk 26 / targetSdk 37, CameraX 1.4.2, LiteRT 1.4.2,
  Retrofit 2.11, Markwon 4.6.2 (Markdown en el chat), RecyclerView,
  core-splashscreen
- **Modelo**: YOLO26n (Ultralytics), 640×640, float32, ~9 MB
- **Asistente**: Gemini `gemini-3.6-flash` con File Search (RAG gestionado),
  llamado **directamente desde la app** por REST — sin servidor propio. Voz con
  `SpeechRecognizer` y `TextToSpeech` de Android
- **Seguridad**: la clave de Gemini la pone cada usuario en Ajustes y se cifra con
  AES-256-GCM y una llave del Android Keystore. El APK no lleva ninguna clave dentro
- **Etiquetado**: Label Studio

## Estado

- [x] Pipeline de datos y entrenamiento reproducible
- [x] Modelo entrenado y exportado a TFLite
- [x] Detección en vivo con CameraX y dibujo de cajas
- [x] Fichas técnicas de los 7 equipos
- [x] Asistente RAG por chat y voz, sin servidor propio
- [x] Búsqueda acotada al equipo detectado (filtro por metadatos)
- [x] Clave de la API por usuario, cifrada en el dispositivo (pantalla Ajustes)
- [x] Ficha técnica en PDF, disponible sin conexión
- [x] Identidad visual del proyecto aplicada
- [x] Pantalla de bienvenida y tira de equipos detectados, con foto en el modal
- [x] Modo de voz persistente: se conversa hablando, turno tras turno
- [x] Voz de Rumi elegible en Ajustes, y respuestas renderizadas en Markdown
- [x] Modo oscuro con interruptor manual (Claro / Oscuro / Seguir el sistema)
- [x] Dataset ampliado a 854 imágenes: las 7 clases superan el mínimo de 80
- [ ] Streaming de respuestas
- [ ] Ajustar el presupuesto de razonamiento del modelo (`thinkingLevel`)
- [ ] Reforzar `memmert`, la clase con menos ejemplos (57 cajas en train)

Lo que falta de cara a la entrega —dataset, split 70/15/15, video y evidencias— está
en [`documentacion/PENDIENTES.md`](documentacion/PENDIENTES.md).

## Limitaciones conocidas

El dataset son **854 imágenes con 1436 cajas** (1,68 por foto), repartidas
70/15/15. Las 7 clases superan el mínimo de 80 fotos.

Lo que sigue abierto:

- **`memmert` es la clase más justa**: 89 fotos y 57 cajas de entrenamiento. Es
  además la que se confunde con `ankom_estufa`, porque ambas son estufas.
- **Desbalance 4,7:1** entre `ohaus_pr224` (419 cajas) y `memmert` (89).
- **No hay imágenes negativas** (escenas sin ningún equipo); ayudarían a reducir
  falsos positivos.

Sobre las métricas: el mAP50 de 0.985 del primer entrenamiento **estaba
inflado**, porque muchas fotos eran ráfagas casi idénticas repartidas entre
entrenamiento y validación. El dataset actual es bastante más independiente, así
que las cifras nuevas serán más bajas y a la vez más honestas — no son
comparables con aquel número.
