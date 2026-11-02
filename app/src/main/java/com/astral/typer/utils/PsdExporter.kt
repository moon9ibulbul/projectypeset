package com.astral.typer.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.astral.typer.views.AstralCanvasView
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

object PsdExporter {

    data class CompressedChannel(
        val rowLengths: IntArray,
        val rowData: Array<ByteArray>,
        val totalByteSize: Int
    )

    data class PsdLayerMeta(
        val name: String,
        val opacity: Int,
        val isVisible: Boolean,
        val blendMode: String,
        val isClipped: Boolean = false,
        val renderAction: (Canvas) -> Unit
    )

    data class PreparedLayer(
        val name: String,
        val top: Int,
        val left: Int,
        val bottom: Int,
        val right: Int,
        val opacity: Int,
        val isVisible: Boolean,
        val blendMode: String,
        val isClipped: Boolean,
        val alphaChan: CompressedChannel,
        val redChan: CompressedChannel,
        val greenChan: CompressedChannel,
        val blueChan: CompressedChannel,
        val recordLen: Int
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
            writePsdData(canvasView, canvasW, canvasH, scale, layersMeta, bufferedStream)
            bufferedStream.flush()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun writePsdData(
        canvasView: AstralCanvasView,
        width: Int,
        height: Int,
        scale: Float,
        layersMeta: List<PsdLayerMeta>,
        outStream: OutputStream
    ) {
        val preparedLayers = mutableListOf<PreparedLayer>()

        for (meta in layersMeta) {
            val layerBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val layerCanvas = Canvas(layerBmp)
            layerCanvas.scale(scale, scale)

            meta.renderAction(layerCanvas)

            val bounds = getNonTransparentBounds(layerBmp)

            preparedLayers.add(
                prepareLayer(
                    name = meta.name,
                    fullBmp = layerBmp,
                    bounds = bounds,
                    opacity = meta.opacity,
                    isVisible = meta.isVisible,
                    blendMode = meta.blendMode,
                    isClipped = meta.isClipped
                )
            )
            layerBmp.recycle()
        }

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
        var totalRecordsLen = 0
        var totalChannelDataLenAllLayers = 0

        for (l in preparedLayers) {
            totalRecordsLen += l.recordLen
            totalChannelDataLenAllLayers += l.alphaChan.totalByteSize + l.redChan.totalByteSize + l.greenChan.totalByteSize + l.blueChan.totalByteSize
        }

        var layerInfoSubsectionLen = 2 + totalRecordsLen + totalChannelDataLenAllLayers
        var padByteCount = 0
        if (layerInfoSubsectionLen % 2 != 0) {
            padByteCount = 1
            layerInfoSubsectionLen += 1
        }

        val section4Len = 4 + layerInfoSubsectionLen

        // Write Section 4 Header
        dos.writeInt(section4Len)
        dos.writeInt(layerInfoSubsectionLen)

        // Layer Count (Int16)
        dos.writeShort(preparedLayers.size)

        // Write Layer Records
        for (l in preparedLayers) {
            dos.writeInt(l.top)
            dos.writeInt(l.left)
            dos.writeInt(l.bottom)
            dos.writeInt(l.right)
            dos.writeShort(4) // 4 channels

            // Channel 0: Alpha (-1)
            dos.writeShort(-1)
            dos.writeInt(l.alphaChan.totalByteSize)

            // Channel 1: Red (0)
            dos.writeShort(0)
            dos.writeInt(l.redChan.totalByteSize)

            // Channel 2: Green (1)
            dos.writeShort(1)
            dos.writeInt(l.greenChan.totalByteSize)

            // Channel 3: Blue (2)
            dos.writeShort(2)
            dos.writeInt(l.blueChan.totalByteSize)

            // Blend Mode
            dos.writeBytes("8BIM")
            dos.writeBytes(mapBlendModeKey(l.blendMode))

            dos.writeByte(l.opacity) // Opacity
            dos.writeByte(if (l.isClipped) 1 else 0) // Clipping
            dos.writeByte(if (l.isVisible) 0x00 else 0x02) // Flags (bit 1 = hidden)
            dos.writeByte(0) // Filler

            // Extra Data
            val nameBytes = l.name.toByteArray(Charsets.US_ASCII)
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

        // Stream Channel Image Data for each layer
        for (l in preparedLayers) {
            // Write Alpha (-1)
            writeChannelData(dos, l.alphaChan)
            // Write Red (0)
            writeChannelData(dos, l.redChan)
            // Write Green (1)
            writeChannelData(dos, l.greenChan)
            // Write Blue (2)
            writeChannelData(dos, l.blueChan)
        }

        if (padByteCount > 0) {
            dos.writeByte(0)
        }

        // ===== SECTION 5: MERGED IMAGE DATA =====
        dos.writeShort(1) // Compression Format = 1 (RLE PackBits)

        val compositeBmp = canvasView.renderToBitmap(scale)
        val rowPixels = IntArray(width)
        val rowR = ByteArray(width)
        val rowG = ByteArray(width)
        val rowB = ByteArray(width)
        val rowA = ByteArray(width)

        val compRRowData = Array(height) { ByteArray(0) }
        val compGRowData = Array(height) { ByteArray(0) }
        val compBRowData = Array(height) { ByteArray(0) }
        val compARowData = Array(height) { ByteArray(0) }

        for (y in 0 until height) {
            compositeBmp.getPixels(rowPixels, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val p = rowPixels[x]
                rowR[x] = ((p shr 16) and 0xFF).toByte()
                rowG[x] = ((p shr 8) and 0xFF).toByte()
                rowB[x] = (p and 0xFF).toByte()
                rowA[x] = ((p shr 24) and 0xFF).toByte()
            }
            compRRowData[y] = packBitsEncodeRow(rowR, 0, width)
            compGRowData[y] = packBitsEncodeRow(rowG, 0, width)
            compBRowData[y] = packBitsEncodeRow(rowB, 0, width)
            compARowData[y] = packBitsEncodeRow(rowA, 0, width)
        }
        compositeBmp.recycle()

        // Write composite row byte counts
        for (y in 0 until height) dos.writeShort(compRRowData[y].size)
        for (y in 0 until height) dos.writeShort(compGRowData[y].size)
        for (y in 0 until height) dos.writeShort(compBRowData[y].size)
        for (y in 0 until height) dos.writeShort(compARowData[y].size)

        // Write composite RLE data
        for (y in 0 until height) dos.write(compRRowData[y])
        for (y in 0 until height) dos.write(compGRowData[y])
        for (y in 0 until height) dos.write(compBRowData[y])
        for (y in 0 until height) dos.write(compARowData[y])

        dos.flush()
    }

    private fun prepareLayer(
        name: String,
        fullBmp: Bitmap,
        bounds: Rect,
        opacity: Int,
        isVisible: Boolean,
        blendMode: String,
        isClipped: Boolean
    ): PreparedLayer {
        val cropW = bounds.width().coerceAtLeast(1)
        val cropH = bounds.height().coerceAtLeast(1)

        val croppedBmp = Bitmap.createBitmap(fullBmp, bounds.left, bounds.top, cropW, cropH)

        // Extract RGBA channels
        val pixels = IntArray(cropW)
        val aRow = ByteArray(cropW)
        val rRow = ByteArray(cropW)
        val gRow = ByteArray(cropW)
        val bRow = ByteArray(cropW)

        val aRowLengths = IntArray(cropH)
        val aRowData = Array(cropH) { ByteArray(0) }
        val rRowLengths = IntArray(cropH)
        val rRowData = Array(cropH) { ByteArray(0) }
        val gRowLengths = IntArray(cropH)
        val gRowData = Array(cropH) { ByteArray(0) }
        val bRowLengths = IntArray(cropH)
        val bRowData = Array(cropH) { ByteArray(0) }

        var aByteSum = 0
        var rByteSum = 0
        var gByteSum = 0
        var bByteSum = 0

        for (y in 0 until cropH) {
            croppedBmp.getPixels(pixels, 0, cropW, 0, y, cropW, 1)
            for (x in 0 until cropW) {
                val p = pixels[x]
                aRow[x] = ((p shr 24) and 0xFF).toByte()
                rRow[x] = ((p shr 16) and 0xFF).toByte()
                gRow[x] = ((p shr 8) and 0xFF).toByte()
                bRow[x] = (p and 0xFF).toByte()
            }

            val encA = packBitsEncodeRow(aRow, 0, cropW)
            aRowData[y] = encA
            aRowLengths[y] = encA.size
            aByteSum += encA.size

            val encR = packBitsEncodeRow(rRow, 0, cropW)
            rRowData[y] = encR
            rRowLengths[y] = encR.size
            rByteSum += encR.size

            val encG = packBitsEncodeRow(gRow, 0, cropW)
            gRowData[y] = encG
            gRowLengths[y] = encG.size
            gByteSum += encG.size

            val encB = packBitsEncodeRow(bRow, 0, cropW)
            bRowData[y] = encB
            bRowLengths[y] = encB.size
            bByteSum += encB.size
        }

        croppedBmp.recycle()

        val aChan = CompressedChannel(aRowLengths, aRowData, 2 + (cropH * 2) + aByteSum)
        val rChan = CompressedChannel(rRowLengths, rRowData, 2 + (cropH * 2) + rByteSum)
        val gChan = CompressedChannel(gRowLengths, gRowData, 2 + (cropH * 2) + gByteSum)
        val bChan = CompressedChannel(bRowLengths, bRowData, 2 + (cropH * 2) + bByteSum)

        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val nameLen = nameBytes.size.coerceAtMost(255)
        val pascalLen = 1 + nameLen
        val paddedPascalLen = ((pascalLen + 3) / 4) * 4

        val recLen = 66 + paddedPascalLen

        return PreparedLayer(
            name = name,
            top = bounds.top,
            left = bounds.left,
            bottom = bounds.bottom,
            right = bounds.right,
            opacity = opacity,
            isVisible = isVisible,
            blendMode = blendMode,
            isClipped = isClipped,
            alphaChan = aChan,
            redChan = rChan,
            greenChan = gChan,
            blueChan = bChan,
            recordLen = recLen
        )
    }

    private fun writeChannelData(dos: DataOutputStream, chan: CompressedChannel) {
        dos.writeShort(1) // Format = 1 (RLE)
        val h = chan.rowLengths.size
        for (y in 0 until h) {
            dos.writeShort(chan.rowLengths[y])
        }
        for (y in 0 until h) {
            dos.write(chan.rowData[y])
        }
    }

    fun getNonTransparentBounds(bmp: Bitmap): Rect {
        val w = bmp.width
        val h = bmp.height
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        val pixels = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(pixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val alpha = (pixels[x] ushr 24)
                if (alpha > 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return Rect(0, 0, 1, 1)
        }

        return Rect(minX, minY, maxX + 1, maxY + 1)
    }

    fun packBitsEncodeRow(src: ByteArray, offset: Int, length: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < length) {
            var runLen = 1
            while (i + runLen < length && runLen < 128 && src[offset + i + runLen] == src[offset + i]) {
                runLen++
            }

            if (runLen >= 2) {
                out.write((1 - runLen))
                out.write(src[offset + i].toInt() and 0xFF)
                i += runLen
            } else {
                var literalLen = 0
                while (i + literalLen < length && literalLen < 128) {
                    if (i + literalLen + 2 < length &&
                        src[offset + i + literalLen] == src[offset + i + literalLen + 1] &&
                        src[offset + i + literalLen] == src[offset + i + literalLen + 2]) {
                        break
                    }
                    literalLen++
                }
                if (literalLen > 0) {
                    out.write((literalLen - 1))
                    out.write(src, offset + i, literalLen)
                    i += literalLen
                }
            }
        }
        return out.toByteArray()
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
