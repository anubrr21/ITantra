package com.itantra.radio.lang

import java.util.Locale

enum class SupportedLanguage(
    val displayName: String,
    val code: String,
    val ttsLocale: Locale,
    val voskAssetFolder: String,
) {
    ENGLISH("English", "en", Locale.US, "model-en-us"),
    HINDI("Hindi", "hi", Locale("hi", "IN"), "model-hi");

    companion object {
        fun fromCode(code: String?): SupportedLanguage? = entries.firstOrNull { it.code == code }
    }
}
