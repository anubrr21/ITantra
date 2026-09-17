import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import soundfile as sf

SNAPSHOT = (
    Path.home()
    / ".cache/huggingface/hub/models--ai4bharat--indic-conformer-600m-multilingual"
    / "snapshots/e9b71b369c048e2c6b634d4c131061c34e441179/assets"
)
PREPROCESSOR_ONNX = Path(__file__).resolve().parents[1] / "exported" / "preprocessor.onnx"
BLANK_ID = 256


class PureOnnxIndicConformer:
    def __init__(self):
        providers = ["CPUExecutionProvider"]
        self.preprocessor = ort.InferenceSession(str(PREPROCESSOR_ONNX), providers=providers)
        self.encoder = ort.InferenceSession(str(SNAPSHOT / "encoder.onnx"), providers=providers)
        self.ctc_decoder = ort.InferenceSession(str(SNAPSHOT / "ctc_decoder.onnx"), providers=providers)

        with open(SNAPSHOT / "vocab.json", encoding="utf-8") as f:
            self.vocab = json.load(f)
        with open(SNAPSHOT / "language_masks.json", encoding="utf-8") as f:
            self.language_masks = json.load(f)

    def transcribe(self, wav_path: str, lang: str) -> str:
        data, sr = sf.read(wav_path, dtype="float32", always_2d=True)
        assert sr == 16000, f"expected 16kHz, got {sr}"
        audio = data.mean(axis=1, keepdims=True).T  # [1, time]
        length = np.array([audio.shape[1]], dtype=np.int64)

        features, feat_len = self.preprocessor.run(
            ["features", "features_length"],
            {"input_signal": audio, "length": length},
        )

        encoder_out, encoded_lengths = self.encoder.run(
            ["outputs", "encoded_lengths"],
            {"audio_signal": features, "length": feat_len},
        )

        logprobs = self.ctc_decoder.run(["logprobs"], {"encoder_output": encoder_out})[0]

        mask = self.language_masks[lang]
        masked = logprobs[:, :, mask][0]
        masked = masked - masked.max(axis=-1, keepdims=True)
        exp = np.exp(masked)
        log_softmax = masked - np.log(exp.sum(axis=-1, keepdims=True))

        indices = np.argmax(log_softmax, axis=-1)
        collapsed = [indices[0]] if len(indices) else []
        for idx in indices[1:]:
            if idx != collapsed[-1]:
                collapsed.append(idx)

        vocab = self.vocab[lang]
        text = "".join(vocab[i] for i in collapsed if i != BLANK_ID)
        return text.replace("▁", " ").strip()


if __name__ == "__main__":
    import sys

    model = PureOnnxIndicConformer()
    text = model.transcribe(sys.argv[1], sys.argv[2])
    print(text)
