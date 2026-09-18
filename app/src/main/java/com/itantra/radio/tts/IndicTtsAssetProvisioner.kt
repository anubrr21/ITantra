package com.itantra.radio.tts

import android.content.Context
import java.io.File

object IndicTtsAssetProvisioner {
    private const val ASSET_ROOT = "indic_tts"
    private const val COMPLETE_MARKER = ".complete"

    val ASSET_FILES = listOf(
        "fastpitch.onnx",
        "fastpitch.onnx.data",
        "hifigan.onnx",
        "hifigan.onnx.data",
        "char_to_id.json",
    )

    fun isBundled(context: Context, languageCode: String): Boolean {
        val bundled = context.assets.list("$ASSET_ROOT/$languageCode")?.toSet() ?: return false
        return bundled.containsAll(ASSET_FILES)
    }

    fun ensureFiles(context: Context, languageCode: String): Map<String, File> {
        val outputDir = File(context.filesDir, "$ASSET_ROOT/$languageCode").apply { mkdirs() }
        val marker = File(outputDir, COMPLETE_MARKER)
        val files = ASSET_FILES.associateWith { File(outputDir, it) }

        val alreadyCopied = marker.exists() && files.values.all { it.exists() && it.length() > 0L }
        if (!alreadyCopied) {
            marker.delete()
            for ((name, target) in files) {
                val partial = File(outputDir, "$name.partial")
                context.assets.open("$ASSET_ROOT/$languageCode/$name").use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                target.delete()
                check(partial.renameTo(target)) { "could not finalise $name" }
            }
            marker.writeText("ok")
        }
        return files
    }
}
