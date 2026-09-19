package com.itantra.radio.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

object AudioConfig {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    const val FRAME_DURATION_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1000
    const val FRAME_BYTES = FRAME_SAMPLES * 2
}

class AudioCapturer {

    @SuppressLint("MissingPermission")
    fun capture(): Flow<ByteArray> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE_HZ,
            AudioConfig.CHANNEL_CONFIG_IN,
            AudioConfig.ENCODING,
        )
        val bufferSize = maxOf(minBuffer, AudioConfig.FRAME_BYTES * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AudioConfig.SAMPLE_RATE_HZ,
            AudioConfig.CHANNEL_CONFIG_IN,
            AudioConfig.ENCODING,
            bufferSize,
        )
        try {
            record.startRecording()
            val buffer = ByteArray(AudioConfig.FRAME_BYTES)
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    emit(if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read))
                }
            }
        } finally {
            record.stop()
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}

class AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private var playing = false
    private var queuedBytes = 0

    companion object {
        private const val BUFFER_FRAMES = 30
        private const val PREBUFFER_FRAMES = 8
    }

    @Synchronized
    fun start() {
        if (audioTrack != null) return
        val minBuffer = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE_HZ,
            AudioConfig.CHANNEL_CONFIG_OUT,
            AudioConfig.ENCODING,
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioConfig.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioConfig.CHANNEL_CONFIG_OUT)
                    .setEncoding(AudioConfig.ENCODING)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, AudioConfig.FRAME_BYTES * BUFFER_FRAMES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        playing = false
        queuedBytes = 0
    }

    @Synchronized
    fun playFrame(frame: ByteArray) {
        val track = audioTrack ?: return
        track.write(frame, 0, frame.size)
        if (!playing) {
            queuedBytes += frame.size
            if (queuedBytes >= AudioConfig.FRAME_BYTES * PREBUFFER_FRAMES) {
                track.play()
                playing = true
            }
        }
    }

    @Synchronized
    fun endOfBurst() {
        val track = audioTrack ?: return
        if (!playing && queuedBytes > 0) {
            track.play()
            playing = true
        }
        if (playing) {
            track.stop()
            playing = false
        }
        queuedBytes = 0
    }

    @Synchronized
    fun stop() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        playing = false
        queuedBytes = 0
    }
}
