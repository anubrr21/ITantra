package com.itantra.radio.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

private const val TAG = "BluetoothClassicTransport"

@SuppressLint("MissingPermission")
class BluetoothClassicTransport(
    context: Context,
    private val scope: CoroutineScope,
) : Transport {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7a1e9f2e-2b7e-4f0a-9c1a-3f2b1c4d5e6f")
        const val SERVICE_NAME = "iTantraRadio"
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val incoming = Channel<ByteArray>(capacity = 64)
    private var socket: BluetoothSocket? = null
    private var writer: SerialFrameWriter? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var bondedDevices: Map<String, BluetoothDevice> = emptyMap()

    override fun startDiscovery() {
        val devices = adapter?.bondedDevices ?: emptySet()
        bondedDevices = devices.associateBy { it.address }
        _state.value = TransportState.PeersFound(
            devices.map { TransportPeer(it.address, it.name ?: it.address) },
        )
    }

    override fun stopDiscovery() {
    }

    override fun becomeHost() {
        scope.launch(Dispatchers.IO) {
            try {
                val server = adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                    ?: run {
                        _state.value = TransportState.Failed("Bluetooth adapter unavailable")
                        return@launch
                    }
                serverSocket = server
                _state.value = TransportState.Discovering
                val client = server.accept()
                socket = client
                openWriter(client)
                runCatching { server.close() }
                serverSocket = null
                _state.value = TransportState.Connected(
                    TransportPeer(client.remoteDevice.address, client.remoteDevice.name ?: "Peer"),
                )
                listenLoop(client)
            } catch (e: IOException) {
                _state.value = TransportState.Failed("Bluetooth host error: ${e.message}")
            }
        }
    }

    override fun connectTo(peer: TransportPeer) {
        val device = bondedDevices[peer.id] ?: run {
            _state.value = TransportState.Failed("Unknown or unpaired device ${peer.id}")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                _state.value = TransportState.Connecting
                adapter?.cancelDiscovery()
                val client = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                client.connect()
                socket = client
                openWriter(client)
                _state.value = TransportState.Connected(peer)
                listenLoop(client)
            } catch (e: IOException) {
                _state.value = TransportState.Failed("Bluetooth connect error: ${e.message}")
            }
        }
    }

    private fun openWriter(client: BluetoothSocket) {
        writer?.close()
        writer = SerialFrameWriter(scope, client.outputStream) { error ->
            Log.e(TAG, "send failed", error)
            reportLinkLost(client, "send failed: ${error.message}")
        }
    }

    private fun listenLoop(client: BluetoothSocket) {
        val input = client.inputStream
        while (client.isConnected) {
            val frame = FrameCodec.readFrame(input) ?: break
            incoming.trySend(frame)
        }
        reportLinkLost(client, "peer closed the link or sent invalid data")
    }

    private fun reportLinkLost(client: BluetoothSocket, reason: String) {
        if (socket !== client) return
        Log.w(TAG, "link lost: $reason")
        _state.value = TransportState.Failed("Link to peer lost ($reason)")
    }

    override fun send(frame: ByteArray) {
        writer?.send(frame)
    }

    override fun incomingFrames(): Flow<ByteArray> = incoming.receiveAsFlow()

    override fun disconnect() {
        writer?.close()
        writer = null
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        socket = null
        serverSocket = null
        _state.value = TransportState.Idle
    }
}
