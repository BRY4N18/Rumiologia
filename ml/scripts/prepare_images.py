"""
Normaliza las fotos crudas para dejarlas listas para Label Studio y YOLO.

Toma ml/imagenes_crudas/<Carpeta por equipo>/*.{jpg,heic,...} y produce una
sola carpeta plana ml/imagenes_listas/ con archivos ya utilizables.

Hace cuatro cosas, todas necesarias:

1. Convierte HEIC (formato de iPhone) a JPG. Ni Label Studio ni YOLO leen HEIC:
   sin esta conversion, esas fotos simplemente no existen para el proyecto.
2. Aplica la rotacion EXIF y la elimina. Las fotos de celular guardan la
   orientacion como metadato; si no se aplica, el modelo entrena con imagenes
   giradas 90 grados respecto a lo que tu ves.
3. Reduce el lado mayor a --max-size (1280 por defecto). YOLO entrena a 640, asi
   que fotos de 4000 px solo hacen el dataset pesado y el etiquetado lento.
4. Renombra con el prefijo del equipo: ankom_estufa_003.jpg. Los nombres quedan
   sin espacios ni simbolos raros, unicos, y al etiquetar sabes de un vistazo
   que equipo estas viendo.

Requiere el entorno virtual del proyecto (Pillow + pillow-heif ya instalados):

    .venv\\Scripts\\python.exe ml\\scripts\\prepare_images.py

El nombre de cada subcarpeta se convierte en el prefijo usando el mapeo de
ml/clases.json, para que coincida exactamente con las clases de data.yaml.
"""

import argparse
import json
import sys
import unicodedata
from pathlib import Path

try:
    from PIL import Image, ImageOps
    import pillow_heif
    pillow_heif.register_heif_opener()
except ImportError:
    print("ERROR: faltan dependencias. Ejecuta:")
    print(r"    .venv\Scripts\python.exe -m pip install pillow pillow-heif")
    sys.exit(1)

RAIZ = Path(__file__).resolve().parents[2]
ENTRADA = RAIZ / "ml" / "imagenes_crudas"
SALIDA = RAIZ / "ml" / "imagenes_listas"
CLASES = RAIZ / "ml" / "clases.json"

EXT_VALIDAS = {".jpg", ".jpeg", ".png", ".bmp", ".webp", ".heic", ".heif"}


def slug(texto: str) -> str:
    """Convierte 'AQUASEARCHER(tm) AB33M1 de_OHAUS' en 'aquasearcher_ab33m1_de_ohaus'."""
    t = unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode()
    t = "".join(c if c.isalnum() else "_" for c in t.lower())
    while "__" in t:
        t = t.replace("__", "_")
    return t.strip("_")


def cargar_prefijos() -> dict:
    """Mapea nombre de carpeta -> slug de clase, usando clases.json si existe."""
    if not CLASES.is_file():
        return {}
    datos = json.loads(CLASES.read_text(encoding="utf-8"))
    mapa = {}
    for c in datos["clases"]:
        for carpeta in c.get("carpetas", []):
            mapa[carpeta] = c["slug"]
    return mapa


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--max-size", type=int, default=1280,
                    help="Lado mayor en pixeles (0 = no redimensionar)")
    ap.add_argument("--calidad", type=int, default=90, help="Calidad JPEG (1-100)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if not ENTRADA.is_dir():
        print(f"ERROR: no existe {ENTRADA}")
        return 1

    prefijos = cargar_prefijos()
    carpetas = sorted(p for p in ENTRADA.iterdir() if p.is_dir())
    if not carpetas:
        print(f"ERROR: no hay subcarpetas de equipos en {ENTRADA}")
        return 1

    print(f"Entrada : {ENTRADA}")
    print(f"Salida  : {SALIDA}")
    print(f"Tamano  : lado mayor <= {args.max_size or 'original'} px\n")

    total, fallos, resumen = 0, [], {}

    for carpeta in carpetas:
        prefijo = prefijos.get(carpeta.name) or slug(carpeta.name)
        fotos = sorted(p for p in carpeta.rglob("*") if p.suffix.lower() in EXT_VALIDAS)
        if not fotos:
            print(f"  AVISO: {carpeta.name} no tiene imagenes")
            continue

        if not args.dry_run:
            SALIDA.mkdir(parents=True, exist_ok=True)

        n_ok = 0
        for i, foto in enumerate(fotos, 1):
            destino = SALIDA / f"{prefijo}_{i:04d}.jpg"
            if args.dry_run:
                n_ok += 1
                continue
            try:
                with Image.open(foto) as img:
                    img = ImageOps.exif_transpose(img)   # aplica y descarta rotacion EXIF
                    img = img.convert("RGB")             # descarta alfa y perfiles raros
                    if args.max_size and max(img.size) > args.max_size:
                        img.thumbnail((args.max_size, args.max_size), Image.LANCZOS)
                    img.save(destino, "JPEG", quality=args.calidad, optimize=True)
                n_ok += 1
            except Exception as e:
                fallos.append(f"{foto.relative_to(ENTRADA)}: {e}")

        resumen[carpeta.name] = (prefijo, n_ok, len(fotos))
        total += n_ok
        marca = "" if prefijos.get(carpeta.name) else "  (prefijo deducido del nombre)"
        print(f"  {n_ok:>4}/{len(fotos):<4} {carpeta.name}  ->  {prefijo}_XXXX.jpg{marca}")

    print(f"\nTotal procesado: {total} imagenes")

    if fallos:
        print(f"\nFALLOS ({len(fallos)}):")
        for f in fallos[:15]:
            print(f"  - {f}")

    # Aviso de desbalance: una clase con muy pocas fotos se detecta mal en la app.
    if resumen:
        minimo = min(v[1] for v in resumen.values())
        maximo = max(v[1] for v in resumen.values())
        print("\nBalance del dataset:")
        for nombre, (pref, n, _) in sorted(resumen.items(), key=lambda x: x[1][1]):
            barra = "#" * max(1, int(30 * n / maximo))
            alerta = "  <-- POCAS FOTOS" if n < 100 else ""
            print(f"  {n:>4}  {barra:<30} {pref}{alerta}")
        if maximo > 3 * minimo:
            print(f"\n  AVISO: desbalance {maximo}:{minimo}. Las clases con menos fotos")
            print("  se detectaran peor. Lo ideal es 150+ por clase y proporciones parecidas.")

    if args.dry_run:
        print("\n--dry-run: no se escribio nada.")
    else:
        print(f"\nListo. Importa {SALIDA.name}/ en Label Studio (arrastra todo el contenido).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
