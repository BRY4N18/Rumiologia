"""
Verifica la integridad de un dataset YOLO antes de subirlo a Colab.

Detecta los errores que hacen fallar (o degradar en silencio) un entrenamiento:
  - imagenes sin etiqueta y etiquetas sin imagen
  - lineas mal formadas en los .txt
  - coordenadas fuera del rango [0, 1]
  - indices de clase que no existen en data.yaml
  - cajas de area cero
  - clases sin representacion en val/test

Solo usa la libreria estandar.

Uso:
    python ml/scripts/check_dataset.py --dataset dataset --data-yaml ml/data.yaml
"""

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

IMG_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
SPLITS = ("train", "val", "test")


def leer_names(yaml_path: Path):
    """Lee el bloque 'names:' de data.yaml sin depender de PyYAML."""
    names, dentro = {}, False
    for linea in yaml_path.read_text(encoding="utf-8").splitlines():
        sin_comentario = linea.split("#")[0].rstrip()
        if not sin_comentario.strip():
            continue
        if re.match(r"^names\s*:", sin_comentario):
            dentro = True
            continue
        if dentro:
            m = re.match(r"^\s+(\d+)\s*:\s*(.+?)\s*$", sin_comentario)      # formato dict
            if m:
                names[int(m.group(1))] = m.group(2).strip("'\"")
                continue
            m = re.match(r"^\s*-\s*(.+?)\s*$", sin_comentario)              # formato lista
            if m:
                names[len(names)] = m.group(1).strip("'\"")
                continue
            if not sin_comentario.startswith((" ", "\t")):
                dentro = False
    return names


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dataset", required=True, help="Carpeta con images/ y labels/")
    ap.add_argument("--data-yaml", default="ml/data.yaml")
    args = ap.parse_args()

    ds = Path(args.dataset)
    yml = Path(args.data_yaml)
    if not ds.is_dir():
        print(f"ERROR: no existe {ds}")
        return 1
    if not yml.is_file():
        print(f"ERROR: no existe {yml}")
        return 1

    names = leer_names(yml)
    if not names:
        print(f"ERROR: no se pudo leer 'names' de {yml}")
        return 1
    print(f"Clases declaradas en {yml.name}: {len(names)}")
    for i in sorted(names):
        print(f"  {i}: {names[i]}")

    errores, avisos = [], []
    conteo = {s: Counter() for s in SPLITS}
    totales = {}

    for split in SPLITS:
        img_dir, lbl_dir = ds / "images" / split, ds / "labels" / split
        if not img_dir.is_dir():
            avisos.append(f"[{split}] no existe la carpeta images/{split}")
            totales[split] = 0
            continue

        imgs = sorted(p for p in img_dir.iterdir() if p.suffix.lower() in IMG_EXT)
        totales[split] = len(imgs)
        stems_img = {p.stem for p in imgs}
        stems_lbl = {p.stem for p in lbl_dir.glob("*.txt")} if lbl_dir.is_dir() else set()

        for s in sorted(stems_img - stems_lbl):
            errores.append(f"[{split}] imagen sin etiqueta: {s}")
        for s in sorted(stems_lbl - stems_img):
            errores.append(f"[{split}] etiqueta sin imagen: {s}.txt")

        for lbl in sorted(lbl_dir.glob("*.txt")) if lbl_dir.is_dir() else []:
            for n, linea in enumerate(lbl.read_text(encoding="utf-8").splitlines(), 1):
                linea = linea.strip()
                if not linea:
                    continue
                partes = linea.split()
                if len(partes) != 5:
                    errores.append(f"[{split}] {lbl.name}:{n} esperaba 5 valores, hay {len(partes)}")
                    continue
                try:
                    cid = int(partes[0])
                    cx, cy, w, h = (float(v) for v in partes[1:])
                except ValueError:
                    errores.append(f"[{split}] {lbl.name}:{n} valores no numericos")
                    continue
                if cid not in names:
                    errores.append(f"[{split}] {lbl.name}:{n} clase {cid} no existe en data.yaml")
                    continue
                if not all(0.0 <= v <= 1.0 for v in (cx, cy, w, h)):
                    errores.append(f"[{split}] {lbl.name}:{n} coordenadas fuera de [0,1]")
                    continue
                if w <= 0 or h <= 0:
                    errores.append(f"[{split}] {lbl.name}:{n} caja de area cero")
                    continue
                conteo[split][cid] += 1

    print("\nImagenes por split:")
    for s in SPLITS:
        print(f"  {s:<6} {totales.get(s, 0):>5}")

    print("\nCajas por clase:")
    print(f"  {'id':<4}{'clase':<22}" + "".join(f"{s:>8}" for s in SPLITS) + f"{'total':>8}")
    for i in sorted(names):
        fila = [conteo[s][i] for s in SPLITS]
        print(f"  {i:<4}{names[i]:<22}" + "".join(f"{v:>8}" for v in fila) + f"{sum(fila):>8}")
        if sum(fila) == 0:
            avisos.append(f"la clase '{names[i]}' no tiene NINGUNA caja en el dataset")
        elif conteo["val"][i] == 0:
            avisos.append(f"la clase '{names[i]}' no aparece en val: no podras medir su precision")
        elif sum(fila) < 50:
            avisos.append(f"la clase '{names[i]}' tiene solo {sum(fila)} cajas (recomendado: 150+)")

    if avisos:
        print(f"\nAVISOS ({len(avisos)}):")
        for a in avisos:
            print(f"  - {a}")

    if errores:
        print(f"\nERRORES ({len(errores)}):")
        for e in errores[:40]:
            print(f"  - {e}")
        if len(errores) > 40:
            print(f"  ... y {len(errores) - 40} mas")
        print("\nCorrige los errores antes de entrenar.")
        return 1

    print("\nOK: el dataset esta bien formado. Listo para comprimir y subir a Drive.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
