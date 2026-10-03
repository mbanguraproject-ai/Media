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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
//  LIVING BACKDROP    The cover itself, behind Now Playing. Decoded at 20px
//                     and drawn full-screen with bilinear filtering, which IS
//                     a blur: smooth on every Android version, one texture
//                     draw. Android 12+ adds a real blur on top on the tiers
//                     that can afford it. Saturation is lifted so it reads as
//                     coloured light. The scrim over it is only as dark as
//                     legibility needs and never reaches solid black, so the
//                     colour runs all the way under the system navigation bar.
//
//  LIGHT FIELD        Premium and Ultra. Soft pools of the cover's own tones
//                     drifting over the backdrop on looping paths (integer
//                     frequencies, so the loop has no seam). Ultra adds a
//                     fourth pool, and with Reactive artwork they swell on
//                     the bass.
//
//  GRAIN              Every tier. A fixed noise texture at a few percent,
//                     overlaid: it breaks the 8-bit banding that smooth dark
//                     gradients show on most panels. Not a look - a fix.
//
//  PARALLAX           Ultra. The cover tilts a few degrees with the phone,
//                     read from the game rotation vector (gyro + accel, no
//                     magnetometer, so no compass jitter), and a specular
//                     sheen slides across it the way light moves on glass.
//                     The rest angle re-centres slowly, so holding the phone
//                     at any angle comes back to flat.
//
//  TOP REFRESH        Ultra. While Now Playing is open the window asks the
//                     panel for its highest refresh rate at the current
//                     resolution, and hands the choice back on close.
// ============================================================================

internal const val BACKDROP_PX = 20

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

/** The cover as a tiny source for the backdrop. Null for none. */
@Composable
private fun rememberBackdropImage(item: AppMediaItem?): ImageBitmap? {
    val context = LocalContext.current
    val artVersion by ArtworkStore.version.collectAsState()
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item?.uri, artVersion) {
        if (item == null) { image = null; return@LaunchedEffect }
        image = withContext(Dispatchers.IO) {
            runCatching {
                val src = loadArt(context, item, 96)?.asAndroidBitmap() ?: return@runCatching null
                Bitmap.createScaledBitmap(src, BACKDROP_PX, BACKDROP_PX, true).asImageBitmap()
            }.getOrNull()
        }
    }
    return image
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

@Composable
fun LivingBackdrop(
    item: AppMediaItem?,
    colors: ArtColors?,
    beat: BeatState,
    reactive: Boolean,
    drift: Boolean,
    turn: Boolean,
    fieldPools: Int,
    grain: Boolean,
    blur: Dp,
    modifier: Modifier = Modifier
) {
    val image = rememberBackdropImage(item) ?: return
    val reduced = LocalReducedMotion.current
    // Cross-dissolve on a track change instead of a cut.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(image) {
        shown.snapTo(0f)
        shown.animateTo(1f, tween(Motion.Large))
    }
    val moving = drift && !reduced
    // Only a tier that moves runs a clock at all: an infinite transition
    // asks for every frame for as long as it exists, read or not.
    val pan = if (moving) {
        rememberInfiniteTransition(label = "backdrop").animateFloat(
            0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse), label = "pan"
        )
    } else null
    val spin = if (moving && turn) {
        rememberInfiniteTransition(label = "backdropTurn").animateFloat(
            0f, 360f, infiniteRepeatable(tween(90_000, easing = LinearEasing), RepeatMode.Restart), label = "spin"
        )
    } else null
    val pools = if (moving) fieldPools.coerceAtMost(colors?.field?.size?.let { maxOf(it, 2) } ?: 0) else 0
    val field = if (pools > 0) {
        rememberInfiniteTransition(label = "field").animateFloat(
            0f, 1f, infiniteRepeatable(tween(48_000, easing = LinearEasing), RepeatMode.Restart), label = "fieldClock"
        )
    } else null
    val ink = MediaColors.Ink
    // Saturated so it reads as coloured light, and dimmed a little so a white
    // cover cannot wash out the controls now that the scrim stops short of
    // black.
    val saturate = BackdropFilter
    val grainBrush = remember { ShaderBrush(ImageShader(grainImage, TileMode.Repeated, TileMode.Repeated)) }
    val blurred = if (blur > 0.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(blur) else Modifier

    Box(modifier) {
        // Light: the cover and the light field. Blurred where the tier allows.
        Canvas(Modifier.fillMaxSize().then(blurred)) {
            val level = if (reactive) beat.level else 0f
            val side = max(size.width, size.height) * 1.35f
            val p = pan?.value ?: 0.5f
            val dx = (p - 0.5f) * size.width * 0.22f
            val dy = (0.5f - p) * size.height * 0.10f
            val scale = 1f + 0.06f * level
            val mid = center
            val degrees = spin?.value
            withTransform({
                translate(dx, dy)
                if (degrees != null) rotate(degrees, pivot = mid)
                scale(scale, scale, pivot = mid)
            }) {
                drawCover(image, side, saturate, alpha = shown.value)
            }
            val t = field?.value
            if (t != null && colors != null && colors.field.isNotEmpty()) {
                drawLightField(colors.field, t, level, pools, shown.value)
            }
        }
        // Legibility and finish, never blurred. The scrim is only as dark as
        // white text needs and stops well short of black, so the colour runs
        // on under the navigation bar instead of ending in a black band.
        Canvas(Modifier.fillMaxSize()) {
            val level = if (reactive) beat.level else 0f
            val lift = 0.14f * level
            drawRect(
                Brush.verticalGradient(
                    0f to ink.copy(alpha = (0.40f - lift).coerceAtLeast(0f)),
                    0.45f to ink.copy(alpha = (0.42f - lift).coerceAtLeast(0f)),
                    0.75f to ink.copy(alpha = 0.52f),
                    1f to ink.copy(alpha = 0.58f)
                )
            )
            if (grain) drawRect(grainBrush, alpha = 0.06f, blendMode = BlendMode.Overlay)
        }
    }
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
