# Pendientes para la entrega final

Comparación del proyecto contra los lineamientos de la asignación (Tema 4 —
Laboratorio de Rumiología), hecha el 2026-08-30. Consolida lo que estaba
repartido entre el `README.md` raíz y `DecisionesMovil.md`, más lo que no
tenía dónde vivir todavía.

No repite lo que ya está resuelto (ver "Estado" en `README.md`). Es solo lo
que falta, ordenado por qué tan bloqueante es.

## 1. Dataset — bloqueante, hay que volver al laboratorio

Conteo real hecho el 2026-08-30 sobre `ml/imagenes_crudas/` (fotos originales
por carpeta) y `dataset/labels/` (cajas ya etiquetadas y divididas). El
lineamiento pide **80–150 fotos por tipo de equipo**.

| Clase | Fotos crudas hoy | Faltan para el mínimo (80) | Cajas etiquetadas (train/val/test) |
|---|---:|---:|---|
| `ankom_estufa` | **11** | **69** | 7 / 1 / 2 = 10 |
| `ankom_daisy_incubator` | 47 | 33 | 31 / 5 / 6 = 42 |
| `memmert` | 41 | 39 | 26 / 12 / 4 = 42 |
| `aquasearcher_ab33m1` | 45 | 35 | 33 / 15 / 4 = 52 |
| `ohaus_pr224` | 44 | 36 | 29 / 10 / 7 = 46 |
| `contador_de_colonias` | 51 | 29 | 46 / 8 / 2 = 56 |
| `ankom_200_fiber_analyzer` | 101 | 0 (ya en rango) | 51 / 15 / 8 = 74 |

**Total: faltan 241 fotos nuevas** para que las 6 clases por debajo del
mínimo lleguen a 80 (¡`ankom_200_fiber_analyzer` ya está lista, no hace
falta tocarla!). El dataset actual tiene 306 imágenes con 322 cajas en total
(~1.05 cajas/foto), confirmando el aviso del README: casi no hay fotos con
varios equipos etiquetados a la vez.

- [x] ~~Correr `prepare_images.py --dry-run` para saber el déficit por
  clase~~ — hecho arriba directamente contando las carpetas (el venv local
  no tenía `pillow`/`pillow-heif` instalados; instalar antes de la próxima
  corrida real: `.venv\Scripts\python.exe -m pip install pillow pillow-heif`).
- [ ] Nueva sesión de fotos en el laboratorio, en este orden de urgencia:
  `ankom_estufa` (69) → `memmert` (39) → `ohaus_pr224` (36) →
  `aquasearcher_ab33m1` (35) → `ankom_daisy_incubator` (33) →
  `contador_de_colonias` (29).
  - `ankom_estufa` es además la más urgente por otro motivo: se confunde
    con `memmert` (ambas son estufas) y hoy casi no tiene datos para
    aprender la diferencia.
- [ ] Al fotografiar, **etiquetar todos los equipos visibles en cada foto**,
  no solo el principal (regla ya documentada en `ml/README.md` §1, pero el
  dataset actual no la cumple: ~1 caja por foto en promedio). Un equipo
  visible sin caja le enseña al modelo que es "fondo".
- [ ] Variar ángulo, distancia, iluminación y fondo por sesión, no repetir
  ráfagas casi idénticas — es lo que hoy infla el mAP50 (0.985 no es
  representativo, según el propio README).
- [ ] Volver a correr `prepare_images.py` → Label Studio → `split_dataset.py`
  → `check_dataset.py` → reentrenar con `train_yolo26.ipynb`.

## 2. Split 70/15/15, no 70/20/10

`ml/scripts/split_dataset.py` tiene por defecto `--val 0.20 --test 0.10`. El
lineamiento pide exactamente **70/15/15**.

- [ ] Volver a dividir con `--val 0.15 --test 0.15` cuando se rehaga el
  dataset ampliado (no hace falta hacerlo dos veces: se resuelve en el mismo
  re-split del punto 1).

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
  guarda en texto plano. `ClaveUsuario` (nuevo `ProveedorClave`) la
  prioriza y solo cae a la clave compilada de `local.properties` si no hay
  ninguna guardada (para seguir pudiendo desarrollar sin abrir Ajustes cada
  vez). Entradas: botón de engranaje en la cámara, y un aviso en el chat si
  no hay ninguna clave disponible.
- [x] ~~Copia cifrada en Supabase~~ — se implementó completa y funcionando
  (login anónimo, tabla `claves_api` con RLS, único archivo Kotlin del
  proyecto), pero se **quitó el 2026-08-30**: el equipo no le vio sentido
  a un respaldo que solo se recupera en el mismo teléfono. Detalle en
  `DecisionesMovil.md`. La app volvió a ser 100 % Java.
- [x] ~~`local.properties`/`GEMINI_API_KEY` como respaldo de desarrollo~~ —
  quitado por completo el 2026-08-30 (`ClaveCompilada`, el `buildConfigField`
  y la lectura de `local.properties` en `build.gradle`). Ahora la única
  fuente de la clave es la que cada persona guarda en Ajustes; sin eso, el
  APK no lleva ninguna clave dentro. Detalle en `DecisionesMovil.md`.

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
  es accesible para quien evalúe, y que `best.pt` (modelo original, no solo
  el `.tflite`) está guardado ahí — hoy `*.pt` está excluido del repo a
  propósito (ver `.gitignore`).
- [ ] Generar y guardar una copia del APK instalable para la entrega (no se
  versiona en git a propósito).

## Ya resuelto, no repetir aquí

Ver la sección "Estado" de `README.md` y "Pendiente" al final de
`DecisionesMovil.md` para lo que ya está hecho y lo que sigue abierto a
nivel técnico fino (streaming de respuestas, presupuesto de razonamiento,
reexportar a 320×320, etc.) — son mejoras de calidad, no requisitos de la
asignación.
