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
                    val output = result[0].value

                    val tileDetections = parsePredictions(output)

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

    private fun parsePredictions(output: Any): List<Detection> {
        val detections = mutableListOf<Detection>()

        var data: Array<FloatArray>? = null
        var isTransposed = false

        if (output is Array<*>) {
            val batch = output[0]
            if (batch is Array<*>) {
                if (batch[0] is FloatArray) {
                    val d = batch as Array<FloatArray>
                    val dim1 = d.size
                    val dim2 = d[0].size

                    // RT-DETR: [300, 7] (boxes, features) usually.
                    // If [7, 300], then dim1 < dim2.
                    if (dim1 < dim2) {
                        isTransposed = true
                        data = d
                    } else {
                        data = d
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

                var maxScore = 0f
                // Classes start from index 4
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

                var maxScore = 0f
                // Classes start from index 4
                for (j in 4 until cols) {
                    if (data[i][j] > maxScore) maxScore = data[i][j]
                }
                score = maxScore
            }

            if (score > CONFIDENCE_THRESHOLD) {
                var finalX = cx
                var finalY = cy
                var finalW = w
                var finalH = h

                // Sanity check for normalization
                if (cx <= 1.0f && cy <= 1.0f && w <= 1.0f && h <= 1.0f && score > 0.0f) {
                     finalX *= INPUT_SIZE
                     finalY *= INPUT_SIZE
                     finalW *= INPUT_SIZE
                     finalH *= INPUT_SIZE
                }

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
