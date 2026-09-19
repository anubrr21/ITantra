import json
import sys
import time
import zipfile
import tarfile
from pathlib import Path

import jiwer
import numpy as np
import sherpa_onnx
import soundfile as sf
import vosk

REPO_ROOT = Path(__file__).resolve().parents[2]
MODELS = REPO_ROOT / "ml" / "stt" / "english_models"
MANIFEST = REPO_ROOT / "ml" / "eval" / "manifest.tsv"
VOSK_EN_US = REPO_ROOT / "app" / "src" / "main" / "assets" / "model-en-us"


def extract(archive: Path) -> Path:
    name = archive.name.replace(".tar.bz2", "").replace(".zip", "")
    target = MODELS / name
    if target.exists():
        return next((p for p in target.iterdir() if p.is_dir()), target)
    if archive.suffix == ".zip":
        with zipfile.ZipFile(archive) as z:
            z.extractall(target)
    else:
        with tarfile.open(archive, "r:bz2") as t:
            t.extractall(target)
    return next((p for p in target.iterdir() if p.is_dir()), target)


def load_clips():
    clips = []
    for line in MANIFEST.read_text(encoding="utf-8").splitlines()[1:]:
        path, lang, reference = line.split("\t")
        if lang != "en":
            continue
        data, rate = sf.read(REPO_ROOT / path, dtype="float32")
        if data.ndim > 1:
            data = data.mean(axis=1)
        assert rate == 16000
        clips.append((Path(path).name, data, reference))
    extra = MODELS / "extra_clips.tsv"
    if extra.exists():
        for line in extra.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            path, reference = line.split("\t")
            data, rate = sf.read(path, dtype="float32")
            if data.ndim > 1:
                data = data.mean(axis=1)
            clips.append((Path(path).name, data, reference))
    return clips


def vosk_transcriber(model_dir: Path):
    vosk.SetLogLevel(-1)
    model = vosk.Model(str(model_dir))

    def transcribe(samples):
        rec = vosk.KaldiRecognizer(model, 16000)
        pcm = (samples * 32767).astype(np.int16).tobytes()
        rec.AcceptWaveform(pcm)
        return json.loads(rec.FinalResult()).get("text", "")

    return transcribe


def whisper_transcriber(model_dir: Path, variant: str):
    recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(model_dir / f"{variant}-encoder.int8.onnx"),
        decoder=str(model_dir / f"{variant}-decoder.int8.onnx"),
        tokens=str(model_dir / f"{variant}-tokens.txt"),
        language="en",
        task="transcribe",
        num_threads=2,
    )

    def transcribe(samples):
        stream = recognizer.create_stream()
        stream.accept_waveform(16000, samples)
        recognizer.decode_stream(stream)
        return stream.result.text

    return transcribe


def normalize(text: str) -> str:
    keep = "abcdefghijklmnopqrstuvwxyz' "
    return " ".join("".join(c for c in text.lower() if c in keep).split())


def main():
    clips = load_clips()
    engines = {"vosk small en-us (current)": vosk_transcriber(VOSK_EN_US)}

    in_zip = MODELS / "vosk-model-small-en-in-0.4.zip"
    if in_zip.exists():
        engines["vosk small en-in"] = vosk_transcriber(extract(in_zip))

    for variant in ("tiny.en", "base.en"):
        archive = MODELS / f"whisper-{variant}.tar.bz2"
        if archive.exists() and archive.stat().st_size > 100_000_000 * (1 if variant == "tiny.en" else 2) - 1:
            directory = extract(archive)
            engines[f"whisper {variant} (int8)"] = whisper_transcriber(directory, variant)

    print(f"{len(clips)} English clips\n")
    for name, transcribe in engines.items():
        references, hypotheses = [], []
        total_time = 0.0
        print(f"== {name}")
        for clip_name, samples, reference in clips:
            start = time.perf_counter()
            heard = transcribe(samples)
            total_time += time.perf_counter() - start
            references.append(normalize(reference))
            hypotheses.append(normalize(heard))
            print(f"   {clip_name}: ref=\"{reference}\"  heard=\"{heard}\"")
        wer = jiwer.wer(references, hypotheses)
        print(f"   WER={wer * 100:.1f}%  total decode time={total_time:.2f}s\n")


if __name__ == "__main__":
    sys.exit(main())
