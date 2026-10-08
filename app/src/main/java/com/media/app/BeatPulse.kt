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
//  5.2 - THE CONE. The idea is a reactive speaker: watch the woofer and you
//  see the music. The cover is driven like one: a spring (stiff, lightly
//  damped) that each kick drum strikes. It punches out within ~35ms, swings
//  back past rest on the rebound and settles in ~300ms, and between kicks it
//  is still - so every movement you see is a kick.
//
//  ONE SYSTEM, ONE JOB PER DRUM. The first 5.2 build gave every drum several
//  effects, each on its own smoothing, so a single kick set off six things
//  slightly out of step and the snare and hats piled more on top. Now:
//
//    KICK   -> MOTION   the cone. Its glow, its shadow, the rings it sends
//                       out and the waveform's playhead all read the SAME
//                       spring, so they move as one body, in phase.
//    SNARE  -> LIGHT    one flash across the cover. A kick landing with it
//                       has priority (it is the same moment; motion wins),
//                       unless the snare is an accent.
//    HATS   -> TEXTURE  a fine shimmer on the cover's surface, each hat a
//                       short decay; it ducks under the kick.
//    ROOM               the backdrop swells slowly with the bass over a bar
//                       or so. It is the ambience, never a hit.
//
//  Tiers add drums, not effects: Enhanced the kick, Premium the snare,
//  Ultra the hats and the cone's true deformation (Phantom.kt).
// ============================================================================

private const val RINGS = 4
private const val RING_LIFE_NS = 520_000_000f

/** A snare within this of a kick is the kick's moment, unless it is an accent. */
private const val DUCK_NS = 70_000_000L
private const val ACCENT = 0.85f
/** How long the snare's flash lasts. */
internal const val FLASH_NS = 420_000_000L
/** How fast one hat's shimmer dies away. */
private const val TEXTURE_TAU_S = 0.09f

// The cone spring: w = 33 rad/s (5.3 Hz), damping ratio 0.2. Simulated: a
// kick peaks at +0.8 after ~33ms, rebounds to -0.24 at ~117ms and settles by
// ~380ms.
private const val CONE_K = 1100f
private const val CONE_D = 13.3f
private const val CONE_STRIKE = 30f
// How much the bass line holds the cone out between kicks: a little, so a
// held bass reads as weight without blurring the kicks.
private const val CONE_HOLD = 0.08f

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

    /** The cone: about 0..1 out on a kick, below 0 on the rebound. */
    var cone by mutableStateOf(0f)
        internal set

    /** The hats' shimmer, 0..1: each hat a short decay, ducked under the kick. */
    var texture by mutableStateOf(0f)
        internal set

    /** Frame clock. Reading it in a Canvas is what drives ring redraw. */
    var frameNanos by mutableStateOf(0L)
        internal set

    /** The cone's push, 0..1: what the glow, the shadow and the playhead follow. */
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

    private var hat = 0f
    internal var coneVelocity = 0f
    /** The last analysis frame whose hits have fired, so a resync never fires one twice. */
    internal var firedFrame = Int.MIN_VALUE

    internal fun kick(now: Long, p: Float) {
        kickNanos = now; kickPower = p
        born[next] = now
        power[next] = p
        next = (next + 1) % RINGS
        coneVelocity += CONE_STRIKE * p
    }

    internal fun snare(now: Long, p: Float) {
        // Same moment as a kick: the kick's. An accent still flashes.
        if (kickNanos != 0L && now - kickNanos in 0..DUCK_NS && p < ACCENT) return
        snareNanos = now; snarePower = p
    }

    internal fun hat(p: Float) {
        if (p > hat) hat = p
    }

    internal fun step(dt: Float, bass: Float, now: Long) {
        val a = CONE_K * (bass * CONE_HOLD - cone) - CONE_D * coneVelocity
        coneVelocity += a * dt
        cone = (cone + coneVelocity * dt).coerceIn(-0.6f, 1.4f)
        hat *= kotlin.math.exp(-dt / TEXTURE_TAU_S)
        // The snare's light, as it fades: what the hats duck under.
        val since = now - snareNanos
        val light = if (snareNanos != 0L && since in 0..FLASH_NS) {
            val f = 1f - since / FLASH_NS.toFloat()
            f * f * snarePower
        } else 0f
        // The hats give way to the cone and to the flash: one thing at a time.
        texture = hat * (1f - push * 0.85f) * (1f - light * 0.8f)
        room += (bass - room) * if (bass > room) 0.05f else 0.02f
    }

    internal fun reset() {
        room = 0f; cone = 0f; texture = 0f
        hat = 0f
        coneVelocity = 0f
        firedFrame = Int.MIN_VALUE
        for (i in 0 until RINGS) born[i] = 0L
        kickNanos = 0L; snareNanos = 0L
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
 * Drives [BeatState] at the display's rate whenever [active] and playback is
 * running.
 *
 * SYNC. Until 5.3 this re-read the position twice a second (PlayerState) and
 * guessed in between by counting frames from the last read. Each read was
 * already a frame or more old when the effect restarted on it, so the guess
 * started behind, drifted, and snapped back twice a second: the artwork and
 * the song looked like two different things. Now [clock] is the player's own
 * position, read on every frame, plus the time the frame takes to reach the
 * screen - so a hit is drawn on the frame you see as you hear it.
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
        val lead = (DISPLAY_LEAD_MS * state.speed).toLong()
        val hz = SoundShape.HZ
        var last = 0L
        while (true) {
            withFrameNanos { frameTime ->
                val dt = if (last == 0L) 0f else ((frameTime - last) / 1e9f).coerceIn(0f, 0.05f)
                last = frameTime
                val ms = now() + lead
                val frame = (ms * hz / 1000L).toInt()

                when {
                    // A jump - a seek, a resume after a while away - fires
                    // nothing it skipped.
                    frame < beat.firedFrame - 2 || frame > beat.firedFrame + hz / 2 ->
                        beat.firedFrame = frame
                    // Every hit playback has crossed since the last frame, on
                    // this frame. Volume scales them: quieter means softer.
                    frame > beat.firedFrame -> {
                        fire(shape.lowHits, beat.firedFrame, frame) { p -> beat.kick(frameTime, p * power) }
                        fire(shape.midHits, beat.firedFrame, frame) { p -> beat.snare(frameTime, p * power) }
                        fire(shape.highHits, beat.firedFrame, frame) { p -> beat.hat(p * power) }
                        beat.firedFrame = frame
                    }
                }
                beat.step(dt, punch(shape.low.levelAt(ms)) * power, frameTime)
                beat.frameNanos = frameTime
            }
        }
    }
    return beat
}

/** Calls [action] for each hit in [hits] after frame [from], up to and including [to]. */
private inline fun fire(hits: Hits, from: Int, to: Int, action: (power: Float) -> Unit) {
    var i = hits.firstAtOrAfter(from + 1)
    while (i < hits.size && hits.frames[i] <= to) {
        action(hits.power[i])
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
