package com.media.app

// ============================================================================
//  LRC
//
//  [mm:ss.xx]text, with everything the format collects in the wild:
//    - several stamps on one line:     [00:12.00][01:30.50]Chorus
//    - two or three digit fractions:   [00:12.5] [00:12.50] [00:12.500]
//    - a colon before the fraction:    [00:12:50]
//    - hours on long files:            [1:02:03.40]
//    - word-level "enhanced" stamps:   <00:12.10>Hel<00:12.40>lo  (removed)
//    - [offset:+250] in milliseconds:  positive shows the lyrics earlier
//    - [ar:] [ti:] [al:] [by:] [length:] metadata, ignored
//
//  Lyrics are called synchronised only when they really are: a handful of
//  stamped lines, not one stray [00:00] in front of a plain text. The
//  blueprint is explicit that timing must never be claimed where it is absent.
// ============================================================================

data class ParsedLyrics(
    val lines: List<LyricLine>,
    /** The file's own [offset:] tag, already applied to [lines]. */
    val fileOffsetMs: Long = 0L
) {
    val synced: Boolean get() = lines.isNotEmpty()
}

object Lrc {

    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?::(\d{1,2}))?(?:[.:](\d{1,3}))?]""")
    private val ENHANCED = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val META = Regex("""^\[[a-zA-Z#]+:.*]\s*$""")

    /** Fewer stamped lines than this and the text is treated as plain. */
    private const val MIN_SYNCED_LINES = 3

    fun looksSynced(text: String): Boolean =
        text.lineSequence().count { STAMP.containsMatchIn(it.trimStart().take(16)) } >= MIN_SYNCED_LINES

    fun parse(text: String): ParsedLyrics {
        val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            var rest = line
            val stamps = ArrayList<Long>()
            while (true) {
                val m = STAMP.find(rest) ?: break
                if (m.range.first != 0) break
                stamps += stampMs(m)
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) continue
            val words = ENHANCED.replace(rest, "").replace(Regex("""\s+"""), " ").trim()
            stamps.forEach { out += LyricLine((it - offset).coerceAtLeast(0L), words) }
        }
        if (out.size < MIN_SYNCED_LINES) return ParsedLyrics(emptyList())
        // Stable: two lines on one stamp keep the order they were written in.
        return ParsedLyrics(out.sortedBy { it.timeMs }, offset)
    }

    private fun stampMs(m: MatchResult): Long {
        val a = m.groupValues[1].toLong()
        val b = m.groupValues[2].toLong()
        val c = m.groupValues[3]
        val frac = m.groupValues[4]
        // [mm:ss:xx] is the common colon-fraction form; only a stamp with a
        // third field AND a fraction is [h:mm:ss.xx].
        val (minutes, seconds, fractionText) = when {
            c.isNotEmpty() && frac.isNotEmpty() -> Triple(a * 60 + b, c.toLong(), frac)
            c.isNotEmpty() -> Triple(a, b, c)
            else -> Triple(a, b, frac)
        }
        val fraction = when (fractionText.length) {
            0 -> 0L
            1 -> fractionText.toLong() * 100
            2 -> fractionText.toLong() * 10
            else -> fractionText.take(3).toLong()
        }
        return (minutes * 60 + seconds) * 1000 + fraction
    }

    /**
     * The line playing at [positionMs], or -1 before the first. [offsetMs] is
     * the listener's own adjustment: positive shows lyrics earlier.
     */
    fun activeIndex(lines: List<LyricLine>, positionMs: Long, offsetMs: Long = 0L): Int {
        val t = positionMs + offsetMs
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** Plain text from lyrics that may carry stamps, for display without timing. */
    fun plain(text: String): String =
        text.lineSequence()
            .map { STAMP.replace(it, "").let { l -> ENHANCED.replace(l, "") }.trim() }
            .filterNot { META.matches(it) }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

    /** Serialises lines back to LRC, for the cache. */
    fun format(lines: List<LyricLine>): String = buildString {
        for (l in lines) {
            val total = l.timeMs.coerceAtLeast(0)
            val m = total / 60000
            val s = (total % 60000) / 1000
            val cs = (total % 1000) / 10
            append('[')
            append(m.toString().padStart(2, '0')).append(':')
            append(s.toString().padStart(2, '0')).append('.')
            append(cs.toString().padStart(2, '0')).append(']')
            append(l.text).append('\n')
        }
    }
}
