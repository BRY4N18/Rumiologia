# Asistente RAG del laboratorio

> **Backend temporal — prueba de concepto.** Sirve para comprobar que el enfoque RAG
> funciona con las fichas reales; no es la versión definitiva del servicio.
> Ver [`ESTADO.md`](ESTADO.md) para qué está demostrado, qué falta y qué es
> reutilizable.

Servicio que responde preguntas sobre los equipos usando **únicamente** las fichas
técnicas de [`documentacion/fichas/`](../documentacion/fichas), con la **File Search
Tool** de Gemini.

## Por qué existe este servicio

La app Android **no puede** llamar a Gemini directamente: la API key acabaría dentro
del APK y extraerla es trivial (basta descomprimirlo). Aquí la clave vive en una
variable de entorno del servidor y nunca sale de él.

```
App Android ──HTTP──► este servicio ──API key──► Gemini + File Search
                                                        │
                                                   fichas .md indexadas
```

## Cómo funciona el RAG

Es **RAG gestionado**: Google trocea los documentos, calcula los embeddings, los
indexa y hace la búsqueda. Nosotros no escribimos nada de eso — solo subimos las
fichas y activamos la herramienta en cada llamada.

1. `crear_almacen.py` sube las 7 fichas a un *File Search Store*. Se ejecuta una vez.
2. En cada pregunta, el modelo busca por su cuenta los fragmentos relevantes y
   responde citando de dónde salieron.

La alternativa era implementarlo a mano (trocear, embeddings, similitud coseno,
almacén propio). Se descartó porque para siete documentos no aporta nada frente a
lo que ya resuelve la herramienta.

## Puesta en marcha

```bash
pip install -r requirements.txt
```

Consigue la clave en [aistudio.google.com/apikey](https://aistudio.google.com/apikey)
y expórtala en la terminal:

```bash
$env:GEMINI_API_KEY = "tu_clave"
```

Crea el almacén y sube las fichas (imprime un identificador al terminar):

```bash
python crear_almacen.py
```

Guarda ese identificador:

```bash
$env:FILE_SEARCH_STORE = "fileSearchStores/xxxxxxxx"
```

Arranca el servidor:

```bash
uvicorn app:app --host 0.0.0.0 --port 8000 --reload
```

`0.0.0.0` es necesario: con el `127.0.0.1` por defecto solo respondería a la propia
máquina, y el emulador no podría alcanzarlo.

Comprueba que está bien configurado en `http://localhost:8000/health`, y explora la
API en `http://localhost:8000/docs`.

## Endpoints

| Método | Ruta | Para qué |
|---|---|---|
| GET | `/health` | Verifica que la clave y el almacén están configurados |
| POST | `/chat` | Pregunta completa: busca en las fichas y responde |

```json
POST /chat
{
  "pregunta": "¿cómo enciendo la estufa ANKOM?",
  "equipo": "ankom_estufa",
  "historial": [{"rol": "usuario", "texto": "hola"}]
}
```

`equipo` es opcional: llega cuando el usuario tocó una detección en la cámara, y se
omite cuando pregunta sin el equipo delante. **No filtra la búsqueda**, solo orienta
al modelo — el usuario puede estar frente a una balanza y preguntar por otra cosa.

## Decisiones del prompt

La instrucción del sistema (en `app.py`) es la parte que más protege, y merece que
la leas antes de cambiarla:

- **Prohíbe explícitamente inventar** voltajes, temperaturas, tiempos o pasos. Sin
  esa regla, el modelo rellena huecos con conocimiento general de internet sobre
  equipos parecidos. En un laboratorio, un dato inventado sobre temperaturas o
  reactivos no es un error cosmético.
- **Obliga a mencionar las advertencias de seguridad** relacionadas aunque no se
  pregunten. La ficha de la estufa ANKOM advierte sobre riesgo de explosión con
  acetona: eso debe salir siempre que se hable de secar bolsas de filtro.
- **Desambigua las dos estufas.** Hay una ANKOM de secado y una MEMMERT universal;
  ante un "la estufa" a secas, el asistente pregunta a cuál se refiere.
- **`temperature=0.2`** — queremos fidelidad a la fuente, no redacción creativa.

## Verificado en funcionamiento

Probado de extremo a extremo el 28/08/2026 con `google-genai 2.20.0` y
`gemini-3.6-flash`. Comportamientos comprobados:

| Prueba | Resultado |
|---|---|
| Pregunta operativa ("¿cómo enciendo la estufa ANKOM?") | Devuelve los 4 pasos reales y **añade sola** la advertencia de la acetona |
| Pregunta ambigua ("¿cómo enciendo la estufa?") | Pregunta a cuál de las dos se refiere |
| Fuera de las fichas ("¿cuánto cuesta?") | "Esa información no está en las fichas técnicas" |
| Dato específico (capacidad de la balanza) | 220 g y 0.1 mg, con la advertencia de sobrecarga |
| Historial ("¿y cuánto dura la incubación?") | Mantiene el contexto del turno anterior |

**Importante sobre la versión del SDK:** `google-genai` debe ser **2.20.0 o
superior**. Las versiones anteriores no tienen `file_search_stores` y fallan con
`AttributeError`.

También queda por ajustar el **presupuesto de razonamiento**: en las pruebas, el
modelo gastó 123 tokens de "thinking" para responder una sola palabra. Para
respuestas basadas en documentos eso es latencia y coste sin beneficio. La línea
está comentada en `app.py`, a la espera de confirmar el nombre del campo.
