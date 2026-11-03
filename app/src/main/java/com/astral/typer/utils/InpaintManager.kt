package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * Manages inpainting operations, providing a robust fallback if OpenCV fails.
 */
class InpaintManager(private val context: Context) {

    enum class Engine {
        OPENCV,
        PATCHMATCH,
        SMART_SOLID,
        HYBRID,
        LAMA,
        MIGAN
    }

    private var currentEngine: Engine = Engine.OPENCV
    private var isOpenCvInitialized = false
    private val lamaProcessor by lazy { LaMaProcessor(context) }
    private val miganProcessor by lazy { MiganProcessor(context) }

    init {
        try {
            if (OpenCVLoader.initDebug()) {
                isOpenCvInitialized = true
                Log.d("InpaintManager", "OpenCV initialized successfully.")
            } else {
                Log.e("InpaintManager", "OpenCV initialization failed! Will use fallback.")
            }
        } catch (e: Throwable) {
            Log.e("InpaintManager", "Error initializing OpenCV", e)
        }
    }

    fun setEngine(engine: Engine) {
        currentEngine = engine
    }

    fun getEngine(): Engine {
        return currentEngine
    }

    /**
     * Inpaints the original bitmap using the provided mask.
     * @param originalBitmap The source image (ARGB_8888 recommended).
     * @param maskBitmap The mask image (where non-transparent pixels indicate areas to remove).
     * @param bounds Optional bounding box for cropped/sub-region inpainting.
     * @return A new Bitmap with the area inpainted, or null if absolutely everything failed.
     */
    suspend fun inpaint(originalBitmap: Bitmap, maskBitmap: Bitmap, bounds: android.graphics.Rect? = null): Bitmap? {
        val rect = bounds ?: android.graphics.Rect(0, 0, originalBitmap.width, originalBitmap.height)

        // Ensure bounds are valid and within bitmap boundaries
        if (rect.left < 0 || rect.top < 0 || rect.right > originalBitmap.width || rect.bottom > originalBitmap.height || rect.isEmpty) {
            return performInpaintOnRegion(originalBitmap, maskBitmap)
        }

        // If bounds cover the whole image, process directly without cropping overhead
        if (rect.left == 0 && rect.top == 0 && rect.width() == originalBitmap.width && rect.height() == originalBitmap.height) {
            return performInpaintOnRegion(originalBitmap, maskBitmap)
        }

        // Crop and process sub-region
        return withContext(Dispatchers.Default) {
            try {
                val croppedOriginal = Bitmap.createBitmap(originalBitmap, rect.left, rect.top, rect.width(), rect.height())

                // If the provided mask is already sized to match the bounds, use it directly.
                // Otherwise, crop the mask to match the sub-region bounds.
                val croppedMask = if (maskBitmap.width == rect.width() && maskBitmap.height == rect.height()) {
                    maskBitmap
                } else {
                    Bitmap.createBitmap(maskBitmap, rect.left, rect.top, rect.width(), rect.height())
                }

                val croppedResult = performInpaintOnRegion(croppedOriginal, croppedMask)

                // Clean up temporary cropped bitmap
                if (croppedOriginal != originalBitmap) {
                    croppedOriginal.recycle()
                }
                if (croppedMask != maskBitmap) {
                    croppedMask.recycle()
                }

                if (croppedResult != null) {
                    // Create a mutable copy of the original and paste the inpainted region
                    val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = android.graphics.Canvas(resultBitmap)
                    canvas.drawBitmap(croppedResult, rect.left.toFloat(), rect.top.toFloat(), null)
                    croppedResult.recycle()
                    return@withContext resultBitmap
                }
            } catch (e: Exception) {
                Log.e("InpaintManager", "Cropped inpaint failed, falling back to full inpaint", e)
            }

            // Fallback to full inpaint if cropped fails
            performInpaintOnRegion(originalBitmap, maskBitmap)
        }
    }

    private suspend fun performInpaintOnRegion(originalBitmap: Bitmap, maskBitmap: Bitmap): Bitmap? {
        return withContext(Dispatchers.Default) {
            when (currentEngine) {
                Engine.HYBRID -> {
                    inpaintHybrid(originalBitmap, maskBitmap)
                }
                Engine.PATCHMATCH -> {
                    inpaintWithPatchMatch(originalBitmap, maskBitmap)
                }
                Engine.SMART_SOLID -> {
                    inpaintWithSmartSolid(originalBitmap, maskBitmap)
                }
                Engine.LAMA -> {
                    if (lamaProcessor.isModelAvailable()) {
                        val result = lamaProcessor.inpaint(originalBitmap, maskBitmap)
                        if (result != null) return@withContext result
                        Log.w("InpaintManager", "LaMa inpaint failed, falling back to OpenCV")
                    }
                    inpaintWithOpenCVOrFallback(originalBitmap, maskBitmap)
                }
                Engine.MIGAN -> {
                    if (miganProcessor.isModelAvailable()) {
                        val result = miganProcessor.inpaint(originalBitmap, maskBitmap)
                        if (result != null) return@withContext result
                        Log.w("InpaintManager", "MIGAN inpaint failed, falling back to OpenCV")
                    }
                    inpaintWithOpenCVOrFallback(originalBitmap, maskBitmap)
                }
                Engine.OPENCV -> {
                    inpaintWithOpenCVOrFallback(originalBitmap, maskBitmap)
                }
            }
        }
    }

    /**
     * Hybrid Inpainting (matching AstralCleaner clean() strategy):
     * Groups nearby mask regions into connected component blobs.
     * For each blob, samples the surrounding outer ring of background pixels (ring = 8, tol = 22, min_frac = 0.85).
     * If the background around the blob is uniform (e.g. speech bubbles with uniformity >= 85%),
     * fills directly using Smart Solid Fill (+1px dilation) for clean, 100% artifact-free results.
     * Complex/artwork areas are routed to LaMa (or Smart Solid Fill fallback if LaMa is unavailable).
     */
    suspend fun inpaintHybrid(originalBitmap: Bitmap, maskBitmap: Bitmap): Bitmap? {
        val w = originalBitmap.width
        val h = originalBitmap.height
        val total = w * h

        val maskPixels = IntArray(total)
        if (maskBitmap.width != w || maskBitmap.height != h) {
            val scaled = Bitmap.createScaledBitmap(maskBitmap, w, h, false)
            scaled.getPixels(maskPixels, 0, w, 0, 0, w, h)
            scaled.recycle()
        } else {
            maskBitmap.getPixels(maskPixels, 0, w, 0, 0, w, h)
        }

        val origPixels = IntArray(total)
        originalBitmap.getPixels(origPixels, 0, w, 0, 0, w, h)

        // 1. Grow mask by 2px (matching AstralCleaner: if grow > 0: mask = cv2.dilate(mask, ellipse(grow)))
        val binm = BooleanArray(total)
        var hasMask = false
        for (i in 0 until total) {
            if ((maskPixels[i] ushr 24) > 0) {
                binm[i] = true
                hasMask = true
            }
        }
        if (!hasMask) return originalBitmap

        val grownMask = dilateBooleanMask(binm, w, h, 2)

        // 2. Merge nearby blobs with 4px dilation
        val blob = dilateBooleanMask(grownMask, w, h, 4)

        // 3. Label connected components of blob
        val labels = IntArray(total)
        var labelCount = 0
        val queue: java.util.Queue<Int> = java.util.LinkedList()

        // Bounding boxes for each component
        class BlobInfo(val id: Int, var minX: Int, var maxX: Int, var minY: Int, var maxY: Int, var area: Int)
        val blobMap = HashMap<Int, BlobInfo>()

        for (i in 0 until total) {
            if (blob[i] && labels[i] == 0) {
                labelCount++
                val curLabel = labelCount
                labels[i] = curLabel
                queue.add(i)

                val bx = i % w
                val by = i / w
                val info = BlobInfo(curLabel, bx, bx, by, by, 1)

                while (!queue.isEmpty()) {
                    val curr = queue.remove()
                    val cx = curr % w
                    val cy = curr / w

                    if (cx < info.minX) info.minX = cx
                    if (cx > info.maxX) info.maxX = cx
                    if (cy < info.minY) info.minY = cy
                    if (cy > info.maxY) info.maxY = cy

                    // 4-connectivity
                    if (cx > 0) {
                        val n = curr - 1
                        if (blob[n] && labels[n] == 0) { labels[n] = curLabel; queue.add(n); info.area++ }
                    }
                    if (cx < w - 1) {
                        val n = curr + 1
                        if (blob[n] && labels[n] == 0) { labels[n] = curLabel; queue.add(n); info.area++ }
                    }
                    if (cy > 0) {
                        val n = curr - w
                        if (blob[n] && labels[n] == 0) { labels[n] = curLabel; queue.add(n); info.area++ }
                    }
                    if (cy < h - 1) {
                        val n = curr + w
                        if (blob[n] && labels[n] == 0) { labels[n] = curLabel; queue.add(n); info.area++ }
                    }
                }

                if (info.area >= 8) {
                    blobMap[curLabel] = info
                }
            }
        }

        val outPixels = origPixels.clone()
        val lamaBlobs = ArrayList<BlobInfo>()
        val ring = 8
        val tol = 22
        val minFrac = 0.85f

        for ((_, info) in blobMap) {
            val m = ring * 3 + 2
            val ex1 = (info.minX - m).coerceIn(0, w)
            val ey1 = (info.minY - m).coerceIn(0, h)
            val ex2 = (info.maxX + m + 1).coerceIn(0, w)
            val ey2 = (info.maxY + m + 1).coerceIn(0, h)

            val bgCol = estimateComponentBackground(origPixels, labels, blob, w, h, info.id, ex1, ey1, ex2, ey2, ring, tol, minFrac)
            if (bgCol != null) {
                // Background is uniform -> fill component's mask with solid color + 1px dilation
                val compMask = BooleanArray(total)
                for (y in info.minY..info.maxY) {
                    val yOff = y * w
                    for (x in info.minX..info.maxX) {
                        val idx = yOff + x
                        if (grownMask[idx] && labels[idx] == info.id) {
                            compMask[idx] = true
                        }
                    }
                }
                val filledMask = dilateBooleanMask(compMask, w, h, 1)
                for (y in info.minY..info.maxY) {
                    val yOff = y * w
                    for (x in info.minX..info.maxX) {
                        val idx = yOff + x
                        if (filledMask[idx]) {
                            outPixels[idx] = bgCol
                        }
                    }
                }
            } else {
                lamaBlobs.add(info)
            }
        }

        // Create base result bitmap
        val resultBitmap = Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)

        // If there are complex background blobs, process via LaMa
        if (lamaBlobs.isNotEmpty()) {
            if (lamaProcessor.isModelAvailable()) {
                // Build mask for only the LaMa blobs (+1px dilation matching AstralCleaner pending)
                val lamaMaskBits = BooleanArray(total)
                for (info in lamaBlobs) {
                    for (y in info.minY..info.maxY) {
                        val yOff = y * w
                        for (x in info.minX..info.maxX) {
                            val idx = yOff + x
                            if (grownMask[idx] && labels[idx] == info.id) {
                                lamaMaskBits[idx] = true
                            }
                        }
                    }
                }
                val dilatedLamaMask = dilateBooleanMask(lamaMaskBits, w, h, 1)
                val lamaMaskPixels = IntArray(total)
                for (i in 0 until total) {
                    if (dilatedLamaMask[i]) lamaMaskPixels[i] = Color.WHITE
                }
                val lamaMaskBitmap = Bitmap.createBitmap(lamaMaskPixels, w, h, Bitmap.Config.ARGB_8888)

                val lamaResult = lamaProcessor.inpaint(resultBitmap, lamaMaskBitmap)
                lamaMaskBitmap.recycle()
                if (lamaResult != null) {
                    resultBitmap.recycle()
                    return lamaResult
                }
            }

            // Fallback for remaining blobs if LaMa is unavailable or failed: Smart Solid Fill
            val fallbackMaskPixels = IntArray(total)
            for (info in lamaBlobs) {
                for (y in info.minY..info.maxY) {
                    val yOff = y * w
                    for (x in info.minX..info.maxX) {
                        val idx = yOff + x
                        if (grownMask[idx] && labels[idx] == info.id) {
                            fallbackMaskPixels[idx] = Color.WHITE
                        }
                    }
                }
            }
            val fallbackMaskBmp = Bitmap.createBitmap(fallbackMaskPixels, w, h, Bitmap.Config.ARGB_8888)
            val fallbackResult = inpaintWithSmartSolid(resultBitmap, fallbackMaskBmp)
            fallbackMaskBmp.recycle()
            resultBitmap.recycle()
            return fallbackResult
        }

        return resultBitmap
    }

    private fun dilateBooleanMask(src: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        if (radius <= 0) return src.clone()
        val out = BooleanArray(w * h)
        val rSq = radius * radius
        for (y in 0 until h) {
            val yOff = y * w
            for (x in 0 until w) {
                if (src[yOff + x]) {
                    for (dy in -radius..radius) {
                        val ny = y + dy
                        if (ny !in 0 until h) continue
                        val nyOff = ny * w
                        for (dx in -radius..radius) {
                            if (dx * dx + dy * dy <= rSq) {
                                val nx = x + dx
                                if (nx in 0 until w) {
                                    out[nyOff + nx] = true
                                }
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    private fun estimateComponentBackground(
        origPixels: IntArray,
        labels: IntArray,
        blobAll: BooleanArray,
        w: Int,
        h: Int,
        blobId: Int,
        ex1: Int,
        ey1: Int,
        ex2: Int,
        ey2: Int,
        ring: Int,
        tol: Int,
        minFrac: Float
    ): Int? {
        val subW = ex2 - ex1
        val subH = ey2 - ey1
        if (subW <= 0 || subH <= 0) return null

        val subBlob = BooleanArray(subW * subH)
        for (y in ey1 until ey2) {
            val yOff = y * w
            val syOff = (y - ey1) * subW
            for (x in ex1 until ex2) {
                if (labels[yOff + x] == blobId) {
                    subBlob[syOff + (x - ex1)] = true
                }
            }
        }

        for (k in 1..3) {
            val ringRadius = ring * k
            val dilatedSubBlob = dilateBooleanMask(subBlob, subW, subH, ringRadius)
            val sampledColors = ArrayList<Int>()

            for (y in ey1 until ey2) {
                val yOff = y * w
                val syOff = (y - ey1) * subW
                for (x in ex1 until ex2) {
                    val sIdx = syOff + (x - ex1)
                    val gIdx = yOff + x
                    // Dilated sub blob but outside any blob
                    if (dilatedSubBlob[sIdx] && !blobAll[gIdx]) {
                        sampledColors.add(origPixels[gIdx])
                    }
                }
            }

            if (sampledColors.size >= 30) {
                // Compute dominant color with 16-bin quantization
                val counts = HashMap<Int, Int>()
                for (c in sampledColors) {
                    val r = ((c shr 16) and 0xFF) / 16
                    val g = ((c shr 8) and 0xFF) / 16
                    val b = (c and 0xFF) / 16
                    val code = (r shl 8) or (g shl 4) or b
                    counts[code] = (counts[code] ?: 0) + 1
                }
                val topCode = counts.maxByOrNull { it.value }?.key ?: continue
                val centerR = ((topCode shr 8) and 0xF) * 16 + 8
                val centerG = ((topCode shr 4) and 0xF) * 16 + 8
                val centerB = (topCode and 0xF) * 16 + 8

                // Find pixels within tol + 8
                val nearPixels = ArrayList<Int>()
                var withinTolCount = 0
                for (c in sampledColors) {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val dr = r - centerR
                    val dg = g - centerG
                    val db = b - centerB
                    val dist = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble())
                    if (dist <= tol + 8) {
                        nearPixels.add(c)
                    }
                    if (dist <= tol) {
                        withinTolCount++
                    }
                }

                val frac = withinTolCount.toFloat() / sampledColors.size
                if (frac >= minFrac) {
                    // Compute median color of near pixels
                    val pool = if (nearPixels.isNotEmpty()) nearPixels else sampledColors
                    val rList = pool.map { (it shr 16) and 0xFF }.sorted()
                    val gList = pool.map { (it shr 8) and 0xFF }.sorted()
                    val bList = pool.map { it and 0xFF }.sorted()
                    val mid = pool.size / 2
                    val medR = rList[mid]
                    val medG = gList[mid]
                    val medB = bList[mid]
                    return Color.rgb(medR, medG, medB)
                }
                return null
            }
        }
        return null
    }

    private fun inpaintWithOpenCVOrFallback(originalBitmap: Bitmap, maskBitmap: Bitmap): Bitmap {
        if (isOpenCvInitialized) {
            val result = inpaintWithOpenCV(originalBitmap, maskBitmap)
            if (result != null) {
                return result
            }
            Log.w("InpaintManager", "OpenCV inpaint returned null, switching to fallback.")
        }
        return inpaintFallback(originalBitmap, maskBitmap)
    }

    /**
     * Smart Solid Fill: Highly optimized for speech bubbles and flat/gradient backgrounds in comics.
     * Identifies boundary pixels around mask hole, evaluates variance, and applies solid fill or smooth IDW interpolation.
     */
    private fun inpaintWithSmartSolid(original: Bitmap, mask: Bitmap): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val w = result.width
        val h = result.height

        val pixels = IntArray(w * h)
        result.getPixels(pixels, 0, w, 0, 0, w, h)

        val maskPixels = IntArray(w * h)
        if (mask.width != w || mask.height != h) {
            val scaled = Bitmap.createScaledBitmap(mask, w, h, false)
            scaled.getPixels(maskPixels, 0, w, 0, 0, w, h)
            scaled.recycle()
        } else {
            mask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        }

        val hole = BooleanArray(w * h)
        var holeCount = 0
        for (i in pixels.indices) {
            if (Color.alpha(maskPixels[i]) > 0 && (Color.red(maskPixels[i]) > 10 || maskPixels[i] != 0)) {
                hole[i] = true
                holeCount++
            }
        }

        if (holeCount == 0) return result

        // Find boundary pixels (non-hole pixels adjacent to a hole pixel within radius 3)
        val boundaryIndices = ArrayList<Int>()
        val boundaryRadius = 3
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!hole[idx]) {
                    var isNearHole = false
                    for (dy in -boundaryRadius..boundaryRadius) {
                        for (dx in -boundaryRadius..boundaryRadius) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until w && ny in 0 until h) {
                                if (hole[ny * w + nx]) {
                                    isNearHole = true
                                    break
                                }
                            }
                        }
                        if (isNearHole) break
                    }
                    if (isNearHole) {
                        boundaryIndices.add(idx)
                    }
                }
            }
        }

        if (boundaryIndices.isEmpty()) return result

        // Calculate boundary stats
        var sumA = 0L
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (bIdx in boundaryIndices) {
            val p = pixels[bIdx]
            sumA += Color.alpha(p)
            sumR += Color.red(p)
            sumG += Color.green(p)
            sumB += Color.blue(p)
        }
        val count = boundaryIndices.size
        val avgA = (sumA / count).toInt()
        val avgR = (sumR / count).toInt()
        val avgG = (sumG / count).toInt()
        val avgB = (sumB / count).toInt()

        // Calculate variance across RGB
        var varR = 0.0
        var varG = 0.0
        var varB = 0.0
        for (bIdx in boundaryIndices) {
            val p = pixels[bIdx]
            val dr = Color.red(p) - avgR
            val dg = Color.green(p) - avgG
            val db = Color.blue(p) - avgB
            varR += dr * dr
            varG += dg * dg
            varB += db * db
        }
        val stdDevMax = kotlin.math.sqrt(kotlin.math.max(varR, kotlin.math.max(varG, varB)) / count)

        // If boundary has low variance (e.g. solid white speech bubble), fill directly with avg color
        if (stdDevMax < 18.0) {
            val solidColor = Color.argb(avgA, avgR, avgG, avgB)
            for (i in pixels.indices) {
                if (hole[i]) {
                    pixels[i] = solidColor
                }
            }
            result.setPixels(pixels, 0, w, 0, 0, w, h)
            return result
        }

        // Downsample boundary points if too many (for performance in smooth IDW)
        val maxSamples = 100
        val sampledBoundary = if (boundaryIndices.size > maxSamples) {
            val step = boundaryIndices.size / maxSamples
            val sampled = ArrayList<Int>(maxSamples)
            for (i in 0 until boundaryIndices.size step step) {
                sampled.add(boundaryIndices[i])
            }
            sampled
        } else {
            boundaryIndices
        }

        val boundXs = IntArray(sampledBoundary.size)
        val boundYs = IntArray(sampledBoundary.size)
        val boundCols = IntArray(sampledBoundary.size)
        for (i in sampledBoundary.indices) {
            val bIdx = sampledBoundary[i]
            boundXs[i] = bIdx % w
            boundYs[i] = bIdx / w
            boundCols[i] = pixels[bIdx]
        }

        // Interpolate hole pixels using Inverse Distance Weighting
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (hole[idx]) {
                    var weightSum = 0.0
                    var rSum = 0.0
                    var gSum = 0.0
                    var bSum = 0.0
                    var aSum = 0.0

                    for (i in boundXs.indices) {
                        val dx = (x - boundXs[i]).toDouble()
                        val dy = (y - boundYs[i]).toDouble()
                        val distSq = dx * dx + dy * dy
                        if (distSq < 0.0001) {
                            val c = boundCols[i]
                            rSum = Color.red(c).toDouble()
                            gSum = Color.green(c).toDouble()
                            bSum = Color.blue(c).toDouble()
                            aSum = Color.alpha(c).toDouble()
                            weightSum = 1.0
                            break
                        }
                        val w = 1.0 / distSq
                        val c = boundCols[i]
                        aSum += Color.alpha(c) * w
                        rSum += Color.red(c) * w
                        gSum += Color.green(c) * w
                        bSum += Color.blue(c) * w
                        weightSum += w
                    }

                    if (weightSum > 0.0) {
                        val finalA = (aSum / weightSum).toInt().coerceIn(0, 255)
                        val finalR = (rSum / weightSum).toInt().coerceIn(0, 255)
                        val finalG = (gSum / weightSum).toInt().coerceIn(0, 255)
                        val finalB = (bSum / weightSum).toInt().coerceIn(0, 255)
                        pixels[idx] = Color.argb(finalA, finalR, finalG, finalB)
                    }
                }
            }
        }

        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * PatchMatch (Content-Aware Fill): Randomized patch matching synthesis for texture and structure propagation.
     */
    private fun inpaintWithPatchMatch(original: Bitmap, mask: Bitmap): Bitmap {
        val smartSolidBmp = inpaintWithSmartSolid(original, mask)
        val result = smartSolidBmp.copy(Bitmap.Config.ARGB_8888, true)
        if (smartSolidBmp != original) {
            smartSolidBmp.recycle()
        }
        val w = result.width
        val h = result.height

        val pixels = IntArray(w * h)
        result.getPixels(pixels, 0, w, 0, 0, w, h)

        val maskPixels = IntArray(w * h)
        if (mask.width != w || mask.height != h) {
            val scaled = Bitmap.createScaledBitmap(mask, w, h, false)
            scaled.getPixels(maskPixels, 0, w, 0, 0, w, h)
            scaled.recycle()
        } else {
            mask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        }

        val hole = BooleanArray(w * h)
        var holeCount = 0
        for (i in pixels.indices) {
            if (Color.alpha(maskPixels[i]) > 0 && (Color.red(maskPixels[i]) > 10 || maskPixels[i] != 0)) {
                hole[i] = true
                holeCount++
            }
        }

        if (holeCount == 0) return result

        val patchRadius = 3
        val isValidSource = BooleanArray(w * h)
        val validSources = ArrayList<Int>()
        for (y in patchRadius until h - patchRadius) {
            for (x in patchRadius until w - patchRadius) {
                val idx = y * w + x
                if (!hole[idx]) {
                    var allValid = true
                    for (dy in -patchRadius..patchRadius) {
                        for (dx in -patchRadius..patchRadius) {
                            if (hole[(y + dy) * w + (x + dx)]) {
                                allValid = false
                                break
                            }
                        }
                        if (!allValid) break
                    }
                    if (allValid) {
                        isValidSource[idx] = true
                        validSources.add(idx)
                    }
                }
            }
        }

        if (validSources.isEmpty()) {
            return result
        }

        // Initialize Nearest Neighbor Field (NNF) maps pixel index -> source pixel index
        val nnf = IntArray(w * h)
        val random = java.util.Random(42)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (hole[idx]) {
                    nnf[idx] = validSources[random.nextInt(validSources.size)]
                }
            }
        }

        fun patchDistance(x1: Int, y1: Int, x2: Int, y2: Int): Double {
            var diffSum = 0.0
            var count = 0
            for (dy in -patchRadius..patchRadius) {
                val py1 = y1 + dy
                val py2 = y2 + dy
                if (py1 < 0 || py1 >= h || py2 < 0 || py2 >= h) continue
                for (dx in -patchRadius..patchRadius) {
                    val px1 = x1 + dx
                    val px2 = x2 + dx
                    if (px1 < 0 || px1 >= w || px2 < 0 || px2 >= w) continue

                    val idx1 = py1 * w + px1
                    val idx2 = py2 * w + px2

                    val p1 = pixels[idx1]
                    val p2 = pixels[idx2]

                    val dr = Color.red(p1) - Color.red(p2)
                    val dg = Color.green(p1) - Color.green(p2)
                    val db = Color.blue(p1) - Color.blue(p2)

                    diffSum += (dr * dr + dg * dg + db * db)
                    count++
                }
            }
            return if (count > 0) diffSum / count else Double.MAX_VALUE
        }

        // PatchMatch iterations (Propagation + Random Search)
        val numIterations = 4
        for (iter in 0 until numIterations) {
            val forward = (iter % 2 == 0)
            val yStart = if (forward) 0 else h - 1
            val yEnd = if (forward) h else -1
            val yStep = if (forward) 1 else -1

            val xStart = if (forward) 0 else w - 1
            val xEnd = if (forward) w else -1
            val xStep = if (forward) 1 else -1

            var y = yStart
            while (y != yEnd) {
                var x = xStart
                while (x != xEnd) {
                    val idx = y * w + x
                    if (hole[idx]) {
                        var currentSrc = nnf[idx]
                        var bestDist = patchDistance(x, y, currentSrc % w, currentSrc / w)

                        // 1. Propagation
                        val prevX = x - xStep
                        if (prevX in 0 until w) {
                            val candidateSrc = nnf[y * w + prevX] + xStep
                            if (candidateSrc in 0 until w * h && isValidSource[candidateSrc]) {
                                val candidateX = candidateSrc % w
                                val candidateY = candidateSrc / w
                                val dist = patchDistance(x, y, candidateX, candidateY)
                                if (dist < bestDist) {
                                    bestDist = dist
                                    currentSrc = candidateSrc
                                }
                            }
                        }

                        val prevY = y - yStep
                        if (prevY in 0 until h) {
                            val candidateSrc = nnf[prevY * w + x] + yStep * w
                            if (candidateSrc in 0 until w * h && isValidSource[candidateSrc]) {
                                val candidateX = candidateSrc % w
                                val candidateY = candidateSrc / w
                                val dist = patchDistance(x, y, candidateX, candidateY)
                                if (dist < bestDist) {
                                    bestDist = dist
                                    currentSrc = candidateSrc
                                }
                            }
                        }

                        // 2. Random Search
                        var searchRadius = kotlin.math.max(w, h) / 2
                        val alpha = 0.5
                        val currentX = currentSrc % w
                        val currentY = currentSrc / w

                        while (searchRadius >= 1) {
                            val rx = currentX + ((random.nextDouble() * 2 - 1) * searchRadius).toInt()
                            val ry = currentY + ((random.nextDouble() * 2 - 1) * searchRadius).toInt()
                            val clampedX = rx.coerceIn(patchRadius, w - patchRadius - 1)
                            val clampedY = ry.coerceIn(patchRadius, h - patchRadius - 1)
                            val candidateSrc = clampedY * w + clampedX

                            if (isValidSource[candidateSrc]) {
                                val dist = patchDistance(x, y, clampedX, clampedY)
                                if (dist < bestDist) {
                                    bestDist = dist
                                    currentSrc = candidateSrc
                                }
                            }
                            searchRadius = (searchRadius * alpha).toInt()
                        }

                        nnf[idx] = currentSrc
                    }
                    x += xStep
                }
                y += yStep
            }
        }

        // Reconstruction pass: Overlapping patch voting to eliminate grain noise
        val newPixels = pixels.clone()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val targetIdx = y * w + x
                if (hole[targetIdx]) {
                    var sumR = 0.0
                    var sumG = 0.0
                    var sumB = 0.0
                    var sumA = 0.0
                    var count = 0

                    for (dy in -patchRadius..patchRadius) {
                        val py = y + dy
                        if (py < 0 || py >= h) continue
                        for (dx in -patchRadius..patchRadius) {
                            val px = x + dx
                            if (px < 0 || px >= w) continue

                            val patchIdx = py * w + px
                            if (hole[patchIdx]) {
                                val srcIdx = nnf[patchIdx]
                                val sx = srcIdx % w
                                val sy = srcIdx / w

                                val srcX = sx - dx
                                val srcY = sy - dy

                                if (srcX in 0 until w && srcY in 0 until h) {
                                    val p = pixels[srcY * w + srcX]
                                    sumA += Color.alpha(p)
                                    sumR += Color.red(p)
                                    sumG += Color.green(p)
                                    sumB += Color.blue(p)
                                    count++
                                }
                            }
                        }
                    }

                    if (count > 0) {
                        val finalA = (sumA / count).toInt().coerceIn(0, 255)
                        val finalR = (sumR / count).toInt().coerceIn(0, 255)
                        val finalG = (sumG / count).toInt().coerceIn(0, 255)
                        val finalB = (sumB / count).toInt().coerceIn(0, 255)
                        newPixels[targetIdx] = Color.argb(finalA, finalR, finalG, finalB)
                    } else {
                        val srcIdx = nnf[targetIdx]
                        newPixels[targetIdx] = pixels[srcIdx]
                    }
                }
            }
        }

        // Edge-preserving bilateral smoothing pass over hole pixels to remove residual noise
        val smoothedPixels = newPixels.clone()
        val filterRadius = 2
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (hole[idx]) {
                    var rSum = 0.0
                    var gSum = 0.0
                    var bSum = 0.0
                    var aSum = 0.0
                    var wSum = 0.0

                    val centerCol = newPixels[idx]
                    val cr = Color.red(centerCol)
                    val cg = Color.green(centerCol)
                    val cb = Color.blue(centerCol)

                    for (dy in -filterRadius..filterRadius) {
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -filterRadius..filterRadius) {
                            val nx = x + dx
                            if (nx < 0 || nx >= w) continue

                            val nIdx = ny * w + nx
                            val col = newPixels[nIdx]

                            val dr = Color.red(col) - cr
                            val dg = Color.green(col) - cg
                            val db = Color.blue(col) - cb
                            val colorDistSq = dr * dr + dg * dg + db * db

                            val spatialDistSq = dx * dx + dy * dy
                            val weight = Math.exp(-spatialDistSq / 4.0 - colorDistSq / 1600.0)

                            aSum += Color.alpha(col) * weight
                            rSum += Color.red(col) * weight
                            gSum += Color.green(col) * weight
                            bSum += Color.blue(col) * weight
                            wSum += weight
                        }
                    }

                    if (wSum > 0) {
                        val fa = (aSum / wSum).toInt().coerceIn(0, 255)
                        val fr = (rSum / wSum).toInt().coerceIn(0, 255)
                        val fg = (gSum / wSum).toInt().coerceIn(0, 255)
                        val fb = (bSum / wSum).toInt().coerceIn(0, 255)
                        smoothedPixels[idx] = Color.argb(fa, fr, fg, fb)
                    }
                }
            }
        }

        result.setPixels(smoothedPixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun inpaintWithOpenCV(originalBitmap: Bitmap, maskBitmap: Bitmap): Bitmap? {
        val srcMat = Mat()
        val rgbMat = Mat()
        val maskMat = Mat()
        val grayMask = Mat()
        val dstMat = Mat()
        val outputBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)

        var scaledMask: Bitmap? = null

        try {
            // 1. Convert Bitmaps to Mats
            Utils.bitmapToMat(originalBitmap, srcMat)

            // Convert Source to RGB (Drop Alpha) - OpenCV inpaint expects 8-bit 1-channel or 3-channel
            // Attempting to inpaint 4-channel usually fails or behaves unexpectedly
            Imgproc.cvtColor(srcMat, rgbMat, Imgproc.COLOR_RGBA2RGB)

            // 2. Process Mask
            // Ensure mask is same size as original
            val maskToUse = if (maskBitmap.width != originalBitmap.width || maskBitmap.height != originalBitmap.height) {
                scaledMask = Bitmap.createScaledBitmap(maskBitmap, originalBitmap.width, originalBitmap.height, false)
                scaledMask
            } else {
                maskBitmap
            }

            Utils.bitmapToMat(maskToUse, maskMat)

            // Convert Mask to Grayscale (CV_8UC1)
            // Transparent (0) -> Black (0), White (255) -> White (255)
            // Pixels > 0 are inpainted
            Imgproc.cvtColor(maskMat, grayMask, Imgproc.COLOR_RGBA2GRAY)

            // 3. Inpaint
            // Radius 5.0 is standard for Telea
            Photo.inpaint(rgbMat, grayMask, dstMat, 5.0, Photo.INPAINT_TELEA)

            // 4. Convert back to Bitmap
            // dstMat is RGB. Utils.matToBitmap handles conversion to ARGB (adds opaque alpha)
            Utils.matToBitmap(dstMat, outputBitmap)

            return outputBitmap

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("InpaintManager", "OpenCV Inpaint failed", e)
            return null
        } finally {
            // Cleanup native resources
            try { srcMat.release() } catch(e:Exception){}
            try { rgbMat.release() } catch(e:Exception){}
            try { maskMat.release() } catch(e:Exception){}
            try { grayMask.release() } catch(e:Exception){}
            try { dstMat.release() } catch(e:Exception){}

            scaledMask?.recycle()
        }
    }

    /**
     * A simple "Push-Pull" or "Diffusion" based inpainting fallback.
     * It iteratively fills the masked area with average colors from the boundary.
     * This is slower and lower quality than Telea, but works without native libs.
     */
    private fun inpaintFallback(original: Bitmap, mask: Bitmap): Bitmap {
        Log.d("InpaintManager", "Starting Fallback Inpainting...")

        // Working on a mutable copy
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val w = result.width
        val h = result.height

        // Access pixels
        val pixels = IntArray(w * h)
        result.getPixels(pixels, 0, w, 0, 0, w, h)

        val maskPixels = IntArray(w * h)
        // Ensure mask is scaled if needed
        if (mask.width != w || mask.height != h) {
             val scaled = Bitmap.createScaledBitmap(mask, w, h, false)
             scaled.getPixels(maskPixels, 0, w, 0, 0, w, h)
             scaled.recycle()
        } else {
             mask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        }

        // Identify mask boolean array for speed
        // Mask pixel != 0 is "hole"
        val hole = BooleanArray(w * h)
        var holeCount = 0
        for (i in pixels.indices) {
            // Check alpha or brightness of mask
            // Assuming mask is white on transparent/black
            if (Color.alpha(maskPixels[i]) > 0 && (Color.red(maskPixels[i]) > 10 || maskPixels[i] != 0)) {
                hole[i] = true
                holeCount++
                // Clear the pixel in result initially (optional)
                // pixels[i] = Color.TRANSPARENT
            }
        }

        if (holeCount == 0) return result // Nothing to do

        Log.d("InpaintManager", "Fallback: Identified $holeCount pixels to fix.")

        // Simple Iterative Diffusion (Pyramid-like)
        // Repeat N times: for every hole pixel, set it to average of non-hole neighbors
        // To make it converge, we update 'hole' status or use two buffers?
        // Simple approximation: Just smooth it repeatedly.

        // Pass 1: Fill holes with nearest valid pixel (Voronoi-ish) or just generic color
        // to have a starting point. simpler: Skip this, just run diffusion.

        val iterations = 10
        val tempPixels = pixels.clone()

        for (iter in 0 until iterations) {
            var changes = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    if (hole[idx]) {
                        // Gather neighbors
                        var rSum = 0
                        var gSum = 0
                        var bSum = 0
                        var count = 0

                        // Check 4 neighbors
                        // Manually unrolled for performance to avoid alloc

                        // Left
                        if (x > 0) {
                            val nIdx = idx - 1
                            val p = tempPixels[nIdx]
                            rSum += Color.red(p)
                            gSum += Color.green(p)
                            bSum += Color.blue(p)
                            count++
                        }
                        // Right
                        if (x < w - 1) {
                            val nIdx = idx + 1
                            val p = tempPixels[nIdx]
                            rSum += Color.red(p)
                            gSum += Color.green(p)
                            bSum += Color.blue(p)
                            count++
                        }
                        // Top
                        if (y > 0) {
                            val nIdx = idx - w
                            val p = tempPixels[nIdx]
                            rSum += Color.red(p)
                            gSum += Color.green(p)
                            bSum += Color.blue(p)
                            count++
                        }
                        // Bottom
                        if (y < h - 1) {
                            val nIdx = idx + w
                            val p = tempPixels[nIdx]
                            rSum += Color.red(p)
                            gSum += Color.green(p)
                            bSum += Color.blue(p)
                            count++
                        }

                        if (count > 0) {
                            val newCol = Color.rgb(rSum / count, gSum / count, bSum / count)
                            pixels[idx] = newCol // Write to current buffer
                            changes++
                        }
                    }
                }
            }
            // Update temp buffer for next pass
            System.arraycopy(pixels, 0, tempPixels, 0, pixels.size)
        }

        // Apply back
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    fun close() {
        // No explicit cleanup needed
    }
}
