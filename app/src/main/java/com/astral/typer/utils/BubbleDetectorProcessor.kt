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
import java.util.ArrayList
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

class BubbleDetectorProcessor(private val context: Context) {

    companion object {
        private const val MODEL_URL = "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/main/detector.onnx"
        private const val MODEL_FILENAME = "detector.onnx"
        private const val CONNECT_TIMEOUT = 30000 // 30 seconds
        private const val READ_TIMEOUT = 30000 // 30 seconds
        private const val USER_AGENT = "AstralTyper/1.0"

        // Model Constants (Assuming standard YOLO input/output)
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f

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

                    if (redirects >= maxRedirects) {
                        Log.e("BubbleDetector", "Too many redirects")
                        return@withContext false
                    }
                    val location = connection!!.getHeaderField("Location")
                    if (location != null) {
                        urlStr = location
                        redirects++
                        connection!!.disconnect()
                        continue
                    } else {
                        Log.e("BubbleDetector", "Redirect with no Location header")
                        return@withContext false
                    }
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    break
                } else {
                     Log.e("BubbleDetector", "Server returned HTTP $responseCode ${connection!!.responseMessage}")
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
                try {
                     tmpFile.copyTo(file, overwrite = true)
                     tmpFile.delete()
                } catch (e: Exception) {
                    Log.e("BubbleDetector", "Failed to rename or copy temp file", e)
                    return@withContext false
                }
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
                Log.w("BubbleDetector", "Failed to set optimization options", e)
            }
            ortSession = ortEnvironment!!.createSession(modelFile.absolutePath, sessionOptions)
        }
        return ortSession!!
    }

    private fun closeSession() {
        try {
            ortSession?.close()
            ortSession = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun detect(bitmap: Bitmap): List<RectF> = withContext(Dispatchers.Default) {
        if (!isModelAvailable()) return@withContext emptyList()

        // Sliding Window Logic
        val allBoxes = ArrayList<RectF>()

        val w = bitmap.width
        val h = bitmap.height
        val tileSize = INPUT_SIZE // 640
        val overlap = (tileSize * 0.2f).toInt()
        val stride = tileSize - overlap

        // Calculate number of tiles
        // We iterate with stride.

        try {
            val session = getSession()
            val env = OrtEnvironment.getEnvironment()

            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    // Extract Tile
                    val realW = min(tileSize, w - x)
                    val realH = min(tileSize, h - y)

                    // Create Tile Bitmap (padded if necessary is automatic if we use createScaledBitmap from a crop)
                    // But we want 640x640 input.
                    // Crop first
                    val tile = Bitmap.createBitmap(bitmap, x, y, realW, realH)
                    // Scale/Pad to INPUT_SIZE
                    // YOLO expects square input usually, or we pad to square.
                    // For detection coordinates to be accurate, we should preserve aspect ratio or know the scale.
                    // Simplest for tiling: Crop 640x640 from source (if possible).
                    // If source < 640, pad with gray.

                    val inputBitmap = if (realW == tileSize && realH == tileSize) {
                         tile
                    } else {
                         // Pad
                         val padded = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
                         val c = Canvas(padded)
                         c.drawColor(android.graphics.Color.GRAY) // Padding color
                         c.drawBitmap(tile, 0f, 0f, null)
                         padded
                    }

                    val boxes = runInference(env, session, inputBitmap)

                    // Shift boxes back to global coordinates
                    for (box in boxes) {
                        // box is relative to 640x640 tile
                        // If we padded, we don't need to scale, just clip to realW/realH
                        if (box.left >= realW) continue
                        if (box.top >= realH) continue

                        box.offset(x.toFloat(), y.toFloat())
                        allBoxes.add(box)
                    }

                    if (inputBitmap != tile) inputBitmap.recycle()
                    if (tile != bitmap) tile.recycle()

                    x += stride
                }
                y += stride
            }

            // Global NMS
            return@withContext nms(allBoxes, IOU_THRESHOLD)

        } catch (e: Throwable) {
            Log.e("BubbleDetector", "Detection failed: ${e.message}", e)
            closeSession() // Reset on error
            return@withContext emptyList()
        }
    }

    private fun runInference(env: OrtEnvironment, session: OrtSession, bitmap: Bitmap): List<RectF> {
        val tensor = bitmapToOnnxTensor(env, bitmap)
        val inputs = mapOf("images" to tensor) // Standard YOLO input name usually "images"

        try {
            val result = session.run(inputs)
            val output = result[0].value as Array<Array<FloatArray>> // [1, 5+, 8400] usually or [1, 8400, 5+]

            // Detect shape
            // YOLOv8: [1, 4+cls, 8400]
            // YOLOv5: [1, 25200, 85]

            val shape = (result[0].info as ai.onnxruntime.TensorInfo).shape
            // shape[1] is channels, shape[2] is anchors? or vice versa

            val boxes = ArrayList<RectF>()

            if (shape.size == 3) {
                val dim1 = shape[1].toInt()
                val dim2 = shape[2].toInt()

                if (dim1 < dim2) {
                     // Likely YOLOv8 [1, 5, 8400] (cx, cy, w, h, conf)
                     // Or [1, 6, 8400] (cx, cy, w, h, conf, cls) or (cx, cy, w, h, cls1, cls2)

                     val outputData = output[0] // [dim1][dim2] -> [5][8400]

                     for (i in 0 until dim2) {
                         // Extract column i
                         val cx = outputData[0][i]
                         val cy = outputData[1][i]
                         val w = outputData[2][i]
                         val h = outputData[3][i]

                         // Confidence
                         // If 5 channels: cx, cy, w, h, conf (assuming 1 class implicit or just objectness)
                         // If > 5: cx, cy, w, h, cls1_conf, cls2_conf...

                         var maxConf = 0f
                         // Iterate remaining channels for max class prob
                         for (c in 4 until dim1) {
                             if (outputData[c][i] > maxConf) {
                                 maxConf = outputData[c][i]
                             }
                         }

                         if (maxConf > CONFIDENCE_THRESHOLD) {
                             val left = cx - w/2
                             val top = cy - h/2
                             val right = cx + w/2
                             val bottom = cy + h/2
                             boxes.add(RectF(left, top, right, bottom))
                         }
                     }
                } else {
                     // Likely YOLOv5 [1, 25200, 85]
                     // Rows are anchors
                     val outputData = output[0] // [25200][85]
                     for (i in 0 until dim1) {
                         val row = outputData[i]
                         val conf = row[4]
                         if (conf > CONFIDENCE_THRESHOLD) {
                             // Check class probs
                             var maxClsConf = 0f
                             for (c in 5 until row.size) {
                                 if (row[c] > maxClsConf) maxClsConf = row[c]
                             }

                             if (maxClsConf * conf > CONFIDENCE_THRESHOLD) {
                                 val cx = row[0]
                                 val cy = row[1]
                                 val w = row[2]
                                 val h = row[3]
                                 val left = cx - w/2
                                 val top = cy - h/2
                                 val right = cx + w/2
                                 val bottom = cy + h/2
                                 boxes.add(RectF(left, top, right, bottom))
                             }
                         }
                     }
                }
            }

            result.close()
            tensor.close()

            // Local NMS (Optional but good for tile)
            return nms(boxes, IOU_THRESHOLD)

        } catch (e: Exception) {
             // Try to handle name mismatch?
             // Usually inputs are "images" or "input"
             // If failed, maybe check input name?
             // For now assume "images" works for standard YOLO export.
             // If key error, we catch it.
             Log.e("BubbleDetector", "Inference error", e)
             tensor.close()
             return emptyList()
        }
    }

    private fun bitmapToOnnxTensor(env: OrtEnvironment, bitmap: Bitmap): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val size = 3 * w * h
        val data = FloatArray(size)
        val channelSize = w * h

        for (i in 0 until channelSize) {
            val p = pixels[i]
            // Standard YOLO normalization 0..1
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

    private fun nms(boxes: List<RectF>, threshold: Float): List<RectF> {
        if (boxes.isEmpty()) return emptyList()

        // Sort by area (proxy for confidence if not available, but here we lost confidence)
        // Ideally we should keep confidence.
        // For simplicity, let's just greedily merge.
        // Or assume order from model implies confidence (often true).

        // Let's modify logic to keep confidence if possible, but our signature returns List<RectF>.
        // We'll trust the order or just standard NMS.

        val mutableBoxes = ArrayList(boxes)
        val selected = ArrayList<RectF>()

        while (mutableBoxes.isNotEmpty()) {
            val current = mutableBoxes.removeAt(0)
            selected.add(current)

            val iterator = mutableBoxes.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (iou(current, other) > threshold) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)

        if (left < right && top < bottom) {
            val intersection = (right - left) * (bottom - top)
            val areaA = a.width() * a.height()
            val areaB = b.width() * b.height()
            return intersection / (areaA + areaB - intersection)
        }
        return 0f
    }
}
