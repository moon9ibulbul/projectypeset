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

class LaMaProcessor(private val context: Context) {

    companion object {
        private const val TRAINED_SIZE = 512
        private const val MODEL_URL = "https://huggingface.co/bulbulmoon/lama/resolve/main/LaMa_512.onnx"
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
            val env = OrtEnvironment.getEnvironment()
            val session = getSession()

            // 0. Detect separate mask blobs (connected components)
            // This allows us to process spatially separated masks individually,
            // resulting in much higher quality (HD) because we don't downscale a huge bounding box.
            val maskRects = getSeparateMaskRects(mask)

            if (maskRects.isEmpty()) {
                return@withContext image // Nothing to mask
            }

            // We will accumulate results into this bitmap
            // Start with a copy of the original
            val resultBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Draw base
            canvas.drawBitmap(image, 0f, 0f, paint)

            // Loop through each distinct mask area
            for (rect in maskRects) {
                // Process this specific region
                processRegion(image, mask, rect, session, env, canvas, paint)
            }

            return@withContext resultBitmap

        } catch (e: Exception) {
            Log.e("LaMaProcessor", "Inference failed", e)
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
        // Calculate padded square crop (smart crop) based on this specific maskRect
        // 3x padding logic from original code
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

        // Clamp final values just in case
        left = left.coerceIn(0, imgW)
        right = right.coerceIn(0, imgW)
        top = top.coerceIn(0, imgH)
        bottom = bottom.coerceIn(0, imgH)

        if (right <= left || bottom <= top) return // Invalid crop

        val cropRect = android.graphics.Rect(left, top, right, bottom)

        // 1. Create Crops
        val cropImage = Bitmap.createBitmap(originalImage, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        val cropMask = Bitmap.createBitmap(originalMask, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())

        // 2. Resize Input for Model
        val inputImage = Bitmap.createScaledBitmap(cropImage, TRAINED_SIZE, TRAINED_SIZE, true)
        val inputMask = Bitmap.createScaledBitmap(cropMask, TRAINED_SIZE, TRAINED_SIZE, false)

        var tensorImg: OnnxTensor? = null
        var tensorMask: OnnxTensor? = null
        var resultOrt: OrtSession.Result? = null

        try {
            // 3. Prepare Tensors
            tensorImg = bitmapToOnnxTensor(env, inputImage)
            tensorMask = bitmapToMaskTensor(env, inputMask)

            val inputs = mapOf("image" to tensorImg, "mask" to tensorMask)

            // 4. Run Inference
            resultOrt = session.run(inputs)
            val outputTensor = resultOrt[0] as OnnxTensor

            // 5. Post Process
            val outputBitmap = outputTensorToBitmap(outputTensor)

            // 6. Resize Output back to Crop Size
            val outputCrop = Bitmap.createScaledBitmap(outputBitmap, cropRect.width(), cropRect.height(), true)

            // 7. Composite Logic (Paste back onto the accumulating canvas)
            val sc = canvas.saveLayer(
                cropRect.left.toFloat(),
                cropRect.top.toFloat(),
                cropRect.right.toFloat(),
                cropRect.bottom.toFloat(),
                null
            )

            // Draw the inferred result at the crop position
            paint.xfermode = null // Normal draw
            canvas.drawBitmap(outputCrop, cropRect.left.toFloat(), cropRect.top.toFloat(), paint)

            // DST_IN: Blend with the mask crop to ensure we only paste over the masked area
            // This ensures we don't overwrite surrounding pixels that were outside the mask but inside the crop
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawBitmap(cropMask, cropRect.left.toFloat(), cropRect.top.toFloat(), paint)

            // Restore
            paint.xfermode = null
            canvas.restoreToCount(sc)

        } catch (e: Exception) {
            Log.e("LaMaProcessor", "Error processing region $maskRect", e)
        } finally {
            // Explicit cleanup of tensors for this loop iteration
            try {
                resultOrt?.close()
                tensorImg?.close()
                tensorMask?.close()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * Finds connected components (blobs) in the mask using a simple BFS/FloodFill.
     * Returns a list of bounding rectangles for each separate mask area.
     * This avoids using heavy external dependencies like OpenCV.
     */
    private fun getSeparateMaskRects(mask: Bitmap): List<android.graphics.Rect> {
        val w = mask.width
        val h = mask.height
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)

        val visited = BooleanArray(w * h)
        val rects = ArrayList<android.graphics.Rect>()

        val queue: Queue<Int> = LinkedList()

        for (i in pixels.indices) {
            // Check if pixel is part of mask (Alpha > 0) and not visited
            if (!visited[i] && (pixels[i] ushr 24) > 0) {
                // Found a new component
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

                    // Update bounds
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    // Check 4 neighbors
                    // Left
                    if (cx > 0) {
                        val nIdx = currIdx - 1
                        if (!visited[nIdx] && (pixels[nIdx] ushr 24) > 0) {
                            visited[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                    // Right
                    if (cx < w - 1) {
                        val nIdx = currIdx + 1
                        if (!visited[nIdx] && (pixels[nIdx] ushr 24) > 0) {
                            visited[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                    // Top
                    if (cy > 0) {
                        val nIdx = currIdx - w
                        if (!visited[nIdx] && (pixels[nIdx] ushr 24) > 0) {
                            visited[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                    // Bottom
                    if (cy < h - 1) {
                        val nIdx = currIdx + w
                        if (!visited[nIdx] && (pixels[nIdx] ushr 24) > 0) {
                            visited[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                }

                // Add component rect
                if (maxX >= minX && maxY >= minY) {
                    rects.add(android.graphics.Rect(minX, minY, maxX + 1, maxY + 1))
                }
            }
        }

        // Fallback: if list is empty (e.g., all transparent), return nothing
        return rects
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
