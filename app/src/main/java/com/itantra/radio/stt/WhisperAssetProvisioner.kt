package com.itantra.radio.stt

import android.content.Context
import com.itantra.radio.assets.AssetTreeCopier
import java.io.File

object WhisperAssetProvisioner {
    private const val ASSET_ROOT = "whisper_stt"
    private const val COMPLETE_MARKER = ".complete"
    private const val ASSET_VERSION = "base.en-int8-v1"
    private val REQUIRED = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")

    class Files(val encoder: File, val decoder: File, val tokens: File)

    fun isBundled(context: Context, languageCode: String): Boolean {
        val bundled = context.assets.list("$ASSET_ROOT/$languageCode")?.toSet() ?: return false
        return bundled.containsAll(REQUIRED)
    }

    fun ensureFiles(context: Context, languageCode: String): Files {
        val outputDir = File(context.filesDir, "$ASSET_ROOT/$languageCode").apply { mkdirs() }
        val marker = File(outputDir, COMPLETE_MARKER)
        val files = Files(
            encoder = File(outputDir, "encoder.int8.onnx"),
            decoder = File(outputDir, "decoder.int8.onnx"),
            tokens = File(outputDir, "tokens.txt"),
        )
        val upToDate = marker.exists() && marker.readText() == ASSET_VERSION &&
            files.encoder.length() > 0L && files.decoder.length() > 0L && files.tokens.length() > 0L
        if (!upToDate) {
            marker.delete()
            AssetTreeCopier.copy(context, "$ASSET_ROOT/$languageCode", outputDir)
            marker.writeText(ASSET_VERSION)
        }
        return files
    }
}
