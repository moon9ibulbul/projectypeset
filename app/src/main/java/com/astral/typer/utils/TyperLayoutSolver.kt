package com.astral.typer.utils

import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mesin penyusun layout teks cerdas terinspirasi dari Typer Tools (TypeR / MD TYPER).
 * Menghitung ukuran font bertahap (graceful stepping), optimasi bentuk siluet balon
 * (diamond / oval silhouette "berundak"), serta pemenggalan kata cerdas (Indonesian hyphenation).
 */
object TyperLayoutSolver {

    data class LayoutResult(
        val formattedText: String,
        val boxWidth: Float,
        val scale: Float,
        val estimatedHeight: Float,
        val lineCount: Int,
        val variantName: String = ""
    )

    enum class ReshapeProfile {
        BALANCED_DIAMOND,
        COMPACT_DIAMOND,
        WIDE_DIAMOND,
        NATURAL_STEPPED,
        GENTLE_DIAMOND
    }

    /**
     * Memecahkan tata letak teks terbaik di dalam bounding box balon menggunakan TextPaint Android.
     */
    fun solveLayout(
        rawText: String,
        bubbleRect: RectF,
        isOval: Boolean,
        basePaint: TextPaint,
        minScale: Float = 0.20f
    ): LayoutResult {
        val wordCache = mutableMapOf<String, Float>()
        val baseFontSize = basePaint.textSize
        return solveLayoutInternal(
            rawText = rawText,
            bubbleWidth = bubbleRect.width(),
            bubbleHeight = bubbleRect.height(),
            isOval = isOval,
            baseFontSize = baseFontSize,
            measureWord = { word, size ->
                val baseW = wordCache.getOrPut(word) { basePaint.measureText(word) }
                (baseW / baseFontSize) * size
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
        minScale: Float = 0.20f
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
        minScale: Float = 0.20f
    ): LayoutResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return LayoutResult("", bubbleWidth.coerceAtLeast(50f), 1f, 0f, 0)
        }

        // Padding aman di dalam balon agar teks tidak menempel di batas dinding
        val hPadding = if (isOval) bubbleWidth * 0.16f else bubbleWidth * 0.10f
        val vPadding = if (isOval) bubbleHeight * 0.16f else bubbleHeight * 0.10f

        val targetWidth = (bubbleWidth - hPadding * 2).coerceAtLeast(40f)
        val targetHeight = (bubbleHeight - vPadding * 2).coerceAtLeast(40f)

        // 1. Ekstrak kata-kata asli
        val originalWords = trimmed.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (originalWords.isEmpty()) {
            return LayoutResult(trimmed, targetWidth, 1f, 0f, 0)
        }

        // Kasus 1 kata tunggal
        if (originalWords.size == 1) {
            return solveSingleWord(originalWords[0], targetWidth, targetHeight, baseFontSize, measureWord, minScale)
        }

        // Evaluasi ukuran font bertahap (Graceful Stepping dari 100% turun hingga minScale)
        val scaleSteps = mutableListOf<Float>()
        var s = 1.0f
        while (s >= minScale - 0.001f) {
            scaleSteps.add((s * 100).toInt() / 100f)
            s -= 0.05f
        }
        if (scaleSteps.isEmpty() || scaleSteps.last() > minScale) {
            scaleSteps.add(minScale)
        }

        var bestCandidate: CandidateResult? = null
        var bestPenalty = Float.MAX_VALUE

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
            ) ?: continue

            // Hitung penalti
            val penalty = evaluatePenalty(candidate, targetWidth, targetHeight, isOval, scale)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestCandidate = candidate
            }
        }

        if (bestCandidate != null) {
            val boxW = (bestCandidate.maxLineWidth + 10f).coerceAtMost(targetWidth).coerceAtLeast(40f)
            return LayoutResult(
                formattedText = bestCandidate.lines.joinToString("\n"),
                boxWidth = boxW,
                scale = bestCandidate.scale,
                estimatedHeight = bestCandidate.estimatedHeight,
                lineCount = bestCandidate.lines.size
            )
        }

        // Fallback jika tidak ada kandidat yang muat sempurna
        val fallbackFontSize = baseFontSize * minScale
        val fallbackWords = preprocessWordsForWidth(originalWords, targetWidth, allowHyphenation = true) { w -> measureWord(w, fallbackFontSize) }
        val lines = greedyWrapLines(fallbackWords, targetWidth) { w -> measureWord(w, fallbackFontSize) }
        val maxW = lines.maxOfOrNull { measureWord(it, fallbackFontSize) } ?: targetWidth
        return LayoutResult(
            formattedText = lines.joinToString("\n"),
            boxWidth = (maxW + 10f).coerceAtMost(targetWidth).coerceAtLeast(40f),
            scale = minScale,
            estimatedHeight = lines.size * fallbackFontSize * 1.25f,
            lineCount = lines.size
        )
    }

    /**
     * Menghasilkan hingga 5 variasi layout cerdas berbentuk diamond stepped silhouette untuk layer teks aktif.
     * Mengutamakan keutuhan kata tanpa pemenggalan suku kata (Indonesian hyphenation) sebisa mungkin.
     */
    fun generateReshapeVariants(
        rawText: String,
        currentWidth: Float,
        currentHeight: Float,
        isOval: Boolean,
        basePaint: TextPaint,
        minScale: Float = 0.20f
    ): List<LayoutResult> {
        // Optimasi: Ukur kata satu kali di basePaint.textSize tanpa alokasi TextPaint baru berulang-ulang
        val wordCache = mutableMapOf<String, Float>()
        val baseMeasure = { word: String ->
            wordCache.getOrPut(word) { basePaint.measureText(word) }
        }
        val spaceW = basePaint.measureText(" ")

        return generateReshapeVariantsFast(
            rawText = rawText,
            targetW = currentWidth,
            targetH = currentHeight,
            isOval = isOval,
            baseFontSize = basePaint.textSize,
            measureBaseWord = baseMeasure,
            baseSpaceW = spaceW,
            minScale = minScale
        )
    }

    /**
     * Versi pure/decoupled untuk JVM unit testing.
     */
    fun generateReshapeVariantsPure(
        rawText: String,
        currentWidth: Float,
        currentHeight: Float,
        isOval: Boolean,
        baseFontSize: Float,
        minScale: Float = 0.20f
    ): List<LayoutResult> {
        return generateReshapeVariantsFast(
            rawText = rawText,
            targetW = currentWidth,
            targetH = currentHeight,
            isOval = isOval,
            baseFontSize = baseFontSize,
            measureBaseWord = { word -> word.length * baseFontSize * 0.55f },
            baseSpaceW = baseFontSize * 0.35f,
            minScale = minScale
        )
    }

    /**
     * Engine komputasi cepat (O(1) allocation) untuk menghasilkan 5 variasi layout diamond.
     * Menggunakan penskalaan proporsional dari base width yang sudah diukur sebelumnya.
     */
    private fun generateReshapeVariantsFast(
        rawText: String,
        targetW: Float,
        targetH: Float,
        isOval: Boolean,
        baseFontSize: Float,
        measureBaseWord: (String) -> Float,
        baseSpaceW: Float,
        minScale: Float = 0.20f
    ): List<LayoutResult> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val rawOrigWords = trimmed.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (rawOrigWords.isEmpty()) return emptyList()

        val effWidth = targetW.coerceAtLeast(80f)
        val effHeight = targetH.coerceAtLeast(80f)

        val hasReduplication = rawOrigWords.any { isReduplicationWord(it) }

        if (rawOrigWords.size == 1 && !hasReduplication) {
            val single = solveSingleWord(rawOrigWords[0], effWidth, effHeight, baseFontSize, { w, s -> (measureBaseWord(w) / baseFontSize) * s }, minScale)
            return listOf(single.copy(variantName = "Standar"))
        }

        val variants = mutableListOf<LayoutResult>()
        val seenFormatted = mutableSetOf<String>()

        val origWords = rawOrigWords
        val baseWordWidths = origWords.map { measureBaseWord(it) }
        val nWords = origWords.size

        // 3 Konfigurasi profil variasi dengan parameter siluet yang berbeda
        val profileConfigs = listOf(
            ProfileConfig(ReshapeProfile.BALANCED_DIAMOND, "Diamond Seimbang", targetLinesOffset = 0, aspectMult = 1.0f, curveBonus = 50f),
            ProfileConfig(ReshapeProfile.COMPACT_DIAMOND, "Diamond Kompak", targetLinesOffset = 1, aspectMult = 0.85f, curveBonus = 40f),
            ProfileConfig(ReshapeProfile.WIDE_DIAMOND, "Diamond Melebar", targetLinesOffset = -1, aspectMult = 1.25f, curveBonus = 35f)
        )

        // Skala langkah cepat (lompatan 10% untuk performa instan)
        val fastScaleSteps = listOf(1.0f, 0.90f, 0.80f, 0.70f, 0.60f, 0.50f, 0.40f, 0.30f, 0.20f)
            .filter { it >= minScale - 0.01f }

        for (cfg in profileConfigs) {
            val result = solveForVariantFast(
                words = origWords,
                baseWordWidths = baseWordWidths,
                baseSpaceW = baseSpaceW,
                targetW = effWidth,
                targetH = effHeight,
                isOval = isOval,
                baseFontSize = baseFontSize,
                scaleSteps = fastScaleSteps,
                cfg = cfg,
                seenFormatted = seenFormatted
            )
            if (result != null) {
                seenFormatted.add(result.formattedText)
                variants.add(result)
            }
        }

        if (hasReduplication) {
            val splitWords = mutableListOf<String>()
            val splitWordIndices = mutableSetOf<Int>()
            for (w in rawOrigWords) {
                if (isReduplicationWord(w)) {
                    val parts = splitReduplication(w)
                    if (parts != null) {
                        splitWordIndices.add(splitWords.size)
                        splitWords.add(parts.first)
                        splitWords.add(parts.second)
                    } else {
                        splitWords.add(w)
                    }
                } else {
                    splitWords.add(w)
                }
            }

            if (splitWords.size > rawOrigWords.size) {
                val splitBaseWordWidths = splitWords.map { measureBaseWord(it) }
                for (cfg in profileConfigs) {
                    if (variants.size >= 3) break
                    val result = solveForVariantFast(
                        words = splitWords,
                        baseWordWidths = splitBaseWordWidths,
                        baseSpaceW = baseSpaceW,
                        targetW = effWidth,
                        targetH = effHeight,
                        isOval = isOval,
                        baseFontSize = baseFontSize,
                        scaleSteps = fastScaleSteps,
                        cfg = cfg,
                        seenFormatted = seenFormatted,
                        reduplicationSplitIndices = splitWordIndices
                    )
                    if (result != null) {
                        seenFormatted.add(result.formattedText)
                        variants.add(result)
                    }
                }
            }
        }

        // Jika opsi masih kurang dari 3 (misal teks pendek atau profil beririsan),
        // sediakan partisi eksplisit target baris L=2, 3, 1, 4
        if (variants.size < 3) {
            val fallbackLineTargets = listOf(2, 3, 1, 4)
            for (targetL in fallbackLineTargets) {
                if (variants.size >= 3) break
                if (targetL > nWords) continue

                val fbResult = solveForExplicitLineCountFast(
                    words = origWords,
                    baseWordWidths = baseWordWidths,
                    baseSpaceW = baseSpaceW,
                    targetL = targetL,
                    targetW = effWidth,
                    targetH = effHeight,
                    isOval = isOval,
                    baseFontSize = baseFontSize,
                    scaleSteps = fastScaleSteps
                )
                if (fbResult != null && !seenFormatted.contains(fbResult.formattedText)) {
                    seenFormatted.add(fbResult.formattedText)
                    variants.add(fbResult)
                }
            }
        }

        return variants.take(3)
    }

    private data class ProfileConfig(
        val profile: ReshapeProfile,
        val title: String,
        val targetLinesOffset: Int,
        val aspectMult: Float,
        val curveBonus: Float
    )

    fun isReduplicationWord(token: String): Boolean {
        val clean = token.trim().trim('"', '\'', '“', '”', '(', ')', '[', ']', '.', ',', '!', '?', ':', ';')
        val dashIdx = clean.indexOf('-')
        if (dashIdx <= 0 || dashIdx >= clean.length - 1) return false
        val left = clean.substring(0, dashIdx).lowercase()
        val right = clean.substring(dashIdx + 1).lowercase()
        if (left.length < 2 || right.length < 2) return false
        if (left == right) return true
        if (left.endsWith(right) || right.endsWith(left)) return true
        if (left.startsWith(right) || right.startsWith(left)) return true
        val prefixes = listOf("ter", "ber", "me", "di", "ke", "se", "pe", "per")
        for (pref in prefixes) {
            if (left.startsWith(pref) && left.removePrefix(pref) == right) return true
            if (right.startsWith(pref) && right.removePrefix(pref) == left) return true
        }
        return false
    }

    fun splitReduplication(token: String): Pair<String, String>? {
        val dashIdx = token.indexOf('-')
        if (dashIdx <= 0 || dashIdx >= token.length - 1) return null
        val left = token.substring(0, dashIdx + 1)
        val right = token.substring(dashIdx + 1)
        return Pair(left, right)
    }

    private fun solveForVariantFast(
        words: List<String>,
        baseWordWidths: List<Float>,
        baseSpaceW: Float,
        targetW: Float,
        targetH: Float,
        isOval: Boolean,
        baseFontSize: Float,
        scaleSteps: List<Float>,
        cfg: ProfileConfig,
        seenFormatted: Set<String>,
        reduplicationSplitIndices: Set<Int> = emptySet()
    ): LayoutResult? {
        val adjustedW = targetW * cfg.aspectMult
        val adjustedH = targetH / cfg.aspectMult
        val n = words.size

        var bestCandidate: CandidateResult? = null
        var bestCost = Float.MAX_VALUE

        var minL = max(1, 1 + cfg.targetLinesOffset)
        var maxL = min(n, 5 + cfg.targetLinesOffset)
        if (minL > maxL) minL = 1

        for (scale in scaleSteps) {
            val curFontSize = baseFontSize * scale
            val lineSpacing = curFontSize * 1.25f
            val curSpaceW = baseSpaceW * scale
            val maxAllowedW = adjustedW * 1.10f

            for (L in minL..maxL) {
                val estH = L * lineSpacing
                if (estH > adjustedH * 1.25f) continue

                val profile = getVariantSilhouetteProfile(L, isOval, cfg.profile)
                val partitions = partitionIndicesFast(n, L, baseWordWidths, curSpaceW, scale, maxAllowedW)

                for (partIndices in partitions) {
                    val lineTexts = partIndices.map { (start, end) ->
                        val sb = StringBuilder()
                        for (idx in start until end) {
                            if (idx > start) {
                                if (!words[idx - 1].endsWith("-")) {
                                    sb.append(" ")
                                }
                            }
                            sb.append(words[idx])
                        }
                        sb.toString()
                    }
                    val formatted = lineTexts.joinToString("\n")
                    if (seenFormatted.contains(formatted)) continue

                    val lineWidths = partIndices.map { (start, end) ->
                        var w = 0f
                        for (i in start until end) {
                            w += baseWordWidths[i] * scale
                        }
                        w += (end - start - 1) * curSpaceW
                        w
                    }

                    val maxLineWidth = lineWidths.maxOrNull() ?: 0f
                    if (maxLineWidth > maxAllowedW) continue

                    var cost = calculateSilhouetteCost(lineWidths, maxLineWidth, profile, isOval, L, adjustedW, estH, adjustedH)
                    cost -= cfg.curveBonus
                    cost += (1.0f - scale) * 35f

                    if (reduplicationSplitIndices.isNotEmpty()) {
                        for (splitIdx in reduplicationSplitIndices) {
                            val splitPart = partIndices.find { it.first <= splitIdx && splitIdx < it.second }
                            if (splitPart != null && splitPart.second == splitIdx + 1) {
                                cost += 15f
                            }
                        }
                    }

                    // Penalti orphan word
                    val lastLineWordsCount = partIndices.last().second - partIndices.last().first
                    if (lastLineWordsCount == 1 && words[partIndices.last().first].length <= 3) {
                        cost += 20f
                    }

                    if (cost < bestCost) {
                        bestCost = cost
                        bestCandidate = CandidateResult(
                            lines = lineTexts,
                            lineWords = emptyList(),
                            lineWidths = lineWidths,
                            maxLineWidth = maxLineWidth,
                            estimatedHeight = estH,
                            scale = scale,
                            lineCount = L
                        )
                    }
                }
            }
            if (bestCandidate != null) break
        }

        if (bestCandidate != null) {
            val boxW = (bestCandidate.maxLineWidth + 10f).coerceAtLeast(40f)
            return LayoutResult(
                formattedText = bestCandidate.lines.joinToString("\n"),
                boxWidth = boxW,
                scale = bestCandidate.scale,
                estimatedHeight = bestCandidate.estimatedHeight,
                lineCount = bestCandidate.lines.size,
                variantName = cfg.title
            )
        }
        return null
    }

    private fun solveForExplicitLineCountFast(
        words: List<String>,
        baseWordWidths: List<Float>,
        baseSpaceW: Float,
        targetL: Int,
        targetW: Float,
        targetH: Float,
        isOval: Boolean,
        baseFontSize: Float,
        scaleSteps: List<Float>
    ): LayoutResult? {
        val n = words.size
        if (targetL > n) return null

        for (scale in scaleSteps) {
            val curFontSize = baseFontSize * scale
            val curSpaceW = baseSpaceW * scale
            val maxAllowedW = targetW * 1.25f

            val partitions = partitionIndicesFast(n, targetL, baseWordWidths, curSpaceW, scale, maxAllowedW)
            if (partitions.isNotEmpty()) {
                val profile = getIdealSilhouetteProfile(targetL, isOval)
                val bestPart = partitions.minByOrNull { partIndices ->
                    val lineWidths = partIndices.map { (start, end) ->
                        var w = 0f
                        for (i in start until end) {
                            w += baseWordWidths[i] * scale
                        }
                        w += (end - start - 1) * curSpaceW
                        w
                    }
                    val maxW = lineWidths.maxOrNull() ?: 1f
                    calculateSilhouetteCost(lineWidths, maxW, profile, isOval, targetL, targetW, targetL * curFontSize * 1.25f, targetH)
                } ?: partitions.first()

                val lineTexts = bestPart.map { (start, end) ->
                    words.subList(start, end).joinToString(" ")
                }
                val lineWidths = bestPart.map { (start, end) ->
                    var w = 0f
                    for (i in start until end) {
                        w += baseWordWidths[i] * scale
                    }
                    w += (end - start - 1) * curSpaceW
                    w
                }
                val maxW = lineWidths.maxOrNull() ?: targetW
                return LayoutResult(
                    formattedText = lineTexts.joinToString("\n"),
                    boxWidth = (maxW + 10f).coerceAtLeast(40f),
                    scale = scale,
                    estimatedHeight = targetL * curFontSize * 1.25f,
                    lineCount = targetL,
                    variantName = "Opsi $targetL Baris"
                )
            }
        }
        return null
    }

    /**
     * Generator partisi cepat berbasis indeks [start, end) kata tanpa alokasi memori string.
     */
    private fun partitionIndicesFast(
        n: Int,
        L: Int,
        baseWordWidths: List<Float>,
        spaceW: Float,
        scale: Float,
        maxW: Float
    ): List<List<Pair<Int, Int>>> {
        val results = mutableListOf<List<Pair<Int, Int>>>()

        fun backtrack(start: Int, lineIdx: Int, currentLines: MutableList<Pair<Int, Int>>) {
            val linesLeft = L - lineIdx
            if (linesLeft == 1) {
                var currentLineW = 0f
                for (i in start until n) {
                    val w = baseWordWidths[i] * scale
                    currentLineW += if (i == start) w else spaceW + w
                }
                if (currentLineW <= maxW) {
                    currentLines.add(Pair(start, n))
                    results.add(ArrayList(currentLines))
                    currentLines.removeAt(currentLines.size - 1)
                }
                return
            }

            var currentLineW = 0f
            val maxEnd = n - (linesLeft - 1)
            for (end in (start + 1)..maxEnd) {
                val w = baseWordWidths[end - 1] * scale
                currentLineW += if (end - 1 == start) w else spaceW + w
                if (currentLineW > maxW) break

                currentLines.add(Pair(start, end))
                backtrack(end, lineIdx + 1, currentLines)
                currentLines.removeAt(currentLines.size - 1)
            }
        }

        backtrack(0, 0, mutableListOf())
        return results
    }

    private fun getVariantSilhouetteProfile(L: Int, isOval: Boolean, variant: ReshapeProfile): FloatArray {
        if (L <= 1) return FloatArray(L) { 1.0f }

        return when (variant) {
            ReshapeProfile.BALANCED_DIAMOND -> {
                when (L) {
                    2 -> floatArrayOf(0.92f, 0.92f)
                    3 -> floatArrayOf(0.70f, 1.00f, 0.72f)
                    4 -> floatArrayOf(0.66f, 1.00f, 0.96f, 0.68f)
                    5 -> floatArrayOf(0.55f, 0.88f, 1.00f, 0.88f, 0.58f)
                    else -> getIdealSilhouetteProfile(L, isOval)
                }
            }
            ReshapeProfile.COMPACT_DIAMOND -> {
                when (L) {
                    2 -> floatArrayOf(0.85f, 0.85f)
                    3 -> floatArrayOf(0.60f, 1.00f, 0.62f)
                    4 -> floatArrayOf(0.58f, 0.95f, 1.00f, 0.60f)
                    5 -> floatArrayOf(0.48f, 0.82f, 1.00f, 0.84f, 0.50f)
                    else -> getIdealSilhouetteProfile(L, isOval)
                }
            }
            ReshapeProfile.WIDE_DIAMOND -> {
                when (L) {
                    2 -> floatArrayOf(1.00f, 0.95f)
                    3 -> floatArrayOf(0.80f, 1.00f, 0.82f)
                    4 -> floatArrayOf(0.78f, 1.00f, 0.98f, 0.80f)
                    5 -> floatArrayOf(0.70f, 0.95f, 1.00f, 0.95f, 0.72f)
                    else -> getIdealSilhouetteProfile(L, isOval)
                }
            }
            ReshapeProfile.NATURAL_STEPPED -> {
                when (L) {
                    2 -> floatArrayOf(0.95f, 0.90f)
                    3 -> floatArrayOf(0.75f, 1.00f, 0.80f)
                    4 -> floatArrayOf(0.72f, 0.98f, 1.00f, 0.75f)
                    5 -> floatArrayOf(0.65f, 0.90f, 1.00f, 0.92f, 0.68f)
                    else -> getIdealSilhouetteProfile(L, isOval)
                }
            }
            ReshapeProfile.GENTLE_DIAMOND -> {
                when (L) {
                    2 -> floatArrayOf(0.88f, 0.92f)
                    3 -> floatArrayOf(0.65f, 1.00f, 0.78f)
                    4 -> floatArrayOf(0.62f, 1.00f, 0.90f, 0.65f)
                    5 -> floatArrayOf(0.52f, 0.85f, 1.00f, 0.80f, 0.55f)
                    else -> getIdealSilhouetteProfile(L, isOval)
                }
            }
        }
    }

    private fun solveSingleWord(
        word: String,
        targetWidth: Float,
        targetHeight: Float,
        baseFontSize: Float,
        measureWord: (String, Float) -> Float,
        minScale: Float
    ): LayoutResult {
        var s = 1.0f
        var bestScale = minScale
        while (s >= minScale - 0.001f) {
            val curFs = baseFontSize * s
            val w = measureWord(word, curFs)
            val h = curFs * 1.25f
            if (w <= targetWidth && h <= targetHeight) {
                bestScale = s
                break
            }
            s -= 0.05f
        }
        val finalFs = baseFontSize * bestScale
        val finalW = measureWord(word, finalFs)
        return LayoutResult(
            formattedText = word,
            boxWidth = (finalW + 10f).coerceAtMost(targetWidth).coerceAtLeast(40f),
            scale = bestScale,
            estimatedHeight = finalFs * 1.25f,
            lineCount = 1
        )
    }

    private data class CandidateResult(
        val lines: List<String>,
        val lineWords: List<List<String>>,
        val lineWidths: List<Float>,
        val maxLineWidth: Float,
        val estimatedHeight: Float,
        val scale: Float,
        val lineCount: Int
    )

    private fun solveForScale(
        words: List<String>,
        targetWidth: Float,
        targetHeight: Float,
        isOval: Boolean,
        fontSize: Float,
        measureWord: (String) -> Float,
        scale: Float
    ): CandidateResult? {
        val processedWords = preprocessWordsForWidth(words, targetWidth, allowHyphenation = true, measureWord = measureWord)
        val n = processedWords.size
        val lineSpacing = fontSize * 1.25f

        // Tentukan batas jumlah baris
        val minL = 1
        // Jika kata >= 3 dan oval, hindari jumlah baris == jumlah kata (1 kata per baris)
        var maxL = min(n, 6)
        if (n >= 3 && isOval) {
            maxL = min(n - 1, 6)
        }

        var bestForScale: CandidateResult? = null
        var minScaleCost = Float.MAX_VALUE

        for (L in minL..maxL) {
            val estH = L * lineSpacing
            if (estH > targetHeight) {
                continue
            }

            val profile = getIdealSilhouetteProfile(L, isOval)

            // Cari semua partisi kata yang valid ke dalam L baris
            val partitions = partitionWordsIntoLines(processedWords, L, targetWidth, measureWord)
            for (parts in partitions) {
                val lineWidths = parts.map { lineWordList ->
                    val totalWordW = lineWordList.sumOf { measureWord(it).toDouble() }.toFloat()
                    val spaceW = (lineWordList.size - 1) * measureWord(" ")
                    totalWordW + spaceW
                }

                val maxW = lineWidths.maxOrNull() ?: 0f
                if (maxW > targetWidth) {
                    continue
                }

                // Hitung biaya siluet
                val cost = calculateSilhouetteCost(lineWidths, maxW, profile, isOval, L, targetWidth, estH, targetHeight)
                if (cost < minScaleCost) {
                    minScaleCost = cost
                    val lineStrings = parts.map { it.joinToString(" ") }
                    bestForScale = CandidateResult(
                        lines = lineStrings,
                        lineWords = parts,
                        lineWidths = lineWidths,
                        maxLineWidth = maxW,
                        estimatedHeight = estH,
                        scale = scale,
                        lineCount = L
                    )
                }
            }
        }

        return bestForScale
    }

    private fun preprocessWordsForWidth(
        words: List<String>,
        maxWidth: Float,
        allowHyphenation: Boolean = true,
        measureWord: (String) -> Float
    ): List<String> {
        val result = mutableListOf<String>()
        for (w in words) {
            val wordW = measureWord(w)
            if (allowHyphenation && wordW > maxWidth && w.length > 5) {
                val opportunities = IndonesianHyphenator.getHyphenationOpportunities(w)
                if (opportunities.isNotEmpty()) {
                    val mid = opportunities[opportunities.size / 2]
                    result.add(mid.first)
                    result.add(mid.second)
                    continue
                }
            }
            result.add(w)
        }
        return result
    }

    private fun partitionWordsIntoLines(
        words: List<String>,
        L: Int,
        maxTargetWidth: Float,
        measureWord: (String) -> Float
    ): List<List<List<String>>> {
        val n = words.size
        val spaceW = measureWord(" ")
        val wordWidths = words.map { measureWord(it) }

        val results = mutableListOf<List<List<String>>>()

        fun backtrack(start: Int, lineIdx: Int, currentLines: MutableList<List<String>>) {
            val linesLeft = L - lineIdx
            if (linesLeft == 1) {
                val slice = words.subList(start, n)
                var currentLineW = 0f
                for (i in start until n) {
                    val w = wordWidths[i]
                    currentLineW += if (i == start) w else spaceW + w
                }
                if (currentLineW <= maxTargetWidth) {
                    currentLines.add(slice)
                    results.add(currentLines.toList())
                    currentLines.removeAt(currentLines.size - 1)
                }
                return
            }

            var currentLineW = 0f
            val maxEnd = n - (linesLeft - 1)
            for (end in (start + 1)..maxEnd) {
                val w = wordWidths[end - 1]
                currentLineW += if (end - 1 == start) w else spaceW + w
                if (currentLineW > maxTargetWidth) {
                    break
                }

                currentLines.add(words.subList(start, end))
                backtrack(end, lineIdx + 1, currentLines)
                currentLines.removeAt(currentLines.size - 1)
            }
        }

        backtrack(0, 0, mutableListOf())
        return results
    }

    private fun getIdealSilhouetteProfile(L: Int, isOval: Boolean): FloatArray {
        if (!isOval || L <= 1) {
            return FloatArray(L) { 1.0f }
        }
        if (L == 2) {
            return floatArrayOf(0.92f, 0.92f)
        }
        if (L == 3) {
            // Siluet intan / oval berundak: baris tengah paling panjang, atas dan bawah lebih pendek
            return floatArrayOf(0.72f, 1.00f, 0.75f)
        }
        if (L == 4) {
            return floatArrayOf(0.68f, 1.00f, 0.98f, 0.70f)
        }

        val profile = FloatArray(L)
        var maxP = 0f
        for (i in 0 until L) {
            val y = -0.8f + (1.6f * i) / (L - 1)
            val factor = sqrt(max(0.2f, 1.0f - y * y))
            profile[i] = factor
            if (factor > maxP) maxP = factor
        }
        if (maxP > 0f) {
            for (i in 0 until L) {
                profile[i] /= maxP
            }
        }
        return profile
    }

    private fun calculateSilhouetteCost(
        lineWidths: List<Float>,
        maxW: Float,
        profile: FloatArray,
        isOval: Boolean,
        L: Int,
        targetWidth: Float,
        estHeight: Float,
        targetHeight: Float
    ): Float {
        var cost = 0f

        // 1. Kesesuaian dengan siluet ideal
        for (i in lineWidths.indices) {
            val normW = if (maxW > 0) lineWidths[i] / maxW else 1f
            val diff = normW - profile[i]
            cost += diff * diff * 30f
        }

        // 2. Bonus khusus siluet intan (diamond) untuk 3 dan 4 baris balon oval
        if (isOval && L == 3) {
            if (lineWidths[1] >= lineWidths[0] && lineWidths[1] >= lineWidths[2]) {
                cost -= 50f // Hadiahi bentuk intan di mana baris 2 adalah yang terpanjang
            } else {
                cost += 50f
            }
        } else if (isOval && L == 4) {
            val midMin = min(lineWidths[1], lineWidths[2])
            val edgeMax = max(lineWidths[0], lineWidths[3])
            if (midMin >= edgeMax) {
                cost -= 50f
            } else {
                cost += 50f
            }
        }

        // 3. Kesesuaian Aspect Ratio teks terhadap balon dialog
        val bubbleAr = targetWidth / targetHeight
        val textAr = if (estHeight > 0) maxW / estHeight else 1f
        cost += abs(textAr - bubbleAr) * 10f

        return cost
    }

    private fun evaluatePenalty(
        candidate: CandidateResult,
        targetWidth: Float,
        targetHeight: Float,
        isOval: Boolean,
        scale: Float
    ): Float {
        var penalty = 0f

        // 1. Penalti overflow tinggi & lebar (berat agar tidak tembus dinding balon)
        if (candidate.estimatedHeight > targetHeight) {
            penalty += (candidate.estimatedHeight - targetHeight) * 50f
        }
        if (candidate.maxLineWidth > targetWidth) {
            penalty += (candidate.maxLineWidth - targetWidth) * 50f
        }

        // 2. Penalti penurunan ukuran font (preferensikan font size lebih besar yang tetap muat)
        penalty += (1.0f - scale) * 40f

        // 3. Penalti orphan word (baris terakhir hanya 1 kata pendek <= 3 huruf)
        val lastLineWords = candidate.lineWords.lastOrNull()
        if (lastLineWords != null && lastLineWords.size == 1 && lastLineWords[0].length <= 3) {
            penalty += 25f
        }

        // 4. Penalti jika baris terlalu banyak (> 5)
        if (candidate.lineCount > 5) {
            penalty += (candidate.lineCount - 5) * 20f
        }

        // 5. Kesesuaian bentuk siluet berundak
        val profile = getIdealSilhouetteProfile(candidate.lineCount, isOval)
        penalty += calculateSilhouetteCost(
            candidate.lineWidths,
            candidate.maxLineWidth,
            profile,
            isOval,
            candidate.lineCount,
            targetWidth,
            candidate.estimatedHeight,
            targetHeight
        )

        return penalty
    }

    private fun greedyWrapLines(
        words: List<String>,
        maxBoxWidth: Float,
        measureWord: (String) -> Float
    ): List<String> {
        val lines = mutableListOf<String>()
        var cur = StringBuilder()
        var curW = 0f
        val spaceW = measureWord(" ")

        for (w in words) {
            val wordW = measureWord(w)
            val needed = if (cur.isEmpty()) wordW else curW + spaceW + wordW
            if (needed <= maxBoxWidth || cur.isEmpty()) {
                if (cur.isNotEmpty()) {
                    cur.append(" ")
                    curW += spaceW
                }
                cur.append(w)
                curW += wordW
            } else {
                lines.add(cur.toString())
                cur = StringBuilder(w)
                curW = wordW
            }
        }
        if (cur.isNotEmpty()) {
            lines.add(cur.toString())
        }
        return lines
    }
}
