package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer

class MiganProcessor(private val context: Context) {

    companion object {
        private const val TRAINED_SIZE = 512
        private const val MODEL_URL = "https://huggingface.co/bulbulmoon/lama/resolve/main/migan_512.onnx"
        private const val MODEL_FILENAME = "migan_512.onnx"
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

            connection!!.inputStream.buffered().use { input ->
                FileOutputStream(tmpFile).use { output ->
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
                }
            }
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
            try {
                 sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                 sessionOptions.setInterOpNumThreads(4)
                 sessionOptions.setIntraOpNumThreads(4)
            } catch (e: Exception) {
                Log.w("MiganProcessor", "Failed to set optimization options", e)
            }
            try {
                ortSession = ortEnvironment!!.createSession(modelFile.absolutePath, sessionOptions)
            } finally {
                try {
                    sessionOptions.close()
                } catch (e: Exception) {
                    Log.w("MiganProcessor", "Failed to close session options", e)
                }
            }
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

        var cropImage: Bitmap? = null
        var cropMask: Bitmap? = null
        var inputImage: Bitmap? = null
        var inputMask: Bitmap? = null
        var outputBitmap: Bitmap? = null
        var outputCrop: Bitmap? = null
        var tensorInput: OnnxTensor? = null
        var resultOrt: OrtSession.Result? = null

        try {
            val env = OrtEnvironment.getEnvironment()
            val session = getSession()

            // Find a single bounding box around the active mask using row-by-row scanning (no heap bloat)
            val w = mask.width
            val h = mask.height
            val rowPixels = IntArray(w)

            var minX = w
            var maxX = -1
            var minY = h
            var maxY = -1

            for (y in 0 until h) {
                mask.getPixels(rowPixels, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val pixel = rowPixels[x]
                    val alpha = (pixel ushr 24) and 0xFF
                    val red = (pixel ushr 16) and 0xFF
                    if (alpha > 0 || red > 120) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }

            if (maxX < 0) {
                return@withContext image // Nothing to mask
            }

            val maskRect = android.graphics.Rect(minX, minY, maxX + 1, maxY + 1)

            // Calculate padded square crop (smart crop) based on maskRect
            val size = (kotlin.math.max(maskRect.width(), maskRect.height()) * 3)
            val cx = maskRect.centerX()
            val cy = maskRect.centerY()
            val halfSize = size / 2

            var left = cx - halfSize
            var top = cy - halfSize
            var right = cx + halfSize
            var bottom = cy + halfSize

            val imgW = image.width
            val imgH = image.height

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

            if (right <= left || bottom <= top) {
                return@withContext image
            }

            val cropRect = android.graphics.Rect(left, top, right, bottom)

            // 1. Create Crops
            cropImage = Bitmap.createBitmap(image, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
            cropMask = Bitmap.createBitmap(mask, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())

            // 2. Resize Input for Model
            inputImage = Bitmap.createScaledBitmap(cropImage, TRAINED_SIZE, TRAINED_SIZE, true)
            inputMask = Bitmap.createScaledBitmap(cropMask, TRAINED_SIZE, TRAINED_SIZE, false)

            // 3. Prepare Tensors (concat mask and image as MIGAN expects [1, 4, 512, 512])
            val floatBuffer = buildMiganInput(inputImage, inputMask)
            val shape = longArrayOf(1, 4, TRAINED_SIZE.toLong(), TRAINED_SIZE.toLong())
            tensorInput = OnnxTensor.createTensor(env, floatBuffer, shape)

            val inputs = mapOf("input" to tensorInput)

            // 4. Run Inference
            resultOrt = session.run(inputs)
            val outputTensor = resultOrt[0] as OnnxTensor

            // 5. Post Process
            outputBitmap = outputTensorToBitmap(outputTensor)

            // 6. Resize Output back to Crop Size
            outputCrop = Bitmap.createScaledBitmap(outputBitmap, cropRect.width(), cropRect.height(), true)

            // We will accumulate results into this bitmap
            val resultBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Draw base
            canvas.drawBitmap(image, 0f, 0f, paint)

            // 7. Composite Logic
            val sc = canvas.saveLayer(
                cropRect.left.toFloat(),
                cropRect.top.toFloat(),
                cropRect.right.toFloat(),
                cropRect.bottom.toFloat(),
                null
            )

            paint.xfermode = null // Normal draw
            canvas.drawBitmap(outputCrop, cropRect.left.toFloat(), cropRect.top.toFloat(), paint)

            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawBitmap(cropMask, cropRect.left.toFloat(), cropRect.top.toFloat(), paint)

            paint.xfermode = null
            canvas.restoreToCount(sc)

            return@withContext resultBitmap

        } catch (e: Exception) {
            Log.e("MiganProcessor", "Inference failed", e)
            // Force close session on error to allow retry
            closeSession()
            return@withContext null
        } finally {
            // Memory Optimization: Explicitly recycle all temporary bitmaps!
            // Crucial: Only recycle if they are NOT references to the original images!
            if (cropImage != null && cropImage != image) {
                try { cropImage.recycle() } catch (e: Exception) {}
            }
            if (cropMask != null && cropMask != mask) {
                try { cropMask.recycle() } catch (e: Exception) {}
            }
            if (inputImage != null && inputImage != cropImage && inputImage != image) {
                try { inputImage.recycle() } catch (e: Exception) {}
            }
            if (inputMask != null && inputMask != cropMask && inputMask != mask) {
                try { inputMask.recycle() } catch (e: Exception) {}
            }
            if (outputBitmap != null) {
                try { outputBitmap.recycle() } catch (e: Exception) {}
            }
            if (outputCrop != null) {
                try { outputCrop.recycle() } catch (e: Exception) {}
            }
            try {
                resultOrt?.close()
                tensorInput?.close()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun buildMiganInput(img: Bitmap, mask: Bitmap): FloatBuffer {
        val n = TRAINED_SIZE * TRAINED_SIZE
        val data = FloatArray(4 * n)
        val ip = IntArray(n)
        img.getPixels(ip, 0, TRAINED_SIZE, 0, 0, TRAINED_SIZE, TRAINED_SIZE)
        val mp = IntArray(n)
        mask.getPixels(mp, 0, TRAINED_SIZE, 0, 0, TRAINED_SIZE, TRAINED_SIZE)

        for (i in 0 until n) {
            val alpha = (mp[i] ushr 24) and 0xFF
            val red = (mp[i] ushr 16) and 0xFF
            val hole = if (alpha > 0 || red > 120) 1f else 0f
            data[i] = 0.5f - hole
        }

        for (c in 0 until 3) {
            val base = (c + 1) * n
            val shift = 16 - c * 8
            for (i in 0 until n) {
                val alpha = (mp[i] ushr 24) and 0xFF
                val red = (mp[i] ushr 16) and 0xFF
                val hole = if (alpha > 0 || red > 120) 1f else 0f
                val v = ((ip[i] ushr shift) and 0xFF) / 255f * 2f - 1f
                data[base + i] = v * (1f - hole)
            }
        }
        return FloatBuffer.wrap(data)
    }

    private fun outputTensorToBitmap(tensor: OnnxTensor): Bitmap {
        val buffer = tensor.floatBuffer
        val data = FloatArray(buffer.capacity())
        buffer.get(data)

        val width = TRAINED_SIZE
        val height = TRAINED_SIZE
        val size = width * height
        val pixels = IntArray(size)

        val scale = 127.5f
        val bias = 127.5f

        for (i in 0 until size) {
            val r = ((data[i] * scale + bias) + 0.5f).toInt().coerceIn(0, 255)
            val g = ((data[size + i] * scale + bias) + 0.5f).toInt().coerceIn(0, 255)
            val b = ((data[2 * size + i] * scale + bias) + 0.5f).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
