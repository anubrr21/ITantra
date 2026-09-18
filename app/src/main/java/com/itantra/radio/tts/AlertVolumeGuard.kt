package com.itantra.radio.tts

import android.content.Context
import android.media.AudioManager

class AlertVolumeGuard(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedVolume: Int? = null

    @Synchronized
    fun raiseToMax() {
        if (savedVolume != null) return
        savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0,
        )
    }

    @Synchronized
    fun restore() {
        val previous = savedVolume ?: return
        savedVolume = null
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
    }
}
