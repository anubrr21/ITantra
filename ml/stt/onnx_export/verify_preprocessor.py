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


def main():
    original = torch.jit.load(str(SNAPSHOT / "preprocessor.ts"))
    original.eval()

    _, consts = original.code_with_constants
    window = consts.c3
    mel_filterbank = consts.c4

    reimplemented = MelSpectrogramPreprocessor(window, mel_filterbank)
    reimplemented.eval()

    torch.manual_seed(0)
    worst = 0.0
    for num_samples in (8000, 16000, 48000, 50123):
        audio = torch.randn(1, num_samples)
        length = torch.tensor([num_samples], dtype=torch.int64)

        with torch.no_grad():
            expected_features, expected_length = original(audio, length)
            actual_features, actual_length = reimplemented(audio, length)

        assert expected_length.tolist() == actual_length.tolist(), (
            f"length mismatch at {num_samples}: {expected_length} vs {actual_length}"
        )
        diff = (expected_features - actual_features).abs().max().item()
        worst = max(worst, diff)
        print(f"num_samples={num_samples} shape={tuple(actual_features.shape)} max_abs_diff={diff:.3e}")

    print()
    if worst < 1e-4:
        print(f"MATCH: worst max_abs_diff={worst:.3e} across all test lengths")
    else:
        print(f"MISMATCH: worst max_abs_diff={worst:.3e} exceeds tolerance - do not trust this reimplementation")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
