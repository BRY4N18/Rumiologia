"""
Servidor del asistente RAG del laboratorio.

La app Android habla con este servicio, y este servicio habla con Gemini. Nunca al
reves: si la app llamara directamente a Gemini, la API key viajaria dentro del APK
y extraerla es trivial (basta descomprimirlo). Aqui la clave vive en una variable
de entorno del servidor.

Arranque:
    uvicorn app:app --host 0.0.0.0 --port 8000 --reload

El host 0.0.0.0 es necesario para que el telefono o el emulador puedan alcanzarlo;
con el 127.0.0.1 por defecto solo respondería a la propia maquina.
"""

import os
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

# Lee backend/.env y lo carga como variables de entorno. Asi la clave vive en un
# archivo ignorado por git en vez de escribirse a mano en cada terminal.
load_dotenv(Path(__file__).parent / ".env")

MODELO = "gemini-3.6-flash"

INSTRUCCION = """Eres el asistente del laboratorio de Rumiología. Ayudas a estudiantes
a entender y operar los equipos del laboratorio.

Respondes ÚNICAMENTE con la información de las fichas técnicas que la herramienta de
búsqueda te proporciona.

Reglas estrictas:
- Si las fichas no contienen la respuesta, di: "Esa información no está en las fichas
  técnicas del laboratorio." No la deduzcas, no la estimes, no recurras a tu
  conocimiento general sobre equipos parecidos.
- Nunca inventes voltajes, temperaturas, capacidades, tiempos ni pasos de un
  procedimiento. Un dato erróneo sobre un equipo de laboratorio puede causar un
  accidente o arruinar un análisis.
- Cuando la ficha incluya una advertencia de seguridad relacionada con lo que se
  pregunta, menciónala aunque no te la hayan pedido.
- Di a qué equipo corresponde tu respuesta: el usuario puede no tenerlo delante.
- Hay DOS estufas distintas (Estufa de Secado ANKOM y Estufa Universal MEMMERT). Si
  la pregunta dice solo "la estufa", pregunta a cuál se refiere antes de responder.
- Responde en español, breve y directo, como a un estudiante.
- Las respuestas se leen en voz alta: evita tablas y listas muy largas."""

app = FastAPI(title="Asistente RAG — Laboratorio de Rumiología")

_cliente = None


def cliente():
    global _cliente
    if _cliente is None:
        from google import genai
        clave = os.environ.get("GEMINI_API_KEY")
        if not clave:
            raise HTTPException(500, "Falta la variable de entorno GEMINI_API_KEY")
        _cliente = genai.Client(api_key=clave)
    return _cliente


def almacen() -> str:
    nombre = os.environ.get("FILE_SEARCH_STORE")
    if not nombre:
        raise HTTPException(
            500,
            "Falta FILE_SEARCH_STORE. Ejecuta primero: python crear_almacen.py",
        )
    return nombre


class Turno(BaseModel):
    rol: str          # "usuario" o "asistente"
    texto: str


class Consulta(BaseModel):
    pregunta: str = Field(min_length=1, max_length=1000)
    # Slug del equipo si el usuario venia de tocar una deteccion en la camara.
    # Opcional a proposito: el chat tambien se usa sin el equipo delante.
    equipo: str | None = None
    historial: list[Turno] = []


class Respuesta(BaseModel):
    respuesta: str
    fuentes: list[str] = []


@app.get("/health")
def health():
    """Comprobacion rapida de que el servicio esta configurado."""
    return {
        "ok": True,
        "modelo": MODELO,
        "clave_configurada": bool(os.environ.get("GEMINI_API_KEY")),
        "almacen_configurado": bool(os.environ.get("FILE_SEARCH_STORE")),
    }


@app.post("/chat", response_model=Respuesta)
def chat(c: Consulta):
    from google.genai import types

    contenidos = []

    # Solo los ultimos turnos: una conversacion larga encarece la llamada sin
    # mejorar la respuesta, porque el contexto util lo aporta la busqueda.
    for turno in c.historial[-6:]:
        papel = "user" if turno.rol == "usuario" else "model"
        contenidos.append(types.Content(role=papel, parts=[types.Part(text=turno.texto)]))

    pregunta = c.pregunta
    if c.equipo:
        # Pista de contexto, no un filtro: el usuario puede estar frente a un equipo
        # y preguntar por otro. La busqueda sigue viendo todas las fichas.
        pregunta = (f"[El usuario está viendo el equipo '{c.equipo}' en la cámara. "
                    f"Si la pregunta no indica otro equipo, asume que se refiere a ese.]\n\n"
                    f"{pregunta}")

    contenidos.append(types.Content(role="user", parts=[types.Part(text=pregunta)]))

    salida = cliente().models.generate_content(
        model=MODELO,
        contents=contenidos,
        config=types.GenerateContentConfig(
            system_instruction=INSTRUCCION,
            temperature=0.2,     # baja: queremos fidelidad a la ficha, no creatividad
            tools=[types.Tool(
                file_search=types.FileSearch(file_search_store_names=[almacen()])
            )],
            # El razonamiento extendido añade latencia y coste sin aportar nada
            # cuando la respuesta se limita a lo que dice un documento.
            # VERIFICAR el nombre de este campo en la documentacion actual:
            # thinking_config=types.ThinkingConfig(thinking_budget=0),
        ),
    )

    return Respuesta(respuesta=salida.text or "", fuentes=extraer_fuentes(salida))


def extraer_fuentes(salida) -> list[str]:
    """
    Saca los documentos citados de los metadatos de grounding.

    Es lo que hace util al RAG frente a un modelo suelto: el usuario puede
    comprobar de que ficha salio cada dato. La estructura exacta de estos
    metadatos conviene verificarla imprimiendo la respuesta completa la primera vez.
    """
    fuentes: list[str] = []
    try:
        for candidato in salida.candidates or []:
            meta = getattr(candidato, "grounding_metadata", None)
            for trozo in getattr(meta, "grounding_chunks", None) or []:
                contexto = getattr(trozo, "retrieved_context", None)
                titulo = getattr(contexto, "title", None) if contexto else None
                if titulo and titulo not in fuentes:
                    fuentes.append(titulo)
    except Exception:
        pass          # las fuentes son informativas: nunca deben romper la respuesta
    return fuentes
