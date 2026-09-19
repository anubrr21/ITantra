# Architecture

## Goal

Two phones (or a phone + an embedded device running the same protocol) act as a
walkie-talkie, but instead of streaming raw audio — expensive on a low-bitrate link —
one side runs STT to turn speech into text, sends the text, and the other side runs TTS
to speak it. Phase 1 (current) sends raw audio while the transport/PTT layer gets proven
out; STT/TTS get inserted into the same pipeline in later phases without changing its
shape.

## Layers

```
UI (Compose)  ── PairingScreen, RadioScreen
     │
PttController  ── pure state machine: PUSH_TO_TALK vs PHONE_MODE, IDLE/TRANSMITTING/RECEIVING
     │
RadioService (foreground Service) ── owns the mic, the speaker, the active Transport, and
     │                                 (Phase 2+) the VAD/STT/TTS engines. TransmissionMode
     │                                 picks the path: RAW_AUDIO sends mic frames straight
     │                                 through; VOICE_TEXT runs them through VAD → SttEngine,
     │                                 and sends the recognized text instead of audio.
     │                                 Incoming RadioFrame.Audio plays through the speaker;
     │                                 RadioFrame.Text goes to TtsEngine.speak().
     │
AudioCapturer / AudioPlayer  ── AudioRecord/AudioTrack, 16kHz mono PCM16, 20ms frames
     │
RadioFrame / RadioFrameCodec  ── 1-byte type tag (Audio | Text) wrapping the payload;
     │                            everything below this line is unaware it exists
Transport (interface)  ── WifiDirectTransport | BluetoothClassicTransport
     │
FrameCodec  ── length-prefixed framing over the raw socket byte stream
```

## Why a `Transport` interface

The problem statement explicitly asks for "wifi/Bluetooth connected embedded device or
another phone." Everything above the `Transport` interface (PTT, audio, STT/TTS) is
written against `send(frame)` / `incomingFrames(): Flow<ByteArray>` / connection state,
and never touches `WifiP2pManager` or `BluetoothAdapter` directly. That means:
- Phase 6 can add a "which link is available right now" auto-fallback without touching
  the audio/ML code.
- The embedded-device side mentioned in the spec can implement the same `Transport`
  interface (e.g. over a serial-to-WiFi bridge) and plug into the same app unchanged.

WiFi Direct is the default because it gives ~200m range and far higher throughput than
classic Bluetooth (per research done for this project — see the walkie-talkie
architecture references in `docs/MODEL_NOTES.md`'s sources). Bluetooth Classic RFCOMM is
the fallback for devices/situations where WiFi Direct isn't usable.

## Why a foreground Service, not just an Activity

Android suspends microphone access and throttles background work once an app isn't
visible. A walkie-talkie has to keep listening/transmitting with the screen off — that
requires a foreground `Service` with a persistent notification, which is what
`RadioService` is. The `MainActivity` binds to it and reflects its state in Compose, but
the mic/socket work happens in the service regardless of whether the activity is alive.

## Push-to-talk vs phone mode

The spec says: "if turned off it should work like a phone." `PttController` models this
as two modes:
- `PUSH_TO_TALK`: mic is only live while the button is held — half-duplex, classic
  walkie-talkie behaviour.
- `PHONE_MODE`: mic is always live — full-duplex, like a normal call.

`PttController` is deliberately framework-free (no Android imports) so it can be unit
tested without instrumentation.

## Audio format

16kHz mono PCM16, 20ms frames (320 samples / 640 bytes), chosen because:
1. It's the standard input rate for the STT models this project targets (see
   `docs/MODEL_NOTES.md`) — Phase 1's raw-audio path and Phase 2+'s STT path share the
   exact same `AudioCapturer` output format, no resampling glue needed later.
2. 20ms is a conventional VAD/speech-processing frame size (WebRTC VAD, Silero VAD, and
   most streaming ASR front-ends expect 10-30ms frames).

## Framing over the socket

Both WiFi Direct (plain TCP once the P2P group forms) and Bluetooth Classic (RFCOMM)
hand you an unstructured byte stream — no message boundaries. `FrameCodec` prefixes every
payload with a 4-byte big-endian length so the receiver knows where one message ends and
the next begins. Phase 1 frames are raw audio chunks; Phase 2+ will use the same codec to
send recognized-text messages (much smaller than audio, which is the whole point of the
problem statement).

## Phase 2: VAD + STT/TTS bring-up

- **VAD**: `WebRtcVoiceActivityDetector` wraps `com.konovalov.vad.webrtc.VadWebRTC`
  (from `com.github.gkonovalov.android-vad:webrtc`, JitPack). `EnergyVoiceActivityDetector`
  still exists as the original hand-rolled fallback but isn't wired in anywhere anymore.
  `RadioService` tracks the speech→silence transition itself (the library only says
  "is this frame speech", not "utterance just ended") and calls `SttEngine.endUtterance()`
  on that edge — this is the "detecting pauses and stoppages" the spec asks for.
- **STT**: `VoskSttEngine` (`com.alphacephei:vosk-android`) feeds frames into a
  `org.vosk.Recognizer` only while VAD says speech is active, then calls
  `finalResult()` on the pause edge and recreates the recognizer for the next utterance.
  It needs an unpacked Vosk model directory to construct (see
  `app/src/main/assets/README.md` for how the model files are provisioned).
- **TTS**: `AndroidSystemTtsEngine` wraps the OS's built-in `TextToSpeech` — no model
  files needed, works immediately. This is the Phase 2 bring-up baseline Phase 4 will
  replace with the on-device AI4Bharat Indic-TTS model.
- **Language selection**: `SupportedLanguage` (English, Hindi so far, per the Phase 2
  scope decision) ties together a Vosk asset folder name and a TTS `Locale`.
  `RadioService.setLanguage()` tears down and rebuilds both engines for the new language.
- **Not disturbing Phase 1**: `Transport`, `FrameCodec`, `WifiDirectTransport`, and
  `BluetoothClassicTransport` are untouched. The only wire-format change is
  `RadioFrame`'s 1-byte type tag, added in `RadioService` above the transport layer — the
  raw-audio walkie-talkie still behaves identically to a user, it's picked via a new
  "Voice → text" toggle that defaults off (`TransmissionMode.RAW_AUDIO`).

## Phase 3b: on-device IndicConformer for Hindi

`IndicConformerSttEngine` implements the same `SttEngine` interface as `VoskSttEngine`
but is a straight Kotlin port of the pure-ONNX-Runtime pipeline verified in
`ml/stt/onnx_export/pure_onnx_pipeline.py` — three chained ONNX graphs
(`preprocessor.onnx` → `encoder.onnx` → `ctc_decoder.onnx`, all loaded via
`onnxruntime-android`) plus a greedy-CTC-collapse decode using AI4Bharat's own
language-masking scheme. `IndicConformerAssetProvisioner` copies the ~900MB of ONNX
assets from `app/src/main/assets/indic_conformer/` to internal storage once (ONNX
Runtime loads from a file path, not a byte array, since the encoder alone is 880MB).

`RadioService.setLanguage()` now tries `IndicConformerSttEngine` first for Hindi
(falling back to Vosk automatically if construction fails — e.g. the ONNX assets
aren't present) and still uses Vosk directly for every other language, since
AI4Bharat has no English model. Model construction runs on `Dispatchers.IO` inside
`serviceScope`, not the calling thread — copying ~900MB and loading an 880MB ONNX
session the first time a language is selected would otherwise freeze the UI.

## Phase 4: neural TTS and playback rules

`RadioService.setLanguage()` starts with `AndroidSystemTtsEngine` so speech works
immediately, then on `Dispatchers.IO` swaps in a neural engine for Hindi:
`PiperTtsEngine` (sherpa-onnx VITS, pratham) if its assets are bundled, else
`IndicTtsEngine` (FastPitch+HiFiGAN via ONNX Runtime) if provisioned, else it stays on
system TTS. `PiperAssetProvisioner` copies the assets to internal storage once, guarded
by a version marker.

All neural engines share `SpeechScheduler`, which owns the spec's playback rules:
normal messages play in arrival order; an alert jumps ahead of queued messages,
interrupts a playing normal message (its already-synthesized clip is kept and replayed
afterwards), and is never interrupted itself. While an alert is active the alarm stream
is raised to max (`AlertVolumeGuard`) and restored afterwards; clips play through
`ClipPlayer` (`AudioTrack`, `USAGE_ALARM` for alerts, `USAGE_MEDIA` otherwise). The
next sentence is synthesized while the current one plays. Synthesis calls on the Piper
engine are serialised because espeak-ng is not thread-safe.

An alert is a distinct `RadioFrame` tag byte (2) carrying UTF-8 text; the normal text
(1) and audio (0) tags are unchanged, so existing behaviour and peers are unaffected.
