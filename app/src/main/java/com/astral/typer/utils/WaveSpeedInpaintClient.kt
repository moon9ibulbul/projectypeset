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
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * WaveSpeed AI cloud inpainting (https://wavespeed.ai/docs/rest-api).
 *
 * Wavespeed bills per run, not per pixel, so painted areas that sit next to each other are sent
 * as one crop in a single request instead of one run each. Only the neighbourhood of the
 * selection is uploaded so the request stays small. A crop larger than 1536 px is downsampled;
 * for edit models (no mask input) only the painted pixels of the answer are kept.
 */
class WaveSpeedInpaintClient(private val context: Context) {

    class WaveSpeedException(message: String, val httpCode: Int = 0) : Exception(message)

    /** One planned Wavespeed run: regions that share a single request. */
    private class BatchPlan(
        val indices: List<Int>,
        val union: Rect
    )

    private val prefs = context.applicationContext
        .getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    fun getApiKey(): String = prefs.getString(PREF_API_KEY, "")?.trim().orEmpty()

    fun getModel(): String {
        val saved = prefs.getString(PREF_MODEL, "")?.trim().orEmpty()
        return normalizeModelId(saved.ifEmpty { DEFAULT_MODEL })
    }

    /** `wavespeed-ai/flux-2-pro/edit/` and `/wavespeed-ai/...` are not valid model paths. */
    private fun normalizeModelId(raw: String): String {
        val trimmed = raw.trim().trim('/')
        return if (trimmed.isEmpty()) DEFAULT_MODEL else trimmed
    }

    /**
     * Multi-image edit models (`wavespeed-ai/flux-2-pro/edit`, `google/gemini-2.5-flash-image/edit`, ...)
     * take `images: [url]` and have no mask parameter, so they repaint the whole uploaded crop.
     * Mask-based inpainting models (`wavespeed-ai/flux-fill-dev`, LaMa, ...) take `image` + `mask_image`.
     */
    private fun modelUsesImageList(model: String): Boolean {
        val id = model.lowercase()
        if (MASK_MODEL_HINTS.any { id.contains(it) }) return false
        return IMAGE_LIST_MODEL_HINTS.any { id.contains(it) } || id.endsWith("/edit")
    }

    fun isConfigured(): Boolean = getApiKey().isNotEmpty() && getModel().isNotEmpty()

    /** How many Wavespeed runs the given areas need for the configured model. */
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
        val runsText = if (runs == 1) "1 Wavespeed run" else "$runs Wavespeed runs"
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
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            throw WaveSpeedException("Wavespeed AI API key is not set. Add it in Settings > AI & Model Inpaint.")
        }
        val model = getModel()
        if (prompt.isBlank()) {
            throw WaveSpeedException("A prompt is required for Wavespeed AI inpainting.")
        }
        val useMask = !modelUsesImageList(model)

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

            onProgress?.invoke(8, "Wavespeed AI: uploading image 8%")
            val imageUrl = upload(imageBytes, "inpaint-source.png", apiKey)
            val maskUrl = if (useMask) {
                onProgress?.invoke(15, "Wavespeed AI: uploading mask 15%")
                upload(encodePng(sendMask), "inpaint-mask.png", apiKey)
            } else {
                null
            }
            onProgress?.invoke(22, "Wavespeed AI: submitting 22%")

            val payload = JsonObject().apply {
                addProperty("prompt", prompt.trim())
                if (useMask) {
                    addProperty("image", imageUrl)
                    addProperty("mask_image", maskUrl)
                } else {
                    // Edit models only accept a list of reference images and ignore any mask.
                    add("images", JsonArray().apply { add(imageUrl) })
                }
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
                        throw WaveSpeedException("Could not prepare the inpainted result.")
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

    /**
     * Inpaints many areas with as few Wavespeed runs as possible and returns one result per
     * region (same size as the region, null when that region could not be produced).
     *
     * Every run sends one crop that holds several nearby areas. Mask models get the mask of all
     * blobs in that crop; edit models repaint the whole crop and only the painted pixels are kept.
     */
    suspend fun inpaintRegions(
        regions: List<Rect>,
        imageBounds: Rect,
        prompt: String,
        sourceProvider: suspend (Rect) -> Bitmap?,
        maskProvider: suspend (Rect) -> Bitmap?,
        onProgress: ((percent: Int, message: String) -> Unit)? = null
    ): List<Bitmap?> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            throw WaveSpeedException("Wavespeed AI API key is not set. Add it in Settings > AI & Model Inpaint.")
        }
        if (prompt.isBlank()) {
            throw WaveSpeedException("A prompt is required for Wavespeed AI inpainting.")
        }
        val model = getModel()
        val useMask = !modelUsesImageList(model)
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
            val output = request(source, mask, model, prompt, apiKey, onProgress)
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

    /** Uploads one prepared image (plus its mask for mask models) and returns the raw result. */
    private suspend fun request(
        image: Bitmap,
        mask: Bitmap?,
        model: String,
        prompt: String,
        apiKey: String,
        onProgress: ((Int, String) -> Unit)?
    ): Bitmap {
        val useMask = !modelUsesImageList(model)
        if (useMask && mask == null) {
            throw WaveSpeedException("This model needs a mask, but no selection was provided.")
        }
        if (image.width <= 0 || image.height <= 0) {
            throw WaveSpeedException("The selected area is empty.")
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
        if (sendImage !== image) temporaries.add(sendImage)
        if (sendMaskSource != null && sendMaskSource !== scaledMask) temporaries.add(sendMaskSource)
        try {
            val sendMask = if (sendMaskSource != null) {
                val bitmap = Bitmap.createBitmap(sendW, sendH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(sendMaskSource, 0f, 0f, null)
                temporaries.add(bitmap)
                bitmap
            } else {
                null
            }

            onProgress?.invoke(5, "Wavespeed AI: preparing 5%")
            onProgress?.invoke(8, "Wavespeed AI: uploading image 8%")
            val imageUrl = upload(encodePng(sendImage), "inpaint-source.png", apiKey)
            val maskUrl = if (sendMask != null) {
                onProgress?.invoke(15, "Wavespeed AI: uploading mask 15%")
                upload(encodePng(sendMask), "inpaint-mask.png", apiKey)
            } else {
                null
            }
            onProgress?.invoke(22, "Wavespeed AI: submitting 22%")

            val payload = JsonObject().apply {
                addProperty("prompt", prompt.trim())
                if (sendMask != null && maskUrl != null) {
                    addProperty("image", imageUrl)
                    addProperty("mask_image", maskUrl)
                } else {
                    add("images", JsonArray().apply { add(imageUrl) })
                }
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
            return if (downloaded.width != image.width || downloaded.height != image.height) {
                Bitmap.createScaledBitmap(downloaded, image.width, image.height, true).also {
                    if (!downloaded.isRecycled) downloaded.recycle()
                }
            } else {
                downloaded
            }
        } finally {
            for (bitmap in temporaries) {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            if (scaledMask != null && scaledMask !== mask && !scaledMask.isRecycled) scaledMask.recycle()
            if (sendImage !== image && !sendImage.isRecycled) sendImage.recycle()
        }
    }

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
        return if (modelUsesImageList(getModel())) {
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
        private const val NEARBY_GAP = 128
        private const val POLL_INTERVAL_MS = 3000L
        private const val ESTIMATED_TASK_SECONDS = 25f
        private const val MAX_POLL_FAILURES = 5

        /** Models that really take a mask and inpaint only the selected pixels. */
        private val MASK_MODEL_HINTS = listOf("flux-fill", "inpaint", "lama")

        /** Models whose schema is `images: [url]` with no mask field. */
        private val IMAGE_LIST_MODEL_HINTS = listOf("gemini", "nano-banana", "flux-2", "seedream")

        const val PREF_API_KEY = "wavespeed_api_key"
        const val PREF_MODEL = "wavespeed_model"
        const val DEFAULT_MODEL = "wavespeed-ai/flux-fill-dev"
    }
}
