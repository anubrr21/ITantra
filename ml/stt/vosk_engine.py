import json
import wave

from vosk import KaldiRecognizer, Model


def load_model(model_path: str) -> Model:
    return Model(model_path)


def transcribe(model: Model, wav_path: str) -> str:
    wf = wave.open(wav_path, "rb")
    if wf.getnchannels() != 1 or wf.getsampwidth() != 2:
        raise ValueError(f"{wav_path} must be 16-bit mono PCM WAV")
    recognizer = KaldiRecognizer(model, wf.getframerate())
    while True:
        data = wf.readframes(4000)
        if len(data) == 0:
            break
        recognizer.AcceptWaveform(data)
    result = json.loads(recognizer.FinalResult())
    return result.get("text", "")
