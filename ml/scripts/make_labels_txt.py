"""
Genera app/src/main/assets/labels.txt a partir de ml/data.yaml.

Garantiza que el orden de clases en Android sea IDENTICO al del entrenamiento.
Ejecutalo cada vez que cambies 'names' en data.yaml.

Uso:
    python ml/scripts/make_labels_txt.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from check_dataset import leer_names  # noqa: E402

RAIZ = Path(__file__).resolve().parents[2]
YAML = RAIZ / "ml" / "data.yaml"
SALIDA = RAIZ / "app" / "src" / "main" / "assets" / "labels.txt"


def main() -> int:
    if not YAML.is_file():
        print(f"ERROR: no existe {YAML}")
        return 1

    names = leer_names(YAML)
    if not names:
        print("ERROR: no se pudo leer 'names' de data.yaml")
        return 1

    faltantes = [i for i in range(max(names) + 1) if i not in names]
    if faltantes:
        print(f"ERROR: faltan indices de clase consecutivos: {faltantes}")
        return 1

    SALIDA.parent.mkdir(parents=True, exist_ok=True)
    SALIDA.write_text("\n".join(names[i] for i in sorted(names)) + "\n", encoding="utf-8")

    print(f"Escrito: {SALIDA.relative_to(RAIZ)}  ({len(names)} clases)")
    for i in sorted(names):
        print(f"  {i}: {names[i]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
