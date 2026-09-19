import csv
import time
from pathlib import Path

import sounddevice as sd
import soundfile as sf

SAMPLE_RATE = 16000
SECONDS = 5
REPO_ROOT = Path(__file__).resolve().parents[2]
TESTSET = REPO_ROOT / "ml" / "eval" / "testset"
MANIFEST = REPO_ROOT / "ml" / "eval" / "manifest.tsv"

SENTENCES = [
    "what is your name",
    "we need water and food",
    "the road is blocked by a fallen tree",
    "there are three injured people here",
    "please send a doctor to the school",
    "my phone battery is almost finished",
    "the flood water is rising fast",
    "call the rescue team immediately",
]


def next_index() -> int:
    existing = sorted(TESTSET.glob("en_*.wav"))
    numbers = [int(p.stem.split("_")[1]) for p in existing if p.stem.split("_")[1].isdigit()]
    return (max(numbers) if numbers else 0) + 1


def main():
    index = next_index()
    print(f"\nYou will record {len(SENTENCES)} English sentences, {SECONDS} seconds each.")
    print("Speak naturally, the way you would into the walkie-talkie.\n")
    for sentence in SENTENCES:
        print(f'Sentence {SENTENCES.index(sentence) + 1}/{len(SENTENCES)}:  "{sentence}"')
        input("  Press Enter, then start speaking after the countdown... ")
        for remaining in (3, 2, 1):
            print(f"  {remaining}...")
            time.sleep(1)
        print("  SPEAK NOW")
        audio = sd.rec(int(SECONDS * SAMPLE_RATE), samplerate=SAMPLE_RATE, channels=1, dtype="int16")
        sd.wait()
        name = f"en_{index:03d}.wav"
        sf.write(TESTSET / name, audio, SAMPLE_RATE, subtype="PCM_16")
        with open(MANIFEST, "a", newline="", encoding="utf-8") as f:
            csv.writer(f, delimiter="\t").writerow([f"ml/eval/testset/{name}", "en", sentence])
        print(f"  saved {name}\n")
        index += 1
    print("Done. Thank you - tell Claude it is finished.")


if __name__ == "__main__":
    main()
