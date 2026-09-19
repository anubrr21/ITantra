# On-device export pipeline for Indic-TTS (Hindi)

Mirrors the STT export pattern in `ml/stt/onnx_export/` — same verify-before-trust
discipline. Run in order:

1. **`fastpitch_wrapper.py`** — wraps `ForwardTTS.inference()` (FastPitch) for clean
   ONNX export. Also monkey-patches `PositionalEncoding.forward` to drop a defensive
   `raise RuntimeError` guard clause (`if self.pe.size(2) < x.size(2): raise ...`) that
   blocks tracing with a dynamic sequence length — safe to drop since it only guards
   against sequences longer than 5000 frames (~58s of audio), far beyond any realistic
   voice message, and doesn't change the computed values for any input within that
   bound.
2. **`verify_fastpitch.py`** — proves the wrapper matches `model.inference()` exactly
   (0.0 diff — it's the same computation, just wrapped).
3. **`export_fastpitch.py`** — exports to `ml/tts/exported/hi/fastpitch.onnx` (+ a
   `.onnx.data` external-weights file, ONNX's standard format for large models).
   **Must use `dynamo=True`** — the legacy TorchScript-based exporter produces a graph
   that silently bakes in the traced sequence length inside `nn.MultiheadAttention`'s
   internal reshape, which only fails at *runtime* on a different-length input (a real
   correctness bug, not just a warning). The newer `torch.export`-based exporter
   handles this correctly, and its stricter symbolic-shape tracing is exactly what
   caught the positional-encoding issue in step 1 in the first place.
4. **`verify_fastpitch_onnx.py`** — the test that actually matters: verifies the ONNX
   export against three sentences of very different lengths (12, 24, 84 tokens), not
   just the one used for tracing. All matched to ~7e-5 (float32 noise).
5. **`hifigan_wrapper.py`** / **`export_hifigan.py`** / **`verify_hifigan_onnx.py`** —
   same pattern for the HiFiGAN vocoder. Much simpler model (pure feedforward
   convolutions, no attention), exported and verified across three very different mel
   lengths with no issues.
6. **`pure_onnx_tts_pipeline.py`** — chains both ONNX models (FastPitch → HiFiGAN, no
   PyTorch needed at inference — `torch` is only used to load `speakers.pth`, a tiny
   static lookup that'll just be a hardcoded constant in Kotlin). Verified two ways:
   (a) it's the real character-to-ID mapping extracted from the actual tokenizer and
   confirmed to reproduce identical token IDs (see `char_to_id.json`, not a
   reimplementation guess — an earlier draft of this file *did* guess the vocab
   ordering and was wrong; don't repeat that mistake), and (b) round-trip validation:
   synthesized speech fed back through the already-verified Phase 3b STT pipeline
   transcribes back word-for-word correctly. A raw sample-by-sample diff against
   `Synthesizer.tts()`'s own output doesn't match exactly (different length — the
   `Synthesizer` does its own silence trimming/padding this raw pipeline doesn't), but
   that's a cosmetic post-processing difference, not a computation bug — the
   round-trip test is what actually confirms correctness.

**Real sizes achieved (Hindi), before quantization:**
- FastPitch: 637MB raw checkpoint → 217MB ONNX (stripped optimizer state)
- HiFiGAN: 1016MB raw checkpoint → 56MB ONNX (stripped discriminator + optimizer state)
- Combined: ~273MB, already far more reasonable than the raw 1.65GB

**Outcome:** the Kotlin port (`IndicTtsEngine`) works on-device, but the shipped Hindi
voice is now Piper pratham (see `ml/tts/piper_export/` and `docs/MODEL_NOTES.md`);
FastPitch+HiFiGAN stays as a fallback. FastPitch int8 was tried
(`quantize_fastpitch.py`, `verify_quantized_fastpitch.py`) and rejected.
