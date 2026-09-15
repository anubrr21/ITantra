package com.itantra.radio.stt

/**
 * Speech-to-text backend abstraction. Phase 1 (current) ships no implementation — the
 * radio transmits raw audio frames directly, so the transport/PTT/audio plumbing can be
 * proven end to end first. Phase 2 adds a VoskSttEngine (Hindi + English) as a fast,
 * lightweight bring-up baseline; Phase 3 swaps in a quantized AI4Bharat IndicConformer
 * per language once benchmarked against it. See docs/MODEL_NOTES.md.
 */
interface SttEngine {
    val languageCode: String

    fun start()

    /** Feed one 20ms PCM16 frame (see AudioConfig.FRAME_BYTES) from the mic. */
    fun acceptAudioFrame(frame: ByteArray)

    /** Called on a VAD-detected pause/stop to flush the current utterance. */
    fun endUtterance()

    fun stop()

    fun setOnResult(callback: (text: String) -> Unit)
}
