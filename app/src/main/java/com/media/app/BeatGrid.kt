package com.media.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ============================================================================
//  BEAT GRID - what Reactive artwork reacts to, and when
//
//  5.2 reacted to every hit the analysis found in each band. On real music
//  that was 110-170 reactions a minute, a third to two thirds of them off
//  the beat, and a light snare-band hit just before a heavy kick moved the
//  cover twice within 100ms - after which it was a beat behind. Measured on
//  four tracks (hip-hop, electronic, pop, ragtime), 24 to 96 times per
//  track. The cover read as reacting to every sound.
//
//  Now each track is listened to as a drummer would, once, when its shape
//  is loaded:
//
//    TEMPO   the onset strength of the three bands, autocorrelated in
//            8-second windows that are each normalised on their own and
//            averaged, with a gentle preference for 120 BPM - the method
//            librosa uses, and on the same tracks the same tempo it finds.
//    BEATS   Ellis's dynamic-programming beat tracker: the beats that best
//            sit on strong onsets while keeping that tempo.
//    MOMENTS the hits the cover answers. A hit counts where music puts
//            one: on a beat, on the half-beat, or - a strong kick only - on
//            a quarter, which is where the syncopated kicks of afrobeats,
//            dancehall and reggaeton fall; a strong kick counts anywhere.
//            The kicks are chosen first, strongest first, and nothing may
//            react within about half a beat of one; then snares, only where
//            no kick is near. So a light hit can never take the place of the
//            heavy kick next to it, and the cover is always ready for the
//            next beat. A kick is motion; a snare with no kick near it is
//            light.
//
//  A track with no steady beat (spoken word, free time) falls back to its
//  strongest hits, never closer than a quarter of a second.
//
//  Plain arithmetic, tested off the device against the same tracks.
// ============================================================================

/** What the cover answers: in time order, each a kick (motion) or a snare (light). */
class Moments(
    val frames: IntArray,
    val power: FloatArray,
    val kick: BooleanArray,
    /** The beat, in frames at [SoundShape.HZ]; 0 when the track has none. */
    val periodFrames: Float
) {
    val size: Int get() = frames.size

    /** Media time of moment [i] in ms: the middle of its analysis frame. */
    fun timeMs(i: Int): Float = (frames[i] + 0.5f) * 1000f / SoundShape.HZ

    /** Index of the first moment at or after [ms]. */
    fun firstAtOrAfter(ms: Float): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timeMs(mid) < ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val NONE = Moments(IntArray(0), FloatArray(0), BooleanArray(0), 0f)
    }
}

object BeatGrid {

    /** The moments for a track's three band curves, given each band's detected hits. */
    fun moments(low: FloatArray, mid: FloatArray, high: FloatArray, kicks: Hits, snares: Hits): Moments {
        if (low.size < SoundShape.HZ * 4) return fallback(kicks, 0f)
        val onset = onsetStrength(low, mid, high)
        val period = tempo(onset)
        val beats = track(onset, period)
        if (beats.size < 8) return fallback(kicks, period)

        val tol = 4
        // (score, frame, power, kick)
        val cand = ArrayList<Candidate>()
        fun place(f: Int): Slot? {
            var i = upperBound(beats, f) - 1
            if (i < 0 || i >= beats.size - 1) return null
            val span = (beats[i + 1] - beats[i]).toFloat()
            val ph = (f - beats[i]) / span
            var best: Slot? = null
            for (s in SLOTS) {
                val d = abs(ph - s.phase) * span
                if (d <= min(tol.toFloat(), 0.13f * span) && (best == null || d < best.dist)) best = s.at(d)
            }
            return best
        }
        for (k in 0 until kicks.size) {
            val s = place(kicks.frames[k])
            val p = kicks.power[k]
            when {
                s != null && p >= s.minPower -> cand.add(Candidate(p * s.weight, kicks.frames[k], p, true))
                // Off the grid, a strong kick is still a kick: the grid can
                // be the wrong one (a 3-3-2 kick pattern reads as a faster
                // tempo), or the kick syncopated past it.
                p >= 0.6f -> cand.add(Candidate(0.6f * p, kicks.frames[k], p, true))
            }
        }
        for (k in 0 until snares.size) {
            val s = place(snares.frames[k]) ?: continue
            val p = snares.power[k]
            // Light belongs to the beat and the half-beat only.
            if (s.phase == 0.25f || s.phase == 0.75f) continue
            if (p >= max(s.minPower, 0.35f)) cand.add(Candidate(0.5f * p * s.weight, snares.frames[k], p, false))
        }
        // A grid that explains few of the hits is not this track's beat.
        val explained = cand.size.toFloat() / max(1, kicks.size + snares.size)
        if (explained < 0.3f) return fallback(kicks, period)
        return strongestFirst(cand, 0.45f * period, period)
    }

    // ---- Selection ----------------------------------------------------------

    private class Candidate(val score: Float, val frame: Int, val power: Float, val kick: Boolean)

    private class Slot(val phase: Float, val weight: Float, val minPower: Float, val dist: Float = 0f) {
        fun at(d: Float) = Slot(phase, weight, minPower, d)
    }

    // Where in the beat a hit may land, how much it counts there, and how
    // strong it must be. The quarters are for strong kicks only.
    private val SLOTS = arrayOf(
        Slot(0f, 1f, 0f), Slot(0.5f, 0.8f, 0.45f),
        Slot(0.25f, 0.6f, 0.7f), Slot(0.75f, 0.6f, 0.7f),
        Slot(1f, 1f, 0f)
    )

    /**
     * Kicks first, the strongest first, then snares where no kick is near:
     * nothing may react within [gap] frames of a moment already taken. A
     * snare-band hit's strength is only against its own band, so a light one
     * scores high; ranked against the kicks it took the heavy kick's moment.
     */
    private fun strongestFirst(cand: List<Candidate>, gap: Float, period: Float): Moments {
        val taken = ArrayList<Candidate>()
        for (kick in booleanArrayOf(true, false)) {
            for (c in cand.filter { it.kick == kick }.sortedByDescending { it.score }) {
                var free = true
                for (t in taken) if (abs(c.frame - t.frame) < gap) { free = false; break }
                if (free) taken.add(c)
            }
        }
        taken.sortBy { it.frame }
        return Moments(
            IntArray(taken.size) { taken[it].frame },
            FloatArray(taken.size) { taken[it].power },
            BooleanArray(taken.size) { taken[it].kick },
            period
        )
    }

    /** No steady beat: the strongest kicks, a quarter of a second apart at least. */
    private fun fallback(kicks: Hits, period: Float): Moments {
        val cand = ArrayList<Candidate>()
        for (k in 0 until kicks.size) if (kicks.power[k] >= 0.5f) cand.add(Candidate(kicks.power[k], kicks.frames[k], kicks.power[k], true))
        return strongestFirst(cand, max(0.45f * period, 12f), 0f)
    }

    // ---- Onset strength -------------------------------------------------------

    /** The three bands' rises, each scaled by its own 95th percentile; kick first. */
    fun onsetStrength(low: FloatArray, mid: FloatArray, high: FloatArray): FloatArray {
        val n = low.size
        val a = novelty(low); val b = novelty(mid); val c = novelty(high)
        val sa = 1f / max(percentile(a, 0.95f), 1e-6f)
        val sb = 0.6f / max(percentile(b, 0.95f), 1e-6f)
        val sc = 0.25f / max(percentile(c, 0.95f), 1e-6f)
        return FloatArray(n) { a[it] * sa + b.getOrElse(it) { 0f } * sb + c.getOrElse(it) { 0f } * sc }
    }

    private fun novelty(e: FloatArray): FloatArray {
        val n = e.size
        val out = FloatArray(n)
        for (i in 1 until n) {
            val d1 = e[i] - e[i - 1]
            val d2 = if (i >= 2) (e[i] - e[i - 2]) * 0.7f else 0f
            out[i] = max(0f, max(d1, d2))
        }
        return out
    }

    private fun percentile(a: FloatArray, q: Float): Float {
        if (a.isEmpty()) return 0f
        val s = a.copyOf().also { it.sort() }
        // numpy's linear interpolation
        val pos = q * (s.size - 1)
        val i = pos.toInt()
        val f = pos - i
        return if (i + 1 < s.size) s[i] + (s[i + 1] - s[i]) * f else s[i]
    }

    // ---- Tempo ----------------------------------------------------------------

    /**
     * The beat period in frames (fractional). Autocorrelation in Hann windows
     * of 8 s every half second, each normalised by its own energy, averaged;
     * weighted by a log-normal preference around 120 BPM, one octave wide.
     */
    fun tempo(onset: FloatArray, loBpm: Float = 60f, hiBpm: Float = 190f): Float {
        val hz = SoundShape.HZ
        val n = onset.size
        val lmin = (hz * 60f / hiBpm).toInt()
        val lmax = kotlin.math.ceil(hz * 60f / loBpm).toInt()
        val lags = IntArray(lmax + 2 - lmin) { lmin + it }
        val acc = DoubleArray(lags.size)
        val win = 400
        val stride = 25
        val hann = DoubleArray(win) { 0.5 - 0.5 * cos(2 * PI * it / (win - 1)) }
        var count = 0
        val seg = DoubleArray(win)
        var a = 0
        while (true) {
            val len = min(win, n - a)
            if (len <= 0) break
            for (j in 0 until len) seg[j] = onset[a + j] * hann[j]
            var e0 = 0.0
            for (j in 0 until len) e0 += seg[j] * seg[j]
            if (e0 > 1e-12) {
                for ((i, l) in lags.withIndex()) {
                    if (l >= len) continue
                    var s = 0.0
                    for (j in 0 until len - l) s += seg[j] * seg[j + l]
                    acc[i] += s / e0
                }
                count++
            }
            if (n < win || a + stride > n - win) break
            a += stride
        }
        if (count > 0) for (i in acc.indices) acc[i] /= count
        var k = 0
        var best = Double.NEGATIVE_INFINITY
        for (i in 0 until acc.size - 1) {
            val bpm = hz * 60.0 / lags[i]
            val lg = ln(bpm / 120.0) / ln(2.0)
            val v = acc[i] * exp(-0.5 * lg * lg)
            if (v > best) { best = v; k = i }
        }
        var off = 0.0
        if (k > 0 && k < acc.size - 1) {
            val den = acc[k - 1] - 2 * acc[k] + acc[k + 1]
            if (den != 0.0) off = (0.5 * (acc[k - 1] - acc[k + 1]) / den).coerceIn(-0.5, 0.5)
        }
        return (lags[k] + off).toFloat()
    }

    // ---- Beats ----------------------------------------------------------------

    /**
     * Ellis's dynamic-programming beat tracker, as librosa runs it: the
     * onset strength is smoothed over a 32nd of a beat, every frame takes the
     * best previous beat between half and twice a period back, paying for
     * any departure from the tempo; the last strong beat is traced back, and
     * weak beats at either end are dropped.
     */
    fun track(onset: FloatArray, periodF: Float, tightness: Double = 100.0): IntArray {
        val n = onset.size
        val period = max(2, periodF.roundToInt())
        if (n < 2 * period) return IntArray(0)
        var mean = 0.0
        for (v in onset) mean += v
        mean /= n
        var sd = 0.0
        for (v in onset) sd += (v - mean) * (v - mean)
        sd = sqrt(sd / (n - 1))
        val norm = DoubleArray(n) { onset[it] / (sd + 1e-9) }
        val g = DoubleArray(2 * period + 1) { val k = it - period; exp(-0.5 * (k * 32.0 / period) * (k * 32.0 / period)) }
        val local = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (k in -period..period) {
                val j = i + k
                if (j in 0 until n) s += norm[j] * g[k + period]
            }
            local[i] = s
        }
        var localMax = Double.NEGATIVE_INFINITY
        for (v in local) localMax = max(localMax, v)

        val lo = Math.rint(period / 2.0).toInt()
        val offsets = IntArray(2 * period - lo + 1) { -2 * period + it }   // -2p .. -lo
        val tx = DoubleArray(offsets.size) { val r = ln(-offsets[it].toDouble() / period); -tightness * r * r }
        val cum = DoubleArray(n)
        val back = IntArray(n)
        var first = true
        for (i in 0 until n) {
            var best = Double.NEGATIVE_INFINITY
            var bl = 0
            for (w in offsets.indices) {
                val j = i + offsets[w]
                val v = tx[w] + if (j >= 0) cum[j] else 0.0
                if (v > best) { best = v; bl = w }
            }
            cum[i] = local[i] + best
            if (first && local[i] < 0.01 * localMax) {
                back[i] = -1
            } else {
                back[i] = i + offsets[bl]
                first = false
            }
        }
        // The last beat: the latest local maximum of the score above half
        // the median of them.
        val maxima = ArrayList<Double>()
        for (i in 1 until n - 1) if (cum[i] > cum[i - 1] && cum[i] >= cum[i + 1]) maxima.add(cum[i])
        val med = if (maxima.isEmpty()) 0.0 else maxima.sorted().let { s ->
            if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
        var last = -1
        for (i in 0 until n) {
            val isMax = i in 1 until n - 1 && cum[i] > cum[i - 1] && cum[i] >= cum[i + 1]
            if ((if (isMax) cum[i] else 0.0) * 2 > med) last = i
        }
        if (last < 0) { last = 0; for (i in 1 until n) if (cum[i] > cum[last]) last = i }
        val beats = ArrayList<Int>()
        var t = last
        while (t >= 0) { beats.add(t); t = back[t] }
        beats.reverse()
        if (beats.size <= 2) return beats.toIntArray()
        // Weak beats at either end (an intro's silence, a fade) go.
        val hann5 = doubleArrayOf(0.0, 0.5, 1.0, 0.5, 0.0)
        val sm = DoubleArray(beats.size) { i ->
            var s = 0.0
            for (k in -2..2) { val j = i + k; if (j in beats.indices) s += local[beats[j]] * hann5[k + 2] }
            s
        }
        var sq = 0.0
        for (v in sm) sq += v * v
        val thr = 0.5 * sqrt(sq / sm.size)
        var k0 = 0
        while (k0 < beats.size && sm[k0] <= thr) k0++
        var k1 = beats.size
        while (k1 > k0 && sm[k1 - 1] <= thr) k1--
        return beats.subList(k0, k1).toIntArray()
    }

    private fun upperBound(a: IntArray, v: Int): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (a[m] <= v) lo = m + 1 else hi = m
        }
        return lo
    }
}

/**
 * The cover's answer to one kick: up in 30ms (smoothstep), then an
 * exponential fall of [decayS]. Nothing after it - no rebound, no ringing:
 * between hits the cover is still, and every movement is a hit.
 */
object Pulse {
    const val ATTACK_S = 0.03f

    fun at(ageS: Float, decayS: Float): Float = when {
        ageS < 0f -> 0f
        ageS < ATTACK_S -> { val x = ageS / ATTACK_S; x * x * (3f - 2f * x) }
        else -> exp(-(ageS - ATTACK_S) / decayS)
    }

    /** The fall for a beat of [periodS]: done before the next beat, quick enough for an off-beat. */
    fun decayFor(periodS: Float): Float =
        if (periodS <= 0f) 0.12f else (0.3f * periodS).coerceIn(0.07f, 0.16f)
}
