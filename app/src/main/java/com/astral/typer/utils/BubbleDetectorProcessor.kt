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
import kotlin.math.max
import kotlin.math.min

class BubbleDetectorProcessor(private val context: Context) {

    companion object {
        // Defined Constants
        private const val INPUT_SIZE = 640
        private const val STRIDE = 512
        private const val CONFIDENCE_THRESHOLD = 0.4f
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

    // --- Core Inference Logic ---

    suspend fun detect(image: Bitmap): List<RectF> = process(image)

    suspend fun process(bitmap: Bitmap): List<RectF> = withContext(Dispatchers.Default) {
        if (!isModelAvailable()) return@withContext emptyList()

        val allBoxes = mutableListOf<Detection>()
        val width = bitmap.width
        val height = bitmap.height

        val session = getSession()
        val env = OrtEnvironment.getEnvironment()

        val xSteps = if (width <= INPUT_SIZE) listOf(0) else (0 until width step STRIDE).toList()
        val ySteps = (0 until height step STRIDE).toList()

        for (y in ySteps) {
            for (x in xSteps) {
                var tileX = x
                var tileY = y

                if (tileX + INPUT_SIZE > width && width >= INPUT_SIZE) tileX = width - INPUT_SIZE
                if (tileY + INPUT_SIZE > height && height >= INPUT_SIZE) tileY = height - INPUT_SIZE

                val tileBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(tileBitmap)

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

                try {
                    val floatBuffer = preProcess(tileBitmap)

                    val tensor = OnnxTensor.createTensor(
                        env,
                        floatBuffer,
                        longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
                    )

                    val inputs = mapOf("images" to tensor)
                    val result = session.run(inputs)

                    val outputs = mutableListOf<Any>()
                    for (entry in result) {
                        outputs.add(entry.value.value)
                    }

                    val tileDetections = parsePredictions(outputs)

                    for (det in tileDetections) {
                        det.rect.offset(tileX.toFloat(), tileY.toFloat())

                        det.rect.left = max(0f, det.rect.left)
                        det.rect.top = max(0f, det.rect.top)
                        det.rect.right = min(width.toFloat(), det.rect.right)
                        det.rect.bottom = min(height.toFloat(), det.rect.bottom)

                        allBoxes.add(det)
                    }

                    result.close()
                    tensor.close()
                } catch (e: Exception) {
                    Log.e("BubbleDetector", "Tile inference failed", e)
                } finally {
                    tileBitmap.recycle()
                }
            }
        }

        val finalBoxes = nonMaximumSuppression(allBoxes)
        return@withContext finalBoxes
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

    data class Detection(val rect: RectF, val score: Float)

    private fun parsePredictions(outputs: List<Any>): List<Detection> {
        val detections = mutableListOf<Detection>()

        var boxes: Array<FloatArray>? = null // [N][4]
        var scores: Array<FloatArray>? = null // [N][C]

        // Helper to unwrap batch dimension and normalize to [N][Features]
        fun normalize(tensor: Any): Array<FloatArray>? {
            if (tensor is Array<*>) {
                val batch = tensor[0] // Assume batch size 1
                if (batch is Array<*>) {
                    if (batch.isNotEmpty() && batch[0] is FloatArray) {
                        @Suppress("UNCHECKED_CAST")
                        val data = batch as Array<FloatArray>

                        val rows = data.size
                        val cols = if (rows > 0) data[0].size else 0

                        // Heuristic for Transpose: RT-DETR queries usually 300 or 1000. Features 4 or 80.
                        // If we see [4, 300] -> transpose to [300, 4]
                        // If we see [80, 300] -> transpose to [300, 80]
                        if (rows < cols && rows < 100) {
                             val transposed = Array(cols) { FloatArray(rows) }
                             for (i in 0 until rows) {
                                 for (j in 0 until cols) {
                                     transposed[j][i] = data[i][j]
                                 }
                             }
                             return transposed
                        }
                        return data
                    }
                }
            }
            return null
        }

        val parsedTensors = outputs.mapNotNull { normalize(it) }

        if (parsedTensors.isEmpty()) return emptyList()

        if (parsedTensors.size >= 2) {
            // Split outputs: Boxes and Scores
            // Find tensor with width 4 for boxes
            boxes = parsedTensors.find { it.isNotEmpty() && it[0].size == 4 }

            // Find score tensor (not the boxes tensor)
            scores = parsedTensors.find { it !== boxes && it.isNotEmpty() }

        } else {
            // Single concatenated output
            val data = parsedTensors[0]
            if (data.isNotEmpty()) {
                val dim = data[0].size
                if (dim > 4) {
                    // Split [cx, cy, w, h, score...]
                    boxes = Array(data.size) { i -> FloatArray(4) { j -> data[i][j] } }
                    scores = Array(data.size) { i -> FloatArray(dim - 4) { j -> data[i][j + 4] } }
                }
            }
        }

        if (boxes == null || scores == null) return emptyList()
        if (boxes!!.size != scores!!.size) return emptyList()

        val numQueries = boxes!!.size
        val numClasses = scores!![0].size

        for (i in 0 until numQueries) {
            val cx = boxes!![i][0]
            val cy = boxes!![i][1]
            val w = boxes!![i][2]
            val h = boxes!![i][3]

            var maxScore = 0f
            // Some models export scores with background class, some without.
            // Usually we just take max across all classes.
            for (j in 0 until numClasses) {
                 if (scores!![i][j] > maxScore) maxScore = scores!![i][j]
            }

            if (maxScore > CONFIDENCE_THRESHOLD) {
                var finalX = cx
                var finalY = cy
                var finalW = w
                var finalH = h

                // Normalize if values are relative (<= 1.0)
                // RT-DETR can output normalized or absolute.
                // Usually normalized.
                if (cx <= 1.05f && cy <= 1.05f && w <= 1.05f && h <= 1.05f) {
                     finalX *= INPUT_SIZE
                     finalY *= INPUT_SIZE
                     finalW *= INPUT_SIZE
                     finalH *= INPUT_SIZE
                }

                val left = finalX - finalW / 2f
                val top = finalY - finalH / 2f
                val right = finalX + finalW / 2f
                val bottom = finalY + finalH / 2f

                detections.add(Detection(RectF(left, top, right, bottom), maxScore))
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
