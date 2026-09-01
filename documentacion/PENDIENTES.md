# Pendientes para la entrega final

Comparación del proyecto contra los lineamientos de la asignación (Tema 4 —
Laboratorio de Rumiología), hecha el 2026-08-30 y **actualizada el 2026-09-01**
tras la segunda sesión de fotos y el re-etiquetado completo. Consolida lo que
estaba repartido entre el `README.md` raíz y `DecisionesMovil.md`, más lo que
no tenía dónde vivir todavía.

No repite lo que ya está resuelto (ver "Estado" en `README.md`). Es solo lo
que falta, ordenado por qué tan bloqueante es.

## 1. Dataset — RESUELTO (2026-09-01)

**Las 7 clases superaron el mínimo de 80 fotos.** El lineamiento pide 80–150
fotos por tipo de equipo, y tras la segunda sesión de fotos (571 imágenes
nuevas tomadas por el equipo el 2026-08-31) y el re-etiquetado completo en
Label Studio, el conteo medido sobre el export es:

| Clase | Fotos | Cajas | Estado |
|---|---:|---:|---|
| `ankom_200_fiber_analyzer` | 107 | 107 | OK |
| `ankom_daisy_incubator` | 157 | 216 | OK |
| `ankom_estufa` | 102 | 102 | OK (venía de 10) |
| `aquasearcher_ab33m1` | 218 | 286 | OK |
| `contador_de_colonias` | 217 | 217 | OK |
| `memmert` | 89 | 89 | OK, la más justa |
| `ohaus_pr224` | 313 | 419 | OK |

**854 imágenes y 1436 cajas**, frente a las 306 imágenes y 322 cajas
anteriores. La densidad subió de **1,05 a 1,68 cajas por foto**: la regla de
etiquetar todos los equipos visibles sí se aplicó esta vez.

Lo que queda abierto de este punto, ya no como bloqueante sino como mejora:

- [ ] `memmert` es ahora el eslabón débil: 89 fotos y solo **57 cajas en
  train**. Es además la que se confunde con `ankom_estufa`. Si el modelo falla
  en alguna clase, es la primera candidata.
- [ ] Desbalance **4,7:1** entre `ohaus_pr224` (419 cajas) y `memmert` (89).
  `prepare_images.py` avisa por encima de 3:1. No impide entrenar, pero explica
  de antemano si las clases con menos ejemplos rinden peor.
- [ ] **No hay imágenes negativas** (escenas sin ningún equipo). `ml/README.md`
  recomienda ~10 %; ayudan a reducir falsos positivos.
- [ ] Quedaron **24 tareas sin anotar** de las 878 subidas. El snapshot se
  generó con 854. Terminarlas sumaría un poco más de material.

### Conteo histórico (2026-08-30), antes de la segunda sesión

Se conserva como referencia de dónde se partía. El lineamiento pide
**80–150 fotos por tipo de equipo**.

| Clase | Fotos crudas hoy | Faltan para el mínimo (80) | Cajas etiquetadas (train/val/test) |
|---|---:|---:|---|
| `ankom_estufa` | **11** | **69** | 7 / 1 / 2 = 10 |
| `ankom_daisy_incubator` | 47 | 33 | 31 / 5 / 6 = 42 |
| `memmert` | 41 | 39 | 26 / 12 / 4 = 42 |
| `aquasearcher_ab33m1` | 45 | 35 | 33 / 15 / 4 = 52 |
| `ohaus_pr224` | 44 | 36 | 29 / 10 / 7 = 46 |
| `contador_de_colonias` | 51 | 29 | 46 / 8 / 2 = 56 |
| `ankom_200_fiber_analyzer` | 101 | 0 (ya en rango) | 51 / 15 / 8 = 74 |

**Faltaban 241 fotos nuevas** para que las 6 clases por debajo del mínimo
llegaran a 80. El dataset de entonces tenía 306 imágenes con 322 cajas
(~1.05 cajas/foto). Todo eso quedó resuelto con la sesión del 2026-08-31.

- [x] ~~Correr `prepare_images.py --dry-run` para saber el déficit por clase~~
- [x] ~~Nueva sesión de fotos en el laboratorio~~ — hecha el 2026-08-31 por
  Belinda (165), Mario (353) y Melanie (53): **571 fotos nuevas**, verificadas
  sin duplicados contra las existentes.
- [x] ~~Etiquetar todos los equipos visibles en cada foto~~ — la densidad pasó
  de 1,05 a **1,68 cajas por foto**.
- [x] ~~Volver a correr el pipeline completo~~ — `prepare_images.py` →
  Label Studio (HumanSignal Cloud) → `split_dataset.py` → `check_dataset.py`
  (`OK`) → `dataset.zip` regenerado.

Notas de método que valen para la próxima vez:

- `prepare_images.py` **renumera desde 1 en cada corrida y no limpia la salida**.
  Correrlo dos veces con distinto número de fotos cambia a qué foto apunta cada
  nombre y rompe la correspondencia con lo ya etiquetado. Por eso se le
  agregaron `--entrada`, `--salida`, `--sufijo` y una guarda que aborta antes de
  pisar archivos existentes.
- El importador de HumanSignal **acepta 100 archivos por tanda**: las 878 se
  subieron en 9 lotes.
- El export **YOLO a secas no trae las imágenes**; hay que pedir
  **YOLO_WITH_IMAGES**. Los `.txt` conservan el nombre original detrás de un
  hash (`003e114c-mario_s2_0042.txt`), así que también se pueden reemparejar
  con las fotos locales si hiciera falta.

## 2. Split 70/15/15 — RESUELTO (2026-09-01)

`ml/scripts/split_dataset.py` **sigue teniendo por defecto** `--val 0.20
--test 0.10`, así que los dos flags hay que pasarlos siempre a mano:

```
python ml/scripts/split_dataset.py --src ml/export_labelstudio --dst dataset \
    --val 0.15 --test 0.15 --keep-negatives
```

- [x] ~~Volver a dividir con `--val 0.15 --test 0.15`~~ — hecho: el dataset
  actual es **597 train / 128 val / 129 test** sobre 854 imágenes, y
  `check_dataset.py` devuelve `OK`.

## 3. Arquitectura del RAG — decisión tomada, hay que documentarla bien

El enunciado dice literalmente que "el backend o servidor MCP deberá buscar
los fragmentos pertinentes" y que "todos los proyectos mantienen exactamente
la misma arquitectura". Este proyecto llama a Gemini File Search **directo
desde Android**, sin backend propio (ver `DecisionesMovil.md`, sección "Sin
servidor propio").

**Decisión (2026-08-30): se mantiene así.** Justificación para la entrega:

- La app nunca envía documentos completos al LLM — Gemini File Search
  trocea, indexa y busca los fragmentos, que es el resultado que pide el
  enunciado, aunque el motor de búsqueda no sea un servidor propio.
- Hubo un backend FastAPI propio y se retiró a propósito: dependía de una
  laptop encendida, y apagarla dejaba la app sin asistente en plena
  demostración — inaceptable para un uso real en el laboratorio.
- Riesgo a asumir: si la arquitectura común entre grupos es parte de la
  rúbrica de forma estricta, esto puede leerse como desviación. Vale la pena
  confirmarlo con el docente cuando se revise el estado general del
  proyecto (ver punto 5), pero no bloquea seguir desarrollando.

## 4. Seguridad de la clave de API

- [x] Pantalla **Ajustes** (2026-08-30): cada persona pega su propia clave de
  Gemini desde la app. Se cifra en el dispositivo con AES-256-GCM y una
  llave del Android Keystore (`CifradorClave`, `AlmacenClaves`) — nunca se
  guarda en texto plano. `ClaveUsuario` es hoy la **única** implementación
  de `ProveedorClave`: si no hay clave guardada devuelve `null` y el chat
  se bloquea con un aviso, sin ningún respaldo compilado (ver el punto de
  más abajo). Entradas: botón de engranaje en la cámara, y el aviso en el
  chat cuando no hay clave disponible.
- [x] ~~Copia cifrada en Supabase~~ — se implementó completa y funcionando
  (login anónimo, tabla `claves_api` con RLS, único archivo Kotlin del
  proyecto), pero se **quitó el 2026-08-30**: el equipo no le vio sentido
  a un respaldo que solo se recupera en el mismo teléfono. Detalle en
  `DecisionesMovil.md`. La app volvió a ser 100 % Java.
- [x] ~~`local.properties`/`GEMINI_API_KEY` como respaldo de desarrollo~~ —
  quitado por completo el 2026-08-30 (`ClaveCompilada`, el `buildConfigField`
  y la lectura de `local.properties` en `build.gradle`). Ahora la única
  fuente de la clave es la que cada persona guarda en Ajustes; sin eso, el
  APK no lleva ninguna clave dentro. Verificado el 2026-08-31: no queda
  ninguna mención a `GEMINI_API_KEY` ni a `BuildConfig` en `app/src` ni en
  los `build.gradle`. Detalle en `DecisionesMovil.md`.
- [ ] **Borrar la línea `GEMINI_API_KEY=...` que sigue en el `local.properties`
  local.** Ya no la lee nadie, pero es una clave real en texto plano en el
  disco de trabajo. El archivo está en `.gitignore`, así que nunca se subió;
  aun así conviene quitarla y, por prudencia, regenerar esa clave en
  aistudio.google.com.

## 5. Entregables de cierre — a propósito, después de la app

Decisión del equipo (2026-08-30): estos puntos se dejan para el final,
después de terminar la aplicación y revisar el estado general con el
ingeniero. No se generan antes por evitar rehacer trabajo si algo cambia.

- [ ] Video de demostración.
- [ ] Evidencias de funcionamiento en un teléfono real (capturas/fotos).
- [ ] Autorización formal para fotografiar el laboratorio — ya se hizo la
  visita y se fotografío de manera informal; falta el documento/registro
  formal si el docente lo exige por escrito.
- [ ] Confirmar que el Drive con el dataset (`MyDrive/Laboratorio_Rumiologia/`)
  es accesible para quien evalúe. El `best.pt` **ya se guarda solo** ahí: la
  celda de entrenamiento usa `project=f'{BASE}/runs'`, así que los pesos
  quedan en `runs/rumio_v1/weights/best.pt`. También hay copia local en
  `ml/models/best.pt` (no versionada: `*.pt` está excluido en `.gitignore`).
- [x] ~~Generar y guardar una copia del APK instalable~~ — `Rumiologia-1.7.apk`
  (versionCode 11) en la raíz del proyecto, generado el 2026-09-01 con el
  modelo reentrenado dentro. Se borró el `Rumiologia-1.6.apk` anterior, que
  no tenía la bienvenida, la tira de equipos, el modo de voz persistente ni
  el modo oscuro. No se versiona en git a propósito.
  - Es un build **debug**: instalable, pero firmado con la clave de depuración.
    El proyecto no tiene `signingConfig`, así que `assembleRelease` saldría
    sin firmar y no se podría instalar. Si el docente exige un release
    firmado, hay que crear un keystore antes.

## Ya resuelto, no repetir aquí

Ver la sección "Estado" de `README.md` y "Pendiente" al final de
`DecisionesMovil.md` para lo que ya está hecho y lo que sigue abierto a
nivel técnico fino (streaming de respuestas, presupuesto de razonamiento,
reexportar a 320×320, etc.) — son mejoras de calidad, no requisitos de la
asignación.
