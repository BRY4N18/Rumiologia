"""
Crea el File Search Store de Gemini y sube las fichas tecnicas.

Se ejecuta A MANO, una sola vez, y otra vez cada vez que cambien las fichas:

    python crear_almacen.py

Google se encarga de trocear los documentos, calcular los embeddings e indexarlos.
Nosotros no escribimos nada de eso: por eso el RAG es "gestionado".

Al terminar imprime el identificador del almacen. Ese identificador va al archivo
.env como FILE_SEARCH_STORE, y es lo que usa el servidor para consultar.

------------------------------------------------------------------------------
NOTA SOBRE EL SDK: los nombres exactos de estos metodos conviene contrastarlos
con la documentacion actual (ai.google.dev/gemini-api/docs, seccion File Search).
La estructura de tres pasos -crear almacen, subir, consultar- es estable, pero el
SDK cambia de nombres con frecuencia. Si algo falla, el error dira que metodo no
existe y se corrige en el sitio.
------------------------------------------------------------------------------
"""

import os
import sys
import time
from pathlib import Path

from dotenv import load_dotenv

RAIZ = Path(__file__).resolve().parents[1]
FICHAS = RAIZ / "documentacion" / "fichas"
NOMBRE_ALMACEN = "fichas-rumiologia"


def main() -> int:
    load_dotenv(Path(__file__).parent / ".env")

    clave = os.environ.get("GEMINI_API_KEY")
    if not clave:
        print("ERROR: falta la variable de entorno GEMINI_API_KEY")
        print("  Crea backend/.env con:  GEMINI_API_KEY=tu_clave")
        print("  Consiguela en https://aistudio.google.com/apikey")
        return 1

    archivos = sorted(f for f in FICHAS.glob("*.md") if f.stem != "README")
    if not archivos:
        print(f"ERROR: no hay fichas en {FICHAS}")
        return 1

    print(f"Fichas encontradas: {len(archivos)}")
    for f in archivos:
        print(f"  {f.name:<32} {f.stat().st_size / 1024:.1f} KB")

    from google import genai

    cliente = genai.Client(api_key=clave)

    print(f"\nCreando el almacen '{NOMBRE_ALMACEN}'...")
    almacen = cliente.file_search_stores.create(
        config={"display_name": NOMBRE_ALMACEN}
    )
    print(f"  {almacen.name}")

    print("\nSubiendo fichas...")
    for archivo in archivos:
        operacion = cliente.file_search_stores.upload_to_file_search_store(
            file_search_store_name=almacen.name,
            file=str(archivo),
            config={"display_name": archivo.stem},
        )
        # La indexacion es asincrona: hay que esperar a que Google termine de
        # trocear y calcular embeddings antes de poder consultar el documento.
        while not operacion.done:
            time.sleep(2)
            operacion = cliente.operations.get(operacion)
        print(f"  OK  {archivo.name}")

    print("\n" + "=" * 68)
    print("Almacen listo. Guarda este identificador en backend/.env:")
    print(f"\n  FILE_SEARCH_STORE={almacen.name}\n")
    print("=" * 68)
    print("\nSi vuelves a ejecutar este script se creara un almacen NUEVO.")
    print("Para actualizar las fichas, borra el anterior desde la API o")
    print("reutiliza el mismo almacen subiendo solo los archivos cambiados.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
