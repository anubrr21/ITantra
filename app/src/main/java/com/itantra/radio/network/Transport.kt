package com.itantra.radio.network

import kotlinx.coroutines.flow.Flow

interface Transport {
    val state: Flow<TransportState>

    fun startDiscovery()
    fun stopDiscovery()
    fun becomeHost()
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
