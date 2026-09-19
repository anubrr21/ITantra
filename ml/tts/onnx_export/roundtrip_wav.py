import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "stt" / "onnx_export"))
from pure_onnx_pipeline import PureOnnxIndicConformer


def main(paths):
    stt = PureOnnxIndicConformer()
    for path in paths:
        data, sr = sf.read(path, dtype="float32")
        if data.ndim > 1:
            data = data.mean(axis=1)
        resampled = resample_poly(data, 16000, sr).astype(np.float32)
        tmp = Path(path).with_suffix(".16k.wav")
        sf.write(tmp, resampled, 16000)
        print(Path(path).name, "->", stt.transcribe(str(tmp), "hi"))


if __name__ == "__main__":
    main(sys.argv[1:])
