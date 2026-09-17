import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from preprocessor_module import MelSpectrogramPreprocessor

SNAPSHOT = (
    Path.home()
    / ".cache/huggingface/hub/models--ai4bharat--indic-conformer-600m-multilingual"
    / "snapshots/e9b71b369c048e2c6b634d4c131061c34e441179/assets"
)
OUTPUT_PATH = Path(__file__).resolve().parents[1] / "exported" / "preprocessor.onnx"


def main():
    original = torch.jit.load(str(SNAPSHOT / "preprocessor.ts"))
    _, consts = original.code_with_constants
    window = consts.c3
    mel_filterbank = consts.c4

    model = MelSpectrogramPreprocessor(window, mel_filterbank)
    model.eval()

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)

    dummy_audio = torch.randn(1, 16000 * 3)
    dummy_length = torch.tensor([16000 * 3], dtype=torch.int64)

    torch.onnx.export(
        model,
        (dummy_audio, dummy_length),
        str(OUTPUT_PATH),
        input_names=["input_signal", "length"],
        output_names=["features", "features_length"],
        dynamic_axes={
            "input_signal": {0: "batch", 1: "time"},
            "length": {0: "batch"},
            "features": {0: "batch", 2: "feature_time"},
            "features_length": {0: "batch"},
        },
        opset_version=17,
        dynamo=False,
    )
    print(f"exported to {OUTPUT_PATH} ({OUTPUT_PATH.stat().st_size / 1e3:.1f} KB)")


if __name__ == "__main__":
    main()
