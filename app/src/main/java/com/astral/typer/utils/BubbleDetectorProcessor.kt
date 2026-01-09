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
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min

class BubbleDetectorProcessor(private val context: Context) {

    companion object {
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.35f
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
        // The model likely requires "images" and "orig_target_sizes"
        val inputNames = session.inputNames
        val imageInputName = inputNames.find { it.contains("image", ignoreCase = true) } ?: "images"
        val sizeInputName = inputNames.find { it.contains("size", ignoreCase = true) || it.contains("orig", ignoreCase = true) } ?: "orig_target_sizes"

        // Resize to 640x640 for the model input
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        try {
            // Prepare Image Tensor: (1, 3, 640, 640)
            val floatBuffer = preProcess(resizedBitmap)
            val imageTensor = OnnxTensor.createTensor(
                env,
                floatBuffer,
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            )

            // Prepare Size Tensor: (1, 2) with [width, height]
            // Python: orig_size = np.array([[w, h]], dtype=np.int64)
            val sizeBuffer = LongBuffer.allocate(2)
            sizeBuffer.put(width.toLong())
            sizeBuffer.put(height.toLong())
            sizeBuffer.flip()
            val sizeTensor = OnnxTensor.createTensor(env, sizeBuffer, longArrayOf(1, 2))

            // Run Inference
            val inputs = mapOf(
                imageInputName to imageTensor,
                sizeInputName to sizeTensor
            )

            val result = session.run(inputs)

            // Extract outputs
            // Expected 3 outputs: labels, boxes, scores
            val outputs = mutableListOf<Any>()
            for (entry in result) {
                outputs.add(entry.value.value)
            }

            val detections = parsePredictions(outputs)

            result.close()
            imageTensor.close()
            sizeTensor.close()

            return@withContext detections

        } catch (e: Exception) {
            Log.e("BubbleDetector", "Inference failed: ${e.message}", e)
            return@withContext emptyList()
        } finally {
            if (resizedBitmap != bitmap) {
                resizedBitmap.recycle()
            }
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

    private fun parsePredictions(outputs: List<Any>): List<RectF> {
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

        return nonMaximumSuppression(detections)
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
