# Roadmap

Kept up to date as phases complete. No fixed deadline — phases are ordered to de-risk
the hardest parts (multilingual accuracy, on-device performance) early rather than
leaving them to the end.

**Status:** Phases 0-2 are coded but none have been run on a real device yet — testing
was deliberately deferred until two physical phones are available. Treat Phase 2 as
"should work" rather than "verified" until that happens.

- [x] **Phase 0 — Scaffold.** Repo, Android project skeleton, Gradle config, docs.
- [x] **Phase 1 — Transport & PTT skeleton.** WiFi Direct + Bluetooth Classic behind a
      common `Transport` interface, raw 16kHz PCM audio streaming, push-to-talk +
      "phone mode", foreground service. No ML. Testable as a basic two-phone intercom.
- [x] **Phase 2 — VAD + STT bring-up (Hindi + English). Complete.**
      `WebRtcVoiceActivityDetector` (via `com.github.gkonovalov.android-vad:webrtc`) does
      real pause/stop detection. `VoskSttEngine` (via `com.alphacephei:vosk-android`) is
      the fast baseline STT, with the actual `vosk-model-small-en-us-0.15` and
      `vosk-model-small-hi-0.22` models bundled under `app/src/main/assets/`.
      `AndroidSystemTtsEngine` (the OS's built-in TTS) closes the loop end-to-end
      (text → speech) before touching any custom model. A new "Voice → text" toggle in
      the UI switches between this pipeline and Phase 1's raw-audio path, which is
      untouched and still the default. Not yet tested on a real device (see status note
      below) — that's the only thing left before calling this phase verified.
- [x] **Phase 3a — Accuracy benchmark & model decision: AI4Bharat IndicConformer
      (Hindi). Complete.** English dropped from this phase's scope — AI4Bharat has no
      English model; see MODEL_NOTES.md. Real Python eval harness at `ml/stt/` and
      `ml/eval/` — loads the actual `indic-conformer-600m-multilingual` model via
      `transformers`, transcribes real recorded WAV files (the user's own voice), scores
      real WER against Vosk via `jiwer`, with a fix for a Devanagari Unicode
      normalization quirk that was unfairly penalizing both engines. **Real result
      (2026-09-17, n=3 Hindi utterances): IndicConformer CTC 0.0% WER vs Vosk 23.1%
      WER.** Decision made: IndicConformer (CTC decoder) is the accuracy target for
      Hindi. See MODEL_NOTES.md for the full numbers and caveats (small sample size).
- [ ] **Phase 3b — On-device export for IndicConformer (Hindi).** Not started. The
      gated model repo turned out to already ship pre-exported ONNX pieces
      (`encoder.onnx` + external fp32 weights ~2.4GB, `ctc_decoder.onnx` 23MB) — likely
      a better starting point than tracing/exporting from PyTorch ourselves. Work needed:
      quantize the encoder (int8, maybe int4) via ONNX Runtime's own quantization
      tooling, get it running through onnxruntime-android, wire it into the app as a new
      `IndicConformerSttEngine` implementing the existing `SttEngine` interface (same
      pattern as `VoskSttEngine`), and re-benchmark size/RAM/RTF on a real low/mid-range
      phone (that last part needs Phase 5's device access anyway). Worth deciding later
      whether to do this per-language now or batch it with Phase 6's other 8 languages.
- [ ] **Phase 4 — TTS upgrade: AI4Bharat Indic-TTS.** Export FastPitch+HiFiGAN to ONNX
      Runtime Mobile, replace the system-TTS bring-up. Implement the spec's exact
      playback rules: normal messages play as a voice note, alert-type messages play at
      max volume and can't be interrupted.
- [ ] **Phase 5 — Full loop validation.** Two phones, one in STT mode / one in TTS mode,
      measure round-trip latency exactly as the spec's evaluation method describes.
- [ ] **Phase 6 — Multilingual expansion.** Repeat Phase 3/4's benchmark-and-pick pattern
      for Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali. Add a
      language switcher to the UI and a per-language model manager.
- [ ] **Phase 7 — Efficiency & robustness.** Measure RAM/flash footprint and idle CPU on
      an actual low/mid-range test device (the spec's stated target hardware).
      Battery/thermal check under sustained use. Connection-drop recovery. Noise
      robustness testing (the spec's real use case is alert/disaster scenarios — field
      noise, wind, crowds). Accessibility pass: icon-driven UI usable by non-literate
      users, per the spec's inclusivity goal.
- [ ] **Phase 8 — Submission packaging.** Pitch deck, demo video script, architecture
      diagrams, a written test report scored against the problem statement's own
      evaluation metrics table.

## Why this order

Training or fine-tuning is the highest-risk, highest-effort part of this project — it
gets validated on 1 language (Phase 3, Hindi — English has no AI4Bharat equivalent and
stays on Vosk) before being repeated 8 more times (Phase 6), instead of discovering a
fundamental problem after building all 10 language pipelines.
Raw-audio transport (Phase 1) ships first because it's fully testable without any model
work and de-risks the networking/PTT layer independently.
