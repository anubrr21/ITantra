package com.itantra.radio.ptt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PttMode {
    /** Hold-to-talk: mic is only live while the PTT button is pressed (half-duplex),
     * matching the spec's "push to talk feature" for walkie-talkie mode. */
    PUSH_TO_TALK,

    /** PTT toggled off: mic is always live, matching the spec's "if turned off it
     * should work like a phone" (open full-duplex call). */
    PHONE_MODE,
}

enum class TransmitState { IDLE, TRANSMITTING, RECEIVING }

/**
 * Pure state machine for whether the mic should be capturing right now. Deliberately
 * free of any Android framework types so it's unit-testable without instrumentation;
 * RadioService is the only thing that touches real audio/transport APIs based on it.
 */
class PttController {
    private val _mode = MutableStateFlow(PttMode.PUSH_TO_TALK)
    val mode: StateFlow<PttMode> = _mode.asStateFlow()

    private val _transmitState = MutableStateFlow(TransmitState.IDLE)
    val transmitState: StateFlow<TransmitState> = _transmitState.asStateFlow()

    val shouldCaptureMic: Boolean
        get() = _transmitState.value == TransmitState.TRANSMITTING

    fun setMode(mode: PttMode) {
        _mode.value = mode
        _transmitState.value = if (mode == PttMode.PHONE_MODE) TransmitState.TRANSMITTING else TransmitState.IDLE
    }

    fun onPttPressed() {
        if (_mode.value == PttMode.PUSH_TO_TALK) {
            _transmitState.value = TransmitState.TRANSMITTING
        }
    }

    fun onPttReleased() {
        if (_mode.value == PttMode.PUSH_TO_TALK) {
            _transmitState.value = TransmitState.IDLE
        }
    }

    /** Called whenever frames do/don't arrive from the peer, to drive the RECEIVING
     * indicator without ever overriding our own TRANSMITTING state (half-duplex). */
    fun onPeerTransmitting(isTransmitting: Boolean) {
        if (_transmitState.value == TransmitState.TRANSMITTING) return
        _transmitState.value = if (isTransmitting) TransmitState.RECEIVING else TransmitState.IDLE
    }
}
