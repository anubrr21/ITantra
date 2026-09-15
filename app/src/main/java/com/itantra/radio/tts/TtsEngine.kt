package com.itantra.radio.tts

/**
 * Text-to-speech backend abstraction. Phase 2 starts with Android's built-in TTS engine
 * as a bring-up baseline (fast to wire up, already covers several Indian languages).
 * Phase 4 swaps in the on-device AI4Bharat Indic-TTS (FastPitch+HiFiGAN, via ONNX
 * Runtime Mobile) model for full "no hosted API" compliance and better intelligibility.
 * See docs/MODEL_NOTES.md.
 */
interface TtsEngine {
    val languageCode: String

    /**
     * @param isAlert per the spec: alert-type messages must be announced at max volume
     * and must not be interruptible by the user.
     */
    fun speak(text: String, isAlert: Boolean)

    fun stop()
}
