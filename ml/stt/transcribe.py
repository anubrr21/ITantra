import argparse

from indic_conformer import load_model, transcribe


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("wav_path")
    parser.add_argument("lang_code")
    parser.add_argument("decoder", nargs="?", default="ctc", choices=["ctc", "rnnt"])
    args = parser.parse_args()

    model = load_model()
    text = transcribe(model, args.wav_path, args.lang_code, args.decoder)
    print(text)


if __name__ == "__main__":
    main()
