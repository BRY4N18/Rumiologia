"""
Gestion de los almacenes de File Search de Gemini.

Herramienta de administracion que se ejecuta en el PC. La app Android NO la usa:
solo consulta el almacen ya preparado. Aqui se crea, se suben las fichas, se
comprueba el contenido y se prueban consultas sin tocar la app.

Uso:
    python gestionar.py listar
    python gestionar.py documentos
    python gestionar.py subir
    python gestionar.py subir --solo ankom_estufa
    python gestionar.py probar "como enciendo la estufa" --equipo ankom_estufa
    python gestionar.py borrar-documento ankom_estufa
    python gestionar.py borrar-almacen --confirmar

La clave y el identificador del almacen se leen de .env (que no va a git).

------------------------------------------------------------------------------
Diseno: la clase GestorAlmacenes concentra todo el trato con la API y no sabe
nada de la linea de comandos; las funciones cmd_* solo traducen argumentos y
presentan resultados. Asi la logica se puede reutilizar desde otro script o
desde una interfaz distinta sin arrastrar el CLI.
------------------------------------------------------------------------------
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

from dotenv import load_dotenv

RAIZ = Path(__file__).resolve().parents[1]
FICHAS = RAIZ / "documentacion" / "fichas"
ENV = Path(__file__).parent / ".env"

# Clave de metadato con la que se etiqueta cada ficha. Debe coincidir con la que
# usa la app al construir el filtro.
CLAVE_METADATO = "equipo"

MODELO_CONSULTA = "gemini-3.6-flash"

# La MISMA instruccion que usa la app. Sin ella el modelo rellena los huecos con
# conocimiento general de internet en vez de reconocer que no tiene el dato, asi
# que probar sin instruccion no representa el comportamiento real.
# Si se cambia aqui, hay que cambiarla tambien en ClienteGemini.java.
INSTRUCCION = """Eres el asistente del laboratorio de Rumiologia. Ayudas a estudiantes
a entender y operar los equipos del laboratorio.

Respondes UNICAMENTE con la informacion de las fichas tecnicas que la herramienta de
busqueda te proporciona.

Reglas estrictas:
- Si las fichas no contienen la respuesta, di: "Esa informacion no esta en las fichas
  tecnicas del laboratorio." No la deduzcas, no la estimes, no recurras a tu
  conocimiento general sobre equipos parecidos.
- Nunca inventes voltajes, temperaturas, capacidades, tiempos ni pasos de un
  procedimiento. Un dato erroneo sobre un equipo de laboratorio puede causar un
  accidente o arruinar un analisis.
- Cuando la ficha incluya una advertencia de seguridad relacionada con lo que se
  pregunta, mencionala aunque no te la hayan pedido.
- Di a que equipo corresponde tu respuesta: el usuario puede no tenerlo delante.
- Hay DOS estufas distintas (Estufa de Secado ANKOM y Estufa Universal MEMMERT). Si
  la pregunta dice solo "la estufa", pregunta a cual se refiere antes de responder.
- Responde en espanol, breve y directo, como a un estudiante.
- Las respuestas se leen en voz alta: evita tablas y listas muy largas."""


class GestorAlmacenes:
    """Envuelve las operaciones sobre File Search Stores."""

    def __init__(self, clave_api: str):
        from google import genai
        self._cliente = genai.Client(api_key=clave_api)

    # ------------------------------------------------------------- almacenes

    def listar_almacenes(self) -> list:
        return list(self._cliente.file_search_stores.list())

    def crear_almacen(self, etiqueta: str):
        return self._cliente.file_search_stores.create(
            config={"display_name": etiqueta}
        )

    def borrar_almacen(self, nombre: str) -> None:
        # force=True borra tambien los documentos que contenga.
        self._cliente.file_search_stores.delete(name=nombre, config={"force": True})

    # ------------------------------------------------------------ documentos

    def listar_documentos(self, almacen: str) -> list:
        return list(self._cliente.file_search_stores.documents.list(parent=almacen))

    def borrar_documento(self, nombre_completo: str) -> None:
        # force=True es obligatorio: la API rechaza borrar un documento que ya
        # tiene fragmentos indexados ("Cannot delete non-empty Document").
        self._cliente.file_search_stores.documents.delete(
            name=nombre_completo, config={"force": True})

    def buscar_documento(self, almacen: str, etiqueta: str):
        """Devuelve el documento cuyo display_name coincide, o None."""
        for d in self.listar_documentos(almacen):
            if d.display_name == etiqueta:
                return d
        return None

    def subir_ficha(self, almacen: str, archivo: Path, equipo: str) -> None:
        """
        Sube una ficha etiquetada con su equipo.

        Si ya existe un documento con esa etiqueta lo borra antes: subir sin
        borrar dejaria dos copias del mismo contenido y la busqueda devolveria
        fragmentos duplicados.
        """
        from google.genai import types

        existente = self.buscar_documento(almacen, archivo.stem)
        if existente is not None:
            self.borrar_documento(existente.name)

        operacion = self._cliente.file_search_stores.upload_to_file_search_store(
            file_search_store_name=almacen,
            file=str(archivo),
            config={
                "display_name": archivo.stem,
                "custom_metadata": [
                    types.CustomMetadata(key=CLAVE_METADATO, string_value=equipo)
                ],
            },
        )

        # La indexacion es asincrona: consultar un documento aun no indexado no
        # devolveria nada, asi que hay que esperar a que termine.
        while not operacion.done:
            time.sleep(2)
            operacion = self._cliente.operations.get(operacion)

    # -------------------------------------------------------------- consulta

    def consultar(self, almacen: str, pregunta: str, equipo: str | None = None,
                  instruccion: str | None = None):
        """Lanza una consulta con File Search, opcionalmente filtrada por equipo."""
        from google.genai import types

        file_search = types.FileSearch(file_search_store_names=[almacen])
        if equipo:
            file_search.metadata_filter = f'{CLAVE_METADATO}="{equipo}"'

        return self._cliente.models.generate_content(
            model=MODELO_CONSULTA,
            contents=pregunta,
            config=types.GenerateContentConfig(
                system_instruction=instruccion,
                temperature=0.2,
                tools=[types.Tool(file_search=file_search)],
            ),
        )


# ---------------------------------------------------------------- utilidades


def fichas_disponibles() -> list[Path]:
    return sorted(f for f in FICHAS.glob("*.md") if f.stem != "README")


def fuentes_de(respuesta) -> list[str]:
    fuentes: list[str] = []
    try:
        for candidato in respuesta.candidates or []:
            meta = getattr(candidato, "grounding_metadata", None)
            for trozo in getattr(meta, "grounding_chunks", None) or []:
                contexto = getattr(trozo, "retrieved_context", None)
                titulo = getattr(contexto, "title", None) if contexto else None
                if titulo and titulo not in fuentes:
                    fuentes.append(titulo)
    except Exception:
        pass
    return fuentes


def gestor() -> GestorAlmacenes:
    clave = os.environ.get("GEMINI_API_KEY")
    if not clave:
        print("ERROR: falta GEMINI_API_KEY en gestion_almacenes/.env")
        sys.exit(1)
    return GestorAlmacenes(clave)


def almacen_configurado() -> str:
    nombre = os.environ.get("FILE_SEARCH_STORE")
    if not nombre:
        print("ERROR: falta FILE_SEARCH_STORE en gestion_almacenes/.env")
        print("       Crea uno con:  python gestionar.py crear --nombre fichas-rumiologia")
        sys.exit(1)
    return nombre


# ------------------------------------------------------------------ comandos


def cmd_listar(args) -> None:
    almacenes = gestor().listar_almacenes()
    if not almacenes:
        print("No hay almacenes en esta cuenta.")
        return
    actual = os.environ.get("FILE_SEARCH_STORE")
    for a in almacenes:
        marca = "  <-- el configurado en .env" if a.name == actual else ""
        print(f"{a.display_name}{marca}")
        print(f"  {a.name}")
        print(f"  {a.active_documents_count} documentos, {a.size_bytes} bytes")
        print(f"  creado {a.create_time}")
        print()


def cmd_crear(args) -> None:
    almacen = gestor().crear_almacen(args.nombre)
    print(f"Almacen creado: {almacen.name}")
    print(f"\nAnotalo en gestion_almacenes/.env:")
    print(f"  FILE_SEARCH_STORE={almacen.name}")


def cmd_documentos(args) -> None:
    almacen = args.almacen or almacen_configurado()
    documentos = gestor().listar_documentos(almacen)
    if not documentos:
        print("El almacen esta vacio.")
        return
    print(f"{len(documentos)} documentos en {almacen}\n")
    for d in documentos:
        metadatos = getattr(d, "custom_metadata", None)
        etiquetas = ", ".join(
            f"{m.key}={m.string_value}" for m in (metadatos or [])
        ) or "SIN METADATOS"
        print(f"  {d.display_name:<30} {d.size_bytes:>6} B   {etiquetas}")


def cmd_subir(args) -> None:
    almacen = args.almacen or almacen_configurado()
    g = gestor()

    archivos = fichas_disponibles()
    if args.solo:
        archivos = [f for f in archivos if f.stem == args.solo]
        if not archivos:
            print(f"ERROR: no existe la ficha '{args.solo}'")
            print("Disponibles: " + ", ".join(f.stem for f in fichas_disponibles()))
            sys.exit(1)

    print(f"Subiendo {len(archivos)} ficha(s) a {almacen}\n")
    for archivo in archivos:
        equipo = archivo.stem
        print(f"  {archivo.name:<32} etiqueta {CLAVE_METADATO}={equipo} ...", end=" ", flush=True)
        g.subir_ficha(almacen, archivo, equipo)
        print("OK")

    print("\nListo. Comprueba con:  python gestionar.py documentos")


def cmd_borrar_documento(args) -> None:
    almacen = args.almacen or almacen_configurado()
    g = gestor()
    documento = g.buscar_documento(almacen, args.etiqueta)
    if documento is None:
        print(f"No existe un documento con la etiqueta '{args.etiqueta}'")
        sys.exit(1)
    g.borrar_documento(documento.name)
    print(f"Borrado: {args.etiqueta}")


def cmd_borrar_almacen(args) -> None:
    almacen = args.almacen or almacen_configurado()
    if not args.confirmar:
        print(f"Esto borrara el almacen {almacen} y TODOS sus documentos.")
        print("Si estas seguro, repite el comando con --confirmar")
        sys.exit(1)
    gestor().borrar_almacen(almacen)
    print(f"Almacen borrado: {almacen}")
    print("Recuerda actualizar FILE_SEARCH_STORE en .env")


def cmd_probar(args) -> None:
    almacen = args.almacen or almacen_configurado()
    respuesta = gestor().consultar(almacen, args.pregunta, args.equipo, INSTRUCCION)

    print(f"PREGUNTA : {args.pregunta}")
    print(f"FILTRO   : {args.equipo or '(ninguno: busca en todas las fichas)'}")
    print("-" * 70)
    print(respuesta.text)
    print("-" * 70)
    print(f"FUENTES  : {fuentes_de(respuesta) or '(ninguna)'}")

    uso = getattr(respuesta, 'usage_metadata', None)
    if uso:
        # prompt_token_count incluye los fragmentos que la busqueda inyecto:
        # es la forma de medir cuanto cuesta realmente cada consulta.
        print(f"TOKENS   : entrada {uso.prompt_token_count}"
              f" | salida {uso.candidates_token_count}"
              f" | razonamiento {uso.thoughts_token_count or 0}"
              f" | total {uso.total_token_count}")


def main() -> None:
    load_dotenv(ENV)

    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="comando", required=True)

    p = sub.add_parser("listar", help="lista los almacenes de la cuenta")
    p.set_defaults(func=cmd_listar)

    p = sub.add_parser("crear", help="crea un almacen nuevo")
    p.add_argument("--nombre", required=True)
    p.set_defaults(func=cmd_crear)

    p = sub.add_parser("documentos", help="lista los documentos y sus metadatos")
    p.add_argument("--almacen")
    p.set_defaults(func=cmd_documentos)

    p = sub.add_parser("subir", help="sube las fichas etiquetadas con su equipo")
    p.add_argument("--almacen")
    p.add_argument("--solo", help="subir una sola ficha, por su slug")
    p.set_defaults(func=cmd_subir)

    p = sub.add_parser("borrar-documento", help="borra un documento del almacen")
    p.add_argument("etiqueta")
    p.add_argument("--almacen")
    p.set_defaults(func=cmd_borrar_documento)

    p = sub.add_parser("borrar-almacen", help="borra el almacen entero")
    p.add_argument("--almacen")
    p.add_argument("--confirmar", action="store_true")
    p.set_defaults(func=cmd_borrar_almacen)

    p = sub.add_parser("probar", help="lanza una consulta de prueba")
    p.add_argument("pregunta")
    p.add_argument("--equipo", help="filtra por este equipo")
    p.add_argument("--almacen")
    p.set_defaults(func=cmd_probar)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
