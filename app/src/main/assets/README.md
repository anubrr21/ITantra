Vosk models go directly here as unzipped folders, one per language, matching
`SupportedLanguage.voskAssetFolder` in
`app/src/main/java/com/itantra/radio/lang/SupportedLanguage.kt`:

```
app/src/main/assets/model-en-us/   <- from vosk-model-small-en-us-0.15
app/src/main/assets/model-hi/      <- from vosk-model-small-hi-0.22
```

Get them from https://alphacephei.com/vosk/models, unzip, and rename the extracted
folder to match the names above exactly (`VoskModelProvisioner` passes that name
straight to `StorageService.unpack`, which looks for an asset folder of that name).

Without these, the app still runs fine: "Voice → text" mode just won't produce any
outgoing text (TTS playback still works, since Android's built-in TTS needs no model
files). See docs/ROADMAP.md Phase 2.

Do not commit the unzipped model files to git — they're several MB of binary data per
language and belong in `.gitignore` (already covers `ml/**` model artifacts; add an
entry here too if you do bundle them, or fetch them at first-run instead).
