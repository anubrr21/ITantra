from pathlib import Path

from onnxruntime.quantization import QuantType, quantize_dynamic

SNAPSHOT = (
    Path.home()
    / ".cache/huggingface/hub/models--ai4bharat--indic-conformer-600m-multilingual"
    / "snapshots/e9b71b369c048e2c6b634d4c131061c34e441179/assets"
)
OUTPUT_PATH = Path(__file__).resolve().parents[1] / "exported" / "encoder.int8.onnx"


def main():
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    quantize_dynamic(
        model_input=str(SNAPSHOT / "encoder.onnx"),
        model_output=str(OUTPUT_PATH),
        weight_type=QuantType.QInt8,
        op_types_to_quantize=["MatMul"],
    )
    total_bytes = sum(f.stat().st_size for f in OUTPUT_PATH.parent.glob("encoder.int8*"))
    print(f"quantized encoder written to {OUTPUT_PATH}")
    print(f"total size: {total_bytes / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
