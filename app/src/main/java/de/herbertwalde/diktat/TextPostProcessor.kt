package de.herbertwalde.diktat

import kotlin.math.abs
import kotlin.math.max

/**
 * Nachbearbeitung des Rohtranskripts, portiert aus Handy (cjpais/Handy, src-tauri/src/audio_toolkit/text.rs):
 * Wörterbuch-Korrektur (Levenshtein + Soundex + N-Gramme bis 3 Wörter), Füllwörter entfernen, Leerzeichen normalisieren.
 *
 * Abweichung vom Original: Handy lässt Wörter mit Nicht-ASCII-Zeichen beim Fuzzy-Matching aus. Für Deutsch
 * erlauben wir alle Buchstaben/Ziffern (z. B. „Größenordnung“); Soundex bleibt wie bei Handy auf ASCII-Buchstaben beschränkt.
 */
object TextPostProcessor {

    /** Handy-Standardwert `word_correction_threshold`. */
    const val DEFAULT_THRESHOLD = 0.18

    private val UNIVERSAL_FILLERS = listOf("uh", "uhm", "umm", "uhh", "uhhh", "ehh", "ehm", "ahm", "hmm", "hm", "mmm")
    private val GERMAN_FILLERS = listOf("äh", "ähm", "öhm")

    fun process(raw: String, customWords: List<String>, threshold: Double = DEFAULT_THRESHOLD): String {
        val corrected = applyCustomWords(raw, customWords, threshold)
        return normalize(removeFillerWords(corrected))
    }

    // ---------------------------------------------------------------- Wörterbuch

    private class MatchKey(val wordIndex: Int, val key: String)

    private fun matchKey(word: String): String =
        word.filter { it.isLetterOrDigit() }.lowercase()

    private fun isSupportedFuzzyKey(key: String) = key.isNotEmpty() && key.all { it.isLetterOrDigit() }

    private fun supportsSoundex(key: String) = key.isNotEmpty() && key.all { it in 'a'..'z' }

    private fun buildMatchKeys(word: String, index: Int): List<MatchKey> {
        val keys = mutableListOf<MatchKey>()
        val primary = matchKey(word)
        if (isSupportedFuzzyKey(primary)) keys += MatchKey(index, primary)
        if (word.contains('&')) {
            val expanded = matchKey(word.replace("&", " und "))
            if (isSupportedFuzzyKey(expanded) && expanded != primary) keys += MatchKey(index, expanded)
            val expandedEn = matchKey(word.replace("&", " and "))
            if (isSupportedFuzzyKey(expandedEn) && expandedEn != primary) keys += MatchKey(index, expandedEn)
        }
        return keys
    }

    private fun findBestMatch(
        candidate: String,
        customWords: List<String>,
        keys: List<MatchKey>,
        threshold: Double,
    ): Pair<String, Double>? {
        if (!isSupportedFuzzyKey(candidate) || candidate.length > 50) return null
        var best: String? = null
        var bestScore = Double.MAX_VALUE
        for (k in keys) {
            val maxLen = max(candidate.length, k.key.length).toDouble()
            val lenDiff = abs(candidate.length - k.key.length).toDouble()
            val maxAllowedDiff = max(maxLen * 0.25, 2.0)
            if (lenDiff > maxAllowedDiff) continue

            val levScore = if (maxLen > 0) levenshtein(candidate, k.key) / maxLen else 1.0
            val phonetic = supportsSoundex(candidate) && supportsSoundex(k.key) && soundex(candidate) == soundex(k.key)
            val combined = if (phonetic) levScore * 0.3 else levScore
            if (combined < threshold && combined < bestScore) {
                best = customWords[k.wordIndex]
                bestScore = combined
            }
        }
        return best?.let { it to bestScore }
    }

    fun applyCustomWords(text: String, customWords: List<String>, threshold: Double = DEFAULT_THRESHOLD): String {
        val words = customWords.map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return text
        val keys = words.flatMapIndexed { i, w -> buildMatchKeys(w, i) }

        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            var best: Triple<Int, String, Double>? = null
            for (n in 3 downTo 1) {
                if (i + n > tokens.size) continue
                val ngramTokens = tokens.subList(i, i + n)
                // Nicht über Satzzeichen hinweg zusammenziehen ("Charge B, che").
                if (ngramTokens.dropLast(1).any { extractPunctuation(it).second.isNotEmpty() }) continue
                val ngram = ngramTokens.joinToString("") { matchKey(it) }
                val match = findBestMatch(ngram, words, keys, threshold) ?: continue
                if (best == null || match.second < best.third) best = Triple(n, match.first, match.second)
            }
            if (best != null) {
                val (n, replacement, _) = best
                val prefix = extractPunctuation(tokens[i]).first
                val suffix = extractPunctuation(tokens[i + n - 1]).second
                result += prefix + preserveCase(tokens[i], replacement) + suffix
                i += n
            } else {
                result += tokens[i]
                i++
            }
        }
        return result.joinToString(" ")
    }

    private fun preserveCase(original: String, replacement: String): String {
        val letters = original.filter { it.isLetter() }
        return when {
            letters.length > 1 && letters.all { it.isUpperCase() } -> replacement.uppercase()
            // Schreibweise des Wörterbucheintrags bleibt erhalten (z. B. „iPhone“), nur ein großgeschriebener
            // Anfang (Satzanfang/Substantiv) wird übernommen.
            replacement.any { it.isUpperCase() } -> replacement
            original.firstOrNull { it.isLetter() }?.isUpperCase() == true ->
                replacement.first().uppercaseChar() + replacement.substring(1)
            else -> replacement
        }
    }

    private fun extractPunctuation(word: String): Pair<String, String> {
        val start = word.indexOfFirst { it.isLetterOrDigit() }
        if (start < 0) return word to ""
        val end = word.indexOfLast { it.isLetterOrDigit() } + 1
        return word.substring(0, start) to word.substring(end)
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Amerikanischer Soundex (wie `natural::phonetics::soundex`). */
    fun soundex(s: String): String {
        val word = s.lowercase().filter { it in 'a'..'z' }
        if (word.isEmpty()) return ""
        fun code(c: Char) = when (c) {
            'b', 'f', 'p', 'v' -> '1'
            'c', 'g', 'j', 'k', 'q', 's', 'x', 'z' -> '2'
            'd', 't' -> '3'
            'l' -> '4'
            'm', 'n' -> '5'
            'r' -> '6'
            else -> '0'
        }
        val sb = StringBuilder().append(word[0].uppercaseChar())
        var last = code(word[0])
        for (idx in 1 until word.length) {
            val c = word[idx]
            val cd = code(c)
            if (cd != '0' && cd != last) sb.append(cd)
            if (c != 'h' && c != 'w') last = cd
            if (sb.length == 4) break
        }
        while (sb.length < 4) sb.append('0')
        return sb.toString()
    }

    // ---------------------------------------------------------------- Füllwörter & Normalisierung

    private val fillerRegexes: List<Regex> = (UNIVERSAL_FILLERS + GERMAN_FILLERS).map {
        // \b funktioniert in Java nicht zuverlässig mit Umlauten, daher eigene Grenzen.
        Regex("(?iu)(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])[,.]?")
    }

    fun removeFillerWords(text: String): String =
        fillerRegexes.fold(text) { acc, r -> r.replace(acc, "") }

    fun normalize(text: String): String {
        var t = text.replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("\\s+([,.!?;:])"), "$1")
        t = t.replace(Regex("^[,.;:]\\s*"), "")
        return t
    }
}
