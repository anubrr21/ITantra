# Roadmap

Kept up to date as phases complete. No fixed deadline — phases are ordered to de-risk
the hardest parts (multilingual accuracy, on-device performance) early rather than
leaving them to the end.

**Status:** As of 2026-09-17, Phases 0-3b compile and package into a real APK, and have
now been **installed and exercised on a real physical phone** (Redmi A7 Pro 5G, 4GB
RAM — genuinely low-end, exactly the spec's target hardware) via `adb`, one device at a
time since a second phone isn't available yet. Real bugs were found and fixed, and the
single biggest open risk in the whole project — whether the 880MB quantized
IndicConformer model even loads on a low-end phone — passed. Full two-phone pairing is
still untested. See "Build environment notes" and "Single-device real hardware testing"
below for the details and why nobody should assume these are guesses.

- [x] **Phase 0 — Scaffold.** Repo, Android project skeleton, Gradle config, docs.
- [x] **Phase 1 — Transport & PTT skeleton.** WiFi Direct + Bluetooth Classic behind a
      common `Transport` interface, raw 16kHz PCM audio streaming, push-to-talk +
      "phone mode", foreground service. No ML. Testable as a basic two-phone intercom.
      **Partially real-device tested (2026-09-17, single phone, see below):** app
      launches and runs stably, permission flow works, WiFi Direct discovery and
      Bluetooth Classic discovery/connect-attempt both exercised without crashing (WiFi
      Direct hit a real hardware limitation on this specific phone, handled gracefully —
      not our bug, see below). Full two-phone pairing still untested.
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
- [x] **Phase 3b — On-device export for IndicConformer (Hindi). Code-complete,
      not yet run on a device.** `assets/preprocessor.ts` (the mel-spectrogram frontend)
      couldn't be exported to ONNX directly — `torch.stft`'s complex-tensor output
      isn't supported by ONNX's STFT op — so it was faithfully reimplemented in eager
      PyTorch using `return_complex=False` (mathematically identical, ONNX-exportable)
      and numerically verified to match the original to ~1e-6 (see
      `ml/stt/onnx_export/verify_preprocessor.py`), then exported and re-verified against
      ONNX Runtime to ~5e-5 (`verify_preprocessor_onnx.py`) — both are floating-point
      noise, not real discrepancies. The full chain (this new `preprocessor.onnx` →
      AI4Bharat's own pre-exported `encoder.onnx` → `ctc_decoder.onnx`, decoded with the
      exact language-masking + greedy-CTC-collapse algorithm read directly out of
      AI4Bharat's reference `model_onnx.py`) now runs in **pure ONNX Runtime, no PyTorch
      needed at inference** (`ml/stt/onnx_export/pure_onnx_pipeline.py`) and reproduces
      the exact same transcriptions as the official model on all 3 real Hindi
      recordings. This is the verified blueprint for the Android port.
      **Quantized and wired into the app.** `encoder.onnx` int8-quantized (MatMul ops
      only — quantizing Conv too hit a `ConvInteger` op this machine's ONNX Runtime CPU
      provider can't run at all): 2.4GB → 880MB, re-verified against the same 3 real
      Hindi recordings at only 7.7% WER (still far ahead of Vosk's 23.1%, and almost all
      of that is one word's diacritic spelling variant, not a real error). `onnxruntime-android:1.27.0`
      added to Gradle; `IndicConformerSttEngine.kt` + `IndicConformerAssetProvisioner.kt`
      port the exact verified Python pipeline (preprocessor → encoder → CTC decoder →
      language-mask + greedy-CTC-collapse decode) to Kotlin, and `RadioService` now
      routes Hindi to it (falling back to Vosk automatically if the ~900MB of ONNX
      assets aren't present). **Still needed:** this Kotlin code has never been
      compiled or run — no Android SDK on the research machine, and real-device testing
      is deliberately deferred until Phase 3 is fully wired up. 880MB is also still
      heavy for the spec's low/mid-range phone target; worth revisiting in Phase 7 with
      real device RAM numbers, or falling back to the 120M per-language NeMo checkpoint
      if it proves impractical. Worth deciding later whether to do the remaining 8
      languages (Phase 6) through this same exported pipeline in a batch.
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

## Build environment notes (2026-09-17)

Android Studio (Quail 4, 2026.1.4) plus its bundled SDK finally landed on the research
machine, and getting an actual build green took more than just installing them — real,
non-obvious fixes, recorded here so nobody "fixes" them back into a broken state:

- **Gradle itself needs JDK ≤22, not Android Studio's bundled JDK 25.** Gradle 8.9
  fails against JBR 25 with a cryptic error (`What went wrong: 25.0.3` — no message,
  just the JDK version). Use a separate JDK (this machine has one at
  `C:\Program Files\Java\jdk-22`) as `JAVA_HOME` when running Gradle from the command
  line. Android Studio itself handles this internally when it manages Gradle, so this
  mostly matters for command-line builds.
- **Kotlin bumped 1.9.24 → 2.2.0** (and KSP correspondingly to `2.2.0-2.0.2`, and the
  Compose Compiler moved from the old `composeOptions.kotlinCompilerExtensionVersion`
  mechanism to the new `org.jetbrains.kotlin.plugin.compose` Gradle plugin, required by
  Kotlin 2.0+). Root cause: some dependency in this project's graph is compiled against
  kotlin-stdlib 2.2.0, which a 1.9.24 compiler can't read at all.
- **`ksp.useKSP2=false` in `gradle.properties`, intentionally.** KSP2 (the default with
  newer KSP versions) crashes processing Hilt's generated code with a Dagger
  `SuperficialValidation` `UnexpectedException` on `DefaultViewModelFactories
  .ActivityModule.viewModelKeys()` — a known, still-open class of Dagger/KSP2
  compatibility bug as of this Hilt version. Forcing the older KSP1 implementation
  avoids it entirely. Revisit removing this once Dagger/Hilt's KSP2 support matures.
- **Hilt bumped 2.51.1 → 2.56.2.** 2.51.1's bundled `kotlinx-metadata-jvm` only reads
  Kotlin metadata up to version 2.1.0; anything compiled with Kotlin 2.2.0 (see above)
  made `hiltJavaCompileDebug` fail with `IllegalArgumentException: Provided Metadata
  instance has version 2.2.0, while maximum supported version is 2.1.0`. 2.56.2 fixed it.
- **Two real bugs found in `WifiDirectTransport.kt`** (not environment issues — actual
  mistakes): `override fun onSuccess() = Log.d(...)` infers `Int` as the return type
  (since `Log.d()` returns one), which doesn't satisfy `WifiP2pManager.ActionListener
  .onSuccess(): Unit`. Fixed by using a block body instead of an expression body.
- The resulting debug APK is **~1.16GB**, almost entirely the bundled IndicConformer
  (~900MB) and Vosk (~150MB) model assets — a concrete number for the Phase 7
  efficiency-pass discussion about whether the 880MB quantized encoder is really
  viable for the spec's low-end-phone target.

## Single-device real hardware testing (2026-09-17)

Installed and exercised on the user's own phone — a **Redmi A7 Pro 5G, 4GB RAM,
Android 16 (HyperOS 3.0.10)** — via `adb`, since a second phone wasn't available. Real
bugs found and fixed, in order encountered:

1. **Crash on launch: `SecurityException` starting the foreground service.** Android
   14+ requires `RECORD_AUDIO` to already be granted *before* calling
   `startForeground()` with `foregroundServiceType="microphone"`. `MainActivity` was
   requesting the permission and starting the service at the same time, so the service
   almost always lost the race. Fixed: only start the service immediately if the
   permission is already granted; otherwise wait for the permission-result callback to
   confirm `RECORD_AUDIO` before starting it (`MainActivity.kt`).
2. **"Connect via WiFi Direct" appeared to do nothing.** Not a freeze — a real state
   bug. A freshly-created `Transport` starts in `TransportState.Idle`, the *same* state
   used to mean "no link chosen yet," so `PairingScreen` couldn't tell the two apart and
   kept showing the initial two buttons forever. Fixed by passing an explicit
   `hasChosenLink` boolean (`transport != null`) instead of inferring it from
   `transportState` (`PairingScreen.kt`, `MainActivity.kt`).
3. **Duplicate `BroadcastReceiver` registration.** `WifiDirectTransport.register()` had
   no guard, so tapping "Find peers" more than once (or hitting both "Find peers" and
   "Host") registered the same receiver twice, logged by Android as an "already
   registered" warning — a real resource leak. Fixed with an `isRegistered` flag
   (`WifiDirectTransport.kt`).
4. **WiFi Direct discovery failed on this specific phone with `BUSY (2)`** — decoded
   properly now instead of showing a bare integer (`WifiP2pManager` failure reasons
   mapped to readable strings in `describeFailure()`). Root cause, confirmed via
   `adb logcat`: `HalDevMgr: bestIfaceCreationProposal is null, requestIface=P2P,
   existingIface=[name=wlan2 type=AP, name=wlan0 type=STA]` — **this phone's WiFi
   chipset cannot run a P2P interface at the same time as a station (`STA`) connection
   plus an access-point (`AP`) interface**, and the phone had its Mobile Hotspot on
   (needed for the dev laptop's internet). This is a real hardware/firmware limitation
   on this specific chipset, not a bug in the app — but it's a genuinely useful field
   finding: **some real low-end phones cannot do WiFi Direct while hotspot/tethering is
   active**, which is exactly the kind of condition that could occur during the spec's
   actual disaster/field use case. Worth testing again once hotspot isn't needed, and
   worth remembering as a real-world constraint on WiFi Direct's reliability — part of
   why Bluetooth Classic exists as a fallback.
5. **Bluetooth Classic tested and works as designed.** "Find peers" correctly lists
   already-bonded devices (tested against the phone's paired earbuds); attempting to
   connect to a bonded device that isn't running this app fails gracefully (expected —
   it doesn't implement our custom RFCOMM service) without crashing the app.
6. **The single biggest open risk in Phase 3b — verified, passed.** Temporarily forced
   the app to load `IndicConformerSttEngine` (Hindi) immediately on startup instead of
   its normal English default, specifically to test whether the ~900MB of ONNX assets
   (880MB quantized encoder included) actually load on this real 4GB-RAM low-end phone
   without an out-of-memory crash. Result: **it works.** Confirmed three ways —
   (a) all 5 files copied byte-exact to internal storage (checked via `adb shell run-as`
   diffing file sizes against the source), (b) 2+ minutes of stable memory monitoring via
   `adb shell dumpsys meminfo` with zero crashes (process PSS settled around ~1GB, with
   most of it pushed into compressed zRAM swap rather than resident RAM — Android's
   memory management absorbed the large footprint instead of OOM-killing the process),
   and (c) no Vosk-fallback log lines appeared, which only happens if
   `IndicConformerSttEngine` construction threw and the code fell back to Vosk. This was
   a temporary, reverted-immediately test change (not a permanent default) — see the git
   history for the two commits bracketing it. **This does not mean performance is fine**
   (inference speed under heavy zRAM swap is untested and likely to be slow — a Phase 7
   question), but it means the model is not simply un-loadable on this class of device,
   which was a real, credible risk before this test.

Still not tested: full two-phone WiFi Direct/Bluetooth pairing, actual audio streaming
end-to-end, VAD/STT/TTS running through the real UI (blocked on reaching `RadioScreen`,
which requires a successful pairing), and anything in Phase 4+.
