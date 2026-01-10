package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

class BubbleDetectorProcessor(private val context: Context) {

    companion object {
        private const val INPUT_SIZE = 640
        private const val STRIDE = 512
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD = 0.5f
        private const val MIN_BOX_SIZE = 20f // Noise filter threshold
        private const val TOUCHING_TOLERANCE_PX = 15f // Distance to consider boxes "touching"
        private const val ALIGNMENT_OVERLAP_RATIO = 0.5f // Ratio of shared edge length to consider aligned

        private const val MODEL_URL = "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/main/detector.onnx"
        private const val MODEL_FILENAME = "detector.onnx"
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 30000
        private const val USER_AGENT = "AstralTyper/1.0"

        private var ortEnvironment: OrtEnvironment? = null
        private var ortSession: OrtSession? = null
    }

    private val modelFile: File
        get() = File(context.filesDir, "onnx/$MODEL_FILENAME")

    fun isModelAvailable(): Boolean {
        return modelFile.exists() && modelFile.length() > 0
    }

    // --- Model Management ---

    suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val file = modelFile
            file.parentFile?.mkdirs()
            val tmpFile = File(file.parentFile, "$MODEL_FILENAME.tmp")

            var urlStr = MODEL_URL
            var redirects = 0
            val maxRedirects = 5

            while (true) {
                val url = URL(urlStr)
                connection = url.openConnection() as HttpURLConnection
                connection!!.instanceFollowRedirects = false
                connection!!.connectTimeout = CONNECT_TIMEOUT
                connection!!.readTimeout = READ_TIMEOUT
                connection!!.setRequestProperty("User-Agent", USER_AGENT)
                connection!!.connect()

                val responseCode = connection!!.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER) {

                    if (redirects >= maxRedirects) return@withContext false
                    val location = connection!!.getHeaderField("Location")
                    if (location != null) {
                        urlStr = location
                        redirects++
                        connection!!.disconnect()
                        continue
                    } else {
                        return@withContext false
                    }
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    break
                } else {
                     return@withContext false
                }
            }

            val fileLength = connection!!.contentLengthLong
            val input = BufferedInputStream(connection!!.inputStream)
            val output = FileOutputStream(tmpFile)

            val data = ByteArray(8192)
            var total: Long = 0
            var count: Int
            while (input.read(data).also { count = it } != -1) {
                total += count.toLong()
                if (fileLength > 0) {
                    onProgress(total.toFloat() / fileLength)
                }
                output.write(data, 0, count)
            }

            output.flush()
            output.close()
            input.close()
            connection!!.disconnect()

            if (file.exists()) file.delete()
            if (!tmpFile.renameTo(file)) {
                 tmpFile.copyTo(file, overwrite = true)
                 tmpFile.delete()
            }

            closeSession()
            return@withContext true

        } catch (e: Exception) {
            Log.e("BubbleDetector", "Download failed: ${e.message}", e)
            connection?.disconnect()
            return@withContext false
        }
    }

    private fun getSession(): OrtSession {
        if (ortEnvironment == null) {
            ortEnvironment = OrtEnvironment.getEnvironment()
        }
        if (ortSession == null) {
             val sessionOptions = OrtSession.SessionOptions()
             try {
                 sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                 sessionOptions.setInterOpNumThreads(4)
                 sessionOptions.setIntraOpNumThreads(4)
                 sessionOptions.addNnapi()
             } catch (e: Exception) {
                 Log.w("BubbleDetector", "Failed to set opts", e)
             }
             ortSession = ortEnvironment!!.createSession(modelFile.absolutePath, sessionOptions)
        }
        return ortSession!!
    }

    private fun closeSession() {
        try {
            ortSession?.close()
            ortSession = null
        } catch (e: Exception) {}
    }

    // --- Core Inference Logic ---

    suspend fun detect(image: Bitmap): List<RectF> = process(image)

    suspend fun process(bitmap: Bitmap): List<RectF> = withContext(Dispatchers.Default) {
        if (!isModelAvailable()) return@withContext emptyList()

        val width = bitmap.width
        val height = bitmap.height

        val session = getSession()
        val env = OrtEnvironment.getEnvironment()

        // Determine input names
        val inputNames = session.inputNames
        val imageInputName = inputNames.find { it.contains("image", ignoreCase = true) } ?: "images"
        val sizeInputName = inputNames.find { it.contains("size", ignoreCase = true) || it.contains("orig", ignoreCase = true) } ?: "orig_target_sizes"

        // 1. Calculate Tile Positions
        val tiles = mutableListOf<Point>()
        var y = 0
        while (y < height) {
            var actualY = y
            // Handle edge case: shift window up if we are at the bottom
            if (height >= INPUT_SIZE) {
                if (actualY + INPUT_SIZE > height) actualY = height - INPUT_SIZE
            } else {
                actualY = 0 // Image smaller than input size
            }

            var x = 0
            while (x < width) {
                var actualX = x
                // Handle edge case: shift window left if we are at the right edge
                if (width >= INPUT_SIZE) {
                    if (actualX + INPUT_SIZE > width) actualX = width - INPUT_SIZE
                } else {
                    actualX = 0 // Image smaller than input size
                }

                tiles.add(Point(actualX, actualY))

                // Break if we just processed the last chunk horizontally
                if (actualX + INPUT_SIZE >= width) break
                x += STRIDE
            }

            // Break if we just processed the last chunk vertically
            if (actualY + INPUT_SIZE >= height) break
            y += STRIDE
        }

        // 2. Parallel Inference
        val allResults = coroutineScope {
            tiles.map { point ->
                async {
                    processTile(bitmap, point.x, point.y, width, height, env, session, imageInputName, sizeInputName)
                }
            }.awaitAll()
        }

        // 3. Flatten results and apply NMS
        val allDetections = allResults.flatten()

        val nmsResults = nonMaximumSuppression(allDetections)

        // 4. Merge Adjacent Boxes (Split by tiling)
        val mergedBoxes = mergeTouchingBoxes(nmsResults)

        // 5. Shrink boxes to fit inside the bubble (Inner Box)
        // Scale factor 0.75 approximates the inscribed rectangle of an ellipse/circle
        val scale = 0.75f
        return@withContext mergedBoxes.map { box ->
            val cx = box.centerX()
            val cy = box.centerY()
            val w = box.width()
            val h = box.height()

            val newW = w * scale
            val newH = h * scale

            RectF(cx - newW / 2, cy - newH / 2, cx + newW / 2, cy + newH / 2)
        }
    }

    private fun processTile(
        sourceBitmap: Bitmap,
        actualX: Int,
        actualY: Int,
        totalWidth: Int,
        totalHeight: Int,
        env: OrtEnvironment,
        session: OrtSession,
        imageInputName: String,
        sizeInputName: String
    ): List<Detection> {
        // Create tile bitmap (default transparent/black)
        val tileBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tileBitmap)

        try {
            // Draw crop from source
            val srcRect = Rect(actualX, actualY, min(actualX + INPUT_SIZE, totalWidth), min(actualY + INPUT_SIZE, totalHeight))
            // Draw into the top-left of the tile (or appropriately padded)
            val dstRect = Rect(0, 0, srcRect.width(), srcRect.height())

            // Note: sourceBitmap access is thread-safe for reading.
            canvas.drawBitmap(sourceBitmap, srcRect, dstRect, null)

            // Prepare Image Tensor: (1, 3, 640, 640)
            val floatBuffer = preProcess(tileBitmap)
            val imageTensor = OnnxTensor.createTensor(
                env,
                floatBuffer,
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            )

            // Prepare Size Tensor: Always [640, 640] for the tile detection
            val sizeBuffer = LongBuffer.allocate(2)
            sizeBuffer.put(INPUT_SIZE.toLong())
            sizeBuffer.put(INPUT_SIZE.toLong())
            sizeBuffer.flip()
            val sizeTensor = OnnxTensor.createTensor(env, sizeBuffer, longArrayOf(1, 2))

            // Run Inference
            val inputs = mapOf(
                imageInputName to imageTensor,
                sizeInputName to sizeTensor
            )

            // OrtSession.run is thread-safe
            val result = session.run(inputs)

            val outputs = mutableListOf<Any>()
            for (entry in result) {
                outputs.add(entry.value.value)
            }

            val tileDetections = parsePredictions(outputs)

            val shiftedDetections = mutableListOf<Detection>()

            // Adjust coordinates and accumulate
            for (detection in tileDetections) {
                // Offset the detection by the tile position
                val shiftedRect = RectF(detection.rect)
                shiftedRect.offset(actualX.toFloat(), actualY.toFloat())

                // Clip to original image bounds just in case
                shiftedRect.left = max(0f, shiftedRect.left)
                shiftedRect.top = max(0f, shiftedRect.top)
                shiftedRect.right = min(totalWidth.toFloat(), shiftedRect.right)
                shiftedRect.bottom = min(totalHeight.toFloat(), shiftedRect.bottom)

                shiftedDetections.add(detection.copy(rect = shiftedRect))
            }

            result.close()
            imageTensor.close()
            sizeTensor.close()

            return shiftedDetections

        } catch (e: Exception) {
            Log.e("BubbleDetector", "Tile inference failed at ($actualX, $actualY): ${e.message}", e)
            return emptyList()
        } finally {
            tileBitmap.recycle()
        }
    }

    private fun preProcess(bitmap: Bitmap): FloatBuffer {
        val count = 1 * 3 * INPUT_SIZE * INPUT_SIZE
        val floatBuffer = FloatBuffer.allocate(count)

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val stride = INPUT_SIZE * INPUT_SIZE
        val floatArray = FloatArray(count)

        for (i in 0 until stride) {
            val pixel = pixels[i]
            floatArray[i] = ((pixel shr 16) and 0xFF) / 255.0f
            floatArray[stride + i] = ((pixel shr 8) and 0xFF) / 255.0f
            floatArray[2 * stride + i] = (pixel and 0xFF) / 255.0f
        }

        floatBuffer.put(floatArray)
        floatBuffer.flip()
        return floatBuffer
    }

    data class Detection(val rect: RectF, val score: Float, val label: Long)

    private fun parsePredictions(outputs: List<Any>): List<Detection> {
        // We expect 3 tensors from RT-DETR export.
        // Boxes: [1, N, 4] Float
        // Scores: [1, N] Float
        // Labels: [1, N] Long (or Int)

        var boxes: Array<FloatArray>? = null // [N][4]
        var scores: FloatArray? = null // [N]
        var labels: LongArray? = null // [N]

        for (output in outputs) {
            if (output is Array<*>) {
                // Check if it's [1, N, 4] -> Boxes
                // Output is float[1][N][4] -> Array<Array<FloatArray>>
                if (output.isNotEmpty()) {
                     val first = output[0]
                     if (first is Array<*>) {
                         // Shape [Batch, N, Features]
                         // We assume Batch=1, so first is [N, Features] (Array<FloatArray>)
                         if (first.isNotEmpty() && first[0] is FloatArray) {
                             @Suppress("UNCHECKED_CAST")
                             val batchData = first as Array<FloatArray>
                             val cols = if (batchData.isNotEmpty()) batchData[0].size else 0
                             if (cols == 4) {
                                 boxes = batchData
                             }
                         }
                     } else if (first is FloatArray) {
                         // Shape [Batch, N] -> Scores (float[1][N])
                         scores = first
                     } else if (first is LongArray) {
                         // Shape [Batch, N] -> Labels (long[1][N])
                         labels = first
                     } else if (first is IntArray) {
                         // Shape [Batch, N] -> Labels (int[1][N]) - just in case
                         val intArr = first
                         labels = LongArray(intArr.size) { intArr[it].toLong() }
                     }
                }
            }
        }

        if (boxes == null || scores == null) {
            Log.e("BubbleDetector", "Could not identify boxes or scores in outputs")
            return emptyList()
        }

        val numBoxes = boxes!!.size
        val numScores = scores!!.size

        if (numBoxes != numScores) {
             Log.e("BubbleDetector", "Mismatch: boxes=$numBoxes, scores=$numScores")
             return emptyList()
        }

        val detections = mutableListOf<Detection>()

        for (i in 0 until numBoxes) {
            val score = scores!![i]
            if (score > CONFIDENCE_THRESHOLD) {
                val box = boxes!![i]

                // Boxes from RT-DETR with orig_target_sizes are already absolute coordinates.
                // box = [x1, y1, x2, y2]
                val x1 = box[0]
                val y1 = box[1]
                val x2 = box[2]
                val y2 = box[3]

                val label = if (labels != null && i < labels!!.size) labels!![i] else -1L

                // We accept all detected classes (Bubble, Text Bubble, Text Free)
                // Filter if needed: e.g. if (label == 0L || label == 1L)

                detections.add(Detection(RectF(x1, y1, x2, y2), score, label))
            }
        }

        return detections
    }

    private fun nonMaximumSuppression(detections: List<Detection>): List<RectF> {
        // 1. Sort by Area (Largest first)
        val sorted = detections.sortedByDescending {
            it.rect.width() * it.rect.height()
        }.toMutableList()

        val results = mutableListOf<RectF>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0) // The "main" bubble candidate

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()

                // Calculate IoU (Standard Overlap)
                val iou = calculateIoU(best.rect, other.rect)

                // Calculate IoS (Intersection over Smaller / Containment)
                // How much of the 'other' box is inside 'best'?
                val ios = calculateIoS(other.rect, best.rect)

                // LOGIC:
                // Only suppress/remove the smaller box if:
                // 1. It overlaps significantly (IoU > 0.5) OR
                // 2. It is ALMOST FULLY contained inside the big box (IoS > 0.85).
                // We do NOT use union/merge here to preserve the accuracy of the main box.
                if (iou > IOU_THRESHOLD || ios > 0.85f) {
                    iterator.remove()
                }
            }

            // NOISE FILTER: Only add if dimensions are large enough to be a bubble/text
            if (best.rect.width() > MIN_BOX_SIZE && best.rect.height() > MIN_BOX_SIZE) {
                results.add(best.rect)
            }
        }
        return results
    }

    /**
     * Merges boxes that are physically adjacent (touching or very close) and share
     * significant alignment on the shared axis. This fixes the issue where tiling
     * splits large bubbles into multiple non-overlapping boxes.
     */
    private fun mergeTouchingBoxes(initialBoxes: List<RectF>): List<RectF> {
        val boxes = initialBoxes.toMutableList()
        var merged = true

        while (merged) {
            merged = false
            var i = 0
            while (i < boxes.size) {
                val boxA = boxes[i]
                var j = i + 1
                while (j < boxes.size) {
                    val boxB = boxes[j]

                    // Check if they should be merged
                    if (shouldMerge(boxA, boxB)) {
                        // Merge B into A
                        boxA.union(boxB)
                        // Remove B
                        boxes.removeAt(j)
                        // Flag to restart or continue aggressively
                        merged = true
                        // Don't increment j, as the next element shifted to j
                    } else {
                        j++
                    }
                }
                i++
            }
        }
        return boxes
    }

    private fun shouldMerge(a: RectF, b: RectF): Boolean {
        // 1. Vertical Adjacency Check (One above another)
        val vertGap = if (a.bottom < b.top) b.top - a.bottom else if (b.bottom < a.top) a.top - b.bottom else -1f

        // If they overlap vertically (negative gap), they might be candidates if the overlap is small
        // but here we are looking for "split" boxes, so we usually care about small gaps OR small overlaps
        // actually NMS handles big overlaps. This is for "tiled" splits which might have 0 gap or slight overlap.

        // Horizontal Overlap for Vertical Adjacency
        val hOverlapStart = max(a.left, b.left)
        val hOverlapEnd = min(a.right, b.right)
        val hOverlapLen = hOverlapEnd - hOverlapStart

        val minWidth = min(a.width(), b.width())
        val isVertAligned = (hOverlapLen > 0) && (hOverlapLen / minWidth > ALIGNMENT_OVERLAP_RATIO)

        if (isVertAligned && vertGap <= TOUCHING_TOLERANCE_PX && vertGap > -TOUCHING_TOLERANCE_PX) {
             return true
        }

        // 2. Horizontal Adjacency Check (Side by side)
        val horzGap = if (a.right < b.left) b.left - a.right else if (b.right < a.left) a.left - b.right else -1f

        // Vertical Overlap for Horizontal Adjacency
        val vOverlapStart = max(a.top, b.top)
        val vOverlapEnd = min(a.bottom, b.bottom)
        val vOverlapLen = vOverlapEnd - vOverlapStart

        val minHeight = min(a.height(), b.height())
        val isHorzAligned = (vOverlapLen > 0) && (vOverlapLen / minHeight > ALIGNMENT_OVERLAP_RATIO)

        if (isHorzAligned && horzGap <= TOUCHING_TOLERANCE_PX && horzGap > -TOUCHING_TOLERANCE_PX) {
            return true
        }

        return false
    }

    // Helper: Intersection over Smaller Area (to detect contained boxes)
    private fun calculateIoS(inner: RectF, outer: RectF): Float {
        val left = max(inner.left, outer.left)
        val top = max(inner.top, outer.top)
        val right = min(inner.right, outer.right)
        val bottom = min(inner.bottom, outer.bottom)

        if (right < left || bottom < top) return 0f

        val intersectionArea = (right - left) * (bottom - top)
        val innerArea = inner.width() * inner.height()

        if (innerArea <= 0) return 0f
        return intersectionArea / innerArea
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)

        if (right < left || bottom < top) return 0f

        val intersection = (right - left) * (bottom - top)
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()

        return intersection / (areaA + areaB - intersection)
    }
}
