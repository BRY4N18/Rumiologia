# ml/ — Modelo de detección (YOLO26)

Todo lo relacionado con entrenar el detector de equipos del laboratorio.
El resultado final de esta carpeta son **dos archivos** que consume la app Android:

- `app/src/main/assets/model.tflite`
- `app/src/main/assets/labels.txt`

## Dónde vive cada cosa

| Lugar | Contenido | Por qué |
|---|---|---|
| Google Drive `MyDrive/Laboratorio_Rumiologia/` | imágenes, etiquetas, `runs/` | almacén y respaldo; Colab lo monta |
| Colab `/content/dataset/` | copia descomprimida durante la sesión | leer archivos sueltos desde Drive es lentísimo |
| Este repo | scripts, notebook, `data.yaml`, `.tflite` final | las imágenes **no** entran al proyecto Android |

Estructura esperada en Drive:

```
MyDrive/Laboratorio_Rumiologia/
├─ raw/            imágenes originales, intactas
├─ dataset/        ya etiquetado y dividido
│  ├─ images/train  val  test
│  ├─ labels/train  val  test
│  └─ data.yaml
├─ dataset.zip     ← lo que Colab copia y descomprime
└─ runs/           checkpoints del entrenamiento
```

## Flujo completo

### 0. Normalizar las fotos crudas

Las fotos van en `ml/imagenes_crudas/<Nombre del equipo>/` (una subcarpeta por
equipo, tal como salen de Drive). Luego:

```bash
.venv\Scripts\python.exe ml\scripts\prepare_images.py
```

Convierte HEIC a JPG (ni Label Studio ni YOLO leen HEIC), aplica la rotación EXIF,
reduce el lado mayor a 1280 px y renombra todo con el prefijo de la clase, dejando
el resultado plano en `ml/imagenes_listas/`. **Esa** es la carpeta que se importa
en Label Studio.

La correspondencia carpeta → clase se define en `ml/clases.json`.

### 1. Etiquetar con Label Studio

```bash
pip install label-studio
```

```bash
label-studio start
```

En `http://localhost:8080`:

1. **Create Project** → `Laboratorio_Rumiologia`
2. **Labeling Setup** → plantilla *Object Detection with Bounding Boxes*
3. Define las clases **en el orden definitivo** (ese orden son los índices 0,1,2… del modelo)
4. **Import** → arrastra las imágenes descargadas de Drive
5. Etiqueta pegando la caja al objeto, sin margen extra
6. **Export → YOLO** → obtienes un zip con `images/`, `labels/`, `classes.txt`

Label Studio no conecta con Google Drive (soporta S3, GCS, Azure y Redis),
así que se etiqueta en local y el resultado se vuelve a subir a Drive.

Recomendado: 150–400 imágenes por clase, variando ángulo, distancia, iluminación
y fondo, más ~10 % de imágenes negativas (escenas sin ningún equipo).

**Regla crítica:** en cada foto hay que etiquetar **todos** los equipos de la lista
que aparezcan, no solo el principal. Muchas fotos son tomas abiertas del laboratorio
donde se ven varios equipos a la vez. Si uno de ellos queda sin caja, el modelo
aprende que ese equipo es "fondo" y después no lo detecta.

### 2. Ajustar las clases

Edita `ml/data.yaml` y pon las clases reales en `names`, en el **mismo orden**
que usaste en Label Studio. Luego genera el archivo que usa Android:

```bash
python ml/scripts/make_labels_txt.py
```

### 3. Dividir en train / val / test

```bash
python ml/scripts/split_dataset.py --src ml/export_labelstudio --dst dataset --keep-negatives
```

Opciones útiles: `--dry-run` para ver el reparto sin copiar nada,
`--train/--val/--test` para cambiar los porcentajes (por defecto 70/20/10),
`--seed` para reproducir exactamente el mismo split.

### 4. Verificar antes de subir

```bash
python ml/scripts/check_dataset.py --dataset dataset --data-yaml ml/data.yaml
```

Detecta imágenes sin etiqueta, coordenadas fuera de rango, clases inexistentes
y cajas de área cero. **No subas nada hasta que salga `OK`.**

### 5. Subir a Drive

Copia `ml/data.yaml` dentro de `dataset/`, comprime la carpeta como `dataset.zip`
y súbela a `MyDrive/Laboratorio_Rumiologia/`.

### 6. Entrenar en Colab

Sube `ml/train_yolo26.ipynb` a Colab (o ábrelo desde Drive), selecciona
**Entorno de ejecución → Cambiar tipo de entorno → T4 GPU** y ejecuta las celdas
en orden.

Tiempo aproximado en T4 con el modelo *nano* a 640 px:

| Imágenes | 150 épocas |
|---|---|
| 500 | 45 min – 1 h |
| 1.000 | 1.5 – 2 h |
| 2.000 | 3 – 3.5 h |

Con `patience=30` el early stopping suele cortar cerca de la mitad de ese tiempo.

### 7. Llevar el modelo a Android

Descarga el `.tflite` de la celda 9, renómbralo a `model.tflite` y déjalo en
`app/src/main/assets/`. Guarda también `best.pt` (sirve para reentrenar después).

**Importante:** anota la salida de la celda 8 (firma del modelo). Si el tensor de
salida es `[1, N, 6]`, la salida es end-to-end y en Android no hace falta escribir
NMS; si es `[1, 4+clases, 8400]`, sí hay que decodificar y filtrar a mano.

## Archivos

| Archivo | Función |
|---|---|
| `data.yaml` | clases y rutas del dataset — el contrato del proyecto |
| `train_yolo26.ipynb` | notebook de Colab: entrenar, validar, exportar |
| `scripts/split_dataset.py` | divide el export de Label Studio en train/val/test |
| `scripts/check_dataset.py` | valida integridad del dataset |
| `scripts/make_labels_txt.py` | genera `labels.txt` para Android desde `data.yaml` |
| `models/` | destino local de los modelos descargados |

Los scripts usan solo la librería estándar de Python: no hay que instalar nada
para ejecutarlos.
