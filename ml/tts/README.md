# TTS model prep

Uses its own venv, `ml/.venv-tts` (Python 3.10), separate from `ml/.venv` (STT) —
`coqui-tts`'s dependency constraints conflict with the newer `transformers` the STT
work needs, so they're kept isolated rather than fighting a shared environment. Install
with `ml/.venv-tts/Scripts/pip.exe install "coqui-tts[codec]" "transformers>=4.57,<5"`
then `pip install torch torchaudio` (not bundled by default since `coqui-tts` 0.27.4).

- `checkpoints/<lang>/{fastpitch,hifigan}/` — raw AI4Bharat checkpoints, downloaded from
  https://github.com/AI4Bharat/Indic-TTS/releases/tag/v1-checkpoints-release (~1.42GB
  per language zip, not gated — plain download, unlike the STT model). Git-ignored, not
  committed. **After unzipping, `config.json`'s `speakers_file` field needs patching**
  — it hardcodes a path from AI4Bharat's own training directory layout
  (`models/v1/<lang>/fastpitch/speakers.pth`) that the `Synthesizer` reads directly
  regardless of the constructor argument. Replace it with the real absolute path to
  the `speakers.pth` sitting next to that same `config.json`.
- `synthesize.py` — loads a language's FastPitch+HiFiGAN pair via `coqui-tts`'s
  `Synthesizer` and synthesizes real speech. Usage:
  `python synthesize.py <lang> <female|male> "<text>" out.wav`.

See [../../docs/MODEL_NOTES.md](../../docs/MODEL_NOTES.md) for the full research,
including a real round-trip validation (synthesized Hindi speech transcribed back
word-for-word correctly by the Phase 3b STT pipeline).

**Not done yet:** ONNX export (no pre-exported ONNX ships with this model, unlike the
STT one), quantization, and the Kotlin/`onnxruntime-android` port.
