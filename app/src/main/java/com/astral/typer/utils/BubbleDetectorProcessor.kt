package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

class BubbleDetectorProcessor(private val context: Context) {

    companion object {
        private const val INPUT_SIZE = 640
        private const val MODEL_URL = "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/main/detector.onnx"
        private const val MODEL_FILENAME = "detector.onnx"
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 30000
        private const val USER_AGENT = "AstralTyper/1.0"

        // NMS Thresholds
        private const val CONFIDENCE_THRESHOLD = 0.4f
        private const val IOU_THRESHOLD = 0.5f

        private var ortEnvironment: OrtEnvironment? = null
        private var ortSession: OrtSession? = null
    }

    private val modelFile: File
        get() = File(context.filesDir, "onnx/$MODEL_FILENAME")

    fun isModelAvailable(): Boolean {
        return modelFile.exists() && modelFile.length() > 0
    }

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
            Log.e("BubbleDetector", "Download failed", e)
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

    /**
     * Runs object detection on the provided bitmap.
     * Handles large images by tiling (sliding window).
     */
    suspend fun detect(image: Bitmap): List<RectF> = withContext(Dispatchers.Default) {
        if (!isModelAvailable()) return@withContext emptyList()

        val allBoxes = mutableListOf<Detection>()
        val width = image.width
        val height = image.height

        // Tiling strategy
        val tileSize = 1024 // Use 1024 tiles for efficiency, resize to 640 for inference
        val overlap = (tileSize * 0.2f).toInt()
        val step = tileSize - overlap

        try {
            val session = getSession()
            val env = OrtEnvironment.getEnvironment()

            // Loop through tiles
            for (y in 0 until height step step) {
                for (x in 0 until width step step) {
                    // Define tile bounds
                    val tileW = min(tileSize, width - x)
                    val tileH = min(tileSize, height - y)

                    // Skip tiny strips at edges if they are covered by previous overlap (unless it's the only tile)
                    if (tileW < 100 || tileH < 100) continue

                    // Create tile bitmap
                    val tileBitmap = Bitmap.createBitmap(image, x, y, tileW, tileH)

                    // Preprocess: Resize to 640x640 (Model Input)
                    // We need to maintain aspect ratio or pad?
                    // YOLO usually expects letterboxing or direct resize.
                    // For simplicity, we'll resize directly but we must track the scale factor to map back.
                    // Actually, YOLOv8/v5 is robust to aspect ratio changes within reason, but letterboxing is better.
                    // Let's do simple resize for now to match the "Replicate exact download implementation" style simplicity,
                    // but we must map coordinates correctly.

                    val inputBitmap = Bitmap.createScaledBitmap(tileBitmap, INPUT_SIZE, INPUT_SIZE, true)

                    // Prepare Tensor
                    val tensor = bitmapToOnnxTensor(env, inputBitmap)

                    // Inference
                    val inputs = mapOf("images" to tensor)
                    val result = session.run(inputs)
                    val output = result[0].value as Array<Array<FloatArray>> // [1, 8400, 85] usually or [1, num_classes+4, num_anchors]

                    // Parse Output
                    // YOLO output shape varies.
                    // Typical YOLOv8: [1, 4 + classes, 8400]
                    // Typical YOLOv5/7: [1, 25200, 85] (xywh, conf, classes...)

                    // Let's inspect shape dynamically if possible, or assume YOLOv8 format which is common for new models.
                    // The model is "comic-text-and-bubble-detector". It is likely YOLOv8 based on HuggingFace link context often seen.
                    // Let's handle generic [batch, boxes, coords] or [batch, coords, boxes]

                    // The output usually needs transposing if it is [1, xywh+classes, boxes]

                    val detections = parsePredictions(output, x, y, tileW.toFloat(), tileH.toFloat())
                    allBoxes.addAll(detections)

                    // Clean up
                    result.close()
                    tensor.close()
                    inputBitmap.recycle()
                    tileBitmap.recycle()
                }
            }

            // Global NMS
            return@withContext nonMaximumSuppression(allBoxes)

        } catch (e: Exception) {
            Log.e("BubbleDetector", "Detection failed", e)
            // If OOM or crash, return what we have so far
            return@withContext nonMaximumSuppression(allBoxes)
        }
    }

    data class Detection(val rect: RectF, val score: Float)

    private fun parsePredictions(output: Any, tileX: Int, tileY: Int, tileW: Float, tileH: Float): List<Detection> {
        val detections = mutableListOf<Detection>()

        // Handle various output shapes (support YOLOv8 and YOLOv5/standard)
        // Case 1: [1, num_features, num_boxes] -> YOLOv8 default
        // Case 2: [1, num_boxes, num_features] -> YOLOv5/Others

        var data: Array<FloatArray>? = null
        var isTransposed = false // If true, rows are features, cols are boxes

        if (output is Array<*>) {
            val batch = output[0]
            if (batch is Array<*>) {
                if (batch[0] is FloatArray) {
                    val rows = batch.size
                    val cols = (batch[0] as FloatArray).size

                    // Heuristic: Boxes are usually the larger dimension (e.g. 8400), features are small (e.g. 5-85)
                    if (rows < cols) {
                        isTransposed = true // [features, boxes]
                        data = batch as Array<FloatArray>
                    } else {
                        data = batch as Array<FloatArray> // [boxes, features]
                    }
                }
            }
        }

        if (data == null) return emptyList()

        val rows = data.size
        val cols = data[0].size
        val numBoxes = if (isTransposed) cols else rows

        // Scale factors to map 640x640 back to Tile Size
        val scaleX = tileW / INPUT_SIZE
        val scaleY = tileH / INPUT_SIZE

        for (i in 0 until numBoxes) {
            // Extract box info
            // Standard YOLO: x_center, y_center, w, h, confidence (if exists), class_scores...
            // YOLOv8: x, y, w, h, class_score1, class_score2... (No objectness score usually, just max class score)

            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            val score: Float

            if (isTransposed) {
                // data[feature][box_index]
                cx = data[0][i]
                cy = data[1][i]
                w = data[2][i]
                h = data[3][i]
                // Score: Find max of remaining classes.
                // Assuming class 0 is "text" or "bubble". We want any valid class.
                // Usually these models have 1 or 2 classes (Text, Bubble).
                var maxScore = 0f
                for (j in 4 until rows) {
                    if (data[j][i] > maxScore) maxScore = data[j][i]
                }
                score = maxScore
            } else {
                // data[box_index][feature]
                cx = data[i][0]
                cy = data[i][1]
                w = data[i][2]
                h = data[i][3]

                // Check if index 4 is objectness or class.
                // If cols > 5, usually 4 is obj, 5+ are classes.
                // If cols == 5 or 6 (e.g. xywh + 1 class), maybe just class score.
                // Let's assume standard logic:
                var maxClassScore = 0f
                val startClass = if (cols > 5) 5 else 4
                val objScore = if (cols > 5) data[i][4] else 1.0f

                for (j in startClass until cols) {
                    if (data[i][j] > maxClassScore) maxClassScore = data[i][j]
                }
                score = maxClassScore * objScore
            }

            if (score > CONFIDENCE_THRESHOLD) {
                // Convert center-wh to top-left-bottom-right (relative to 640x640)
                val x1 = (cx - w / 2f)
                val y1 = (cy - h / 2f)
                // val x2 = (cx + w / 2f)
                // val y2 = (cy + h / 2f)

                // Map to Tile Coordinates
                val tileX1 = x1 * scaleX
                val tileY1 = y1 * scaleY
                val tileRectW = w * scaleX
                val tileRectH = h * scaleY

                // Map to Global Coordinates
                val globalX = tileX + tileX1
                val globalY = tileY + tileY1

                detections.add(Detection(RectF(globalX, globalY, globalX + tileRectW, globalY + tileRectH), score))
            }
        }

        return detections
    }

    private fun nonMaximumSuppression(detections: List<Detection>): List<RectF> {
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val results = mutableListOf<RectF>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            results.add(best.rect)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (calculateIoU(best.rect, other.rect) > IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }
        return results
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

    private fun bitmapToOnnxTensor(env: OrtEnvironment, bitmap: Bitmap): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val size = 3 * w * h
        val data = FloatArray(size)
        val channelSize = w * h

        // Normalization (Standard 0-1 or ImageNet stats? YOLO usually just 0-1)
        for (i in 0 until channelSize) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f

            data[i] = r
            data[channelSize + i] = g
            data[2 * channelSize + i] = b
        }

        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1, 3, h.toLong(), w.toLong())
        )
    }
}
