package com.itantra.radio.tts

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class AndroidSystemTtsEngine(
    context: Context,
    private val locale: Locale,
    override val languageCode: String,
) : TtsEngine {

    private var ready = false
    private val volumeGuard = AlertVolumeGuard(context)
    private val activeAlertIds = HashSet<String>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = locale
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finishAlert(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finishAlert(utteranceId)
        })
    }

    override fun speak(text: String, isAlert: Boolean) {
        if (!ready || text.isBlank()) return
        val utteranceId = UUID.randomUUID().toString()
        if (!isAlert) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            return
        }
        val alertAlreadyPlaying = synchronized(activeAlertIds) {
            val playing = activeAlertIds.isNotEmpty()
            activeAlertIds.add(utteranceId)
            playing
        }
        volumeGuard.raiseToMax()
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
        val queueMode = if (alertAlreadyPlaying) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        tts.speak(text, queueMode, params, utteranceId)
    }

    override fun stop() {
        tts.stop()
        tts.shutdown()
        synchronized(activeAlertIds) { activeAlertIds.clear() }
        volumeGuard.restore()
    }

    private fun finishAlert(utteranceId: String?) {
        if (utteranceId == null) return
        val noAlertsLeft = synchronized(activeAlertIds) {
            activeAlertIds.remove(utteranceId) && activeAlertIds.isEmpty()
        }
        if (noAlertsLeft) volumeGuard.restore()
    }
}
