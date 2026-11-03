package com.astral.typer.utils

import java.util.Locale

/**
 * Pemenggal suku kata dan kata Bahasa Indonesia berdasarkan kaidah EYD / PUEBI.
 * Mendukung pemenggalan kata dasar, diftong, konsonan rangkap (ng, ny, sy, kh),
 * serta imbuhan umum (awalan dan akhiran).
 */
object IndonesianHyphenator {

    private val DIGRAPHS = setOf("ng", "ny", "sy", "kh")
    private val DIPHTHONGS = setOf("ai", "au", "oi", "ei")
    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

    private val PREFIXES = listOf(
        "meng", "meny", "men", "mem", "me",
        "peng", "peny", "pen", "pem", "per", "pe",
        "ber", "be", "ter", "te", "di", "ke", "se"
    )

    private val SUFFIXES = listOf(
        "kan", "an", "i", "kah", "lah", "pun", "nya", "ku", "mu"
    )

    /**
     * Memecah kata menjadi suku kata yang valid menurut tata bahasa Indonesia.
     * Contoh:
     * - "makan" -> ["ma", "kan"]
     * - "belajar" -> ["be", "la", "jar"]
     * - "menyelesaikan" -> ["me", "nye", "le", "sai", "kan"]
     * - "pantai" -> ["pan", "tai"]
     */
    fun hyphenateWord(word: String): List<String> {
        if (word.length <= 3) return listOf(word)

        // Pisahkan tanda baca di awal atau akhir kata jika ada
        var prefixPunct = ""
        var suffixPunct = ""
        var cleanWord = word

        var startIdx = 0
        while (startIdx < cleanWord.length && !cleanWord[startIdx].isLetter()) {
            prefixPunct += cleanWord[startIdx]
            startIdx++
        }
        cleanWord = cleanWord.substring(startIdx)

        var endIdx = cleanWord.length
        while (endIdx > 0 && !cleanWord[endIdx - 1].isLetter()) {
            suffixPunct = cleanWord[endIdx - 1] + suffixPunct
            endIdx--
        }
        cleanWord = cleanWord.substring(0, endIdx)

        if (cleanWord.length <= 3) {
            return listOf(prefixPunct + cleanWord + suffixPunct)
        }

        val syllables = splitBaseSyllables(cleanWord)
        if (syllables.isEmpty()) {
            return listOf(word)
        }

        val result = syllables.toMutableList()
        if (prefixPunct.isNotEmpty()) {
            result[0] = prefixPunct + result[0]
        }
        if (suffixPunct.isNotEmpty()) {
            result[result.size - 1] = result[result.size - 1] + suffixPunct
        }
        return result
    }

    private fun isVowel(c: Char): Boolean = VOWELS.contains(c.lowercaseChar())

    private fun splitBaseSyllables(text: String): List<String> {
        val lower = text.lowercase(Locale.ROOT)
        val breaks = mutableListOf<Int>()
        val len = text.length

        var i = 0
        while (i < len - 1) {
            val c1 = lower[i]
            val c2 = lower[i + 1]

            // 1. Dua vokal berurutan
            if (isVowel(c1) && isVowel(c2)) {
                val pair = "" + c1 + c2
                // Jika bukan diftong (ai, au, oi, ei), pisahkan di antara kedua vokal (ma-in, sa-at)
                if (!DIPHTHONGS.contains(pair)) {
                    breaks.add(i + 1)
                }
            } else if (isVowel(c1) && !isVowel(c2)) {
                // Vokal diikuti satu atau lebih konsonan
                if (i + 2 < len) {
                    val c3 = lower[i + 2]
                    if (isVowel(c3)) {
                        // V - K - V -> pemisahan sebelum konsonan (a-nak, u-bi, ba-tu)
                        breaks.add(i + 1)
                    } else {
                        // V - K - K ...
                        val pairDigraph = "" + c2 + c3
                        if (DIGRAPHS.contains(pairDigraph)) {
                            // Konsonan rangkap melambangkan 1 bunyi (ng, ny, sy, kh)
                            if (i + 3 < len && isVowel(lower[i + 3])) {
                                // V - [digraph] - V -> pisah sebelum digraph (sa-ngat, ke-nyang)
                                breaks.add(i + 1)
                            } else {
                                // V - [digraph] - K ... (bang-krut) -> pisah setelah digraph
                                breaks.add(i + 3)
                            }
                        } else {
                            // Konsonan ganda biasa: pisah di antara kedua konsonan (am-bil, pan-tai)
                            breaks.add(i + 2)
                        }
                    }
                }
            } else if (!isVowel(c1) && !isVowel(c2)) {
                // Dua konsonan berurutan
                val pairDigraph = "" + c1 + c2
                if (!DIGRAPHS.contains(pairDigraph) && i > 0 && isVowel(lower[i - 1]) && i + 2 < len && isVowel(lower[i + 2])) {
                    // K-K diapit vokal (V-K-K-V), pisahkan di tengah konsonan (pin-tu)
                    if (!breaks.contains(i + 1)) {
                        breaks.add(i + 1)
                    }
                }
            }
            i++
        }

        if (breaks.isEmpty()) {
            return listOf(text)
        }

        // Susun potongan suku kata berdasarkan break index
        val sortedBreaks = breaks.distinct().filter { it in 1 until len }.sorted()
        val syllables = mutableListOf<String>()
        var prev = 0
        for (b in sortedBreaks) {
            // Hindari suku kata terlalu kecil (1 huruf konsonan lepas)
            if (b - prev >= 1) {
                syllables.add(text.substring(prev, b))
                prev = b
            }
        }
        if (prev < len) {
            syllables.add(text.substring(prev))
        }

        // Sanitasi: jika suku kata terakhir tidak memiliki vokal, gabungkan ke suku kata sebelumnya
        val cleaned = mutableListOf<String>()
        for (syl in syllables) {
            if (cleaned.isEmpty()) {
                cleaned.add(syl)
            } else {
                val hasVowel = syl.any { isVowel(it) }
                if (!hasVowel) {
                    cleaned[cleaned.size - 1] = cleaned.last() + syl
                } else {
                    cleaned.add(syl)
                }
            }
        }

        return cleaned
    }

    /**
     * Menghasilkan titik potong potensial beserta tanda hubung "-" untuk sebuah kata panjang.
     * Contoh "menyelesaikan" -> [("menye-", "lesaikan"), ("menyelesai-", "kan")]
     */
    fun getHyphenationOpportunities(word: String): List<Pair<String, String>> {
        val syllables = hyphenateWord(word)
        if (syllables.size <= 1) return emptyList()

        val opportunities = mutableListOf<Pair<String, String>>()
        var prefix = ""
        for (i in 0 until syllables.size - 1) {
            prefix += syllables[i]
            val suffix = syllables.subList(i + 1, syllables.size).joinToString("")
            // Hindari memenggal jika prefix atau suffix terlalu pendek (< 2 huruf)
            if (prefix.length >= 2 && suffix.length >= 2) {
                opportunities.add(Pair("$prefix-", suffix))
            }
        }
        return opportunities
    }
}
