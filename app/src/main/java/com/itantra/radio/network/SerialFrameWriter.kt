package com.itantra.radio.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream

class SerialFrameWriter(
    scope: CoroutineScope,
    private val output: OutputStream,
    private val onFailure: (IOException) -> Unit,
) {
    private val queue = Channel<ByteArray>(capacity = QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val job: Job = scope.launch(Dispatchers.IO) {
        try {
            for (frame in queue) FrameCodec.writeFrame(output, frame)
        } catch (e: IOException) {
            onFailure(e)
        }
    }

    fun send(frame: ByteArray) {
        queue.trySend(frame)
    }

    fun close() {
        queue.close()
        job.cancel()
    }

    private companion object {
        const val QUEUE_CAPACITY = 256
    }
}
