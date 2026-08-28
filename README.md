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
CameraX (ImageAnalysis, RGBA_8888)
    │  frame rotado a la orientación de pantalla
    ▼
Detector  ── letterbox a 640×640 ──► LiteRT ──► [1, 300, 6]
    │                                            x1,y1,x2,y2,score,clase
    │  coordenadas normalizadas al frame
    ▼
OverlayView  ── dibuja cajas y detecta toques
```

El modelo se exporta **end-to-end** (sin NMS): la salida ya son detecciones
finales, así que no hay post-procesado de supresión de duplicados en Java.
`Detector` igualmente soporta salidas crudas estilo YOLOv8 por si se cambia de
modelo, y detecta automáticamente si la entrada es NCHW o NHWC.

## Estructura

| Ruta | Contenido |
|---|---|
| `app/` | Aplicación Android (Java) |
| `app/src/main/assets/` | `model.tflite` y `labels.txt` |
| `ml/` | Pipeline de datos y entrenamiento |
| `ml/train_yolo26.ipynb` | Notebook de Colab: entrenar, validar, exportar |
| `ml/scripts/` | Preparación de imágenes, división y verificación del dataset |
| `backend/` | Servicio del asistente RAG (temporal) |
| `documentacion/` | Decisiones técnicas y explicación del código |
| `documentacion/fichas/` | Fichas técnicas de los 7 equipos |

Las imágenes del laboratorio y el dataset **no están versionados** aquí: viven en
Google Drive. Ver [`ml/README.md`](ml/README.md) para el flujo completo.

## Stack

- **Android**: Java, minSdk 26, CameraX 1.4.2, LiteRT 1.4.2, Retrofit 2.11,
  RecyclerView, core-splashscreen
- **Modelo**: YOLO26n (Ultralytics), 640×640, float32, ~9 MB
- **Asistente**: Gemini `gemini-3.6-flash` con File Search (RAG gestionado),
  backend FastAPI, voz con `SpeechRecognizer` y `TextToSpeech` de Android
- **Etiquetado**: Label Studio

## Estado

- [x] Pipeline de datos y entrenamiento reproducible
- [x] Modelo entrenado y exportado a TFLite
- [x] Detección en vivo con CameraX y dibujo de cajas
- [x] Fichas técnicas de los 7 equipos
- [x] Asistente RAG por chat y voz — **backend temporal**, ver
      [`backend/ESTADO.md`](backend/ESTADO.md)
- [ ] Servicio definitivo del asistente (desplegado, con HTTPS y autenticación)
- [ ] Pantalla de ficha técnica dentro de la app

## Limitaciones conocidas

El dataset actual son 306 imágenes con 322 cajas (~1 por foto). La clase
`ankom_estufa` tiene solo 7 imágenes de entrenamiento y no se detecta de forma
fiable. Las métricas de validación (mAP50 = 0.985) están infladas porque muchas
fotos son ráfagas casi idénticas repartidas entre entrenamiento y validación.
