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
- STT bring-up: `com.alphacephei:vosk-android:0.3.75` (Maven Central). Needs unzipped
  model folders `model-en-us` (from `vosk-model-small-en-us-0.15`) and `model-hi` (from
  `vosk-model-small-hi-0.22`) under `app/src/main/assets/` — not fetched yet, see
  `app/src/main/assets/README.md`.
- TTS bring-up: Android's built-in `android.speech.tts.TextToSpeech` — zero extra
  dependency, zero model files.

## TTS candidates

| Model | Languages | License | Notes |
|---|---|---|---|
| [`AI4Bharat/Indic-TTS`](https://github.com/AI4Bharat/Indic-TTS) | 13 languages (covers all 10 required) | MIT (verify per-repo) | FastPitch + HiFiGAN — much lighter than LLM-style TTS, the better fit for "must run smoothly on low/mid-range phones." Primary target. |
| [`ai4bharat/indic-parler-tts`](https://huggingface.co/ai4bharat/indic-parler-tts-pretrained) | 21 languages | check model card | Parler-TTS architecture (LLM-style, larger, more natural prosody but heavier) — candidate only if Indic-TTS proves too robotic and the phone can afford the extra compute. |
| [`AI4Bharat/IndicF5`](https://github.com/AI4Bharat/IndicF5) | subset | check model card | Flow-matching TTS — worth a comparison pass, likely heavier than FastPitch. |

**Decision for Phase 4:** Indic-TTS (FastPitch+HiFiGAN) as the primary target given the
efficiency requirement; Parler-TTS/IndicF5 evaluated only if quality falls short after
quantization.

## On-device runtime

The problem statement requires open-source/TinyML frameworks and explicitly allows
"TensorFlow Lite for Microcontrollers, PyTorch Mobile or similar."

- **STT (conformer/wav2vec2-style transformer models):** TFLite conversion of these
  architectures is notoriously painful (dynamic shapes, unsupported ops). PyTorch
  Mobile / **ExecuTorch** (PyTorch's current on-device runtime, explicitly built for
  exactly this — voice models with int4/int8 quantization via `torchao`) is the more
  practical path and is explicitly permitted by the spec's "PyTorch Mobile or similar"
  clause.
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
