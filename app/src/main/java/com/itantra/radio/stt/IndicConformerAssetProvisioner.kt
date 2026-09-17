package com.itantra.radio.stt

import android.content.Context
import java.io.File

object IndicConformerAssetProvisioner {
    private val ASSET_FILES = listOf(
        "preprocessor.onnx",
        "encoder.onnx",
        "ctc_decoder.onnx",
        "vocab.json",
        "language_masks.json",
    )

    fun ensureFiles(context: Context): Map<String, File> {
        val outputDir = File(context.filesDir, "indic_conformer").apply { mkdirs() }
        val result = mutableMapOf<String, File>()
        for (name in ASSET_FILES) {
            val outFile = File(outputDir, name)
            if (!outFile.exists() || outFile.length() == 0L) {
                context.assets.open("indic_conformer/$name").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            result[name] = outFile
        }
        return result
    }
}
