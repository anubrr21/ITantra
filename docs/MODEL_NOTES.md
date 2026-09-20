# Model research notes

Research done while planning this project (September 2026). Re-verify version/size
details on the model cards before actually integrating — these move fast.

## Why not train from scratch

Training accurate ASR/TTS for 10 Indian languages from scratch requires large curated
datasets and significant compute per language — not realistic for a solo/small-team
build. The realistic path to genuinely high accuracy is building on existing
purpose-built, open-source Indian-language models and specializing the *deployment*
(quantization, runtime, per-language selection) rather than the *training*.

## STT candidates

| Model | Languages | License | Notes |
|---|---|---|---|
| [`ai4bharat/indic-conformer-600m-multilingual`](https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual) | 22 Indian languages incl. all 10 required | MIT | Hybrid CTC+RNNT conformer, 600M params — likely too large to run comfortably on low/mid-range phones unquantized. Primary accuracy target once quantized. |
| Per-language `ai4bharat/indicconformer_stt_<lang>_hybrid_ctc_rnnt_large` checkpoints | one per language | MIT | Same family, per-language — worth comparing size/accuracy against the multilingual checkpoint per language. |
| `ai4bharat/indicwav2vec-hindi` and siblings | per language | MIT | Wav2Vec2-style, older generation than IndicConformer; useful fallback/comparison point. |
| [Vosk](https://alphacephei.com/vosk/) (Hindi, Gujarati, Telugu confirmed; check others) | subset of the 10 | Apache 2.0 | Classic Kaldi DNN-HMM, ~50MB/language, already has Android bindings. Much lower accuracy than transformer models, but trivial to integrate — this is the Phase 2 bring-up baseline specifically because it de-risks the *pipeline* fast, not because it's the final accuracy target. |

**Decision for Phase 2/3:** Vosk first (bring-up), then benchmark IndicConformer
(quantized) against it for Hindi + English before deciding what ships and before
repeating the process for the other 8 languages.

**Phase 2 implementation, locked in:**
- VAD: `com.github.gkonovalov.android-vad:webrtc:2.0.10` (JitPack) — a pure Kotlin/JNI
  port of WebRTC's VAD, no separate native build step needed. `EnergyVoiceActivityDetector`
  (hand-rolled, Phase 1) is kept in the codebase but no longer used.
- STT bring-up: `com.alphacephei:vosk-android:0.3.75` (Maven Central). Uses unzipped
  model folders `model-en-us` (from `vosk-model-small-en-us-0.15`, ~68MB unzipped) and
  `model-hi` (from `vosk-model-small-hi-0.22`, ~79MB unzipped) under
  `app/src/main/assets/` — downloaded and in place (git-ignored, not committed; see
  `app/src/main/assets/README.md` for how to re-fetch them on a fresh clone).
- TTS bring-up: Android's built-in `android.speech.tts.TextToSpeech` — zero extra
  dependency, zero model files.

**Phase 3 status: blocked on a real, external gate — not something to route around.**
Every AI4Bharat STT model on Hugging Face (`indic-conformer-600m-multilingual`,
`indicconformer_stt_hi_hybrid_ctc_rnnt_large`, `indicwav2vec-hindi`) is **gated**: the
page banner reads "You need to agree to share your contact information to access this
model," requiring a free Hugging Face account and clicking through the agreement before
any files can be downloaded. This can't be scripted or done on the user's behalf — it's
an identity/consent step only the account owner can take. Unblocking it:

1. Create a free account at https://huggingface.co/join if you don't have one.
2. Visit https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual and click
   "Agree and access repository."
3. Generate a read-only token at https://huggingface.co/settings/tokens.
4. In your own terminal (not something Claude can do — it needs an interactive prompt),
   run `ml/.venv/Scripts/huggingface-cli.exe login` and paste the token when asked. This
   caches it under your Windows user profile; it's never seen by or shared with Claude.

Once logged in, `ml/stt/transcribe.py` and `ml/eval/wer_eval.py` will work as-is —
`transformers.AutoModel.from_pretrained(..., trust_remote_code=True)` reads the cached
token automatically.

**Model choice for Phase 3, decided:** `ai4bharat/indic-conformer-600m-multilingual`
via plain `transformers` (`trust_remote_code=True`), **not** the per-language
`indicconformer_stt_hi_hybrid_ctc_rnnt_large` checkpoint. The per-language model is
smaller (120M vs 600M params) but requires installing AI4Bharat's NeMo fork
(`git clone https://github.com/AI4Bharat/NeMo.git`) — a much heavier, more fragile
dependency chain (pytorch-lightning, hydra, sentencepiece, etc.) than plain
`transformers`. The multilingual model trades some size for a dramatically simpler,
more reliable setup, and it covers all remaining 8 non-English Indic languages needed
for Phase 6 later, so it's not one-off work. Re-evaluate this trade-off once real
benchmark numbers exist — if CPU/mobile inference on the 600M model turns out too slow,
the per-language NeMo path becomes worth the setup cost.

**Real file-size finding (measured via the HF API, not estimated):** the repo already
ships pre-exported ONNX pieces — `assets/encoder.onnx` (3.0MB graph), `assets/ctc_decoder.onnx`
(23.1MB), `assets/rnnt_decoder.onnx` (40.7MB), and tiny (<1MB each) per-language RNNT
joint-network heads (`assets/joint_post_net_hi.onnx`, etc.), plus a shared `vocab.json`
and `language_masks.json` (a single shared vocabulary across all 22 languages, masked
per-language at inference — much more storage-efficient than 22 separate vocabs). The
encoder's actual weights are stored as ~2.4GB of external tensor files alongside the
tiny `encoder.onnx` graph file (ONNX's external-data mechanism) — that number lines up
almost exactly with 600M params × 4 bytes (fp32), confirming the model ships unquantized.

**This matters for the on-device decision:** the encoder (the expensive, shared part)
is ~2.4GB fp32 — much too large for a low/mid-range phone as-is. Quantization isn't
optional polish here, it's required before this could ever run on-device (int8 →
roughly 600-700MB, still heavy for a phone; int4 → roughly 300-350MB, more plausible
but needs an accuracy check). By contrast, the 120M-param per-language NeMo checkpoint
(`indicconformer_stt_hi_hybrid_ctc_rnnt_large`) is ~480MB fp32 → ~120MB int8, a much
more mobile-realistic size despite its heavier NeMo-fork setup cost. The 600M model via
`transformers` remains the right choice for the *Python-side accuracy research* either
way — it's what's actually easy to run and compare against Vosk.

**Update (2026-09-17): actually quantized and measured, not just estimated.** Dynamic
int8 quantization restricted to `MatMul` ops (quantizing `Conv` too produced a smaller
file but emitted `ConvInteger` nodes this machine's ONNX Runtime CPU provider can't run
at all — `NOT_IMPLEMENTED` at session creation) got the encoder from 2.4GB fp32 to
**880MB**, re-verified at only 7.7% WER (barely moved from 0.0%, and that's one word's
diacritic spelling variant, not a real error) against the same 3 real Hindi recordings.
880MB is still a lot heavier than the 120M NeMo model's projected ~120MB — **worth
revisiting once real device RAM numbers exist (Phase 7)**, especially if a genuinely
low-end test device can't comfortably hold an 880MB model in memory alongside the rest
of the app. Next quantization step to try if that happens: figure out why `ConvInteger`
isn't implemented on this CPU provider (a newer onnxruntime version? a different
quantization approach for Conv, e.g. `QDQ` format instead of `QOperator`?) to reclaim
the extra ~230MB Conv quantization would have saved, or fall back to the 120M model.

**Real measured result (2026-09-17), n=3 Hindi + 2 English utterances, user's own
recorded voice):**

| Engine / Language | WER |
|---|---|
| IndicConformer (CTC decoder) / Hindi | 0.0% |
| Vosk / Hindi | 23.1% |
| Vosk / English | 33.3% |

IndicConformer transcribed all 3 Hindi utterances exactly right, including one Vosk
transcribed almost entirely wrong. Sample size is small — this is directional evidence,
not a statistically bulletproof benchmark — but it matches what the research predicted
(a modern 600M-param conformer beating an older Kaldi DNN-HMM model) and is decisive
enough to act on. **Decision: IndicConformer (CTC decoder) is the target for Hindi**,
pending the quantization work below to make it mobile-viable; Vosk remains the only
option for English (no AI4Bharat equivalent exists). More real recordings across more
speakers/conditions would strengthen this further and are welcome any time, but aren't
blocking the decision.

Fair comparison required one fix to the eval harness itself: Hindi text has two valid
Unicode ways to write the same nasal sound (chandrabindu U+0901 vs anusvara U+0902) —
without normalizing them to the same code point before scoring, a technically-correct
transcription using the "other" spelling gets penalized as an error. `wer_eval.py` now
normalizes both reference and hypothesis text before computing WER, and reports a
separate WER per (engine, language) pair rather than one number that mixes languages.

**Important caveat on language codes:** AI4Bharat's model card lists supported
languages by name (Hindi, Bengali, Tamil, …), not by exact code string. `INDIC_CONFORMER_LANGUAGES`
in `ml/eval/wer_eval.py` uses a best-effort ISO 639-1/639-3 guess (`hi`, `bn`, `ta`, …,
falling back to 3-letter codes like `brx`/`doi`/`kok`/`mai`/`mni`/`sat` for languages
without a 639-1 code) — **verify these against the model's actual `config.json` /
`model_onnx.py` once you're past the login gate**, don't trust the guess blindly.

**English has no AI4Bharat equivalent.** All AI4Bharat STT models are for "Indic"
languages specifically and exclude English (confirmed: English is not in the 22-language
list for `indic-conformer-600m-multilingual`). English stays on Vosk indefinitely unless
a separate research track (Whisper, wav2vec2-base-960h) gets evaluated for it later —
that's a deliberate scope correction from the original Phase 3 wording ("Hindi +
English"), not an oversight.

## TTS candidates

| Model | Languages | License | Notes |
|---|---|---|---|
| [`AI4Bharat/Indic-TTS`](https://github.com/AI4Bharat/Indic-TTS) | 13 languages (covers all 10 required) | MIT (verify per-repo) | FastPitch + HiFiGAN — much lighter than LLM-style TTS, the better fit for "must run smoothly on low/mid-range phones." Primary target. |
| [`ai4bharat/indic-parler-tts`](https://huggingface.co/ai4bharat/indic-parler-tts-pretrained) | 21 languages | check model card | Parler-TTS architecture (LLM-style, larger, more natural prosody but heavier) — candidate only if Indic-TTS proves too robotic and the phone can afford the extra compute. |
| [`AI4Bharat/IndicF5`](https://github.com/AI4Bharat/IndicF5) | subset | check model card | Flow-matching TTS — worth a comparison pass, likely heavier than FastPitch. |

**Decision for Phase 4:** Indic-TTS (FastPitch+HiFiGAN) as the primary target given the
efficiency requirement; Parler-TTS/IndicF5 evaluated only if quality falls short after
quantization.

**Phase 4 bring-up, real and verified (2026-09-18):** Not gated (unlike the STT model) —
checkpoints are plain GitHub Release downloads, no HF login needed. Real findings:
- Per-language checkpoint zip is **~1.42GB** (`hi.zip`), unzipping to a 637MB FastPitch
  `best_model.pth` + a 1016MB HiFiGAN `best_model.pth` — both far larger than these
  architectures' actual inference weights (tens of MB each), meaning these are raw
  training checkpoints (optimizer state, discriminator weights) not inference-stripped
  exports. Real ONNX export sizes should end up much smaller once traced.
- Sample rate is **22050 Hz**, not the 16kHz used throughout the STT pipeline —
  resampling will be needed wherever TTS output feeds into anything expecting 16kHz
  (or just play it at its native rate on Android, which is simpler and fine for output).
- Model is multi-speaker (`female`=0, `male`=1) — `speaker` must be specified at
  synthesis time.
- One real bug hit and fixed: `config.json` hardcodes an absolute-looking path
  (`models/v1/hi/fastpitch/speakers.pth`) left over from AI4Bharat's own training
  directory layout, which the `Synthesizer` reads directly instead of respecting the
  `tts_speakers_file` constructor argument — worked around by patching the path in
  the config file itself (see `ml/tts/synthesize.py`'s surrounding notes).
- Uses the community-maintained `coqui-tts` PyPI fork (prebuilt Windows wheels — the
  original `TTS` package needs a C++ compiler to build from source on Windows, which
  this machine doesn't have). Needed its own **separate Python venv**
  (`ml/.venv-tts`) — `coqui-tts` declares only `transformers>=4.57` with no upper
  bound, so it pulls in a `transformers` version too new for its own bundled (unused)
  XTTS code; pinned to `transformers>=4.57,<5` to fix, kept isolated from the STT
  venv's newer `transformers` rather than downgrading that one and risking breaking
  Phase 3 work.
- **Real quality validation:** synthesized "नमस्ते, यह एक परीक्षण है" (see
  `ml/tts/hindi_tts_sample.wav`), resampled it to 16kHz, and fed it back through the
  already-verified Phase 3b IndicConformer STT pipeline. Transcribed back as
  "नमस्ते यह एक परीक्षण है" — word-for-word identical (the only difference is the
  comma, which CTC-based ASR correctly doesn't vocalize). This is real evidence the
  synthesized speech is genuinely correct and intelligible, not just "it ran without
  crashing."

**Update (2026-09-18): ONNX export done and verified, see `ml/tts/onnx_export/`.**
Real, non-obvious problem hit and fixed: FastPitch's self-attention
(`nn.MultiheadAttention`) exported "successfully" with the legacy TorchScript-based
exporter but silently baked in the traced sequence length, producing a runtime crash
on any other input length — a genuine correctness bug, not just a warning, and one
that a single-length smoke test would never catch (which is exactly why
`verify_fastpitch_onnx.py` tests three very different lengths, not one). Fixed by
switching to the newer `torch.export`-based exporter (`dynamo=True`), whose stricter
symbolic-shape tracing also surfaced a second, unrelated blocker — a defensive
`raise RuntimeError` guard in the positional encoding for sequences over 5000 frames —
patched out via a targeted monkeypatch since it can't fire for any realistic input.
HiFiGAN (pure feedforward convolutions, no attention) exported cleanly on the first
try. Real sizes: FastPitch 637MB→217MB ONNX, HiFiGAN 1016MB→56MB ONNX — combined
~273MB before quantization, already in the same ballpark as the STT model's *final
quantized* size. Full pipeline re-verified via the same round-trip-through-STT
technique used for the initial bring-up. **Still not done:** quantization and the
Kotlin/`onnxruntime-android` port.

## On-device runtime

The problem statement requires open-source/TinyML frameworks and explicitly allows
"TensorFlow Lite for Microcontrollers, PyTorch Mobile or similar."

- **STT (conformer/wav2vec2-style transformer models): done for Hindi CTC, via ONNX
  Runtime, not ExecuTorch as originally planned.** TFLite conversion of these
  architectures is notoriously painful (dynamic shapes, unsupported ops), and
  PyTorch Mobile/ExecuTorch would have needed tracing/exporting the PyTorch model
  ourselves. Better path found: `ai4bharat/indic-conformer-600m-multilingual`'s own
  repo already ships pre-exported ONNX pieces (`assets/encoder.onnx`,
  `assets/ctc_decoder.onnx`, `assets/rnnt_decoder.onnx`, per-language RNNT joint heads).
  The one piece AI4Bharat didn't export (`preprocessor.ts`, the mel-spectrogram
  frontend — its `torch.stft(return_complex=True)` isn't ONNX-exportable) was
  faithfully reimplemented and exported ourselves (see `ml/stt/onnx_export/`). The full
  chain now runs through `onnxruntime-android` on the Kotlin side
  (`IndicConformerSttEngine`) — ONNX Runtime Mobile ended up being the single runtime
  for STT, and is the leading candidate for TTS too (below), instead of needing two
  different mobile runtimes. ExecuTorch remains a fallback if a future language/model
  hits an ONNX export wall, and stays explicitly permitted by the spec's "PyTorch
  Mobile or similar" clause either way. RNNT decoding was not pursued for on-device use
  (CTC-only, per the original plan) — RNNT's autoregressive per-frame decoder loop
  would be far more complex to port and is unlikely to be worth it given CTC already
  hits 7.7% WER quantized.
- **TTS (FastPitch+HiFiGAN):** these convert cleanly to **ONNX**; **ONNX Runtime
  Mobile** (open-source, Android AAR available) is the practical target. TFLite is a
  fallback if ONNX Runtime Mobile's footprint turns out too large for the "low RAM/flash"
  requirement.
- Final choice per model gets locked in during Phase 3/4 after actually benchmarking
  model size, RAM use, and real-time factor on a real low/mid-range test device — that's
  the whole point of those phases.

## Sources consulted

- https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual
- https://github.com/AI4Bharat/IndicConformerASR
- https://huggingface.co/ai4bharat/indicwav2vec-hindi
- https://github.com/AI4Bharat/Indic-TTS
- https://huggingface.co/ai4bharat/indic-parler-tts-pretrained
- https://github.com/AI4Bharat/IndicF5
- https://ai4bharat.iitm.ac.in/areas/asr and /areas/tts
- https://github.com/pytorch/executorch
- https://pytorch.org/blog/building-voice-agents-with-executorch-a-cross-platform-foundation-for-on-device-audio/
- https://github.com/pytorch/android-demo-app/blob/master/SpeechRecognition/README.md
- https://alphacephei.com/vosk/ and https://alphacephei.com/vosk/android
- https://github.com/neso613/ASR_TFLite
- https://github.com/gkonovalov/android-vad (WebRTC VAD Android port, used in Phase 2)
- https://central.sonatype.com/artifact/com.alphacephei/vosk-android (used in Phase 2)
- https://github.com/murtaza98/Walkie-Talkie (WiFi Direct walkie-talkie reference)
- https://github.com/gms298/Android-Walkie-Talkie (Bluetooth walkie-talkie reference)
- https://f-droid.org/packages/org.jsl.wfwt/ (WiFi walkie-talkie reference)

## Phase 4 outcome: Piper (pratham) replaces FastPitch+HiFiGAN for Hindi

The AI4Bharat FastPitch+HiFiGAN pair ran correctly on-device but the user judged it
robotic, and it was slow on a low-end phone (RTF 1.2-2.0). Piper's Hindi voices
(`rhasspy/piper-voices`, `hi/hi_IN`: pratham, priyamvada, rohan) were compared by ear
and **pratham** was chosen. They are trained on the same IIT-Madras IndicTTS recordings
(see the dataset license linked from the voice's MODEL_CARD), so the difference is the
VITS architecture, not new data.

- **Runtime:** sherpa-onnx v1.13.8 `static-link-onnxruntime` AAR (38.7MB, placed in
  `app/libs/`, git-ignored; download from the k2-fsa/sherpa-onnx GitHub release). It
  statically links its own ONNX Runtime so it cannot clash with the
  `onnxruntime-android` the STT engine uses. Only a stray x86 `libonnxruntime.so` needed
  a `pickFirsts` rule.
- **Phonemization:** Hindi needs espeak-ng (stress placement, schwa deletion and
  cross-word effects are not a simple letter table), bundled inside that AAR. Its data
  is trimmed to ~1.3MB (`hi_dict`, `en_dict`, phondata, lang, voices).
- **License:** espeak-ng is GPL-3, so the distributed app is effectively GPL-3.
- **Reproduce:** `ml/tts/piper_export/convert_to_sherpa.py` adds the metadata sherpa
  expects and writes `tokens.txt` (LF endings required); `stage_android_assets.py`
  stages `app/src/main/assets/piper_tts/hi/`. Voice files: download `hi_IN-pratham-medium
  .onnx` and `.onnx.json` into `ml/tts/piper/`.
- **Quantization:** FastPitch int8 (MatMul only) saved 37MB and shifted predicted
  durations; not shipped. HiFiGAN is conv-only and stays fp32 (ConvInteger is not
  supported on ORT CPU).

## English speech recognition: Whisper base replaces Vosk small (2026-09-20)

Vosk's small en-us model was weak on the user's Indian-accented English in live use ("what's your
name" heard as "know your name"). Benchmarked on 10 of the user's own recordings
(`ml/eval/english_bench.py`; recordings are private and git-ignored, transcripts are in
`ml/eval/manifest.tsv`):

| Model | WER | Decode time, 10 clips (laptop) |
|---|---|---|
| Vosk small en-us (previous) | 71.4% | 13.8s |
| Vosk small en-in | 83.9% (51.8% after volume normalization) | 6.7s |
| Whisper tiny.en int8 | 26.8% | 3.4s |
| **Whisper base.en int8 (chosen)** | **12.5%** | 6.2s |

On the phone (Redmi A7 Pro 5G, 4GB) the same 10 clips gave **8.9% WER at ~0.9s per utterance**,
model load 2.4s. Runs through the sherpa-onnx `OfflineRecognizer` already bundled for Piper, so
there is no new native library; assets are ~161MB (encoder 29MB + decoder 131MB int8) under
`assets/whisper_stt/en/`, staged with `ml/stt/stage_whisper_assets.py base.en`. Vosk stays as
the fallback. Whisper emits non-speech markers such as "[ Silence ]" and "(buzzer)", which
`SttTextFilter` drops. Caveat: 10 clips from one speaker is a small test; it is a strong signal
against Vosk, not a general accuracy claim.

