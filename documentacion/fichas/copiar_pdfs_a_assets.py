"""
Copia las fichas en PDF al APK, renombradas al slug de cada equipo.

Los PDF originales se llaman "Ficha Tecnica ANKOM Estufa.pdf" — con espacios y sin
relacion directa con los identificadores del modelo. La app necesita encontrarlos por
slug (`ankom_estufa.pdf`), asi que aqui se hace el mapeo explicito.

Se ejecuta cuando cambien los PDF:

    python documentacion/fichas/copiar_pdfs_a_assets.py

Solo usa la libreria estandar.
"""

import shutil
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
ORIGEN = RAIZ / "documentacion" / "fichas" / "MOVIL APP EXAM" / "PDF"
DESTINO = RAIZ / "app" / "src" / "main" / "assets" / "fichas"

# Nombre del PDF -> slug del equipo. El mapeo es manual porque los nombres de los
# archivos no siguen ninguna regla deducible: "Ohaus AB33M1" es el AQUASEARCHER.
MAPEO = {
    "Ficha Tecnica ANKOM 200.pdf": "ankom_200_fiber_analyzer",
    "Ficha Tecnica ANKOM DAISY.pdf": "ankom_daisy_incubator",
    "Ficha Tecnica ANKOM Estufa.pdf": "ankom_estufa",
    "Ficha Tecnica Ohaus AB33M1.pdf": "aquasearcher_ab33m1",
    "Ficha Tecnica Contador Colonias.pdf": "contador_de_colonias",
    "Ficha Tecnica MEMMERT.pdf": "memmert",
    "Ficha Tecnica Ohaus PR224.pdf": "ohaus_pr224",
}


def main() -> int:
    if not ORIGEN.is_dir():
        print(f"ERROR: no existe {ORIGEN}")
        return 1

    DESTINO.mkdir(parents=True, exist_ok=True)
    for viejo in DESTINO.glob("*.pdf"):
        viejo.unlink()

    total = 0
    faltan = []

    for nombre, slug in MAPEO.items():
        origen = ORIGEN / nombre
        if not origen.is_file():
            faltan.append(nombre)
            continue
        destino = DESTINO / f"{slug}.pdf"
        shutil.copy2(origen, destino)
        total += destino.stat().st_size
        print(f"  {nombre:<38} -> {slug}.pdf  ({destino.stat().st_size // 1024} KB)")

    if faltan:
        print(f"\nFALTAN {len(faltan)} archivos en {ORIGEN}:")
        for f in faltan:
            print(f"  - {f}")
        return 1

    # Sobrantes: PDF que estan en la carpeta pero no en el mapeo. Suelen ser un
    # equipo nuevo del que nadie se acordo de anadir la entrada.
    conocidos = set(MAPEO)
    sobrantes = [p.name for p in ORIGEN.glob("*.pdf") if p.name not in conocidos]
    if sobrantes:
        print(f"\nAVISO: {len(sobrantes)} PDF sin mapear (no se copiaron):")
        for s in sobrantes:
            print(f"  - {s}")

    print(f"\n{len(MAPEO)} fichas copiadas a assets/fichas/ ({total / 1024 / 1024:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
