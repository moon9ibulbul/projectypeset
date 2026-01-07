package com.astral.typer.utils

import com.astral.typer.models.TextLayer

object StyleManager {
    private val savedStyles = mutableListOf<TextLayer>()
    var clipboardStyle: TextLayer? = null

    fun copyStyle(layer: TextLayer) {
        clipboardStyle = layer.clone() as TextLayer
    }

    fun saveStyle(layer: TextLayer) {
        savedStyles.add(layer.clone() as TextLayer)
    }

    fun getSavedStyles(): List<TextLayer> {
        return savedStyles
    }
}
