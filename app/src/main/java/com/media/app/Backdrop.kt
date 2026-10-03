package com.media.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ============================================================================
//  THE VISUAL ENGINE
//
//  What the display-quality tiers draw beyond layout and motion. Every piece
//  is real work on real signals - the cover's own pixels, the track's analysed
//  bass envelope, the phone's motion sensor - never decoration pretending to
//  be a measurement.
//
//  ART COLOURS        Every tier. The cover's palette, tuned for a dark room:
//                     an accent bright enough to read (the waveform, the
//                     rings, the lyric glow), a glow colour for the light
//                     behind the cover and its coloured shadow, and up to four
//                     deep tones for the light field. Now Playing takes its
//                     colour from the music instead of one fixed teal.
//
//  LIVING BACKDROP    The cover itself, behind Now Playing: its colour, none
//                     of its detail, as a smooth 128px texture made once per
//                     song (CoverLight.kt) and drawn full-screen - one texture
//                     draw, smooth on every Android version, no per-frame
//                     blur. Saturation is lifted so it reads as coloured
//                     light. The scrim over it is measured per cover: as dark
//                     as this cover needs for the text on it, never lighter
//                     than the designed gradient, and never solid black, so
//                     the colour runs on under the system navigation bar.
//
//  FLUID              Ultra on Android 13+. Instead of panning and turning,
//                     the cover flows like liquid (FluidLight.kt).
//
//  EDGE LIGHT         Every tier. The colour at each edge of the cover spills
//                     out behind it (CoverLight.kt), swelling on the beat with
//                     Reactive artwork. Capped per cover so a white cover's
//                     halo never sits bright under the title.
//
//  LIGHT FIELD        Enhanced and up. Soft pools of the cover's own tones
//                     drifting over the backdrop on looping paths (integer
//                     frequencies, so the loop has no seam). Premium adds a
//                     fourth pool and Ultra a fifth, and with Reactive artwork
//                     they swell on the bass.
//
//  GRAIN              Every tier. A fixed noise texture at a few percent,
//                     overlaid: it breaks the 8-bit banding that smooth dark
//                     gradients show on most panels. Not a look - a fix.
//
//  PARALLAX           Premium and Ultra. The cover tilts a few degrees with the phone,
//                     read from the game rotation vector (gyro + accel, no
//                     magnetometer, so no compass jitter), and a specular
//                     sheen slides across it the way light moves on glass.
//                     The rest angle re-centres slowly, so holding the phone
//                     at any angle comes back to flat. Behind it the backdrop
//                     moves the other way as the deepest layer, the light
//                     field half as far, so the screen has depth.
//
//  TOP REFRESH        Premium and Ultra. While Now Playing is open the window asks the
//                     panel for its highest refresh rate at the current
//                     resolution, and hands the choice back on close.
// ============================================================================

/** The cover's colours, tuned for a dark room. Null fields: the cover had no colour to give. */
data class ArtColors(val accent: Color?, val glow: Color?, val field: List<Color>)

private val artColorCache = LruCache<String, ArtColors>(48)

private fun hsl(argb: Int): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(argb, it) }

private fun fromHsl(h: Float, s: Float, l: Float): Color =
    Color(ColorUtils.HSLToColor(floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

/** Pure: the swatches in, the tuned colours out. */
internal fun artColorsFrom(p: Palette): ArtColors {
    val ordered = listOfNotNull(
        p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch,
        p.mutedSwatch, p.darkMutedSwatch, p.lightMutedSwatch, p.dominantSwatch
    ).map { it.rgb }.distinct()
    val colourful = ordered.filter { hsl(it)[1] >= 0.14f }
    // A black-and-white cover has no hue to borrow: the app accent stays.
    val lead = (p.vibrantSwatch?.rgb ?: colourful.firstOrNull())?.takeIf { hsl(it)[1] >= 0.14f }
    val accent = lead?.let { val c = hsl(it); fromHsl(c[0], maxOf(c[1], 0.50f), c[2].coerceIn(0.58f, 0.72f)) }
    val glow = lead?.let { val c = hsl(it); fromHsl(c[0], c[1].coerceIn(0.50f, 0.90f), c[2].coerceIn(0.42f, 0.58f)) }
    val field = (if (colourful.isNotEmpty()) colourful else ordered).take(4).map {
        val c = hsl(it)
        fromHsl(c[0], c[1].coerceIn(0.35f, 0.85f), c[2].coerceIn(0.26f, 0.50f))
    }
    return ArtColors(accent, glow, field)
}

/** The cover's palette for [item], cached per cover. Null until known. */
@Composable
fun rememberArtColors(item: AppMediaItem?): ArtColors? {
    val context = LocalContext.current
    val artVersion by ArtworkStore.version.collectAsState()
    var colors by remember { mutableStateOf<ArtColors?>(null) }
    LaunchedEffect(item?.uri, artVersion) {
        if (item == null) { colors = null; return@LaunchedEffect }
        val key = "${item.uri}#${ArtworkStore.stamp(item.id)}"
        artColorCache.get(key)?.let { colors = it; return@LaunchedEffect }
        colors = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = loadArt(context, item, 128)?.asAndroidBitmap() ?: return@runCatching null
                artColorsFrom(Palette.from(bmp).maximumColorCount(24).generate())
            }.getOrNull()
        }?.also { artColorCache.put(key, it) }
    }
    return colors
}

/** 96px of fixed noise, made once. Tiled, it is the anti-banding grain. */
internal val grainImage: ImageBitmap by lazy {
    val n = 96
    val rnd = java.util.Random(0x5EED)
    val px = IntArray(n * n) { val v = 96 + rnd.nextInt(64); android.graphics.Color.argb(255, v, v, v) }
    Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * How a cover becomes light: saturated so it reads as coloured light, and
 * dimmed a little so a white cover cannot wash out the controls. Shared by
 * Now Playing and the app-wide backdrop (AppBackdrop.kt), so both show the
 * same colour from the same cover.
 */
internal val BackdropFilter: ColorFilter by lazy {
    val m = ColorMatrix().apply { setToSaturation(1.45f) }
    m.timesAssign(ColorMatrix().apply { setToScale(BACKDROP_DIM, BACKDROP_DIM, BACKDROP_DIM, 1f) })
    ColorFilter.colorMatrix(m)
}

/** BackdropFilter's brightness scale. Saturation keeps luma, so this is the luma scale. */
internal const val BACKDROP_DIM = 0.82f

/** The edge light: saturated so it reads as light, not dimmed - it is already capped per cover. */
internal val EdgeFilter: ColorFilter by lazy {
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.30f) })
}

// Now Playing is meant to be more colourful than the room behind the other
// screens (AppBackdrop.kt, 0.22 / 0.80), so its targets are looser.
/** Dark theme: luma the brightest 15% of the drawn cover is brought down to. */
private const val NP_DARK_TARGET = 0.30f
/** Light theme: luma the darkest 15% is brought up to. */
private const val NP_LIGHT_TARGET = 0.72f
private const val NP_MAX_SCRIM = 0.80f
/** Dark theme: the edge light's bright edges are capped at this luma. */
private const val EDGE_DARK_TARGET = 0.42f
/** Light theme: the edge light may darken the floor down to this luma, no further. */
private const val EDGE_LIGHT_FLOOR = 0.58f

@Composable
fun LivingBackdrop(
    light: CoverLight?,
    colors: ArtColors?,
    beat: BeatState,
    reactive: Boolean,
    drift: Boolean,
    turn: Boolean,
    fluid: Boolean,
    fieldPools: Int,
    grain: Boolean,
    /** Premium and Ultra: the backdrop moves against the tilt as the deepest layer. Null: no depth. */
    tilt: Tilt?,
    modifier: Modifier = Modifier
) {
    if (light == null) return
    val image = light.backdrop
    val reduced = LocalReducedMotion.current
    // Cross-dissolve on a track change instead of a cut.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(image) {
        shown.snapTo(0f)
        shown.animateTo(1f, tween(Motion.Large))
    }
    val moving = drift && !reduced
    val flowing = moving && fluid && fluidSupported
    // Only a tier that moves runs a clock at all: an infinite transition
    // asks for every frame for as long as it exists, read or not. The flow
    // carries its own drift and turn, so it replaces those two clocks.
    val pan = if (moving && !flowing) {
        rememberInfiniteTransition(label = "backdrop").animateFloat(
            0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse), label = "pan"
        )
    } else null
    val spin = if (moving && turn && !flowing) {
        rememberInfiniteTransition(label = "backdropTurn").animateFloat(
            0f, 360f, infiniteRepeatable(tween(90_000, easing = LinearEasing), RepeatMode.Restart), label = "spin"
        )
    } else null
    val flow = if (flowing) {
        rememberInfiniteTransition(label = "backdropFlow").animateFloat(
            0f, 1f, infiniteRepeatable(tween(FLUID_LOOP_MS, easing = LinearEasing), RepeatMode.Restart), label = "flow"
        )
    } else null
    val fluidLight = remember { FluidLight() }
    val pools = if (moving) fieldPools.coerceAtMost(colors?.field?.size?.let { maxOf(it, 2) } ?: 0) else 0
    val field = if (pools > 0) {
        rememberInfiniteTransition(label = "field").animateFloat(
            0f, 1f, infiniteRepeatable(tween(48_000, easing = LinearEasing), RepeatMode.Restart), label = "fieldClock"
        )
    } else null
    val ink = MediaColors.Ink
    val darkTheme = ink.luminance() < 0.5f
    // As dark as this cover needs for the text on it; the designed gradient
    // below is the least it ever gets.
    val need = scrimFor(light.bright, light.dark, ink.lumaSrgb(), darkTheme, NP_DARK_TARGET, NP_LIGHT_TARGET)
        .coerceAtMost(NP_MAX_SCRIM)
    val grainBrush = remember { ShaderBrush(ImageShader(grainImage, TileMode.Repeated, TileMode.Repeated)) }

    Box(modifier) {
        // Light: the cover and the light field.
        Canvas(Modifier.fillMaxSize()) {
            val level = if (reactive) beat.level else 0f
            val mid = center
            val scale = 1f + 0.06f * level
            // Depth: the deepest layer moves against the tilt.
            val deepX = -(tilt?.x ?: 0f) * size.width * 0.035f
            val deepY = -(tilt?.y ?: 0f) * size.height * 0.02f
            val brush = flow?.let { fluidLight.brush(image, size, it.value, level) }
            if (brush != null) {
                // Drawn past the edges, so the depth shift never shows one.
                val m = max(size.width, size.height) * 0.06f
                withTransform({
                    translate(deepX, deepY)
                    scale(scale, scale, pivot = mid)
                }) {
                    drawRect(
                        brush, topLeft = Offset(-m, -m), size = Size(size.width + 2 * m, size.height + 2 * m),
                        alpha = shown.value, colorFilter = BackdropFilter
                    )
                }
            } else {
                val side = max(size.width, size.height) * 1.35f
                val p = pan?.value ?: 0.5f
                val dx = (p - 0.5f) * size.width * 0.22f + deepX
                val dy = (0.5f - p) * size.height * 0.10f + deepY
                val degrees = spin?.value
                withTransform({
                    translate(dx, dy)
                    if (degrees != null) rotate(degrees, pivot = mid)
                    scale(scale, scale, pivot = mid)
                }) {
                    drawCover(image, side, BackdropFilter, alpha = shown.value)
                }
            }
            val t = field?.value
            if (t != null && colors != null && colors.field.isNotEmpty()) {
                translate(deepX * 0.5f, deepY * 0.5f) {
                    drawLightField(colors.field, t, level, pools, shown.value)
                }
            }
        }
        // Legibility and finish. Only as dark as this cover needs and never
        // black, so the colour runs on under the navigation bar instead of
        // ending in a black band.
        Canvas(Modifier.fillMaxSize()) {
            val level = if (reactive) beat.level else 0f
            val lift = 0.14f * level
            drawRect(
                Brush.verticalGradient(
                    0f to ink.copy(alpha = (max(0.40f, need) - lift).coerceAtLeast(0f)),
                    0.45f to ink.copy(alpha = (max(0.42f, need) - lift).coerceAtLeast(0f)),
                    0.75f to ink.copy(alpha = max(0.52f, need)),
                    1f to ink.copy(alpha = max(0.58f, need))
                )
            )
            if (grain) drawRect(grainBrush, alpha = 0.06f, blendMode = BlendMode.Overlay)
        }
    }
}

/**
 * How strongly the edge light may show on this floor: in the dark theme its
 * bright edges are held to EDGE_DARK_TARGET, in the light theme its dark
 * edges may not pull the floor below EDGE_LIGHT_FLOOR. Never under a quarter.
 */
internal fun edgeLightCap(light: CoverLight, floorLuma: Float, darkTheme: Boolean): Float =
    if (darkTheme) (EDGE_DARK_TARGET / light.edgeBright.coerceAtLeast(0.001f)).coerceIn(0.25f, 1f)
    else ((floorLuma - EDGE_LIGHT_FLOOR) / (floorLuma - light.edgeDark).coerceAtLeast(0.001f)).coerceIn(0.25f, 1f)

/**
 * The edge light behind a cover of side [cover] centred on [at]: the texture
 * at twice the cover's size, a little more on the beat.
 */
internal fun DrawScope.drawEdgeLight(edge: ImageBitmap, at: Offset, cover: Float, level: Float, alpha: Float) {
    val s = (cover * 2f * (1f + 0.06f * level)).toInt().coerceAtLeast(1)
    drawImage(
        image = edge,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(edge.width, edge.height),
        dstOffset = IntOffset((at.x - s / 2f).toInt(), (at.y - s / 2f).toInt()),
        dstSize = IntSize(s, s),
        alpha = alpha.coerceIn(0f, 1f),
        colorFilter = EdgeFilter,
        filterQuality = FilterQuality.High
    )
}

/**
 * Pools of the cover's tones on looping paths. Frequencies are whole numbers
 * of the clock's period, so when it wraps from 1 back to 0 every pool is
 * exactly where it started.
 */
internal fun DrawScope.drawLightField(
    tones: List<Color>, t: Float, level: Float, pools: Int, alpha: Float,
    w: Float = size.width, h: Float = size.height
) {
    val base = max(w, h) * 0.52f
    val a = (2.0 * PI * t).toFloat()
    for (i in 0 until pools) {
        val tone = tones[i % tones.size]
        val fx = (i % 3 + 1).toFloat()
        val fy = ((i + 1) % 2 + 1).toFloat()
        val ph = i * 1.9f
        val x = w * (0.5f + 0.34f * sin(a * fx + ph))
        val y = h * (0.40f + 0.28f * cos(a * fy + ph * 0.7f))
        val r = base * (0.85f + 0.12f * (i % 2)) * (1f + 0.22f * level)
        drawCircle(
            Brush.radialGradient(
                listOf(tone.copy(alpha = 0.50f * alpha), tone.copy(alpha = 0.18f * alpha), Color.Transparent),
                center = Offset(x, y), radius = r
            ),
            radius = r, center = Offset(x, y)
        )
    }
}

internal fun DrawScope.drawCover(
    image: ImageBitmap, side: Float, filter: ColorFilter, alpha: Float, at: Offset = center
) {
    val s = side.toInt().coerceAtLeast(1)
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset((at.x - s / 2f).toInt(), (at.y - s / 2f).toInt()),
        dstSize = IntSize(s, s),
        alpha = alpha.coerceIn(0f, 1f),
        colorFilter = filter,
        filterQuality = FilterQuality.High
    )
}

// ------------------------------------------------------------------ parallax

/** Tilt as -1..1 on each axis. Read inside draw or layer lambdas only. */
@Stable
class Tilt {
    var x by mutableStateOf(0f)
        internal set
    var y by mutableStateOf(0f)
        internal set
}

@Composable
fun rememberTilt(enabled: Boolean): Tilt {
    val context = LocalContext.current
    val tilt = remember { Tilt() }
    DisposableEffect(enabled) {
        if (!enabled) {
            tilt.x = 0f; tilt.y = 0f
            return@DisposableEffect onDispose { }
        }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }
        val rot = FloatArray(9)
        val ori = FloatArray(3)
        var refPitch = Float.NaN
        var refRoll = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                val pitch = ori[1]
                val roll = ori[2]
                if (refPitch.isNaN()) { refPitch = pitch; refRoll = roll }
                // Re-centre slowly: the rest angle follows the hand.
                refPitch += (pitch - refPitch) * 0.015f
                refRoll += (roll - refRoll) * 0.015f
                // ~0.35 rad (20 degrees) of hand movement is full tilt.
                val tx = ((roll - refRoll) / 0.35f).coerceIn(-1f, 1f)
                val ty = ((pitch - refPitch) / 0.35f).coerceIn(-1f, 1f)
                // Low-pass so sensor noise never shows as shimmer.
                tilt.x += (tx - tilt.x) * 0.18f
                tilt.y += (ty - tilt.y) * 0.18f
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            sm.unregisterListener(listener)
            tilt.x = 0f; tilt.y = 0f
        }
    }
    return tilt
}

/**
 * The glass highlight that slides across the cover as it tilts, plus the
 * flash that sweeps it on a hard hit (Ultra with Reactive artwork on).
 * [flash] is 0..1, already shaped; 0 draws nothing.
 */
fun DrawScope.drawCoverLight(tiltX: Float, tiltY: Float, sheen: Boolean, flash: Float, flashPos: Float) {
    if (sheen) {
        val cx = size.width * (0.5f - tiltX * 0.55f)
        val cy = size.height * (0.5f - tiltY * 0.55f)
        val strength = 0.05f + 0.07f * (abs(tiltX) + abs(tiltY)).coerceAtMost(1f)
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = strength),
                1f to Color.Transparent,
                start = Offset(cx - size.width * 0.6f, cy - size.height * 0.6f),
                end = Offset(cx + size.width * 0.6f, cy + size.height * 0.6f)
            )
        )
    }
    if (flash > 0.01f) {
        // A diagonal band crossing from top-left to bottom-right.
        val w = size.width + size.height
        val at = -0.3f * w + flashPos * 1.6f * w
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.32f * flash),
                1f to Color.Transparent,
                start = Offset(at - w * 0.18f, 0f),
                end = Offset(at + w * 0.18f, size.height * 0.4f)
            )
        )
    }
}

// ---------------------------------------------------------------- refresh

/** Asks for the panel's top refresh rate while [enabled]; restores on exit. */
@Composable
fun TopRefreshRate(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val activity = context as? Activity
        val window = activity?.window
        if (!enabled || window == null) return@DisposableEffect onDispose { }
        val before = window.attributes.preferredDisplayModeId
        runCatching {
            @Suppress("DEPRECATION")
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
                else activity.windowManager.defaultDisplay
            val current = display?.mode
            val best = display?.supportedModes
                ?.filter { current == null ||
                    (it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight) }
                ?.maxByOrNull { it.refreshRate }
            if (best != null && best.modeId != before) {
                window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
            }
        }
        onDispose {
            runCatching {
                window.attributes = window.attributes.apply { preferredDisplayModeId = before }
            }
        }
    }
}
