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
RadioService (foreground Service) ── owns the mic, the speaker, and the active Transport;
     │                                 wires capture → [future: VAD → STT →] Transport.send()
     │                                 and Transport.incomingFrames() → [future: TTS →] speaker
     │
AudioCapturer / AudioPlayer  ── AudioRecord/AudioTrack, 16kHz mono PCM16, 20ms frames
     │
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

## What's deliberately not built yet

- **VAD**: `EnergyVoiceActivityDetector` exists as a real, working but simple
  energy-threshold VAD — good enough to prove segmentation logic, not accurate enough for
  noisy field conditions. Phase 2 upgrades to WebRTC VAD or Silero VAD.
- **STT/TTS**: `SttEngine`/`TtsEngine` are interfaces only. Nothing implements them yet —
  Phase 1 sends raw audio directly, bypassing them entirely.
- **Multilingual UI**: language selection isn't built — there's only one (implicit)
  language right now because there's no STT/TTS to select a language for.
