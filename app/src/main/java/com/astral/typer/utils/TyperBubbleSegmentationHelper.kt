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
     * Checks if the boundary contains dashed gaps by finding if a dilation/closing
     * of non-target (edge/background) pixels prevents flood-fill leakage, or closes small line gaps.
     *
     * In dashed panels (e.g., Panel 5), the border has small gaps (e.g. 5-25px).
     * If a flood fill without closing expands excessively compared to a fill with line dilation,
     * closing the gaps prevents leakage to the entire panel.
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
     * Segments a connected binary mask (represented by list of points or 2D boolean array)
     * into one or more sub-bubbles.
     * If there are multiple distinct peaks in the Euclidean distance transform with a neck / constriction,
     * it splits them into separate bubble rects.
     *
     * @param maskWidth width of local bounding box
     * @param maskHeight height of local bounding box
     * @param mask boolean array where true indicates the flood-filled region
     * @param offsetX offset to add to rect X
     * @param offsetY offset to add to rect Y
     * @param tapX optional original tap coordinate in canvas space to prioritize sub-bubble if desired
     * @param tapY optional original tap coordinate in canvas space
     * @return list of segmented BubbleResult
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

        // 1. Compute Distance Transform on binary mask (Manhattan / Chamfer approximation or exact Euclidean)
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

        // Find local maxima / peaks in the distance transform
        // Peaks represent bubble centers
        var maxD = 0f
        for (v in dist) {
            if (v > maxD) maxD = v
        }

        // Minimum radius for a distinct bubble center to avoid noise
        val minPeakDist = max(15f, maxD * 0.35f)

        data class Peak(val x: Int, val y: Int, val dist: Float)
        val candidatePeaks = mutableListOf<Peak>()

        val peakSearchRadius = max(10, (maxD * 0.4f).toInt())

        for (y in 1 until maskHeight - 1) {
            for (x in 1 until maskWidth - 1) {
                val d = dist[y * maskWidth + x]
                if (d >= minPeakDist) {
                    var isLocalMax = true
                    // Check local neighborhood
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

        // Cluster candidate peaks that are close to each other
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

        // If there's only 1 peak or distance between peaks is not deep enough, return single bounding box
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
                return listOf(
                    BubbleResult(
                        minX + offsetX,
                        minY + offsetY,
                        maxX + offsetX,
                        maxY + offsetY,
                        isOval = true
                    )
                )
            }
            return emptyList()
        }

        // We have multiple peaks (e.g. double bubbles in Panel 7).
        // Check if there is a constriction / neck between the peaks.
        // Watershed or distance-based assignment to partition the mask pixels
        val label = IntArray(maskWidth * maskHeight) { 0 }
        val queue = ArrayDeque<Int>()

        // Initialize queue with peak locations
        for (i in filteredPeaks.indices) {
            val p = filteredPeaks[i]
            val idx = p.y * maskWidth + p.x
            label[idx] = i + 1
            queue.add(idx)
        }

        // Breadth-first watershed propagation across mask pixels
        while (!queue.isEmpty()) {
            val curr = queue.poll() ?: break
            val cx = curr % maskWidth
            val cy = curr / maskWidth
            val currLabel = label[curr]

            // 4-connected neighbors
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

        // Calculate bounding box for each labeled component
        val results = mutableListOf<BubbleResult>()
        for (i in filteredPeaks.indices) {
            val targetLabel = i + 1
            var minX = maskWidth
            var maxX = 0
            var minY = maskHeight
            var maxY = 0
            var count = 0

            for (y in 0 until maskHeight) {
                for (x in 0 until maskWidth) {
                    if (label[y * maskWidth + x] == targetLabel) {
                        count++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }

            if (count > 50 && minX <= maxX && minY <= maxY) {
                // If user tapped a specific point, check if we should return both or only the tapped bubble
                results.add(
                    BubbleResult(
                        minX + offsetX,
                        minY + offsetY,
                        maxX + offsetX,
                        maxY + offsetY,
                        isOval = true
                    )
                )
            }
        }

        // If tap coordinates are provided, we can either return all detected components
        // or sort them so the tapped one is first
        if (tapX != null && tapY != null && results.size > 1) {
            results.sortByDescending {
                tapX in it.left..it.right && tapY in it.top..it.bottom
            }
        }

        return if (results.isNotEmpty()) results else {
            // Fallback to whole mask
            listOf(BubbleResult(offsetX, offsetY, offsetX + maskWidth, offsetY + maskHeight, isOval = true))
        }
    }

    /**
     * Checks if a border or edge has dashed gaps and closes them in a local region.
     * When flood fill reaches beyond a normal bubble threshold without hitting solid borders,
     * closing small gaps (e.g. 5-15px) prevents escaping the dashed rectangle.
     */
    fun performClosedFloodFill(
        startX: Int,
        startY: Int,
        canvasW: Int,
        canvasH: Int,
        pixelProvider: PixelProvider,
        maxDiff: Int,
        gapClosingRadius: Int = 12
    ): Pair<IntArray, BooleanArray>? {
        // First check standard BFS
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

        // If the border is dashed (Panel 5), line segments leave gaps.
        // To prevent leaking, we can bridge gaps by treating pixels that are close to border pixels
        // across small distances as solid barriers, or performing flood fill with a virtual gap bridge.
        // An efficient method:
        // Identify non-target color pixels (the border lines) within a local window around start.
        // Dilate the border pixels by gapClosingRadius / 2 so dashed gaps are sealed.
        // Then flood-fill inside the sealed border.

        // Let's first test if standard fill escapes excessively
        // We do a bounded flood fill with max search bounds
        val maxExtent = (min(canvasW, canvasH) * 0.85f).toInt()
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
            for (lx in 0 until localW) {
                val gx = minXBound + lx
                val c = pixelProvider.getPixel(gx, gy)
                isBorder[ly * localW + lx] = !isSame(c)
            }
        }

        // Dilate borders horizontally & vertically by gapClosingRadius to bridge dashed lines
        val closedBorder = BooleanArray(localW * localH)
        val bridgeR = gapClosingRadius

        for (ly in 0 until localH) {
            var lastBorderX = -1000
            for (lx in 0 until localW) {
                if (isBorder[ly * localW + lx]) {
                    // Check if there was a recent border pixel within gap radius
                    val gap = lx - lastBorderX
                    if (gap in 2..bridgeR) {
                        for (fillX in lastBorderX until lx) {
                            closedBorder[ly * localW + fillX] = true
                        }
                    }
                    lastBorderX = lx
                    closedBorder[ly * localW + lx] = true
                }
            }
        }

        for (lx in 0 until localW) {
            var lastBorderY = -1000
            for (ly in 0 until localH) {
                if (isBorder[ly * localW + lx]) {
                    val gap = ly - lastBorderY
                    if (gap in 2..bridgeR) {
                        for (fillY in lastBorderY until ly) {
                            closedBorder[fillY * localW + lx] = true
                        }
                    }
                    lastBorderY = ly
                    closedBorder[ly * localW + lx] = true
                }
            }
        }

        // Flood fill from start point on the closedBorder map
        val sLocalX = startX - minXBound
        val sLocalY = startY - minYBound
        if (closedBorder[sLocalY * localW + sLocalX]) {
            // Start point was bridged over; shouldn't happen unless clicked right on a gap
            return null
        }

        val visited = BooleanArray(localW * localH)
        val queue = ArrayDeque<Int>()
        queue.add((sLocalX shl 16) or (sLocalY and 0xFFFF))
        visited[sLocalY * localW + sLocalX] = true

        var count = 0
        var bMinX = sLocalX
        var bMaxX = sLocalX
        var bMinY = sLocalY
        var bMaxY = sLocalY

        var reachedBoundary = false

        while (!queue.isEmpty() && count < 2000000) {
            val enc = queue.poll() ?: break
            val cx = enc ushr 16
            val cy = enc and 0xFFFF
            count++

            if (cx < bMinX) bMinX = cx
            if (cx > bMaxX) bMaxX = cx
            if (cy < bMinY) bMinY = cy
            if (cy > bMaxY) bMaxY = cy

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

        // If the filled region reached local boundary, it wasn't a closed shape even with dashed closing
        if (reachedBoundary) {
            return null
        }

        val maskW = bMaxX - bMinX + 1
        val maskH = bMaxY - bMinY + 1
        val tightMask = BooleanArray(maskW * maskH)

        for (ly in bMinY..bMaxY) {
            for (lx in bMinX..bMaxX) {
                if (visited[ly * localW + lx]) {
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
