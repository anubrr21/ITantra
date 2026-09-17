import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from preprocessor_module import MelSpectrogramPreprocessor

SNAPSHOT = (
    Path.home()
    / ".cache/huggingface/hub/models--ai4bharat--indic-conformer-600m-multilingual"
    / "snapshots/e9b71b369c048e2c6b634d4c131061c34e441179/assets"
)
ONNX_PATH = Path(__file__).resolve().parents[1] / "exported" / "preprocessor.onnx"


def main():
    original = torch.jit.load(str(SNAPSHOT / "preprocessor.ts"))
    _, consts = original.code_with_constants
    pytorch_model = MelSpectrogramPreprocessor(consts.c3, consts.c4)
    pytorch_model.eval()

    session = ort.InferenceSession(str(ONNX_PATH), providers=["CPUExecutionProvider"])

    torch.manual_seed(1)
    worst = 0.0
    for num_samples in (8000, 16000, 48000, 50123):
        audio = torch.randn(1, num_samples)
        length = torch.tensor([num_samples], dtype=torch.int64)

        with torch.no_grad():
            expected_features, expected_length = pytorch_model(audio, length)

        onnx_features, onnx_length = session.run(
            ["features", "features_length"],
            {"input_signal": audio.numpy(), "length": length.numpy()},
        )

        assert expected_length.tolist() == onnx_length.tolist(), (
            f"length mismatch at {num_samples}: {expected_length} vs {onnx_length}"
        )
        diff = np.abs(expected_features.numpy() - onnx_features).max()
        worst = max(worst, diff)
        print(f"num_samples={num_samples} shape={onnx_features.shape} max_abs_diff={diff:.3e}")

    print()
    if worst < 1e-3:
        print(f"MATCH: worst max_abs_diff={worst:.3e} - ONNX export is correct")
    else:
        print(f"MISMATCH: worst max_abs_diff={worst:.3e} - do not trust this ONNX export")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
