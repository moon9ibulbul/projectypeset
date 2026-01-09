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

class LaMaProcessor(private val context: Context) {

    companion object {
        private const val TRAINED_SIZE = 512
        private const val MODEL_URL = "https://github.com/T8RIN/ImageToolboxRemoteResources/raw/refs/heads/main/onnx/inpaint/lama/LaMa_512.onnx"
        private const val MODEL_FILENAME = "LaMa_512.onnx"
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
                        Log.e("LaMaProcessor", "Too many redirects")
                        return@withContext false
                    }
                    val location = connection!!.getHeaderField("Location")
                    if (location != null) {
                        urlStr = location
                        redirects++
                        connection!!.disconnect()
                        continue
                    } else {
                        Log.e("LaMaProcessor", "Redirect with no Location header")
                        return@withContext false
                    }
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    break
                } else {
                     Log.e("LaMaProcessor", "Server returned HTTP $responseCode ${connection!!.responseMessage}")
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
                    Log.e("LaMaProcessor", "Failed to rename or copy temp file", e)
                    return@withContext false
                }
            }

            // Clear cache to reload new model if session exists
            closeSession()
            return@withContext true

        } catch (e: Exception) {
            Log.e("LaMaProcessor", "Download failed", e)
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
                Log.w("LaMaProcessor", "Failed to set optimization options", e)
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
            val env = OrtEnvironment.getEnvironment() // Use shared env getter if needed, or static
            val session = getSession()

            // 0. Calculate Smart Crop
            val maskRect = getMaskBoundRect(mask) ?: return@withContext image // Return original if empty mask

            // Calculate padded square crop
            // 3x padding
            val size = (kotlin.math.max(maskRect.width(), maskRect.height()) * 3)
            val cx = maskRect.centerX()
            val cy = maskRect.centerY()
            val halfSize = size / 2

            // Calculate raw bounds
            var left = cx - halfSize
            var top = cy - halfSize
            var right = cx + halfSize
            var bottom = cy + halfSize

            // Constrain to image bounds (shift if possible, else clamp)
            // Strategy: Try to shift window to stay within bounds.
            // If window > image, center it and clip (will be handled by bitmap creation)

            val imgW = image.width
            val imgH = image.height

            // Adjust horizontal
            if (right - left > imgW) {
                // Crop is wider than image, center and clamp
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

            // Adjust vertical
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

            val cropRect = android.graphics.Rect(left, top, right, bottom)

            // 1. Create Crops
            val cropImage = Bitmap.createBitmap(image, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
            val cropMask = Bitmap.createBitmap(mask, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())

            // 2. Resize Input for Model
            val inputImage = Bitmap.createScaledBitmap(cropImage, TRAINED_SIZE, TRAINED_SIZE, true)
            val inputMask = Bitmap.createScaledBitmap(cropMask, TRAINED_SIZE, TRAINED_SIZE, false)

            // 3. Prepare Tensors
            val tensorImg = bitmapToOnnxTensor(env, inputImage)
            val tensorMask = bitmapToMaskTensor(env, inputMask)

            val inputs = mapOf("image" to tensorImg, "mask" to tensorMask)

            // 4. Run Inference
            val resultOrt = session.run(inputs)
            val outputTensor = resultOrt[0] as OnnxTensor

            // 5. Post Process
            val outputBitmap = outputTensorToBitmap(outputTensor)

            // Cleanup Inputs/Outputs but keep Session
            resultOrt.close()
            tensorImg.close()
            tensorMask.close()

            // 6. Resize Output back to Crop Size
            val outputCrop = Bitmap.createScaledBitmap(outputBitmap, cropRect.width(), cropRect.height(), true)

            // 7. Composite Logic
            // Blend the outputCrop back into the original image using the cropMask

            // Create result bitmap based on original dimensions
            val resultBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Draw Original Image first (Base Layer)
            canvas.drawBitmap(image, 0f, 0f, paint)

            // Draw the Inpainted Crop masked by the Original Crop Mask
            // We only need to affect the area within cropRect.

            // Save Layer restricted to cropRect
            val sc = canvas.saveLayer(
                cropRect.left.toFloat(),
                cropRect.top.toFloat(),
                cropRect.right.toFloat(),
                cropRect.bottom.toFloat(),
                null
            )

            // Draw the inferred result at the crop position
            canvas.drawBitmap(outputCrop, cropRect.left.toFloat(), cropRect.top.toFloat(), paint)

            // DST_IN: Blend with the mask crop to ensure we only paste over the masked area
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

            // Draw the mask crop at the correct position
            canvas.drawBitmap(cropMask, cropRect.left.toFloat(), cropRect.top.toFloat(), paint)

            // Restore
            paint.xfermode = null
            canvas.restoreToCount(sc)

            return@withContext resultBitmap

        } catch (e: Exception) {
            Log.e("LaMaProcessor", "Inference failed", e)
            // Force close session on error to allow retry
            closeSession()
            return@withContext null
        }
    }

    private fun getMaskBoundRect(mask: Bitmap): android.graphics.Rect? {
        val w = mask.width
        val h = mask.height
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1

        var found = false

        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = pixels[y * w + x]
                // Check Alpha > 0
                if ((pixel ushr 24) > 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    found = true
                }
            }
        }

        return if (found) android.graphics.Rect(minX, minY, maxX + 1, maxY + 1) else null
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
            // If alpha > 0, it is a mask.
            data[i] = if (alpha > 0) 1f else 0f
        }

        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1, 1, h.toLong(), w.toLong())
        )
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

    private fun outputTensorToBitmap(tensor: OnnxTensor): Bitmap {
        val buffer = tensor.floatBuffer
        val data = FloatArray(buffer.capacity())
        buffer.get(data)

        val width = TRAINED_SIZE
        val height = TRAINED_SIZE
        val size = width * height
        val pixels = IntArray(size)

        // For standard LaMa model, output is 0..1 (approx) or unnormalized?
        // Reference says: if (isFastModel) 255 else 1. We are using standard model (not fast?).
        // Actually the URL I used is LaMa_512.onnx.
        // Let's assume output is 0..255 if reference logic applies amp=1.
        // Wait, if amp=1, then (data[i] * 1) -> 0..1 or 0..255?
        // If the model output is 0..255, multiplying by 1 keeps it 0..255.
        // If the model output is 0..1, we need to multiply by 255.

        // Let's re-read reference logic:
        // val amp = if (isFastModel) 255 else 1
        // r = (data[i] * amp).toInt()

        // Reference URL: https://github.com/T8RIN/ImageToolboxRemoteResources/raw/refs/heads/main/onnx/inpaint/lama/LaMa_512.onnx
        // This is the "Normal" model. Reference code says `isFastModel` defaults to false.
        // So amp = 1.
        // This implies the normal model outputs values in 0..255 range directly.
        // Whereas FAST model likely outputs 0..1 range and needs 255 scaling.

        // So for LaMa_512.onnx (Normal), we multiply by 1.
        val amp = 1

        for (i in 0 until size) {
            val r = (data[i] * amp).toInt().coerceIn(0, 255)
            val g = (data[size + i] * amp).toInt().coerceIn(0, 255)
            val b = (data[2 * size + i] * amp).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
