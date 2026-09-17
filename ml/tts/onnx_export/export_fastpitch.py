import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastpitch_wrapper import FastPitchInferenceWrapper
from synthesize import load_synthesizer

OUTPUT_PATH = Path(__file__).resolve().parents[1] / "exported" / "hi" / "fastpitch.onnx"


def main():
    synth = load_synthesizer("hi")
    model = synth.tts_model
    model.eval()

    wrapper = FastPitchInferenceWrapper(model)
    wrapper.eval()

    text = "नमस्ते, यह एक परीक्षण है"
    token_ids = torch.tensor(model.tokenizer.text_to_ids(text), dtype=torch.long).unsqueeze(0)
    speaker_id = torch.tensor([synth.tts_model.speaker_manager.name_to_id["female"]], dtype=torch.long)

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)

    torch.onnx.export(
        wrapper,
        (token_ids, speaker_id),
        str(OUTPUT_PATH),
        input_names=["token_ids", "speaker_id"],
        output_names=["mel"],
        dynamic_axes={
            "token_ids": {0: "batch", 1: "num_tokens"},
            "speaker_id": {0: "batch"},
            "mel": {0: "batch", 1: "mel_time"},
        },
        opset_version=17,
        dynamo=True,
    )
    print(f"exported to {OUTPUT_PATH} ({OUTPUT_PATH.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
