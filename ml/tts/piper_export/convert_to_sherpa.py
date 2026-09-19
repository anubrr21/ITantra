import json
import sys
from pathlib import Path

import onnx

PIPER_DIR = Path(__file__).resolve().parents[1] / "piper"
OUT_ROOT = Path(__file__).resolve().parents[1] / "exported" / "piper"


def convert(voice: str):
    model_path = PIPER_DIR / f"hi_IN-{voice}-medium.onnx"
    config = json.loads((PIPER_DIR / f"hi_IN-{voice}-medium.onnx.json").read_text(encoding="utf-8"))

    out_dir = OUT_ROOT / f"hi_IN-{voice}-medium"
    out_dir.mkdir(parents=True, exist_ok=True)

    with open(out_dir / "tokens.txt", "w", encoding="utf-8", newline="\n") as f:
        for symbol, ids in config["phoneme_id_map"].items():
            f.write(f"{symbol} {ids[0]}\n")

    model = onnx.load(str(model_path))
    metadata = {
        "model_type": "vits",
        "comment": "piper",
        "language": config["language"]["name_english"],
        "voice": config["espeak"]["voice"],
        "has_espeak": 1,
        "n_speakers": config["num_speakers"],
        "sample_rate": config["audio"]["sample_rate"],
    }
    while len(model.metadata_props):
        model.metadata_props.pop()
    for key, value in metadata.items():
        entry = model.metadata_props.add()
        entry.key = key
        entry.value = str(value)
    onnx.save(model, str(out_dir / "model.onnx"))
    print("wrote", out_dir)


if __name__ == "__main__":
    convert(sys.argv[1] if len(sys.argv) > 1 else "pratham")
