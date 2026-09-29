package com.media.app

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ============================================================================
//  MATCHING
//
//  Every online answer - a cover, a set of lyrics, a corrected title - is a
//  guess until it has been compared with what the file already says. This is
//  that comparison, as numbers, so "confidence" is data rather than a hope
//  (blueprint §15: "treat metadata confidence as data, not as an
//  assumption").
//
//  Pure Kotlin, no Android: it is the part most worth testing.
// ============================================================================

object Matching {

    /** Auto-apply threshold: artwork and lyrics attach on their own above this. */
    const val CONFIDENT = 0.80f

    /** Shown as a suggestion the listener has to accept. */
    const val PLAUSIBLE = 0.60f

    private val BRACKETS = Regex("""[(\[{][^)\]}]*[)\]}]""")
    private val FEAT = Regex("""\s+(feat\.?|ft\.?|featuring|with)\s+.*$""", RegexOption.IGNORE_CASE)
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val MARKS = Regex("""\p{Mn}+""")

    /**
     * Lower case, accents folded, featured artists and bracketed asides
     * ("(Remastered 2011)", "[Official Video]") removed, punctuation gone.
     * "Beyoncé - Halo (Live)" and "beyonce halo" compare as equal.
     */
    fun normalize(s: String?): String {
        if (s.isNullOrBlank()) return ""
        var t = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "")
        t = t.lowercase().replace("&", " and ")
        val stripped = BRACKETS.replace(t, " ")
        // Never normalise a title away entirely: "(Untitled)" is a title.
        if (stripped.isNotBlank()) t = stripped
        t = FEAT.replace(t, "")
        return NON_WORD.replace(t, " ").trim().replace(Regex("""\s+"""), " ")
    }

    /** 0..1: the better of word overlap and edit distance on normalised text. */
    fun similarity(a: String?, b: String?): Float {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        if (x == y) return 1f
        val tx = x.split(' ').toSet()
        val ty = y.split(' ').toSet()
        val jaccard = tx.intersect(ty).size.toFloat() / tx.union(ty).size
        // One containing the other entirely - "halo" in "halo radio edit" -
        // is closer than word overlap alone says.
        val contained = if (tx.containsAll(ty) || ty.containsAll(tx))
            min(tx.size, ty.size).toFloat() / max(tx.size, ty.size) * 0.5f + 0.45f else 0f
        val edit = 1f - levenshtein(x, y).toFloat() / max(x.length, y.length)
        return maxOf(jaccard, edit, contained).coerceIn(0f, 1f)
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

    /** 1 within 2 s, falling to 0 at 12 s. Unknown on either side is neutral. */
    fun durationScore(aMs: Long?, bMs: Long?): Float? {
        if (aMs == null || bMs == null || aMs <= 0 || bMs <= 0) return null
        val d = abs(aMs - bMs)
        return when {
            d <= 2000 -> 1f
            d >= 12000 -> 0f
            else -> 1f - (d - 2000) / 10000f
        }
    }

    /** What a file says it is. */
    data class Query(
        val title: String?,
        val artist: String?,
        val album: String? = null,
        val durationMs: Long? = null
    )

    /** What a service offered. */
    data class Candidate(
        val title: String?,
        val artist: String?,
        val album: String? = null,
        val durationMs: Long? = null
    )

    /**
     * Weighted agreement. Title and artist carry the decision; album and
     * length confirm it. A missing album or length is left out of the sum
     * rather than counted as a mismatch, and a length that is wildly off
     * caps the score below [CONFIDENT] however well the text matches - a
     * live version is not the studio one.
     */
    fun score(q: Query, c: Candidate): Float {
        var total = 0f
        var weight = 0f
        fun add(w: Float, v: Float?) { if (v != null) { total += w * v; weight += w } }
        add(0.45f, if (q.title.isUsable()) similarity(q.title, c.title) else null)
        add(0.35f, if (q.artist.isUsable()) similarity(q.artist, c.artist) else null)
        add(0.10f, if (q.album.isUsable() && !c.album.isNullOrBlank()) similarity(q.album, c.album) else null)
        val d = durationScore(q.durationMs, c.durationMs)
        add(0.10f, d)
        if (weight < 0.45f) return 0f   // not enough known to judge at all
        var s = total / weight
        if (d != null && d == 0f) s = min(s, CONFIDENT - 0.05f)
        return s.coerceIn(0f, 1f)
    }

    /** Placeholder values MediaStore and taggers leave behind are not information. */
    fun String?.isUsable(): Boolean {
        if (this.isNullOrBlank()) return false
        val n = normalize(this)
        return n.isNotEmpty() && n !in PLACEHOLDERS
    }

    private val PLACEHOLDERS = setOf(
        "unknown", "unknown artist", "unknown album", "untitled", "track", "various artists",
        "various", "va", "audio", "none", "null"
    )

    /** Lucene syntax MusicBrainz' search would otherwise read as operators. */
    fun luceneEscape(s: String): String {
        val special = "+-&|!(){}[]^\"~*?:\\/"
        return buildString { for (ch in s) { if (ch in special) append('\\'); append(ch) } }
    }
}

// ============================================================================
//  ARTWORK QUALITY
//
//  "Bad artwork" in a real library is four different things, and only three
//  of them can be told from the pixels:
//    MISSING      no picture at all
//    LOW_RES      a 100px thumbnail stretched to fill Now Playing
//    PLACEHOLDER  one flat colour, or a near-blank grey square
//  The fourth - the wrong album entirely, or a download site's logo - needs a
//  person, which is why every cover can also be replaced by hand.
// ============================================================================

enum class ArtIssue { NONE, MISSING, LOW_RES, PLACEHOLDER }

object ArtworkQuality {
    /** Below this on the short side, Now Playing visibly upscales it. */
    const val MIN_SIDE = 300

    /** Standard deviation of luminance (0..255) below which a cover is flat. */
    private const val FLAT_STDDEV = 6.0

    /**
     * [pixels] are ARGB from a small downsample of the cover. [width] and
     * [height] are the ORIGINAL image's, since resolution is the question.
     */
    fun assess(width: Int, height: Int, pixels: IntArray?): ArtIssue {
        if (width <= 0 || height <= 0) return ArtIssue.MISSING
        if (min(width, height) < MIN_SIDE) return ArtIssue.LOW_RES
        if (pixels != null && pixels.size >= 16 && luminanceStdDev(pixels) < FLAT_STDDEV) {
            return ArtIssue.PLACEHOLDER
        }
        return ArtIssue.NONE
    }

    fun luminanceStdDev(pixels: IntArray): Double {
        var sum = 0.0
        var sq = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
            sum += y
            sq += y * y
        }
        val n = pixels.size.toDouble()
        val mean = sum / n
        return kotlin.math.sqrt((sq / n - mean * mean).coerceAtLeast(0.0))
    }
}
