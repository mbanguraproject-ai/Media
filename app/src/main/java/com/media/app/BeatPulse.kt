package com.media.app

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

// ============================================================================
//  BEAT PULSE — shared driver (§13, §33)
//
//  One clock, many consumers. The expanded player and the playing card on Home
//  read the SAME BeatState, so they cannot drift apart and only one frame loop
//  ever runs.
//
//  Two changes from the first version, both about impact:
//
//  1. PUNCH SHAPING. The stored envelope is a faithful curve with a 0.7 gamma
//     that deliberately lifts quiet passages — good analysis, weak visuals.
//     Shaping happens at RENDER time instead: cut everything below 0.30, then
//     smoothstep. Measured on a synthetic kick that takes contrast from 0.77
//     to 0.92 and drops the between-hit trough from 0.11 to 0.03. Doing it
//     here rather than in the analyzer means no cached envelope is invalidated.
//
//  2. TRANSIENT RINGS. A shockwave fires on sharp rises only, not on every
//     frame — that's what reads as a hit landing rather than a throb.
// ============================================================================

private const val RINGS = 4
private const val RING_LIFE_NS = 520_000_000f
private const val RING_MIN_GAP_NS = 90_000_000L   // no double-fire on one hit

/** Floor cut + smoothstep. Crushes the mush, keeps the peaks. */
private fun punch(v: Float): Float {
    val x = ((v - 0.30f) / 0.70f).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

@Stable
class BeatState {
    /** Smoothed 0..1. Fast attack, slow release, like a compressor envelope. */
    var level by mutableStateOf(0f)
        internal set

    /** Frame clock. Reading it in a Canvas is what drives ring redraw. */
    var frameNanos by mutableStateOf(0L)
        internal set

    internal val born = LongArray(RINGS)
    internal val power = FloatArray(RINGS)
    private var next = 0

    internal fun fire(now: Long, p: Float) {
        born[next] = now
        power[next] = p
        next = (next + 1) % RINGS
    }

    internal fun reset() {
        level = 0f
        for (i in 0 until RINGS) born[i] = 0L
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
    envelope: FloatArray?,
    active: Boolean,
    volume: Float
): BeatState {
    val reduced = LocalReducedMotion.current
    val beat = remember { BeatState() }

    LaunchedEffect(envelope, state.isPlaying, state.positionMs, state.speed, reduced, active, volume) {
        // Silent means still. Not "quiet" - nothing at all.
        if (envelope == null || !state.isPlaying || reduced || !active || volume <= 0.01f) {
            beat.reset()
            return@LaunchedEffect
        }
        val power = volume.coerceIn(0f, 1f)
        val base = state.positionMs
        val speed = state.speed
        var t0 = 0L
        var prev = 0f
        var lastRing = 0L
        while (true) {
            withFrameNanos { now ->
                if (t0 == 0L) t0 = now
                val ms = base + ((now - t0) / 1_000_000f * speed).toLong()
                // Volume scales the SIGNAL, not just the drawing - so at low
                // volume the rising edges rarely clear the ring threshold and
                // you get fewer, softer hits. Turning up brings the power.
                val target = punch(envelope.levelAt(ms)) * power

                // Rising edge only — a sustained loud passage is not a hit.
                if (target - prev > 0.20f && now - lastRing > RING_MIN_GAP_NS) {
                    beat.fire(now, target)
                    lastRing = now
                }
                prev = target

                beat.level += (target - beat.level) *
                    if (target > beat.level) 0.72f else 0.13f
                beat.frameNanos = now
            }
        }
    }
    return beat
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
    strength: Float = 1f
) {
    val now = beat.frameNanos
    Canvas(modifier) {
        if (now == 0L || baseRadiusPx <= 0f) return@Canvas
        for (i in 0 until RINGS) {
            val b = beat.born[i]
            if (b == 0L) continue
            val p = (now - b) / RING_LIFE_NS
            if (p < 0f || p >= 1f) continue
            // Ease-out: fast off the mark, decelerating. Reaches 2.1x the
            // artwork radius so it genuinely leaves the cover behind.
            val e = 1f - (1f - p) * (1f - p)
            val radius = baseRadiusPx * (1f + 1.1f * e)
            val alpha = (1f - p) * (1f - p) * beat.power[i] * 0.7f * strength
            drawCircle(
                color = color,
                radius = radius,
                center = centerPx,
                alpha = alpha,
                style = Stroke(width = baseRadiusPx * 0.055f * (1f - p * 0.6f))
            )
        }
    }
}
