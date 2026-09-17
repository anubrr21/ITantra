import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastpitch_wrapper import FastPitchInferenceWrapper
from synthesize import load_synthesizer

ONNX_PATH = Path(__file__).resolve().parents[1] / "exported" / "hi" / "fastpitch.onnx"

TEST_SENTENCES = [
    "नमस्ते, यह एक परीक्षण है",
    "मदद भेजो अभी",
    "पानी खत्म हो गया है जल्दी आओ, हमें यहाँ बहुत मुश्किल हो रही है और समय बहुत कम बचा है",
]


def main():
    synth = load_synthesizer("hi")
    model = synth.tts_model
    model.eval()
    wrapper = FastPitchInferenceWrapper(model)
    wrapper.eval()

    session = ort.InferenceSession(str(ONNX_PATH), providers=["CPUExecutionProvider"])
    speaker_id_value = synth.tts_model.speaker_manager.name_to_id["female"]

    worst = 0.0
    for text in TEST_SENTENCES:
        token_ids = torch.tensor(model.tokenizer.text_to_ids(text), dtype=torch.long).unsqueeze(0)
        speaker_id = torch.tensor([speaker_id_value], dtype=torch.long)

        with torch.no_grad():
            reference = wrapper(token_ids, speaker_id).numpy()

        (onnx_mel,) = session.run(
            ["mel"],
            {"token_ids": token_ids.numpy(), "speaker_id": speaker_id.numpy()},
        )

        diff = np.abs(reference - onnx_mel).max()
        worst = max(worst, diff)
        print(f"tokens={token_ids.shape[1]:3d} mel_shape={onnx_mel.shape} max_abs_diff={diff:.3e}")

    print()
    if worst < 1e-3:
        print(f"MATCH: worst max_abs_diff={worst:.3e}")
    else:
        print(f"MISMATCH: worst max_abs_diff={worst:.3e} - the trace did not generalize")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
