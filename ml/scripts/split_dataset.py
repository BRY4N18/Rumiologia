"""
Divide un dataset exportado desde Label Studio (formato YOLO) en train/val/test.

Solo usa la libreria estandar: no requiere instalar nada.

Uso tipico:
    python ml/scripts/split_dataset.py --src export_labelstudio --dst dataset

Estructura esperada en --src (lo que entrega el export "YOLO" de Label Studio):
    export_labelstudio/
        images/  foto_001.jpg ...
        labels/  foto_001.txt ...
        classes.txt

Estructura generada en --dst:
    dataset/
        images/train/  images/val/  images/test/
        labels/train/  labels/val/  labels/test/

Cada imagen viaja SIEMPRE junto a su .txt: es el error mas comun al dividir a mano.
"""

import argparse
import random
import shutil
import sys
from collections import Counter
from pathlib import Path

IMG_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
SPLITS = ("train", "val", "test")


def find_images(src: Path):
    """Busca imagenes en src/images/ y, si no existe, en src/ directamente."""
    img_dir = src / "images" if (src / "images").is_dir() else src
    return sorted(p for p in img_dir.rglob("*") if p.suffix.lower() in IMG_EXT)


def label_for(img: Path, src: Path) -> Path:
    """Devuelve la ruta del .txt correspondiente a una imagen."""
    if (src / "labels").is_dir():
        return src / "labels" / (img.stem + ".txt")
    return img.with_suffix(".txt")


def count_classes(label: Path) -> Counter:
    c = Counter()
    if not label.exists():
        return c
    for line in label.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            c[line.split()[0]] += 1
    return c


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", required=True, help="Carpeta exportada de Label Studio")
    ap.add_argument("--dst", required=True, help="Carpeta destino del dataset dividido")
    ap.add_argument("--train", type=float, default=0.70)
    ap.add_argument("--val", type=float, default=0.20)
    ap.add_argument("--test", type=float, default=0.10)
    ap.add_argument("--seed", type=int, default=0, help="Semilla: mismo split reproducible")
    ap.add_argument("--move", action="store_true", help="Mover en vez de copiar")
    ap.add_argument("--keep-negatives", action="store_true",
                    help="Conservar imagenes sin .txt como ejemplos negativos (recomendado ~10%%)")
    ap.add_argument("--dry-run", action="store_true", help="Solo mostrar lo que haria")
    args = ap.parse_args()

    total_ratio = args.train + args.val + args.test
    if abs(total_ratio - 1.0) > 1e-6:
        print(f"ERROR: los porcentajes suman {total_ratio}, deben sumar 1.0")
        return 1

    src, dst = Path(args.src), Path(args.dst)
    if not src.is_dir():
        print(f"ERROR: no existe la carpeta de origen: {src}")
        return 1

    images = find_images(src)
    if not images:
        print(f"ERROR: no se encontraron imagenes en {src}")
        return 1

    # Separa las que tienen etiqueta de las que no.
    con_etiqueta, sin_etiqueta = [], []
    for img in images:
        (con_etiqueta if label_for(img, src).exists() else sin_etiqueta).append(img)

    print(f"Imagenes encontradas .......... {len(images)}")
    print(f"  con etiqueta ................ {len(con_etiqueta)}")
    print(f"  sin etiqueta ................ {len(sin_etiqueta)}")

    if sin_etiqueta and not args.keep_negatives:
        print("\n  AVISO: las imagenes sin .txt se OMITIRAN.")
        print("  Si son negativos intencionales (escenas sin equipos), usa --keep-negatives.")
        for p in sin_etiqueta[:5]:
            print(f"    - {p.name}")
        if len(sin_etiqueta) > 5:
            print(f"    ... y {len(sin_etiqueta) - 5} mas")

    seleccionadas = con_etiqueta + (sin_etiqueta if args.keep_negatives else [])
    if not seleccionadas:
        print("ERROR: no queda ninguna imagen que procesar.")
        return 1

    # Detecta nombres de archivo repetidos: romperian la correspondencia imagen/etiqueta.
    stems = Counter(p.stem for p in seleccionadas)
    repetidos = [s for s, n in stems.items() if n > 1]
    if repetidos:
        print(f"\nERROR: hay {len(repetidos)} nombres de archivo repetidos, renombralos primero:")
        for s in repetidos[:10]:
            print(f"    - {s}")
        return 1

    random.Random(args.seed).shuffle(seleccionadas)

    n = len(seleccionadas)
    n_train = int(n * args.train)
    n_val = int(n * args.val)
    reparto = {
        "train": seleccionadas[:n_train],
        "val": seleccionadas[n_train:n_train + n_val],
        "test": seleccionadas[n_train + n_val:],
    }

    print(f"\nReparto (semilla={args.seed}):")
    for s in SPLITS:
        print(f"  {s:<6} {len(reparto[s]):>5} imagenes")

    if args.dry_run:
        print("\n--dry-run: no se copio nada.")
        return 0

    accion = shutil.move if args.move else shutil.copy2
    conteo = {s: Counter() for s in SPLITS}
    vacios = Counter()

    for split, items in reparto.items():
        (dst / "images" / split).mkdir(parents=True, exist_ok=True)
        (dst / "labels" / split).mkdir(parents=True, exist_ok=True)
        for img in items:
            lbl = label_for(img, src)
            accion(str(img), str(dst / "images" / split / img.name))
            destino_lbl = dst / "labels" / split / (img.stem + ".txt")
            if lbl.exists():
                conteo[split].update(count_classes(lbl))
                accion(str(lbl), str(destino_lbl))
            else:
                destino_lbl.write_text("", encoding="utf-8")  # negativo: .txt vacio
                vacios[split] += 1

    print(f"\nDataset generado en: {dst.resolve()}")
    print("\nCajas por clase y split:")
    todas = sorted({c for s in SPLITS for c in conteo[s]}, key=int)
    print(f"  {'clase':<8}" + "".join(f"{s:>9}" for s in SPLITS) + f"{'total':>9}")
    for c in todas:
        fila = [conteo[s][c] for s in SPLITS]
        print(f"  {c:<8}" + "".join(f"{v:>9}" for v in fila) + f"{sum(fila):>9}")
    if sum(vacios.values()):
        print(f"\n  negativos (sin cajas): " + ", ".join(f"{s}={vacios[s]}" for s in SPLITS))

    print("\nSiguiente paso:")
    print(f"  1. Copia ml/data.yaml dentro de {dst}/ y ajusta 'names'")
    print(f"  2. Comprime la carpeta '{dst}' como dataset.zip")
    print("  3. Sube dataset.zip a MyDrive/Laboratorio_Rumiologia/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
