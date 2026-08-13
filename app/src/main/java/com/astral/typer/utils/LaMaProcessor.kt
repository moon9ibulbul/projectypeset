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

    @Synchronized
    private fun getSession(): OrtSession {
        if (ortEnvironment == null) {
            ortEnvironment = OrtEnvironment.getEnvironment()
        }

        if (ortSession == null) {
            val sessionOptions = OrtSession.SessionOptions()
            // Optimization options
            try {
                 sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                 // Dynamic thread pool count based on available CPU cores
                 val numCores = Runtime.getRuntime().availableProcessors()
                 val optimalThreads = (numCores / 2).coerceIn(1, 4)
                 sessionOptions.setInterOpNumThreads(optimalThreads)
                 sessionOptions.setIntraOpNumThreads(optimalThreads)

                 // Retrieve NNAPI toggle preference from Settings
                 val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                 val enableNnapi = prefs.getBoolean("enable_lama_nnapi", false)
                 if (enableNnapi) {
                     sessionOptions.addNnapi()
                     Log.d("LaMaProcessor", "NNAPI enabled for LaMa session.")
                 } else {
                     Log.d("LaMaProcessor", "NNAPI disabled for LaMa session (CPU only).")
                 }
            } catch (e: Exception) {
                Log.w("LaMaProcessor", "Failed to set optimization options or enable NNAPI", e)
            }
            ortSession = ortEnvironment!!.createSession(modelFile.absolutePath, sessionOptions)
        }
        return ortSession!!
    }

    suspend fun warmUp() = withContext(Dispatchers.Default) {
        if (!isModelAvailable()) return@withContext
        try {
            Log.d("LaMaProcessor", "Triggering lazy background pre-warming...")
            getSession()
            Log.d("LaMaProcessor", "LaMa background pre-warming completed.")
        } catch (e: Exception) {
            Log.e("LaMaProcessor", "Failed to pre-warm LaMa model", e)
        }
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

            // Create a mutable copy of the mask to allow progressive erasing.
            // This allows subsequent tiles to use previously inpainted areas as valid context, preventing seams.
            val currentMask = mask.copy(Bitmap.Config.ARGB_8888, true)
            val maskCanvas = Canvas(currentMask)
            val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }

            // Loop through each distinct mask area
            for (rect in maskRects) {
                // Process this specific region using the progressively accumulated result and eroding mask
                processRegion(resultBitmap, currentMask, rect, session, env, canvas, paint)

                // Erase the processed tile from the mask so future crops treat this newly inpainted area as valid image context
                maskCanvas.drawRect(rect, clearPaint)
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
        val cx = maskRect.centerX()
        val cy = maskRect.centerY()

        // Since maskRects are guaranteed to be <= 256x256 from getSeparateMaskRects,
        // we can always use the native trained size (512x512) for a 1:1 context window
        // without ever needing to scale or lose quality.
        val size = TRAINED_SIZE
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

        var cropImage: Bitmap? = null
        var cropMask: Bitmap? = null
        var inputImage: Bitmap? = null
        var inputMask: Bitmap? = null
        var outputBitmap: Bitmap? = null
        var outputCrop: Bitmap? = null
        var tensorImg: OnnxTensor? = null
        var tensorMask: OnnxTensor? = null
        var resultOrt: OrtSession.Result? = null

        try {
            // 1. Create Crops
            cropImage = Bitmap.createBitmap(originalImage, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
            cropMask = Bitmap.createBitmap(originalMask, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())

            // 2. Prepare Input for Model (Native Padding)
            // Pad it with transparent pixels to exactly 512x512 to preserve native resolution without downsampling blur
            inputImage = Bitmap.createBitmap(TRAINED_SIZE, TRAINED_SIZE, Bitmap.Config.ARGB_8888)
            val imgCanvas = Canvas(inputImage)
            // Center the crop inside the 512x512 input. Use integer division to prevent sub-pixel bilinear interpolation blur.
            val dx = (TRAINED_SIZE - cropRect.width()) / 2
            val dy = (TRAINED_SIZE - cropRect.height()) / 2
            imgCanvas.drawBitmap(cropImage, dx.toFloat(), dy.toFloat(), null)

            inputMask = Bitmap.createBitmap(TRAINED_SIZE, TRAINED_SIZE, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(inputMask)
            maskCanvas.drawBitmap(cropMask, dx.toFloat(), dy.toFloat(), null)

            // 3. Prepare Tensors
            tensorImg = bitmapToOnnxTensor(env, inputImage)
            tensorMask = bitmapToMaskTensor(env, inputMask)

            val inputs = mapOf("image" to tensorImg, "mask" to tensorMask)

            // 4. Run Inference
            resultOrt = session.run(inputs)
            val outputTensor = resultOrt[0] as OnnxTensor

            // 5. Post Process
            outputBitmap = outputTensorToBitmap(outputTensor, TRAINED_SIZE, TRAINED_SIZE)

            // 6. Resize/Crop Output back to Original Crop Size
            // Extract the exact region back from the center of the 512x512 output
            outputCrop = Bitmap.createBitmap(outputBitmap, dx, dy, cropRect.width(), cropRect.height())

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
            // Memory Optimization: Explicitly recycle all temporary bitmaps
            if (cropImage != originalImage) {
                try { cropImage?.recycle() } catch (e: Exception) {}
            }
            if (cropMask != originalMask) {
                try { cropMask?.recycle() } catch (e: Exception) {}
            }
            if (inputImage != cropImage) {
                try { inputImage?.recycle() } catch (e: Exception) {}
            }
            if (inputMask != cropMask) {
                try { inputMask?.recycle() } catch (e: Exception) {}
            }
            try { outputBitmap?.recycle() } catch (e: Exception) {}
            try { outputCrop?.recycle() } catch (e: Exception) {}

            // Explicit cleanup of tensors for this loop iteration
            try {
                resultOrt?.close()
                tensorImg?.close()
                tensorMask?.close()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * Scans the mask using a high-speed grid-based approach (max 256x256 tiles).
     * This avoids full-image allocations (OOM errors) and ensures large masks are dynamically
     * split into native-sized tiles, guaranteeing 1:1 inference without downscaling.
     */
    private fun getSeparateMaskRects(mask: Bitmap): List<android.graphics.Rect> {
        val w = mask.width
        val h = mask.height
        val maxTileSize = TRAINED_SIZE / 2 // 256x256

        val rects = ArrayList<android.graphics.Rect>()
        // Reused buffer for extracting tile pixels (max 256x256)
        val pixelBuffer = IntArray(maxTileSize * maxTileSize)

        // 1. Grid Scan
        for (y in 0 until h step maxTileSize) {
            for (x in 0 until w step maxTileSize) {
                val tileW = kotlin.math.min(maxTileSize, w - x)
                val tileH = kotlin.math.min(maxTileSize, h - y)

                mask.getPixels(pixelBuffer, 0, tileW, x, y, tileW, tileH)

                var minX = tileW
                var maxX = -1
                var minY = tileH
                var maxY = -1
                var found = false

                // Find tight bounding box within this tile
                for (ty in 0 until tileH) {
                    val rowOffset = ty * tileW
                    for (tx in 0 until tileW) {
                        if ((pixelBuffer[rowOffset + tx] ushr 24) > 0) {
                            if (tx < minX) minX = tx
                            if (tx > maxX) maxX = tx
                            if (ty < minY) minY = ty
                            if (ty > maxY) maxY = ty
                            found = true
                        }
                    }
                }

                if (found) {
                    // Absolute coordinates
                    rects.add(
                        android.graphics.Rect(
                            x + minX,
                            y + minY,
                            x + maxX + 1,
                            y + maxY + 1
                        )
                    )
                }
            }
        }

        // 2. Merge adjacent/overlapping rects IF their union remains <= 256x256
        // This ensures small adjacent mask patches are grouped, but huge masks remain tiled.
        var merged = true
        while (merged) {
            merged = false
            for (i in 0 until rects.size) {
                for (j in i + 1 until rects.size) {
                    val r1 = rects[i]
                    val r2 = rects[j]

                    // Calculate potential union
                    val unionLeft = kotlin.math.min(r1.left, r2.left)
                    val unionTop = kotlin.math.min(r1.top, r2.top)
                    val unionRight = kotlin.math.max(r1.right, r2.right)
                    val unionBottom = kotlin.math.max(r1.bottom, r2.bottom)

                    val unionWidth = unionRight - unionLeft
                    val unionHeight = unionBottom - unionTop

                    // If union fits inside our max tile size (and they are close/intersecting), merge them
                    if (unionWidth <= maxTileSize && unionHeight <= maxTileSize) {
                        // Check if they overlap or are very close (e.g., within 16px of each other)
                        val expandedR1 = android.graphics.Rect(r1.left - 16, r1.top - 16, r1.right + 16, r1.bottom + 16)
                        if (android.graphics.Rect.intersects(expandedR1, r2)) {
                            rects[i] = android.graphics.Rect(unionLeft, unionTop, unionRight, unionBottom)
                            rects.removeAt(j)
                            merged = true
                            break
                        }
                    }
                }
                if (merged) break
            }
        }

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

        val size = w * h
        val byteBuffer = java.nio.ByteBuffer.allocateDirect(size * 4).order(java.nio.ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()

        val data = FloatArray(size)
        for (i in 0 until size) {
            val p = pixels[i]
            val alpha = (p shr 24) and 0xFF
            data[i] = if (alpha > 0) 1f else 0f
        }

        floatBuffer.put(data)
        floatBuffer.rewind()

        return OnnxTensor.createTensor(
            env,
            floatBuffer,
            longArrayOf(1, 1, h.toLong(), w.toLong())
        )
    }

    private fun bitmapToOnnxTensor(env: OrtEnvironment, bitmap: Bitmap): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val channelSize = w * h
        val size = 3 * channelSize
        val byteBuffer = java.nio.ByteBuffer.allocateDirect(size * 4).order(java.nio.ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()

        val data = FloatArray(size)

        // Write R, G, B
        for (i in 0 until channelSize) {
            val p = pixels[i]
            data[i] = ((p shr 16) and 0xFF) / 255f
            data[channelSize + i] = ((p shr 8) and 0xFF) / 255f
            data[2 * channelSize + i] = (p and 0xFF) / 255f
        }

        floatBuffer.put(data)
        floatBuffer.rewind()

        return OnnxTensor.createTensor(
            env,
            floatBuffer,
            longArrayOf(1, 3, h.toLong(), w.toLong())
        )
    }

    private fun outputTensorToBitmap(tensor: OnnxTensor, width: Int, height: Int): Bitmap {
        val buffer = tensor.floatBuffer
        val size = width * height
        val pixels = IntArray(size)

        val data = FloatArray(buffer.capacity())
        buffer.rewind()
        buffer.get(data)

        // Dynamically detect range. LaMa models from different exports can output [0, 1] or [0, 255]
        var needsMultiplier = true
        val step = kotlin.math.max(1, data.size / 1000)
        for (i in data.indices step step) {
            if (data[i] > 1.0f) {
                needsMultiplier = false
                break
            }
        }

        val multiplier = if (needsMultiplier) 255f else 1f

        for (i in 0 until size) {
            val r = (data[i] * multiplier).toInt().coerceIn(0, 255)
            val g = (data[size + i] * multiplier).toInt().coerceIn(0, 255)
            val b = (data[2 * size + i] * multiplier).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
