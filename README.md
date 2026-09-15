# iTantra

**Indian Multilingual TTS & STT Aided Neural Transceiver — Radio Access for Low-Bitrate
Links.** Built for Smart India Hackathon 2026, Problem Statement 26173 (ISRO / Department
of Space).

An Android app that turns speech into text, streams the text over WiFi Direct or
Bluetooth instead of raw audio, and speaks it back out on the receiving phone — a
"digital walkie-talkie" for alert and distress scenarios where bandwidth is too scarce
for real audio, across 10 Indian languages (Hindi, Gujarati, Marathi, Kannada,
Malayalam, Tamil, Telugu, Odia, Bengali, English).

## Current status: Phase 2 — VAD + STT/TTS bring-up (not yet tested on a device)

Phase 1 built a working two-phone **raw-audio intercom**: WiFi Direct (primary) or
Bluetooth Classic (fallback) for the link, push-to-talk (hold = half-duplex transmit) or
a "phone mode" toggle (open full-duplex call), running in a foreground service. That
path is untouched and still the default.

Phase 2 adds a **"Voice → text" toggle** alongside it: when on, mic audio runs through a
WebRTC-based VAD, gets transcribed by Vosk (English + Hindi) on pause, and the
*recognized text* — not audio — is what actually goes over the wire, with the other
phone's Android system TTS speaking it back out. This is the core of what the problem
statement actually asks for (text over a low-bitrate link instead of streaming audio).
The Vosk model files are downloaded and in place; the only thing left is actually
running it on a device — see docs/ROADMAP.md's status note.

See [docs/ROADMAP.md](docs/ROADMAP.md) for the full phased plan (VAD, STT, TTS,
multilingual expansion, efficiency tuning) and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces fit together.
[docs/MODEL_NOTES.md](docs/MODEL_NOTES.md) has the research on which open-source models
this will run once ML lands.

## Building and running

You need **Android Studio** (which bundles the Android SDK, a JDK, and Gradle) — none of
that is installed on this machine yet. Get it from
[developer.android.com/studio](https://developer.android.com/studio).

1. Open the `iTantra/` folder in Android Studio.
2. Let it sync Gradle (first sync will download the Gradle 8.9 distribution and the
   Android SDK components declared in `app/build.gradle.kts` — needs an internet
   connection once, not at runtime).
3. **You need two physical Android phones to test this** — WiFi Direct does not work in
   the emulator, and Bluetooth Classic RFCOMM is unreliable there too.
4. Install the app on both phones (`Run` in Android Studio, or build an APK via
   `Build > Build Bundle(s)/APK(s)`).
5. Grant the microphone / location / nearby-devices / Bluetooth permissions when
   prompted on both phones (WiFi Direct peer discovery requires location permission on
   Android — that's a platform requirement, not something this app uses your location
   for).
6. On one phone tap **Connect via WiFi Direct → Host**. On the other, tap **Connect via
   WiFi Direct → Find peers** and select the host from the list.
7. Once connected, hold the big button to transmit (release to stop), or flip **Phone
   mode** on for an always-open call. Flip **Voice → text** on to switch to the STT/TTS
   pipeline instead of raw audio — pick English or Hindi, talk, and the recognized text
   (not audio) is what actually crosses the link.

If WiFi Direct fails to connect (some phone/router combos are picky about it), fall back
to **Connect via Bluetooth** — pair the two phones in system Bluetooth settings first,
then use the same Host / Find peers flow.

## Project layout

```
app/        Android app (Kotlin + Jetpack Compose), see docs/ARCHITECTURE.md
ml/         Python workspace for model prep — download/quantize/export STT & TTS models,
            plus the WER/latency/size benchmarking harness (Phase 2+, not used yet)
docs/       Architecture, roadmap, and model research notes
```

## License

MIT — see [LICENSE](LICENSE). Built on AI4Bharat's open-source (MIT) STT/TTS models;
see [docs/MODEL_NOTES.md](docs/MODEL_NOTES.md) for attribution.
