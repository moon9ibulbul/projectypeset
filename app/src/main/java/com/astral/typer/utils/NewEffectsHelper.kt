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
            float freq = max(0.02, roughness * 0.045);
            float n1 = noise(coord * freq);
            float n2 = noise(coord * freq * 2.3 + float2(17.1, 31.7));
            float2 offset = float2(n1 - 0.5, n2 - 0.5) * intensity * 2.2;
            half4 col = inputShader.eval(coord + offset);
            // Subtle organic edge feather/bleed
            float bleed = noise(coord * freq * 3.7 + float2(5.2, 9.4));
            if (col.a > 0.05 && col.a < 0.95) {
                col.a = clamp(col.a + (bleed - 0.45) * 0.25, 0.0, 1.0);
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

        val alphaBmp = srcBmp.extractAlpha()

        val rad = Math.toRadians(layer.bevelAngle.toDouble())
        val dist = layer.bevelDepth.coerceIn(1f, 40f) * scale
        val dx = (cos(rad) * dist).toFloat()
        val dy = (sin(rad) * dist).toFloat()
        val softness = (layer.bevelSoftness.coerceIn(0f, 15f) * scale)

        // Build inner highlight rim: alphaBmp minus shifted alphaBmp
        val hlBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val hlCanvas = Canvas(hlBmp)
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        hlCanvas.drawBitmap(alphaBmp, 0f, 0f, whitePaint)
        val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        hlCanvas.drawBitmap(alphaBmp, dx, dy, cutPaint)

        // Build inner shadow rim: alphaBmp minus opposite shifted alphaBmp
        val shBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val shCanvas = Canvas(shBmp)
        val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        shCanvas.drawBitmap(alphaBmp, 0f, 0f, blackPaint)
        shCanvas.drawBitmap(alphaBmp, -dx, -dy, cutPaint)

        // Composite onto srcBmp inside a masked layer
        val compCanvas = Canvas(srcBmp)
        val saveCount = compCanvas.saveLayer(0f, 0f, bmpW.toFloat(), bmpH.toFloat(), null)

        if (softness > 0.5f) {
            val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(softness, BlurMaskFilter.Blur.NORMAL)
            }
            val hlBlurred = hlBmp.extractAlpha(blurPaint, null)
            val shBlurred = shBmp.extractAlpha(blurPaint, null)
            val hlDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = (layer.bevelHighlightAlpha.coerceIn(0f, 1f) * 255).toInt()
            }
            val shDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                alpha = (layer.bevelShadowAlpha.coerceIn(0f, 1f) * 255).toInt()
            }
            compCanvas.drawBitmap(hlBlurred, 0f, 0f, hlDrawPaint)
            compCanvas.drawBitmap(shBlurred, 0f, 0f, shDrawPaint)
            hlBlurred.recycle()
            shBlurred.recycle()
        } else {
            val hlDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (layer.bevelHighlightAlpha.coerceIn(0f, 1f) * 255).toInt()
            }
            val shDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (layer.bevelShadowAlpha.coerceIn(0f, 1f) * 255).toInt()
            }
            compCanvas.drawBitmap(hlBmp, 0f, 0f, hlDrawPaint)
            compCanvas.drawBitmap(shBmp, 0f, 0f, shDrawPaint)
        }

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        compCanvas.drawBitmap(alphaBmp, 0f, 0f, maskPaint)
        compCanvas.restoreToCount(saveCount)

        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(1f / scale, 1f / scale)
        canvas.drawBitmap(srcBmp, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        hlBmp.recycle()
        shBmp.recycle()
        alphaBmp.recycle()
        srcBmp.recycle()
    }

    fun renderBlockExtrude3D(
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

        val alphaBmp = srcBmp.extractAlpha()

        val rad = Math.toRadians(layer.extrudeAngle.toDouble())
        val depth = layer.extrudeDepth.coerceIn(1f, 120f)
        val totalDx = (cos(rad) * depth).toFloat()
        val totalDy = (sin(rad) * depth).toFloat()
        val steps = depth.toInt().coerceIn(2, 48)

        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(1f / scale, 1f / scale)

        // Optional outline behind extrusion
        val outlineW = layer.extrudeOutlineWidth.coerceIn(0f, 25f) * scale
        if (outlineW > 0.5f) {
            val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = layer.extrudeOutlineColor
            }
            val ringCount = 8
            for (i in steps downTo 0) {
                val t = i / steps.toFloat()
                val ox = totalDx * t * scale
                val oy = totalDy * t * scale
                for (r in 0 until ringCount) {
                    val rAngle = (r * 2.0 * Math.PI) / ringCount
                    val rx = ox + (cos(rAngle) * outlineW).toFloat()
                    val ry = oy + (sin(rAngle) * outlineW).toFloat()
                    canvas.drawBitmap(alphaBmp, rx, ry, outlinePaint)
                }
            }
        }

        // Solid 3D extrude steps
        val extrudePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = layer.extrudeColor
        }
        for (i in steps downTo 1) {
            val t = i / steps.toFloat()
            canvas.drawBitmap(alphaBmp, totalDx * t * scale, totalDy * t * scale, extrudePaint)
        }

        // Foreground content
        canvas.drawBitmap(srcBmp, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        alphaBmp.recycle()
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
                // Fallback to software path below
            }
        }

        // Software / API < 33 fallback: organic seeded multi-stamp bleed
        val scale = computeSafeScale(bounds)
        val bmpW = ceil(bounds.width() * scale).toInt().coerceAtLeast(1)
        val bmpH = ceil(bounds.height() * scale).toInt().coerceAtLeast(1)

        val srcBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val srcCanvas = Canvas(srcBmp)
        srcCanvas.scale(scale, scale)
        srcCanvas.translate(-bounds.left, -bounds.top)
        drawContentBlock(srcCanvas)

        val rng = Random(layer.inkBleedSeed)
        val amp = layer.inkBleedIntensity.coerceIn(0.5f, 25f) * scale * 0.45f
        val stampCount = 6

        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(1f / scale, 1f / scale)

        val bleedPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = 110
        }
        for (i in 0 until stampCount) {
            val jx = (rng.nextFloat() - 0.5f) * 2f * amp
            val jy = (rng.nextFloat() - 0.5f) * 2f * amp
            canvas.drawBitmap(srcBmp, jx, jy, bleedPaint)
        }
        canvas.drawBitmap(srcBmp, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        srcBmp.recycle()
    }
}
