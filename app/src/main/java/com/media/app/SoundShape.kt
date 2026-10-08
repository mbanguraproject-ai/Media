package com.media.app

import kotlin.math.max
import kotlin.math.pow

// ============================================================================
//  SOUND SHAPE - what Reactive artwork sees in a track
//
//  Until 5.1 the artwork saw one number, the bass energy, twenty times a
//  second, and every tier drew that same number a little differently - which
//  is why the tiers looked alike. Now the analysis hears three parts of the
//  kit and knows the moment each one is struck:
//
//    LOW    kick drum and bass       below ~150 Hz
//    MID    snare, claps, voices     ~250 Hz - 2.5 kHz
//    HIGH   hi-hats, cymbals         above ~5 kHz
//
//  each measured fifty times a second (20ms), so a hit lands within a frame
//  of the sound. The hits are found once per track, when its shape is
//  loaded, by picking peaks of the rise in each band against a moving
//  threshold - so a loud chorus does not fire on every frame and a quiet
//  verse still has its hits.
//
//  Everything here is plain arithmetic on arrays, checked off the device.
// ============================================================================

/** The moments a band is struck: frame indices at [SoundShape.HZ], and how hard. */
class Hits(val frames: IntArray, val power: FloatArray) {
    val size: Int get() = frames.size

    /** Index of the first hit at or after [frame], or [size] if none. */
    fun firstAtOrAfter(frame: Int): Int {
        var lo = 0
        var hi = frames.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (frames[mid] < frame) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** These hits without the ones within [within] frames of a hit in [other], unless above [keepAbove]. */
    fun maskedBy(other: Hits, within: Int, keepAbove: Float): Hits {
        if (size == 0 || other.size == 0) return this
        val f = ArrayList<Int>(size); val p = ArrayList<Float>(size)
        for (i in 0 until size) {
            val j = other.firstAtOrAfter(frames[i] - within)
            val near = j < other.size && other.frames[j] <= frames[i] + within
            if (!near || power[i] > keepAbove) { f.add(frames[i]); p.add(power[i]) }
        }
        return Hits(IntArray(f.size) { f[it] }, FloatArray(p.size) { p[it] })
    }

    companion object {
        val NONE = Hits(IntArray(0), FloatArray(0))
    }
}

class SoundShape(val low: FloatArray, val mid: FloatArray, val high: FloatArray) {

    /** The waveform the scrubber draws: the bass curve it has always drawn. */
    val wave: FloatArray get() = low

    val lowHits: Hits = Onsets.detect(low, minGapFrames = 6)      // 120ms: no kick rolls faster
    // A kick's beater click reaches the snare band too. A mid hit on the same
    // frame as a kick is the kick's, unless it is strong enough to be a snare
    // struck with it.
    val midHits: Hits = Onsets.detect(mid, minGapFrames = 5).maskedBy(lowHits, within = 1, keepAbove = 0.8f)
    val highHits: Hits = Onsets.detect(high, minGapFrames = 3)    // 60ms: hats can be quick

    /** Interleaved low/mid/high per frame, for storage. */
    fun interleaved(): FloatArray {
        val n = low.size
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            out[i * 3] = low[i]
            out[i * 3 + 1] = mid.getOrElse(i) { 0f }
            out[i * 3 + 2] = high.getOrElse(i) { 0f }
        }
        return out
    }

    companion object {
        const val HZ = 50

        fun fromInterleaved(data: FloatArray): SoundShape? {
            val n = data.size / 3
            if (n == 0) return null
            val l = FloatArray(n)
            val m = FloatArray(n)
            val h = FloatArray(n)
            for (i in 0 until n) {
                l[i] = data[i * 3]; m[i] = data[i * 3 + 1]; h[i] = data[i * 3 + 2]
            }
            return SoundShape(l, m, h)
        }
    }
}

/** Value of a band at [positionMs], interpolated between frames for 60fps. */
fun FloatArray.levelAt(positionMs: Long): Float {
    if (isEmpty()) return 0f
    val exact = positionMs / 1000f * SoundShape.HZ
    val i = exact.toInt()
    if (i < 0) return this[0]
    if (i >= lastIndex) return this[lastIndex]
    val t = exact - i
    return this[i] + (this[i + 1] - this[i]) * t
}

object Onsets {
    /**
     * Hits in one band's energy curve [e] (0..1 per frame).
     *
     * Novelty is the rise into a frame (the larger of the one- and two-frame
     * rise, so a softer attack spread over 40ms still counts). A frame is a
     * hit when its novelty is a local peak, clears a threshold that follows
     * the music (1.6x the average novelty over the surrounding ~0.4s, plus a
     * floor), and is at least [minGapFrames] after the previous hit; of two
     * candidates too close together, the stronger wins; and it must be at
     * least [localRatio] of the strongest hit within [localWindow] frames
     * (0.6s) either side. Power is the rise
     * against the track's typical hit, weighted by how loud the frame is.
     */
    fun detect(
        e: FloatArray,
        minGapFrames: Int,
        window: Int = 10,
        ratio: Float = 1.6f,
        floor: Float = 0.035f,
        localWindow: Int = 30,
        localRatio: Float = 0.4f
    ): Hits {
        val n = e.size
        if (n < 3) return Hits.NONE
        val nov = FloatArray(n)
        for (i in 1 until n) {
            val d1 = e[i] - e[i - 1]
            val d2 = if (i >= 2) (e[i] - e[i - 2]) * 0.7f else 0f
            nov[i] = max(0f, max(d1, d2))
        }
        // Prefix sums for the moving average.
        val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + nov[i]

        val pickedF = ArrayList<Int>()
        val pickedN = ArrayList<Float>()
        for (i in 1 until n) {
            val v = nov[i]
            if (v <= floor) continue
            // Local peak over +-2 frames (ties go to the earlier frame).
            var peak = true
            for (k in maxOf(1, i - 2)..minOf(n - 1, i + 2)) {
                if (k == i) continue
                if (nov[k] > v || (nov[k] == v && k < i)) { peak = false; break }
            }
            if (!peak) continue
            val a = maxOf(0, i - window)
            val b = minOf(n, i + window / 2 + 1)
            val mean = ((pre[b] - pre[a]) / (b - a)).toFloat()
            if (v < mean * ratio + floor) continue
            val last = pickedF.lastIndex
            if (last >= 0 && i - pickedF[last] < minGapFrames) {
                if (v > pickedN[last]) { pickedF[last] = i; pickedN[last] = v }
                continue
            }
            pickedF.add(i); pickedN.add(v)
        }
        if (pickedF.isEmpty()) return Hits.NONE
        // Keep a hit only if it is at least [localRatio] of the strongest
        // hit near it. What another drum leaks into this band is a fraction
        // of this band's own hits around it, and goes; a quiet verse is
        // judged against its own neighbours, not the chorus, and stays.
        val keep = BooleanArray(pickedF.size)
        var lo = 0
        var hi = 0
        for (j in pickedF.indices) {
            while (pickedF[lo] < pickedF[j] - localWindow) lo++
            while (hi + 1 < pickedF.size && pickedF[hi + 1] <= pickedF[j] + localWindow) hi++
            var strongest = 0f
            for (k in lo..hi) if (pickedN[k] > strongest) strongest = pickedN[k]
            keep[j] = pickedN[j] >= strongest * localRatio
        }
        val keptF = ArrayList<Int>(); val keptN = ArrayList<Float>()
        for (j in pickedF.indices) if (keep[j]) { keptF.add(pickedF[j]); keptN.add(pickedN[j]) }
        pickedF.clear(); pickedF.addAll(keptF)
        pickedN.clear(); pickedN.addAll(keptN)
        val sorted = pickedN.toFloatArray().also { it.sort() }
        val typical = sorted[(sorted.size * 0.8f).toInt().coerceIn(0, sorted.lastIndex)].coerceAtLeast(1e-4f)
        val frames = IntArray(pickedF.size) { pickedF[it] }
        val power = FloatArray(pickedF.size) {
            val rise = (pickedN[it] / typical).coerceIn(0f, 1f)
            val loud = e[pickedF[it]].coerceIn(0f, 1f)
            ((0.35f + 0.65f * rise) * (0.55f + 0.45f * loud)).coerceIn(0f, 1f)
        }
        return Hits(frames, power)
    }
}

/**
 * Splits mono samples into the three bands and measures each one's RMS per
 * window. Every stage is a one-pole filter; a high-pass is the sample minus
 * its own one-pole low-pass, which is exact, so stages cascade cleanly:
 *   LOW   three low-passes at 110 Hz (18 dB/octave): a kick's 50-100 Hz, and
 *         a snare's ~200 Hz body down by ~19 dB
 *   MID   two high-passes at 250 Hz, then two low-passes at 2.5 kHz
 *   HIGH  two high-passes at 5 kHz
 * (Subtracting one low-pass from another, which the first version did, does
 * not cancel: the two outputs are out of phase, and a kick leaked through
 * into the snare band at nearly half strength.)
 */
class BandMeter(sampleRate: Int, private val hz: Int = SoundShape.HZ) {
    private var aLow = 0f; private var aMidHp = 0f; private var aMidLp = 0f; private var aHigh = 0f
    private var window = 1
    private var l1 = 0f; private var l2 = 0f; private var l3 = 0f
    private var p1 = 0f; private var p2 = 0f; private var q1 = 0f; private var q2 = 0f
    private var r1 = 0f; private var r2 = 0f
    private var sl = 0.0; private var sm = 0.0; private var sh = 0.0
    private var count = 0
    val low = FloatBuf(); val mid = FloatBuf(); val high = FloatBuf()

    init { configure(sampleRate) }

    fun configure(sampleRate: Int) {
        aLow = alpha(110f, sampleRate)
        aMidHp = alpha(250f, sampleRate)
        aMidLp = alpha(2500f, sampleRate)
        aHigh = alpha(5000f, sampleRate)
        window = (sampleRate / hz).coerceAtLeast(1)
    }

    val frames: Int get() = low.size

    fun push(x: Float) {
        // LOW
        l1 += aLow * (x - l1); l2 += aLow * (l1 - l2); l3 += aLow * (l2 - l3)
        val lo = l3
        // MID: high-pass twice, then low-pass twice
        p1 += aMidHp * (x - p1); val hp1 = x - p1
        p2 += aMidHp * (hp1 - p2); val hp2 = hp1 - p2
        q1 += aMidLp * (hp2 - q1); q2 += aMidLp * (q1 - q2)
        val md = q2
        // HIGH: high-pass twice
        r1 += aHigh * (x - r1); val hh1 = x - r1
        r2 += aHigh * (hh1 - r2); val hi = hh1 - r2
        sl += (lo * lo).toDouble(); sm += (md * md).toDouble(); sh += (hi * hi).toDouble()
        if (++count >= window) {
            low.add(kotlin.math.sqrt(sl / count).toFloat())
            mid.add(kotlin.math.sqrt(sm / count).toFloat())
            high.add(kotlin.math.sqrt(sh / count).toFloat())
            sl = 0.0; sm = 0.0; sh = 0.0; count = 0
        }
    }

    /** The finished shape, each band scaled 0..1 on its own. */
    fun shape(): SoundShape? {
        if (low.size == 0) return null
        return SoundShape(normalise(low.toArray()), normalise(mid.toArray()), normalise(high.toArray()))
    }

    private fun alpha(cutoff: Float, sampleRate: Int): Float {
        val dt = 1f / sampleRate
        val rc = 1f / (2f * Math.PI.toFloat() * cutoff)
        return dt / (rc + dt)
    }

    companion object {
        /**
         * To 0..1 against the 95th percentile, so one transient does not
         * flatten the track; the 0.7 gamma keeps quiet passages visible.
         */
        fun normalise(arr: FloatArray): FloatArray {
            if (arr.isEmpty()) return arr
            val sorted = arr.copyOf().also { it.sort() }
            val p95 = sorted[(sorted.size * 0.95f).toInt().coerceIn(0, sorted.lastIndex)]
            val scale = if (p95 > 1e-6f) 1f / p95 else 1f
            for (i in arr.indices) arr[i] = (arr[i] * scale).coerceIn(0f, 1f).pow(0.7f)
            return arr
        }
    }
}

/** A growable float array without boxing. */
class FloatBuf(initial: Int = 4096) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
