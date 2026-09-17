import soundfile as sf
import torch
import torchaudio
from transformers import AutoModel

MODEL_ID = "ai4bharat/indic-conformer-600m-multilingual"


def load_model():
    return AutoModel.from_pretrained(MODEL_ID, trust_remote_code=True)


def load_wav_16k_mono(wav_path: str) -> torch.Tensor:
    data, sr = sf.read(wav_path, dtype="float32", always_2d=True)
    wav = torch.from_numpy(data.T)
    wav = torch.mean(wav, dim=0, keepdim=True)
    target_sample_rate = 16000
    if sr != target_sample_rate:
        resampler = torchaudio.transforms.Resample(orig_freq=sr, new_freq=target_sample_rate)
        wav = resampler(wav)
    return wav


def transcribe(model, wav_path: str, lang_code: str, decoder: str = "ctc") -> str:
    wav = load_wav_16k_mono(wav_path)
    with torch.no_grad():
        return model(wav, lang_code, decoder)
