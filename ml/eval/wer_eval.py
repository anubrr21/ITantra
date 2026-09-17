import csv
import sys
import time
import unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "stt"))

import jiwer

from indic_conformer import load_model as load_indic_conformer
from indic_conformer import transcribe as transcribe_indic_conformer
from vosk_engine import load_model as load_vosk
from vosk_engine import transcribe as transcribe_vosk

REPO_ROOT = Path(__file__).resolve().parents[2]
VOSK_MODEL_DIRS = {
    "en": REPO_ROOT / "app" / "src" / "main" / "assets" / "model-en-us",
    "hi": REPO_ROOT / "app" / "src" / "main" / "assets" / "model-hi",
}
INDIC_CONFORMER_LANGUAGES = {
    "as", "bn", "brx", "doi", "gu", "hi", "kn", "ks", "kok", "mai", "ml", "mni",
    "mr", "ne", "or", "pa", "sa", "sat", "sd", "ta", "te", "ur",
}

# Chandrabindu (U+0901) and anusvara (U+0902) are two different Unicode code
# points both commonly used to write the same nasal sound in spoken Hindi -
# treating them as distinct would penalize a correct transcription just for
# picking the other valid spelling of what was actually said.
CHANDRABINDU = "ँ"
ANUSVARA = "ं"


def normalize_text(text: str) -> str:
    text = unicodedata.normalize("NFC", text)
    text = text.replace(CHANDRABINDU, ANUSVARA)
    return text.strip()


def read_manifest(manifest_path: Path):
    with open(manifest_path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def main():
    manifest_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent / "manifest.tsv"
    rows = read_manifest(manifest_path)
    if not rows:
        print(f"No rows in {manifest_path} yet - see eval/testset/README.md to add real recordings.")
        return

    vosk_models = {}
    indic_conformer_model = None

    references_by_key = {}
    hypotheses_by_key = {}

    def record(engine: str, lang: str, reference: str, hypothesis: str):
        key = (engine, lang)
        references_by_key.setdefault(key, []).append(normalize_text(reference))
        hypotheses_by_key.setdefault(key, []).append(normalize_text(hypothesis))

    for row in rows:
        wav_path = REPO_ROOT / row["wav_path"]
        lang = row["lang_code"]
        reference = row["reference_text"]

        if lang not in vosk_models:
            model_dir = VOSK_MODEL_DIRS.get(lang)
            if model_dir and model_dir.exists():
                vosk_models[lang] = load_vosk(str(model_dir))
            else:
                vosk_models[lang] = None

        started = time.time()
        vosk_text = transcribe_vosk(vosk_models[lang], str(wav_path)) if vosk_models[lang] else None
        vosk_latency = time.time() - started
        if vosk_text is not None:
            record("vosk", lang, reference, vosk_text)
            print(f"[vosk/{lang}] {wav_path.name} ({vosk_latency:.2f}s): {vosk_text!r}")

        if lang in INDIC_CONFORMER_LANGUAGES:
            if indic_conformer_model is None:
                indic_conformer_model = load_indic_conformer()
            started = time.time()
            ic_text = transcribe_indic_conformer(indic_conformer_model, str(wav_path), lang, "ctc")
            ic_latency = time.time() - started
            record("indic_conformer_ctc", lang, reference, ic_text)
            print(f"[indic_conformer_ctc/{lang}] {wav_path.name} ({ic_latency:.2f}s): {ic_text!r}")
        else:
            print(f"[indic_conformer_ctc/{lang}] skipped - not an Indic-Conformer language")

    print()
    print("WER summary (per engine, per language - text normalized for Devanagari spelling variants)")
    for (engine, lang), refs in sorted(references_by_key.items()):
        wer = jiwer.wer(refs, hypotheses_by_key[(engine, lang)])
        print(f"  {engine}/{lang}: {wer:.3f} over {len(refs)} utterance(s)")


if __name__ == "__main__":
    main()
