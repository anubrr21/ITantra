import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

EXPORTED = Path(__file__).resolve().parents[1] / "exported"
CHECKPOINTS = Path(__file__).resolve().parents[1] / "checkpoints"


class PureOnnxIndicTts:
    def __init__(self, lang: str):
        providers = ["CPUExecutionProvider"]
        lang_dir = EXPORTED / lang
        self.fastpitch = ort.InferenceSession(str(lang_dir / "fastpitch.onnx"), providers=providers)
        self.hifigan = ort.InferenceSession(str(lang_dir / "hifigan.onnx"), providers=providers)

        with open(Path(__file__).resolve().parent / "char_to_id.json", encoding="utf-8") as f:
            self.char_to_id = json.load(f)

        speakers = torch.load(
            CHECKPOINTS / lang / "fastpitch" / "speakers.pth",
            weights_only=False,
        )
        self.speaker_to_id = dict(speakers)

    def text_to_ids(self, text: str) -> np.ndarray:
        ids = [self.char_to_id[ch] for ch in text if ch in self.char_to_id]
        return np.array([ids], dtype=np.int64)

    def synthesize(self, text: str, speaker: str) -> np.ndarray:
        token_ids = self.text_to_ids(text)
        speaker_id = np.array([self.speaker_to_id[speaker]], dtype=np.int64)

        (mel,) = self.fastpitch.run(["mel"], {"token_ids": token_ids, "speaker_id": speaker_id})
        mel = mel.transpose(0, 2, 1)

        (wav,) = self.hifigan.run(["waveform"], {"mel": mel.astype(np.float32)})
        return wav.squeeze()


if __name__ == "__main__":
    import sys

    import soundfile as sf

    lang, speaker, text, out_path = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    tts = PureOnnxIndicTts(lang)
    wav = tts.synthesize(text, speaker)
    sf.write(out_path, wav, 22050)
    print(f"wrote {out_path}")
