package com.itantra.radio.stt

import android.content.Context
import org.vosk.Model
import org.vosk.android.StorageService

object VoskModelProvisioner {
    fun unpack(context: Context, assetFolder: String, onReady: (Model) -> Unit, onError: (Exception) -> Unit) {
        StorageService.unpack(context, assetFolder, "model", onReady, onError)
    }
}
