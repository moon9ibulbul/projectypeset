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

        private const val MODEL_URL = "https://huggingface.co/bulbulmoon/lama/resolve/main/detector.onnx"
        private const val MODEL_FILENAME = "detector.onnx"
        private const val MODEL_INT8_URL = "https://huggingface.co/bulbulmoon/lama/resolve/main/detector-v4-s_int8.onnx"
        private const val MODEL_INT8_FILENAME = "detector-v4-s_int8.onnx"
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 30000
        private const val USER_AGENT = "AstralTyper/1.0"

        private var ortEnvironment: OrtEnvironment? = null
        private var ortSession: OrtSession? = null
    }

    private val modelFile: File
        get() = File(context.filesDir, "onnx/$MODEL_FILENAME")

    private val modelInt8File: File
        get() = File(context.filesDir, "onnx/$MODEL_INT8_FILENAME")

    fun isModelAvailable(): Boolean {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        val useInt8 = prefs.getString("typer_model_version", "Int8") == "Int8"
        val activeFile = if (useInt8) modelInt8File else modelFile

        if (!activeFile.exists() || activeFile.length() <= 0) {
            val fallbackFile = if (useInt8) modelFile else modelInt8File
            return fallbackFile.exists() && fallbackFile.length() > 0
        }
        return true
    }

    fun isOriginalModelAvailable(): Boolean {
        return modelFile.exists() && modelFile.length() > 0
    }

    fun isInt8ModelAvailable(): Boolean {
        return modelInt8File.exists() && modelInt8File.length() > 0
    }

    // --- Model Management ---

    suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean {
        return downloadInternal(MODEL_URL, modelFile, "$MODEL_FILENAME.tmp", onProgress)
    }

    suspend fun downloadInt8Model(onProgress: (Float) -> Unit): Boolean {
        return downloadInternal(MODEL_INT8_URL, modelInt8File, "$MODEL_INT8_FILENAME.tmp", onProgress)
    }

    private suspend fun downloadInternal(urlStrInput: String, file: File, tmpFileName: String, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            file.parentFile?.mkdirs()
            val tmpFile = File(file.parentFile, tmpFileName)

            var urlStr = urlStrInput
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
                 val numCores = Runtime.getRuntime().availableProcessors()
                 val optimalThreads = (numCores / 2).coerceIn(1, 4)
                 sessionOptions.setInterOpNumThreads(optimalThreads)
                 sessionOptions.setIntraOpNumThreads(optimalThreads)
                 // NNAPI is disabled to prevent crashes on unsupported devices, especially with INT8 models
             } catch (e: Exception) {
                 Log.w("BubbleDetector", "Failed to set opts", e)
             }
             val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
             val useInt8 = prefs.getString("typer_model_version", "Int8") == "Int8"
             var activeFile = if (useInt8) modelInt8File else modelFile

             if (!activeFile.exists() || activeFile.length() <= 0) {
                 activeFile = if (useInt8) modelFile else modelInt8File
             }

             ortSession = ortEnvironment!!.createSession(activeFile.absolutePath, sessionOptions)
        }
        return ortSession!!
    }

    fun closeSession() {
        try {
            ortSession?.close()
            ortSession = null
        } catch (e: Exception) {}
    }

    fun deleteOriginalModel(): Boolean {
        closeSession()
        return if (modelFile.exists()) {
            modelFile.delete()
        } else false
    }

    fun deleteInt8Model(): Boolean {
        closeSession()
        return if (modelInt8File.exists()) {
            modelInt8File.delete()
        } else false
    }

    // --- Core Inference Logic ---

    suspend fun detect(image: Bitmap, allowedLabels: Set<Long>? = null, boxScale: Float = 0.75f): List<RectF> = process(image, allowedLabels, boxScale)

    suspend fun process(bitmap: Bitmap, allowedLabels: Set<Long>? = null, boxScale: Float = 0.75f): List<RectF> = withContext(Dispatchers.Default) {
        if (!isModelAvailable()) return@withContext emptyList()

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        val targetWidth = 640

        // Resize to 640px if height < 35000 (Optimization)
        // Otherwise use scale 1.0 (Full Resolution) for very large images
        val scaleFactor = if (originalHeight < 35000 && originalWidth > targetWidth) {
            targetWidth.toFloat() / originalWidth
        } else {
            1.0f
        }

        val procBitmap = if (scaleFactor < 1.0f) {
            val targetHeight = (originalHeight * scaleFactor).toInt()
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } else {
            bitmap
        }

        try {
            val width = procBitmap.width
            val height = procBitmap.height

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
                        processTile(procBitmap, point.x, point.y, width, height, env, session, imageInputName, sizeInputName)
                    }
                }.awaitAll()
            }

            // 3. Flatten results and apply NMS
            val allDetections = allResults.flatten().toMutableList()

            // Supplement with visual detection of clean/empty speech bubbles (label 0L)
            // Clean speech bubbles without text often have low confidence from RT-DETR
            if (allowedLabels == null || allowedLabels.contains(0L)) {
                val cleanBubbles = detectCleanBubbles(procBitmap)
                allDetections.addAll(cleanBubbles)
            }

            val filteredDetections = if (allowedLabels != null) {
                allDetections.filter { allowedLabels.contains(it.label) }
            } else {
                allDetections
            }

            val nmsResults = nonMaximumSuppression(filteredDetections)

            // 4. Merge Adjacent Boxes (Split by tiling)
            val mergedBoxes = mergeTouchingBoxes(nmsResults)

            // 5. Shrink boxes to fit inside the bubble (Inner Box)
            // Scale factor 0.75 approximates the inscribed rectangle of an ellipse/circle
            // boxScale is now a parameter

            return@withContext mergedBoxes.map { box ->
                // Map back to original coordinates
                val originalBox = RectF(
                    box.left / scaleFactor,
                    box.top / scaleFactor,
                    box.right / scaleFactor,
                    box.bottom / scaleFactor
                )

                val cx = originalBox.centerX()
                val cy = originalBox.centerY()
                val w = originalBox.width()
                val h = originalBox.height()

                val newW = w * boxScale
                val newH = h * boxScale

                RectF(cx - newW / 2, cy - newH / 2, cx + newW / 2, cy + newH / 2)
            }
        } finally {
            // Recycle scaled bitmap if it was created
            if (procBitmap != bitmap) {
                procBitmap.recycle()
            }
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

    private fun preProcess(bitmap: Bitmap): java.nio.FloatBuffer {
        val count = 1 * 3 * INPUT_SIZE * INPUT_SIZE
        val byteBuffer = java.nio.ByteBuffer.allocateDirect(count * 4).order(java.nio.ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val stride = INPUT_SIZE * INPUT_SIZE

        for (i in 0 until stride) {
            val pixel = pixels[i]
            floatBuffer.put(i, ((pixel shr 16) and 0xFF) / 255.0f)
            floatBuffer.put(stride + i, ((pixel shr 8) and 0xFF) / 255.0f)
            floatBuffer.put(2 * stride + i, (pixel and 0xFF) / 255.0f)
        }

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

    /**
     * Visually detects clean/empty speech bubbles in manga panels from a Bitmap.
     */
    fun detectCleanBubbles(image: Bitmap): List<Detection> {
        val w = image.width
        val h = image.height
        if (w < 40 || h < 40) return emptyList()

        val pixels = IntArray(w * h)
        image.getPixels(pixels, 0, w, 0, 0, w, h)
        return detectCleanBubblesFromPixels(pixels, w, h)
    }

    /**
     * Visually detects clean/empty speech bubbles from raw ARGB pixel array.
     * Clean bubbles consist of uniform light/white interior regions enclosed by dark contour lines.
     */
    fun detectCleanBubblesFromPixels(pixels: IntArray, w: Int, h: Int): List<Detection> {
        if (w < 40 || h < 40 || pixels.size < w * h) return emptyList()

        // Identify white / very light pixels (grayscale >= 235)
        val isWhite = BooleanArray(w * h)
        for (i in 0 until (w * h)) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            if (lum >= 235) {
                isWhite[i] = true
            }
        }

        // Connected component labeling on white pixels
        val visited = BooleanArray(w * h)
        val queue = java.util.ArrayDeque<Int>()
        val cleanBubbles = mutableListOf<Detection>()

        val minDimension = 30
        val maxDimW = (w * 0.90f).toInt()
        val maxDimH = (h * 0.90f).toInt()
        val minArea = 1200

        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                val idx = rowOffset + x
                if (isWhite[idx] && !visited[idx]) {
                    visited[idx] = true
                    queue.add(idx)

                    var minX = x
                    var maxX = x
                    var minY = y
                    var maxY = y
                    var count = 0
                    var touchesImageBorder = false

                    while (!queue.isEmpty()) {
                        val curr = queue.poll() ?: break
                        val cx = curr % w
                        val cy = curr / w
                        count++

                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy

                        if (cx == 0 || cx == w - 1 || cy == 0 || cy == h - 1) {
                            touchesImageBorder = true
                        }

                        // 4-connected neighbors
                        val neighbors = arrayOf(
                            cx - 1 to cy,
                            cx + 1 to cy,
                            cx to cy - 1,
                            cx to cy + 1
                        )
                        for ((nx, ny) in neighbors) {
                            if (nx in 0 until w && ny in 0 until h) {
                                val nIdx = ny * w + nx
                                if (isWhite[nIdx] && !visited[nIdx]) {
                                    visited[nIdx] = true
                                    queue.add(nIdx)
                                }
                            }
                        }
                    }

                    // Exclude regions touching image exterior bounds (margins/gutters)
                    if (touchesImageBorder) continue

                    val boxW = maxX - minX + 1
                    val boxH = maxY - minY + 1
                    val boxArea = boxW * boxH

                    if (boxW in minDimension..maxDimW && boxH in minDimension..maxDimH && count >= minArea) {
                        val fillRatio = count.toFloat() / boxArea.toFloat()
                        // Speech bubbles typically have fill ratio between 0.50 (oval / capsule) and 0.95 (rounded rect)
                        if (fillRatio in 0.50f..0.96f) {
                            // Check surrounding perimeter for presence of dark border/ink
                            var darkBorderCount = 0
                            var borderSampleCount = 0
                            val pad = 3

                            // Sample top and bottom borders
                            for (sx in minX..maxX) {
                                val ty = minY - pad
                                if (ty in 0 until h) {
                                    borderSampleCount++
                                    val c = pixels[ty * w + sx]
                                    val lum = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)).toInt()
                                    if (lum < 180) darkBorderCount++
                                }
                                val by = maxY + pad
                                if (by in 0 until h) {
                                    borderSampleCount++
                                    val c = pixels[by * w + sx]
                                    val lum = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)).toInt()
                                    if (lum < 180) darkBorderCount++
                                }
                            }

                            // Sample left and right borders
                            for (sy in minY..maxY) {
                                val lx = minX - pad
                                if (lx in 0 until w) {
                                    borderSampleCount++
                                    val c = pixels[sy * w + lx]
                                    val lum = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)).toInt()
                                    if (lum < 180) darkBorderCount++
                                }
                                val rx = maxX + pad
                                if (rx in 0 until w) {
                                    borderSampleCount++
                                    val c = pixels[sy * w + rx]
                                    val lum = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)).toInt()
                                    if (lum < 180) darkBorderCount++
                                }
                            }

                            // An enclosed clean speech bubble will have dark boundary lines along its perimeter
                            val darkRatio = if (borderSampleCount > 0) darkBorderCount.toFloat() / borderSampleCount.toFloat() else 0f
                            if (darkRatio >= 0.12f) {
                                val rect = RectF().apply {
                                    left = minX.toFloat()
                                    top = minY.toFloat()
                                    right = maxX.toFloat()
                                    bottom = maxY.toFloat()
                                }
                                cleanBubbles.add(Detection(rect, 0.90f, 0L))
                            }
                        }
                    }
                }
            }
        }

        return cleanBubbles
    }

    /**
     * Extracts tight ink mask paths from detected text boxes, constrained to bubble interior
     * (matching AstralCleaner's ink_mask, bubble_interior, and text_polys polygon logic), with user padding.
     */
    fun generateTightTextMasks(
        image: Bitmap,
        textRects: List<RectF>,
        bubbleRects: List<RectF> = emptyList(),
        extraPadding: Int = 0
    ): List<android.graphics.Path> {
        val paths = mutableListOf<android.graphics.Path>()
        val imgW = image.width
        val imgH = image.height

        val interiorCache = mutableMapOf<RectF, BubbleInteriorResult?>()

        fun computeBubbleInterior(bub: RectF): BubbleInteriorResult? {
            if (interiorCache.containsKey(bub)) {
                return interiorCache[bub]
            }

            val bx1 = bub.left.toInt().coerceIn(0, imgW)
            val by1 = bub.top.toInt().coerceIn(0, imgH)
            val bx2 = bub.right.toInt().coerceIn(0, imgW)
            val by2 = bub.bottom.toInt().coerceIn(0, imgH)
            val bw = bx2 - bx1
            val bh = by2 - by1

            if (bw < 12 || bh < 12) {
                interiorCache[bub] = null
                return null
            }

            val bPixels = IntArray(bw * bh)
            image.getPixels(bPixels, 0, bw, bx1, by1, bw, bh)

            // Quantize RGB to 16 bins: (r / 16) * 256 + (g / 16) * 16 + (b / 16)
            val codeCounts = IntArray(4096)
            for (p in bPixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val code = (r / 16) * 256 + (g / 16) * 16 + (b / 16)
                codeCounts[code]++
            }

            var topCode = 0
            var maxCount = 0
            for (c in 0 until 4096) {
                if (codeCounts[c] > maxCount) {
                    maxCount = codeCounts[c]
                    topCode = c
                }
            }

            if (maxCount.toFloat() / (bw * bh) < 0.2f) {
                interiorCache[bub] = null
                return null
            }

            val paperR = (topCode / 256) * 16 + 8
            val paperG = ((topCode / 16) % 16) * 16 + 8
            val paperB = (topCode % 16) * 16 + 8

            // Distance <= 40
            val paperMask = BooleanArray(bw * bh)
            for (i in bPixels.indices) {
                val p = bPixels[i]
                val dr = ((p shr 16) and 0xFF) - paperR
                val dg = ((p shr 8) and 0xFF) - paperG
                val db = (p and 0xFF) - paperB
                if (dr * dr + dg * dg + db * db <= 40 * 40) {
                    paperMask[i] = true
                }
            }

            // Morphological close with 5x5 ellipse kernel (radius 2)
            val closedPaper = morphClose(paperMask, bw, bh, 2)

            // Connected components: find largest component
            val largestComp = findLargestConnectedComponent(closedPaper, bw, bh)
            if (largestComp == null) {
                interiorCache[bub] = null
                return null
            }

            // Fill holes (letters inside bubble)
            val filledComp = fillHoles(largestComp, bw, bh)

            // Check if filled area > 15%
            var filledCount = 0
            for (v in filledComp) if (v) filledCount++
            if (filledCount.toFloat() / (bw * bh) <= 0.15f) {
                interiorCache[bub] = null
                return null
            }

            // Erode with ellipse radius 2
            val erodedComp = morphErode(filledComp, bw, bh, 2)
            val res = BubbleInteriorResult(erodedComp, bx1, by1, bw, bh)
            interiorCache[bub] = res
            return res
        }

        for (box in textRects) {
            val p = 2
            val left = (box.left.toInt() - p).coerceIn(0, imgW)
            val top = (box.top.toInt() - p).coerceIn(0, imgH)
            val right = (box.right.toInt() + p).coerceIn(0, imgW)
            val bottom = (box.bottom.toInt() + p).coerceIn(0, imgH)
            val cropW = right - left
            val cropH = bottom - top

            if (cropW <= 0 || cropH <= 0) continue

            val cropPixels = IntArray(cropW * cropH)
            image.getPixels(cropPixels, 0, cropW, left, top, cropW, cropH)

            // Find containing bubble if any
            val cx = box.centerX()
            val cy = box.centerY()
            val containingBubble = bubbleRects.filter { it.contains(cx, cy) }
                .minByOrNull { it.width() * it.height() }

            // Bubble interior constraint (allowed mask)
            val allowed = BooleanArray(cropW * cropH)
            if (containingBubble != null) {
                val inter = computeBubbleInterior(containingBubble)
                if (inter != null) {
                    val im = inter.mask
                    val ix = inter.x
                    val iy = inter.y
                    val ys = kotlin.math.max(top, iy)
                    val xs = kotlin.math.max(left, ix)
                    val ye = kotlin.math.min(bottom, iy + inter.h)
                    val xe = kotlin.math.min(right, ix + inter.w)
                    for (y in ys until ye) {
                        val cyIdx = (y - top) * cropW
                        val iyIdx = (y - iy) * inter.w
                        for (x in xs until xe) {
                            if (im[iyIdx + (x - ix)]) {
                                allowed[cyIdx + (x - left)] = true
                            }
                        }
                    }
                } else {
                    val m = 3
                    val ax1 = (containingBubble.left.toInt() + m - left).coerceIn(0, cropW)
                    val ay1 = (containingBubble.top.toInt() + m - top).coerceIn(0, cropH)
                    val ax2 = (containingBubble.right.toInt() - m - left).coerceIn(0, cropW)
                    val ay2 = (containingBubble.bottom.toInt() - m - top).coerceIn(0, cropH)
                    for (y in ay1 until ay2) {
                        val yOffset = y * cropW
                        for (x in ax1 until ax2) {
                            allowed[yOffset + x] = true
                        }
                    }
                }
            } else {
                allowed.fill(true)
            }

            // Otsu ink extraction
            val ink = extractInkMask(cropPixels, cropW, cropH, strict = (containingBubble != null))
            val baseInk = ink ?: allowed.clone()

            // ink = ink & allowed
            val combinedInk = BooleanArray(cropW * cropH)
            for (i in combinedInk.indices) {
                combinedInk[i] = baseInk[i] && allowed[i]
            }

            // Dilation with ellipse kernel
            val dilateR = kotlin.math.max(1, 2 + extraPadding)
            val dilatedInk = if (dilateR > 0) {
                val dilated = morphDilate(combinedInk, cropW, cropH, dilateR)
                for (i in dilated.indices) {
                    dilated[i] = dilated[i] && allowed[i]
                }
                dilated
            } else combinedInk

            // Find external contours & approximate polygons
            val polys = findPolygonsFromMask(dilatedInk, cropW, cropH, minArea = 6.0, epsilon = 1.0)

            if (polys.isNotEmpty()) {
                for (poly in polys) {
                    if (poly.size >= 3) {
                        val path = android.graphics.Path()
                        path.moveTo((poly[0].x + left).toFloat(), (poly[0].y + top).toFloat())
                        for (k in 1 until poly.size) {
                            path.lineTo((poly[k].x + left).toFloat(), (poly[k].y + top).toFloat())
                        }
                        path.close()
                        paths.add(path)
                    }
                }
            } else {
                // Fallback to bounding rectangle
                val fallbackPath = android.graphics.Path()
                val r = RectF(box)
                if (extraPadding != 0) r.inset(-extraPadding.toFloat(), -extraPadding.toFloat())
                fallbackPath.addRect(r, android.graphics.Path.Direction.CW)
                paths.add(fallbackPath)
            }
        }

        return paths
    }

    private data class BubbleInteriorResult(
        val mask: BooleanArray,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int
    )

    private data class PointInt(val x: Int, val y: Int)

    private fun extractInkMask(cropPixels: IntArray, w: Int, h: Int, strict: Boolean): BooleanArray? {
        val total = w * h
        if (total == 0) return null

        val gray = IntArray(total)
        val hist = IntArray(256)
        var sumGray = 0L

        for (i in 0 until total) {
            val p = cropPixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            gray[i] = y
            hist[y]++
            sumGray += y
        }

        // Otsu threshold
        var sumB = 0.0
        var wB = 0
        var varMax = 0.0
        var otsuThreshold = 128

        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sumGray - sumB) / wF
            val varBetween = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)

            if (varBetween > varMax) {
                varMax = varBetween
                otsuThreshold = t
            }
        }

        // AstralCleaner: bw = crop > otsu. If bw.mean() > 127: bw = 255 - bw
        val meanGray = sumGray.toDouble() / total
        val bw = BooleanArray(total)
        var bwCount = 0
        val invert = meanGray > 127.0 // or (bw > 0).mean > 127

        for (i in 0 until total) {
            val isWhite = gray[i] > otsuThreshold
            val ink = if (invert) !isWhite else isWhite
            bw[i] = ink
            if (ink) bwCount++
        }

        val inkFrac = bwCount.toFloat() / total
        if (inkFrac <= 0.004f || inkFrac >= 0.55f) {
            return null
        }

        if (strict) {
            // Remove outline components (bubble borders inside text crop)
            val visited = BooleanArray(total)
            val queue = java.util.ArrayDeque<Int>()
            val out = BooleanArray(total)

            for (i in 0 until total) {
                if (bw[i] && !visited[i]) {
                    visited[i] = true
                    queue.add(i)

                    var minX = w
                    var maxX = 0
                    var minY = h
                    var maxY = 0
                    var compArea = 0
                    val compPixels = mutableListOf<Int>()

                    while (!queue.isEmpty()) {
                        val curr = queue.poll() ?: continue
                        compPixels.add(curr)
                        val qx = curr % w
                        val qy = curr / w
                        compArea++

                        if (qx < minX) minX = qx
                        if (qx > maxX) maxX = qx
                        if (qy < minY) minY = qy
                        if (qy > maxY) maxY = qy

                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                if (dx == 0 && dy == 0) continue
                                val nx = qx + dx
                                val ny = qy + dy
                                if (nx in 0 until w && ny in 0 until h) {
                                    val nIdx = ny * w + nx
                                    if (bw[nIdx] && !visited[nIdx]) {
                                        visited[nIdx] = true
                                        queue.add(nIdx)
                                    }
                                }
                            }
                        }
                    }

                    val cw = maxX - minX + 1
                    val ch = maxY - minY + 1
                    val isOutline = ((cw > 0.8f * w && ch > 0.6f * h) || (ch > 0.8f * h && cw > 0.6f * w)) &&
                            (compArea.toFloat() / (cw * ch) < 0.2f)

                    if (!isOutline) {
                        for (idx in compPixels) {
                            out[idx] = true
                        }
                    }
                }
            }
            return out
        }

        return bw
    }

    private fun morphDilate(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return mask.clone()
        val out = BooleanArray(w * h)
        val rSq = r * r
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x]) {
                    for (dy in -r..r) {
                        val ny = y + dy
                        if (ny !in 0 until h) continue
                        val nyOffset = ny * w
                        for (dx in -r..r) {
                            if (dx * dx + dy * dy <= rSq) {
                                val nx = x + dx
                                if (nx in 0 until w) {
                                    out[nyOffset + nx] = true
                                }
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    private fun morphErode(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return mask.clone()
        val out = BooleanArray(w * h)
        val rSq = r * r
        for (y in 0 until h) {
            for (x in 0 until w) {
                var keep = true
                for (dy in -r..r) {
                    if (!keep) break
                    val ny = y + dy
                    if (ny !in 0 until h) { keep = false; break }
                    val nyOffset = ny * w
                    for (dx in -r..r) {
                        if (dx * dx + dy * dy <= rSq) {
                            val nx = x + dx
                            if (nx !in 0 until w || !mask[nyOffset + nx]) {
                                keep = false
                                break
                            }
                        }
                    }
                }
                out[y * w + x] = keep
            }
        }
        return out
    }

    private fun morphClose(mask: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val dilated = morphDilate(mask, w, h, r)
        return morphErode(dilated, w, h, r)
    }

    private fun findLargestConnectedComponent(mask: BooleanArray, w: Int, h: Int): BooleanArray? {
        val total = w * h
        val visited = BooleanArray(total)
        val queue = java.util.ArrayDeque<Int>()

        var maxArea = 0
        var bestComponent: List<Int>? = null

        for (i in 0 until total) {
            if (mask[i] && !visited[i]) {
                visited[i] = true
                queue.add(i)

                val comp = mutableListOf<Int>()
                while (!queue.isEmpty()) {
                    val curr = queue.poll() ?: continue
                    comp.add(curr)
                    val qx = curr % w
                    val qy = curr / w

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = qx + dx
                            val ny = qy + dy
                            if (nx in 0 until w && ny in 0 until h) {
                                val nIdx = ny * w + nx
                                if (mask[nIdx] && !visited[nIdx]) {
                                    visited[nIdx] = true
                                    queue.add(nIdx)
                                }
                            }
                        }
                    }
                }

                if (comp.size > maxArea) {
                    maxArea = comp.size
                    bestComponent = comp
                }
            }
        }

        if (bestComponent == null) return null
        val out = BooleanArray(total)
        for (idx in bestComponent) out[idx] = true
        return out
    }

    private fun fillHoles(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        // Flood fill from border inverted mask
        val total = w * h
        val reachableFromBorder = BooleanArray(total)
        val queue = java.util.ArrayDeque<Int>()

        // Seed with all border pixels where mask is false
        for (x in 0 until w) {
            val topIdx = x
            if (!mask[topIdx] && !reachableFromBorder[topIdx]) {
                reachableFromBorder[topIdx] = true
                queue.add(topIdx)
            }
            val botIdx = (h - 1) * w + x
            if (!mask[botIdx] && !reachableFromBorder[botIdx]) {
                reachableFromBorder[botIdx] = true
                queue.add(botIdx)
            }
        }
        for (y in 0 until h) {
            val leftIdx = y * w
            if (!mask[leftIdx] && !reachableFromBorder[leftIdx]) {
                reachableFromBorder[leftIdx] = true
                queue.add(leftIdx)
            }
            val rightIdx = y * w + (w - 1)
            if (!mask[rightIdx] && !reachableFromBorder[rightIdx]) {
                reachableFromBorder[rightIdx] = true
                queue.add(rightIdx)
            }
        }

        while (!queue.isEmpty()) {
            val curr = queue.poll() ?: continue
            val qx = curr % w
            val qy = curr / w

            if (qx > 0) {
                val nIdx = curr - 1
                if (!mask[nIdx] && !reachableFromBorder[nIdx]) { reachableFromBorder[nIdx] = true; queue.add(nIdx) }
            }
            if (qx < w - 1) {
                val nIdx = curr + 1
                if (!mask[nIdx] && !reachableFromBorder[nIdx]) { reachableFromBorder[nIdx] = true; queue.add(nIdx) }
            }
            if (qy > 0) {
                val nIdx = curr - w
                if (!mask[nIdx] && !reachableFromBorder[nIdx]) { reachableFromBorder[nIdx] = true; queue.add(nIdx) }
            }
            if (qy < h - 1) {
                val nIdx = curr + w
                if (!mask[nIdx] && !reachableFromBorder[nIdx]) { reachableFromBorder[nIdx] = true; queue.add(nIdx) }
            }
        }

        // Any pixel that is NOT reachable from the border is part of the filled mask
        val filled = BooleanArray(total)
        for (i in 0 until total) {
            filled[i] = !reachableFromBorder[i]
        }
        return filled
    }

    private fun findPolygonsFromMask(
        mask: BooleanArray,
        w: Int,
        h: Int,
        minArea: Double = 6.0,
        epsilon: Double = 1.0
    ): List<List<PointInt>> {
        val contours = traceExternalContours(mask, w, h)
        val polys = mutableListOf<List<PointInt>>()

        for (c in contours) {
            val area = calculateContourArea(c)
            if (area < minArea) continue

            val approx = ramerDouglasPeucker(c, epsilon)
            if (approx.size >= 3) {
                polys.add(approx)
            }
        }
        return polys
    }

    private fun calculateContourArea(pts: List<PointInt>): Double {
        if (pts.size < 3) return 0.0
        var a = 0.0
        val n = pts.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += pts[i].x.toDouble() * pts[j].y.toDouble() - pts[j].x.toDouble() * pts[i].y.toDouble()
        }
        return kotlin.math.abs(a) * 0.5
    }

    private fun ramerDouglasPeucker(pts: List<PointInt>, eps: Double): List<PointInt> {
        if (pts.size <= 2) return pts
        val first = pts[0]
        val last = pts[pts.size - 1]

        var maxDist = 0.0
        var maxIndex = 0

        val dx = (last.x - first.x).toDouble()
        val dy = (last.y - first.y).toDouble()
        val denom = kotlin.math.hypot(dx, dy)

        for (i in 1 until pts.size - 1) {
            val p = pts[i]
            val dist = if (denom == 0.0) {
                kotlin.math.hypot((p.x - first.x).toDouble(), (p.y - first.y).toDouble())
            } else {
                kotlin.math.abs(dy * p.x - dx * p.y + last.x * first.y - last.y * first.x) / denom
            }
            if (dist > maxDist) {
                maxDist = dist
                maxIndex = i
            }
        }

        return if (maxDist > eps) {
            val left = ramerDouglasPeucker(pts.subList(0, maxIndex + 1), eps)
            val right = ramerDouglasPeucker(pts.subList(maxIndex, pts.size), eps)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    private fun traceExternalContours(mask: BooleanArray, w: Int, h: Int): List<List<PointInt>> {
        val visited = BooleanArray(w * h)
        val contours = mutableListOf<List<PointInt>>()

        // 8-directions clockwise: right, down-right, down, down-left, left, up-left, up, up-right
        val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (mask[idx] && !visited[idx]) {
                    // Check if on an external edge (above pixel is not mask or border)
                    if (y == 0 || !mask[(y - 1) * w + x]) {
                        val contour = mutableListOf<PointInt>()
                        var cx = x
                        var cy = y
                        var entryDir = 6 // coming from top
                        val firstPt = PointInt(x, y)
                        var secondPt: PointInt? = null

                        contour.add(firstPt)
                        visited[idx] = true

                        var searchDir = (entryDir + 1) % 8
                        var maxSteps = w * h
                        while (maxSteps-- > 0) {
                            var foundNext = false
                            for (step in 0 until 8) {
                                val ndir = (searchDir + step) % 8
                                val nx = cx + dx[ndir]
                                val ny = cy + dy[ndir]
                                if (nx in 0 until w && ny in 0 until h && mask[ny * w + nx]) {
                                    val nextPt = PointInt(nx, ny)
                                    if (contour.size == 1) {
                                        secondPt = nextPt
                                    } else if (cx == firstPt.x && cy == firstPt.y && nextPt == secondPt) {
                                        // Completed cycle
                                        foundNext = false
                                        break
                                    }
                                    contour.add(nextPt)
                                    visited[ny * w + nx] = true
                                    searchDir = (ndir + 5) % 8 // backtrack
                                    cx = nx
                                    cy = ny
                                    foundNext = true
                                    break
                                }
                            }
                            if (!foundNext) break
                            if (cx == firstPt.x && cy == firstPt.y && contour.size > 2) {
                                break
                            }
                        }

                        if (contour.size >= 3) {
                            contours.add(contour)
                        }
                    }
                }
            }
        }
        return contours
    }
}
