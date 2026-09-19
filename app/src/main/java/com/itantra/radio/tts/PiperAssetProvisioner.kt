package com.itantra.radio.tts

import android.content.Context
import com.itantra.radio.assets.AssetTreeCopier
import java.io.File

object PiperAssetProvisioner {
    private const val ASSET_ROOT = "piper_tts"
    private const val COMPLETE_MARKER = ".complete"
    private const val ASSET_VERSION = "pratham-medium-v2"
    private val REQUIRED = listOf("model.onnx", "tokens.txt", "espeak-ng-data")

    class Files(val model: File, val tokens: File, val espeakData: File)

    fun isBundled(context: Context, languageCode: String): Boolean {
        val bundled = context.assets.list("$ASSET_ROOT/$languageCode")?.toSet() ?: return false
        return bundled.containsAll(REQUIRED)
    }

    fun ensureFiles(context: Context, languageCode: String): Files {
        val outputDir = File(context.filesDir, "$ASSET_ROOT/$languageCode").apply { mkdirs() }
        val marker = File(outputDir, COMPLETE_MARKER)
        val files = Files(
            model = File(outputDir, "model.onnx"),
            tokens = File(outputDir, "tokens.txt"),
            espeakData = File(outputDir, "espeak-ng-data"),
        )

        val upToDate = marker.exists() && marker.readText() == ASSET_VERSION &&
            files.model.length() > 0L && files.tokens.length() > 0L && files.espeakData.isDirectory
        if (!upToDate) {
            marker.delete()
            files.espeakData.deleteRecursively()
            AssetTreeCopier.copy(context, "$ASSET_ROOT/$languageCode", outputDir)
            marker.writeText(ASSET_VERSION)
        }
        return files
    }
}
