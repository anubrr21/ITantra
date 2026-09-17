import sys
from pathlib import Path

from TTS.utils.synthesizer import Synthesizer

CHECKPOINTS = Path(__file__).resolve().parent / "checkpoints"


def load_synthesizer(lang: str) -> Synthesizer:
    lang_dir = CHECKPOINTS / lang
    return Synthesizer(
        tts_checkpoint=str(lang_dir / "fastpitch" / "best_model.pth"),
        tts_config_path=str(lang_dir / "fastpitch" / "config.json"),
        tts_speakers_file=str(lang_dir / "fastpitch" / "speakers.pth"),
        vocoder_checkpoint=str(lang_dir / "hifigan" / "best_model.pth"),
        vocoder_config=str(lang_dir / "hifigan" / "config.json"),
    )


def synthesize(synthesizer: Synthesizer, text: str, speaker: str, out_path: str):
    wav = synthesizer.tts(text, speaker_name=speaker)
    synthesizer.save_wav(wav, out_path)


if __name__ == "__main__":
    lang = sys.argv[1]
    speaker = sys.argv[2]
    text = sys.argv[3]
    out_path = sys.argv[4]

    synth = load_synthesizer(lang)
    synthesize(synth, text, speaker, out_path)
    print(f"wrote {out_path}")
