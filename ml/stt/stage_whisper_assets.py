import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MODELS = REPO_ROOT / "ml" / "stt" / "english_models"
TARGET = REPO_ROOT / "app" / "src" / "main" / "assets" / "whisper_stt" / "en"


def main(variant: str):
    source = next((MODELS / f"whisper-{variant}").iterdir())
    TARGET.mkdir(parents=True, exist_ok=True)
    shutil.copy(source / f"{variant}-encoder.int8.onnx", TARGET / "encoder.int8.onnx")
    shutil.copy(source / f"{variant}-decoder.int8.onnx", TARGET / "decoder.int8.onnx")
    shutil.copy(source / f"{variant}-tokens.txt", TARGET / "tokens.txt")
    total = sum(f.stat().st_size for f in TARGET.iterdir())
    print(f"staged {variant} into {TARGET} ({total / 1e6:.1f} MB)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "base.en")
