package com.astral.typer.utils

import android.graphics.Path
import java.util.Random

object SpeedLineHelper {

    fun generateSpeedLinePath(
        w: Float,
        h: Float,
        type: String,
        lineWidth: Float,
        lineHeight: Float,
        lineCount: Int,
        thickness: Float,
        length: Float,
        additional: Int,
        angle: Float,
        seed: Long,
        viewScale: Float = 1.0f
    ): Path {
        val path = Path()
        val centerX = w / 2f
        val centerY = h / 2f
        val random = Random(seed)

        // Scale-down count slightly during rapid far-zoom out preview (viewScale < 0.25f) to maintain 60fps
        val effectiveCount = if (viewScale < 0.25f) {
            (lineCount * 0.5f).toInt().coerceAtLeast(1)
        } else {
            lineCount.coerceAtLeast(1)
        }

        val effectiveAdditional = if (viewScale < 0.25f) {
            (additional * 0.5f).toInt()
        } else {
            additional
        }

        if (type == "RADIAL") {
            val numLines = effectiveCount
            val outerA = lineWidth / 2f
            val outerB = lineHeight / 2f

            for (i in 0 until numLines) {
                val baseAngle = (2f * Math.PI * i / numLines) + (random.nextDouble() * 0.1)
                val actualLength = length * (0.7f + random.nextFloat() * 0.6f)
                val cosA = Math.cos(baseAngle)
                val sinA = Math.sin(baseAngle)
                val startX = centerX + (outerA * cosA).toFloat()
                val startY = centerY + (outerB * sinA).toFloat()
                val endX = centerX + ((outerA - actualLength).coerceAtLeast(0f) * cosA).toFloat()
                val endY = centerY + ((outerB - actualLength).coerceAtLeast(0f) * sinA).toFloat()

                val perpCos = -sinA
                val perpSin = cosA
                val halfT = thickness / 2f
                val p1x = startX - (halfT * perpCos).toFloat()
                val p1y = startY - (halfT * perpSin).toFloat()
                val p2x = startX + (halfT * perpCos).toFloat()
                val p2y = startY + (halfT * perpSin).toFloat()

                path.moveTo(p1x, p1y)
                path.lineTo(p2x, p2y)
                path.lineTo(endX, endY)
                path.close()

                for (j in 0 until effectiveAdditional) {
                    val subAngle = baseAngle + (random.nextGaussian() * 0.05)
                    val subLength = length * (0.3f + random.nextFloat() * 0.6f)
                    val subThickness = thickness * (0.3f + random.nextFloat() * 0.6f)

                    val cosSub = Math.cos(subAngle)
                    val sinSub = Math.sin(subAngle)
                    val subStartX = centerX + (outerA * cosSub).toFloat()
                    val subStartY = centerY + (outerB * sinSub).toFloat()
                    val subEndX = centerX + ((outerA - subLength).coerceAtLeast(0f) * cosSub).toFloat()
                    val subEndY = centerY + ((outerB - subLength).coerceAtLeast(0f) * sinSub).toFloat()

                    val subPerpCos = -sinSub
                    val subPerpSin = cosSub
                    val subHalfT = subThickness / 2f
                    val sp1x = subStartX - (subHalfT * subPerpCos).toFloat()
                    val sp1y = subStartY - (subHalfT * subPerpSin).toFloat()
                    val sp2x = subStartX + (subHalfT * subPerpCos).toFloat()
                    val sp2y = subStartY + (subHalfT * subPerpSin).toFloat()

                    path.moveTo(sp1x, sp1y)
                    path.lineTo(sp2x, sp2y)
                    path.lineTo(subEndX, subEndY)
                    path.close()
                }
            }
        } else {
            val numLines = effectiveCount
            val angleRad = Math.toRadians(angle.toDouble())
            val boxW = lineWidth
            val boxH = lineHeight
            val cosA = Math.cos(angleRad)
            val sinA = Math.sin(angleRad)

            fun toCanvasCoords(u: Float, v: Float): Pair<Float, Float> {
                val x = centerX + (u * cosA - v * sinA).toFloat()
                val y = centerY + (u * sinA + v * cosA).toFloat()
                return Pair(x, y)
            }

            for (i in 0 until numLines) {
                val v = (random.nextFloat() - 0.5f) * boxH
                val isStartSide = true
                val actualLength = length * (0.7f + random.nextFloat() * 0.6f)

                val halfW = boxW / 2f
                val uStart = if (isStartSide) -halfW else halfW
                val uEnd = if (isStartSide) -halfW + actualLength else halfW - actualLength

                val halfT = thickness / 2f
                val (p1x, p1y) = toCanvasCoords(uStart, v - halfT)
                val (p2x, p2y) = toCanvasCoords(uStart, v + halfT)
                val (endX, endY) = toCanvasCoords(uEnd, v)

                path.moveTo(p1x, p1y)
                path.lineTo(p2x, p2y)
                path.lineTo(endX, endY)
                path.close()

                for (j in 0 until effectiveAdditional) {
                    val subV = v + (random.nextGaussian().toFloat() * 10f)
                    val subLength = length * (0.3f + random.nextFloat() * 0.6f)
                    val subThickness = thickness * (0.3f + random.nextFloat() * 0.6f)
                    val subUEnd = if (isStartSide) -halfW + subLength else halfW - subLength

                    val subHalfT = subThickness / 2f
                    val (sp1x, sp1y) = toCanvasCoords(uStart, subV - subHalfT)
                    val (sp2x, sp2y) = toCanvasCoords(uStart, subV + subHalfT)
                    val (subEndX, subEndY) = toCanvasCoords(subUEnd, subV)

                    path.moveTo(sp1x, sp1y)
                    path.lineTo(sp2x, sp2y)
                    path.lineTo(subEndX, subEndY)
                    path.close()
                }
            }
        }
        return path
    }

    fun calculateSpeedLineHash(
        w: Float,
        h: Float,
        type: String,
        lineWidth: Float,
        lineHeight: Float,
        lineCount: Int,
        thickness: Float,
        length: Float,
        additional: Int,
        angle: Float,
        seed: Long,
        viewScale: Float = 1.0f
    ): Int {
        var result = w.hashCode()
        result = 31 * result + h.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + lineWidth.hashCode()
        result = 31 * result + lineHeight.hashCode()
        result = 31 * result + lineCount
        result = 31 * result + thickness.hashCode()
        result = 31 * result + length.hashCode()
        result = 31 * result + additional
        result = 31 * result + angle.hashCode()
        result = 31 * result + seed.hashCode()
        result = 31 * result + (viewScale < 0.25f).hashCode()
        return result
    }
}
