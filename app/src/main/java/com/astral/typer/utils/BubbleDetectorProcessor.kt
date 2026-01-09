package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
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
        // Defined Constants
        private const val INPUT_SIZE = 640
        private const val STRIDE = 512
        private const val CONFIDENCE_THRESHOLD = 0.4f // Updated from 0.25
        private const val IOU_THRESHOLD = 0.5f

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

    // --- Model Management (Preserved) ---

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

    // --- Core Inference Logic (Rewritten) ---

    // Renamed to 'process' as requested in prompt, aliased by 'detect' for compatibility
    suspend fun detect(image: Bitmap): List<RectF> = process(image)

    suspend fun process(bitmap: Bitmap): List<RectF> = withContext(Dispatchers.Default) {
        if (!isModelAvailable()) return@withContext emptyList()

        val allBoxes = mutableListOf<Detection>()
        val width = bitmap.width
        val height = bitmap.height

        val session = getSession()
        val env = OrtEnvironment.getEnvironment() // Environment is singleton

        // Sliding Window Loop (2D to cover width > 640)
        // Prompt requirement: "Create a loop to traverse the image from top to bottom (y from 0 to height step STRIDE)."
        // Also handling X explicitly for robustness.

        val xSteps = if (width <= INPUT_SIZE) listOf(0) else (0 until width step STRIDE).toList()
        val ySteps = (0 until height step STRIDE).toList()

        for (y in ySteps) {
            for (x in xSteps) {
                // Crop Tile
                // "Handle the bottom edge case where the remaining height < 640 by padding or shifting the crop up"
                // Shifting up is better to avoid padding artifacts if possible, but let's stick to standard crop.
                // The prompt says: "Crop: Extract a bitmap tile of size INPUT_SIZE x INPUT_SIZE at the current y."
                // To guarantee 640x640 input without resizing (scaling), we should create a 640x640 bitmap.
                // If we are at the edge, we draw the partial image into the 640x640 buffer (effectively padding with 0/black).

                var tileX = x
                var tileY = y

                // Alternative: Shift crop to fit if possible (avoid padding)
                if (tileX + INPUT_SIZE > width && width >= INPUT_SIZE) tileX = width - INPUT_SIZE
                if (tileY + INPUT_SIZE > height && height >= INPUT_SIZE) tileY = height - INPUT_SIZE

                // Create input bitmap
                val tileBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(tileBitmap)

                // Draw portion of original bitmap
                val srcRect = android.graphics.Rect(
                    tileX,
                    tileY,
                    min(tileX + INPUT_SIZE, width),
                    min(tileY + INPUT_SIZE, height)
                )
                val dstRect = android.graphics.Rect(
                    0,
                    0,
                    srcRect.width(),
                    srcRect.height()
                )
                canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                // Remaining area is transparent/black (default for new Bitmap)

                try {
                    // Pre-process
                    val floatBuffer = preProcess(tileBitmap)

                    // Create Tensor
                    // Shape: [1, 3, 640, 640]
                    val tensor = OnnxTensor.createTensor(
                        env,
                        floatBuffer,
                        longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
                    )

                    // Inference
                    val inputs = mapOf("images" to tensor)
                    val result = session.run(inputs)

                    // Parse
                    // Model output depends on architecture.
                    // Assuming YOLOv8 format [1, 8400, 5+] or [1, 5+, 8400].
                    // Or "The model returns boxes in the range [0..640]".
                    val output = result[0].value

                    val tileDetections = parsePredictions(output)

                    // Map Coordinates to Global
                    for (det in tileDetections) {
                        // "Must add the current loop offset (currentY) to the box.top and box.bottom"
                        // Also currentX
                        det.rect.offset(tileX.toFloat(), tileY.toFloat())

                        // Clip to image bounds
                        det.rect.left = max(0f, det.rect.left)
                        det.rect.top = max(0f, det.rect.top)
                        det.rect.right = min(width.toFloat(), det.rect.right)
                        det.rect.bottom = min(height.toFloat(), det.rect.bottom)

                        allBoxes.add(det)
                    }

                    // Cleanup
                    result.close()
                    tensor.close()
                } catch (e: Exception) {
                    Log.e("BubbleDetector", "Tile inference failed", e)
                } finally {
                    tileBitmap.recycle()
                }
            }
        }

        // Global NMS
        val finalBoxes = nonMaximumSuppression(allBoxes)
        return@withContext finalBoxes
    }

    private fun preProcess(bitmap: Bitmap): FloatBuffer {
        // "Allocate a FloatBuffer of size 1 * 3 * 640 * 640"
        val count = 1 * 3 * INPUT_SIZE * INPUT_SIZE
        val floatBuffer = FloatBuffer.allocate(count)

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // NCHW Formatting: RRR... GGG... BBB...
        // Loop through pixels. Extract R, G, B. Normalize / 255.0f.

        // Optimization: Use separate arrays or loop 3 times?
        // Single loop with offset is cache friendly for reading pixels, but jumping writes.
        // Or 3 loops over pixels?
        // Let's do 3 separate passes or fill 3 separate buffers then merge? FloatBuffer is contiguous.
        // We need to write R section, then G section, then B section.

        val stride = INPUT_SIZE * INPUT_SIZE

        // It's faster to iterate pixels once and write to specific indices if using array, but FloatBuffer is direct put.
        // Let's use a float array then wrap.
        val floatArray = FloatArray(count)

        for (i in 0 until stride) {
            val pixel = pixels[i]
            // R
            floatArray[i] = ((pixel shr 16) and 0xFF) / 255.0f
            // G
            floatArray[stride + i] = ((pixel shr 8) and 0xFF) / 255.0f
            // B
            floatArray[2 * stride + i] = (pixel and 0xFF) / 255.0f
        }

        floatBuffer.put(floatArray)
        floatBuffer.flip() // Prepare for reading
        return floatBuffer
    }

    // Data class for internal usage
    data class Detection(val rect: RectF, val score: Float)

    private fun parsePredictions(output: Any): List<Detection> {
        val detections = mutableListOf<Detection>()

        // Logic to handle YOLOv8 / YOLOv5 output shapes
        // Usually [1, features, boxes] (e.g., [1, 5, 8400]) for YOLOv8
        // Or [1, boxes, features] (e.g., [1, 25200, 85]) for YOLOv5

        var data: Array<FloatArray>? = null
        var isTransposed = false // true if [features, boxes]

        if (output is Array<*>) {
            val batch = output[0]
            if (batch is Array<*>) {
                if (batch[0] is FloatArray) {
                    val d = batch as Array<FloatArray>
                    val dim1 = d.size
                    val dim2 = d[0].size

                    // Heuristic: Boxes dim is usually large (e.g. > 1000)
                    if (dim1 < dim2) {
                        isTransposed = true // [features, boxes]
                        data = d
                    } else {
                        data = d // [boxes, features]
                    }
                }
            }
        }

        if (data == null) return emptyList()

        val rows = data.size
        val cols = data[0].size
        val numBoxes = if (isTransposed) cols else rows

        for (i in 0 until numBoxes) {
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

                // Score: Max of class scores. Assuming index 4+ are classes.
                // For "comic-text-and-bubble-detector", it might be specific.
                // Assuming standard YOLOv8 (xywh + classes)
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

                // YOLOv5 style: index 4 is objectness, 5+ are classes
                val objScore = if (cols > 5) data[i][4] else 1.0f
                var maxClassScore = 0f
                val startClass = if (cols > 5) 5 else 4
                for (j in startClass until cols) {
                    if (data[i][j] > maxClassScore) maxClassScore = data[i][j]
                }
                score = maxClassScore * objScore
            }

            if (score > CONFIDENCE_THRESHOLD) {
                // "The model returns boxes in the range [0..640]"
                // Some models return normalized [0..1]. We need to check or enforce.
                // Prompt says: "Map Coordinates: The model returns boxes in the range [0..640]."
                // So we assume pixel coordinates relative to 640x640.

                // However, if the model output IS normalized (common in YOLO), we must multiply by INPUT_SIZE.
                // Let's add a safe check. If boxes are all small < 1.0, they are likely normalized.
                // But prompt is explicit. I will trust prompt BUT add a sanity check just in case.
                // "Map Coordinates: The model returns boxes in the range [0..640]." -> implies pixel coords.

                var finalX = cx
                var finalY = cy
                var finalW = w
                var finalH = h

                // Sanity check: If values are normalized, scale them.
                if (cx <= 1.0f && cy <= 1.0f && w <= 1.0f && h <= 1.0f && score > 0.5f) {
                     // Likely normalized
                     finalX *= INPUT_SIZE
                     finalY *= INPUT_SIZE
                     finalW *= INPUT_SIZE
                     finalH *= INPUT_SIZE
                }

                // Convert Center-WH to Top-Left-Bottom-Right
                val left = finalX - finalW / 2f
                val top = finalY - finalH / 2f
                val right = finalX + finalW / 2f
                val bottom = finalY + finalH / 2f

                detections.add(Detection(RectF(left, top, right, bottom), score))
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
}
