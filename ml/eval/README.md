# Benchmarking harness

`wer_eval.py` reads `manifest.tsv` (tab-separated: `wav_path`, `lang_code`,
`reference_text`), runs each real recording through Vosk and — for Indic languages —
through IndicConformer (CTC decoder), and prints real per-utterance transcripts, timing,
and an aggregate WER via `jiwer`. English rows only get scored against Vosk (AI4Bharat
has no English model).

It prints "no rows yet" against an empty manifest rather than fabricating a result —
see `testset/README.md` for how to add real recorded test cases. This only produces a
meaningful number once real speech (ideally several speakers, including noisy
conditions matching the spec's disaster/alert use case) is in the manifest.

Run: `ml/.venv/Scripts/python.exe ml/eval/wer_eval.py`

Model size, RAM footprint, and real-time-factor measurement on an actual low/mid-range
phone (not this dev machine) is Phase 7 scope — this harness only covers the accuracy
side for now.
