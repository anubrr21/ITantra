import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastpitch_wrapper import FastPitchInferenceWrapper
from synthesize import load_synthesizer


def main():
    synth = load_synthesizer("hi")
    model = synth.tts_model
    model.eval()

    text = "नमस्ते, यह एक परीक्षण है"
    token_ids = torch.tensor(model.tokenizer.text_to_ids(text), dtype=torch.long).unsqueeze(0)
    speaker_id = torch.tensor([synth.tts_model.speaker_manager.name_to_id["female"]], dtype=torch.long)

    with torch.no_grad():
        reference = model.inference(token_ids, {"d_vectors": None, "speaker_ids": speaker_id})["model_outputs"]

    wrapper = FastPitchInferenceWrapper(model)
    wrapper.eval()
    with torch.no_grad():
        actual = wrapper(token_ids, speaker_id)

    diff = (reference - actual).abs().max().item()
    print(f"reference shape={tuple(reference.shape)} actual shape={tuple(actual.shape)}")
    print(f"max_abs_diff={diff:.3e}")
    if diff < 1e-6:
        print("MATCH")
    else:
        print("MISMATCH")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
