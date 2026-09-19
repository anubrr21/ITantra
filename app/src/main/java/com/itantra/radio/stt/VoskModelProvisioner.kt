package com.itantra.radio.stt

import android.content.Context
import com.itantra.radio.assets.AssetTreeCopier
import org.vosk.Model
import java.io.File

object VoskModelProvisioner {
    private const val COMPLETE_MARKER = ".complete"
    private const val MODEL_VERSION = "v1"

    fun unpack(context: Context, assetFolder: String, onReady: (Model) -> Unit, onError: (Exception) -> Unit) {
        val model = try {
            Model(ensureFiles(context, assetFolder).absolutePath)
        } catch (e: Exception) {
            onError(e)
            return
        }
        onReady(model)
    }

    fun ensureFiles(context: Context, assetFolder: String): File {
        val target = File(context.filesDir, "vosk/$assetFolder")
        val marker = File(target, COMPLETE_MARKER)
        val upToDate = marker.exists() && marker.readText() == MODEL_VERSION
        if (!upToDate) {
            target.deleteRecursively()
            AssetTreeCopier.copy(context, assetFolder, target)
            marker.writeText(MODEL_VERSION)
        }
        return target
    }
}
