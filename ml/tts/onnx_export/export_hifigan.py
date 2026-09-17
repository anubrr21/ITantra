import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from hifigan_wrapper import HifiganInferenceWrapper
from synthesize import load_synthesizer

OUTPUT_PATH = Path(__file__).resolve().parents[1] / "exported" / "hi" / "hifigan.onnx"


def main():
    synth = load_synthesizer("hi")
    model = synth.vocoder_model
    model.eval()

    wrapper = HifiganInferenceWrapper(model)
    wrapper.eval()

    dummy_mel = torch.randn(1, 80, 170)

    with torch.no_grad():
        reference = wrapper(dummy_mel)
    print(f"reference waveform shape: {tuple(reference.shape)}")

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)

    torch.onnx.export(
        wrapper,
        (dummy_mel,),
        str(OUTPUT_PATH),
        input_names=["mel"],
        output_names=["waveform"],
        dynamic_axes={
            "mel": {0: "batch", 2: "mel_time"},
            "waveform": {0: "batch", 2: "wav_time"},
        },
        opset_version=17,
        dynamo=True,
    )
    print(f"exported to {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
