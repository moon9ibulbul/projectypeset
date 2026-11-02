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

    data class PsdTextMeta(
        val text: String,
        val fontSize: Float,
        val fontName: String,
        val color: Int,
        val x: Float,
        val y: Float,
        val rotation: Float,
        val scaleX: Float,
        val scaleY: Float,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderline: Boolean = false,
        val isStrikethrough: Boolean = false,
        val justification: Int = 0, // 0 = left, 1 = right, 2 = center
        val isWarp: Boolean = false,
        val warpRows: Int = 2,
        val warpCols: Int = 2
    )

    data class PsdShadowMeta(
        val blur: Float,
        val dx: Float,
        val dy: Float,
        val color: Int
    )

    data class PsdLayerMeta(
        val name: String,
        val opacity: Int,
        val isVisible: Boolean,
        val blendMode: String,
        val isClipped: Boolean = false,
        val textMeta: PsdTextMeta? = null,
        val shadowMeta: PsdShadowMeta? = null,
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
        val extraData: ByteArray? = null,
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

                val textMeta = if (layer is com.astral.typer.models.TextLayer) {
                    val fontName = extractFontName(layer.fontPath)
                    val just = when (layer.textAlign) {
                        android.text.Layout.Alignment.ALIGN_NORMAL -> 0
                        android.text.Layout.Alignment.ALIGN_OPPOSITE -> 1
                        android.text.Layout.Alignment.ALIGN_CENTER -> 2
                        else -> 0
                    }
                    PsdTextMeta(
                        text = layer.text.toString(),
                        fontSize = layer.fontSize * scale,
                        fontName = fontName,
                        color = layer.color,
                        x = layer.x * scale,
                        y = layer.y * scale,
                        rotation = layer.rotation,
                        scaleX = layer.scaleX,
                        scaleY = layer.scaleY,
                        isBold = layer.isBold,
                        isItalic = layer.isItalic,
                        isUnderline = layer.isUnderline,
                        isStrikethrough = layer.isStrikethrough,
                        justification = just,
                        isWarp = layer.isWarpActive,
                        warpRows = layer.warpRows,
                        warpCols = layer.warpCols
                    )
                } else null

                val shadowMeta = if (layer is com.astral.typer.models.StylableLayer &&
                    (layer.shadowRadius > 0f || layer.shadowDx != 0f || layer.shadowDy != 0f) &&
                    Color.alpha(layer.shadowColor) > 0) {
                    PsdShadowMeta(
                        blur = layer.shadowRadius * scale,
                        dx = layer.shadowDx * scale,
                        dy = layer.shadowDy * scale,
                        color = layer.shadowColor
                    )
                } else null

                layersMeta.add(
                    PsdLayerMeta(
                        name = layerName,
                        opacity = opacityVal,
                        isVisible = layer.isVisible,
                        blendMode = layer.blendMode,
                        isClipped = layer.isClipped,
                        textMeta = textMeta,
                        shadowMeta = shadowMeta,
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
                    isClipped = meta.isClipped,
                    textMeta = meta.textMeta,
                    shadowMeta = meta.shadowMeta
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

            val extraDataLen = l.extraData?.size ?: 0
            val extraLen = 4 + 4 + paddedPascalLen + extraDataLen
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

            // Tagged blocks (Additional Layer Information)
            if (l.extraData != null && l.extraData.isNotEmpty()) {
                dos.write(l.extraData)
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
        isClipped: Boolean,
        textMeta: PsdTextMeta? = null,
        shadowMeta: PsdShadowMeta? = null
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

        // Build tagged blocks (Additional Layer Information)
        val taggedBlocksBaos = ByteArrayOutputStream()
        val taggedDos = DataOutputStream(taggedBlocksBaos)

        // 1. TySh Tagged Block (for editable text in Photoshop)
        if (textMeta != null) {
            val tyshBytes = buildTyShBlock(textMeta)
            taggedDos.writeBytes("8BIM")
            taggedDos.writeBytes("TySh")
            taggedDos.writeInt(tyshBytes.size)
            taggedDos.write(tyshBytes)
            if (tyshBytes.size % 2 != 0) {
                taggedDos.writeByte(0) // Pad to 2 bytes
            }
        }

        // 2. lrFX Tagged Block (Layer Effects, e.g. Drop Shadow)
        if (shadowMeta != null) {
            val lrfxBytes = buildLrfxBlock(shadowMeta)
            taggedDos.writeBytes("8BIM")
            taggedDos.writeBytes("lrFX")
            taggedDos.writeInt(lrfxBytes.size)
            taggedDos.write(lrfxBytes)
            if (lrfxBytes.size % 2 != 0) {
                taggedDos.writeByte(0) // Pad to 2 bytes
            }
        }

        // 2. luni Tagged Block (Unicode layer name)
        val luniBaos = ByteArrayOutputStream()
        val luniDos = DataOutputStream(luniBaos)
        val nameChars = name.toCharArray()
        luniDos.writeInt(nameChars.size)
        for (c in nameChars) {
            luniDos.writeShort(c.code)
        }
        val rawLuniData = luniBaos.toByteArray()
        val luniPaddedLen = ((rawLuniData.size + 3) / 4) * 4 // Padded to 4 bytes
        val luniFinalBaos = ByteArrayOutputStream()
        luniFinalBaos.write(rawLuniData)
        if (luniPaddedLen > rawLuniData.size) {
            luniFinalBaos.write(ByteArray(luniPaddedLen - rawLuniData.size))
        }
        val luniData = luniFinalBaos.toByteArray()

        taggedDos.writeBytes("8BIM")
        taggedDos.writeBytes("luni")
        taggedDos.writeInt(luniData.size)
        taggedDos.write(luniData)

        val extraDataBytes = taggedBlocksBaos.toByteArray()
        val recLen = 66 + paddedPascalLen + extraDataBytes.size

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
            extraData = extraDataBytes,
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

    fun extractFontName(fontPath: String?): String {
        if (fontPath.isNullOrBlank()) return "ArialMT"
        val base = fontPath.substringAfterLast("/").substringAfterLast(":").substringBeforeLast(".")
        return if (base.isNotBlank()) base else "ArialMT"
    }

    private fun writeAsciiStringOrClassId(baos: ByteArrayOutputStream, value: String) {
        val dos = DataOutputStream(baos)
        val ascii = value.toByteArray(Charsets.US_ASCII)
        if (ascii.size == 4 && value != "warp" && value != "time" && value != "hold" && value != "list" && value != "json") {
            dos.writeInt(0)
            dos.write(ascii)
        } else {
            dos.writeInt(ascii.size)
            dos.write(ascii)
        }
    }

    private fun writeUnicodeString(baos: ByteArrayOutputStream, text: String) {
        val dos = DataOutputStream(baos)
        dos.writeInt(text.length)
        for (ch in text) {
            dos.writeShort(ch.code)
        }
    }

    private fun encodeEngineString(text: String): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(" (".toByteArray(Charsets.US_ASCII))
        baos.write(0xFE)
        baos.write(0xFF)
        for (ch in text) {
            val code = ch.code
            val b1 = (code shr 8) and 0xFF
            val b2 = code and 0xFF
            if (b1 == 40 || b1 == 41 || b1 == 92) {
                baos.write(92)
            }
            baos.write(b1)
            if (b2 == 40 || b2 == 41 || b2 == 92) {
                baos.write(92)
            }
            baos.write(b2)
        }
        baos.write(")".toByteArray(Charsets.US_ASCII))
        return baos.toByteArray()
    }

    fun buildEngineData(textMeta: PsdTextMeta): ByteArray {
        val baos = ByteArrayOutputStream()
        val normText = textMeta.text.replace("\r\n", "\r").replace("\n", "\r") + "\r"
        val normTextLen = normText.length

        val r = (Color.red(textMeta.color) / 255f)
        val g = (Color.green(textMeta.color) / 255f)
        val b = (Color.blue(textMeta.color) / 255f)

        val textEnc = encodeEngineString(normText)
        val normalRgbEnc = encodeEngineString("Normal RGB")
        val invisFontEnc = encodeEngineString("AdobeInvisFont")
        val fontNameEnc = encodeEngineString(textMeta.fontName)

        val sb = StringBuilder()
        sb.append("\n\n<<\n")
        sb.append("\t/EngineDict\n\t<<\n")
        sb.append("\t\t/Editor\n\t\t<<\n\t\t\t/Text ")
        baos.write(sb.toString().toByteArray(Charsets.US_ASCII))
        baos.write(textEnc)

        val sb2 = StringBuilder()
        sb2.append("\n\t\t>>\n")
        sb2.append("\t\t/ParagraphRun\n\t\t<<\n")
        sb2.append("\t\t\t/DefaultRunData\n\t\t\t<<\n")
        sb2.append("\t\t\t\t/ParagraphSheet\n\t\t\t\t<<\n")
        sb2.append("\t\t\t\t\t/DefaultStyleSheet 0\n")
        sb2.append("\t\t\t\t\t/Properties\n\t\t\t\t\t<<\n\t\t\t\t\t>>\n")
        sb2.append("\t\t\t\t>>\n")
        sb2.append("\t\t\t\t/Adjustments\n\t\t\t\t<<\n")
        sb2.append("\t\t\t\t\t/Axis [ 1.0 0.0 1.0 ]\n")
        sb2.append("\t\t\t\t\t/XY [ 0.0 0.0 ]\n")
        sb2.append("\t\t\t\t>>\n")
        sb2.append("\t\t\t>>\n")
        sb2.append("\t\t\t/RunArray [\n\t\t\t<<\n")
        sb2.append("\t\t\t\t/ParagraphSheet\n\t\t\t\t<<\n")
        sb2.append("\t\t\t\t\t/DefaultStyleSheet 0\n")
        sb2.append("\t\t\t\t\t/Properties\n\t\t\t\t\t<<\n")
        sb2.append("\t\t\t\t\t\t/Justification ${textMeta.justification}\n")
        sb2.append("\t\t\t\t\t\t/FirstLineIndent 0.0\n")
        sb2.append("\t\t\t\t\t\t/StartIndent 0.0\n")
        sb2.append("\t\t\t\t\t\t/EndIndent 0.0\n")
        sb2.append("\t\t\t\t\t\t/SpaceBefore 0.0\n")
        sb2.append("\t\t\t\t\t\t/SpaceAfter 0.0\n")
        sb2.append("\t\t\t\t\t\t/AutoHyphenate true\n")
        sb2.append("\t\t\t\t\t\t/HyphenatedWordSize 6\n")
        sb2.append("\t\t\t\t\t\t/PreHyphen 2\n")
        sb2.append("\t\t\t\t\t\t/PostHyphen 2\n")
        sb2.append("\t\t\t\t\t\t/ConsecutiveHyphens 8\n")
        sb2.append("\t\t\t\t\t\t/Zone 36.0\n")
        sb2.append("\t\t\t\t\t\t/WordSpacing [ 0.8 1.0 1.33 ]\n")
        sb2.append("\t\t\t\t\t\t/LetterSpacing [ 0.0 0.0 0.0 ]\n")
        sb2.append("\t\t\t\t\t\t/GlyphSpacing [ 1.0 1.0 1.0 ]\n")
        sb2.append("\t\t\t\t\t\t/AutoLeading 1.2\n")
        sb2.append("\t\t\t\t\t\t/LeadingType 0\n")
        sb2.append("\t\t\t\t\t\t/Hanging false\n")
        sb2.append("\t\t\t\t\t\t/Burasagari false\n")
        sb2.append("\t\t\t\t\t\t/KinsokuOrder 0\n")
        sb2.append("\t\t\t\t\t\t/EveryLineComposer false\n")
        sb2.append("\t\t\t\t\t>>\n\t\t\t\t>>\n")
        sb2.append("\t\t\t\t/Adjustments\n\t\t\t\t<<\n")
        sb2.append("\t\t\t\t\t/Axis [ 1.0 0.0 1.0 ]\n")
        sb2.append("\t\t\t\t\t/XY [ 0.0 0.0 ]\n")
        sb2.append("\t\t\t\t>>\n\t\t\t>>\n\t\t\t]\n")
        sb2.append("\t\t\t/RunLengthArray [ $normTextLen ]\n")
        sb2.append("\t\t\t/IsJoinable 1\n")
        sb2.append("\t\t>>\n")
        sb2.append("\t\t/StyleRun\n\t\t<<\n")
        sb2.append("\t\t\t/DefaultRunData\n\t\t\t<<\n")
        sb2.append("\t\t\t\t/StyleSheet\n\t\t\t\t<<\n")
        sb2.append("\t\t\t\t\t/StyleSheetData\n\t\t\t\t\t<<\n\t\t\t\t\t>>\n")
        sb2.append("\t\t\t\t>>\n\t\t\t>>\n")
        sb2.append("\t\t\t/RunArray [\n\t\t\t<<\n")
        sb2.append("\t\t\t\t/StyleSheet\n\t\t\t\t<<\n")
        sb2.append("\t\t\t\t\t/StyleSheetData\n\t\t\t\t\t<<\n")
        sb2.append(String.format(java.util.Locale.US, "\t\t\t\t\t\t/Font 1\n\t\t\t\t\t\t/FontSize %.1f\n", textMeta.fontSize))
        sb2.append("\t\t\t\t\t\t/FauxBold ${if (textMeta.isBold) "true" else "false"}\n")
        sb2.append("\t\t\t\t\t\t/FauxItalic ${if (textMeta.isItalic) "true" else "false"}\n")
        sb2.append("\t\t\t\t\t\t/Underline ${if (textMeta.isUnderline) "true" else "false"}\n")
        sb2.append("\t\t\t\t\t\t/Strikethrough ${if (textMeta.isStrikethrough) "true" else "false"}\n")
        sb2.append("\t\t\t\t\t\t/AutoLeading true\n")
        sb2.append("\t\t\t\t\t\t/Leading 0.0\n")
        sb2.append("\t\t\t\t\t\t/HorizontalScale 1.0\n")
        sb2.append("\t\t\t\t\t\t/VerticalScale 1.0\n")
        sb2.append("\t\t\t\t\t\t/Tracking 0\n")
        sb2.append("\t\t\t\t\t\t/AutoKerning true\n")
        sb2.append("\t\t\t\t\t\t/Kerning 0\n")
        sb2.append("\t\t\t\t\t\t/BaselineShift 0.0\n")
        sb2.append("\t\t\t\t\t\t/FontCaps 0\n")
        sb2.append("\t\t\t\t\t\t/FontBaseline 0\n")
        sb2.append("\t\t\t\t\t\t/Ligatures true\n")
        sb2.append("\t\t\t\t\t\t/DLigatures false\n")
        sb2.append("\t\t\t\t\t\t/BaselineDirection 2\n")
        sb2.append("\t\t\t\t\t\t/Tsume 0.0\n")
        sb2.append("\t\t\t\t\t\t/StyleRunAlignment 2\n")
        sb2.append("\t\t\t\t\t\t/Language 0\n")
        sb2.append("\t\t\t\t\t\t/NoBreak false\n")
        sb2.append("\t\t\t\t\t\t/FillColor\n\t\t\t\t\t\t<<\n")
        sb2.append(String.format(java.util.Locale.US, "\t\t\t\t\t\t\t/Type 1\n\t\t\t\t\t\t\t/Values [ 1.0 %.5f %.5f %.5f ]\n", r, g, b))
        sb2.append("\t\t\t\t\t\t>>\n")
        sb2.append("\t\t\t\t\t\t/StrokeColor\n\t\t\t\t\t\t<<\n")
        sb2.append("\t\t\t\t\t\t\t/Type 1\n\t\t\t\t\t\t\t/Values [ 1.0 0.0 0.0 0.0 ]\n\t\t\t\t\t\t>>\n")
        sb2.append("\t\t\t\t\t\t/FillFlag true\n")
        sb2.append("\t\t\t\t\t\t/StrokeFlag false\n")
        sb2.append("\t\t\t\t\t\t/FillFirst true\n")
        sb2.append("\t\t\t\t\t\t/YUnderline 1\n")
        sb2.append("\t\t\t\t\t\t/OutlineWidth 1.0\n")
        sb2.append("\t\t\t\t\t\t/CharacterDirection 0\n")
        sb2.append("\t\t\t\t\t\t/HindiNumbers false\n")
        sb2.append("\t\t\t\t\t\t/Kashida 1\n")
        sb2.append("\t\t\t\t\t\t/DiacriticPos 2\n")
        sb2.append("\t\t\t\t\t>>\n\t\t\t\t>>\n\t\t\t>>\n\t\t\t]\n")
        sb2.append("\t\t\t/RunLengthArray [ $normTextLen ]\n")
        sb2.append("\t\t\t/IsJoinable 2\n")
        sb2.append("\t\t>>\n")
        sb2.append("\t\t/AntiAlias 4\n")
        sb2.append("\t\t/UseFractionalGlyphWidths true\n")
        sb2.append("\t>>\n")

        sb2.append("\t/ResourceDict\n\t<<\n")
        sb2.append("\t\t/TheNormalStyleSheet 0\n")
        sb2.append("\t\t/TheNormalParagraphSheet 0\n")
        sb2.append("\t\t/ParagraphSheetSet [\n\t\t<<\n")
        sb2.append("\t\t\t/Name ")
        baos.write(sb2.toString().toByteArray(Charsets.US_ASCII))
        baos.write(normalRgbEnc)

        val sb3 = StringBuilder()
        sb3.append("\n\t\t\t/DefaultStyleSheet 0\n")
        sb3.append("\t\t\t/Properties\n\t\t\t<<\n")
        sb3.append("\t\t\t\t/Justification ${textMeta.justification}\n")
        sb3.append("\t\t\t>>\n\t\t>>\n\t\t]\n")
        sb3.append("\t\t/StyleSheetSet [\n\t\t<<\n")
        sb3.append("\t\t\t/Name ")
        baos.write(sb3.toString().toByteArray(Charsets.US_ASCII))
        baos.write(normalRgbEnc)

        val sb4 = StringBuilder()
        sb4.append("\n\t\t\t/StyleSheetData\n\t\t\t<<\n")
        sb4.append("\t\t\t\t/Font 1\n")
        sb4.append("\t\t\t\t/FontSize 12.0\n")
        sb4.append("\t\t\t>>\n\t\t>>\n\t\t]\n")
        sb4.append("\t\t/FontSet [\n\t\t<<\n")
        sb4.append("\t\t\t/Name ")
        baos.write(sb4.toString().toByteArray(Charsets.US_ASCII))
        baos.write(invisFontEnc)

        val sb5 = StringBuilder()
        sb5.append("\n\t\t\t/Script 0\n\t\t\t/FontType 0\n\t\t\t/Synthetic 0\n\t\t>>\n\t\t<<\n")
        sb5.append("\t\t\t/Name ")
        baos.write(sb5.toString().toByteArray(Charsets.US_ASCII))
        baos.write(fontNameEnc)

        val sb6 = StringBuilder()
        sb6.append("\n\t\t\t/Script 0\n\t\t\t/FontType 0\n\t\t\t/Synthetic 0\n\t\t>>\n\t\t]\n")
        sb6.append("\t>>\n")

        sb6.append("\t/DocumentResources\n\t<<\n")
        sb6.append("\t\t/TheNormalStyleSheet 0\n")
        sb6.append("\t\t/TheNormalParagraphSheet 0\n")
        sb6.append("\t\t/ParagraphSheetSet [\n\t\t<<\n")
        sb6.append("\t\t\t/Name ")
        baos.write(sb6.toString().toByteArray(Charsets.US_ASCII))
        baos.write(normalRgbEnc)

        val sb7 = StringBuilder()
        sb7.append("\n\t\t\t/DefaultStyleSheet 0\n")
        sb7.append("\t\t\t/Properties\n\t\t\t<<\n")
        sb7.append("\t\t\t\t/Justification ${textMeta.justification}\n")
        sb7.append("\t\t\t>>\n\t\t>>\n\t\t]\n")
        sb7.append("\t\t/StyleSheetSet [\n\t\t<<\n")
        sb7.append("\t\t\t/Name ")
        baos.write(sb7.toString().toByteArray(Charsets.US_ASCII))
        baos.write(normalRgbEnc)

        val sb8 = StringBuilder()
        sb8.append("\n\t\t\t/StyleSheetData\n\t\t\t<<\n")
        sb8.append("\t\t\t\t/Font 1\n")
        sb8.append("\t\t\t\t/FontSize 12.0\n")
        sb8.append("\t\t\t>>\n\t\t>>\n\t\t]\n")
        sb8.append("\t\t/FontSet [\n\t\t<<\n")
        sb8.append("\t\t\t/Name ")
        baos.write(sb8.toString().toByteArray(Charsets.US_ASCII))
        baos.write(invisFontEnc)

        val sb9 = StringBuilder()
        sb9.append("\n\t\t\t/Script 0\n\t\t\t/FontType 0\n\t\t\t/Synthetic 0\n\t\t>>\n\t\t<<\n")
        sb9.append("\t\t\t/Name ")
        baos.write(sb9.toString().toByteArray(Charsets.US_ASCII))
        baos.write(fontNameEnc)

        val sb10 = StringBuilder()
        sb10.append("\n\t\t\t/Script 0\n\t\t\t/FontType 0\n\t\t\t/Synthetic 0\n\t\t>>\n\t\t]\n")
        sb10.append("\t>>\n>>\n")
        baos.write(sb10.toString().toByteArray(Charsets.US_ASCII))

        return baos.toByteArray()
    }

    fun buildTyShBlock(textMeta: PsdTextMeta): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // 1. TySh version = 1
        dos.writeShort(1)

        // 2. Transform matrix: 6 double (xx, xy, yx, yy, tx, ty)
        val rad = Math.toRadians(textMeta.rotation.toDouble())
        val cosA = Math.cos(rad)
        val sinA = Math.sin(rad)
        val xx = textMeta.scaleX.toDouble() * cosA
        val xy = -textMeta.scaleX.toDouble() * sinA
        val yx = textMeta.scaleY.toDouble() * sinA
        val yy = textMeta.scaleY.toDouble() * cosA
        val tx = textMeta.x.toDouble()
        val ty = textMeta.y.toDouble()
        dos.writeDouble(xx)
        dos.writeDouble(xy)
        dos.writeDouble(yx)
        dos.writeDouble(yy)
        dos.writeDouble(tx)
        dos.writeDouble(ty)

        // 3. Text version = 50
        dos.writeShort(50)

        // 4. Descriptor TxLr
        dos.writeInt(16) // version = 16
        writeUnicodeString(baos, "")
        writeAsciiStringOrClassId(baos, "TxLr")
        dos.writeInt(6) // 6 items

        // Item 1: 'Txt ' (TEXT)
        writeAsciiStringOrClassId(baos, "Txt ")
        dos.writeBytes("TEXT")
        writeUnicodeString(baos, textMeta.text)

        // Item 2: 'textGridding' (enum)
        writeAsciiStringOrClassId(baos, "textGridding")
        dos.writeBytes("enum")
        writeAsciiStringOrClassId(baos, "textGridding")
        writeAsciiStringOrClassId(baos, "None")

        // Item 3: 'Ornt' (enum)
        writeAsciiStringOrClassId(baos, "Ornt")
        dos.writeBytes("enum")
        writeAsciiStringOrClassId(baos, "Ornt")
        writeAsciiStringOrClassId(baos, "Hrzn")

        // Item 4: 'AntA' (enum)
        writeAsciiStringOrClassId(baos, "AntA")
        dos.writeBytes("enum")
        writeAsciiStringOrClassId(baos, "Annt")
        writeAsciiStringOrClassId(baos, "antiAliasSharp")

        // Item 5: 'TextIndex' (long)
        writeAsciiStringOrClassId(baos, "TextIndex")
        dos.writeBytes("long")
        dos.writeInt(0)

        // Item 6: 'EngineData' (tdta)
        writeAsciiStringOrClassId(baos, "EngineData")
        dos.writeBytes("tdta")
        val edBytes = buildEngineData(textMeta)
        dos.writeInt(edBytes.size)
        dos.write(edBytes)

        // 5. Warp version = 1
        dos.writeShort(1)

        // 6. Descriptor warp
        dos.writeInt(16) // version = 16
        writeUnicodeString(baos, "")
        writeAsciiStringOrClassId(baos, "warp")
        val warpItemCount = if (textMeta.isWarp) 10 else 8
        dos.writeInt(warpItemCount)

        // Item 1: 'warpStyle' (enum)
        writeAsciiStringOrClassId(baos, "warpStyle")
        dos.writeBytes("enum")
        writeAsciiStringOrClassId(baos, "warpStyle")
        writeAsciiStringOrClassId(baos, if (textMeta.isWarp) "warpCustom" else "warpNone")

        // Item 2: 'warpValue' (doub)
        writeAsciiStringOrClassId(baos, "warpValue")
        dos.writeBytes("doub")
        dos.writeDouble(0.0)

        // Item 3: 'warpPerspective' (doub)
        writeAsciiStringOrClassId(baos, "warpPerspective")
        dos.writeBytes("doub")
        dos.writeDouble(0.0)

        // Item 4: 'warpPerspectiveOther' (doub)
        writeAsciiStringOrClassId(baos, "warpPerspectiveOther")
        dos.writeBytes("doub")
        dos.writeDouble(0.0)

        // Item 5: 'warpRotate' (enum)
        writeAsciiStringOrClassId(baos, "warpRotate")
        dos.writeBytes("enum")
        writeAsciiStringOrClassId(baos, "Ornt")
        writeAsciiStringOrClassId(baos, "Hrzn")

        // Item 6: 'bounds' (Objc)
        writeAsciiStringOrClassId(baos, "bounds")
        dos.writeBytes("Objc")
        writeUnicodeString(baos, "")
        writeAsciiStringOrClassId(baos, "Rctn")
        dos.writeInt(4) // 4 items: Top , Left, Btom, Rght

        for (corner in listOf("Top ", "Left", "Btom", "Rght")) {
            writeAsciiStringOrClassId(baos, corner)
            dos.writeBytes("UntF")
            dos.writeBytes("#Pxl")
            dos.writeDouble(0.0)
        }

        // Item 7: 'uOrder' (long)
        writeAsciiStringOrClassId(baos, "uOrder")
        dos.writeBytes("long")
        dos.writeInt(0)

        // Item 8: 'vOrder' (long)
        writeAsciiStringOrClassId(baos, "vOrder")
        dos.writeBytes("long")
        dos.writeInt(0)

        if (textMeta.isWarp) {
            // Item 9: 'deformNumRows' (long)
            writeAsciiStringOrClassId(baos, "deformNumRows")
            dos.writeBytes("long")
            dos.writeInt(textMeta.warpRows)

            // Item 10: 'deformNumCols' (long)
            writeAsciiStringOrClassId(baos, "deformNumCols")
            dos.writeBytes("long")
            dos.writeInt(textMeta.warpCols)
        }

        // 7. Bounding box coordinates: 4 floats (0f, 0f, 0f, 0f)
        dos.writeFloat(0f)
        dos.writeFloat(0f)
        dos.writeFloat(0f)
        dos.writeFloat(0f)

        return baos.toByteArray()
    }


    fun buildLrfxBlock(shadowMeta: PsdShadowMeta): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // EffectsLayer header: version = 0, count = 2
        dos.writeShort(0)
        dos.writeShort(2)

        // Effect 1: Common State (cmnS)
        dos.writeBytes("8BIM")
        dos.writeBytes("cmnS")
        dos.writeInt(7) // size = 7
        dos.writeInt(0) // version = 0
        dos.writeByte(1) // visible = 1
        dos.writeShort(0) // 2 unused bytes

        // Effect 2: Drop Shadow (dsdw)
        dos.writeBytes("8BIM")
        dos.writeBytes("dsdw")
        dos.writeInt(51) // size = 51

        val dist = Math.hypot(shadowMeta.dx.toDouble(), shadowMeta.dy.toDouble()).toInt()
        val angle = Math.toDegrees(Math.atan2(shadowMeta.dy.toDouble(), shadowMeta.dx.toDouble())).toInt()

        dos.writeInt(2) // version = 2
        dos.writeInt(shadowMeta.blur.toInt().coerceAtLeast(0))
        dos.writeInt(0) // intensity
        dos.writeInt(angle)
        dos.writeInt(dist)

        // Color (RGB space ID = 0, 4 short values: R*257, G*257, B*257, 0)
        val a = Color.alpha(shadowMeta.color)
        val r = Color.red(shadowMeta.color)
        val g = Color.green(shadowMeta.color)
        val b = Color.blue(shadowMeta.color)

        dos.writeShort(0) // RGB color space
        dos.writeShort(r * 257)
        dos.writeShort(g * 257)
        dos.writeShort(b * 257)
        dos.writeShort(0)

        // Blend mode signature and key
        dos.writeBytes("8BIM")
        dos.writeBytes("mul ")

        dos.writeByte(1) // enabled = 1
        dos.writeByte(0) // useGlobalAngle = 0
        dos.writeByte(a) // opacity

        // native_color
        dos.writeShort(0)
        dos.writeShort(r * 257)
        dos.writeShort(g * 257)
        dos.writeShort(b * 257)
        dos.writeShort(0)

        return baos.toByteArray()
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
