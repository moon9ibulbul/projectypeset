package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Color
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
import java.util.LinkedList
import java.util.Queue

class MiganProcessor(private val context: Context) {

    companion object {
        private const val TRAINED_SIZE = 512
        private const val MODEL_URL = "https://huggingface.co/bulbulmoon/lama/resolve/main/migan.onnx"
        private const val MODEL_FILENAME = "migan.onnx"
        private const val CONNECT_TIMEOUT = 30000 // 30 seconds
        private const val READ_TIMEOUT = 30000 // 30 seconds
        private const val USER_AGENT = "AstralTyper/1.0"

        // Caching environment and session to avoid reloading overhead
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
                        Log.e("MiganProcessor", "Too many redirects")
                        return@withContext false
                    }
                    val location = connection!!.getHeaderField("Location")
                    if (location != null) {
                        urlStr = location
                        redirects++
                        connection!!.disconnect()
                        continue
                    } else {
                        Log.e("MiganProcessor", "Redirect with no Location header")
                        return@withContext false
                    }
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    break
                } else {
                     Log.e("MiganProcessor", "Server returned HTTP $responseCode ${connection!!.responseMessage}")
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

            // Try rename, if fails, try copy and delete
            if (!tmpFile.renameTo(file)) {
                // Fallback for rename failure
                try {
                     tmpFile.copyTo(file, overwrite = true)
                     tmpFile.delete()
                } catch (e: Exception) {
                    Log.e("MiganProcessor", "Failed to rename or copy temp file", e)
                    return@withContext false
                }
            }

            // Clear cache to reload new model if session exists
            closeSession()
            return@withContext true

        } catch (e: Exception) {
            Log.e("MiganProcessor", "Download failed", e)
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
            // Optimization options
            try {
                 sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                 sessionOptions.setInterOpNumThreads(4)
                 sessionOptions.setIntraOpNumThreads(4)
            } catch (e: Exception) {
                Log.w("MiganProcessor", "Failed to set optimization options", e)
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

    suspend fun inpaint(image: Bitmap, mask: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        if (!isModelAvailable()) return@withContext null

        try {
            val env = OrtEnvironment.getEnvironment()
            val session = getSession()

            // 0. Detect separate mask blobs (connected components)
            val maskRects = getSeparateMaskRects(mask)

            if (maskRects.isEmpty()) {
                return@withContext image // Nothing to mask
            }

            // We will accumulate results into this bitmap
            val resultBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Draw base
            canvas.drawBitmap(image, 0f, 0f, paint)

            // Loop through each distinct mask area
            for (rect in maskRects) {
                processRegion(image, mask, rect, session, env, canvas, paint)
            }

            return@withContext resultBitmap

        } catch (e: Exception) {
            Log.e("MiganProcessor", "Inference failed", e)
            // Force close session on error to allow retry
            closeSession()
            return@withContext null
        }
    }

    private fun processRegion(
        originalImage: Bitmap,
        originalMask: Bitmap,
        maskRect: android.graphics.Rect,
        session: OrtSession,
        env: OrtEnvironment,
        canvas: Canvas,
        paint: Paint
    ) {
        val size = (kotlin.math.max(maskRect.width(), maskRect.height()) * 3)
        val cx = maskRect.centerX()
        val cy = maskRect.centerY()
        val halfSize = size / 2

        var left = cx - halfSize
        var top = cy - halfSize
        var right = cx + halfSize
        var bottom = cy + halfSize

        val imgW = originalImage.width
        val imgH = originalImage.height

        // Adjust bounds to fit image
        if (right - left > imgW) {
            left = 0
            right = imgW
        } else {
            if (left < 0) {
                val diff = -left
                left += diff
                right += diff
            }
            if (right > imgW) {
                val diff = right - imgW
                left -= diff
                right -= diff
            }
        }

        if (bottom - top > imgH) {
            top = 0
            bottom = imgH
        } else {
            if (top < 0) {
                val diff = -top
                top += diff
                bottom += diff
            }
            if (bottom > imgH) {
                val diff = bottom - imgH
                top -= diff
                bottom -= diff
            }
        }

        left = left.coerceIn(0, imgW)
        right = right.coerceIn(0, imgW)
        top = top.coerceIn(0, imgH)
        bottom = bottom.coerceIn(0, imgH)

        if (right <= left || bottom <= top) return

        val cropRect = android.graphics.Rect(left, top, right, bottom)

        val cropImage = Bitmap.createBitmap(originalImage, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        val cropMask = Bitmap.createBitmap(originalMask, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())

        val inputImage = Bitmap.createScaledBitmap(cropImage, TRAINED_SIZE, TRAINED_SIZE, true)
        val inputMask = Bitmap.createScaledBitmap(cropMask, TRAINED_SIZE, TRAINED_SIZE, false)

        var tensorImg: OnnxTensor? = null
        var tensorMask: OnnxTensor? = null
        var resultOrt: OrtSession.Result? = null

        try {
            tensorImg = bitmapToOnnxTensor(env, inputImage, inputMask)
            tensorMask = bitmapToMaskTensor(env, inputMask)

            // Dynamic names detection
            val inputNames = session.inputNames.toList()
            val imageInputName = inputNames.find { it.contains("image", ignoreCase = true) } ?: inputNames[0]
            val maskInputName = inputNames.find { it.contains("mask", ignoreCase = true) } ?: inputNames.getOrNull(1) ?: "mask"

            val inputs = mutableMapOf<String, OnnxTensor>()
            inputs[imageInputName] = tensorImg
            inputs[maskInputName] = tensorMask

            resultOrt = session.run(inputs)
            val outputTensor = resultOrt[0] as OnnxTensor

            val outputBitmap = outputTensorToBitmap(outputTensor)

            val outputCrop = Bitmap.createScaledBitmap(outputBitmap, cropRect.width(), cropRect.height(), true)

            val sc = canvas.saveLayer(
                cropRect.left.toFloat(),
                cropRect.top.toFloat(),
                cropRect.right.toFloat(),
                cropRect.bottom.toFloat(),
                null
            )

            paint.xfermode = null
            canvas.drawBitmap(outputCrop, cropRect.left.toFloat(), cropRect.top.toFloat(), paint)

            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawBitmap(cropMask, cropRect.left.toFloat(), cropRect.top.toFloat(), paint)

            paint.xfermode = null
            canvas.restoreToCount(sc)

        } catch (e: Exception) {
            Log.e("MiganProcessor", "Error processing region $maskRect", e)
        } finally {
            try {
                resultOrt?.close()
                tensorImg?.close()
                tensorMask?.close()
                cropImage.recycle()
                cropMask.recycle()
                inputImage.recycle()
                inputMask.recycle()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun getSeparateMaskRects(mask: Bitmap): List<android.graphics.Rect> {
        val w = mask.width
        val h = mask.height
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)

        val visited = BooleanArray(w * h)
        val rects = ArrayList<android.graphics.Rect>()

        val queue: Queue<Int> = LinkedList()

        for (i in pixels.indices) {
            if (!visited[i] && (pixels[i] ushr 24) > 0) {
                var minX = w
                var maxX = -1
                var minY = h
                var maxY = -1

                visited[i] = true
                queue.add(i)

                while (!queue.isEmpty()) {
                    val currIdx = queue.remove()
                    val cx = currIdx % w
                    val cy = currIdx / w

                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    if (cx > 0) {
                        val nIdx = currIdx - 1
                        if (!visited[nIdx] && (pixels[nIdx] ushr 24) > 0) {
                            visited[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                    if (cx < w - 1) {
                        val nIdx = currIdx + 1
                        if (!visited[nIdx] && (pixels[nIdx] ushr 24) > 0) {
                            visited[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                    if (cy > 0) {
                        val nIdx = currIdx - w
                        if (!visited[nIdx] && (pixels[nIdx] ushr 24) > 0) {
                            visited[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                    if (cy < h - 1) {
                        val nIdx = currIdx + w
                        if (!visited[nIdx] && (pixels[nIdx] ushr 24) > 0) {
                            visited[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                }

                if (maxX >= minX && maxY >= minY) {
                    rects.add(android.graphics.Rect(minX, minY, maxX + 1, maxY + 1))
                }
            }
        }

        return rects
    }

    private fun bitmapToMaskTensor(env: OrtEnvironment, bitmap: Bitmap): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val data = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = (p shr 24) and 0xFF
            // MI-GAN documentation: 255 (1.0) denotes known region, 0 (0.0) denotes masked region.
            data[i] = if (alpha > 0) 0f else 1f
        }

        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1, 1, h.toLong(), w.toLong())
        )
    }

    private fun bitmapToOnnxTensor(env: OrtEnvironment, bitmap: Bitmap, mask: Bitmap): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val maskPixels = IntArray(w * h)
        mask.getPixels(maskPixels, 0, w, 0, 0, w, h)

        val size = 3 * w * h
        val data = FloatArray(size)
        val channelSize = w * h

        for (i in 0 until channelSize) {
            val p = pixels[i]
            val m = maskPixels[i]
            val isMasked = (m ushr 24) > 0

            // Normalize to [-1, 1] and set masked area to 0.0 as per MI-GAN training
            var r = ((p shr 16) and 0xFF) / 127.5f - 1f
            var g = ((p shr 8) and 0xFF) / 127.5f - 1f
            var b = (p and 0xFF) / 127.5f - 1f

            if (isMasked) {
                r = 0f
                g = 0f
                b = 0f
            }

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

    private fun outputTensorToBitmap(tensor: OnnxTensor): Bitmap {
        val buffer = tensor.floatBuffer
        val data = FloatArray(buffer.capacity())
        buffer.get(data)

        val width = TRAINED_SIZE
        val height = TRAINED_SIZE
        val size = width * height
        val pixels = IntArray(size)

        for (i in 0 until size) {
            // Denormalize from [-1, 1] to [0, 255]
            val r = ((data[i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
            val g = ((data[size + i] + 1f) * 127.5f).toInt().coerceIn(0, 255)
            val b = ((data[2 * size + i] + 1f) * 127.5f).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
