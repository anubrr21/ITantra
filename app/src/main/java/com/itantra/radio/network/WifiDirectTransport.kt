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
import java.util.concurrent.atomic.AtomicBoolean

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
    private var writer: SerialFrameWriter? = null
    private val linkStarted = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var discoveredDevices: Map<String, WifiP2pDevice> = emptyMap()
    private var isRegistered = false

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
                        Log.d(TAG, "connection info: groupFormed=${info.groupFormed} owner=${info.isGroupOwner} address=${info.groupOwnerAddress?.hostAddress}")
                        if (info.groupFormed && socket == null && linkStarted.compareAndSet(false, true)) {
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
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        isRegistered = true
    }

    fun unregister() {
        if (!isRegistered) return
        runCatching { context.unregisterReceiver(receiver) }
        isRegistered = false
    }

    override fun startDiscovery() {
        register()
        _state.value = TransportState.Discovering
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Peer discovery started") }
            override fun onFailure(reason: Int) {
                _state.value = TransportState.Failed("WiFi Direct discovery failed: ${describeFailure(reason)}")
            }
        })
    }

    override fun stopDiscovery() {
        manager.stopPeerDiscovery(channel, null)
    }

    override fun becomeHost() {
        register()
        _state.value = TransportState.Discovering
        manager.removeGroup(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = createHostGroup()
                override fun onFailure(reason: Int) = createHostGroup()
            },
        )
    }

    private fun createHostGroup() {
        manager.createGroup(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Host group created, waiting for a peer")
                }

                override fun onFailure(reason: Int) {
                    _state.value = TransportState.Failed("WiFi Direct host failed: ${describeFailure(reason)}")
                }
            },
        )
    }

    override fun connectTo(peer: TransportPeer) {
        val device = discoveredDevices[peer.id] ?: run {
            _state.value = TransportState.Failed("Unknown peer ${peer.id}")
            return
        }
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0
        }
        _state.value = TransportState.Connecting
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "Connect requested") }
            override fun onFailure(reason: Int) {
                _state.value = TransportState.Failed("WiFi Direct connect failed: ${describeFailure(reason)}")
            }
        })
    }

    private fun describeFailure(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "internal error ($reason)"
        WifiP2pManager.P2P_UNSUPPORTED -> "WiFi Direct not supported on this device ($reason)"
        WifiP2pManager.BUSY -> "WiFi Direct radio busy - try again in a moment ($reason)"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests ($reason)"
        else -> "unknown ($reason)"
    }

    private fun runAsHost() {
        try {
            val server = ServerSocket(PORT)
            serverSocket = server
            Log.d(TAG, "host listening on port $PORT")
            val client = server.accept()
            Log.d(TAG, "host accepted a peer")
            socket = client
            openWriter(client)
            _state.value = TransportState.Connected(
                TransportPeer(client.inetAddress.hostAddress ?: "peer", "Peer"),
            )
            listenLoop(client)
        } catch (e: IOException) {
            Log.e(TAG, "host socket error", e)
            linkStarted.set(false)
            _state.value = TransportState.Failed("WiFi Direct host socket error: ${e.message}")
        }
    }

    private fun runAsClient(hostAddress: String) {
        try {
            val client = Socket()
            Log.d(TAG, "client connecting to $hostAddress:$PORT")
            client.connect(InetSocketAddress(hostAddress, PORT), CONNECT_TIMEOUT_MS)
            Log.d(TAG, "client connected")
            socket = client
            openWriter(client)
            _state.value = TransportState.Connected(TransportPeer(hostAddress, "Host"))
            listenLoop(client)
        } catch (e: IOException) {
            Log.e(TAG, "client socket error", e)
            linkStarted.set(false)
            _state.value = TransportState.Failed("WiFi Direct client socket error: ${e.message}")
        }
    }

    private fun openWriter(client: Socket) {
        writer?.close()
        writer = SerialFrameWriter(scope, client.getOutputStream()) { error ->
            Log.e(TAG, "send failed", error)
            reportLinkLost(client, "send failed: ${error.message}")
        }
    }

    private fun listenLoop(client: Socket) {
        val input = client.getInputStream()
        while (!client.isClosed) {
            val frame = FrameCodec.readFrame(input) ?: break
            incoming.trySend(frame)
        }
        reportLinkLost(client, "peer closed the link or sent invalid data")
    }

    private fun reportLinkLost(client: Socket, reason: String) {
        if (socket !== client) return
        Log.w(TAG, "link lost: $reason")
        _state.value = TransportState.Failed("Link to peer lost ($reason)")
    }

    override fun send(frame: ByteArray) {
        writer?.send(frame)
    }

    override fun incomingFrames(): Flow<ByteArray> = incoming.receiveAsFlow()

    override fun disconnect() {
        linkStarted.set(false)
        writer?.close()
        writer = null
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        socket = null
        serverSocket = null
        runCatching { manager.removeGroup(channel, null) }
        unregister()
        _state.value = TransportState.Idle
    }
}
