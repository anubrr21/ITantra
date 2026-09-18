package com.itantra.radio.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class ClipPlayer(private val sampleRateHz: Int) {

    fun play(clip: ShortArray, isAlert: Boolean, shouldAbort: () -> Boolean): Boolean {
        if (clip.isEmpty()) return true
        val attributes = AudioAttributes.Builder()
            .setUsage(if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(clip.size * BYTES_PER_SAMPLE)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(clip, 0, clip.size)
            track.setVolume(1f)
            track.play()
            val deadlineMs = System.currentTimeMillis() + clip.size * 1000L / sampleRateHz + STALL_GRACE_MS
            while (true) {
                if (shouldAbort()) {
                    track.stop()
                    return false
                }
                if (track.playbackHeadPosition >= clip.size) return true
                if (System.currentTimeMillis() > deadlineMs) return true
                Thread.sleep(POLL_INTERVAL_MS)
            }
        } finally {
            track.release()
        }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val POLL_INTERVAL_MS = 15L
        const val STALL_GRACE_MS = 2_000L
    }
}
