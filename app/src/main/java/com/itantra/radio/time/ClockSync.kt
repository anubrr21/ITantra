package com.itantra.radio.time

import com.itantra.radio.network.RadioFrame
import com.itantra.radio.network.RadioFrameCodec
import com.itantra.radio.network.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class ClockSync {
    private val estimator = ClockSyncEstimator()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Long>>()

    @Volatile
    private var job: Job? = null

    val sampleCount: Int
        get() = estimator.sampleCount

    val offsetMs: Long?
        get() = estimator.bestOffsetMs()

    val bestRttMs: Long?
        get() = estimator.bestRttMs()

    fun toLocalTime(remoteEpochMs: Long): Long? {
        val offset = offsetMs ?: return null
        return remoteEpochMs - offset
    }

    fun toRemoteTime(localEpochMs: Long): Long? {
        val offset = offsetMs ?: return null
        return localEpochMs + offset
    }

    fun start(transport: Transport, scope: CoroutineScope) {
        stop()
        estimator.reset()
        job = scope.launch {
            repeat(MAX_ATTEMPTS) {
                if (estimator.sampleCount >= TARGET_SAMPLES) return@launch
                sendPing(transport)
                delay(PING_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        for (deferred in pending.values) deferred.cancel()
        pending.clear()
    }

    fun handlePong(pong: RadioFrame.ClockSyncPong) {
        pending.remove(pong.originEpochMs)?.complete(pong.replyEpochMs)
    }

    private suspend fun sendPing(transport: Transport) {
        val origin = System.currentTimeMillis()
        val deferred = CompletableDeferred<Long>()
        pending[origin] = deferred
        transport.send(RadioFrameCodec.encode(RadioFrame.ClockSyncPing(origin)))
        val replyEpochMs = withTimeoutOrNull(PING_TIMEOUT_MS) { deferred.await() }
        pending.remove(origin)
        if (replyEpochMs != null) {
            estimator.addSample(origin, replyEpochMs, System.currentTimeMillis())
        }
    }

    private companion object {
        const val TARGET_SAMPLES = 6
        const val MAX_ATTEMPTS = 12
        const val PING_INTERVAL_MS = 250L
        const val PING_TIMEOUT_MS = 800L
    }
}
