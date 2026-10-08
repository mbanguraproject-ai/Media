package com.media.app

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

// ============================================================================
//  BEAT PULSE - shared driver (§13, §33)
//
//  One clock, many consumers. The expanded player and the playing card on Home
//  read the SAME BeatState, so they cannot drift apart and only one frame loop
//  ever runs.
//
//  5.2 - THE CONE. The idea is a reactive speaker: watch the woofer and you
//  see the music. The cover is driven like one now: a spring (stiff, lightly
//  damped) that each kick drum strikes. It punches out within ~35ms, swings
//  back past rest on the rebound and settles in ~300ms, so a kick reads as a
//  thump with a physical after-ring instead of a swell. Between hits the bass
//  holds it out a little, as a bass line moves a cone.
//
//  And it hears the kit, not one number (SoundShape.kt): the moment of every
//  kick, snare and hi-hat, each fired on the exact frame playback crosses it,
//  plus each band's level. Which of those a tier draws is the tier's call
//  (PlayerSurface.kt): Enhanced the kick, Premium the whole kit, Ultra the
//  cover deforming like a cone (Phantom.kt).
// ============================================================================

private const val RINGS = 6
private const val RING_LIFE_NS = 520_000_000f
private const val SNARE_RING_LIFE_NS = 340_000_000f
internal const val SPARK_LIFE_NS = 300_000_000L
private const val SPARKS = 8
internal const val RING_KICK = 0
internal const val RING_SNARE = 1

// The cone spring: w = 33 rad/s (5.3 Hz), damping ratio 0.2. Simulated: a
// kick peaks at +0.8 after ~33ms, rebounds to -0.24 at ~117ms and settles by
// ~380ms.
private const val CONE_K = 1100f
private const val CONE_D = 13.3f
private const val CONE_STRIKE = 30f
private const val CONE_HOLD = 0.25f

/** Floor cut + smoothstep. Crushes the mush, keeps the peaks. */
private fun punch(v: Float): Float {
    val x = ((v - 0.30f) / 0.70f).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

@Stable
class BeatState {
    /** The bass, smoothed 0..1: fast attack, slow release. Backdrop, edge light, flow. */
    var level by mutableStateOf(0f)
        internal set

    /** Snare/voice and hi-hat bands, smoothed 0..1. */
    var mid by mutableStateOf(0f)
        internal set
    var high by mutableStateOf(0f)
        internal set

    /** The cone: about 0..1 out on a kick, below 0 on the rebound. */
    var cone by mutableStateOf(0f)
        internal set

    /** Frame clock. Reading it in a Canvas is what drives ring redraw. */
    var frameNanos by mutableStateOf(0L)
        internal set

    internal val born = LongArray(RINGS)
    internal val power = FloatArray(RINGS)
    internal val kind = IntArray(RINGS)
    private var next = 0

    /** The latest kick and snare: the cone's ripple and the light flash. 0 = none yet. */
    var kickNanos = 0L
        private set
    var kickPower = 0f
        private set
    var snareNanos = 0L
        private set
    var snarePower = 0f
        private set

    /** Hi-hat glints: when, how hard, and a seed that places each one. */
    internal val sparkBorn = LongArray(SPARKS)
    internal val sparkPower = FloatArray(SPARKS)
    internal val sparkSeed = IntArray(SPARKS)
    private var nextSpark = 0

    internal var coneVelocity = 0f
    /** The last analysis frame whose hits have fired, so a resync never fires one twice. */
    internal var firedFrame = Int.MIN_VALUE

    internal fun ring(now: Long, p: Float, k: Int) {
        born[next] = now
        power[next] = p
        kind[next] = k
        next = (next + 1) % RINGS
    }

    internal fun kick(now: Long, p: Float) {
        kickNanos = now; kickPower = p
        ring(now, p, RING_KICK)
        coneVelocity += CONE_STRIKE * p
    }

    internal fun snare(now: Long, p: Float) {
        snareNanos = now; snarePower = p
        ring(now, p, RING_SNARE)
    }

    internal fun spark(now: Long, p: Float, seed: Int) {
        sparkBorn[nextSpark] = now
        sparkPower[nextSpark] = p
        sparkSeed[nextSpark] = seed
        nextSpark = (nextSpark + 1) % SPARKS
    }

    internal fun step(dt: Float, bass: Float) {
        val a = CONE_K * (bass * CONE_HOLD - cone) - CONE_D * coneVelocity
        coneVelocity += a * dt
        cone = (cone + coneVelocity * dt).coerceIn(-0.6f, 1.4f)
    }

    internal fun reset() {
        level = 0f; mid = 0f; high = 0f; cone = 0f
        coneVelocity = 0f
        firedFrame = Int.MIN_VALUE
        for (i in 0 until RINGS) born[i] = 0L
        for (i in 0 until SPARKS) sparkBorn[i] = 0L
        kickNanos = 0L; snareNanos = 0L
    }
}

/**
 * Drives [BeatState] at 60fps whenever [active] and playback is running.
 *
 * [state].positionMs only ticks at 2Hz, so each frame extrapolates from the
 * last known position via the frame clock; re-keying on positionMs resyncs to
 * truth twice a second so drift never accumulates.
 */
/**
 * Music-stream volume as 0..1, polled at ~3Hz.
 *
 * This is the power control: at zero the artwork is completely still, and
 * turning it up brings both the movement and the number of ring hits with it.
 * Polling rather than a receiver because VOLUME_CHANGED_ACTION is undocumented
 * and unreliable across skins; three reads a second costs nothing.
 */
@Composable
fun rememberMusicVolume(): Float {
    val context = LocalContext.current
    var vol by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        while (true) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
            delay(300)
        }
    }
    return vol
}

@Composable
fun rememberBeatPulse(
    state: PlayerState,
    shape: SoundShape?,
    active: Boolean,
    volume: Float
): BeatState {
    val reduced = LocalReducedMotion.current
    val beat = remember { BeatState() }

    LaunchedEffect(shape, state.isPlaying, state.positionMs, state.speed, reduced, active, volume) {
        // Silent means still. Not "quiet" - nothing at all.
        if (shape == null || !state.isPlaying || reduced || !active || volume <= 0.01f) {
            beat.reset()
            return@LaunchedEffect
        }
        val power = volume.coerceIn(0f, 1f)
        val base = state.positionMs
        val speed = state.speed
        val hz = SoundShape.HZ
        // Resynced twice a second: carry on from the last frame fired, unless
        // this is a jump (a seek, a new track), which fires nothing it skips.
        val baseFrame = (base * hz / 1000L).toInt()
        if (beat.firedFrame == Int.MIN_VALUE || kotlin.math.abs(baseFrame - beat.firedFrame) > hz / 2) {
            beat.firedFrame = baseFrame
        }
        var t0 = 0L
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (t0 == 0L) { t0 = now; last = now }
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                val ms = base + ((now - t0) / 1_000_000f * speed).toLong()
                val frame = (ms * hz / 1000L).toInt()

                // Every hit playback has crossed since the last frame, on
                // this frame. Volume scales them: quieter means softer.
                if (frame > beat.firedFrame) {
                    fire(shape.lowHits, beat.firedFrame, frame) { _, p -> beat.kick(now, p * power) }
                    fire(shape.midHits, beat.firedFrame, frame) { _, p -> beat.snare(now, p * power) }
                    fire(shape.highHits, beat.firedFrame, frame) { f, p -> beat.spark(now, p * power, f) }
                    beat.firedFrame = frame
                }

                val bass = punch(shape.low.levelAt(ms)) * power
                beat.level += (bass - beat.level) * if (bass > beat.level) 0.72f else 0.13f
                val m = punch(shape.mid.levelAt(ms)) * power
                beat.mid += (m - beat.mid) * if (m > beat.mid) 0.8f else 0.2f
                val h = shape.high.levelAt(ms) * power
                beat.high += (h - beat.high) * if (h > beat.high) 0.85f else 0.3f
                beat.step(dt, bass)
                beat.frameNanos = now
            }
        }
    }
    return beat
}

/** Calls [action] for each hit in [hits] after frame [from], up to and including [to]. */
private inline fun fire(hits: Hits, from: Int, to: Int, action: (frame: Int, power: Float) -> Unit) {
    var i = hits.firstAtOrAfter(from + 1)
    while (i < hits.size && hits.frames[i] <= to) {
        action(hits.frames[i], hits.power[i])
        i++
    }
}

/**
 * Expanding shockwave rings. Drawn behind artwork so they read as energy
 * leaving the source rather than an outline stuck on top.
 */
/**
 * Expanding shockwave rings.
 *
 * [centerPx] and [baseRadiusPx] are passed EXPLICITLY rather than derived from
 * the Canvas. The previous version sized its own box to ~1.5x the screen width
 * and offset it negative; Compose clamped that to the available width, so the
 * Canvas centre no longer sat on the artwork and every ring appeared to come
 * from the right. Draw into a full-size layer and place the centre by hand.
 */
@Composable
fun BeatRings(
    beat: BeatState,
    color: Color,
    modifier: Modifier,
    centerPx: Offset,
    baseRadiusPx: Float,
    strength: Float = 1f,
    // Premium and Ultra: the snare's own rings, thinner, brighter, quicker.
    snare: Boolean = false
) {
    // The frame clock is read inside the draw, never here: read in
    // composition it recomposed this every frame for as long as music
    // played. In its own layer, so the redraw stays in this canvas.
    Canvas(modifier.graphicsLayer()) {
        val now = beat.frameNanos
        if (now == 0L || baseRadiusPx <= 0f) return@Canvas
        for (i in 0 until RINGS) {
            val b = beat.born[i]
            if (b == 0L) continue
            val isSnare = beat.kind[i] == RING_SNARE
            if (isSnare && !snare) continue
            val life = if (isSnare) SNARE_RING_LIFE_NS else RING_LIFE_NS
            val p = (now - b) / life
            if (p < 0f || p >= 1f) continue
            // Ease-out: fast off the mark, decelerating. A kick's ring
            // reaches 2.1x the artwork radius, so it genuinely leaves the
            // cover behind; a snare's is a sharp crack that stays close.
            val e = 1f - (1f - p) * (1f - p)
            val radius = baseRadiusPx * (if (isSnare) 1.02f + 0.5f * e else 1f + 1.1f * e)
            val alpha = (1f - p) * (1f - p) * beat.power[i] * strength * (if (isSnare) 0.85f else 0.7f)
            drawCircle(
                color = if (isSnare) Color.White else color,
                radius = radius,
                center = centerPx,
                alpha = alpha,
                style = Stroke(
                    width = baseRadiusPx * (if (isSnare) 0.018f else 0.055f) * (1f - p * 0.6f)
                )
            )
        }
    }
}

/**
 * Hi-hat glints on the cover (Premium and Ultra): a small four-point star of
 * light for each hit, gone in 300ms. Where it lands comes from the hit's own
 * frame, so a song glints in the same places every time it plays.
 */
fun DrawScope.drawSparks(beat: BeatState, strength: Float) {
    val now = beat.frameNanos
    if (now == 0L || strength <= 0f) return
    val m = size.minDimension
    for (i in 0 until SPARKS) {
        val b = beat.sparkBorn[i]
        if (b == 0L) continue
        val t = (now - b).toFloat() / SPARK_LIFE_NS
        if (t < 0f || t >= 1f) continue
        val seed = beat.sparkSeed[i]
        val c = Offset(
            size.width * (0.14f + 0.72f * hash01(seed * 73 + 11)),
            size.height * (0.12f + 0.62f * hash01(seed * 151 + 7))
        )
        val p = beat.sparkPower[i]
        val a = ((1f - t) * (1f - t) * p * strength).coerceIn(0f, 1f)
        val r = m * (0.026f + 0.02f * p) * (0.75f + 0.5f * t)
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f * a), Color.Transparent), c, r),
            radius = r, center = c
        )
        val arm = r * 2.3f
        val w = (r * 0.14f).coerceAtLeast(1f)
        for ((dx, dy) in arrayOf(arm to 0f, 0f to arm)) {
            val s0 = Offset(c.x - dx, c.y - dy)
            val s1 = Offset(c.x + dx, c.y + dy)
            drawLine(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = a), Color.Transparent),
                    start = s0, end = s1
                ),
                s0, s1, strokeWidth = w, cap = StrokeCap.Round
            )
        }
    }
}

/** A fixed pseudo-random 0..1 from [n]. */
private fun hash01(n: Int): Float {
    var x = n * 0x45d9f3b
    x = (x xor (x ushr 16)) * 0x45d9f3b
    x = x xor (x ushr 16)
    return (x and 0xFFFF) / 65535f
}
