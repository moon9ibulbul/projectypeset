package com.astral.typer.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.astral.typer.models.Layer
import com.astral.typer.views.AstralCanvasView
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

object PsdExporter {

    data class PsdLayerInfo(
        val name: String,
        val bitmap: Bitmap?,
        val opacity: Int,
        val isVisible: Boolean,
        val blendMode: String,
        val isClipped: Boolean = false
    )

    fun exportToPsd(
        canvasView: AstralCanvasView,
        outputStream: OutputStream,
        scale: Float = 1f
    ): Boolean {
        return try {
            val canvasW = (canvasView.canvasWidth * scale).toInt()
            val canvasH = (canvasView.canvasHeight * scale).toInt()

            if (canvasW <= 0 || canvasH <= 0) return false

            val psdLayers = mutableListOf<PsdLayerInfo>()

            // 1. Background Layer
            val bgBmp = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
            val bgCanvas = Canvas(bgBmp)
            bgCanvas.scale(scale, scale)

            val bgPaint = Paint().apply {
                color = canvasView.canvasColor
                style = Paint.Style.FILL
            }
            bgCanvas.drawRect(0f, 0f, canvasView.canvasWidth.toFloat(), canvasView.canvasHeight.toFloat(), bgPaint)

            val bgImage = canvasView.getBackgroundImage()
            if (bgImage != null) {
                val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
                bgCanvas.drawBitmap(
                    bgImage,
                    null,
                    android.graphics.RectF(0f, 0f, canvasView.canvasWidth.toFloat(), canvasView.canvasHeight.toFloat()),
                    tilePaint
                )
            }

            psdLayers.add(
                PsdLayerInfo(
                    name = "Background",
                    bitmap = bgBmp,
                    opacity = 255,
                    isVisible = true,
                    blendMode = "NORMAL",
                    isClipped = false
                )
            )

            // 2. Canvas Layers
            val layers = canvasView.getLayers()
            for ((idx, layer) in layers.withIndex()) {
                val layerBmp = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
                val layerCanvas = Canvas(layerBmp)
                layerCanvas.scale(scale, scale)

                // Render layer directly
                layer.draw(layerCanvas)

                val layerName = if (!layer.name.isNullOrBlank()) layer.name else "Layer ${idx + 1}"
                val opacityVal = layer.opacity.coerceIn(0, 255)

                psdLayers.add(
                    PsdLayerInfo(
                        name = layerName,
                        bitmap = layerBmp,
                        opacity = opacityVal,
                        isVisible = layer.isVisible,
                        blendMode = layer.blendMode,
                        isClipped = layer.isClipped
                    )
                )
            }

            // 3. Composite Bitmap for Merged Image Section
            val compositeBmp = canvasView.renderToBitmap(scale)

            // Write PSD binary data
            writePsdData(canvasW, canvasH, psdLayers, compositeBmp, outputStream)

            // Clean up temporary bitmaps
            for (l in psdLayers) {
                l.bitmap?.recycle()
            }
            compositeBmp.recycle()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun writePsdData(
        width: Int,
        height: Int,
        layers: List<PsdLayerInfo>,
        compositeBmp: Bitmap,
        outStream: OutputStream
    ) {
        val dos = DataOutputStream(outStream)

        // ===== SECTION 1: HEADER (26 bytes) =====
        dos.writeBytes("8BPS") // Signature
        dos.writeShort(1)     // Version = 1
        dos.write(ByteArray(6)) // Reserved 6 bytes
        dos.writeShort(4)     // Channels = 4 (R, G, B, A)
        dos.writeInt(height)  // Height
        dos.writeInt(width)   // Width
        dos.writeShort(8)     // Depth = 8 bits per channel
        dos.writeShort(3)     // ColorMode = 3 (RGB)

        // ===== SECTION 2: COLOR MODE DATA (4 bytes) =====
        dos.writeInt(0) // Length = 0

        // ===== SECTION 3: IMAGE RESOURCES (4 bytes) =====
        dos.writeInt(0) // Length = 0

        // ===== SECTION 4: LAYER AND MASK INFORMATION =====
        val layerAndMaskStream = ByteArrayOutputStream()
        val layerAndMaskDos = DataOutputStream(layerAndMaskStream)

        val layerInfoStream = ByteArrayOutputStream()
        val layerInfoDos = DataOutputStream(layerInfoStream)

        // Layer Count (Int16)
        layerInfoDos.writeShort(layers.size)

        val pixelCount = width * height
        val channelDataLen = 2 + pixelCount // 2 bytes format (0) + pixel bytes

        // Write Layer Records
        for (layer in layers) {
            layerInfoDos.writeInt(0)      // Top
            layerInfoDos.writeInt(0)      // Left
            layerInfoDos.writeInt(height) // Bottom
            layerInfoDos.writeInt(width)  // Right
            layerInfoDos.writeShort(4)    // 4 channels (-1, 0, 1, 2)

            // Channel 0: Alpha (-1)
            layerInfoDos.writeShort(-1)
            layerInfoDos.writeInt(channelDataLen)

            // Channel 1: Red (0)
            layerInfoDos.writeShort(0)
            layerInfoDos.writeInt(channelDataLen)

            // Channel 2: Green (1)
            layerInfoDos.writeShort(1)
            layerInfoDos.writeInt(channelDataLen)

            // Channel 3: Blue (2)
            layerInfoDos.writeShort(2)
            layerInfoDos.writeInt(channelDataLen)

            // Blend Mode
            layerInfoDos.writeBytes("8BIM")
            layerInfoDos.writeBytes(mapBlendModeKey(layer.blendMode))

            layerInfoDos.writeByte(layer.opacity) // Opacity
            layerInfoDos.writeByte(if (layer.isClipped) 1 else 0) // Clipping
            layerInfoDos.writeByte(if (layer.isVisible) 0x00 else 0x02) // Flags (bit 1 = hidden)
            layerInfoDos.writeByte(0) // Filler

            // Extra Data
            val nameBytes = layer.name.toByteArray(Charsets.US_ASCII)
            val nameLen = nameBytes.size.coerceAtMost(255)
            val pascalLen = 1 + nameLen
            val paddedPascalLen = ((pascalLen + 3) / 4) * 4

            val extraLen = 4 + 4 + paddedPascalLen
            layerInfoDos.writeInt(extraLen)

            layerInfoDos.writeInt(0) // Mask Data Length = 0
            layerInfoDos.writeInt(0) // Blending Ranges Length = 0

            // Pascal string for Layer Name
            layerInfoDos.writeByte(nameLen)
            layerInfoDos.write(nameBytes, 0, nameLen)
            val padBytes = paddedPascalLen - pascalLen
            if (padBytes > 0) {
                layerInfoDos.write(ByteArray(padBytes))
            }
        }

        // Write Channel Image Data for each layer
        val pixels = IntArray(pixelCount)
        val rBytes = ByteArray(pixelCount)
        val gBytes = ByteArray(pixelCount)
        val bBytes = ByteArray(pixelCount)
        val aBytes = ByteArray(pixelCount)

        for (layer in layers) {
            if (layer.bitmap != null) {
                layer.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            } else {
                pixels.fill(0)
            }

            for (i in 0 until pixelCount) {
                val p = pixels[i]
                aBytes[i] = ((p shr 24) and 0xFF).toByte()
                rBytes[i] = ((p shr 16) and 0xFF).toByte()
                gBytes[i] = ((p shr 8) and 0xFF).toByte()
                bBytes[i] = (p and 0xFF).toByte()
            }

            // Write Alpha (-1)
            layerInfoDos.writeShort(0) // Format 0 = Raw
            layerInfoDos.write(aBytes)

            // Write Red (0)
            layerInfoDos.writeShort(0)
            layerInfoDos.write(rBytes)

            // Write Green (1)
            layerInfoDos.writeShort(0)
            layerInfoDos.write(gBytes)

            // Write Blue (2)
            layerInfoDos.writeShort(0)
            layerInfoDos.write(bBytes)
        }

        layerInfoDos.flush()
        val layerInfoBytes = layerInfoStream.toByteArray()

        // Write Layer Info Subsection Length (must be even length)
        layerAndMaskDos.writeInt(layerInfoBytes.size)
        layerAndMaskDos.write(layerInfoBytes)

        layerAndMaskDos.flush()
        val layerAndMaskBytes = layerAndMaskStream.toByteArray()

        // Section 4 Total Length
        dos.writeInt(layerAndMaskBytes.size)
        dos.write(layerAndMaskBytes)

        // ===== SECTION 5: MERGED IMAGE DATA =====
        dos.writeShort(0) // Compression Format = 0 (Raw)

        val compPixels = IntArray(pixelCount)
        compositeBmp.getPixels(compPixels, 0, width, 0, 0, width, height)

        for (i in 0 until pixelCount) {
            val p = compPixels[i]
            rBytes[i] = ((p shr 16) and 0xFF).toByte()
            gBytes[i] = ((p shr 8) and 0xFF).toByte()
            bBytes[i] = (p and 0xFF).toByte()
            aBytes[i] = ((p shr 24) and 0xFF).toByte()
        }

        // Section 5 channel order: Red, Green, Blue, Alpha
        dos.write(rBytes)
        dos.write(gBytes)
        dos.write(bBytes)
        dos.write(aBytes)

        dos.flush()
    }

    private fun mapBlendModeKey(blendMode: String?): String {
        return when (blendMode?.uppercase()) {
            "NORMAL" -> "norm"
            "MULTIPLY" -> "mul "
            "SCREEN" -> "scrn"
            "OVERLAY" -> "over"
            "DARKEN" -> "dark"
            "LIGHTEN" -> "lite"
            "ADD" -> "add "
            else -> "norm"
        }
    }
}
