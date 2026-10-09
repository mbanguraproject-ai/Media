package com.media.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// ============================================================================
//  THE SOUND STAGE - Depth and Space, for headphones and speakers
//
//  Via's own processing, run on the samples themselves before they reach
//  Android (SoundSink.kt), so it works the same on every phone, every Android
//  version and every format - 16-bit, 24-bit, 32-bit and float. Pure
//  arithmetic: no Android in this file, so it is tested off the device.
//
//  It only runs for an output that can do something with it - headphones,
//  Bluetooth, USB, a wired speaker, a car. On the phone's own speaker the
//  samples pass through untouched (SoundEngine decides).
//
//  DEPTH   Deep bass that is felt, not boom.
//          A band boost under the bass, shaped per output: on headphones
//          +9 dB centred near 40 Hz, reaching down to 20 Hz, with the
//          150-300 Hz mud slightly lower. A Bluetooth speaker cannot play
//          40 Hz, so there the boost sits at 60-120 Hz and the octave below
//          is made audible as its own harmonics, the way the ear hears a
//          fundamental that is not there. A car's cabin already lifts the
//          lowest octave, so it gets less.
//          The boost follows the music: a thin mix gets all of it, a mix
//          that is already heavy gets less. And each kick's attack is lifted
//          for a few tens of milliseconds, so the hit lands as a punch.
//
//  SPACE   The sound in front of you and around you, not inside your head.
//          Headphones: the two channels are played as two speakers in front
//          of you. Each ear hears the near speaker and, later and darker
//          round the head, the far one - through a complementary filter pair,
//          so anything in the centre (a voice) comes out exactly as it went
//          in, with no comb of notches. Then the speakers are put in a room:
//          the first reflections off its walls, each arriving from its own
//          side, a short reverberant tail, and the stereo ambience of the
//          recording sent round the sides. Speakers and cars get a wider
//          image instead: the stereo difference is raised above 180 Hz.
//
//  NEVER CLIPS. Whatever Depth adds is held under full scale by a look-ahead
//  limiter that only ever turns the ADDED bass down, so the music underneath
//  is never pumped. A second, wideband stage catches anything the room adds.
//  The look-ahead costs 3 ms of delay while the stage is working.
//
//  Every change ramps: dragging a slider never clicks. Switching the stage on
//  or off crossfades over 20 ms. With both at zero the samples are not
//  touched at all.
// ============================================================================

/** What the sound comes out of. Depth and Space are shaped for it. */
enum class OutputClass { HEADPHONES, SPEAKER, CAR }

/** One complete instruction for the stage; replaced whole, never edited. */
data class StageParams(
    /** False on the phone's own speaker, or with nothing to do. */
    val on: Boolean = false,
    /** 0..1 */
    val depth: Float = 0f,
    /** 0..1 */
    val space: Float = 0f,
    val output: OutputClass = OutputClass.HEADPHONES
) {
    val wanted: Boolean get() = on && (depth > 0f || space > 0f)
}

class SoundStage {

    /** Set from any thread; read once per buffer on the audio thread. */
    @Volatile var params = StageParams()

    private var fs = 0.0
    private var built: OutputClass? = null

    // ---- Depth -------------------------------------------------------------
    private val bandHp = Biquad()
    private val bandLp = Biquad()
    private var bandNorm = 1.0
    private var depthMaxDb = 9.0
    private val lowLp = Biquad()           // the kick's range, for punch
    private var punchMax = 0.6
    private val harmHp = Biquad()          // the octave a speaker cannot play
    private val harmLp = Biquad()
    private val harmOutHp = Biquad()       // its harmonics, kept in their band
    private val harmOutLp = Biquad()
    private var harmMax = 0.0
    private var bassMs = 0.0               // mean square of the bass, slow
    private var bassCoef = 0.0
    private var envFast = 0.0
    private var envSlow = 0.0
    private var trans = 0.0
    private var fastAtt = 0.0; private var fastRel = 0.0
    private var slowAtt = 0.0; private var slowRel = 0.0
    private var transCoef = 0.0
    private var harmEnv = 0.0
    private var harmAtt = 0.0; private var harmRel = 0.0

    // ---- Space -------------------------------------------------------------
    private val crossL = OnePole()         // 700 Hz: the far ear's share
    private val crossR = OnePole()
    private val histL = Ring(1 shl 14)     // input history for the reflections
    private val histR = Ring(1 shl 14)
    private var tapDelayL = IntArray(0)    // per tap and ear, see buildRoom()
    private var tapGainL = DoubleArray(0)
    private var tapDelayR = IntArray(0)
    private var tapGainR = DoubleArray(0)
    private val roomHpL = OnePole(); private val roomHpR = OnePole()
    private val roomLpL = OnePole(); private val roomLpR = OnePole()
    private val fdn = Array(4) { Ring(1 shl 15) }
    private val fdnLen = IntArray(4)
    private val fdnGain = DoubleArray(4)
    private val fdnDamp = Array(4) { OnePole() }
    private val tailHpL = OnePole(); private val tailHpR = OnePole()
    private val ambHp = Biquad()
    private val ambLp = OnePole()
    private val amb = Ring(1 shl 13)
    private var ambDelay = 0
    private val wideHp = Biquad()

    // ---- Limiter -----------------------------------------------------------
    private var look = 1                   // look-ahead, samples
    private val dS = Ring(1 shl 10); private val dR = Ring(1 shl 10)
    private val dB = Ring(1 shl 10)
    private val minBass = SlidingMin(1 shl 10)
    private val minWide = SlidingMin(1 shl 10)
    private val boxBass = Box(1 shl 10)
    private val boxWide = Box(1 shl 10)
    private var gBass = 1.0
    private var gWide = 1.0
    private var relBass = 0.0
    private var relWide = 0.0

    // ---- Ramps -------------------------------------------------------------
    private val kDepth = Ramp(); private val punch = Ramp(); private val harm = Ramp()
    private val headroom = Ramp(1.0)
    private val cross = Ramp(); private val room = Ramp(); private val tail = Ramp()
    private val ambGain = Ramp(); private val widen = Ramp()
    private val mix = Ramp()               // 0 = the input as it came, 1 = the stage
    private val ramps = arrayOf(kDepth, punch, harm, cross, room, tail, ambGain, widen)
    private var rng = 0x9E3779B9.toInt()

    /** Samples the limiter had to be rescued from; the tests hold it at zero. */
    internal var overs = 0L
        private set

    /** Whether the last buffer was changed at all. False = the input stands. */
    var changed = false
        private set

    fun configure(sampleRate: Int) {
        if (sampleRate.toDouble() == fs) return
        fs = sampleRate.toDouble()
        built = null
        look = (0.003 * fs).roundToInt().coerceIn(1, 1000)
        relBass = 1 - exp(-1 / (0.12 * fs))
        relWide = 1 - exp(-1 / (0.08 * fs))
        bassCoef = exp(-1 / (0.4 * fs))
        fastAtt = exp(-1 / (0.0005 * fs)); fastRel = exp(-1 / (0.04 * fs))
        slowAtt = exp(-1 / (0.04 * fs)); slowRel = exp(-1 / (0.2 * fs))
        transCoef = exp(-1 / (0.006 * fs))
        harmAtt = exp(-1 / (0.001 * fs)); harmRel = exp(-1 / (0.05 * fs))
        crossL.set(fs, 700.0); crossR.set(fs, 700.0)
        roomHpL.set(fs, 150.0); roomHpR.set(fs, 150.0)
        roomLpL.set(fs, 5000.0); roomLpR.set(fs, 5000.0)
        tailHpL.set(fs, 200.0); tailHpR.set(fs, 200.0)
        ambHp.highPass(fs, 150.0, 0.707)
        ambLp.set(fs, 6000.0)
        ambDelay = (0.012 * fs).roundToInt()
        wideHp.highPass(fs, 180.0, 0.707)
        buildRoom()
        buildTail()
        val step = 1.0 / (0.05 * fs)
        ramps.forEach { it.step = step }
        headroom.step = step
        mix.step = 1.0 / (0.02 * fs)
        reset()
    }

    /** Forget all audio (a seek, a new stream). Settings stay. */
    fun reset() {
        clearEffects()
        dS.clear(); dR.clear(); dB.clear()
        minBass.clear(); minWide.clear()
        boxBass.clear(look); boxWide.clear(look)
        gBass = 1.0; gWide = 1.0
        // Mid-ramp state is dropped with the audio: start where the settings are.
        val p = params
        val on = p.wanted
        mix.jump(if (on) 1.0 else 0.0)
        targets(p, jump = true)
    }

    private fun clearEffects() {
        bandHp.reset(); bandLp.reset(); lowLp.reset()
        harmHp.reset(); harmLp.reset(); harmOutHp.reset(); harmOutLp.reset()
        bassMs = 0.0; envFast = 0.0; envSlow = 0.0; trans = 0.0; harmEnv = 0.0
        crossL.reset(); crossR.reset(); histL.clear(); histR.clear()
        roomHpL.reset(); roomHpR.reset(); roomLpL.reset(); roomLpR.reset()
        fdn.forEach { it.clear() }; fdnDamp.forEach { it.reset() }
        tailHpL.reset(); tailHpR.reset()
        ambHp.reset(); ambLp.reset(); amb.clear(); wideHp.reset()
    }

    private fun build(output: OutputClass) {
        if (built == output) return
        built = output
        // The boost band: high-pass x low-pass, normalised so its peak is
        // exactly the boost asked for.
        when (output) {
            OutputClass.HEADPHONES -> { bandHp.highPass(fs, 20.0, 0.55); bandLp.lowPass(fs, 75.0, 0.6); depthMaxDb = 9.0; punchMax = 1.0; harmMax = 0.0 }
            OutputClass.SPEAKER -> { bandHp.highPass(fs, 55.0, 0.6); bandLp.lowPass(fs, 120.0, 0.6); depthMaxDb = 5.0; punchMax = 0.8; harmMax = 0.8 }
            OutputClass.CAR -> { bandHp.highPass(fs, 25.0, 0.55); bandLp.lowPass(fs, 70.0, 0.6); depthMaxDb = 6.0; punchMax = 0.6; harmMax = 0.0 }
        }
        var peak = 0.0
        var f = 10.0
        while (f < 1000.0) {
            peak = max(peak, bandHp.magnitude(fs, f) * bandLp.magnitude(fs, f))
            f *= 1.02
        }
        bandNorm = if (peak > 0) 1 / peak else 1.0
        lowLp.lowPass(fs, 110.0, 0.707)
        harmHp.highPass(fs, 40.0, 0.707); harmLp.lowPass(fs, 110.0, 0.707)
        harmOutHp.highPass(fs, 90.0, 0.707); harmOutLp.lowPass(fs, 320.0, 0.707)
        // Filter state is kept: switching output mid-song only bends the
        // curve, the ramps carry the level, and nothing clicks.
    }

    private fun targets(p: StageParams, jump: Boolean) {
        // A new output reshapes the bass filters, which mid-waveform would
        // click. So it happens in silence: Depth fades out, the filters are
        // rebuilt, Depth fades back in for the new output.
        if (jump || built == null || (kDepth.value == 0.0 && punch.value == 0.0 && harm.value == 0.0)) build(p.output)
        val on = p.on
        val asked = if (on) p.depth.coerceIn(0f, 1f).toDouble() else 0.0
        val d = if (built == p.output) asked else 0.0
        val s = if (on) p.space.coerceIn(0f, 1f).toDouble() else 0.0
        val phones = p.output == OutputClass.HEADPHONES
        fun set(r: Ramp, v: Double) {
            if (jump) r.jump(v) else r.target = v
        }
        set(kDepth, 10.0.pow(depthMaxDb * d / 20) - 1)
        set(punch, punchMax * d)
        set(harm, harmMax * d)
        // A quarter of the boost comes back off the top, so a loud master has
        // room for it without the limiter doing all the work.
        set(headroom, 10.0.pow(-0.25 * depthMaxDb * asked / 20))
        set(cross, if (phones && s > 0) 0.2 + 0.3 * sqrt(s) else 0.0)
        set(room, if (phones) 0.42 * s else 0.0)
        set(tail, when (p.output) { OutputClass.HEADPHONES -> 0.18 * s; OutputClass.SPEAKER -> 0.06 * s; OutputClass.CAR -> 0.0 })
        set(ambGain, if (phones) 0.25 * s else 0.0)
        set(widen, when (p.output) { OutputClass.HEADPHONES -> 0.0; OutputClass.SPEAKER -> 0.9 * s; OutputClass.CAR -> 0.5 * s })
    }

    /**
     * Processes [n] stereo frames in place. Afterwards [changed] says whether
     * anything was done: false means the samples are exactly the input.
     */
    fun process(l: FloatArray, r: FloatArray, n: Int) {
        if (fs == 0.0) { changed = false; return }
        val p = params
        targets(p, jump = false)
        val wanted = p.wanted
        mix.target = if (wanted) 1.0 else if (ramps.all { it.value == 0.0 }) 0.0 else mix.value
        if (!wanted && mix.value == 0.0 && mix.target == 0.0) {
            idle(l, r, n)
            return
        }
        changed = true
        val c0 = C
        for (i in 0 until n) {
            val xl = l[i].toDouble()
            val xr = r[i].toDouble()

            // -------- SPACE --------
            // Every part runs on every sample while the stage works, whatever
            // its level: a delay line that stopped being fed would hand back
            // stale audio the moment its level came up again - a click. Only
            // the gains move.
            val cf = cross.next()
            val rm = room.next()
            val tl = tail.next()
            val am = ambGain.next()
            val wd = widen.next()
            // Near ear: the signal plus the highs of the far speaker's share;
            // far ear: the lows of it. LP + (x - LP) = x, so a centred source
            // sums back to itself exactly.
            val lpL = crossL.lowPass(xl)
            val lpR = crossR.lowPass(xr)
            val norm = 1 / (1 + cf)
            var sl = (xl + cf * (xl - lpL) + cf * lpR) * norm
            var sr = (xr + cf * (xr - lpR) + cf * lpL) * norm

            // The room's first reflections: from the left speaker to each
            // ear, then from the right.
            histL.push(xl); histR.push(xr)
            var el = 0.0
            var er = 0.0
            var k = 0
            while (k < tapGainL.size) {
                el += tapGainL[k] * histL.tap(tapDelayL[k])
                er += tapGainL[k + 1] * histL.tap(tapDelayL[k + 1])
                k += 2
            }
            k = 0
            while (k < tapGainR.size) {
                el += tapGainR[k] * histR.tap(tapDelayR[k])
                er += tapGainR[k + 1] * histR.tap(tapDelayR[k + 1])
                k += 2
            }
            el -= roomHpL.lowPass(el); er -= roomHpR.lowPass(er)
            sl += rm * roomLpL.lowPass(el)
            sr += rm * roomLpR.lowPass(er)

            // The recording's own ambience, sent round the sides.
            amb.push(ambLp.lowPass(ambHp.process((xl - xr) * 0.5)))
            val a = am * amb.tap(ambDelay)
            sl += a; sr -= a

            // Speakers: a wider image above 180 Hz.
            run {
                val m = (sl + sr) * 0.5
                var sd = (sl - sr) * 0.5
                sd += wd * wideHp.process(sd)
                sl = m + sd; sr = m - sd
            }

            // The tail. Its level is set where sound goes IN, so turning it
            // down lets what is already ringing die away naturally.
            run {
                val inL = tl * (xl - tailHpL.lowPass(xl))
                val inR = tl * (xr - tailHpR.lowPass(xr))
                val y0 = fdn[0].tap(fdnLen[0]); val y1 = fdn[1].tap(fdnLen[1])
                val y2 = fdn[2].tap(fdnLen[2]); val y3 = fdn[3].tap(fdnLen[3])
                // Hadamard: every line feeds every other, energy kept.
                val h0 = (y0 + y1 + y2 + y3) * 0.5
                val h1 = (y0 - y1 + y2 - y3) * 0.5
                val h2 = (y0 + y1 - y2 - y3) * 0.5
                val h3 = (y0 - y1 - y2 + y3) * 0.5
                fdn[0].push(fdnDamp[0].lowPass(h0 * fdnGain[0]) + inL)
                fdn[1].push(fdnDamp[1].lowPass(h1 * fdnGain[1]) + inR)
                fdn[2].push(fdnDamp[2].lowPass(h2 * fdnGain[2]) + inL)
                fdn[3].push(fdnDamp[3].lowPass(h3 * fdnGain[3]) + inR)
                sl += (y0 - y2) * 0.5
                sr += (y1 - y3) * 0.5
            }

            // -------- DEPTH --------
            val hr = headroom.next()
            sl *= hr; sr *= hr
            val kd = kDepth.next()
            val pu = punch.next()
            val hm = harm.next()
            val m = (sl + sr) * 0.5
            val low = lowLp.process(m)
            // The music's own bass level: all of the boost below -30 dBFS,
            // half of it from -12 dBFS up.
            bassMs = low * low + (bassMs - low * low) * bassCoef
            val lvl = 10 * log10(bassMs + 1e-12)
            val adapt = when {
                lvl <= -30 -> 1.0
                lvl >= -12 -> 0.5
                else -> 1.0 - 0.5 * (lvl + 30) / 18
            }
            var b = kd * adapt * bandNorm * bandLp.process(bandHp.process(m))
            // Punch: how far the fast envelope is ahead of the slow one.
            val al = abs(low)
            envFast = if (al > envFast) al + (envFast - al) * fastAtt else al + (envFast - al) * fastRel
            envSlow = if (al > envSlow) al + (envSlow - al) * slowAtt else al + (envSlow - al) * slowRel
            val tr = if (envFast > 1e-4) ((envFast - 1.3 * envSlow) / envFast).coerceIn(0.0, 1.0) else 0.0
            trans = tr + (trans - tr) * transCoef
            b += pu * trans * low
            if (harmMax > 0.0) {
                // The octave below what the speaker plays, made into its own
                // 2nd and 3rd harmonics, which it can.
                val hb = harmLp.process(harmHp.process(m))
                val ah = abs(hb)
                harmEnv = if (ah > harmEnv) ah + (harmEnv - ah) * harmAtt else ah + (harmEnv - ah) * harmRel
                val u = if (harmEnv > 1e-6) (hb / harmEnv).coerceIn(-1.0, 1.0) else 0.0
                val h = harmEnv * (0.6 * (2 * u * u - 1) + 0.4 * (4 * u * u * u - 3 * u))
                b += hm * harmOutLp.process(harmOutHp.process(h))
            }

            // -------- LIMIT --------
            val peak = max(abs(sl), abs(sr))
            val wReq = if (peak > c0) c0 / peak else 1.0
            var bReq = 1.0
            if (b != 0.0) {
                val ab = abs(b)
                bReq = min(fits(sl, b, wReq, c0, ab), fits(sr, b, wReq, c0, ab))
            }
            dS.push(sl); dR.push(sr); dB.push(b)
            val gw = settle(boxWide.push(minWide.push(wReq, look)), true)
            val gb = settle(boxBass.push(minBass.push(bReq, look)), false)
            val oL = dS.tap(look - 1)
            val oR = dR.tap(look - 1)
            val ob = dB.tap(look - 1)
            var yl = gw * oL + gb * ob
            var yr = gw * oR + gb * ob
            // Never reached by construction; here for rounding.
            if (abs(yl) > c0 + 1e-9 || abs(yr) > c0 + 1e-9) overs++
            yl = yl.coerceIn(-c0, c0)
            yr = yr.coerceIn(-c0, c0)

            // -------- MIX --------
            val mx = mix.next()
            if (mx < 1.0) {
                yl = xl + (yl - xl) * mx
                yr = xr + (yr - xr) * mx
            }
            l[i] = yl.toFloat()
            r[i] = yr.toFloat()
        }
        if (!wanted && mix.value == 0.0) clearEffects()
    }

    /** How much of the bass boost [b] fits on top of [s] under [c0]. */
    private fun fits(s: Double, b: Double, w: Double, c0: Double, ab: Double): Double =
        if (s * b > 0) ((c0 - w * abs(s)) / ab).coerceIn(0.0, 1.0)
        else (c0 / ab).coerceAtMost(1.0)

    private fun settle(box: Double, wide: Boolean): Double {
        return if (wide) {
            gWide = if (box < gWide) box else gWide + (box - gWide) * relWide
            gWide
        } else {
            gBass = if (box < gBass) box else gBass + (box - gBass) * relBass
            gBass
        }
    }

    /**
     * Nothing to do: the input stands. Its tail still goes into the
     * look-ahead line, so switching on mid-song crossfades from audio that is
     * really there.
     */
    private fun idle(l: FloatArray, r: FloatArray, n: Int) {
        changed = false
        for (i in max(0, n - look) until n) {
            dS.push(l[i].toDouble()); dR.push(r[i].toDouble()); dB.push(0.0)
            boxWide.push(minWide.push(1.0, look)); boxBass.push(minBass.push(1.0, look))
        }
        gBass = 1.0; gWide = 1.0
    }

    /** A random value for TPDF dither, -1..1, triangular. */
    fun dither(): Float {
        var x = rng
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        val a = (x and 0xFFFF) / 65536f
        val b = ((x ushr 16) and 0xFFFF) / 65536f
        rng = x
        return a - b
    }

    // ---- The room -----------------------------------------------------------

    private fun buildRoom() {
        // Image sources for two speakers 2 m away at 30 degrees either side,
        // in a room of about 5 x 6 x 2.8 m, the listener a little off centre
        // as people sit - so the two speakers' reflections arrive at
        // different moments instead of stacking into one comb. Per row: delay
        // after the direct sound (ms), level, level at the left and right
        // ear for the direction it comes from, and the ear-to-ear time (ms,
        // + = the left ear hears it later). Floor and ceiling are halved:
        // they colour more than they place.
        val left = arrayOf(
            doubleArrayOf(2.88, 0.25, 0.89, 0.61, -0.19),
            doubleArrayOf(5.16, 0.20, 0.86, 0.64, -0.15),
            doubleArrayOf(6.65, 0.35, 1.09, 0.41, -0.52),
            doubleArrayOf(10.27, 0.28, 0.83, 0.67, -0.10),
            doubleArrayOf(12.29, 0.25, 0.39, 1.11, 0.57),
            doubleArrayOf(13.19, 0.23, 0.82, 0.68, -0.09),
            doubleArrayOf(13.78, 0.16, 0.97, 0.53, -0.31),
            doubleArrayOf(16.25, 0.15, 0.95, 0.55, -0.27),
            doubleArrayOf(17.81, 0.14, 0.47, 1.03, 0.40),
            doubleArrayOf(19.90, 0.13, 0.50, 1.00, 0.36),
            doubleArrayOf(20.49, 0.12, 0.38, 1.12, 0.60)
        )
        val right = arrayOf(
            doubleArrayOf(3.03, 0.24, 0.65, 0.85, 0.14),
            doubleArrayOf(5.38, 0.19, 0.67, 0.83, 0.11),
            doubleArrayOf(7.28, 0.32, 0.41, 1.09, 0.53),
            doubleArrayOf(10.56, 0.26, 0.69, 0.81, 0.08),
            doubleArrayOf(12.59, 0.23, 1.11, 0.39, -0.57),
            doubleArrayOf(13.51, 0.22, 0.70, 0.80, 0.06),
            doubleArrayOf(14.35, 0.15, 0.53, 0.97, 0.31),
            doubleArrayOf(16.81, 0.13, 0.55, 0.95, 0.28),
            doubleArrayOf(18.15, 0.13, 1.03, 0.47, -0.40),
            doubleArrayOf(20.25, 0.12, 1.00, 0.50, -0.36),
            doubleArrayOf(26.70, 0.09, 0.38, 1.12, 0.61)
        )
        // Flattened per tap: left-ear delay and gain, right-ear delay and gain.
        fun flatten(rows: Array<DoubleArray>): Pair<IntArray, DoubleArray> {
            val d = IntArray(rows.size * 2)
            val g = DoubleArray(rows.size * 2)
            for ((t, row) in rows.withIndex()) {
                val base = row[0] / 1000 * fs
                val itd = row[4] / 1000 * fs
                d[2 * t] = (base + max(0.0, itd)).roundToInt()
                d[2 * t + 1] = (base + max(0.0, -itd)).roundToInt()
                g[2 * t] = row[1] * row[2]
                g[2 * t + 1] = row[1] * row[3]
            }
            return d to g
        }
        flatten(left).let { tapDelayL = it.first; tapGainL = it.second }
        flatten(right).let { tapDelayR = it.first; tapGainR = it.second }
    }

    private fun buildTail() {
        // Four lines of mutually prime length, a small room's 0.4 s decay,
        // highs dying faster than lows.
        val ms = doubleArrayOf(23.3, 29.1, 34.7, 41.3)
        val rt60 = 0.4
        for (i in 0 until 4) {
            var len = (ms[i] / 1000 * fs).roundToInt()
            while (!prime(len)) len++
            fdnLen[i] = len
            fdnGain[i] = 10.0.pow(-3.0 * len / (fs * rt60))
            fdnDamp[i].set(fs, 4500.0)
        }
    }

    private fun prime(n: Int): Boolean {
        if (n < 2) return false
        var d = 2
        while (d * d <= n) { if (n % d == 0) return false; d++ }
        return true
    }

    companion object {
        /** The ceiling: -0.15 dBFS. */
        const val C = 0.983

        /**
         * The steady-state gain Depth gives at each of [hz], in dB, for the
         * response drawn on the Sound screen. Ignores the music-dependent
         * parts; with [headroom] false, also the overall level it takes off
         * the top, so the drawing shows the shape.
         */
        fun depthCurveDb(
            output: OutputClass, depth: Float, hz: DoubleArray,
            headroom: Boolean = true, fs: Double = 48000.0
        ): DoubleArray {
            if (depth <= 0f) return DoubleArray(hz.size)
            val hp = Biquad(); val lp = Biquad()
            val maxDb = when (output) {
                OutputClass.HEADPHONES -> { hp.highPass(fs, 20.0, 0.55); lp.lowPass(fs, 75.0, 0.6); 9.0 }
                OutputClass.SPEAKER -> { hp.highPass(fs, 55.0, 0.6); lp.lowPass(fs, 120.0, 0.6); 5.0 }
                OutputClass.CAR -> { hp.highPass(fs, 25.0, 0.55); lp.lowPass(fs, 70.0, 0.6); 6.0 }
            }
            var peak = 0.0
            var f = 10.0
            while (f < 1000.0) { peak = max(peak, hp.magnitude(fs, f) * lp.magnitude(fs, f)); f *= 1.02 }
            val k = 10.0.pow(maxDb * depth / 20) - 1
            val head = if (headroom) -0.25 * maxDb * depth else 0.0
            return DoubleArray(hz.size) { i ->
                val (hr, hi) = hp.response(fs, hz[i])
                val (lr, li) = lp.response(fs, hz[i])
                val br = (hr * lr - hi * li) / peak
                val bi = (hr * li + hi * lr) / peak
                20 * log10(hypot(1 + k * br, k * bi)) + head
            }
        }

        fun depthCurveDb(output: OutputClass, depth: Float, hz: Double, headroom: Boolean = true): Double =
            depthCurveDb(output, depth, doubleArrayOf(hz), headroom)[0]
    }
}

/** RBJ biquad, transposed direct form II, in double for the low bass. */
internal class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var z1 = 0.0; private var z2 = 0.0

    fun lowPass(fs: Double, f: Double, q: Double) = set(fs, f, q, true)
    fun highPass(fs: Double, f: Double, q: Double) = set(fs, f, q, false)

    private fun set(fs: Double, f: Double, q: Double, low: Boolean) {
        val w = 2 * PI * min(f, fs * 0.45) / fs
        val al = sin(w) / (2 * q)
        val c = cos(w)
        val a0 = 1 + al
        if (low) { b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0 } else { b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0 }
        b2 = b0
        a1 = -2 * c / a0
        a2 = (1 - al) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }

    /** Complex response at [hz]: (real, imaginary). */
    fun response(fs: Double, hz: Double): Pair<Double, Double> {
        val w = 2 * PI * hz / fs
        val c1 = cos(w); val s1 = -sin(w); val c2 = cos(2 * w); val s2 = -sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = b1 * s1 + b2 * s2
        val dr = 1 + a1 * c1 + a2 * c2; val di = a1 * s1 + a2 * s2
        val den = dr * dr + di * di
        return ((nr * dr + ni * di) / den) to ((ni * dr - nr * di) / den)
    }

    fun magnitude(fs: Double, hz: Double): Double = response(fs, hz).let { hypot(it.first, it.second) }
}

/** One-pole low-pass, topology-preserving; the high-pass is x minus it. */
internal class OnePole {
    private var g = 0.0
    private var s = 0.0
    fun set(fs: Double, f: Double) { val t = tan(PI * min(f, fs * 0.45) / fs); g = t / (1 + t) }
    fun lowPass(x: Double): Double { val v = (x - s) * g; val y = v + s; s = y + v; return y }
    fun reset() { s = 0.0 }
}

/** A delay line: [tap] (0) is the sample just pushed. */
internal class Ring(size: Int) {
    private val buf = DoubleArray(size)
    private val mask = size - 1
    private var pos = 0
    fun push(x: Double) { pos = (pos + 1) and mask; buf[pos] = x }
    fun tap(d: Int): Double = buf[(pos - d) and mask]
    fun clear() { buf.fill(0.0) }
}

/** The minimum of the last [window] values pushed, in O(1). */
internal class SlidingMin(capacity: Int) {
    private val vals = DoubleArray(capacity)
    private val idx = LongArray(capacity)
    private val mask = capacity - 1
    private var head = 0L
    private var tail = 0L
    private var n = 0L

    fun push(v: Double, window: Int): Double {
        while (tail > head && vals[((tail - 1) and mask.toLong()).toInt()] >= v) tail--
        val t = (tail and mask.toLong()).toInt()
        vals[t] = v; idx[t] = n; tail++
        while (idx[(head and mask.toLong()).toInt()] <= n - window) head++
        n++
        return vals[(head and mask.toLong()).toInt()]
    }

    fun clear() { head = 0L; tail = 0L; n = 0L }
}

/** The mean of the last [len] values pushed. Starts full of ones. */
internal class Box(capacity: Int) {
    private val buf = DoubleArray(capacity)
    private val mask = capacity - 1
    private var pos = 0
    private var len = 1
    private var sum = 0.0

    fun clear(length: Int) {
        len = length
        buf.fill(1.0)
        sum = length.toDouble()
        pos = 0
    }

    fun push(v: Double): Double {
        pos = (pos + 1) and mask
        val old = buf[(pos - len) and mask]
        buf[pos] = v
        sum += v - old
        return min(1.0, sum / len)
    }
}

/** A value that moves to its target at a fixed rate. */
internal class Ramp(initial: Double = 0.0) {
    var value = initial
        private set
    var target = initial
    var step = 1.0

    fun jump(v: Double) { value = v; target = v }

    fun next(): Double {
        val d = target - value
        if (d != 0.0) value = if (abs(d) <= step) target else value + if (d > 0) step else -step
        return value
    }
}
