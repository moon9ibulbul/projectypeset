package com.astral.typer.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Random

class PatchMatchProcessor {

    fun isModelAvailable(): Boolean {
        return true // No model required for PatchMatch
    }

    suspend fun inpaint(image: Bitmap, mask: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        val width = image.width
        val height = image.height

        val resultBitmap = image.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(width * height)
        resultBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val maskPixels = IntArray(width * height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        val patchRadius = 2 // 5x5 patch size

        // 1. Identify Target (Hole) and Source (Donor) pixels
        val targetIndices = ArrayList<Int>()
        val isTarget = BooleanArray(width * height)
        var hasTarget = false

        for (i in pixels.indices) {
            val alpha = (maskPixels[i] ushr 24) and 0xFF
            if (alpha > 0) {
                targetIndices.add(i)
                isTarget[i] = true
                hasTarget = true
            }
        }

        if (!hasTarget) {
            return@withContext image
        }

        // Generate a list of all valid source coordinates (fully outside mask with patch margins)
        val sourceCoords = ArrayList<Int>()
        for (y in patchRadius until height - patchRadius) {
            for (x in patchRadius until width - patchRadius) {
                var isSafeSource = true
                // Check if any pixel within patchRadius around (x, y) is in the target/hole
                for (dy in -patchRadius..patchRadius) {
                    val idx = (y + dy) * width + x
                    for (dx in -patchRadius..patchRadius) {
                        if (isTarget[idx + dx]) {
                            isSafeSource = false
                            break
                        }
                    }
                    if (!isSafeSource) break
                }
                if (isSafeSource) {
                    sourceCoords.add((y shl 16) or x)
                }
            }
        }

        // If source coordinates list is empty or extremely small, loosen constraint to just the center pixel outside the mask
        if (sourceCoords.isEmpty()) {
            for (y in patchRadius until height - patchRadius) {
                for (x in patchRadius until width - patchRadius) {
                    val idx = y * width + x
                    if (!isTarget[idx]) {
                        sourceCoords.add((y shl 16) or x)
                    }
                }
            }
        }

        if (sourceCoords.isEmpty()) {
            // Absolutely no donor pixels found, return original image
            return@withContext image
        }

        // 2. Initialize Target pixels (Voronoi-like smooth propagation of colors from boundary)
        // This provides an excellent initial guess for the PatchMatch algorithm.
        initializeHoleColors(pixels, isTarget, width, height)

        // 3. Multi-pass Wexler Completion (Outer loop)
        val random = Random(42)
        val nnf = IntArray(width * height) // packed (sy shl 16) or sx
        val cost = FloatArray(width * height)

        // Initialize NNF with random source coordinates
        for (idx in targetIndices) {
            val randSrc = sourceCoords[random.nextInt(sourceCoords.size)]
            nnf[idx] = randSrc
            val tx = idx % width
            val ty = idx / width
            val sx = randSrc and 0xFFFF
            val sy = randSrc shr 16
            cost[idx] = getSSD(tx, ty, sx, sy, pixels, width, height, patchRadius)
        }

        val outerIterations = 3
        val pmIterations = 5

        for (outer in 0 until outerIterations) {
            // Run PatchMatch iterations to find the best matching source patch for each target pixel
            for (pm in 0 until pmIterations) {
                val forward = (pm % 2 == 0)
                if (forward) {
                    for (i in 0 until targetIndices.size) {
                        val idx = targetIndices[i]
                        val tx = idx % width
                        val ty = idx / width

                        var currentBestSrc = nnf[idx]
                        var currentBestCost = cost[idx]

                        // Propagation from Left neighbor
                        if (tx > 0 && isTarget[idx - 1]) {
                            val neighborSrc = nnf[idx - 1]
                            val nsx = neighborSrc and 0xFFFF
                            val nsy = neighborSrc shr 16
                            val candidateSrcX = nsx + 1
                            val candidateSrcY = nsy
                            if (candidateSrcX < width - patchRadius) {
                                val candSSD = getSSD(tx, ty, candidateSrcX, candidateSrcY, pixels, width, height, patchRadius)
                                if (candSSD < currentBestCost) {
                                    currentBestCost = candSSD
                                    currentBestSrc = (candidateSrcY shl 16) or candidateSrcX
                                }
                            }
                        }

                        // Propagation from Top neighbor
                        if (ty > 0 && isTarget[idx - width]) {
                            val neighborSrc = nnf[idx - width]
                            val nsx = neighborSrc and 0xFFFF
                            val nsy = neighborSrc shr 16
                            val candidateSrcX = nsx
                            val candidateSrcY = nsy + 1
                            if (candidateSrcY < height - patchRadius) {
                                val candSSD = getSSD(tx, ty, candidateSrcX, candidateSrcY, pixels, width, height, patchRadius)
                                if (candSSD < currentBestCost) {
                                    currentBestCost = candSSD
                                    currentBestSrc = (candidateSrcY shl 16) or candidateSrcX
                                }
                            }
                        }

                        // Random Search
                        var radius = kotlin.math.max(width, height)
                        val bestSx = currentBestSrc and 0xFFFF
                        val bestSy = currentBestSrc shr 16
                        while (radius >= 1) {
                            val rx = bestSx + (random.nextDouble() * 2 - 1) * radius
                            val ry = bestSy + (random.nextDouble() * 2 - 1) * radius
                            val candX = rx.toInt().coerceIn(patchRadius, width - 1 - patchRadius)
                            val candY = ry.toInt().coerceIn(patchRadius, height - 1 - patchRadius)

                            val candSSD = getSSD(tx, ty, candX, candY, pixels, width, height, patchRadius)
                            if (candSSD < currentBestCost) {
                                currentBestCost = candSSD
                                currentBestSrc = (candY shl 16) or candX
                            }
                            radius /= 2
                        }

                        nnf[idx] = currentBestSrc
                        cost[idx] = currentBestCost
                    }
                } else {
                    for (i in targetIndices.size - 1 downTo 0) {
                        val idx = targetIndices[i]
                        val tx = idx % width
                        val ty = idx / width

                        var currentBestSrc = nnf[idx]
                        var currentBestCost = cost[idx]

                        // Propagation from Right neighbor
                        if (tx < width - 1 && isTarget[idx + 1]) {
                            val neighborSrc = nnf[idx + 1]
                            val nsx = neighborSrc and 0xFFFF
                            val nsy = neighborSrc shr 16
                            val candidateSrcX = nsx - 1
                            val candidateSrcY = nsy
                            if (candidateSrcX >= patchRadius) {
                                val candSSD = getSSD(tx, ty, candidateSrcX, candidateSrcY, pixels, width, height, patchRadius)
                                if (candSSD < currentBestCost) {
                                    currentBestCost = candSSD
                                    currentBestSrc = (candidateSrcY shl 16) or candidateSrcX
                                }
                            }
                        }

                        // Propagation from Bottom neighbor
                        if (ty < height - 1 && isTarget[idx + width]) {
                            val neighborSrc = nnf[idx + width]
                            val nsx = neighborSrc and 0xFFFF
                            val nsy = neighborSrc shr 16
                            val candidateSrcX = nsx
                            val candidateSrcY = nsy - 1
                            if (candidateSrcY >= patchRadius) {
                                val candSSD = getSSD(tx, ty, candidateSrcX, candidateSrcY, pixels, width, height, patchRadius)
                                if (candSSD < currentBestCost) {
                                    currentBestCost = candSSD
                                    currentBestSrc = (candidateSrcY shl 16) or candidateSrcX
                                }
                            }
                        }

                        // Random Search
                        var radius = kotlin.math.max(width, height)
                        val bestSx = currentBestSrc and 0xFFFF
                        val bestSy = currentBestSrc shr 16
                        while (radius >= 1) {
                            val rx = bestSx + (random.nextDouble() * 2 - 1) * radius
                            val ry = bestSy + (random.nextDouble() * 2 - 1) * radius
                            val candX = rx.toInt().coerceIn(patchRadius, width - 1 - patchRadius)
                            val candY = ry.toInt().coerceIn(patchRadius, height - 1 - patchRadius)

                            val candSSD = getSSD(tx, ty, candX, candY, pixels, width, height, patchRadius)
                            if (candSSD < currentBestCost) {
                                currentBestCost = candSSD
                                currentBestSrc = (candY shl 16) or candX
                            }
                            radius /= 2
                        }

                        nnf[idx] = currentBestSrc
                        cost[idx] = currentBestCost
                    }
                }
            }

            // Reconstruction step (Weighted Voting)
            // Compute the next iteration's estimated pixel values
            val nextPixels = pixels.clone()
            val sumR = FloatArray(width * height)
            val sumG = FloatArray(width * height)
            val sumB = FloatArray(width * height)
            val sumW = FloatArray(width * height)

            for (idx in targetIndices) {
                val tx = idx % width
                val ty = idx / width

                // Collect votes from all overlapping patches containing (tx, ty)
                for (dy in -patchRadius..patchRadius) {
                    val py = ty - dy
                    if (py < 0 || py >= height) continue
                    for (dx in -patchRadius..patchRadius) {
                        val px = tx - dx
                        if (px < 0 || px >= width) continue

                        val pidx = py * width + px
                        if (isTarget[pidx]) {
                            val matchSrc = nnf[pidx]
                            val msx = matchSrc and 0xFFFF
                            val msy = matchSrc shr 16

                            // Corresponding pixel in the matched source patch
                            val sx = msx + dx
                            val sy = msy + dy

                            if (sx in 0 until width && sy in 0 until height) {
                                val sIdx = sy * width + sx
                                val color = pixels[sIdx]
                                val r = ((color shr 16) and 0xFF).toFloat()
                                val g = ((color shr 8) and 0xFF).toFloat()
                                val b = (color and 0xFF).toFloat()

                                // Similarity-based weighting (weight = 1 / (SSD + 1.0))
                                val ssd = cost[pidx]
                                val w = 1.0f / (ssd + 1.0f)

                                sumR[idx] += r * w
                                sumG[idx] += g * w
                                sumB[idx] += b * w
                                sumW[idx] += w
                            }
                        }
                    }
                }
            }

            // Apply votes back to nextPixels
            for (idx in targetIndices) {
                val w = sumW[idx]
                if (w > 0f) {
                    val r = (sumR[idx] / w).toInt().coerceIn(0, 255)
                    val g = (sumG[idx] / w).toInt().coerceIn(0, 255)
                    val b = (sumB[idx] / w).toInt().coerceIn(0, 255)
                    nextPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            // Copy next estimate back to active pixels array
            System.arraycopy(nextPixels, 0, pixels, 0, pixels.size)
        }

        resultBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return@withContext resultBitmap
    }

    private fun initializeHoleColors(pixels: IntArray, isTarget: BooleanArray, width: Int, height: Int) {
        // Multi-pass boundary color propagation to fill the hole with close boundary colors
        // Pass 1: Forward (top-left to bottom-right)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (isTarget[idx]) {
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    var count = 0

                    if (x > 0) {
                        val neighbor = pixels[idx - 1]
                        rSum += (neighbor shr 16) and 0xFF
                        gSum += (neighbor shr 8) and 0xFF
                        bSum += neighbor and 0xFF
                        count++
                    }
                    if (y > 0) {
                        val neighbor = pixels[idx - width]
                        rSum += (neighbor shr 16) and 0xFF
                        gSum += (neighbor shr 8) and 0xFF
                        bSum += neighbor and 0xFF
                        count++
                    }

                    if (count > 0) {
                        pixels[idx] = (0xFF shl 24) or ((rSum / count) shl 16) or ((gSum / count) shl 8) or (bSum / count)
                    }
                }
            }
        }

        // Pass 2: Backward (bottom-right to top-left)
        for (y in height - 1 downTo 0) {
            for (x in width - 1 downTo 0) {
                val idx = y * width + x
                if (isTarget[idx]) {
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    var count = 0

                    if (x < width - 1) {
                        val neighbor = pixels[idx + 1]
                        rSum += (neighbor shr 16) and 0xFF
                        gSum += (neighbor shr 8) and 0xFF
                        bSum += neighbor and 0xFF
                        count++
                    }
                    if (y < height - 1) {
                        val neighbor = pixels[idx + width]
                        rSum += (neighbor shr 16) and 0xFF
                        gSum += (neighbor shr 8) and 0xFF
                        bSum += neighbor and 0xFF
                        count++
                    }

                    if (count > 0) {
                        pixels[idx] = (0xFF shl 24) or ((rSum / count) shl 16) or ((gSum / count) shl 8) or (bSum / count)
                    }
                }
            }
        }
    }

    private fun getSSD(
        tx: Int, ty: Int,
        sx: Int, sy: Int,
        pixels: IntArray,
        width: Int, height: Int,
        patchRadius: Int
    ): Float {
        var ssd = 0f
        for (dy in -patchRadius..patchRadius) {
            val tyy = ty + dy
            val syy = sy + dy
            if (tyy < 0 || tyy >= height || syy < 0 || syy >= height) continue

            for (dx in -patchRadius..patchRadius) {
                val txx = tx + dx
                val sxx = sx + dx
                if (txx < 0 || txx >= width || sxx < 0 || sxx >= width) continue

                val tColor = pixels[tyy * width + txx]
                val sColor = pixels[syy * width + sxx]

                val tr = (tColor shr 16) and 0xFF
                val tg = (tColor shr 8) and 0xFF
                val tb = tColor and 0xFF

                val sr = (sColor shr 16) and 0xFF
                val sg = (sColor shr 8) and 0xFF
                val sb = sColor and 0xFF

                val dr = tr - sr
                val dg = tg - sg
                val db = tb - sb

                ssd += (dr * dr + dg * dg + db * db).toFloat()
            }
        }
        return ssd
    }
}
