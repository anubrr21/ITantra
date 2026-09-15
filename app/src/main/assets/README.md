Vosk models live directly here as unzipped folders, one per language, matching
`SupportedLanguage.voskAssetFolder` in
`app/src/main/java/com/itantra/radio/lang/SupportedLanguage.kt`:

```
app/src/main/assets/model-en-us/   <- from vosk-model-small-en-us-0.15 (~68MB unzipped)
app/src/main/assets/model-hi/      <- from vosk-model-small-hi-0.22 (~79MB unzipped)
```

**These are present on this machine but git-ignored** (see `.gitignore`) — they're
tens of MB of binary data per language and don't belong in the repo. On a fresh clone
(or a different machine) you need to re-fetch them:

1. Download from https://alphacephei.com/vosk/models:
   - https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
   - https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip
2. Unzip each.
3. Rename the extracted folder to match the names above exactly (`VoskModelProvisioner`
   passes that name straight to `StorageService.unpack`, which looks for an asset folder
   of that name) and place it directly under `app/src/main/assets/`.

Without these, the app still runs fine: "Voice → text" mode just won't produce any
outgoing text (TTS playback still works, since Android's built-in TTS needs no model
files). See docs/ROADMAP.md Phase 2.
