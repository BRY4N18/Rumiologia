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

AGREGAR FOTOS SIN TOCAR LO YA ETIQUETADO
----------------------------------------
Este script renumera desde 1 en cada corrida y no limpia la carpeta de salida.
Correrlo dos veces con distinto numero de fotos deja archivos viejos sueltos y
cambia a que foto apunta cada nombre, lo que rompe la correspondencia con las
tareas ya etiquetadas en Label Studio.

Para una sesion nueva de fotos, procesalas aparte con --entrada/--salida y
marcalas con --sufijo, de modo que los nombres no puedan chocar:

    .venv\\Scripts\\python.exe ml\\scripts\\prepare_images.py ^
        --entrada fotos_para_agregar --salida ml/imagenes_listas_s2 --sufijo s2

Eso produce memmert_s2_0001.jpg, etc. Toda la carpeta de salida es "lo nuevo":
se arrastra entera a Label Studio y las tareas existentes no se tocan.

Si detecta que iba a pisar archivos ya existentes, aborta esa carpeta y lo avisa
en vez de sobrescribir en silencio.

Nota: el prefijo del nombre es solo una comodidad para quien etiqueta. La clase
real de cada caja sale del .txt de anotacion, no del nombre del archivo, asi que
las fotos pueden procesarse antes de estar clasificadas por equipo.
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
    ap.add_argument("--entrada", type=Path, default=ENTRADA,
                    help="Carpeta con las subcarpetas de fotos (por defecto ml/imagenes_crudas)")
    ap.add_argument("--salida", type=Path, default=SALIDA,
                    help="Carpeta destino (por defecto ml/imagenes_listas)")
    ap.add_argument("--sufijo", default="",
                    help="Marca de sesion en el nombre: --sufijo s2 -> memmert_s2_0001.jpg. "
                         "Sirve para procesar fotos nuevas sin que choquen con las ya etiquetadas")
    ap.add_argument("--sobrescribir", action="store_true",
                    help="Permite pisar archivos ya existentes en la carpeta de salida")
    args = ap.parse_args()

    entrada, salida = args.entrada, args.salida
    # El sufijo se normaliza igual que los prefijos: sin espacios ni acentos.
    sufijo = f"_{slug(args.sufijo)}" if args.sufijo else ""

    if not entrada.is_dir():
        print(f"ERROR: no existe {entrada}")
        return 1

    prefijos = cargar_prefijos()
    carpetas = sorted(p for p in entrada.iterdir() if p.is_dir())
    if not carpetas:
        print(f"ERROR: no hay subcarpetas de equipos en {entrada}")
        return 1

    print(f"Entrada : {entrada}")
    print(f"Salida  : {salida}")
    print(f"Sufijo  : {sufijo or '(ninguno)'}")
    print(f"Tamano  : lado mayor <= {args.max_size or 'original'} px\n")

    total, fallos, resumen, colisiones = 0, [], {}, []

    for carpeta in carpetas:
        prefijo = prefijos.get(carpeta.name) or slug(carpeta.name)
        fotos = sorted(p for p in carpeta.rglob("*") if p.suffix.lower() in EXT_VALIDAS)
        if not fotos:
            print(f"  AVISO: {carpeta.name} no tiene imagenes")
            continue

        # Este script numera desde 1 en cada corrida y nunca limpia la salida: si ya
        # hay archivos con estos nombres, son de otra corrida y apuntan a OTRAS fotos.
        # Pisarlos rompe la correspondencia con lo que ya este etiquetado en Label
        # Studio, asi que se avisa y se aborta salvo que se pida explicitamente.
        ya_existen = [n for n in range(1, len(fotos) + 1)
                      if (salida / f"{prefijo}{sufijo}_{n:04d}.jpg").exists()]
        if ya_existen and not args.sobrescribir:
            colisiones.append((prefijo, len(ya_existen)))
            continue

        if not args.dry_run:
            salida.mkdir(parents=True, exist_ok=True)

        n_ok = 0
        for i, foto in enumerate(fotos, 1):
            destino = salida / f"{prefijo}{sufijo}_{i:04d}.jpg"
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
                fallos.append(f"{foto.relative_to(entrada)}: {e}")

        resumen[carpeta.name] = (prefijo, n_ok, len(fotos))
        total += n_ok
        marca = "" if prefijos.get(carpeta.name) else "  (prefijo deducido del nombre)"
        print(f"  {n_ok:>4}/{len(fotos):<4} {carpeta.name}  ->  {prefijo}{sufijo}_XXXX.jpg{marca}")

    print(f"\nTotal procesado: {total} imagenes")

    if colisiones:
        print(f"\nABORTADO en {len(colisiones)} carpeta(s): ya hay archivos con esos nombres")
        print("en la carpeta de salida, y apuntan a OTRAS fotos (este script renumera")
        print("desde 1 en cada corrida). Pisarlos romperia la correspondencia con lo que")
        print("ya tengas etiquetado en Label Studio.\n")
        for pref, n in colisiones:
            print(f"  - {pref}{sufijo}: {n} archivo(s) ya existen")
        print("\nOpciones:  usa --sufijo para marcar esta sesion (recomendado),")
        print("           o --salida para escribir en otra carpeta,")
        print("           o --sobrescribir si de verdad quieres pisarlos.")

    if fallos:
        print(f"\nFALLOS ({len(fallos)}):")
        for f in fallos[:15]:
            print(f"  - {f}")

    # Aviso de desbalance: una clase con muy pocas fotos se detecta mal en la app.
    OBJETIVO_MIN, OBJETIVO_MAX = 80, 150
    if resumen:
        minimo = min(v[1] for v in resumen.values())
        maximo = max(v[1] for v in resumen.values())
        print(f"\nBalance del dataset (objetivo: {OBJETIVO_MIN}-{OBJETIVO_MAX} fotos/clase):")
        faltan_total = 0
        for nombre, (pref, n, _) in sorted(resumen.items(), key=lambda x: x[1][1]):
            barra = "#" * max(1, int(30 * n / max(maximo, OBJETIVO_MAX)))
            if n < OBJETIVO_MIN:
                faltan = OBJETIVO_MIN - n
                faltan_total += faltan
                alerta = f"  <-- faltan {faltan} para el minimo ({OBJETIVO_MIN})"
            elif n > OBJETIVO_MAX:
                alerta = f"  <-- por encima del maximo sugerido ({OBJETIVO_MAX})"
            else:
                alerta = "  OK"
            print(f"  {n:>4}  {barra:<30} {pref}{alerta}")
        if faltan_total:
            print(f"\n  Total de fotos que faltan para que TODAS las clases lleguen")
            print(f"  al minimo de {OBJETIVO_MIN}: {faltan_total}.")
        if maximo > 3 * minimo:
            print(f"\n  AVISO: desbalance {maximo}:{minimo}. Las clases con menos fotos")
            print("  se detectaran peor. Lo ideal es proporciones parecidas entre clases.")

    if args.dry_run:
        print("\n--dry-run: no se escribio nada.")
    elif total:
        print(f"\nListo. Importa {salida.name}/ en Label Studio (arrastra todo el contenido).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
