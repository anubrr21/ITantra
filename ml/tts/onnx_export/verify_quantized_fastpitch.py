import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

EXPORTED = Path(__file__).resolve().parents[1] / "exported"
SENTENCES = [
    "नमस्ते, यह एक परीक्षण है",
    "मदद भेजो अभी",
    "पानी खत्म हो गया है जल्दी आओ हमें बहुत मदद चाहिए यहाँ बाढ़ आ गई है और सड़क बंद है",
]


def load(name: str) -> ort.InferenceSession:
    return ort.InferenceSession(str(EXPORTED / "hi" / name), providers=["CPUExecutionProvider"])


def main():
    with open(Path(__file__).resolve().parent / "char_to_id.json", encoding="utf-8") as f:
        char_to_id = json.load(f)

    fp32 = load("fastpitch.onnx")
    int8 = load("fastpitch.int8.onnx")

    for speaker in (0, 1):
        for text in SENTENCES:
            ids = np.array([[char_to_id[c] for c in text if c in char_to_id]], dtype=np.int64)
            feeds = {"token_ids": ids, "speaker_id": np.array([speaker], dtype=np.int64)}

            t0 = time.perf_counter()
            (mel_a,) = fp32.run(["mel"], feeds)
            t_fp32 = time.perf_counter() - t0

            t0 = time.perf_counter()
            (mel_b,) = int8.run(["mel"], feeds)
            t_int8 = time.perf_counter() - t0

            frames_a, frames_b = mel_a.shape[1], mel_b.shape[1]
            common = min(frames_a, frames_b)
            diff = np.abs(mel_a[:, :common] - mel_b[:, :common])
            print(
                f"speaker={speaker} tokens={ids.shape[1]:3d} "
                f"frames fp32={frames_a} int8={frames_b} "
                f"max_abs_diff={diff.max():.4f} mean_abs_diff={diff.mean():.5f} "
                f"time fp32={t_fp32*1000:.0f}ms int8={t_int8*1000:.0f}ms"
            )


if __name__ == "__main__":
    sys.exit(main())
