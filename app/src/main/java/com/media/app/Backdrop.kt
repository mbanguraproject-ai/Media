package com.media.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlin.math.abs
import kotlin.math.max

// ============================================================================
//  THE VISUAL ENGINE
//
//  What the display-quality tiers draw beyond layout and motion. Every piece
//  is real work on real signals - the cover's own pixels, the track's analysed
//  bass envelope, the phone's motion sensor - never decoration pretending to
//  be a measurement.
//
//  LIVING BACKDROP    The cover itself, behind Now Playing. Decoded at 20px
//                     and drawn full-screen with bilinear filtering, which IS
//                     a blur: smooth on every Android version, one texture
//                     draw, no RenderEffect needed. Android 12+ adds a real
//                     blur on top on the tiers that can afford it. Saturation
//                     is lifted so it reads as coloured light, then a scrim
//                     keeps text legible and the controls on true black.
//                     Premium lets it drift; Ultra drifts and turns.
//                     With Reactive artwork on, it breathes with the bass.
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

private const val BACKDROP_PX = 20

/** The cover as a tiny, saturated source for the backdrop. Null for none. */
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

@Composable
fun LivingBackdrop(
    item: AppMediaItem?,
    beat: BeatState,
    reactive: Boolean,
    drift: Boolean,
    turn: Boolean,
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
    // Only a tier that drifts runs a clock at all: an infinite transition
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
    val ink = MediaColors.Ink
    val saturate = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.45f) }) }
    val blurred = if (blur > 0.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(blur) else Modifier

    Canvas(modifier.then(blurred)) {
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
        // Legibility: dim at the top where the bar sits, near black at the
        // bottom where the controls are. A hit lifts the dim a touch, so the
        // room brightens with the kick.
        val lift = 0.18f * level
        drawRect(
            Brush.verticalGradient(
                0f to ink.copy(alpha = (0.42f - lift).coerceAtLeast(0f)),
                0.45f to ink.copy(alpha = (0.55f - lift).coerceAtLeast(0f)),
                0.78f to ink.copy(alpha = 0.90f),
                1f to ink
            )
        )
    }
}

private fun DrawScope.drawCover(image: ImageBitmap, side: Float, filter: ColorFilter, alpha: Float) {
    val s = side.toInt().coerceAtLeast(1)
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset((center.x - s / 2f).toInt(), (center.y - s / 2f).toInt()),
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
