package com.astral.typer.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import com.astral.typer.models.TextLayer

object StyleManager {
    private val savedStyles = mutableListOf<TextLayer>()
    var clipboardStyle: TextLayer? = null

    // Previews Cache (optional, but good for performance)
    private val stylePreviews = mutableMapOf<TextLayer, Bitmap>()

    fun copyStyle(layer: TextLayer) {
        clipboardStyle = layer.clone() as TextLayer
    }

    fun saveStyle(layer: TextLayer) {
        val newStyle = layer.clone() as TextLayer
        // Reset position properties to make it generic
        newStyle.text = SpannableStringBuilder("Abc")
        newStyle.boxWidth = null
        savedStyles.add(newStyle)
    }

    fun getSavedStyles(): List<TextLayer> {
        return savedStyles
    }

    fun getPreview(layer: TextLayer): Bitmap {
        if (stylePreviews.containsKey(layer)) {
            return stylePreviews[layer]!!
        }

        // Generate Preview
        val w = 150
        val h = 150
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Draw checkered background? Or just dark gray
        canvas.drawColor(Color.DKGRAY)

        val previewLayer = layer.clone() as TextLayer
        previewLayer.text = SpannableStringBuilder("Abc")
        previewLayer.fontSize = 60f
        previewLayer.x = w/2f
        previewLayer.y = h/2f
        previewLayer.rotation = 0f
        previewLayer.scaleX = 1f
        previewLayer.scaleY = 1f

        // Disable perspective for preview simplicity
        previewLayer.isPerspective = false

        previewLayer.draw(canvas)

        stylePreviews[layer] = bmp
        return bmp
    }
}
