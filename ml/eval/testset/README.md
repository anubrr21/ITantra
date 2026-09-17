This eval only means something with real recorded speech in it — nothing here is
synthetic or fabricated, and `ml/eval/wer_eval.py` will just print "no rows yet" against
an empty manifest rather than making up a result.

To add a real test case:

1. Record a short utterance (a few seconds) as 16-bit mono PCM WAV at 16kHz. A phone
   voice recorder app usually needs converting — `ffmpeg -i input.m4a -ar 16000 -ac 1
   -sample_fmt s16 out.wav` gets you there.
2. Drop the file in this folder, e.g. `hi_001.wav`.
3. Add a row to `../manifest.tsv` (tab-separated): the path relative to the repo root,
   the language code (`en` or `hi` for now — Vosk covers both, IndicConformer only
   covers Indic languages so English rows only get scored against Vosk), and the exact
   reference transcript of what was actually said.

Example row:
```
ml/eval/testset/hi_001.wav	hi	नमस्ते कैसे हो
```

Then run:
```
ml/.venv/Scripts/python.exe ml/eval/wer_eval.py
```

The more utterances (different speakers, background noise, sentence lengths) the more
this WER number actually means for the "accuracy 40%" weighting in the problem
statement's evaluation criteria — a single clean sample is a smoke test, not a benchmark.

**`.wav` files in this folder are git-ignored on purpose** — they're recordings of real
people's voices, and this repo is public. `manifest.tsv` (just text) stays committed as
a record of what was tested and the real results obtained (see docs/MODEL_NOTES.md), but
the actual audio stays local-only. Anyone re-running the eval on a fresh clone needs to
re-record their own samples matching the filenames already listed in `manifest.tsv`, or
just add new rows for their own recordings.
