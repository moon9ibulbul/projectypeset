package com.astral.typer.utils

import android.graphics.Bitmap
import com.astral.typer.models.Layer

object TempCanvasState {
    var layers: List<Layer>? = null
    var background: Bitmap? = null
    var isBackgroundModified: Boolean = true
    var width: Int = 1080
    var height: Int = 1080
    var color: Int = -1
    var projectName: String? = null
    var parentFolder: String? = null

    fun hasState(): Boolean = layers != null

    fun clear() {
        layers = null
        background = null
        isBackgroundModified = true
        projectName = null
        parentFolder = null
    }
}
