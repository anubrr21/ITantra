import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from hifigan_wrapper import HifiganInferenceWrapper
from synthesize import load_synthesizer

ONNX_PATH = Path(__file__).resolve().parents[1] / "exported" / "hi" / "hifigan.onnx"


def main():
    synth = load_synthesizer("hi")
    model = synth.vocoder_model
    model.eval()
    wrapper = HifiganInferenceWrapper(model)
    wrapper.eval()

    session = ort.InferenceSession(str(ONNX_PATH), providers=["CPUExecutionProvider"])

    worst = 0.0
    torch.manual_seed(0)
    for mel_time in (50, 170, 400):
        mel = torch.randn(1, 80, mel_time)

        with torch.no_grad():
            reference = wrapper(mel).numpy()

        (onnx_wav,) = session.run(["waveform"], {"mel": mel.numpy()})

        diff = np.abs(reference - onnx_wav).max()
        worst = max(worst, diff)
        print(f"mel_time={mel_time:4d} wav_shape={onnx_wav.shape} max_abs_diff={diff:.3e}")

    print()
    if worst < 1e-3:
        print(f"MATCH: worst max_abs_diff={worst:.3e}")
    else:
        print(f"MISMATCH: worst max_abs_diff={worst:.3e}")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
