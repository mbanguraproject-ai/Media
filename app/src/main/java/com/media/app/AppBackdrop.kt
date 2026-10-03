package com.media.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

// ============================================================================
//  THE ROOM TAKES THE SONG'S COLOUR
//
//  Now Playing has always sat on its cover, blurred into coloured light. This
//  carries that light to the rest of the app. While a song plays, Home,
//  Library, Playlists, Search, Settings, Sound, the album and artist pages
//  and the bottom bar sit on the same cover, drawn the way Now Playing draws
//  it (Backdrop.kt): decoded at 20px and stretched full-screen, which blurs
//  it, through the same saturation filter, with the same light field. When
//  nothing is playing - stopped, or paused long enough that the mini-player
//  has gone - the room fades back to the plain floor. Video keeps the plain
//  floor: its picture is the colour.
//
//  ONE SOURCE, EVERY SURFACE. The cover's light is made once (CoverLight.kt,
//  shared with Now Playing) and the drift clocks run once, in
//  AppAmbientDriver. Every screen background reads that one
//  state and draws in WINDOW coordinates, so a screen opened over Home covers
//  it with exactly the same pixels, and the bottom bar continues the picture
//  instead of cutting a band across it. The draws read the clocks in the
//  draw phase only: the drift costs a redraw per frame, never a recompose.
//
//  CALM. No beat - the room does not pulse with the music, Now Playing does.
//  The tier decides the motion exactly as it does for Now Playing: Enhanced
//  drifts and adds the light field, Premium also turns, Ultra flows like
//  liquid (FluidLight.kt, Android 13+). Songs cross-dissolve. Reduced motion
//  holds still everywhere.
//
//  THE SCREEN ON TOP IS NOT REDRAWN WITH IT. A moving background invalidates
//  every frame. Drawn behind a screen's content in the same layer, it made
//  the whole screen - every row, every line of text - record itself again
//  each frame while a song played. The content now sits in a layer of its
//  own above the background, so the background redraws alone.
//
//  LEGIBLE ON ANY COVER. The scrim is measured per cover, not fixed. In the
//  dark theme the brightest 15% of the decoded cover is dimmed until it sits
//  at a fixed dark level, so a white cover gets a heavier scrim than a navy
//  one and text keeps the same contrast on both. In the light theme the
//  darkest 15% is lifted to a fixed light level instead.
// ============================================================================

/** Dark theme: sRGB luma the brightest 15% of the cover is brought down to. */
private const val DARK_TARGET = 0.22f
/** Light theme: sRGB luma the darkest 15% of the cover is brought up to. */
private const val LIGHT_TARGET = 0.80f
private const val MIN_SCRIM = 0.45f
private const val MAX_SCRIM = 0.88f
private const val FADE_IN_MS = 900
private const val FADE_OUT_MS = 1400
private const val CROSSFADE_MS = 1400

/** The motion: clocks (null while still), the cover's tones, and finish. */
internal class AmbientMotion(
    val pan: State<Float>?,
    val spin: State<Float>?,
    val field: State<Float>?,
    /** Ultra: the flow's phase, 0..1 over a loop. Replaces pan and spin. */
    val flow: State<Float>?,
    val tones: List<Color>,
    val pools: Int,
    val grain: Boolean
)

/** The room's light, shared by every screen background. */
@Stable
class AppAmbient internal constructor() {
    internal var frame by mutableStateOf<CoverLight?>(null)
    /** The previous cover, drawn under [frame] while [mix] runs 0 -> 1. */
    internal var outgoing by mutableStateOf<CoverLight?>(null)
    /** Ultra: one flow each, so both covers can flow during the cross-dissolve. */
    internal val fluidNow = FluidLight()
    internal val fluidOut = FluidLight()
    internal val mix = Animatable(1f)
    /** 0 = the plain floor, 1 = the room fully lit by the cover. */
    internal val presence = Animatable(0f)
    internal var motion by mutableStateOf<AmbientMotion?>(null)
}

/** Null outside the home scaffold: onboarding and the like keep the plain floor. */
val LocalAppAmbient = staticCompositionLocalOf<AppAmbient?> { null }

/** How much of the floor goes over [f] in the room: the room is calmer than Now Playing. */
private fun roomScrim(f: CoverLight, floorLuma: Float, darkTheme: Boolean): Float =
    scrimFor(f.bright, f.dark, floorLuma, darkTheme, DARK_TARGET, LIGHT_TARGET).coerceIn(MIN_SCRIM, MAX_SCRIM)

/**
 * Keeps [ambient] on [item]'s cover while [active] and fades the room back
 * to the plain floor when not. Emits no UI. Its own composable so that the
 * fades recompose only this, never the screen around it.
 */
@Composable
fun AppAmbientDriver(ambient: AppAmbient, item: AppMediaItem?, active: Boolean) {
    val context = LocalContext.current
    val q = LocalQuality.current
    val reduced = LocalReducedMotion.current
    val artVersion by ArtworkStore.version.collectAsState()
    var hasArt by remember { mutableStateOf(false) }

    // The cover. Keyed on the song, not on `active`: a song that is merely
    // paused keeps its frame, so fading out shows its own colour leaving.
    LaunchedEffect(item?.uri, artVersion) {
        val song = item ?: return@LaunchedEffect
        val next = withContext(Dispatchers.IO) {
            runCatching { loadCoverLight(context, song) }.getOrNull()
        }
        // No cover, no colour to take: the room goes back to the floor.
        hasArt = next != null
        if (next == null) return@LaunchedEffect
        val current = ambient.frame
        if (current == null || ambient.presence.value <= 0f) {
            ambient.outgoing = null
            ambient.frame = next
            ambient.mix.snapTo(1f)
        } else {
            ambient.outgoing = current
            ambient.frame = next
            ambient.mix.snapTo(0f)
            ambient.mix.animateTo(1f, tween(CROSSFADE_MS, easing = FastOutSlowInEasing))
            ambient.outgoing = null
        }
    }

    val on = active && hasArt && q.livingBackdrop
    LaunchedEffect(on) {
        ambient.presence.animateTo(
            if (on) 1f else 0f,
            tween(if (on) FADE_IN_MS else FADE_OUT_MS, easing = FastOutSlowInEasing)
        )
    }

    // Clocks only while the room is lit, or still fading, on a tier that
    // moves. An infinite transition asks for every frame while it exists.
    val lit = on || ambient.presence.value > 0f
    val moving = lit && q.backdropDrift && !reduced
    val flowing = moving && q.fluidFlow && fluidSupported
    val colors = rememberArtColors(item)
    val pan = if (moving && !flowing) {
        rememberInfiniteTransition(label = "roomPan").animateFloat(
            0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse), label = "pan"
        )
    } else null
    val spin = if (moving && q.backdropTurn && !flowing) {
        rememberInfiniteTransition(label = "roomTurn").animateFloat(
            0f, 360f, infiniteRepeatable(tween(90_000, easing = LinearEasing), RepeatMode.Restart), label = "spin"
        )
    } else null
    val tones = colors?.field.orEmpty()
    val pools = if (moving) q.lightField.coerceAtMost(if (tones.isEmpty()) 0 else maxOf(tones.size, 2)) else 0
    val field = if (pools > 0) {
        rememberInfiniteTransition(label = "roomField").animateFloat(
            0f, 1f, infiniteRepeatable(tween(48_000, easing = LinearEasing), RepeatMode.Restart), label = "fieldClock"
        )
    } else null
    val flow = if (flowing) {
        rememberInfiniteTransition(label = "roomFlow").animateFloat(
            0f, 1f, infiniteRepeatable(tween(FLUID_LOOP_MS, easing = LinearEasing), RepeatMode.Restart), label = "flow"
        )
    } else null
    val motion = remember(pan, spin, field, flow, tones, pools, q.grain) {
        AmbientMotion(pan, spin, field, flow, tones, pools, q.grain)
    }
    SideEffect { if (ambient.motion !== motion) ambient.motion = motion }
}

/**
 * A full screen's background: the plain floor, with the room's light on it
 * while a song plays. Replaces `.background(moodBackground())` on screens.
 * [extraScrim] darkens it further for a surface that has to read as one
 * (the bottom bar).
 */
@Composable
fun Modifier.screenBackground(extraScrim: Float = 0f): Modifier {
    val floor = LocalPalette.current.bg
    val ambient = LocalAppAmbient.current
        ?: return this.drawBehind { drawRect(floor) }
    val placement = remember { RoomPlacement() }
    return this
        .onGloballyPositioned { c ->
            val origin = c.positionInRoot()
            val room = c.findRootCoordinates().size.toSize()
            if (origin != placement.origin) placement.origin = origin
            if (room != placement.room) placement.room = room
        }
        .drawBehind {
            drawRect(floor)
            drawAmbient(ambient, placement.origin, placement.room, floor, extraScrim)
        }
        // The screen's own content, in a layer of its own: the background
        // above redraws every frame while it moves, and this way the rows and
        // text on top are not recorded again with it.
        .graphicsLayer()
}

/** Where this surface sits in the window, so the room lines up across surfaces. */
@Stable
private class RoomPlacement {
    var origin by mutableStateOf(Offset.Zero)
    var room by mutableStateOf(Size.Zero)
}

private val grainBrush by lazy { ShaderBrush(ImageShader(grainImage, TileMode.Repeated, TileMode.Repeated)) }

private fun DrawScope.drawAmbient(a: AppAmbient, origin: Offset, roomSize: Size, floor: Color, extraScrim: Float) {
    val p = a.presence.value
    val frame = a.frame
    if (p <= 0.001f || frame == null) return
    // Before the first placement the surface's own size stands in; a full
    // screen is the room anyway.
    val room = if (roomSize.width > 0f && roomSize.height > 0f) roomSize else size
    val m = a.motion
    val out = a.outgoing
    val mix = if (out != null) a.mix.value.coerceIn(0f, 1f) else 1f
    val floorLuma = floor.lumaSrgb()
    val darkTheme = floor.luminance() < 0.5f
    val scrim = if (out != null) {
        val from = roomScrim(out, floorLuma, darkTheme)
        from + (roomScrim(frame, floorLuma, darkTheme) - from) * mix
    } else roomScrim(frame, floorLuma, darkTheme)

    clipRect {
        translate(-origin.x, -origin.y) {
            val mid = Offset(room.width / 2f, room.height / 2f)
            val side = max(room.width, room.height) * 1.35f
            val pan = m?.pan?.value ?: 0.5f
            val dx = (pan - 0.5f) * room.width * 0.22f
            val dy = (0.5f - pan) * room.height * 0.10f
            val degrees = m?.spin?.value
            val phase = m?.flow?.value
            // Ultra: both covers flow. Null where the flow cannot run, and
            // the cover is drawn the Premium way instead.
            val nowFlow = if (phase != null) a.fluidNow.brush(frame.backdrop, room, phase, 0f) else null
            val outFlow = if (phase != null && nowFlow != null && out != null && mix < 1f) {
                a.fluidOut.brush(out.backdrop, room, phase, 0f)
            } else null
            if (nowFlow != null) {
                if (outFlow != null) {
                    drawRect(outFlow, topLeft = Offset.Zero, size = room, alpha = p, colorFilter = BackdropFilter)
                }
                drawRect(nowFlow, topLeft = Offset.Zero, size = room, alpha = p * mix, colorFilter = BackdropFilter)
            } else {
                withTransform({
                    translate(dx, dy)
                    if (degrees != null) rotate(degrees, pivot = mid)
                }) {
                    if (out != null && mix < 1f) drawCover(out.backdrop, side, BackdropFilter, p, at = mid)
                    drawCover(frame.backdrop, side, BackdropFilter, p * mix, at = mid)
                }
            }
            val t = m?.field?.value
            if (m != null && t != null && m.pools > 0 && m.tones.isNotEmpty()) {
                drawLightField(m.tones, t, 0f, m.pools, p * mix, w = room.width, h = room.height)
            }
            // A touch lighter at the top, where the header sits on it, and a
            // touch heavier at the bottom, where the rows and the bar are.
            val s = scrim + extraScrim
            drawRect(
                Brush.verticalGradient(
                    0f to floor.copy(alpha = (s - 0.04f).coerceIn(0f, 0.95f)),
                    0.5f to floor.copy(alpha = s.coerceIn(0f, 0.95f)),
                    1f to floor.copy(alpha = (s + 0.06f).coerceIn(0f, 0.95f)),
                    startY = 0f, endY = room.height
                ),
                topLeft = Offset.Zero, size = room
            )
            if (m?.grain != false) {
                drawRect(grainBrush, topLeft = Offset.Zero, size = room, alpha = 0.06f * p, blendMode = BlendMode.Overlay)
            }
        }
    }
}
