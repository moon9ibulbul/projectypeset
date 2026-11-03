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
        private const val MODEL_URL = "https://huggingface.co/ogkalu/lama-manga-onnx-dynamic/resolve/main/lama-manga-dynamic.onnx"
        private const val MODEL_FILENAME = "lama-manga-dynamic.onnx"
        private const val LEGACY_MODEL_FILENAME = "LaMa_512.onnx"
        private const val MODEL_INT8_URL = "https://huggingface.co/othytenk/lama-int4-int8/resolve/main/lama_int4_int8_r4.onnx"
        private const val MODEL_INT8_FILENAME = "lama_int4_int8_r4.onnx"
        private const val LEGACY_MODEL_INT8_FILENAME = "lama_512_int8.onnx"
        private const val CONNECT_TIMEOUT = 30000 // 30 seconds
        private const val READ_TIMEOUT = 30000 // 30 seconds
        private const val USER_AGENT = "AstralTyper/1.0"

        // Caching environment and session to avoid reloading overhead
        private var ortEnvironment: OrtEnvironment? = null
        private var ortSession: OrtSession? = null
    }

    private val modelFile: File
        get() {
            val newFile = File(context.filesDir, "onnx/$MODEL_FILENAME")
            if (newFile.exists() && newFile.length() > 0) return newFile
            val legacyFile = File(context.filesDir, "onnx/$LEGACY_MODEL_FILENAME")
            return if (legacyFile.exists() && legacyFile.length() > 0) legacyFile else newFile
        }

    private val modelInt8File: File
        get() {
            val newFile = File(context.filesDir, "onnx/$MODEL_INT8_FILENAME")
            if (newFile.exists() && newFile.length() > 0) return newFile
            val legacyFile = File(context.filesDir, "onnx/$LEGACY_MODEL_INT8_FILENAME")
            return if (legacyFile.exists() && legacyFile.length() > 0) legacyFile else newFile
        }

    fun isModelAvailable(): Boolean {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        val useInt8 = prefs.getString("lama_model_version", "Int8") == "Int8"
        val activeFile = if (useInt8) modelInt8File else modelFile

        if (!activeFile.exists() || activeFile.length() <= 0) {
            val fallbackFile = if (useInt8) modelFile else modelInt8File
            return fallbackFile.exists() && fallbackFile.length() > 0
        }
        return true
    }

    fun isOriginalModelAvailable(): Boolean {
        return modelFile.exists() && modelFile.length() > 0
    }

    fun isInt8ModelAvailable(): Boolean {
        return modelInt8File.exists() && modelInt8File.length() > 0
    }

    suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean {
        return downloadInternal(MODEL_URL, modelFile, "$MODEL_FILENAME.tmp", onProgress)
    }

    suspend fun downloadInt8Model(onProgress: (Float) -> Unit): Boolean {
        return downloadInternal(MODEL_INT8_URL, modelInt8File, "$MODEL_INT8_FILENAME.tmp", onProgress)
    }

    private suspend fun downloadInternal(urlStrInput: String, file: File, tmpFileName: String, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            file.parentFile?.mkdirs()
            val tmpFile = File(file.parentFile, tmpFileName)

            var urlStr = urlStrInput
            var redirects = 0
            val maxRedirects = 5

            while (true) {
                val url = URL(urlStr)
                connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER) {

                    if (redirects >= maxRedirects) {
                        Log.e("LaMaProcessor", "Too many redirects")
                        return@withContext false
                    }
                    val location = connection.getHeaderField("Location")
                    if (location != null) {
                        urlStr = location
                        redirects++
                        connection.disconnect()
                        continue
                    } else {
                        Log.e("LaMaProcessor", "Redirect with no Location header")
                        return@withContext false
                    }
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    break
                } else {
                     Log.e("LaMaProcessor", "Server returned HTTP $responseCode ${connection.responseMessage}")
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
                try {
                     tmpFile.copyTo(file, overwrite = true)
                     tmpFile.delete()
                } catch (e: Exception) {
                    Log.e("LaMaProcessor", "Failed to rename or copy temp file", e)
                    return@withContext false
                }
            }

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
            val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            val useInt8 = prefs.getString("lama_model_version", "Int8") == "Int8"
            var activeFile = if (useInt8) modelInt8File else modelFile

            if (!activeFile.exists() || activeFile.length() <= 0) {
                activeFile = if (useInt8) modelFile else modelInt8File
            }

            // Optimization options
            try {
                 sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                 // Dynamic thread pool count based on available CPU cores
                 val numCores = Runtime.getRuntime().availableProcessors()
                 val optimalThreads = (numCores / 2).coerceIn(1, 4)
                 sessionOptions.setInterOpNumThreads(optimalThreads)
                 sessionOptions.setIntraOpNumThreads(optimalThreads)

                 // Retrieve NNAPI toggle preference from Settings
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
            ortSession = ortEnvironment!!.createSession(activeFile.absolutePath, sessionOptions)
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

    fun closeSession() {
        try {
            ortSession?.close()
            ortSession = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deleteModel(): Boolean {
        return deleteOriginalModel() || deleteInt8Model()
    }

    fun deleteOriginalModel(): Boolean {
        closeSession()
        val legacy = File(context.filesDir, "onnx/$LEGACY_MODEL_FILENAME")
        if (legacy.exists()) legacy.delete()
        val curr = File(context.filesDir, "onnx/$MODEL_FILENAME")
        return if (curr.exists()) {
            curr.delete()
        } else false
    }

    fun deleteInt8Model(): Boolean {
        closeSession()
        val legacy = File(context.filesDir, "onnx/$LEGACY_MODEL_INT8_FILENAME")
        if (legacy.exists()) legacy.delete()
        val curr = File(context.filesDir, "onnx/$MODEL_INT8_FILENAME")
        return if (curr.exists()) {
            curr.delete()
        } else false
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
        // Adaptive margin matching AstralCleaner: max(64, 0.6 * max(w, h))
        val margin = kotlin.math.max(64, (0.6f * kotlin.math.max(maskRect.width(), maskRect.height())).toInt())
        val imgW = originalImage.width
        val imgH = originalImage.height

        val left = (maskRect.left - margin).coerceIn(0, imgW)
        val top = (maskRect.top - margin).coerceIn(0, imgH)
        val right = (maskRect.right + margin).coerceIn(0, imgW)
        val bottom = (maskRect.bottom + margin).coerceIn(0, imgH)

        if (right <= left || bottom <= top) return // Invalid crop

        val cropRect = android.graphics.Rect(left, top, right, bottom)
        val cropW = cropRect.width()
        val cropH = cropRect.height()

        // 1. Create Crops
        val cropImage = Bitmap.createBitmap(originalImage, cropRect.left, cropRect.top, cropW, cropH)
        val cropMask = Bitmap.createBitmap(originalMask, cropRect.left, cropRect.top, cropW, cropH)

        // Determine if model has fixed or dynamic input shape
        val inputInfo = session.inputInfo
        var isDynamic = true
        var fixedW = 0
        var fixedH = 0

        for ((_, nodeInfo) in inputInfo) {
            val tensorInfo = nodeInfo.info as? ai.onnxruntime.TensorInfo
            if (tensorInfo != null) {
                val shape = tensorInfo.shape
                if (shape.size == 4) {
                    val hDim = shape[2]
                    val wDim = shape[3]
                    if (hDim > 0 && wDim > 0) {
                        isDynamic = false
                        fixedH = hDim.toInt()
                        fixedW = wDim.toInt()
                        break
                    }
                }
            }
        }

        val maxSide = 768
        val scale = if (isDynamic) {
            val maxCropSide = kotlin.math.max(cropW, cropH)
            if (maxCropSide > maxSide) maxSide.toFloat() / maxCropSide else 1.0f
        } else {
            1.0f
        }

        val scaledW = if (isDynamic) {
            (cropW * scale).toInt().coerceAtLeast(8)
        } else {
            if (fixedW > 0) fixedW else TRAINED_SIZE
        }
        val scaledH = if (isDynamic) {
            (cropH * scale).toInt().coerceAtLeast(8)
        } else {
            if (fixedH > 0) fixedH else TRAINED_SIZE
        }

        // For dynamic models, pad to multiple of 8 using reflection/edge clamping
        val targetW = if (isDynamic) {
            val rem = scaledW % 8
            if (rem == 0) scaledW else scaledW + (8 - rem)
        } else {
            scaledW
        }
        val targetH = if (isDynamic) {
            val rem = scaledH % 8
            if (rem == 0) scaledH else scaledH + (8 - rem)
        } else {
            scaledH
        }

        val prepImage = if (cropW != scaledW || cropH != scaledH) {
            Bitmap.createScaledBitmap(cropImage, scaledW, scaledH, true)
        } else {
            cropImage
        }
        val prepMask = if (cropW != scaledW || cropH != scaledH) {
            Bitmap.createScaledBitmap(cropMask, scaledW, scaledH, false)
        } else {
            cropMask
        }

        val inputImage = if (targetW != scaledW || targetH != scaledH) {
            val padded = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val padCanvas = Canvas(padded)
            padCanvas.drawBitmap(prepImage, 0f, 0f, null)
            // Replicate right/bottom edge borders if padded
            if (targetW > scaledW) {
                val srcCol = android.graphics.Rect(scaledW - 1, 0, scaledW, scaledH)
                val dstCol = android.graphics.Rect(scaledW, 0, targetW, scaledH)
                padCanvas.drawBitmap(prepImage, srcCol, dstCol, null)
            }
            if (targetH > scaledH) {
                val srcRow = android.graphics.Rect(0, scaledH - 1, targetW, scaledH)
                val dstRow = android.graphics.Rect(0, scaledH, targetW, targetH)
                padCanvas.drawBitmap(padded, srcRow, dstRow, null)
            }
            padded
        } else {
            prepImage
        }

        val inputMask = if (targetW != scaledW || targetH != scaledH) {
            val padded = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val padCanvas = Canvas(padded)
            padCanvas.drawBitmap(prepMask, 0f, 0f, null)
            padded
        } else {
            prepMask
        }

        var tensorImg: OnnxTensor? = null
        var tensorMask: OnnxTensor? = null
        var tensorCombined: OnnxTensor? = null
        var resultOrt: OrtSession.Result? = null

        try {
            val inputNames = session.inputNames
            val inputs = if (inputNames.contains("input") || inputNames.size == 1) {
                tensorCombined = bitmapToCombined4DOnnxTensor(env, inputImage, inputMask)
                val name = inputNames.firstOrNull() ?: "input"
                mapOf(name to tensorCombined)
            } else {
                tensorImg = bitmapToOnnxTensor(env, inputImage)
                tensorMask = bitmapToMaskTensor(env, inputMask)
                mapOf("image" to tensorImg, "mask" to tensorMask)
            }

            // 4. Run Inference
            resultOrt = session.run(inputs)
            val outputTensor = resultOrt[0] as OnnxTensor

            // 5. Post Process to targetW x targetH bitmap
            val outputBitmap = outputTensorToBitmap(outputTensor, targetW, targetH)

            // Crop back unpadded part
            val unpaddedBitmap = if (targetW != scaledW || targetH != scaledH) {
                Bitmap.createBitmap(outputBitmap, 0, 0, scaledW, scaledH)
            } else {
                outputBitmap
            }

            // 6. Resize Output back to Crop Size
            val outputCrop = if (unpaddedBitmap.width != cropW || unpaddedBitmap.height != cropH) {
                Bitmap.createScaledBitmap(unpaddedBitmap, cropW, cropH, true)
            } else {
                unpaddedBitmap
            }

            // 7. Composite Logic (Paste back onto accumulating canvas)
            val sc = canvas.saveLayer(
                cropRect.left.toFloat(),
                cropRect.top.toFloat(),
                cropRect.right.toFloat(),
                cropRect.bottom.toFloat(),
                null
            )

            // Draw inferred result at crop position
            paint.xfermode = null
            canvas.drawBitmap(outputCrop, cropRect.left.toFloat(), cropRect.top.toFloat(), paint)

            // Blend with mask to ensure surrounding pixels are untouched
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawBitmap(cropMask, cropRect.left.toFloat(), cropRect.top.toFloat(), paint)

            // Restore
            paint.xfermode = null
            canvas.restoreToCount(sc)

            if (unpaddedBitmap != outputBitmap) unpaddedBitmap.recycle()
            if (outputCrop != unpaddedBitmap && outputCrop != outputBitmap) outputCrop.recycle()
            outputBitmap.recycle()

        } catch (e: Exception) {
            Log.e("LaMaProcessor", "Error processing region $maskRect", e)
        } finally {
            try {
                resultOrt?.close()
                tensorImg?.close()
                tensorMask?.close()
                tensorCombined?.close()
            } catch (e: Exception) { /* ignore */ }

            if (inputImage != prepImage && inputImage != cropImage) inputImage.recycle()
            if (inputMask != prepMask && inputMask != cropMask) inputMask.recycle()
            if (prepImage != cropImage) prepImage.recycle()
            if (prepMask != cropMask) prepMask.recycle()
            cropImage.recycle()
            cropMask.recycle()
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

    private fun bitmapToCombined4DOnnxTensor(env: OrtEnvironment, image: Bitmap, mask: Bitmap): OnnxTensor {
        val w = image.width
        val h = image.height
        val imgPixels = IntArray(w * h)
        val maskPixels = IntArray(w * h)
        image.getPixels(imgPixels, 0, w, 0, 0, w, h)
        mask.getPixels(maskPixels, 0, w, 0, 0, w, h)

        val size = 4 * w * h
        val data = FloatArray(size)
        val channelSize = w * h

        for (i in 0 until channelSize) {
            val mVal = if (((maskPixels[i] shr 24) and 0xFF) > 0) 1f else 0f
            val p = imgPixels[i]
            // If masked (mVal == 1f), masked image region is zeroed out
            val r = if (mVal > 0f) 0f else (((p shr 16) and 0xFF) / 255f)
            val g = if (mVal > 0f) 0f else (((p shr 8) and 0xFF) / 255f)
            val b = if (mVal > 0f) 0f else ((p and 0xFF) / 255f)

            data[i] = r
            data[channelSize + i] = g
            data[2 * channelSize + i] = b
            data[3 * channelSize + i] = mVal
        }

        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1, 4, h.toLong(), w.toLong())
        )
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

    private fun outputTensorToBitmap(tensor: OnnxTensor, width: Int = TRAINED_SIZE, height: Int = TRAINED_SIZE): Bitmap {
        val buffer = tensor.floatBuffer
        val data = FloatArray(buffer.capacity())
        buffer.get(data)

        val size = width * height
        val pixels = IntArray(size)

        // Determine if output is normalized 0..1 or 0..255
        var maxVal = 0f
        val checkCount = kotlin.math.min(data.size, 1000)
        for (i in 0 until checkCount) {
            if (data[i] > maxVal) maxVal = data[i]
        }
        val scale = if (maxVal <= 2.0f) 255f else 1f

        for (i in 0 until size) {
            val r = (data[i] * scale).toInt().coerceIn(0, 255)
            val g = (data[size + i] * scale).toInt().coerceIn(0, 255)
            val b = (data[2 * size + i] * scale).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
