import shutil
import sys
from pathlib import Path

import piper

from convert_to_sherpa import OUT_ROOT, convert

ESPEAK_DATA = Path(piper.__file__).resolve().parent / "espeak-ng-data"
APP_ASSETS = Path(__file__).resolve().parents[3] / "app" / "src" / "main" / "assets" / "piper_tts"
KEEP_DICTS = {"hi_dict", "en_dict"}


def stage(voice: str, lang: str):
    convert(voice)
    source = OUT_ROOT / f"hi_IN-{voice}-medium"
    target = APP_ASSETS / lang
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    shutil.copy(source / "model.onnx", target / "model.onnx")
    shutil.copy(source / "tokens.txt", target / "tokens.txt")

    data_target = target / "espeak-ng-data"
    for item in ESPEAK_DATA.iterdir():
        if item.name.endswith("_dict") and item.name not in KEEP_DICTS:
            continue
        if item.is_dir():
            shutil.copytree(item, data_target / item.name)
        else:
            data_target.mkdir(exist_ok=True)
            shutil.copy(item, data_target / item.name)

    total = sum(f.stat().st_size for f in target.rglob("*") if f.is_file())
    count = sum(1 for f in target.rglob("*") if f.is_file())
    print(f"staged {count} files, {total / 1e6:.1f} MB into {target}")


if __name__ == "__main__":
    stage(sys.argv[1] if len(sys.argv) > 1 else "pratham", sys.argv[2] if len(sys.argv) > 2 else "hi")
