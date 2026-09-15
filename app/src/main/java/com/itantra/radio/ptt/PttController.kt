package com.itantra.radio.ptt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PttMode { PUSH_TO_TALK, PHONE_MODE }

enum class TransmitState { IDLE, TRANSMITTING, RECEIVING }

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

    fun onPeerTransmitting(isTransmitting: Boolean) {
        if (_transmitState.value == TransmitState.TRANSMITTING) return
        _transmitState.value = if (isTransmitting) TransmitState.RECEIVING else TransmitState.IDLE
    }
}
