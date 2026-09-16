import sys
import time

import sounddevice as sd
import soundfile as sf

SAMPLE_RATE = 16000


def main():
    if len(sys.argv) < 3:
        print("usage: python record.py <output.wav> <duration_seconds>")
        raise SystemExit(1)

    output_path = sys.argv[1]
    duration = float(sys.argv[2])

    for remaining in (3, 2, 1):
        print(f"Recording in {remaining}...")
        time.sleep(1)
    print(f"Recording for {duration:.0f}s - speak now")

    audio = sd.rec(int(duration * SAMPLE_RATE), samplerate=SAMPLE_RATE, channels=1, dtype="int16")
    sd.wait()

    sf.write(output_path, audio, SAMPLE_RATE, subtype="PCM_16")
    print(f"Saved {output_path}")


if __name__ == "__main__":
    main()
