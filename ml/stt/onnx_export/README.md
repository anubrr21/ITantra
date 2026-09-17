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

**Not done yet:** `encoder.onnx`'s real weights are ~2.4GB fp32 (external ONNX data
files, not in this folder — see `docs/MODEL_NOTES.md`) — far too large for a phone.
Quantizing that down to something mobile-viable (int8 first) is the next step, followed
by the actual Android/Kotlin port using `onnxruntime-android`.
