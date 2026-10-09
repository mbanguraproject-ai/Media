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
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

// ============================================================================
//  BEAT PULSE - shared driver (§13, §33)
//
//  One clock, many consumers. The expanded player and the playing card on Home
//  read the SAME BeatState, so they cannot drift apart and only one frame loop
//  ever runs.
//
//  THE COVER ANSWERS THE BEAT. What it answers is decided once per track
//  (BeatGrid.kt): the kicks and snares where the beat puts them, the
//  strongest of each moment, never two within about half a beat.
//
//    KICK   -> MOTION   the cone: out in 30ms, then a clean fall that is over
//                       before the next beat (a third of a beat, 70-160ms).
//                       Its glow, its shadow and the rings it sends out all
//                       read this one value, so they move as one body.
//    SNARE  -> LIGHT    one flash across the cover, only in a moment no kick
//                       has taken.
//    HATS   -> TEXTURE  a fine shimmer that follows how busy the cymbals
//                       are - a level, not a hit, so it never reacts on its
//                       own; it gives way to the cone and the flash.
//    ROOM               the backdrop swells slowly with the bass over a bar
//                       or so. It is the ambience, never a hit.
//
//  5.2-5.5 drove the cone with a spring that every hit struck. Lightly
//  damped, it swung two or three times after each kick and wandered with the
//  bass line between them, so the cover moved when nothing was hit; and two
//  hits close together struck it mid-swing, so the next beat landed on a
//  rebound and looked late. Now each kick is a fixed shape in time: a new
//  one always starts from a full attack, whatever the last one was doing,
//  so no beat is ever missed, and between hits the cover is still.
//
//  Tiers add drums, not effects: Enhanced the kick, Premium the snare,
//  Ultra the hats and the cone's true deformation (Phantom.kt).
// ============================================================================

private const val RINGS = 4
private const val RING_LIFE_NS = 520_000_000f

/** How long the snare's flash lasts. */
internal const val FLASH_NS = 420_000_000L

/** Floor cut + smoothstep. Crushes the mush, keeps the peaks. */
private fun punch(v: Float): Float {
    val x = ((v - 0.30f) / 0.70f).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

@Stable
class BeatState {
    /**
     * The room: the bass, smoothed slowly (a swell over a bar or so, not a
     * hit). The backdrop, the light field and the flow read this.
     */
    var room by mutableStateOf(0f)
        internal set

    /** The cone: 0 at rest, up to about 1 at a kick's peak. */
    var cone by mutableStateOf(0f)
        internal set

    /** The hats' shimmer, 0..1: how busy the cymbals are, under the cone and the flash. */
    var texture by mutableStateOf(0f)
        internal set

    /** Frame clock. Reading it in a Canvas is what drives ring redraw. */
    var frameNanos by mutableStateOf(0L)
        internal set

    /** The cone's push, 0..1: what the glow, the shadow and the rings follow. */
    val push: Float get() = cone.coerceIn(0f, 1f)

    internal val born = LongArray(RINGS)
    internal val power = FloatArray(RINGS)
    private var next = 0

    /** The latest kick (the cone's ripple) and snare (the flash). 0 = none yet. */
    var kickNanos = 0L
        private set
    var kickPower = 0f
        private set
    var snareNanos = 0L
        private set
    var snarePower = 0f
        private set

    // The last two kicks: a new one starts from a full attack while the one
    // before it finishes its fall underneath.
    private var prevNanos = 0L
    private var prevPower = 0f

    /** The fall of one kick, from the track's beat (Pulse.decayFor). */
    internal var decayS = 0.12f

    /** Where in the track's moments the next one to fire is. */
    internal var nextMoment = 0
    /** The media time of the last frame, to tell playback from a jump. */
    internal var lastMs = Float.NaN

    /** A kick at [at] (display-clock nanos, exact to the moment, not the frame). */
    internal fun kick(at: Long, p: Float) {
        prevNanos = kickNanos; prevPower = kickPower
        kickNanos = at; kickPower = p
        born[next] = at
        power[next] = p
        next = (next + 1) % RINGS
    }

    internal fun snare(at: Long, p: Float) {
        snareNanos = at; snarePower = p
    }

    internal fun step(now: Long, bass: Float, cymbals: Float) {
        fun pulse(at: Long, p: Float) = if (at == 0L) 0f else p * Pulse.at((now - at) / 1e9f, decayS)
        cone = maxOf(pulse(kickNanos, kickPower), pulse(prevNanos, prevPower))
        // The snare's light, as it fades: what the hats give way to.
        val since = now - snareNanos
        val light = if (snareNanos != 0L && since in 0..FLASH_NS) {
            val f = 1f - since / FLASH_NS.toFloat()
            f * f * snarePower
        } else 0f
        val target = cymbals * 0.45f * (1f - push * 0.85f) * (1f - light * 0.8f)
        texture += (target - texture) * if (target > texture) 0.25f else 0.06f
        room += (bass - room) * if (bass > room) 0.05f else 0.02f
    }

    internal fun reset() {
        room = 0f; cone = 0f; texture = 0f
        nextMoment = 0
        lastMs = Float.NaN
        for (i in 0 until RINGS) born[i] = 0L
        kickNanos = 0L; snareNanos = 0L; prevNanos = 0L
    }
}

/**
 * The playing ExoPlayer, for Reactive artwork's clock. Set by PlaybackService
 * (same process); read on the main thread only, where the player lives.
 *
 * Its position is what you are hearing: ExoPlayer derives it from the audio
 * output's own timestamps, so the output's latency is already in it.
 */
object PlayerClock {
    @Volatile internal var player: androidx.media3.common.Player? = null

    fun positionMs(): Long? {
        val p = player ?: return null
        return if (p.playbackState == androidx.media3.common.Player.STATE_IDLE) null else p.currentPosition
    }
}

/**
 * How far ahead of the audible position to draw: a frame drawn now reaches
 * the screen about two vsyncs later.
 */
private const val DISPLAY_LEAD_MS = 30f

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

/**
 * Drives [BeatState] at the display's rate whenever [active] and playback is
 * running.
 *
 * SYNC. [clock] is the player's own position, read on every frame - what you
 * are hearing, the output's latency already in it - plus the time the frame
 * takes to reach the screen, less whatever the Sound chain holds the audio
 * back by (SoundStatus.latencyMs). A moment fires on the frame playback
 * crosses it, timed to the moment itself, not the frame: its pulse is
 * already as far along as the sound is.
 */
@Composable
fun rememberBeatPulse(
    state: PlayerState,
    shape: SoundShape?,
    active: Boolean,
    volume: Float,
    clock: () -> Long
): BeatState {
    val reduced = LocalReducedMotion.current
    val beat = remember { BeatState() }
    val now by rememberUpdatedState(clock)

    LaunchedEffect(shape, state.isPlaying, state.speed, reduced, active, volume) {
        // Silent means still. Not "quiet" - nothing at all.
        if (shape == null || !state.isPlaying || reduced || !active || volume <= 0.01f) {
            beat.reset()
            return@LaunchedEffect
        }
        val power = volume.coerceIn(0f, 1f)
        val speed = state.speed.coerceAtLeast(0.1f)
        val moments = shape.moments
        beat.decayS = Pulse.decayFor(moments.periodFrames / SoundShape.HZ / speed)
        while (true) {
            withFrameNanos { frameTime ->
                val held = SoundEngine.status.value.latencyMs
                val ms = now() + (DISPLAY_LEAD_MS - held) * speed
                val last = beat.lastMs
                if (last.isNaN() || ms < last - 100f || ms > last + 500f) {
                    // A start, a seek, a resume after a while away: fire
                    // nothing that was skipped.
                    beat.nextMoment = moments.firstAtOrAfter(ms)
                } else {
                    var i = beat.nextMoment
                    while (i < moments.size && moments.timeMs(i) <= ms) {
                        // When the moment was, on the display clock.
                        val ago = ((ms - moments.timeMs(i)) / speed * 1_000_000f).toLong()
                        val p = moments.power[i] * power
                        if (moments.kick[i]) beat.kick(frameTime - ago, p) else beat.snare(frameTime - ago, p)
                        i++
                    }
                    beat.nextMoment = i
                }
                beat.lastMs = ms
                val pos = ms.toLong()
                beat.step(frameTime, punch(shape.low.levelAt(pos)) * power, shape.high.levelAt(pos) * power)
                beat.frameNanos = frameTime
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
    // The frame clock is read inside the draw, never here: read in
    // composition it recomposed this every frame for as long as music
    // played. In its own layer, so the redraw stays in this canvas.
    Canvas(modifier.graphicsLayer()) {
        val now = beat.frameNanos
        if (now == 0L || baseRadiusPx <= 0f) return@Canvas
        for (i in 0 until RINGS) {
            val b = beat.born[i]
            if (b == 0L) continue
            val p = (now - b) / RING_LIFE_NS
            if (p < 0f || p >= 1f) continue
            // Ease-out: fast off the mark, decelerating. Reaches 2.1x the
            // artwork radius so it genuinely leaves the cover behind - the
            // air the cone pushed.
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
