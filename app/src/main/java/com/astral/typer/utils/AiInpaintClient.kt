package com.astral.typer.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A third-party (cloud) inpainting provider. Each one is opt-in from Settings: while its checkbox
 * is off it is not offered in the inpainting engine list.
 */
enum class AiInpaintProvider(val displayName: String, val defaultModel: String) {
    WAVESPEED("Wavespeed (AI)", "wavespeed-ai/flux-2-klein-9b/edit"),
    FAL("Fal (AI)", "fal-ai/flux-2/klein/9b/edit"),
    AGNES("Agnes (AI)", "agnes-image-2.5-flash"),
    RUNWARE("Runware (AI)", "runware:400@2");

    val prefPrefix: String get() = name.lowercase()
    val prefEnabled: String get() = prefPrefix + "_enabled"
    val prefApiKey: String get() = prefPrefix + "_api_key"
    val prefModel: String get() = prefPrefix + "_model"
}

/**
 * Cloud inpainting through a 3rd-party provider.
 *
 * Cloud providers bill per run, not per pixel, so painted areas that sit next to each other are
 * sent as one crop in a single request instead of one run each. Only the neighbourhood of the
 * selection is uploaded so the request stays small. A crop larger than 1536 px is downsampled.
 *
 * Wavespeed models may take an explicit mask; the Fal, Agnes and Runware endpoints only accept a
 * reference image, so the whole crop is repainted and only the painted pixels are kept.
 */
class AiInpaintClient(
    private val context: Context,
    private val provider: AiInpaintProvider = AiInpaintProvider.WAVESPEED
) {

    class AiInpaintException(message: String, val httpCode: Int = 0) : Exception(message)

    /** One planned cloud run: regions that share a single request. */
    private class BatchPlan(
        val indices: List<Int>,
        val union: Rect
    )

    private val prefs = context.applicationContext
        .getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    /** Provider name for dialogs, e.g. `Fal (AI)`. */
    val displayName: String get() = provider.displayName

    /** The provider is listed in the inpainting engine list only while this is true. */
    fun isEnabled(): Boolean = prefs.getBoolean(provider.prefEnabled, false)

    fun getApiKey(): String = prefs.getString(provider.prefApiKey, "")?.trim().orEmpty()

    fun getModel(): String {
        val saved = prefs.getString(provider.prefModel, "")?.trim().orEmpty()
        return normalizeModelId(saved.ifEmpty { provider.defaultModel })
    }

    /** `wavespeed-ai/flux-2-pro/edit/` and `/wavespeed-ai/...` are not valid model paths. */
    private fun normalizeModelId(raw: String): String {
        val trimmed = raw.trim().trim('/')
        return if (trimmed.isEmpty()) provider.defaultModel else trimmed
    }

    fun isConfigured(): Boolean = getApiKey().isNotEmpty() && getModel().isNotEmpty()

    /**
     * Wavespeed multi-image edit models (`wavespeed-ai/flux-2-pro/edit`, `google/gemini-.../edit`)
     * take `images: [url]` and have no mask parameter, so they repaint the whole uploaded crop.
     * Mask-based inpainting models (`wavespeed-ai/flux-fill-dev`, LaMa, ...) take `image+mask_image`.
     * Fal, Agnes and Runware never take a mask.
     */
    private fun usesMask(model: String): Boolean {
        if (provider != AiInpaintProvider.WAVESPEED) return false
        val id = model.lowercase()
        if (MASK_MODEL_HINTS.any { id.contains(it) }) return false
        return !(IMAGE_LIST_MODEL_HINTS.any { id.contains(it) } || id.endsWith("/edit"))
    }

    /** How many provider runs the given areas need for the configured model. */
    fun estimateRequests(regions: List<Rect>, imageBounds: Rect): Int {
        if (regions.isEmpty() || imageBounds.isEmpty) return 0
        return planBatches(regions, imageBounds).size
    }

    /**
     * Human readable summary for the prompt dialog, e.g.
     * `3 areas -> 1 Wavespeed run (~$0.06)`.
     */
    fun describeRequestPlan(regions: List<Rect>, imageBounds: Rect): String {
        val runs = estimateRequests(regions, imageBounds)
        if (runs <= 0) return ""
        val areas = if (regions.size == 1) "1 area" else "${regions.size} areas"
        val runsText = if (runs == 1) "1 ${provider.displayName} run" else "$runs ${provider.displayName} runs"
        val price = unitPriceUsd(getModel())
        val cost = if (price == null) "" else " (~${formatUsd(price * runs)})"
        return "$areas \u2192 $runsText$cost"
    }

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
        val apiKey = requireApiKey()
        if (prompt.isBlank()) {
            throw AiInpaintException("A prompt is required for ${provider.displayName} inpainting.")
        }
        val model = getModel()
        val useMask = usesMask(model)

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
            val sendMask = if (useMask) {
                blackBackedMask(scaledMask, sendW, sendH, temporaryBitmaps)
            } else {
                null
            }

            val generated = runProvider(sendImage, sendMask, scaledMask, model, prompt, apiKey, onProgress)
            val scaledBack = if (generated.width != cropW || generated.height != cropH) {
                Bitmap.createScaledBitmap(generated, cropW, cropH, true).also {
                    if (!generated.isRecycled) generated.recycle()
                }
            } else {
                generated
            }
            try {
                val composed = image.copy(Bitmap.Config.ARGB_8888, true)
                    ?: throw AiInpaintException("Could not prepare the inpainted result.")
                val composedCanvas = Canvas(composed)
                if (useMask) {
                    composedCanvas.drawBitmap(scaledBack, crop.left.toFloat(), crop.top.toFloat(), null)
                } else {
                    // Without a mask the model repaints the whole crop, so keep everything outside
                    // the painted selection and take only the generated pixels inside it.
                    // The mask is the destination and the model output is drawn over it with
                    // SRC_IN, otherwise the kept pixels would take the mask's white colour.
                    val selected = try {
                        Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
                    } catch (e: OutOfMemoryError) {
                        throw AiInpaintException("Could not prepare the inpainted result.")
                    }
                    try {
                        val srcIn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                        }
                        val selectedCanvas = Canvas(selected)
                        selectedCanvas.drawBitmap(maskCrop, 0f, 0f, null)
                        selectedCanvas.drawBitmap(scaledBack, 0f, 0f, srcIn)
                        composedCanvas.drawBitmap(selected, crop.left.toFloat(), crop.top.toFloat(), null)
                    } finally {
                        if (!selected.isRecycled) selected.recycle()
                    }
                }
                onProgress?.invoke(100, "Inpainting: done 100%")
                composed
            } finally {
                if (!scaledBack.isRecycled) scaledBack.recycle()
            }
        } finally {
            for (bitmap in temporaryBitmaps) {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            if (maskFull !== mask && !maskFull.isRecycled) maskFull.recycle()
        }
    }

    /**
     * Inpaints many areas with as few provider runs as possible and returns one result per
     * region (same size as the region, null when that region could not be produced).
     *
     * Every run sends one crop that holds several nearby areas. Mask models get the mask of all
     * blobs in that crop; image edit models repaint the whole crop and only the painted pixels
     * are kept.
     */
    suspend fun inpaintRegions(
        regions: List<Rect>,
        imageBounds: Rect,
        prompt: String,
        sourceProvider: suspend (Rect) -> Bitmap?,
        maskProvider: suspend (Rect) -> Bitmap?,
        onProgress: ((percent: Int, message: String) -> Unit)? = null
    ): List<Bitmap?> = withContext(Dispatchers.IO) {
        val apiKey = requireApiKey()
        if (prompt.isBlank()) {
            throw AiInpaintException("A prompt is required for ${provider.displayName} inpainting.")
        }
        val model = getModel()
        val useMask = usesMask(model)
        val results = arrayOfNulls<Bitmap>(regions.size)
        if (regions.isEmpty() || imageBounds.isEmpty) return@withContext results.toList()

        val plans = planBatches(regions, imageBounds)
        if (plans.isEmpty()) return@withContext results.toList()

        for ((planIndex, plan) in plans.withIndex()) {
            val base = planIndex * 100 / plans.size
            val span = max(1, 100 / plans.size)
            val report: ((Int, String) -> Unit)? = onProgress?.let { callback ->
                { percent: Int, message: String ->
                    val overall = (base + percent * span / 100).coerceIn(0, 100)
                    callback(overall, message)
                }
            }
            runMaskGroup(
                plan, regions, model, prompt, apiKey, useMask, sourceProvider, maskProvider, report
            ) { index, bitmap ->
                results[index]?.let { if (!it.isRecycled) it.recycle() }
                results[index] = bitmap
            }
        }
        results.toList()
    }

    /** Sends one crop that carries every area of [plan], masked for mask models. */
    private suspend fun runMaskGroup(
        plan: BatchPlan,
        regions: List<Rect>,
        model: String,
        prompt: String,
        apiKey: String,
        useMask: Boolean,
        sourceProvider: suspend (Rect) -> Bitmap?,
        maskProvider: suspend (Rect) -> Bitmap?,
        onProgress: ((Int, String) -> Unit)?,
        onResult: (Int, Bitmap) -> Unit
    ) {
        val union = plan.union
        if (union.isEmpty) return
        val source = sourceProvider(union) ?: return
        val mask = if (useMask) maskProvider(union) else null
        if (useMask && mask == null) {
            if (!source.isRecycled) source.recycle()
            return
        }
        var generated: Bitmap? = null
        try {
            val output = request(
                source, if (useMask) mask else null, model, prompt, apiKey, useMask, onProgress
            )
            generated = output
            for (index in plan.indices) {
                val region = regions.getOrNull(index) ?: continue
                if (region.width() <= 0 || region.height() <= 0) continue
                val patch = try {
                    Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    continue
                }
                Canvas(patch).drawBitmap(
                    output,
                    (union.left - region.left).toFloat(),
                    (union.top - region.top).toFloat(),
                    null
                )
                onResult(index, patch)
            }
        } finally {
            if (!source.isRecycled) source.recycle()
            if (mask != null && !mask.isRecycled) mask.recycle()
            if (generated != null && !generated.isRecycled) generated.recycle()
        }
    }

    /** Sends one prepared crop (plus its mask for mask models) and returns the raw result. */
    private suspend fun request(
        image: Bitmap,
        mask: Bitmap?,
        model: String,
        prompt: String,
        apiKey: String,
        useMask: Boolean,
        onProgress: ((Int, String) -> Unit)?
    ): Bitmap {
        if (useMask && mask == null) {
            throw AiInpaintException("This model needs a mask, but no selection was provided.")
        }
        if (image.width <= 0 || image.height <= 0) {
            throw AiInpaintException("The selected area is empty.")
        }

        val scaledMask = if (useMask && mask != null && (mask.width != image.width || mask.height != image.height)) {
            Bitmap.createScaledBitmap(mask, image.width, image.height, true)
        } else {
            mask
        }

        val scale = computeScale(image.width, image.height)
        val sendW = max(1, (image.width * scale).roundToInt())
        val sendH = max(1, (image.height * scale).roundToInt())
        val sendImage = if (sendW != image.width || sendH != image.height) {
            Bitmap.createScaledBitmap(image, sendW, sendH, true)
        } else {
            image
        }
        val sendMaskSource = if (scaledMask != null && (sendW != scaledMask.width || sendH != scaledMask.height)) {
            Bitmap.createScaledBitmap(scaledMask, sendW, sendH, true)
        } else {
            scaledMask
        }

        val temporaries = ArrayList<Bitmap>()
        if (scaledMask != null && scaledMask !== mask) temporaries.add(scaledMask)
        if (sendImage !== image) temporaries.add(sendImage)
        if (sendMaskSource != null && sendMaskSource !== scaledMask && sendMaskSource !== mask) temporaries.add(sendMaskSource)
        try {
            val sendMask = if (useMask && sendMaskSource != null) {
                blackBackedMask(sendMaskSource, sendW, sendH, temporaries)
            } else {
                null
            }
            val output = runProvider(sendImage, sendMask, model, prompt, apiKey, onProgress)
            return if (output.width != image.width || output.height != image.height) {
                Bitmap.createScaledBitmap(output, image.width, image.height, true).also {
                    if (!output.isRecycled) output.recycle()
                }
            } else {
                output
            }
        } finally {
            for (bitmap in temporaries) {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    /** Opaque black background with the (white) mask painted on it, as providers expect. */
    private fun blackBackedMask(
        mask: Bitmap,
        width: Int,
        height: Int,
        temporaries: MutableList<Bitmap>
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        temporaries.add(bitmap)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(mask, 0f, 0f, null)
        return bitmap
    }

    private fun requireApiKey(): String {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            throw AiInpaintException(
                "${provider.displayName} API key is not set. Add it in Settings > AI & Model Inpaint."
            )
        }
        return apiKey
    }

    /** Runs the prepared crop on the configured provider and returns its answer bitmap. */
    private suspend fun runProvider(
        image: Bitmap,
        mask: Bitmap?,
        model: String,
        prompt: String,
        apiKey: String,
        onProgress: ((Int, String) -> Unit)?
    ): Bitmap = when (provider) {
        AiInpaintProvider.WAVESPEED -> runWaveSpeed(image, mask, model, prompt, apiKey, onProgress)
        AiInpaintProvider.FAL -> runFal(image, model, prompt, apiKey, onProgress)
        AiInpaintProvider.AGNES -> runAgnes(image, model, prompt, apiKey, onProgress)
        AiInpaintProvider.RUNWARE -> runRunware(image, model, prompt, apiKey, onProgress)
    }

    // ---------------------------------------------------------------- Wavespeed

    private suspend fun runWaveSpeed(
        image: Bitmap,
        mask: Bitmap?,
        model: String,
        prompt: String,
        apiKey: String,
        onProgress: ((Int, String) -> Unit)?
    ): Bitmap {
        onProgress?.invoke(5, "Inpainting: preparing 5%")
        onProgress?.invoke(8, "Inpainting: uploading image 8%")
        val imageUrl = uploadMedia(encodePng(image), "inpaint-source.png", apiKey)
        val maskUrl = if (mask != null) {
            onProgress?.invoke(15, "Inpainting: uploading mask 15%")
            uploadMedia(encodePng(mask), "inpaint-mask.png", apiKey)
        } else {
            null
        }
        onProgress?.invoke(22, "Inpainting: submitting 22%")

        val payload = JsonObject().apply {
            addProperty("prompt", prompt.trim())
            if (maskUrl != null) {
                addProperty("image", imageUrl)
                addProperty("mask_image", maskUrl)
            } else {
                // Edit models only accept a list of reference images and ignore any mask.
                add("images", JsonArray().apply { add(imageUrl) })
            }
            addProperty("enable_base64_output", true)
        }
        val headers = mapOf("Authorization" to "Bearer $apiKey")
        val submit = postJson("$WAVESPEED_BASE_URL/$model", headers, payload)
        val submitData = submit.getAsJsonObject("data") ?: submit
        val taskId = submitData.get("id")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
            ?: throw AiInpaintException("${provider.displayName} did not return a task id.")
        onProgress?.invoke(25, "Inpainting: queued 25%")

        val output = pollWaveSpeedResult(taskId, apiKey, onProgress)
        onProgress?.invoke(92, "Inpainting: loading result 92%")
        return loadOutput(output, onProgress)
    }

    private suspend fun pollWaveSpeedResult(
        taskId: String,
        apiKey: String,
        onProgress: ((Int, String) -> Unit)?
    ): String {
        val url = "$WAVESPEED_BASE_URL/predictions/$taskId/result"
        val headers = mapOf("Authorization" to "Bearer $apiKey")
        val startedAt = System.currentTimeMillis()
        var failures = 0

        while (true) {
            delay(POLL_INTERVAL_MS)
            val json = try {
                getJson(url, headers)
            } catch (e: AiInpaintException) {
                if (!isTransient(e)) throw e
                failures++
                if (failures > MAX_POLL_FAILURES) {
                    throw AiInpaintException("${provider.displayName} polling failed: ${e.message}")
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
                        ?: throw AiInpaintException("${provider.displayName} returned no output image.")
                    return if (first.isJsonPrimitive) first.asString else first.toString()
                }
                "failed", "cancelled", "timeout", "deleted" -> {
                    val error = data.get("error")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    throw AiInpaintException(
                        "${provider.displayName} task $status" + if (error.isNotBlank()) ": $error" else ""
                    )
                }
                else -> {
                    val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000f
                    val percent = (25 + (elapsedSeconds / ESTIMATED_TASK_SECONDS * 60f))
                        .toInt()
                        .coerceIn(25, 88)
                    onProgress?.invoke(percent, "Inpainting: processing $percent%")
                }
            }
        }
    }

    private fun uploadMedia(bytes: ByteArray, filename: String, apiKey: String): String {
        val ticketBody = JsonObject().apply {
            addProperty("filename", filename)
            addProperty("size", bytes.size)
            addProperty("content_type", "image/png")
        }
        val ticket = postJson(
            "$WAVESPEED_BASE_URL/media/uploads",
            mapOf("Authorization" to "Bearer $apiKey"),
            ticketBody
        )
        val data = ticket.getAsJsonObject("data")
            ?: throw AiInpaintException("${provider.displayName} upload did not return data.")
        val downloadUrl = data.get("download_url")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
            ?: throw AiInpaintException("${provider.displayName} upload did not return a download URL.")
        val uploadInfo = data.getAsJsonObject("upload")
            ?: throw AiInpaintException("${provider.displayName} upload did not return an upload ticket.")
        val putUrl = uploadInfo.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
            ?: throw AiInpaintException("${provider.displayName} upload ticket is missing its URL.")
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
                throw AiInpaintException(
                    "${provider.displayName} file upload failed (HTTP $code).", code
                )
            }
        } catch (e: AiInpaintException) {
            throw e
        } catch (e: Exception) {
            throw AiInpaintException(
                "${provider.displayName} file upload failed: ${e.message ?: "network error"}.", 0
            )
        } finally {
            try { conn.disconnect() } catch (ignored: Exception) {}
        }
        return downloadUrl
    }

    // --------------------------------------------------------------------- Fal

    private suspend fun runFal(
        image: Bitmap,
        model: String,
        prompt: String,
        apiKey: String,
        onProgress: ((Int, String) -> Unit)?
    ): Bitmap {
        onProgress?.invoke(8, "Inpainting: preparing image 8%")
        val dataUri = toDataUri(encodePng(image))
        onProgress?.invoke(22, "Inpainting: submitting 22%")

        val payload = JsonObject().apply {
            addProperty("prompt", prompt.trim())
            add("image_urls", JsonArray().apply { add(dataUri) })
            addProperty("num_images", 1)
            addProperty("sync_mode", false)
            addProperty("output_format", "png")
        }
        val headers = mapOf("Authorization" to "Key $apiKey")
        val submit = postJson("$FAL_BASE_URL/$model", headers, payload)
        val requestId = submit.get("request_id")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
            ?: throw AiInpaintException("${provider.displayName} did not return a request id.")
        val statusUrl = submit.get("status_url")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
            ?: "$FAL_BASE_URL/$model/requests/$requestId/status"
        val resultUrl = submit.get("response_url")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
            ?: "$FAL_BASE_URL/$model/requests/$requestId"
        onProgress?.invoke(25, "Inpainting: queued 25%")

        val startedAt = System.currentTimeMillis()
        var failures = 0
        while (true) {
            delay(POLL_INTERVAL_MS)
            val json = try {
                getJson(statusUrl, headers)
            } catch (e: AiInpaintException) {
                if (!isTransient(e)) throw e
                failures++
                if (failures > MAX_POLL_FAILURES) {
                    throw AiInpaintException("${provider.displayName} polling failed: ${e.message}")
                }
                delay((1000L * failures * failures).coerceAtMost(15000L))
                continue
            }
            failures = 0

            val status = json.get("status")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.uppercase(Locale.US)
                .orEmpty()
            when (status) {
                "COMPLETED" -> {
                    onProgress?.invoke(92, "Inpainting: loading result 92%")
                    val result = getJson(resultUrl, headers, readTimeoutMs = 120000)
                    val images = result.getAsJsonArray("images")
                    val first = if (images != null && images.size() > 0) images.get(0) else null
                    val url = if (first != null && first.isJsonObject) {
                        first.asJsonObject.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                    } else {
                        first?.takeIf { it.isJsonPrimitive }?.asString
                    }
                    if (url.isNullOrBlank()) {
                        throw AiInpaintException("${provider.displayName} returned no output image.")
                    }
                    return decodeDownload(url, onProgress)
                }
                "FAILED", "ERROR" -> {
                    val error = json.get("error")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    throw AiInpaintException(
                        "${provider.displayName} task failed" + if (error.isNotBlank()) ": $error" else ""
                    )
                }
                else -> {
                    val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000f
                    val percent = (25 + (elapsedSeconds / ESTIMATED_TASK_SECONDS * 60f))
                        .toInt()
                        .coerceIn(25, 88)
                    onProgress?.invoke(percent, "Inpainting: processing $percent%")
                }
            }
        }
    }

    // ------------------------------------------------------------------- Agnes

    private suspend fun runAgnes(
        image: Bitmap,
        model: String,
        prompt: String,
        apiKey: String,
        onProgress: ((Int, String) -> Unit)?
    ): Bitmap {
        onProgress?.invoke(8, "Inpainting: preparing image 8%")
        val dataUri = toDataUri(encodePng(image))
        onProgress?.invoke(22, "Inpainting: submitting 22%")
        val response = postAgnes(
            agnesPayload(
                model = model,
                prompt = prompt.trim(),
                size = agnesSizeTier(max(image.width, image.height)),
                ratio = agnesRatio(image.width, image.height),
                dataUri = dataUri
            ),
            apiKey
        )
        onProgress?.invoke(92, "Inpainting: loading result 92%")
        return decodeAgnesResponse(response, onProgress)
    }

    private fun agnesPayload(
        model: String,
        prompt: String,
        size: String,
        ratio: String,
        dataUri: String
    ): JsonObject {
        val extraBody = JsonObject().apply {
            add("image", JsonArray().apply { add(dataUri) })
            addProperty("response_format", "url")
        }
        return JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", prompt)
            addProperty("size", size)
            addProperty("ratio", ratio)
            add("extra_body", extraBody)
        }
    }

    /** Agnes answers synchronously, so the request itself can take a while. */
    private fun postAgnes(payload: JsonObject, apiKey: String): JsonObject = postJson(
        "$AGNES_BASE_URL/images/generations",
        mapOf("Authorization" to "Bearer $apiKey"),
        payload,
        readTimeoutMs = AGNES_READ_TIMEOUT_MS
    )

    private fun decodeAgnesResponse(
        response: JsonObject,
        onProgress: ((Int, String) -> Unit)?
    ): Bitmap {
        val data = response.getAsJsonArray("data")
        val first = if (data != null && data.size() > 0) data.get(0) else null
        val entry = first?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw AiInpaintException("${provider.displayName} returned no output image.")
        val base64 = entry.get("b64_json")?.takeIf { it.isJsonPrimitive }?.asString
        val url = entry.get("url")?.takeIf { it.isJsonPrimitive }?.asString
        return when {
            !base64.isNullOrBlank() -> decodeBase64Image(base64)
            !url.isNullOrBlank() -> decodeDownload(url, onProgress)
            else -> throw AiInpaintException("${provider.displayName} returned no output image.")
        }
    }

    private fun agnesSizeTier(maxSide: Int): String = when {
        maxSide <= 1024 -> "1K"
        maxSide <= 2048 -> "2K"
        maxSide <= 3072 -> "3K"
        else -> "4K"
    }

    /** The supported aspect ratio closest to the crop, so the answer is not distorted. */
    private fun agnesRatio(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "1:1"
        val target = width.toDouble() / height.toDouble()
        var best = "1:1"
        var bestDiff = Double.MAX_VALUE
        for ((label, value) in AGNES_RATIOS) {
            val diff = abs(value - target)
            if (diff < bestDiff) {
                bestDiff = diff
                best = label
            }
        }
        return best
    }

    // ----------------------------------------------------------------- Runware

    private suspend fun runRunware(
        image: Bitmap,
        model: String,
        prompt: String,
        apiKey: String,
        onProgress: ((Int, String) -> Unit)?
    ): Bitmap {
        onProgress?.invoke(8, "Inpainting: preparing image 8%")
        val dataUri = toDataUri(encodePng(image))
        // Runware needs explicit output dimensions: multiples of 16 between 128 and 2048.
        val width = runwareDimension(image.width)
        val height = runwareDimension(image.height)
        onProgress?.invoke(22, "Inpainting: submitting 22%")

        val task = JsonObject().apply {
            addProperty("taskType", "imageInference")
            addProperty("taskUUID", UUID.randomUUID().toString())
            addProperty("model", model)
            addProperty("positivePrompt", prompt.trim())
            addProperty("width", width)
            addProperty("height", height)
            add("inputs", JsonObject().apply {
                add("referenceImages", JsonArray().apply { add(dataUri) })
            })
            addProperty("outputType", "URL")
            addProperty("outputFormat", "PNG")
            addProperty("deliveryMethod", "sync")
        }
        // The REST API takes an array of tasks; sync delivery answers in the same call.
        val response = postJson(
            RUNWARE_BASE_URL,
            mapOf("Authorization" to "Bearer $apiKey"),
            JsonArray().apply { add(task) },
            readTimeoutMs = RUNWARE_READ_TIMEOUT_MS
        )
        onProgress?.invoke(92, "Inpainting: loading result 92%")

        val data = response.getAsJsonArray("data")
        val first = if (data != null && data.size() > 0) data.get(0) else null
        val entry = first?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw AiInpaintException("${provider.displayName} returned no output image.")
        val base64 = entry.get("imageBase64Data")?.takeIf { it.isJsonPrimitive }?.asString
        val dataUriOut = entry.get("imageDataURI")?.takeIf { it.isJsonPrimitive }?.asString
        val url = entry.get("imageURL")?.takeIf { it.isJsonPrimitive }?.asString
        return when {
            !base64.isNullOrBlank() -> decodeBase64Image(base64)
            !dataUriOut.isNullOrBlank() -> decodeBase64Image(dataUriOut)
            !url.isNullOrBlank() -> decodeDownload(url, onProgress)
            else -> throw AiInpaintException("${provider.displayName} returned no output image.")
        }
    }

    /** Snaps a side to the 16 px grid Runware accepts, inside 128..2048. */
    private fun runwareDimension(side: Int): Int {
        val stepped = (side.toFloat() / RUNWARE_DIMENSION_STEP).roundToInt() * RUNWARE_DIMENSION_STEP
        return stepped.coerceIn(RUNWARE_MIN_DIMENSION, RUNWARE_MAX_DIMENSION)
    }

    // ----------------------------------------------------------------- Shared

    /**
     * Groups regions into runs. Crops that sit within [mergeGap] px of a group share that group,
     * so the letters of one SFX stay in a single request instead of one run each. A group is only
     * kept inside [maxUnion]: mask models start another run when the union is bigger, while edit
     * models may exceed it and the crop is downsampled before upload.
     */
    private fun planGroups(
        regions: List<Rect>,
        imageBounds: Rect,
        mergeGap: Int,
        maxUnion: Int
    ): List<BatchPlan> {
        val groups = ArrayList<MutableList<Int>>()
        val bounds = ArrayList<Rect>()

        for (index in regions.indices) {
            val crop = cropWithMargin(regions[index], imageBounds)
            if (crop.isEmpty) continue
            var best = -1
            var bestGap = Int.MAX_VALUE
            for (g in groups.indices) {
                val merged = Rect(bounds[g]).apply { union(crop) }
                if (merged.width() > maxUnion || merged.height() > maxUnion) continue
                val gap = gapBetween(bounds[g], crop)
                if (gap <= mergeGap && gap < bestGap) {
                    best = g
                    bestGap = gap
                }
            }
            if (best >= 0) {
                groups[best].add(index)
                bounds[best].union(crop)
            } else {
                groups.add(mutableListOf(index))
                bounds.add(Rect(crop))
            }
        }

        val plans = ArrayList<BatchPlan>(groups.size)
        for (g in groups.indices) plans.add(BatchPlan(ArrayList(groups[g]), Rect(bounds[g])))
        return plans
    }

    /** Shortest distance between two rects, 0 when they touch or overlap. */
    private fun gapBetween(a: Rect, b: Rect): Int {
        val dx = max(0, max(a.left - b.right, b.left - a.right))
        val dy = max(0, max(a.top - b.bottom, b.top - a.bottom))
        return max(dx, dy)
    }

    private fun planBatches(regions: List<Rect>, imageBounds: Rect): List<BatchPlan> {
        return if (!usesMask(getModel())) {
            // Edit models may exceed 1536 px: the crop is downsampled and only the painted pixels
            // of the answer are kept, so no extra run is needed for a slightly larger crop.
            planGroups(regions, imageBounds, NEARBY_GAP, Int.MAX_VALUE)
        } else {
            // Mask models keep context untouched, so only the 1536 px limit matters.
            planGroups(regions, imageBounds, Int.MAX_VALUE, MAX_DIMENSION)
        }
    }

    /** The crop that is uploaded for [region]: the area itself plus surrounding context. */
    private fun cropWithMargin(region: Rect, imageBounds: Rect): Rect {
        if (region.isEmpty) return Rect()
        val margin = max(48, (max(region.width(), region.height()) * 0.35f).roundToInt())
        val crop = Rect(region)
        crop.inset(-margin, -margin)
        crop.left = crop.left.coerceAtLeast(imageBounds.left)
        crop.top = crop.top.coerceAtLeast(imageBounds.top)
        crop.right = crop.right.coerceAtMost(imageBounds.right)
        crop.bottom = crop.bottom.coerceAtMost(imageBounds.bottom)
        return crop
    }

    /** Base price per run for models we know, so the prompt dialog can show a rough cost. */
    private fun unitPriceUsd(model: String): Double? {
        if (provider != AiInpaintProvider.WAVESPEED) return null
        val id = model.lowercase()
        return when {
            id.contains("flux-fill") -> 0.035
            id.contains("gemini-2.5-flash-image") -> 0.038
            id.contains("flux-2-pro") -> 0.06
            else -> null
        }
    }

    private fun formatUsd(value: Double): String {
        var text = String.format(Locale.US, "%.3f", value)
        while (text.contains('.') && text.endsWith('0')) text = text.dropLast(1)
        if (text.endsWith('.')) text += "00"
        return "$$text"
    }

    private fun maskBounds(mask: Bitmap, imageW: Int, imageH: Int): Rect {
        val w = mask.width
        val h = mask.height
        if (w <= 0 || h <= 0) throw AiInpaintException("Paint or select an area to inpaint first.")
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
        if (maxX < 0) throw AiInpaintException("Paint or select an area to inpaint first.")

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

    private fun toDataUri(bytes: ByteArray): String =
        "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decodeDownload(
        url: String,
        onProgress: ((Int, String) -> Unit)?
    ): Bitmap {
        val bytes = download(url) { pct ->
            val overall = (92 + pct * 0.08f).toInt().coerceIn(92, 99)
            onProgress?.invoke(overall, "Inpainting: downloading $overall%")
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw AiInpaintException("${provider.displayName} returned an unreadable image.")
    }

    private fun decodeBase64Image(raw: String): Bitmap {
        var data = raw.trim()
        if (data.startsWith("data:")) data = data.substringAfter(",", "")
        val bytes = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (e: Exception) {
            throw AiInpaintException("${provider.displayName} returned an unsupported output format.")
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw AiInpaintException("${provider.displayName} returned an unreadable image.")
    }

    /** Wavespeed returns an URL, a JSON blob, or a Base64 payload. */
    private fun loadOutput(output: String, onProgress: ((Int, String) -> Unit)?): Bitmap {
        val trimmed = output.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return decodeDownload(trimmed, onProgress)
        }

        var data = trimmed
        if (data.startsWith("{")) {
            data = try {
                val obj = JsonParser.parseString(data).asJsonObject
                obj.get("url")?.asString ?: obj.get("image")?.asString ?: ""
            } catch (e: Exception) {
                ""
            }
            if (data.startsWith("http")) return decodeDownload(data, onProgress)
        }
        if (data.startsWith("data:")) data = data.substringAfter(",", "")
        return decodeBase64Image(data)
    }

    private fun download(url: String, onProgress: ((Int) -> Unit)?): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 180000
            val code = conn.responseCode
            if (code !in 200..299) {
                throw AiInpaintException(
                    "Failed to download the ${provider.displayName} result (HTTP $code).", code
                )
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
        } catch (e: AiInpaintException) {
            throw e
        } catch (e: Exception) {
            throw AiInpaintException(
                "Failed to download the ${provider.displayName} result: ${e.message ?: "network error"}.", 0
            )
        } finally {
            try { conn.disconnect() } catch (ignored: Exception) {}
        }
    }

    private fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JsonElement,
        readTimeoutMs: Int = 120000
    ): JsonObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = readTimeoutMs
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val text = readResponse(conn, code)
            if (code !in 200..299) {
                throw AiInpaintException(describeHttpError(code, text), code)
            }
            return try {
                JsonParser.parseString(text).asJsonObject
            } catch (e: Exception) {
                throw AiInpaintException("${provider.displayName} returned an unreadable response.")
            }
        } catch (e: AiInpaintException) {
            throw e
        } catch (e: Exception) {
            throw AiInpaintException(
                "${provider.displayName} request failed: ${e.message ?: "network error"}.", 0
            )
        } finally {
            try { conn.disconnect() } catch (ignored: Exception) {}
        }
    }

    private fun getJson(
        url: String,
        headers: Map<String, String>,
        readTimeoutMs: Int = 60000
    ): JsonObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = readTimeoutMs
            for ((name, value) in headers) conn.setRequestProperty(name, value)

            val code = conn.responseCode
            val text = readResponse(conn, code)
            if (code !in 200..299) {
                throw AiInpaintException(describeHttpError(code, text), code)
            }
            return try {
                JsonParser.parseString(text).asJsonObject
            } catch (e: Exception) {
                throw AiInpaintException("${provider.displayName} returned an unreadable response.")
            }
        } catch (e: AiInpaintException) {
            throw e
        } catch (e: Exception) {
            throw AiInpaintException(
                "${provider.displayName} connection failed: ${e.message ?: "network error"}.", 0
            )
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
            val firstError = obj.getAsJsonArray("errors")
                ?.firstOrNull { it.isJsonObject }
                ?.asJsonObject
                ?.get("message")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
            firstError
                ?: obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                ?: obj.get("error")?.takeIf { it.isJsonPrimitive }?.asString
                ?: obj.get("detail")?.takeIf { it.isJsonPrimitive }?.asString
                ?: obj.getAsJsonObject("data")?.get("error")?.takeIf { it.isJsonPrimitive }?.asString
        } catch (e: Exception) {
            null
        }
        val name = provider.displayName
        val base = when (code) {
            400 -> "Invalid request (check the model ID and parameters)"
            401 -> "Invalid $name API key"
            403 -> "Access denied by $name (account or key allowlist)"
            402 -> "Insufficient $name balance"
            404 -> "Model not found — check the model ID in Settings"
            413 -> "Image is too large for $name"
            429 -> "$name rate limit exceeded — try again later"
            in 500..599 -> "$name server error"
            else -> "$name request failed"
        }
        return if (serverMessage.isNullOrBlank()) "$base (HTTP $code)" else "$base: $serverMessage"
    }

    private fun isTransient(e: AiInpaintException): Boolean {
        if (e.httpCode == 0) return true
        return e.httpCode == 429 || e.httpCode == 500 || e.httpCode == 502 ||
            e.httpCode == 503 || e.httpCode == 504
    }

    companion object {
        private const val WAVESPEED_BASE_URL = "https://api.wavespeed.ai/api/v3"
        private const val FAL_BASE_URL = "https://queue.fal.run"
        private const val AGNES_BASE_URL = "https://apihub.agnes-ai.com/v1"
        private const val RUNWARE_BASE_URL = "https://api.runware.ai/v1"
        private const val MAX_DIMENSION = 1536
        private const val MIN_DIMENSION = 256
        private const val NEARBY_GAP = 128
        private const val POLL_INTERVAL_MS = 3000L
        private const val ESTIMATED_TASK_SECONDS = 25f
        private const val MAX_POLL_FAILURES = 5
        private const val AGNES_READ_TIMEOUT_MS = 300000
        private const val RUNWARE_READ_TIMEOUT_MS = 180000
        private const val RUNWARE_DIMENSION_STEP = 16
        private const val RUNWARE_MIN_DIMENSION = 128
        private const val RUNWARE_MAX_DIMENSION = 2048

        /** Models that really take a mask and inpaint only the selected pixels. */
        private val MASK_MODEL_HINTS = listOf("flux-fill", "inpaint", "lama")

        /** Models whose schema is `images: [url]` with no mask field. */
        private val IMAGE_LIST_MODEL_HINTS = listOf("gemini", "nano-banana", "flux-2", "seedream")

        private val AGNES_RATIOS = listOf(
            "1:1" to 1.0,
            "3:4" to 3.0 / 4.0,
            "4:3" to 4.0 / 3.0,
            "16:9" to 16.0 / 9.0,
            "9:16" to 9.0 / 16.0,
            "2:3" to 2.0 / 3.0,
            "3:2" to 3.0 / 2.0,
            "21:9" to 21.0 / 9.0
        )

        /** Legacy pref keys of the original Wavespeed-only build. */
        const val PREF_API_KEY = "wavespeed_api_key"
        const val PREF_MODEL = "wavespeed_model"
        const val DEFAULT_MODEL = "wavespeed-ai/flux-2-klein-9b/edit"
    }
}
