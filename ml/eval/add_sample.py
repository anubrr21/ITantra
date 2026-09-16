import csv
import sys
from pathlib import Path

MANIFEST_PATH = Path(__file__).parent / "manifest.tsv"
REPO_ROOT = Path(__file__).resolve().parents[2]


def main():
    if len(sys.argv) < 4:
        print('usage: python add_sample.py <wav_path> <lang_code> "<reference text>"')
        raise SystemExit(1)

    wav_path = Path(sys.argv[1]).resolve()
    lang_code = sys.argv[2]
    reference_text = sys.argv[3]

    if not wav_path.exists():
        print(f"no such file: {wav_path}")
        raise SystemExit(1)

    relative_wav_path = wav_path.relative_to(REPO_ROOT).as_posix()

    with open(MANIFEST_PATH, "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, delimiter="\t")
        writer.writerow([relative_wav_path, lang_code, reference_text])

    print(f"added: {relative_wav_path}\t{lang_code}\t{reference_text}")


if __name__ == "__main__":
    main()
