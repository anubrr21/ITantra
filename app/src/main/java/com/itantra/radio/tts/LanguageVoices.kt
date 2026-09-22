package com.itantra.radio.tts

import android.content.Context
import android.util.Log
import com.itantra.radio.lang.SupportedLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class LanguageVoices(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onVoiceChanged: (SupportedLanguage) -> Unit,
) {
    private val engines = ConcurrentHashMap<SupportedLanguage, TtsEngine>()
    private val labels = ConcurrentHashMap<SupportedLanguage, String>()
    private val upgradeStarted = ConcurrentHashMap.newKeySet<SupportedLanguage>()

    init {
        for (language in SupportedLanguage.entries) {
            engines[language] = AndroidSystemTtsEngine(context, language.ttsLocale, language.code)
            labels[language] = SYSTEM_LABEL
        }
    }

    @Volatile
    private var lastCheckAtMs = 0L

    @Volatile
    private var lastSpeakingAtMs = 0L

    @Volatile
    private var lastResult = false

    fun isPlaying(): Boolean = engines.values.any { runCatching { it.isSpeaking }.getOrDefault(false) }

    fun isSpeaking(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCheckAtMs < CHECK_INTERVAL_MS) return lastResult
        lastCheckAtMs = now
        if (engines.values.any { runCatching { it.isSpeaking }.getOrDefault(false) }) lastSpeakingAtMs = now
        lastResult = lastSpeakingAtMs != 0L && now - lastSpeakingAtMs < ECHO_TAIL_MS
        return lastResult
    }

    fun label(language: SupportedLanguage): String = labels[language] ?: SYSTEM_LABEL

    fun prepare(language: SupportedLanguage) {
        if (!upgradeStarted.add(language)) return
        scope.launch(Dispatchers.IO) {
            val neural = buildNeural(language)
            if (neural == null) {
                onVoiceChanged(language)
                return@launch
            }
            val previous = engines.put(language, neural)
            labels[language] = if (neural is PiperTtsEngine) "neural (Piper)" else "neural (FastPitch)"
            previous?.stop()
            onVoiceChanged(language)
        }
    }

    fun speak(language: SupportedLanguage, text: String, isAlert: Boolean) {
        prepare(language)
        engines[language]?.speak(text, isAlert)
    }

    fun stopAll() {
        for (engine in engines.values) runCatching { engine.stop() }
        engines.clear()
    }

    private fun buildNeural(language: SupportedLanguage): TtsEngine? {
        if (language != SupportedLanguage.HINDI) return null
        if (PiperAssetProvisioner.isBundled(context, language.code)) {
            val piper = runCatching { PiperTtsEngine(context, language.code) }
                .onFailure { Log.e(LOG_TAG, "Piper failed to load", it) }
                .getOrNull()
            if (piper != null) return piper
        }
        if (IndicTtsAssetProvisioner.isBundled(context, language.code)) {
            return runCatching { IndicTtsEngine(context, language.code) }.getOrNull()
        }
        return null
    }

    private companion object {
        const val SYSTEM_LABEL = "system voice"
        const val LOG_TAG = "LanguageVoices"
        const val CHECK_INTERVAL_MS = 100L
        const val ECHO_TAIL_MS = 700L
    }
}
