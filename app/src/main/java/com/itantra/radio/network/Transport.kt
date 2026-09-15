package com.itantra.radio.network

import kotlinx.coroutines.flow.Flow

/**
 * Common abstraction over the two radio links the problem statement requires: WiFi
 * Direct and Bluetooth Classic. Everything above this layer (PTT, audio, and later
 * STT/TTS) talks only to this interface and never needs to know which radio is active.
 */
interface Transport {
    val state: Flow<TransportState>

    /** WiFi Direct: scan for peers. Bluetooth: list already-bonded devices. */
    fun startDiscovery()
    fun stopDiscovery()

    /** Become the listening side (WiFi Direct group owner / Bluetooth RFCOMM server). */
    fun becomeHost()

    /** Connect out to a peer found via [startDiscovery]. */
    fun connectTo(peer: TransportPeer)

    fun send(frame: ByteArray)
    fun incomingFrames(): Flow<ByteArray>
    fun disconnect()
}

data class TransportPeer(val id: String, val displayName: String)

sealed interface TransportState {
    data object Idle : TransportState
    data object Discovering : TransportState
    data class PeersFound(val peers: List<TransportPeer>) : TransportState
    data object Connecting : TransportState
    data class Connected(val peer: TransportPeer) : TransportState
    data class Failed(val reason: String) : TransportState
}
