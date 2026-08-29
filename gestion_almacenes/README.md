# Gestión de almacenes de File Search

Herramienta de administración que se ejecuta **en el PC**. La app Android no la usa:
solo consulta el almacén ya preparado.

Con ella creas almacenes, subes las fichas técnicas etiquetadas por equipo,
compruebas qué hay indexado y pruebas consultas sin tocar la app.

## Puesta en marcha

```bash
pip install -r requirements.txt
```

Copia `.env.example` a `.env` y rellena:

```
GEMINI_API_KEY=tu_clave_de_aistudio.google.com/apikey
FILE_SEARCH_STORE=fileSearchStores/xxxxxxxx
```

## Comandos

```bash
python gestionar.py listar                    # almacenes de la cuenta
python gestionar.py documentos                # qué hay dentro y con qué metadatos
python gestionar.py crear --nombre fichas-v2  # almacén nuevo
python gestionar.py subir                     # sube las 7 fichas etiquetadas
python gestionar.py subir --solo ankom_estufa # actualiza una sola
python gestionar.py borrar-documento memmert
python gestionar.py borrar-almacen --confirmar
python gestionar.py probar "cómo enciendo la estufa" --equipo ankom_estufa
```

**`probar` es el comando más útil**: lanza una consulta real con o sin filtro y
muestra la respuesta y las fuentes. Cuando edites una ficha, `subir --solo` y luego
`probar` te dicen en un minuto si el cambio surtió efecto, sin compilar la app.

## Cómo funciona el etiquetado

Cada ficha se sube con un metadato:

```
ankom_estufa.md  →  customMetadata: { equipo: "ankom_estufa" }
```

El valor sale del nombre del archivo, que coincide con el slug de `ml/clases.json`.
Al consultar, la app filtra con `equipo="<slug>"` para acotar la búsqueda al equipo
que detectó la cámara.

Al subir una ficha que ya existe, **se borra la anterior primero**. Sin eso quedarían
dos copias del mismo contenido y la búsqueda devolvería fragmentos duplicados.

## Por qué el filtro importa

Comprobado el 28/08/2026 con la pregunta *"¿a qué temperatura trabaja la estufa?"*:

| | Fuentes | Respuesta |
|---|---|---|
| Sin filtro | `memmert`, `ankom_estufa` | Mezcla las dos estufas: 300 °C, 37 °C **y** 102 °C |
| `--equipo ankom_estufa` | `ankom_estufa` | 100–105 °C, estándar 102 °C ± 2 °C |
| `--equipo memmert` | `memmert` | Hasta 300 °C, incubación 37 °C |

El laboratorio tiene **dos estufas** y sin filtro el asistente las confunde.

## La instrucción del sistema no es opcional

`probar` envía la misma instrucción que usa la app, y es deliberado. Sin ella el
modelo rellena los huecos con conocimiento general:

Preguntando por la incubadora DAISY con el filtro puesto en la balanza:

- **Sin instrucción**: respondió con tiempos de incubación y un método de dos etapas
  con pepsina y HCl. Suena razonable y **no está en ninguna ficha**.
- **Con instrucción**: *"Esa información no está en las fichas técnicas del
  laboratorio."*

Si cambias la instrucción aquí, cámbiala también en `ClienteGemini.java`. Están
duplicadas a propósito —una para probar, otra para la app— pero deben decir lo mismo.

## Detalles de la API que costaron descubrir

- **Borrar un documento indexado exige `force: true`**, o devuelve
  `400 Cannot delete non-empty Document`.
- **La indexación es asíncrona**: hay que esperar a que la operación termine antes de
  consultar, o el documento aún no aparece en las búsquedas.
- **`google-genai` debe ser 2.20.0 o superior**; las versiones anteriores no tienen
  `file_search_stores`.
- La API falla de forma intermitente con errores transitorios. Si un comando revienta
  sin motivo aparente, reinténtalo antes de investigar.

## Estado actual

Almacén `fileSearchStores/fichasrumiologia-keer2v9xyjnm`, 7 documentos, todos con su
metadato `equipo`.
