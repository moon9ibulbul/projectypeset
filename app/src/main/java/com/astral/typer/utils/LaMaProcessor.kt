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

            // 1. Resize Input
            val inputImage = if (image.width != TRAINED_SIZE || image.height != TRAINED_SIZE) {
                Bitmap.createScaledBitmap(image, TRAINED_SIZE, TRAINED_SIZE, true)
            } else {
                image
            }

            val inputMask = if (mask.width != TRAINED_SIZE || mask.height != TRAINED_SIZE) {
                Bitmap.createScaledBitmap(mask, TRAINED_SIZE, TRAINED_SIZE, false)
            } else {
                mask
            }

            // 2. Prepare Tensors
            val tensorImg = bitmapToOnnxTensor(env, inputImage)
            val tensorMask = bitmapToMaskTensor(env, inputMask)

            val inputs = mapOf("image" to tensorImg, "mask" to tensorMask)

            // 3. Run Inference
            val resultOrt = session.run(inputs)
            val outputTensor = resultOrt[0] as OnnxTensor

            // 4. Post Process
            val outputBitmap = outputTensorToBitmap(outputTensor)

            // Cleanup Inputs/Outputs but keep Session
            resultOrt.close()
            tensorImg.close()
            tensorMask.close()
            // Do not close session here, it is cached

            // 5. Composite Logic
            // Always blend the result back into the original image using the mask.
            // This ensures pixel-perfect fidelity for unmasked areas, even if dimensions matched.

            // Upscale the output to original size (if needed) or just use it
            val upscaledOutput = if (image.width != TRAINED_SIZE || image.height != TRAINED_SIZE) {
                Bitmap.createScaledBitmap(outputBitmap, image.width, image.height, true)
            } else {
                outputBitmap // Use directly if size matches
            }

            // Create result bitmap based on original dimensions
            val resultBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Draw Original Image first (Base Layer)
            canvas.drawBitmap(image, 0f, 0f, paint)

            // Draw the Inpainted Result masked by the Original Mask
            // This replaces the original pixels ONLY where the mask is opaque
            val sc = canvas.saveLayer(0f, 0f, image.width.toFloat(), image.height.toFloat(), null)

            // Draw the inferred result
            canvas.drawBitmap(upscaledOutput, 0f, 0f, paint)

            // DST_IN: Keeps the Destination (Inpainted Result) only where Source (Mask) is opaque.
            // Areas where Mask is transparent will be removed from this layer, revealing the Original Image below.
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

            // Draw mask
            canvas.drawBitmap(mask, 0f, 0f, paint)

            // Restore
            paint.xfermode = null
            canvas.restoreToCount(sc)

            // Note: We do not recycle outputBitmap manually here as Kotlin/ART GC handles it,
            // and if it was not scaled, upscaledOutput IS outputBitmap.

            return@withContext resultBitmap

        } catch (e: Exception) {
            Log.e("LaMaProcessor", "Inference failed", e)
            // Force close session on error to allow retry
            closeSession()
            return@withContext null
        }
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
