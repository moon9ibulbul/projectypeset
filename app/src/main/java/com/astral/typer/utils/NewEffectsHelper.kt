package com.astral.typer.utils

import android.graphics.*
import android.os.Build
import com.astral.typer.models.StylableLayer
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object NewEffectsHelper {

    private const val MAX_OFFSCREEN_DIM = 2048

    private const val INK_BLEED_SHADER = """
        uniform shader inputShader;
        uniform float intensity;
        uniform float roughness;
        uniform float seed;

        float hash21(float2 p) {
            p = fract(p * float2(123.34, 456.21) + seed * 0.013);
            p += dot(p, p + 45.32);
            return fract(p.x * p.y);
        }

        float noise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            float a = hash21(i);
            float b = hash21(i + float2(1.0, 0.0));
            float c = hash21(i + float2(0.0, 1.0));
            float d = hash21(i + float2(1.0, 1.0));
            return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
        }

        half4 main(float2 coord) {
            float freq = max(0.03, roughness * 0.06);
            float n1 = noise(coord * freq);
            float n2 = noise(coord * freq * 2.3 + float2(17.1, 31.7));
            float2 offset = float2(n1 - 0.5, n2 - 0.5) * intensity * 3.2;
            half4 col = inputShader.eval(coord + offset);
            float bleed = noise(coord * freq * 4.1 + float2(5.2, 9.4));
            if (col.a > 0.05 && col.a < 0.98) {
                col.a = clamp(col.a + (bleed - 0.35) * 0.45, 0.0, 1.0);
            }
            return col;
        }
    """

    private fun computeSafeScale(bounds: RectF): Float {
        val w = bounds.width().coerceAtLeast(1f)
        val h = bounds.height().coerceAtLeast(1f)
        val maxDim = maxOf(w, h)
        return if (maxDim > MAX_OFFSCREEN_DIM) MAX_OFFSCREEN_DIM / maxDim else 1f
    }

    private fun createTintedSilhouette(src: Bitmap, color: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    private fun blurBitmapFast(src: Bitmap, radius: Float): Bitmap {
        if (radius <= 0.5f) return src.copy(Bitmap.Config.ARGB_8888, false)
        // Fast 2-step downscale/upscale box-like blur (works identically on all APIs & software/hardware)
        val downFactor = (1f + radius * 0.28f).coerceIn(1.2f, 6f)
        val dw = (src.width / downFactor).toInt().coerceAtLeast(1)
        val dh = (src.height / downFactor).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, dw, dh, true)
        val blurred = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        if (small != blurred) small.recycle()
        return blurred
    }

    fun renderBevelEmboss(
        canvas: Canvas,
        bounds: RectF,
        layer: StylableLayer,
        drawContentBlock: (Canvas) -> Unit
    ) {
        val scale = computeSafeScale(bounds)
        val bmpW = ceil(bounds.width() * scale).toInt().coerceAtLeast(1)
        val bmpH = ceil(bounds.height() * scale).toInt().coerceAtLeast(1)

        val srcBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val srcCanvas = Canvas(srcBmp)
        srcCanvas.scale(scale, scale)
        srcCanvas.translate(-bounds.left, -bounds.top)
        drawContentBlock(srcCanvas)

        val whiteSil = createTintedSilhouette(srcBmp, Color.WHITE)
        val blackSil = createTintedSilhouette(srcBmp, Color.BLACK)

        val rad = Math.toRadians(layer.bevelAngle.toDouble())
        val dist = layer.bevelDepth.coerceIn(1f, 40f) * scale
        val dx = (cos(rad) * dist).toFloat()
        val dy = (sin(rad) * dist).toFloat()
        val softness = layer.bevelSoftness.coerceIn(0f, 15f) * scale

        val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }

        // Inner highlight rim: whiteSil minus whiteSil shifted by (dx, dy)
        val hlBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val hlCanvas = Canvas(hlBmp)
        hlCanvas.drawBitmap(whiteSil, 0f, 0f, null)
        hlCanvas.drawBitmap(whiteSil, dx, dy, cutPaint)

        // Inner shadow rim: blackSil minus blackSil shifted by (-dx, -dy)
        val shBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val shCanvas = Canvas(shBmp)
        shCanvas.drawBitmap(blackSil, 0f, 0f, null)
        shCanvas.drawBitmap(blackSil, -dx, -dy, cutPaint)

        val hlFinal = if (softness > 0.5f) blurBitmapFast(hlBmp, softness) else hlBmp
        val shFinal = if (softness > 0.5f) blurBitmapFast(shBmp, softness) else shBmp

        // Composite highlight and shadow onto srcBmp clipped to srcBmp alpha
        val bevelOverlay = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val overlayCanvas = Canvas(bevelOverlay)
        val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (layer.bevelHighlightAlpha.coerceIn(0f, 1f) * 255).toInt()
        }
        val shPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (layer.bevelShadowAlpha.coerceIn(0f, 1f) * 255).toInt()
        }
        overlayCanvas.drawBitmap(hlFinal, 0f, 0f, hlPaint)
        overlayCanvas.drawBitmap(shFinal, 0f, 0f, shPaint)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        overlayCanvas.drawBitmap(srcBmp, 0f, 0f, maskPaint)

        val compCanvas = Canvas(srcBmp)
        compCanvas.drawBitmap(bevelOverlay, 0f, 0f, null)

        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(1f / scale, 1f / scale)
        canvas.drawBitmap(srcBmp, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        if (hlFinal !== hlBmp) hlFinal.recycle()
        if (shFinal !== shBmp) shFinal.recycle()
        hlBmp.recycle()
        shBmp.recycle()
        whiteSil.recycle()
        blackSil.recycle()
        bevelOverlay.recycle()
        srcBmp.recycle()
    }

    fun renderBlockExtrude3D(
        canvas: Canvas,
        bounds: RectF,
        layer: StylableLayer,
        drawContentBlock: (Canvas) -> Unit
    ) {
        val w = bounds.width().coerceAtLeast(1f)
        val h = bounds.height().coerceAtLeast(1f)
        val maxDim = maxOf(w, h)
        val scale = if (maxDim > 1024f) 1024f / maxDim else 1f
        val bmpW = ceil(w * scale).toInt().coerceAtLeast(1)
        val bmpH = ceil(h * scale).toInt().coerceAtLeast(1)

        val srcBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val srcCanvas = Canvas(srcBmp)
        srcCanvas.scale(scale, scale)
        srcCanvas.translate(-bounds.left, -bounds.top)
        drawContentBlock(srcCanvas)

        val extrudeSil = createTintedSilhouette(srcBmp, layer.extrudeColor)
        val rad = Math.toRadians(layer.extrudeAngle.toDouble())
        val depth = layer.extrudeDepth.coerceIn(1f, 120f)
        val totalDx = (cos(rad) * depth).toFloat()
        val totalDy = (sin(rad) * depth).toFloat()
        val steps = (depth / 2.5f).toInt().coerceIn(2, 22)

        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Composite solid 3D extrude steps into a single bitmap so outline only draws 8x total (not steps * 8x)
        val extrudeBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val extrudeCanvas = Canvas(extrudeBmp)
        for (i in steps downTo 1) {
            val t = i / steps.toFloat()
            extrudeCanvas.drawBitmap(extrudeSil, totalDx * t * scale, totalDy * t * scale, drawPaint)
        }
        extrudeCanvas.drawBitmap(extrudeSil, 0f, 0f, drawPaint)

        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(1f / scale, 1f / scale)

        val outlineW = layer.extrudeOutlineWidth.coerceIn(0f, 25f) * scale
        if (outlineW > 0.5f) {
            val outlineBmp = createTintedSilhouette(extrudeBmp, layer.extrudeOutlineColor)
            val ringCount = 8
            for (r in 0 until ringCount) {
                val rAngle = (r * 2.0 * Math.PI) / ringCount
                val rx = (cos(rAngle) * outlineW).toFloat()
                val ry = (sin(rAngle) * outlineW).toFloat()
                canvas.drawBitmap(outlineBmp, rx, ry, drawPaint)
            }
            outlineBmp.recycle()
        }

        canvas.drawBitmap(extrudeBmp, 0f, 0f, drawPaint)
        canvas.drawBitmap(srcBmp, 0f, 0f, drawPaint)
        canvas.restore()

        extrudeBmp.recycle()
        extrudeSil.recycle()
        srcBmp.recycle()
    }

    fun renderInkBleed(
        canvas: Canvas,
        bounds: RectF,
        layer: StylableLayer,
        drawContentBlock: (Canvas) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canvas.isHardwareAccelerated) {
            try {
                val node = RenderNode("InkBleedNode")
                val w = ceil(bounds.width()).toInt().coerceAtLeast(1)
                val h = ceil(bounds.height()).toInt().coerceAtLeast(1)
                node.setPosition(0, 0, w, h)
                val rec = node.beginRecording()
                rec.translate(-bounds.left, -bounds.top)
                drawContentBlock(rec)
                node.endRecording()

                val shader = RuntimeShader(INK_BLEED_SHADER)
                shader.setFloatUniform("intensity", layer.inkBleedIntensity.coerceIn(0.5f, 25f))
                shader.setFloatUniform("roughness", layer.inkBleedRoughness.coerceIn(1f, 20f))
                shader.setFloatUniform("seed", layer.inkBleedSeed.toFloat())

                node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "inputShader"))
                canvas.save()
                canvas.translate(bounds.left, bounds.top)
                canvas.drawRenderNode(node)
                canvas.restore()
                return
            } catch (_: Throwable) {
                // Fallback to pixel displacement below
            }
        }

        // Fast deterministic pixel-displacement fallback (works on all APIs & software canvases)
        val scale = computeSafeScale(bounds)
        val bmpW = ceil(bounds.width() * scale).toInt().coerceAtLeast(1)
        val bmpH = ceil(bounds.height() * scale).toInt().coerceAtLeast(1)

        val srcBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val srcCanvas = Canvas(srcBmp)
        srcCanvas.scale(scale, scale)
        srcCanvas.translate(-bounds.left, -bounds.top)
        drawContentBlock(srcCanvas)

        val totalPixels = bmpW * bmpH
        val srcPixels = IntArray(totalPixels)
        val dstPixels = IntArray(totalPixels)
        srcBmp.getPixels(srcPixels, 0, bmpW, 0, 0, bmpW, bmpH)

        val amp = (layer.inkBleedIntensity.coerceIn(1f, 25f) * scale * 0.9f)
        val freq = (layer.inkBleedRoughness.coerceIn(1f, 20f) * 0.08f)
        val seedOffset = (layer.inkBleedSeed % 1000) * 0.17f

        // Precompute per-row and per-col wave tables for O(W*H) speed without per-pixel trig
        val rowDx = IntArray(bmpH) { y ->
            val v = sin(y * freq + seedOffset) + 0.5 * sin(y * freq * 2.3 + seedOffset * 1.7)
            (v * amp * 0.65).toInt()
        }
        val colDy = IntArray(bmpW) { x ->
            val v = cos(x * freq * 1.1 + seedOffset * 1.3) + 0.5 * sin(x * freq * 2.7 + seedOffset)
            (v * amp * 0.65).toInt()
        }

        for (y in 0 until bmpH) {
            val rowBase = y * bmpW
            val dx = rowDx[y]
            for (x in 0 until bmpW) {
                val sx = (x + dx).coerceIn(0, bmpW - 1)
                val sy = (y + colDy[x]).coerceIn(0, bmpH - 1)
                dstPixels[rowBase + x] = srcPixels[sy * bmpW + sx]
            }
        }

        val outBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(dstPixels, 0, bmpW, 0, 0, bmpW, bmpH)

        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(1f / scale, 1f / scale)
        canvas.drawBitmap(outBmp, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        outBmp.recycle()
        srcBmp.recycle()
    }

    private const val CHROMATIC_ABERRATION_2_SHADER = """
        uniform shader content;
        uniform float2 dir;
        uniform float shift;
        uniform float blur;
        uniform float3 colorL;
        uniform float3 colorR;
        uniform float3 colorC;

        half4 main(float2 coord) {
            float sigma = max(0.75, blur * 0.45);
            float twoSigmaSq = 2.0 * sigma * sigma;
            float maxSpan = shift + blur * 1.35;

            float aL = 0.0;
            float aC = 0.0;
            float aR = 0.0;
            float sumWL = 0.0;
            float sumWC = 0.0;
            float sumWR = 0.0;

            for (int i = -16; i <= 16; i++) {
                float t = float(i) / 16.0;
                float pos = t * maxSpan;
                float sA = content.eval(coord + dir * pos).a;

                float dL = pos - shift;
                float wL = exp(-(dL * dL) / twoSigmaSq);
                aL += sA * wL;
                sumWL += wL;

                float wC = exp(-(pos * pos) / twoSigmaSq);
                aC += sA * wC;
                sumWC += wC;

                float dR = pos + shift;
                float wR = exp(-(dR * dR) / twoSigmaSq);
                aR += sA * wR;
                sumWR += wR;
            }

            aL = (sumWL > 0.0) ? (aL / sumWL) : 0.0;
            aC = (sumWC > 0.0) ? (aC / sumWC) : 0.0;
            aR = (sumWR > 0.0) ? (aR / sumWR) : 0.0;

            float3 chromaticRgb = clamp(colorL * aL + colorR * aR + colorC * aC, 0.0, 1.0);
            float finalAlpha = clamp(max(aL, max(aC, aR)), 0.0, 1.0);

            half4 base = content.eval(coord);
            float3 baseUnpremul = (base.a > 0.001) ? (base.rgb / base.a) : float3(0.0);
            float3 rgb = mix(chromaticRgb, baseUnpremul * chromaticRgb, base.a * 0.4);
            rgb = min(rgb, float3(finalAlpha));

            return half4(rgb, finalAlpha);
        }
    """

    fun applyChromatic2(
        layer: StylableLayer,
        targetCanvas: Canvas,
        nodeW: Int,
        nodeH: Int,
        recordTranslateX: Float,
        recordTranslateY: Float,
        drawTranslateX: Float,
        drawTranslateY: Float,
        drawInner: (Canvas) -> Unit
    ) {
        val angleRad = Math.toRadians(layer.chromaticAngle.toDouble())
        val dirX = cos(angleRad).toFloat()
        val dirY = sin(angleRad).toFloat()
        val shift = layer.chromaticShift.coerceAtLeast(0f)
        val blur = layer.chromaticBlur.coerceAtLeast(0f)

        val cL = if (layer.chromaticColors.isNotEmpty()) layer.chromaticColors[0] else 0xFF0040FF.toInt()
        val cR = if (layer.chromaticColors.size > 1) layer.chromaticColors[1] else 0xFFFF1A00.toInt()
        val cC = if (layer.chromaticColors.size > 2) layer.chromaticColors[2] else 0xFF00FF40.toInt()

        val rL = Color.red(cL) / 255f
        val gL = Color.green(cL) / 255f
        val bL = Color.blue(cL) / 255f

        val rR = Color.red(cR) / 255f
        val gR = Color.green(cR) / 255f
        val bR = Color.blue(cR) / 255f

        val rC = Color.red(cC) / 255f
        val gC = Color.green(cC) / 255f
        val bC = Color.blue(cC) / 255f

        var useRenderEffect = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && targetCanvas.isHardwareAccelerated) {
            try {
                val node = RenderNode("Chromatic2Node")
                node.setPosition(0, 0, nodeW, nodeH)
                val recordingCanvas = node.beginRecording()
                recordingCanvas.translate(recordTranslateX, recordTranslateY)
                drawInner(recordingCanvas)
                node.endRecording()

                val shader = RuntimeShader(CHROMATIC_ABERRATION_2_SHADER)
                shader.setFloatUniform("dir", dirX, dirY)
                shader.setFloatUniform("shift", shift)
                shader.setFloatUniform("blur", blur)
                shader.setFloatUniform("colorL", rL, gL, bL)
                shader.setFloatUniform("colorR", rR, gR, bR)
                shader.setFloatUniform("colorC", rC, gC, bC)

                node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))

                targetCanvas.save()
                targetCanvas.translate(drawTranslateX, drawTranslateY)
                targetCanvas.drawRenderNode(node)
                targetCanvas.restore()
                useRenderEffect = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!useRenderEffect && nodeW > 0 && nodeH > 0) {
            val srcBmp = Bitmap.createBitmap(nodeW, nodeH, Bitmap.Config.ARGB_8888)
            val srcCanvas = Canvas(srcBmp)
            srcCanvas.translate(recordTranslateX, recordTranslateY)
            drawInner(srcCanvas)

            val total = nodeW * nodeH
            val srcPixels = IntArray(total)
            srcBmp.getPixels(srcPixels, 0, nodeW, 0, 0, nodeW, nodeH)
            val outPixels = IntArray(total)

            val sigma = maxOf(0.75f, blur * 0.45f)
            val twoSigmaSq = 2.0 * sigma * sigma
            val maxSpan = shift + blur * 1.35f

            val tapCount = 33
            val tapDx = IntArray(tapCount)
            val tapDy = IntArray(tapCount)
            val wLArr = FloatArray(tapCount)
            val wCArr = FloatArray(tapCount)
            val wRArr = FloatArray(tapCount)
            var sumWL = 0f
            var sumWC = 0f
            var sumWR = 0f

            for (i in 0 until tapCount) {
                val t = (i - 16) / 16f
                val pos = t * maxSpan
                tapDx[i] = Math.round(dirX * pos)
                tapDy[i] = Math.round(dirY * pos)

                val dL = (pos - shift).toDouble()
                val wL = Math.exp(-(dL * dL) / twoSigmaSq).toFloat()
                wLArr[i] = wL
                sumWL += wL

                val dC = pos.toDouble()
                val wC = Math.exp(-(dC * dC) / twoSigmaSq).toFloat()
                wCArr[i] = wC
                sumWC += wC

                val dR = (pos + shift).toDouble()
                val wR = Math.exp(-(dR * dR) / twoSigmaSq).toFloat()
                wRArr[i] = wR
                sumWR += wR
            }

            if (sumWL > 0f) for (i in 0 until tapCount) wLArr[i] /= sumWL
            if (sumWC > 0f) for (i in 0 until tapCount) wCArr[i] /= sumWC
            if (sumWR > 0f) for (i in 0 until tapCount) wRArr[i] /= sumWR

            for (y in 0 until nodeH) {
                val rowOffset = y * nodeW
                for (x in 0 until nodeW) {
                    var aL = 0f
                    var aC = 0f
                    var aR = 0f

                    for (i in 0 until tapCount) {
                        val sx = x + tapDx[i]
                        val sy = y + tapDy[i]
                        if (sx in 0 until nodeW && sy in 0 until nodeH) {
                            val sA = (srcPixels[sy * nodeW + sx] ushr 24) / 255f
                            aL += sA * wLArr[i]
                            aC += sA * wCArr[i]
                            aR += sA * wRArr[i]
                        }
                    }

                    val chrR = (rL * aL + rR * aR + rC * aC).coerceIn(0f, 1f)
                    val chrG = (gL * aL + gR * aR + gC * aC).coerceIn(0f, 1f)
                    val chrB = (bL * aL + bR * aR + bC * aC).coerceIn(0f, 1f)
                    val finalA = maxOf(aL, maxOf(aC, aR)).coerceIn(0f, 1f)

                    val basePx = srcPixels[rowOffset + x]
                    val baseA = (basePx ushr 24) / 255f
                    val baseR = ((basePx ushr 16) and 0xFF) / 255f
                    val baseG = ((basePx ushr 8) and 0xFF) / 255f
                    val baseB = (basePx and 0xFF) / 255f
                    val mixFactor = baseA * 0.4f

                    val outR = (chrR * (1f - mixFactor) + (baseR * chrR) * mixFactor).coerceIn(0f, 1f)
                    val outG = (chrG * (1f - mixFactor) + (baseG * chrG) * mixFactor).coerceIn(0f, 1f)
                    val outB = (chrB * (1f - mixFactor) + (baseB * chrB) * mixFactor).coerceIn(0f, 1f)

                    val finalAInt = (finalA * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val finalRInt = if (finalA > 0.001f) ((outR / finalA).coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255) else 0
                    val finalGInt = if (finalA > 0.001f) ((outG / finalA).coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255) else 0
                    val finalBInt = if (finalA > 0.001f) ((outB / finalA).coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255) else 0

                    outPixels[rowOffset + x] = (finalAInt shl 24) or (finalRInt shl 16) or (finalGInt shl 8) or finalBInt
                }
            }

            val outBmp = Bitmap.createBitmap(nodeW, nodeH, Bitmap.Config.ARGB_8888)
            outBmp.setPixels(outPixels, 0, nodeW, 0, 0, nodeW, nodeH)

            targetCanvas.save()
            targetCanvas.translate(drawTranslateX, drawTranslateY)
            targetCanvas.drawBitmap(outBmp, 0f, 0f, null)
            targetCanvas.restore()

            outBmp.recycle()
            srcBmp.recycle()
        }
    }
}
