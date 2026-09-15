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

/**
 * Shared audio constants for the whole app. 16kHz mono PCM16 is the standard input rate
 * for the speech models we'll integrate in later phases, so raw audio (Phase 1) and STT
 * (Phase 2+) both run through the same format end to end.
 */
object AudioConfig {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** 20ms frames: a standard speech-processing chunk size (320 samples @ 16kHz). */
    const val FRAME_DURATION_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1000
    const val FRAME_BYTES = FRAME_SAMPLES * 2
}

/**
 * Captures the mic as a cold Flow of fixed-size 20ms PCM16 frames. Cold by design: the
 * flow only records while someone is collecting it, which is exactly the push-to-talk
 * behaviour we want (see PttController).
 */
class AudioCapturer {

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested in MainActivity before this runs
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

/** Streams PCM16 frames straight to the speaker as they arrive. */
class AudioPlayer {
    private var audioTrack: AudioTrack? = null

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
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            .setBufferSizeInBytes(maxOf(minBuffer, AudioConfig.FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    fun playFrame(frame: ByteArray) {
        audioTrack?.write(frame, 0, frame.size)
    }

    fun stop() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
