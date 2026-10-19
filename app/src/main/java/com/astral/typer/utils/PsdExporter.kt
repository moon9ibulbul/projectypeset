package com.astral.typer.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.astral.typer.views.AstralCanvasView
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

object PsdExporter {

    data class PsdLayerMeta(
        val name: String,
        val opacity: Int,
        val isVisible: Boolean,
        val blendMode: String,
        val isClipped: Boolean = false,
        val renderAction: (Canvas) -> Unit
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

            val layersMeta = mutableListOf<PsdLayerMeta>()

            // 1. Background Layer Metadata
            layersMeta.add(
                PsdLayerMeta(
                    name = "Background",
                    opacity = 255,
                    isVisible = true,
                    blendMode = "NORMAL",
                    isClipped = false,
                    renderAction = { canvas ->
                        val bgPaint = Paint().apply {
                            color = canvasView.canvasColor
                            style = Paint.Style.FILL
                        }
                        canvas.drawRect(0f, 0f, canvasView.canvasWidth.toFloat(), canvasView.canvasHeight.toFloat(), bgPaint)

                        val bgImage = canvasView.getBackgroundImage()
                        if (bgImage != null) {
                            val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
                            canvas.drawBitmap(
                                bgImage,
                                null,
                                android.graphics.RectF(0f, 0f, canvasView.canvasWidth.toFloat(), canvasView.canvasHeight.toFloat()),
                                tilePaint
                            )
                        }
                    }
                )
            )

            // 2. Canvas Layers Metadata
            val layers = canvasView.getLayers()
            for ((idx, layer) in layers.withIndex()) {
                val layerName = if (!layer.name.isNullOrBlank()) layer.name else "Layer ${idx + 1}"
                val opacityVal = layer.opacity.coerceIn(0, 255)

                layersMeta.add(
                    PsdLayerMeta(
                        name = layerName,
                        opacity = opacityVal,
                        isVisible = layer.isVisible,
                        blendMode = layer.blendMode,
                        isClipped = layer.isClipped,
                        renderAction = { canvas ->
                            layer.draw(canvas)
                        }
                    )
                )
            }

            // Write PSD binary data via BufferedOutputStream directly
            val bufferedStream = BufferedOutputStream(outputStream, 64 * 1024)
            writePsdDataStreaming(canvasView, canvasW, canvasH, scale, layersMeta, bufferedStream)
            bufferedStream.flush()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun writePsdDataStreaming(
        canvasView: AstralCanvasView,
        width: Int,
        height: Int,
        scale: Float,
        layers: List<PsdLayerMeta>,
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
        val pixelCount = width * height
        val channelDataLen = 2 + pixelCount // 2 bytes format (0) + pixel bytes

        // Pre-calculate Layer Records and Subsection Lengths
        val recordLengths = IntArray(layers.size)
        var totalRecordsLen = 0

        for ((i, layer) in layers.withIndex()) {
            val nameBytes = layer.name.toByteArray(Charsets.US_ASCII)
            val nameLen = nameBytes.size.coerceAtMost(255)
            val pascalLen = 1 + nameLen
            val paddedPascalLen = ((pascalLen + 3) / 4) * 4

            // Bounds (16) + Chans (2) + 4 Chans Meta (24) + Blend Sig (4) + Blend Key (4) + Opacity (1) + Clip (1) + Flags (1) + Filler (1) + ExtraLen (4) + MaskLen (4) + BlendRangeLen (4) + PaddedName
            val recLen = 66 + paddedPascalLen
            recordLengths[i] = recLen
            totalRecordsLen += recLen
        }

        val totalChannelDataLenAllLayers = layers.size * (4 * channelDataLen)
        val layerInfoSubsectionLen = 2 + totalRecordsLen + totalChannelDataLenAllLayers
        val section4Len = 4 + layerInfoSubsectionLen

        // Write Section 4 Header
        dos.writeInt(section4Len)
        dos.writeInt(layerInfoSubsectionLen)

        // Layer Count (Int16)
        dos.writeShort(layers.size)

        // Write Layer Records
        for (layer in layers) {
            dos.writeInt(0)      // Top
            dos.writeInt(0)      // Left
            dos.writeInt(height) // Bottom
            dos.writeInt(width)  // Right
            dos.writeShort(4)    // 4 channels (-1, 0, 1, 2)

            // Channel 0: Alpha (-1)
            dos.writeShort(-1)
            dos.writeInt(channelDataLen)

            // Channel 1: Red (0)
            dos.writeShort(0)
            dos.writeInt(channelDataLen)

            // Channel 2: Green (1)
            dos.writeShort(1)
            dos.writeInt(channelDataLen)

            // Channel 3: Blue (2)
            dos.writeShort(2)
            dos.writeInt(channelDataLen)

            // Blend Mode
            dos.writeBytes("8BIM")
            dos.writeBytes(mapBlendModeKey(layer.blendMode))

            dos.writeByte(layer.opacity) // Opacity
            dos.writeByte(if (layer.isClipped) 1 else 0) // Clipping
            dos.writeByte(if (layer.isVisible) 0x00 else 0x02) // Flags (bit 1 = hidden)
            dos.writeByte(0) // Filler

            // Extra Data
            val nameBytes = layer.name.toByteArray(Charsets.US_ASCII)
            val nameLen = nameBytes.size.coerceAtMost(255)
            val pascalLen = 1 + nameLen
            val paddedPascalLen = ((pascalLen + 3) / 4) * 4

            val extraLen = 4 + 4 + paddedPascalLen
            dos.writeInt(extraLen)

            dos.writeInt(0) // Mask Data Length = 0
            dos.writeInt(0) // Blending Ranges Length = 0

            // Pascal string for Layer Name
            dos.writeByte(nameLen)
            dos.write(nameBytes, 0, nameLen)
            val padBytes = paddedPascalLen - pascalLen
            if (padBytes > 0) {
                dos.write(ByteArray(padBytes))
            }
        }

        // Stream Channel Image Data for each layer (render 1 bitmap at a time, stream row by row)
        val rowPixels = IntArray(width)
        val rowA = ByteArray(width)
        val rowR = ByteArray(width)
        val rowG = ByteArray(width)
        val rowB = ByteArray(width)

        for (layer in layers) {
            val layerBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val layerCanvas = Canvas(layerBmp)
            layerCanvas.scale(scale, scale)

            // Render layer
            layer.renderAction(layerCanvas)

            // Write Alpha (-1)
            dos.writeShort(0) // Format 0 = Raw
            for (y in 0 until height) {
                layerBmp.getPixels(rowPixels, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    rowA[x] = ((rowPixels[x] shr 24) and 0xFF).toByte()
                }
                dos.write(rowA)
            }

            // Write Red (0)
            dos.writeShort(0)
            for (y in 0 until height) {
                layerBmp.getPixels(rowPixels, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    rowR[x] = ((rowPixels[x] shr 16) and 0xFF).toByte()
                }
                dos.write(rowR)
            }

            // Write Green (1)
            dos.writeShort(0)
            for (y in 0 until height) {
                layerBmp.getPixels(rowPixels, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    rowG[x] = ((rowPixels[x] shr 8) and 0xFF).toByte()
                }
                dos.write(rowG)
            }

            // Write Blue (2)
            dos.writeShort(0)
            for (y in 0 until height) {
                layerBmp.getPixels(rowPixels, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    rowB[x] = (rowPixels[x] and 0xFF).toByte()
                }
                dos.write(rowB)
            }

            // Recycle bitmap immediately before rendering next layer
            layerBmp.recycle()
        }

        // ===== SECTION 5: MERGED IMAGE DATA =====
        dos.writeShort(0) // Compression Format = 0 (Raw)

        val compositeBmp = canvasView.renderToBitmap(scale)

        // Red channel
        for (y in 0 until height) {
            compositeBmp.getPixels(rowPixels, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                rowR[x] = ((rowPixels[x] shr 16) and 0xFF).toByte()
            }
            dos.write(rowR)
        }

        // Green channel
        for (y in 0 until height) {
            compositeBmp.getPixels(rowPixels, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                rowG[x] = ((rowPixels[x] shr 8) and 0xFF).toByte()
            }
            dos.write(rowG)
        }

        // Blue channel
        for (y in 0 until height) {
            compositeBmp.getPixels(rowPixels, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                rowB[x] = (rowPixels[x] and 0xFF).toByte()
            }
            dos.write(rowB)
        }

        // Alpha channel
        for (y in 0 until height) {
            compositeBmp.getPixels(rowPixels, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                rowA[x] = ((rowPixels[x] shr 24) and 0xFF).toByte()
            }
            dos.write(rowA)
        }

        compositeBmp.recycle()
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
