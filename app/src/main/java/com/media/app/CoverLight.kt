package com.media.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// ============================================================================
//  COVER LIGHT
//
//  Everything the light effects take from one cover, made once per song on a
//  background thread and cached. Now Playing and the app-wide room
//  (AppBackdrop.kt) read the same one, so a cover is decoded for light once.
//
//  BACKDROP  128px and smooth. The cover reduced to 20px - its colour, none of
//            its detail - brought up to 128px and box-blurred three times,
//            which is a near-Gaussian. The 20px texture stretched straight
//            across a screen showed its pixels as soft steps: a round shape
//            came out an octagon, a skyline a staircase. The 24-32dp blur the
//            higher tiers ran over the whole screen every frame softened the
//            steps but never removed them, and Android 11 and below had no
//            blur at all. This is smooth light on every version, for about a
//            millisecond once per song, and the per-frame blur is gone.
//  EDGE      64px. The cover fills the middle 32px, each pixel on its border is
//            carried straight out to the texture's edge, the whole is blurred,
//            and it fades out from the cover's edge. Drawn behind the cover at
//            twice its size, it is light in the colour of each edge spilling
//            past it - the cyan stripe down one side of a cover lights that
//            side, a sunset lights the top orange - where the old glow was one
//            colour all the way round.
//  LEVELS    How bright the drawn cover and its edge light are, so the scrim
//            and the edge light keep text legible on a white cover as on a
//            black one instead of using one fixed amount for both.
// ============================================================================

internal const val BACKDROP_PX = 20
private const val SMOOTH_PX = 128
private const val SMOOTH_RADIUS = 5
private const val EDGE_PX = 64
private const val EDGE_FOOT = 32
private const val EDGE_RADIUS = 4

/** The light one cover gives. Levels are sRGB luma, 0..1. */
class CoverLight internal constructor(
    /** The smooth backdrop texture. */
    val backdrop: ImageBitmap,
    /** The edge light, drawn at twice the cover's size centred on it. */
    val edge: ImageBitmap,
    /** 85th and 15th percentile luma of the cover as drawn (after BackdropFilter). */
    val bright: Float,
    val dark: Float,
    /** The same for the edge light where it shows (after EdgeFilter, which keeps luma). */
    val edgeBright: Float,
    val edgeDark: Float
)

private val coverLightCache = LruCache<String, CoverLight>(6)

/** [item]'s cover light, from cache or made now. Blocking: call on IO. Null for no cover. */
internal fun loadCoverLight(context: Context, item: AppMediaItem): CoverLight? {
    val key = "${item.uri}#${ArtworkStore.stamp(item.id)}"
    coverLightCache.get(key)?.let { return it }
    var src = loadArt(context, item, 96)?.asAndroidBitmap() ?: return null
    // Pixels are read below; a hardware bitmap has none to read.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE) {
        src = src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    }
    val tiny = Bitmap.createScaledBitmap(src, BACKDROP_PX, BACKDROP_PX, true)
    val tinyPx = IntArray(BACKDROP_PX * BACKDROP_PX).also {
        tiny.getPixels(it, 0, BACKDROP_PX, 0, 0, BACKDROP_PX, BACKDROP_PX)
    }
    val up = Bitmap.createScaledBitmap(tiny, SMOOTH_PX, SMOOTH_PX, true)
    val upPx = IntArray(SMOOTH_PX * SMOOTH_PX).also {
        up.getPixels(it, 0, SMOOTH_PX, 0, 0, SMOOTH_PX, SMOOTH_PX)
    }
    val foot = Bitmap.createScaledBitmap(src, EDGE_FOOT, EDGE_FOOT, true)
    val footPx = IntArray(EDGE_FOOT * EDGE_FOOT).also {
        foot.getPixels(it, 0, EDGE_FOOT, 0, 0, EDGE_FOOT, EDGE_FOOT)
    }
    val smooth = smoothPixels(upPx, SMOOTH_PX, SMOOTH_RADIUS)
    val edge = edgePixels(footPx, EDGE_FOOT, EDGE_PX, EDGE_RADIUS)
    val (bright, dark) = percentiles(lumas(tinyPx, BACKDROP_DIM))
    val (edgeBright, edgeDark) = percentiles(edgeLumas(edge))
    val light = CoverLight(
        backdrop = Bitmap.createBitmap(smooth, SMOOTH_PX, SMOOTH_PX, Bitmap.Config.ARGB_8888).asImageBitmap(),
        edge = Bitmap.createBitmap(edge, EDGE_PX, EDGE_PX, Bitmap.Config.ARGB_8888).asImageBitmap(),
        bright = bright, dark = dark, edgeBright = edgeBright, edgeDark = edgeDark
    )
    coverLightCache.put(key, light)
    return light
}

/** [item]'s cover light, null until made and for a song with no cover. */
@Composable
fun rememberCoverLight(item: AppMediaItem?): CoverLight? {
    val context = LocalContext.current
    val artVersion by ArtworkStore.version.collectAsState()
    var light by remember { mutableStateOf<CoverLight?>(null) }
    LaunchedEffect(item?.uri, artVersion) {
        if (item == null) { light = null; return@LaunchedEffect }
        light = withContext(Dispatchers.IO) {
            runCatching { loadCoverLight(context, item) }.getOrNull()
        }
    }
    return light
}

/**
 * How much of the floor colour has to go over a cover with these levels for
 * text to stay legible: in the dark theme the cover's bright end is brought
 * down to [darkTarget], in the light theme its dark end up to [lightTarget].
 */
internal fun scrimFor(
    bright: Float, dark: Float, floorLuma: Float, darkTheme: Boolean,
    darkTarget: Float, lightTarget: Float
): Float = if (darkTheme) {
    // bright * (1 - s) + floor * s <= darkTarget
    if (bright <= darkTarget) 0f
    else (bright - darkTarget) / (bright - floorLuma).coerceAtLeast(0.01f)
} else {
    // dark * (1 - s) + floor * s >= lightTarget
    if (dark >= lightTarget) 0f
    else (lightTarget - dark) / (floorLuma - dark).coerceAtLeast(0.01f)
}

// ---------------------------------------------------------------- the pixels
// Pure functions on ARGB pixel arrays, so they can be checked off the device.

/** Smooth backdrop: three box-blur passes of [radius] over a [side]-square image. */
internal fun smoothPixels(px: IntArray, side: Int, radius: Int): IntArray {
    val r = FloatArray(px.size) { ((px[it] shr 16) and 0xFF).toFloat() }
    val g = FloatArray(px.size) { ((px[it] shr 8) and 0xFF).toFloat() }
    val b = FloatArray(px.size) { (px[it] and 0xFF).toFloat() }
    for (c in arrayOf(r, g, b)) boxBlur(c, side, side, radius, passes = 3)
    return IntArray(px.size) { pack(255f, r[it], g[it], b[it]) }
}

/**
 * The edge light: the [foot]-square cover in the middle of an [out]-square
 * texture, its border pixels carried out to the edge, blurred, and faded out
 * from the cover's edge to the texture's.
 */
internal fun edgePixels(footPx: IntArray, foot: Int, out: Int, radius: Int): IntArray {
    val off = (out - foot) / 2
    val n = out * out
    val r = FloatArray(n)
    val g = FloatArray(n)
    val b = FloatArray(n)
    for (y in 0 until out) {
        val sy = (y - off).coerceIn(0, foot - 1)
        for (x in 0 until out) {
            val c = footPx[sy * foot + (x - off).coerceIn(0, foot - 1)]
            val i = y * out + x
            r[i] = ((c shr 16) and 0xFF).toFloat()
            g[i] = ((c shr 8) and 0xFF).toFloat()
            b[i] = (c and 0xFF).toFloat()
        }
    }
    for (c in arrayOf(r, g, b)) boxBlur(c, out, out, radius, passes = 3)
    val mid = (out - 1) / 2f
    val half = foot / 2f
    val reach = (out - foot) / 2f
    return IntArray(n) { i ->
        val x = i % out
        val y = i / out
        val dx = max(abs(x - mid) - half, 0f)
        val dy = max(abs(y - mid) - half, 0f)
        val fade = 1f - smoothstep(0f, reach, sqrt(dx * dx + dy * dy))
        pack(255f * fade.pow(1.6f), r[i], g[i], b[i])
    }
}

/** Separable box blur with clamped edges, [passes] times, in place. */
internal fun boxBlur(c: FloatArray, w: Int, h: Int, radius: Int, passes: Int) {
    val tmp = FloatArray(max(w, h))
    val win = (2 * radius + 1).toFloat()
    repeat(passes) {
        for (y in 0 until h) {
            val o = y * w
            var sum = 0f
            for (k in -radius..radius) sum += c[o + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[x] = sum / win
                sum += c[o + (x + radius + 1).coerceAtMost(w - 1)] - c[o + (x - radius).coerceAtLeast(0)]
            }
            tmp.copyInto(c, o, 0, w)
        }
        for (x in 0 until w) {
            var sum = 0f
            for (k in -radius..radius) sum += c[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                tmp[y] = sum / win
                sum += c[(y + radius + 1).coerceAtMost(h - 1) * w + x] - c[(y - radius).coerceAtLeast(0) * w + x]
            }
            for (y in 0 until h) c[y * w + x] = tmp[y]
        }
    }
}

/**
 * Rec.709 luma, the weights ColorMatrix.setToSaturation keeps, so a
 * saturation filter leaves it alone and only [scale] applies.
 */
internal fun luma(c: Int): Float =
    (0.213f * ((c shr 16) and 0xFF) + 0.715f * ((c shr 8) and 0xFF) + 0.072f * (c and 0xFF)) / 255f

private fun lumas(px: IntArray, scale: Float) = FloatArray(px.size) { luma(px[it]) * scale }

/** Luma of the edge light where it actually shows. */
private fun edgeLumas(px: IntArray): FloatArray {
    val shown = px.filter { (it ushr 24) > 13 }
    return FloatArray(shown.size) { luma(shown[it]) }
}

/** 85th and 15th percentile. */
private fun percentiles(v: FloatArray): Pair<Float, Float> {
    if (v.isEmpty()) return 0f to 0f
    v.sort()
    val last = v.size - 1
    return v[(last * 0.85f).toInt()] to v[(last * 0.15f).toInt()]
}

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun pack(a: Float, r: Float, g: Float, b: Float): Int {
    fun ch(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)
    return (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** Rec.709 luma of the gamma-encoded colour, the scale every level here is in. */
internal fun androidx.compose.ui.graphics.Color.lumaSrgb(): Float = 0.213f * red + 0.715f * green + 0.072f * blue
