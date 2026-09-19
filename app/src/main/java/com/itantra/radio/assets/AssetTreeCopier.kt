package com.itantra.radio.assets

import android.content.Context
import java.io.File

object AssetTreeCopier {
    fun copy(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        target.mkdirs()
        for (child in children) {
            val childAsset = "$assetPath/$child"
            val childTarget = File(target, child)
            val grandChildren = context.assets.list(childAsset).orEmpty()
            if (grandChildren.isNotEmpty()) {
                copy(context, childAsset, childTarget)
            } else {
                val partial = File(target, "$child.partial")
                context.assets.open(childAsset).use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                childTarget.delete()
                check(partial.renameTo(childTarget)) { "could not finalise $childAsset" }
            }
        }
    }
}
