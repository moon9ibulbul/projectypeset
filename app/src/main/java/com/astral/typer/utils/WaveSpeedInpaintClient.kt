package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * WaveSpeed AI cloud inpainting (https://wavespeed.ai/docs/rest-api).
 *
 * Only the neighbourhood of the painted selection is uploaded so the request (and its cost)
 * stays small. The source crop is uploaded together with a black/white mask, a prediction is
 * submitted with the user prompt, polled until completion, and the returned image is composited
 * back into the original bitmap.
 */
class WaveSpeedInpaintClient(private val context: Context) {

    class WaveSpeedException(message: String, val httpCode: Int = 0) : Exception(message)

    private val prefs = context.applicationContext
        .getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    fun getApiKey(): String = prefs.getString(PREF_API_KEY, "")?.trim().orEmpty()

    fun getModel(): String {
        val saved = prefs.getString(PREF_MODEL, "")?.trim().orEmpty()
        return saved.ifEmpty { DEFAULT_MODEL }
    }

    fun isConfigured(): Boolean = getApiKey().isNotEmpty() && getModel().isNotEmpty()

    /**
     * @param image source image (usually an already cropped inpaint region).
     * @param mask white-on-transparent mask, same size as [image] or scaled to it.
     * @param prompt what the masked area should become.
     * @param onProgress percentage (0..100) plus a human readable status.
     */
    suspend fun inpaint(
        image: Bitmap,
        mask: Bitmap,
        prompt: String,
        onProgress: ((percent: Int, message: String) -> Unit)? = null
    ): Bitmap = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            throw WaveSpeedException("Wavespeed AI API key is not set. Add it in Settings > AI & Model Inpaint.")
        }
        val model = getModel()
        if (prompt.isBlank()) {
            throw WaveSpeedException("A prompt is required for Wavespeed AI inpainting.")
        }

        val maskFull = if (mask.width != image.width || mask.height != image.height) {
            Bitmap.createScaledBitmap(mask, image.width, image.height, true)
        } else {
            mask
        }

        // Upload only the area around the selection, with a margin of surrounding context.
        val crop = maskBounds(maskFull, image.width, image.height)
        val cropW = crop.width()
        val cropH = crop.height()
        val scale = computeScale(cropW, cropH)
        val sendW = max(1, (cropW * scale).roundToInt())
        val sendH = max(1, (cropH * scale).roundToInt())

        val temporaryBitmaps = ArrayList<Bitmap>()
        try {
            val srcCrop = Bitmap.createBitmap(image, crop.left, crop.top, cropW, cropH)
            temporaryBitmaps.add(srcCrop)
            val maskCrop = Bitmap.createBitmap(maskFull, crop.left, crop.top, cropW, cropH)
            temporaryBitmaps.add(maskCrop)
            val sendImage = if (sendW != cropW || sendH != cropH) {
                Bitmap.createScaledBitmap(srcCrop, sendW, sendH, true).also { temporaryBitmaps.add(it) }
            } else {
                srcCrop
            }
            val scaledMask = if (sendW != cropW || sendH != cropH) {
                Bitmap.createScaledBitmap(maskCrop, sendW, sendH, true).also { temporaryBitmaps.add(it) }
            } else {
                maskCrop
            }
            val sendMask = Bitmap.createBitmap(sendW, sendH, Bitmap.Config.ARGB_8888)
            temporaryBitmaps.add(sendMask)
            val maskCanvas = Canvas(sendMask)
            maskCanvas.drawColor(Color.BLACK)
            maskCanvas.drawBitmap(scaledMask, 0f, 0f, null)

            onProgress?.invoke(5, "Wavespeed AI: preparing 5%")
            val imageBytes = encodePng(sendImage)
            val maskBytes = encodePng(sendMask)

            onProgress?.invoke(8, "Wavespeed AI: uploading image 8%")
            val imageUrl = upload(imageBytes, "inpaint-source.png", apiKey)
            onProgress?.invoke(15, "Wavespeed AI: uploading mask 15%")
            val maskUrl = upload(maskBytes, "inpaint-mask.png", apiKey)
            onProgress?.invoke(22, "Wavespeed AI: submitting 22%")

            val payload = JsonObject().apply {
                addProperty("prompt", prompt.trim())
                addProperty("image", imageUrl)
                addProperty("mask_image", maskUrl)
                addProperty("enable_base64_output", true)
            }
            val submit = postJson("$BASE_URL/$model", apiKey, payload)
            val submitData = submit.getAsJsonObject("data") ?: submit
            val taskId = submitData.get("id")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.takeIf { it.isNotBlank() }
                ?: throw WaveSpeedException("Wavespeed AI did not return a task id.")
            onProgress?.invoke(25, "Wavespeed AI: queued 25%")

            val output = pollResult(taskId, apiKey, onProgress)
            onProgress?.invoke(92, "Wavespeed AI: loading result 92%")
            val downloaded = loadOutput(output, apiKey) { pct ->
                val overall = (92 + pct * 0.08f).toInt().coerceIn(92, 99)
                onProgress?.invoke(overall, "Wavespeed AI: downloading $overall%")
            }
            val scaledBack = if (downloaded.width != cropW || downloaded.height != cropH) {
                Bitmap.createScaledBitmap(downloaded, cropW, cropH, true)
            } else {
                downloaded
            }
            try {
                val composed = image.copy(Bitmap.Config.ARGB_8888, true)
                    ?: throw WaveSpeedException("Could not prepare the inpainted result.")
                Canvas(composed).drawBitmap(scaledBack, crop.left.toFloat(), crop.top.toFloat(), null)
                onProgress?.invoke(100, "Wavespeed AI: done 100%")
                composed
            } finally {
                if (scaledBack !== downloaded && !scaledBack.isRecycled) scaledBack.recycle()
                if (!downloaded.isRecycled) downloaded.recycle()
            }
        } finally {
            for (bitmap in temporaryBitmaps) {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            if (maskFull !== mask && !maskFull.isRecycled) maskFull.recycle()
        }
    }

    private fun maskBounds(mask: Bitmap, imageW: Int, imageH: Int): Rect {
        val w = mask.width
        val h = mask.height
        if (w <= 0 || h <= 0) throw WaveSpeedException("Paint or select an area to inpaint first.")
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                if ((pixels[rowOffset + x] ushr 24) != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) throw WaveSpeedException("Paint or select an area to inpaint first.")

        val scaleX = imageW.toFloat() / w
        val scaleY = imageH.toFloat() / h
        var left = (minX * scaleX).toInt()
        var top = (minY * scaleY).toInt()
        var right = ((maxX + 1) * scaleX).toInt()
        var bottom = ((maxY + 1) * scaleY).toInt()
        left = left.coerceIn(0, imageW - 1)
        top = top.coerceIn(0, imageH - 1)
        right = right.coerceIn(left + 1, imageW)
        bottom = bottom.coerceIn(top + 1, imageH)

        val margin = max(48, (max(right - left, bottom - top) * 0.35f).roundToInt())
        return Rect(
            (left - margin).coerceAtLeast(0),
            (top - margin).coerceAtLeast(0),
            (right + margin).coerceAtMost(imageW),
            (bottom + margin).coerceAtMost(imageH)
        )
    }

    private fun computeScale(w: Int, h: Int): Float {
        var scale = 1f
        val maxSide = max(w, h)
        val minSide = min(w, h)
        if (maxSide > MAX_DIMENSION) {
            scale = MAX_DIMENSION.toFloat() / maxSide
        }
        if (minSide > 0 && minSide * scale < MIN_DIMENSION) {
            scale = MIN_DIMENSION.toFloat() / minSide
        }
        if (maxSide > 0 && maxSide * scale > MAX_DIMENSION) {
            scale = MAX_DIMENSION.toFloat() / maxSide
        }
        return scale
    }

    private fun encodePng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private suspend fun pollResult(
        taskId: String,
        apiKey: String,
        onProgress: ((Int, String) -> Unit)?
    ): String {
        val url = "$BASE_URL/predictions/$taskId/result"
        val startedAt = System.currentTimeMillis()
        var failures = 0

        while (true) {
            delay(POLL_INTERVAL_MS)
            val json = try {
                getJson(url, apiKey)
            } catch (e: WaveSpeedException) {
                if (!isTransient(e)) throw e
                failures++
                if (failures > MAX_POLL_FAILURES) {
                    throw WaveSpeedException("Wavespeed AI polling failed: ${e.message}")
                }
                delay((1000L * failures * failures).coerceAtMost(15000L))
                continue
            }
            failures = 0

            val data = json.getAsJsonObject("data") ?: json
            val status = data.get("status")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            when (status) {
                "completed" -> {
                    val outputs = data.getAsJsonArray("outputs")
                    val first = outputs?.takeIf { it.size() > 0 }?.get(0)
                        ?: throw WaveSpeedException("Wavespeed AI returned no output image.")
                    return if (first.isJsonPrimitive) first.asString else first.toString()
                }
                "failed", "cancelled", "timeout", "deleted" -> {
                    val error = data.get("error")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    throw WaveSpeedException(
                        "Wavespeed AI task $status" + if (error.isNotBlank()) ": $error" else ""
                    )
                }
                else -> {
                    val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000f
                    val percent = (25 + (elapsedSeconds / ESTIMATED_TASK_SECONDS * 60f))
                        .toInt()
                        .coerceIn(25, 88)
                    onProgress?.invoke(percent, "Wavespeed AI: processing $percent%")
                }
            }
        }
    }

    private fun loadOutput(
        output: String,
        apiKey: String,
        onDownloadProgress: ((Int) -> Unit)? = null
    ): Bitmap {
        val trimmed = output.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val bytes = download(trimmed, onDownloadProgress)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw WaveSpeedException("Wavespeed AI returned an unreadable image.")
        }

        var data = trimmed
        if (data.startsWith("{")) {
            data = try {
                val obj = JsonParser.parseString(data).asJsonObject
                obj.get("url")?.asString ?: obj.get("image")?.asString ?: ""
            } catch (e: Exception) {
                ""
            }
            if (data.startsWith("http")) {
                val bytes = download(data, onDownloadProgress)
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw WaveSpeedException("Wavespeed AI returned an unreadable image.")
            }
        }
        if (data.startsWith("data:")) {
            data = data.substringAfter(",", "")
        }
        val bytes = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (e: Exception) {
            throw WaveSpeedException("Wavespeed AI returned an unsupported output format.")
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw WaveSpeedException("Wavespeed AI returned an unreadable image.")
    }

    private fun download(url: String, onProgress: ((Int) -> Unit)?): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 180000
            val code = conn.responseCode
            if (code !in 200..299) {
                throw WaveSpeedException("Failed to download the Wavespeed AI result (HTTP $code).", code)
            }
            val total = conn.contentLengthLong
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var written = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    written += read
                    if (total > 0) {
                        onProgress?.invoke(((written * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            return out.toByteArray()
        } catch (e: WaveSpeedException) {
            throw e
        } catch (e: Exception) {
            throw WaveSpeedException("Failed to download the Wavespeed AI result: ${e.message ?: "network error"}.", 0)
        } finally {
            try { conn.disconnect() } catch (ignored: Exception) {}
        }
    }

    private fun upload(bytes: ByteArray, filename: String, apiKey: String): String {
        val ticketBody = JsonObject().apply {
            addProperty("filename", filename)
            addProperty("size", bytes.size)
            addProperty("content_type", "image/png")
        }
        val ticket = postJson("$BASE_URL/media/uploads", apiKey, ticketBody)
        val data = ticket.getAsJsonObject("data")
            ?: throw WaveSpeedException("Wavespeed AI upload did not return data.")
        val downloadUrl = data.get("download_url")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
            ?: throw WaveSpeedException("Wavespeed AI upload did not return a download URL.")
        val uploadInfo = data.getAsJsonObject("upload")
            ?: throw WaveSpeedException("Wavespeed AI upload did not return an upload ticket.")
        val putUrl = uploadInfo.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
            ?: throw WaveSpeedException("Wavespeed AI upload ticket is missing its URL.")
        val method = uploadInfo.get("method")?.takeIf { it.isJsonPrimitive }?.asString ?: "PUT"
        val headers = uploadInfo.getAsJsonObject("headers")

        val conn = URL(putUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 180000
            conn.setFixedLengthStreamingMode(bytes.size)
            headers?.entrySet()?.forEach { entry ->
                val value = entry.value?.takeIf { it.isJsonPrimitive }?.asString
                if (value != null) conn.setRequestProperty(entry.key, value)
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw WaveSpeedException("Wavespeed AI file upload failed (HTTP $code).", code)
            }
        } catch (e: WaveSpeedException) {
            throw e
        } catch (e: Exception) {
            throw WaveSpeedException("Wavespeed AI file upload failed: ${e.message ?: "network error"}.", 0)
        } finally {
            try { conn.disconnect() } catch (ignored: Exception) {}
        }
        return downloadUrl
    }

    private fun postJson(url: String, apiKey: String, body: JsonObject): JsonObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val text = readResponse(conn, code)
            if (code !in 200..299) {
                throw WaveSpeedException(describeHttpError(code, text), code)
            }
            return try {
                JsonParser.parseString(text).asJsonObject
            } catch (e: Exception) {
                throw WaveSpeedException("Wavespeed AI returned an unreadable response.")
            }
        } catch (e: WaveSpeedException) {
            throw e
        } catch (e: Exception) {
            throw WaveSpeedException("Wavespeed AI request failed: ${e.message ?: "network error"}.", 0)
        } finally {
            try { conn.disconnect() } catch (ignored: Exception) {}
        }
    }

    private fun getJson(url: String, apiKey: String): JsonObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")

            val code = conn.responseCode
            val text = readResponse(conn, code)
            if (code !in 200..299) {
                throw WaveSpeedException(describeHttpError(code, text), code)
            }
            return try {
                JsonParser.parseString(text).asJsonObject
            } catch (e: Exception) {
                throw WaveSpeedException("Wavespeed AI returned an unreadable response.")
            }
        } catch (e: WaveSpeedException) {
            throw e
        } catch (e: Exception) {
            throw WaveSpeedException("Wavespeed AI connection failed: ${e.message ?: "network error"}.", 0)
        } finally {
            try { conn.disconnect() } catch (ignored: Exception) {}
        }
    }

    private fun readResponse(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return try {
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun describeHttpError(code: Int, body: String): String {
        val serverMessage = try {
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                ?: obj.get("error")?.takeIf { it.isJsonPrimitive }?.asString
                ?: obj.getAsJsonObject("data")?.get("error")?.takeIf { it.isJsonPrimitive }?.asString
        } catch (e: Exception) {
            null
        }
        val base = when (code) {
            400 -> "Invalid request (check the model ID and parameters)"
            401 -> "Invalid Wavespeed AI API key"
            403 -> "Access denied by Wavespeed AI (account or key allowlist)"
            402 -> "Insufficient Wavespeed AI balance"
            404 -> "Model not found — check the model ID in Settings"
            413 -> "Image is too large for Wavespeed AI"
            429 -> "Wavespeed AI rate limit exceeded — try again later"
            in 500..599 -> "Wavespeed AI server error"
            else -> "Wavespeed AI request failed"
        }
        return if (serverMessage.isNullOrBlank()) "$base (HTTP $code)" else "$base: $serverMessage"
    }

    private fun isTransient(e: WaveSpeedException): Boolean {
        if (e.httpCode == 0) return true
        return e.httpCode == 429 || e.httpCode == 500 || e.httpCode == 502 ||
            e.httpCode == 503 || e.httpCode == 504
    }

    companion object {
        private const val BASE_URL = "https://api.wavespeed.ai/api/v3"
        private const val MAX_DIMENSION = 1536
        private const val MIN_DIMENSION = 256
        private const val POLL_INTERVAL_MS = 3000L
        private const val ESTIMATED_TASK_SECONDS = 25f
        private const val MAX_POLL_FAILURES = 5

        const val PREF_API_KEY = "wavespeed_api_key"
        const val PREF_MODEL = "wavespeed_model"
        const val DEFAULT_MODEL = "wavespeed-ai/flux-fill-dev"
    }
}
