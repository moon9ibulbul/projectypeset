package com.astral.typer.utils

import android.graphics.RectF
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object TyperBubbleSegmentationHelper {

    data class BubbleResult(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val isOval: Boolean = true
    ) {
        val rect: RectF get() = RectF(left, top, right, bottom)
    }

    /**
     * Interface to sample pixel ARGB without depending directly on canvas or tiled structures.
     */
    fun interface PixelProvider {
        fun getPixel(x: Int, y: Int): Int
    }

    /**
     * Color similarity check based on RGB Manhattan distance.
     */
    fun isColorSimilar(c1: Int, c2: Int, maxDiff: Int): Boolean {
        val r1 = (c1 shr 16) and 0xFF
        val g1 = (c1 shr 8) and 0xFF
        val b1 = c1 and 0xFF

        val r2 = (c2 shr 16) and 0xFF
        val g2 = (c2 shr 8) and 0xFF
        val b2 = c2 and 0xFF

        return (abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)) <= maxDiff
    }

    /**
     * Determines whether a binary mask within a bounding box is oval (ellipse) or rectangular.
     * Ellipses have a fill ratio of approx pi/4 (~0.785) and nearly empty corners (in an ellipse,
     * corners of the bounding box are not filled). Rectangular panels/bubbles have high fill ratios (>0.85)
     * and high corner occupancy (>0.25).
     */
    fun detectIsOval(
        maskWidth: Int,
        maskHeight: Int,
        mask: BooleanArray,
        minX: Int = 0,
        maxX: Int = maskWidth - 1,
        minY: Int = 0,
        maxY: Int = maskHeight - 1
    ): Boolean {
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        if (w <= 5 || h <= 5) return true

        val totalArea = w * h
        var filledCount = 0
        var cornerFilled = 0
        var cornerTotal = 0

        val cornerThresholdX = (w * 0.12f).toInt().coerceAtLeast(1)
        val cornerThresholdY = (h * 0.12f).toInt().coerceAtLeast(1)

        for (y in minY..maxY) {
            val isNearYCorner = (y - minY < cornerThresholdY) || (maxY - y < cornerThresholdY)
            val rowOffset = y * maskWidth
            for (x in minX..maxX) {
                val isNearXCorner = (x - minX < cornerThresholdX) || (maxX - x < cornerThresholdX)
                val inCorner = isNearXCorner && isNearYCorner
                if (inCorner) {
                    cornerTotal++
                }

                if (mask[rowOffset + x]) {
                    filledCount++
                    if (inCorner) {
                        cornerFilled++
                    }
                }
            }
        }

        val fillRatio = filledCount.toFloat() / totalArea.toFloat()
        val cornerFillRatio = if (cornerTotal > 0) cornerFilled.toFloat() / cornerTotal.toFloat() else 0f

        // Rectangles have high fill ratio (>= 0.86) and significant corner occupancy (>= 0.30)
        return !(fillRatio >= 0.86f && cornerFillRatio >= 0.30f)
    }

    /**
     * Segments a connected binary mask into one or more sub-bubbles.
     * If there are multiple distinct peaks in the distance transform with a neck / constriction,
     * it splits them into separate bubble rects.
     */
    fun segmentConnectedMask(
        maskWidth: Int,
        maskHeight: Int,
        mask: BooleanArray,
        offsetX: Float,
        offsetY: Float,
        tapX: Float? = null,
        tapY: Float? = null
    ): List<BubbleResult> {
        if (maskWidth <= 0 || maskHeight <= 0) return emptyList()

        // 1. Compute Distance Transform on binary mask
        val dist = FloatArray(maskWidth * maskHeight)
        val INF = 1e7f

        for (y in 0 until maskHeight) {
            for (x in 0 until maskWidth) {
                val idx = y * maskWidth + x
                if (!mask[idx]) {
                    dist[idx] = 0f
                } else {
                    var d = INF
                    if (x > 0) d = min(d, dist[y * maskWidth + (x - 1)] + 1f)
                    if (y > 0) d = min(d, dist[(y - 1) * maskWidth + x] + 1f)
                    if (x > 0 && y > 0) d = min(d, dist[(y - 1) * maskWidth + (x - 1)] + 1.4142f)
                    if (x < maskWidth - 1 && y > 0) d = min(d, dist[(y - 1) * maskWidth + (x + 1)] + 1.4142f)
                    dist[idx] = d
                }
            }
        }

        for (y in maskHeight - 1 downTo 0) {
            for (x in maskWidth - 1 downTo 0) {
                val idx = y * maskWidth + x
                if (mask[idx]) {
                    var d = dist[idx]
                    if (x < maskWidth - 1) d = min(d, dist[y * maskWidth + (x + 1)] + 1f)
                    if (y < maskHeight - 1) d = min(d, dist[(y + 1) * maskWidth + x] + 1f)
                    if (x < maskWidth - 1 && y < maskHeight - 1) d = min(d, dist[(y + 1) * maskWidth + (x + 1)] + 1.4142f)
                    if (x > 0 && y < maskHeight - 1) d = min(d, dist[(y + 1) * maskWidth + (x - 1)] + 1.4142f)
                    dist[idx] = d
                }
            }
        }

        var maxD = 0f
        for (v in dist) {
            if (v > maxD) maxD = v
        }

        val minPeakDist = max(15f, maxD * 0.35f)

        data class Peak(val x: Int, val y: Int, val dist: Float)
        val candidatePeaks = mutableListOf<Peak>()
        val peakSearchRadius = max(10, (maxD * 0.4f).toInt())

        for (y in 1 until maskHeight - 1) {
            for (x in 1 until maskWidth - 1) {
                val d = dist[y * maskWidth + x]
                if (d >= minPeakDist) {
                    var isLocalMax = true
                    val r = min(peakSearchRadius, 8)
                    for (ny in max(0, y - r)..min(maskHeight - 1, y + r)) {
                        for (nx in max(0, x - r)..min(maskWidth - 1, x + r)) {
                            if ((nx != x || ny != y) && dist[ny * maskWidth + nx] > d) {
                                isLocalMax = false
                                break
                            }
                        }
                        if (!isLocalMax) break
                    }
                    if (isLocalMax) {
                        candidatePeaks.add(Peak(x, y, d))
                    }
                }
            }
        }

        val filteredPeaks = mutableListOf<Peak>()
        candidatePeaks.sortByDescending { it.dist }
        for (p in candidatePeaks) {
            val isTooClose = filteredPeaks.any { existing ->
                val dx = p.x - existing.x
                val dy = p.y - existing.y
                sqrt((dx * dx + dy * dy).toDouble()) < (p.dist * 0.8f)
            }
            if (!isTooClose) {
                filteredPeaks.add(p)
            }
        }

        if (filteredPeaks.size <= 1) {
            var minX = maskWidth
            var maxX = 0
            var minY = maskHeight
            var maxY = 0
            for (y in 0 until maskHeight) {
                for (x in 0 until maskWidth) {
                    if (mask[y * maskWidth + x]) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (minX <= maxX && minY <= maxY) {
                val smartBounds = computeSmartBubbleBounds(
                    maskWidth, maskHeight, mask, null, 0,
                    minX, maxX, minY, maxY, offsetX, offsetY
                )
                return listOf(smartBounds)
            }
            return emptyList()
        }

        val label = IntArray(maskWidth * maskHeight) { 0 }
        val queue = ArrayDeque<Int>()

        for (i in filteredPeaks.indices) {
            val p = filteredPeaks[i]
            val idx = p.y * maskWidth + p.x
            label[idx] = i + 1
            queue.add(idx)
        }

        while (!queue.isEmpty()) {
            val curr = queue.poll() ?: break
            val cx = curr % maskWidth
            val cy = curr / maskWidth
            val currLabel = label[curr]

            val neighbors = arrayOf(
                cx - 1 to cy,
                cx + 1 to cy,
                cx to cy - 1,
                cx to cy + 1
            )

            for ((nx, ny) in neighbors) {
                if (nx in 0 until maskWidth && ny in 0 until maskHeight) {
                    val nIdx = ny * maskWidth + nx
                    if (mask[nIdx] && label[nIdx] == 0) {
                        label[nIdx] = currLabel
                        queue.add(nIdx)
                    }
                }
            }
        }

        // Count pixels per label to identify main bodies vs tiny tails
        val labelCounts = IntArray(filteredPeaks.size + 1)
        for (y in 0 until maskHeight) {
            for (x in 0 until maskWidth) {
                val lbl = label[y * maskWidth + x]
                if (lbl in 1..filteredPeaks.size) {
                    labelCounts[lbl]++
                }
            }
        }

        val maxComponentPixels = labelCounts.maxOrNull() ?: 0

        val results = mutableListOf<BubbleResult>()
        for (i in filteredPeaks.indices) {
            val targetLabel = i + 1
            val count = labelCounts[targetLabel]

            // If component is too tiny or a thin tail relative to the dominant bubble peak,
            // don't treat it as an independent speech bubble
            if (count <= 50) continue
            if (filteredPeaks.size > 1 && count < (maxComponentPixels * 0.12f) && filteredPeaks[i].dist < (maxD * 0.45f)) {
                continue
            }

            var minX = maskWidth
            var maxX = 0
            var minY = maskHeight
            var maxY = 0

            for (y in 0 until maskHeight) {
                for (x in 0 until maskWidth) {
                    if (label[y * maskWidth + x] == targetLabel) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }

            if (minX <= maxX && minY <= maxY) {
                val smartBounds = computeSmartBubbleBounds(
                    maskWidth, maskHeight, mask, label, targetLabel,
                    minX, maxX, minY, maxY, offsetX, offsetY
                )
                results.add(smartBounds)
            }
        }

        if (tapX != null && tapY != null && results.size > 1) {
            results.sortByDescending {
                tapX in it.left..it.right && tapY in it.top..it.bottom
            }
        }

        return if (results.isNotEmpty()) results else {
            val fullBounds = computeSmartBubbleBounds(
                maskWidth, maskHeight, mask, null, 0,
                0, maskWidth - 1, 0, maskHeight - 1, offsetX, offsetY
            )
            listOf(fullBounds)
        }
    }

    /**
     * Smartly computes the dialogue bounding box of a bubble component by trimming
     * thin protruding tails (dialogue pointers pointing towards speakers).
     * Speech bubble tails typically form narrow extensions along one edge whose width or height
     * is drastically smaller than the main body (e.g. occupying less than 20-25% of the main dimension).
     */
    fun computeSmartBubbleBounds(
        maskWidth: Int,
        maskHeight: Int,
        mask: BooleanArray,
        labels: IntArray?,
        targetLabel: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        offsetX: Float,
        offsetY: Float
    ): BubbleResult {
        val rawW = maxX - minX + 1
        val rawH = maxY - minY + 1
        if (rawW <= 20 || rawH <= 20) {
            val isOval = detectIsOval(maskWidth, maskHeight, mask, minX, maxX, minY, maxY)
            return BubbleResult(minX + offsetX, minY + offsetY, maxX + offsetX, maxY + offsetY, isOval = isOval)
        }

        // Profile row occupancy and column occupancy
        val rowOccupancy = IntArray(rawH)
        val colOccupancy = IntArray(rawW)

        for (y in minY..maxY) {
            val rowOffset = y * maskWidth
            val ry = y - minY
            for (x in minX..maxX) {
                val rx = x - minX
                val isTarget = if (labels != null) {
                    labels[rowOffset + x] == targetLabel
                } else {
                    mask[rowOffset + x]
                }
                if (isTarget) {
                    rowOccupancy[ry]++
                    colOccupancy[rx]++
                }
            }
        }

        val maxRowOcc = rowOccupancy.maxOrNull() ?: 0
        val maxColOcc = colOccupancy.maxOrNull() ?: 0

        // If the main bubble has a prominent body, tail slices usually have < 18% of max occupancy
        val minColThreshold = max(3, (maxColOcc * 0.18f).toInt())
        val minRowThreshold = max(3, (maxRowOcc * 0.18f).toInt())

        var trimLeft = 0
        while (trimLeft < rawW / 3 && colOccupancy[trimLeft] <= minColThreshold) {
            trimLeft++
        }

        var trimRight = rawW - 1
        while (trimRight > rawW * 2 / 3 && colOccupancy[trimRight] <= minColThreshold) {
            trimRight--
        }

        var trimTop = 0
        while (trimTop < rawH / 3 && rowOccupancy[trimTop] <= minRowThreshold) {
            trimTop++
        }

        var trimBottom = rawH - 1
        while (trimBottom > rawH * 2 / 3 && rowOccupancy[trimBottom] <= minRowThreshold) {
            trimBottom--
        }

        val smartMinX = minX + trimLeft
        val smartMaxX = minX + trimRight
        val smartMinY = minY + trimTop
        val smartMaxY = minY + trimBottom

        val isOval = detectIsOval(maskWidth, maskHeight, mask, smartMinX, smartMaxX, smartMinY, smartMaxY)
        return BubbleResult(
            smartMinX + offsetX,
            smartMinY + offsetY,
            smartMaxX + offsetX,
            smartMaxY + offsetY,
            isOval = isOval
        )
    }

    /**
     * Checks if a border has dashed gaps and closes them in a local region.
     * Uses 2D separable morphological dilation with a sliding window for O(W * H) gap closing,
     * flood-fills inside the sealed boundary, and expands back to the border edges
     * via constrained distance BFS.
     */
    fun performClosedFloodFill(
        startX: Int,
        startY: Int,
        canvasW: Int,
        canvasH: Int,
        pixelProvider: PixelProvider,
        maxDiff: Int,
        gapClosingRadius: Int = 24
    ): Pair<IntArray, BooleanArray>? {
        val targetColor = pixelProvider.getPixel(startX, startY)
        val r1 = (targetColor shr 16) and 0xFF
        val g1 = (targetColor shr 8) and 0xFF
        val b1 = targetColor and 0xFF

        fun isSame(c: Int): Boolean {
            val r2 = (c shr 16) and 0xFF
            val g2 = (c shr 8) and 0xFF
            val b2 = c and 0xFF
            return (abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)) <= maxDiff
        }

        // Bounded search window around tap
        val maxExtent = min(1200, (min(canvasW, canvasH) * 0.85f).toInt())
        val minXBound = max(0, startX - maxExtent)
        val maxXBound = min(canvasW - 1, startX + maxExtent)
        val minYBound = max(0, startY - maxExtent)
        val maxYBound = min(canvasH - 1, startY + maxExtent)

        val localW = maxXBound - minXBound + 1
        val localH = maxYBound - minYBound + 1

        // Border map: true if pixel is border (NOT same color as target)
        val isBorder = BooleanArray(localW * localH)
        for (ly in 0 until localH) {
            val gy = minYBound + ly
            val rowOffset = ly * localW
            for (lx in 0 until localW) {
                val gx = minXBound + lx
                val c = pixelProvider.getPixel(gx, gy)
                isBorder[rowOffset + lx] = !isSame(c)
            }
        }

        val bridgeR = gapClosingRadius

        // Pass 1: Horizontal sliding window dilation of isBorder
        val hDilated = BooleanArray(localW * localH)
        for (ly in 0 until localH) {
            val rowOffset = ly * localW
            var activeCount = 0
            val initEnd = min(localW - 1, bridgeR)
            for (lx in 0..initEnd) {
                if (isBorder[rowOffset + lx]) activeCount++
            }
            for (lx in 0 until localW) {
                if (activeCount > 0) {
                    hDilated[rowOffset + lx] = true
                }
                val outX = lx - bridgeR
                if (outX >= 0 && isBorder[rowOffset + outX]) {
                    activeCount--
                }
                val inX = lx + bridgeR + 1
                if (inX < localW && isBorder[rowOffset + inX]) {
                    activeCount++
                }
            }
        }

        // Pass 2: Vertical sliding window dilation of hDilated to get closedBorder
        val closedBorder = BooleanArray(localW * localH)
        for (lx in 0 until localW) {
            var activeCount = 0
            val initEnd = min(localH - 1, bridgeR)
            for (ly in 0..initEnd) {
                if (hDilated[ly * localW + lx]) activeCount++
            }
            for (ly in 0 until localH) {
                if (activeCount > 0) {
                    closedBorder[ly * localW + lx] = true
                }
                val outY = ly - bridgeR
                if (outY >= 0 && hDilated[outY * localW + lx]) {
                    activeCount--
                }
                val inY = ly + bridgeR + 1
                if (inY < localH && hDilated[inY * localW + lx]) {
                    activeCount++
                }
            }
        }

        // Flood fill from start point on the closedBorder map
        val sLocalX = startX - minXBound
        val sLocalY = startY - minYBound
        if (closedBorder[sLocalY * localW + sLocalX]) {
            return null
        }

        val visited = BooleanArray(localW * localH)
        val queue = ArrayDeque<Int>()
        queue.add((sLocalX shl 16) or (sLocalY and 0xFFFF))
        visited[sLocalY * localW + sLocalX] = true

        var count = 0
        var reachedBoundary = false

        while (!queue.isEmpty() && count < 2000000) {
            val enc = queue.poll() ?: break
            val cx = enc ushr 16
            val cy = enc and 0xFFFF
            count++

            if (cx == 0 || cx == localW - 1 || cy == 0 || cy == localH - 1) {
                reachedBoundary = true
            }

            val neighbors = arrayOf(
                cx - 1 to cy,
                cx + 1 to cy,
                cx to cy - 1,
                cx to cy + 1
            )

            for ((nx, ny) in neighbors) {
                if (nx in 0 until localW && ny in 0 until localH) {
                    val nIdx = ny * localW + nx
                    if (!closedBorder[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        queue.add((nx shl 16) or (ny and 0xFFFF))
                    }
                }
            }
        }

        // If the filled region reached local boundary, it was not an enclosed shape
        if (reachedBoundary) {
            return null
        }

        // Expand the eroded mask back towards the original border edges using distance-limited BFS
        // Each entry encodes (distance shl 24) or (x shl 12) or y
        val expandQueue = ArrayDeque<Int>()
        val expanded = BooleanArray(localW * localH)

        for (ly in 0 until localH) {
            val rowOffset = ly * localW
            for (lx in 0 until localW) {
                val idx = rowOffset + lx
                if (visited[idx]) {
                    expanded[idx] = true
                    expandQueue.add((0 shl 24) or (lx shl 12) or ly)
                }
            }
        }

        var bMinX = sLocalX
        var bMaxX = sLocalX
        var bMinY = sLocalY
        var bMaxY = sLocalY

        while (!expandQueue.isEmpty()) {
            val enc = expandQueue.poll() ?: break
            val d = enc ushr 24
            val cx = (enc ushr 12) and 0xFFF
            val cy = enc and 0xFFF

            if (cx < bMinX) bMinX = cx
            if (cx > bMaxX) bMaxX = cx
            if (cy < bMinY) bMinY = cy
            if (cy > bMaxY) bMaxY = cy

            if (d < bridgeR) {
                val nextDist = d + 1
                val neighbors = arrayOf(
                    cx - 1 to cy,
                    cx + 1 to cy,
                    cx to cy - 1,
                    cx to cy + 1
                )
                for ((nx, ny) in neighbors) {
                    if (nx in 0 until localW && ny in 0 until localH) {
                        val nIdx = ny * localW + nx
                        if (!expanded[nIdx] && !isBorder[nIdx]) {
                            expanded[nIdx] = true
                            expandQueue.add((nextDist shl 24) or (nx shl 12) or ny)
                        }
                    }
                }
            }
        }

        val maskW = bMaxX - bMinX + 1
        val maskH = bMaxY - bMinY + 1
        val tightMask = BooleanArray(maskW * maskH)

        for (ly in bMinY..bMaxY) {
            for (lx in bMinX..bMaxX) {
                if (expanded[ly * localW + lx]) {
                    tightMask[(ly - bMinY) * maskW + (lx - bMinX)] = true
                }
            }
        }

        val bounds = intArrayOf(
            minXBound + bMinX,
            minYBound + bMinY,
            minXBound + bMaxX,
            minYBound + bMaxY
        )

        return Pair(bounds, tightMask)
    }
}
