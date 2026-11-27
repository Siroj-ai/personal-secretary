package com.personalsecretary.app.voice

import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Converts structured executive briefings and financial chat replies into natural,
 * human-sounding spoken English suitable for both offline Kokoro-82M (lexicon-based)
 * and Google Speech Services neural voices.
 */
object SpeechTextFormatter {

    private val ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    )

    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    )

    private val ORDINALS = mapOf(
        1 to "first", 2 to "second", 3 to "third", 4 to "fourth", 5 to "fifth",
        6 to "sixth", 7 to "seventh", 8 to "eighth", 9 to "ninth", 10 to "tenth",
        11 to "eleventh", 12 to "twelfth", 13 to "thirteenth", 14 to "fourteenth",
        15 to "fifteenth", 16 to "sixteenth", 17 to "seventeenth", 18 to "eighteenth",
        19 to "nineteenth", 20 to "twentieth", 21 to "twenty first", 22 to "twenty second",
        23 to "twenty third", 24 to "twenty fourth", 25 to "twenty fifth",
        26 to "twenty sixth", 27 to "twenty seventh", 28 to "twenty eighth",
        29 to "twenty ninth", 30 to "thirtieth", 31 to "thirty first"
    )

    /**
     * Prepares an advisor reply or briefing for natural spoken playback, split into
     * concise sentence chunks so neural TTS starts talking in <0.3s.
     */
    fun prepareSpokenSentences(
        rawText: String,
        today: LocalDate = LocalDate.now(),
        maxChars: Int = 1100
    ): List<String> {
        val spoken = formatForSpeech(rawText, today, maxChars)
        if (spoken.isBlank()) return emptyList()
        return splitIntoSpokenChunks(spoken, maxChunkChars = 210)
    }

    fun formatForSpeech(
        rawText: String,
        today: LocalDate = LocalDate.now(),
        maxChars: Int = 1100
    ): String {
        if (rawText.isBlank()) return ""

        // If the reply has both "Verified Figures" and "Clara's Advice" (or any secretary's Advice),
        // read the headline from Verified Figures + the natural conversational Advice rather than
        // reciting every raw bullet line of a ledger table verbatim.
        var working = rawText.replace("\r\n", "\n").trim()
        val adviceMarker = Regex("(?im)^\\*{0,2}[A-Za-z]+'s Advice\\*{0,2}\\s*$").find(working)
        val verifiedMarker = Regex("(?im)^\\*{0,2}Verified Figures\\*{0,2}\\s*$").find(working)
        if (verifiedMarker != null && adviceMarker != null && adviceMarker.range.first > verifiedMarker.range.last) {
            val verifiedBody = working.substring(verifiedMarker.range.last + 1, adviceMarker.range.first).trim()
            val adviceBody = working.substring(adviceMarker.range.last + 1).trim()
            val headlineLine = verifiedBody.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("•") && !it.startsWith("-") && !it.startsWith("*") }
            working = buildString {
                if (!headlineLine.isNullOrBlank() && !adviceBody.startsWith(headlineLine.take(18), ignoreCase = true)) {
                    append(headlineLine.trimEnd(':')).append(". ")
                }
                append(adviceBody)
            }
        } else {
            working = working.replace(Regex("(?im)^\\*{0,2}(Verified Figures|[A-Za-z]+'s Advice)\\*{0,2}\\s*$"), "")
        }

        // Remove markdown syntax, UI tags, and brackets that sound robotic
        working = working
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace(Regex("(?m)^#+\\s*"), "")
            .replace(Regex("\\[CC]"), "credit card")
            .replace(Regex("\\[(OPEN|UPCOMING|WATCH|ON TRACK|ZERO SPEND|OVER CAP)[^]]*]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(Gemma runtime note:[^)]*\\)"), "")
            .replace("·", ", ")
            .replace("|", ", ")
            .replace("—", ", ")
            .replace("–", " to ")
            .replace("−", " minus ")
            .replace("&", " and ")
            .replace(Regex("(?m)^\\s*[•\\-*]\\s+"), "")
            .replace(Regex("(?m)^\\s*(\\d+)[.)]\\s+"), "")

        // Convert ISO dates: 2026-11-26 -> November twenty sixth (with year if different)
        working = Regex("\\b(20\\d{2})-(0?[1-9]|1[0-2])-(0?[1-9]|[12]\\d|3[01])\\b").replace(working) { m ->
            val year = m.groupValues[1].toInt()
            val month = Month.of(m.groupValues[2].toInt()).getDisplayName(TextStyle.FULL, Locale.US)
            val day = m.groupValues[3].toInt()
            val ordinalDay = ORDINALS[day] ?: integerToWords(day.toLong())
            if (year == today.year) "$month $ordinalDay" else "$month $ordinalDay, ${yearToWords(year)}"
        }

        // Convert YYYY-MM month keys: 2026-11 -> November twenty twenty six
        working = Regex("\\b(20\\d{2})-(0?[1-9]|1[0-2])\\b").replace(working) { m ->
            val year = m.groupValues[1].toInt()
            val month = Month.of(m.groupValues[2].toInt()).getDisplayName(TextStyle.FULL, Locale.US)
            if (year == today.year) month else "$month ${yearToWords(year)}"
        }

        // Convert currency amounts: -Rs. 1,730.00/d or Rs. 1,166.67 or Rs. 2.5 Cr
        working = Regex("([+-])?\\s*(?:Rs\\.?|NPR)\\s*([\\d,]+(?:\\.\\d+)?)\\s*(Cr\\b|/day|/d\\b)?", RegexOption.IGNORE_CASE)
            .replace(working) { m ->
                val sign = m.groupValues[1]
                val numRaw = m.groupValues[2].replace(",", "")
                val suffix = m.groupValues[3].lowercase(Locale.US)
                val spokenNum = decimalToWords(numRaw, dropZeroDecimals = true)
                val unit = when (suffix) {
                    "cr" -> "crore rupees"
                    "/day", "/d" -> "rupees per day"
                    else -> "rupees"
                }
                val prefix = if (sign == "+") "plus " else ""
                "$prefix$spokenNum $unit"
            }

        // Convert percentages: 85% or +4.25% -> eighty five percent
        working = Regex("([+-])?(\\d+(?:\\.\\d+)?)%").replace(working) { m ->
            val sign = when (m.groupValues[1]) {
                "+" -> "up "
                "-" -> "down "
                else -> ""
            }
            "$sign${decimalToWords(m.groupValues[2], dropZeroDecimals = true)} percent"
        }

        // Convert clock times: 10:30 AM -> ten thirty A M, 9:00 PM -> nine P M
        working = Regex("\\b(\\d{1,2}):(\\d{2})\\s*(AM|PM)\\b", RegexOption.IGNORE_CASE).replace(working) { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = if (m.groupValues[3].equals("am", ignoreCase = true)) "A M" else "P M"
            val hourWord = integerToWords(h.toLong())
            val minWord = when {
                min == 0 -> ""
                min < 10 -> " oh ${integerToWords(min.toLong())}"
                else -> " ${integerToWords(min.toLong())}"
            }
            "$hourWord$minWord $ampm"
        }

        // Expand domain abbreviations for crisp pronunciation in lexicon-based Kokoro
        working = working
            .replace(Regex("\\b(\\d+)d\\b")) { "${integerToWords(it.groupValues[1].toLong())} day" }
            .replace(Regex("\\b(\\d+)h\\s*(\\d+)m\\b")) {
                "${integerToWords(it.groupValues[1].toLong())} hours ${integerToWords(it.groupValues[2].toLong())} minutes"
            }
            .replace(Regex("\\b(\\d+)h\\b")) { "${integerToWords(it.groupValues[1].toLong())} hours" }
            .replace(Regex("\\b(\\d+)m\\b")) { "${integerToWords(it.groupValues[1].toLong())} minutes" }
            .replace(Regex("\\bSSF\\b"), "S S F")
            .replace(Regex("\\bIPOs\\b"), "I P Os")
            .replace(Regex("\\bIPO\\b"), "I P O")
            .replace(Regex("\\bNEPSE\\b", RegexOption.IGNORE_CASE), "Nepsey")
            .replace(Regex("\\bEMI\\b"), "E M I")
            .replace(Regex("\\bSpO2\\b", RegexOption.IGNORE_CASE), "blood oxygen")
            .replace(Regex("\\bbpm\\b", RegexOption.IGNORE_CASE), "beats per minute")
            .replace(Regex("\\bkcal\\b", RegexOption.IGNORE_CASE), "calories")
            .replace(Regex("\\bkm\\b", RegexOption.IGNORE_CASE), "kilometers")
            .replace(Regex("\\bmin\\b", RegexOption.IGNORE_CASE), "minutes")
            .replace(Regex("\\bavg\\b", RegexOption.IGNORE_CASE), "average")
            .replace(Regex("\\bvs\\.?\\b", RegexOption.IGNORE_CASE), "versus")
            .replace(Regex("\\bincl\\.?\\b", RegexOption.IGNORE_CASE), "including")
            .replace(Regex("\\bFY\\b"), "fiscal year")

        // Convert remaining standalone numbers (e.g. "8,000 steps", "48", "2.5") into words
        working = Regex("\\b(\\d{1,3}(?:,\\d{3})+)(?:\\.(\\d+))?\\b").replace(working) { m ->
            val raw = m.groupValues[1].replace(",", "") + if (m.groupValues[2].isNotEmpty()) ".${m.groupValues[2]}" else ""
            decimalToWords(raw, dropZeroDecimals = true)
        }
        working = Regex("\\b(\\d+(?:\\.\\d+)?)\\b").replace(working) { m ->
            decimalToWords(m.groupValues[1], dropZeroDecimals = true)
        }

        // Convert line breaks into natural sentence stops & tidy punctuation
        working = working
            .replace(Regex("\\n+"), ". ")
            .replace(Regex("[:;]"), ", ")
            .replace(Regex("[()\\[\\]{}]"), " ")
            .replace(Regex("\\s*,\\s*,+"), ", ")
            .replace(Regex("\\s*\\.\\s*\\.+"), ". ")
            .replace(Regex(",\\s*\\."), ".")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trimStart(',', '.')
            .trim()

        if (working.length > maxChars) {
            val cut = working.lastIndexOf('.', maxChars)
            working = if (cut > maxChars / 2) working.substring(0, cut + 1) else working.take(maxChars).trim()
        }
        return working
    }

    internal fun splitIntoSpokenChunks(text: String, maxChunkChars: Int = 210): List<String> {
        val rawSentences = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.any { ch -> ch.isLetter() } }
        val result = mutableListOf<String>()
        for (sentence in rawSentences) {
            if (sentence.length <= maxChunkChars) {
                result += sentence
            } else {
                // Split long clauses cleanly on commas
                val buf = StringBuilder()
                for (clause in sentence.split(Regex("(?<=,)\\s+"))) {
                    if (buf.isNotEmpty() && buf.length + clause.length + 1 > maxChunkChars) {
                        result += buf.toString().trim()
                        buf.clear()
                    }
                    if (buf.isNotEmpty()) buf.append(' ')
                    buf.append(clause)
                }
                if (buf.isNotEmpty()) result += buf.toString().trim()
            }
        }
        return result
    }

    internal fun decimalToWords(numStr: String, dropZeroDecimals: Boolean = true): String {
        val parts = numStr.split(".")
        val intVal = parts[0].toLongOrNull() ?: return numStr
        val intWords = integerToWords(intVal)
        if (parts.size < 2) return intWords
        val frac = parts[1]
        if (dropZeroDecimals && frac.all { it == '0' }) return intWords
        val trimmedFrac = frac.trimEnd('0').ifEmpty { return intWords }
        val fracWords = trimmedFrac.map { ch ->
            ONES[(ch - '0').coerceIn(0, 9)]
        }.joinToString(" ")
        return "$intWords point $fracWords"
    }

    internal fun yearToWords(year: Int): String {
        if (year in 2000..2009) return "two thousand ${if (year == 2000) "" else ONES[year - 2000]}".trim()
        if (year in 2010..2099) {
            val hi = year / 100
            val lo = year % 100
            return "${integerToWords(hi.toLong())} ${integerToWords(lo.toLong())}"
        }
        return integerToWords(year.toLong())
    }

    internal fun integerToWords(value: Long): String {
        if (value == 0L) return "zero"
        if (value < 0L) return "minus ${integerToWords(-value)}"
        var n = value
        val parts = mutableListOf<String>()
        val scales = listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")
        for ((scale, label) in scales) {
            if (n >= scale) {
                parts += "${underThousandToWords((n / scale).toInt())} $label"
                n %= scale
            }
        }
        if (n > 0) {
            parts += underThousandToWords(n.toInt())
        }
        return parts.joinToString(" ")
    }

    private fun underThousandToWords(n: Int): String {
        val parts = mutableListOf<String>()
        val h = n / 100
        val rem = n % 100
        if (h > 0) parts += "${ONES[h]} hundred"
        when {
            rem == 0 -> Unit
            rem < 20 -> parts += ONES[rem]
            else -> {
                val t = TENS[rem / 10]
                val o = rem % 10
                parts += if (o == 0) t else "$t ${ONES[o]}"
            }
        }
        return parts.joinToString(" ")
    }
}
