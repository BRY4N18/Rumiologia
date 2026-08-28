"""
Reconstruye un export de Label Studio que vino sin imagenes.

El formato "YOLO" de Label Studio exporta solo los .txt (el que incluye fotos es
"YOLO with Images"). Ademas renombra cada archivo con un prefijo de 8 caracteres
hexadecimales que asigna al subir: 00a0b9dd-ankom_estufa_0005.txt

Este script quita ese prefijo y vuelve a emparejar cada etiqueta con su imagen
original de ml/imagenes_listas/, dejando un export completo y con nombres limpios.

Uso:
    .venv\\Scripts\\python.exe ml\\scripts\\reconstruir_export.py

Solo usa la libreria estandar.
"""

import argparse
import re
import shutil
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
PREFIJO_LS = re.compile(r"^[0-9a-f]{8}-")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--export", default=str(RAIZ / "ml" / "export_labelstudio"))
    ap.add_argument("--imagenes", default=str(RAIZ / "ml" / "imagenes_listas"))
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    export = Path(args.export)
    origen_img = Path(args.imagenes)
    labels_dir = export / "labels"
    destino_img = export / "images"

    if not labels_dir.is_dir():
        print(f"ERROR: no existe {labels_dir}")
        return 1
    if not origen_img.is_dir():
        print(f"ERROR: no existe {origen_img}")
        return 1

    disponibles = {p.stem: p for p in origen_img.glob("*.jpg")}
    etiquetas = sorted(labels_dir.glob("*.txt"))
    if not etiquetas:
        print(f"ERROR: no hay .txt en {labels_dir}")
        return 1

    copiadas, renombradas, huerfanas = 0, 0, []

    for etiqueta in etiquetas:
        limpio = PREFIJO_LS.sub("", etiqueta.stem)
        imagen = disponibles.get(limpio)
        if imagen is None:
            huerfanas.append(etiqueta.name)
            continue

        if args.dry_run:
            copiadas += 1
            continue

        destino_img.mkdir(parents=True, exist_ok=True)
        shutil.copy2(imagen, destino_img / f"{limpio}.jpg")
        copiadas += 1

        # Quita el prefijo tambien del .txt para que los nombres coincidan
        if etiqueta.stem != limpio:
            etiqueta.rename(labels_dir / f"{limpio}.txt")
            renombradas += 1

    print(f"Etiquetas procesadas ..... {len(etiquetas)}")
    print(f"Imagenes recuperadas ..... {copiadas}")
    print(f"Etiquetas renombradas .... {renombradas}")

    if huerfanas:
        print(f"\nSIN IMAGEN ({len(huerfanas)}):")
        for h in huerfanas[:10]:
            print(f"  - {h}")
        print("\n  Revisa que ml/imagenes_listas/ siga completo.")
        return 1

    if args.dry_run:
        print("\n--dry-run: no se escribio nada.")
    else:
        print(f"\nExport completo en {export}")
        print("Siguiente: split_dataset.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
