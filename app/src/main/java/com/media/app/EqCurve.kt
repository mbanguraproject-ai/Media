package com.media.app

import kotlin.math.ln
import kotlin.math.sqrt

// ============================================================================
//  THE EQ AS A CURVE
//
//  Ten sliders are ten control points, not ten boxes. The engine used to give
//  each slider one wide band with a flat gain, so a boost at 62Hz stopped dead
//  at the band edge and the response was a staircase. Now the sliders (plus
//  the bass shelf) define a smooth curve, and the engine renders that curve
//  at 1/3-octave resolution - 31 bands - or, on the classic effect, samples it
//  at whatever centre frequencies the phone's own equaliser has.
//
//  The interpolation is monotone cubic (Fritsch-Carlson) in log frequency:
//  it passes exactly through every slider value, it never overshoots between
//  two of them (a plain cubic spline would invent bumps the listener never
//  set), and it is flat beyond the outermost sliders.
//
//  Pure Kotlin, no Android, so it is tested on its own.
// ============================================================================

object EqCurve {

    /** ISO 1/3-octave centres, 20Hz to 20kHz: the resolution the engine renders at. */
    val THIRD_OCTAVE = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
        200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
        2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f,
        20000f
    )

    private fun log2(x: Float): Float = (ln(x.toDouble()) / ln(2.0)).toFloat()

    /**
     * Gain in dB at [hz] for control points [gains] at [centres] (ascending).
     */
    fun at(hz: Float, centres: FloatArray, gains: FloatArray): Float {
        val n = minOf(centres.size, gains.size)
        if (n == 0) return 0f
        if (n == 1) return gains[0]
        val x = log2(hz.coerceAtLeast(1f))
        val xs = FloatArray(n) { log2(centres[it]) }
        if (x <= xs[0]) return gains[0]
        if (x >= xs[n - 1]) return gains[n - 1]

        // Secant slopes, then tangents that keep every segment monotone.
        val d = FloatArray(n - 1) { (gains[it + 1] - gains[it]) / (xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }

        var k = 0
        while (k < n - 2 && x > xs[k + 1]) k++
        val h = xs[k + 1] - xs[k]
        val t = (x - xs[k]) / h
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * gains[k] +
            (t3 - 2 * t2 + t) * h * m[k] +
            (-2 * t3 + 3 * t2) * gains[k + 1] +
            (t3 - t2) * h * m[k + 1]
    }

    /** Upper band edge for a 1/3-octave centre: a sixth of an octave above it. */
    fun upperEdge(centre: Float): Float = (centre * 1.122462f).coerceAtMost(20_000f)
}
