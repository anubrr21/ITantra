package com.itantra.radio.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "WifiDirectTransport"
private const val PORT = 8988
private const val CONNECT_TIMEOUT_MS = 10_000

@SuppressLint("MissingPermission")
class WifiDirectTransport(
    private val context: Context,
    private val scope: CoroutineScope,
) : Transport {

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = manager.initialize(context, context.mainLooper, null)

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val incoming = Channel<ByteArray>(capacity = 64)
    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var discoveredDevices: Map<String, WifiP2pDevice> = emptyMap()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(channel) { peers ->
                        discoveredDevices = peers.deviceList.associateBy { it.deviceAddress }
                        _state.value = TransportState.PeersFound(
                            peers.deviceList.map { TransportPeer(it.deviceAddress, it.deviceName) },
                        )
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager.requestConnectionInfo(channel) { info ->
                        if (info.groupFormed && socket == null) {
                            scope.launch(Dispatchers.IO) {
                                if (info.isGroupOwner) runAsHost() else runAsClient(info.groupOwnerAddress.hostAddress!!)
                            }
                        }
                    }
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    override fun startDiscovery() {
        register()
        _state.value = TransportState.Discovering
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Peer discovery started") }
            override fun onFailure(reason: Int) {
                _state.value = TransportState.Failed("WiFi Direct discovery failed: $reason")
            }
        })
    }

    override fun stopDiscovery() {
        manager.stopPeerDiscovery(channel, null)
    }

    override fun becomeHost() {
        register()
    }

    override fun connectTo(peer: TransportPeer) {
        val device = discoveredDevices[peer.id] ?: run {
            _state.value = TransportState.Failed("Unknown peer ${peer.id}")
            return
        }
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        _state.value = TransportState.Connecting
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Connect requested") }
            override fun onFailure(reason: Int) {
                _state.value = TransportState.Failed("WiFi Direct connect failed: $reason")
            }
        })
    }

    private fun runAsHost() {
        try {
            val server = ServerSocket(PORT)
            serverSocket = server
            val client = server.accept()
            socket = client
            _state.value = TransportState.Connected(
                TransportPeer(client.inetAddress.hostAddress ?: "peer", "Peer"),
            )
            listenLoop(client)
        } catch (e: IOException) {
            _state.value = TransportState.Failed("WiFi Direct host socket error: ${e.message}")
        }
    }

    private fun runAsClient(hostAddress: String) {
        try {
            val client = Socket()
            client.connect(InetSocketAddress(hostAddress, PORT), CONNECT_TIMEOUT_MS)
            socket = client
            _state.value = TransportState.Connected(TransportPeer(hostAddress, "Host"))
            listenLoop(client)
        } catch (e: IOException) {
            _state.value = TransportState.Failed("WiFi Direct client socket error: ${e.message}")
        }
    }

    private fun listenLoop(client: Socket) {
        val input = client.getInputStream()
        while (!client.isClosed) {
            val frame = FrameCodec.readFrame(input) ?: break
            incoming.trySend(frame)
        }
    }

    override fun send(frame: ByteArray) {
        val client = socket ?: return
        scope.launch(Dispatchers.IO) {
            try {
                FrameCodec.writeFrame(client.getOutputStream(), frame)
            } catch (e: IOException) {
                Log.e(TAG, "send failed", e)
            }
        }
    }

    override fun incomingFrames(): Flow<ByteArray> = incoming.receiveAsFlow()

    override fun disconnect() {
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        socket = null
        serverSocket = null
        runCatching { manager.removeGroup(channel, null) }
        unregister()
        _state.value = TransportState.Idle
    }
}
