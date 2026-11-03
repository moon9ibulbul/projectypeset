package com.astral.typer.utils

import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.sqrt

/**
 * Mesin penyusun layout teks cerdas terinspirasi dari Typer Tools (TypeR / MD TYPER).
 * Menghitung ukuran font bertahap (graceful stepping), optimasi bentuk siluet balon
 * (diamond / oval silhouette), serta pemenggalan kata cerdas (Indonesian hyphenation).
 */
object TyperLayoutSolver {

    data class LayoutResult(
        val formattedText: String,
        val boxWidth: Float,
        val scale: Float,
        val estimatedHeight: Float,
        val lineCount: Int
    )

    /**
     * Memecahkan tata letak teks terbaik di dalam bounding box balon menggunakan TextPaint Android.
     */
    fun solveLayout(
        rawText: String,
        bubbleRect: RectF,
        isOval: Boolean,
        basePaint: TextPaint,
        minScale: Float = 0.70f
    ): LayoutResult {
        return solveLayoutInternal(
            rawText = rawText,
            bubbleWidth = bubbleRect.width(),
            bubbleHeight = bubbleRect.height(),
            isOval = isOval,
            baseFontSize = basePaint.textSize,
            measureWord = { word, size ->
                val p = TextPaint(basePaint).apply { textSize = size }
                p.measureText(word)
            },
            minScale = minScale
        )
    }

    /**
     * Versi pure/decoupled untuk pengujian unit di JVM tanpa runtime native Android.
     * Menggunakan aproksimasi proporsional karakter (lebar rata-rata ~ 0.55 * fontSize).
     */
    fun solveLayoutPure(
        rawText: String,
        bubbleWidth: Float,
        bubbleHeight: Float,
        isOval: Boolean,
        baseFontSize: Float,
        minScale: Float = 0.70f
    ): LayoutResult {
        return solveLayoutInternal(
            rawText = rawText,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            isOval = isOval,
            baseFontSize = baseFontSize,
            measureWord = { word, size -> word.length * size * 0.55f },
            minScale = minScale
        )
    }

    private fun solveLayoutInternal(
        rawText: String,
        bubbleWidth: Float,
        bubbleHeight: Float,
        isOval: Boolean,
        baseFontSize: Float,
        measureWord: (String, Float) -> Float,
        minScale: Float = 0.70f
    ): LayoutResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return LayoutResult("", bubbleWidth.coerceAtLeast(50f), 1f, 0f, 0)
        }

        // Padding aman di dalam balon agar teks tidak menempel di batas dinding
        val hPadding = if (isOval) bubbleWidth * 0.18f else bubbleWidth * 0.10f
        val vPadding = if (isOval) bubbleHeight * 0.18f else bubbleHeight * 0.10f

        val targetWidth = (bubbleWidth - hPadding * 2).coerceAtLeast(40f)
        val targetHeight = (bubbleHeight - vPadding * 2).coerceAtLeast(40f)

        // 1. Ekstrak kata-kata asli
        val originalWords = trimmed.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (originalWords.isEmpty()) {
            return LayoutResult(trimmed, targetWidth, 1f, 0f, 0)
        }

        // 2. Evaluasi ukuran font bertahap (Graceful Stepping dari 100% turun hingga minScale)
        val scaleSteps = mutableListOf<Float>()
        var s = 1.0f
        while (s >= minScale) {
            scaleSteps.add(s)
            s -= 0.05f // Turun 5% per langkah evaluasi
        }
        if (scaleSteps.last() > minScale) {
            scaleSteps.add(minScale)
        }

        var bestResult: LayoutResult? = null
        var minPenalty = Float.MAX_VALUE

        for (scale in scaleSteps) {
            val curFontSize = baseFontSize * scale

            val candidate = solveForScale(
                words = originalWords,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                isOval = isOval,
                fontSize = curFontSize,
                measureWord = { w -> measureWord(w, curFontSize) },
                scale = scale
            )

            // Hitung penalti layout
            val penalty = evaluatePenalty(candidate, targetWidth, targetHeight, scale)
            if (penalty < minPenalty) {
                minPenalty = penalty
                bestResult = candidate
                // Jika kandidat muat dengan sempurna pada scale ini tanpa terpotong atau overflow
                if (candidate.estimatedHeight <= targetHeight && candidate.boxWidth <= targetWidth) {
                    break
                }
            }
        }

        return bestResult ?: LayoutResult(trimmed, targetWidth, minScale, targetHeight, 1)
    }

    private fun solveForScale(
        words: List<String>,
        targetWidth: Float,
        targetHeight: Float,
        isOval: Boolean,
        fontSize: Float,
        measureWord: (String) -> Float,
        scale: Float
    ): LayoutResult {
        // Cari lebar kata terpanjang
        var maxWordW = 0f
        for (w in words) {
            val mw = measureWord(w)
            if (mw > maxWordW) maxWordW = mw
        }

        // Tentukan batas lebar box
        val chosenBoxWidth = when {
            words.size <= 2 -> maxWordW.coerceAtLeast(targetWidth * 0.6f)
            isOval -> (targetWidth * 0.90f).coerceAtLeast(maxWordW)
            else -> (targetWidth * 0.95f).coerceAtLeast(maxWordW)
        }.coerceAtMost(targetWidth)

        // Jika ada kata tunggal yang lebih lebar dari targetWidth, lakukan hyphenation bahasa Indonesia
        val processedWords = mutableListOf<String>()
        for (word in words) {
            val wordW = measureWord(word)
            if (wordW > chosenBoxWidth && word.length > 5) {
                val opportunities = IndonesianHyphenator.getHyphenationOpportunities(word)
                if (opportunities.isNotEmpty()) {
                    // Pilih pemotongan yang paling mendekati tengah
                    val mid = opportunities[opportunities.size / 2]
                    processedWords.add(mid.first)
                    processedWords.add(mid.second)
                } else {
                    processedWords.add(word)
                }
            } else {
                processedWords.add(word)
            }
        }

        // Bangun baris-baris teks dengan greedy balancer untuk menghasilkan siluet seimbang
        val lines = balanceLines(processedWords, chosenBoxWidth, isOval, measureWord, fontSize)
        val formattedText = lines.joinToString("\n")

        // Estimasi tinggi teks = jumlah baris * line spacing (~ 1.25 * fontSize)
        val lineSpacing = fontSize * 1.25f
        val layoutHeight = lines.size * lineSpacing

        return LayoutResult(
            formattedText = formattedText,
            boxWidth = chosenBoxWidth,
            scale = scale,
            estimatedHeight = layoutHeight,
            lineCount = lines.size
        )
    }

    private fun balanceLines(
        words: List<String>,
        maxBoxWidth: Float,
        isOval: Boolean,
        measureWord: (String) -> Float,
        fontSize: Float
    ): List<String> {
        if (words.size <= 1) return words

        val spaceW = measureWord(" ")
        val wordWidths = words.map { measureWord(it) }

        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()
        var currentLineWidth = 0f

        for (i in words.indices) {
            val word = words[i]
            val w = wordWidths[i]

            val neededWidth = if (currentLine.isEmpty()) w else currentLineWidth + spaceW + w

            val isFirstOrLastExpected = lines.isEmpty() || i >= words.size - 2
            val effectiveLimit = if (isOval && isFirstOrLastExpected && words.size >= 4) {
                maxBoxWidth * 0.82f
            } else {
                maxBoxWidth
            }

            if (neededWidth <= effectiveLimit || currentLine.isEmpty()) {
                if (currentLine.isNotEmpty()) {
                    currentLine.append(" ")
                    currentLineWidth += spaceW
                }
                currentLine.append(word)
                currentLineWidth += w
            } else {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
                currentLineWidth = w
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        // Optimasi: Hindari "orphan word" (baris terakhir hanya 1 kata pendek) jika memungkinkan
        if (lines.size >= 2) {
            val lastLineWords = lines.last().split(" ")
            if (lastLineWords.size == 1 && lastLineWords[0].length <= 5) {
                val prevLineWords = lines[lines.size - 2].split(" ").toMutableList()
                if (prevLineWords.size >= 2) {
                    val pulledWord = prevLineWords.removeAt(prevLineWords.size - 1)
                    val newPrevLine = prevLineWords.joinToString(" ")
                    val newLastLine = "$pulledWord ${lines.last()}"
                    val newLastW = measureWord(newLastLine)
                    if (newLastW <= maxBoxWidth) {
                        lines[lines.size - 2] = newPrevLine
                        lines[lines.size - 1] = newLastLine
                    }
                }
            }
        }

        return lines
    }

    private fun evaluatePenalty(
        candidate: LayoutResult,
        targetWidth: Float,
        targetHeight: Float,
        scale: Float
    ): Float {
        var penalty = 0f

        // 1. Penalti overflow tinggi
        if (candidate.estimatedHeight > targetHeight) {
            penalty += (candidate.estimatedHeight - targetHeight) * 10f
        }

        // 2. Penalti overflow lebar
        if (candidate.boxWidth > targetWidth) {
            penalty += (candidate.boxWidth - targetWidth) * 10f
        }

        // 3. Penalti penurunan ukuran font (prefer larger, closer to style default)
        penalty += (1.0f - scale) * 200f

        // 4. Penalti jika terlalu banyak baris untuk teks pendek
        if (candidate.lineCount > 6) {
            penalty += (candidate.lineCount - 6) * 30f
        }

        return penalty
    }
}
