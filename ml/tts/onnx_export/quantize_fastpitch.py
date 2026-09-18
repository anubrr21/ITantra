import sys
from pathlib import Path

from onnxruntime.quantization import QuantType, quantize_dynamic

EXPORTED = Path(__file__).resolve().parents[1] / "exported"


def main(lang: str):
    source = EXPORTED / lang / "fastpitch.onnx"
    target = EXPORTED / lang / "fastpitch.int8.onnx"
    quantize_dynamic(
        model_input=str(source),
        model_output=str(target),
        weight_type=QuantType.QInt8,
        op_types_to_quantize=["MatMul"],
        use_external_data_format=True,
    )
    total_bytes = sum(f.stat().st_size for f in target.parent.glob("fastpitch.int8*"))
    print(f"quantized fastpitch written to {target}")
    print(f"total size: {total_bytes / 1e6:.1f} MB")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "hi")
