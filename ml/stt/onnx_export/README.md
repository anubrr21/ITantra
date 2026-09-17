# On-device export pipeline for IndicConformer (Hindi)

Building block for Phase 3b — getting IndicConformer to actually run on a phone,
not just in this Python research harness. Run in order:

1. **`preprocessor_module.py`** — a faithful eager-PyTorch reimplementation of
   `assets/preprocessor.ts` (the mel-spectrogram frontend AI4Bharat ships as
   TorchScript). Needed because the original uses `torch.stft(..., return_complex=True)`,
   which ONNX's STFT op doesn't support exporting — this version uses
   `return_complex=False` instead, which is mathematically identical but exportable.
   Constants (pre-emphasis coefficient, FFT window, mel filterbank matrix) are extracted
   directly from the shipped model, not approximated.

2. **`verify_preprocessor.py`** — proves the reimplementation above matches the
   original TorchScript module (max diff ~1e-6, pure float32 noise) across several
   input lengths including a non-round one, before trusting it for anything downstream.

3. **`export_preprocessor.py`** — exports the verified reimplementation to
   `ml/stt/exported/preprocessor.onnx`.

4. **`verify_preprocessor_onnx.py`** — proves the ONNX export matches the PyTorch
   version (max diff ~5e-5, still noise) — export succeeding without an error doesn't
   mean the graph is correct, so this is checked independently.

5. **`pure_onnx_pipeline.py`** — chains `preprocessor.onnx` → AI4Bharat's own
   pre-exported `encoder.onnx` → `ctc_decoder.onnx`, using the exact language-masking +
   greedy-CTC-collapse decode algorithm read directly out of AI4Bharat's reference
   `model_onnx.py` (cached locally after the first `transformers` load — see the path
   inside this file). Runs entirely on `onnxruntime`, no PyTorch or `transformers`
   needed. Verified to reproduce the exact same transcriptions as the official model on
   real recorded Hindi speech — this is the actual blueprint to port to Kotlin.

6. **`quantize_encoder.py`** — dynamic int8 quantization of `encoder.onnx`, restricted
   to `MatMul` ops only (`op_types_to_quantize=["MatMul"]`). Quantizing `Conv` too was
   tried first and produced a smaller file (652MB vs 880MB), but it emits `ConvInteger`
   nodes that this machine's ONNX Runtime CPU provider can't run at all
   (`NOT_IMPLEMENTED` at session creation) — MatMul-only avoids that at the cost of
   leaving the (smaller, but not negligible) conv modules in fp32. Verified afterward
   against the same 3 real Hindi recordings: WER only rose from 0.0% to 7.7%, and that's
   almost entirely one word getting a diacritic variant (ख़त्म vs खत्म — same meaning),
   not a real recognition failure. Output: `ml/stt/exported/encoder.int8.onnx`, 880MB.

**Current state:** all 5 files this pipeline produces/needs
(`preprocessor.onnx`, `encoder.int8.onnx` renamed to `encoder.onnx`, `ctc_decoder.onnx`,
`vocab.json`, `language_masks.json`) are copied into
`app/src/main/assets/indic_conformer/` and consumed by
`IndicConformerSttEngine.kt`/`IndicConformerAssetProvisioner.kt`, which reimplement this
exact pipeline (including the language-masking + greedy-CTC-collapse decode) in Kotlin
using `onnxruntime-android`. **880MB is still heavy** for the spec's low/mid-range phone
target — worth revisiting in Phase 7 (real device RAM numbers) or by falling back to the
120M per-language NeMo checkpoint if it proves impractical. Not yet tested on a device —
testing is deferred until Phase 3 is fully wired up, per the project's own plan.
