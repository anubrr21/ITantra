# STT model prep

Python 3.10 venv at `ml/.venv` (created deliberately with 3.10, not the system's newer
Python, for PyTorch/transformers/NeMo ecosystem compatibility). Install deps with
`ml/.venv/Scripts/pip.exe install -r ml/requirements.txt`.

- `indic_conformer.py` — loads `ai4bharat/indic-conformer-600m-multilingual` via
  `transformers` (`trust_remote_code=True`) and transcribes a WAV file. **Needs a
  one-time Hugging Face login first — the model is gated. See
  [../../docs/MODEL_NOTES.md](../../docs/MODEL_NOTES.md) for exact steps.**
- `transcribe.py` — CLI: `python transcribe.py <wav> <lang_code> [ctc|rnnt]`.
- `vosk_engine.py` — thin wrapper around the same Vosk models bundled in the Android app
  (`app/src/main/assets/model-en-us`, `model-hi`), so the WER comparison in
  `ml/eval/wer_eval.py` is apples-to-apples with what actually ships.

See [../../docs/MODEL_NOTES.md](../../docs/MODEL_NOTES.md) for the full research this is
based on, including why the 600M multilingual checkpoint was chosen over the
per-language NeMo-based ones.
