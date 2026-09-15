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
- [x] **Phase 2 — VAD + STT bring-up (Hindi + English).** `WebRtcVoiceActivityDetector`
      (via `com.github.gkonovalov.android-vad:webrtc`) does real pause/stop detection.
      `VoskSttEngine` (via `com.alphacephei:vosk-android`) is the fast baseline STT.
      `AndroidSystemTtsEngine` (the OS's built-in TTS) closes the loop end-to-end
      (text → speech) before touching any custom model. A new "Voice → text" toggle in
      the UI switches between this pipeline and Phase 1's raw-audio path, which is
      untouched and still the default. **Not yet done: the actual Vosk model files
      aren't bundled** — see `app/src/main/assets/README.md`. Without them, `sttEngine`
      stays null and Voice → text mode simply produces no outgoing text (fails safe, no
      crash); the TTS half works immediately since it needs no model files. Not yet
      tested on real devices (see status note below).
- [ ] **Phase 3 — Accuracy upgrade: AI4Bharat IndicConformer (Hindi + English).** Export
      and quantize (int8, via PyTorch ExecuTorch — see MODEL_NOTES.md) the per-language
      IndicConformer checkpoints. Benchmark WER, real-time factor, and model size against
      the Vosk baseline using the problem statement's own weighting (accuracy 40%,
      efficiency 20%, latency 20%) to decide what ships.
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
gets validated on 2 languages (Phase 3) before being repeated 8 more times (Phase 6),
instead of discovering a fundamental problem after building all 10 language pipelines.
Raw-audio transport (Phase 1) ships first because it's fully testable without any model
work and de-risks the networking/PTT layer independently.
