package com.media.app

import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ============================================================================
//  AMBIENT COLOUR (§4, §12)
//
//  §12 wants the Now Playing environment tinted by the current artwork. §4
//  qualifies it hard: "Do this tastefully. Never turn the whole screen into a
//  neon gradient."
//
//  So the extracted swatch is NOT used raw. It is pushed through a clamp that
//  caps saturation and forces a dark lightness band, which turns any swatch —
//  including a screaming yellow album cover — into a deep tonal wash that sits
//  under the existing mood gradient rather than fighting it.
// ============================================================================

// uri -> packed ARGB. Extraction costs a decode + a Palette pass, and the same
// track gets revisited constantly (expand, collapse, skip back).
private val ambientCache = LruCache<String, Int>(64)

/** Caps saturation, forces a dark lightness band. This is the taste filter. */
private fun tame(argb: Int): Color {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(argb, hsl)
    hsl[1] = hsl[1].coerceIn(0.15f, 0.42f)   // never fully grey, never neon
    hsl[2] = hsl[2].coerceIn(0.16f, 0.30f)   // always a deep tone
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}

/**
 * Ambient tone for [item], animated on change so a track skip washes rather
 * than cuts. Falls back to the live mood accent when there is no artwork —
 * which keeps generative-art tracks coherent with the theme instead of
 * dropping to a dead neutral.
 */
@Composable
fun rememberAmbientColor(item: AppMediaItem?): Color {
    val context = LocalContext.current
    val fallback = tame(MediaColors.Accent.toArgb0())
    var target by remember { mutableStateOf(fallback) }

    LaunchedEffect(item?.uri) {
        if (item == null) { target = fallback; return@LaunchedEffect }
        val key = item.uri.toString()
        ambientCache.get(key)?.let { target = Color(it); return@LaunchedEffect }

        val extracted = withContext(Dispatchers.IO) {
            runCatching {
                // 128px is ample for Palette and keeps the decode cheap.
                val bmp = loadArt(context, item, 128)?.asAndroidBitmap()
                    ?: return@runCatching null
                val p = Palette.from(bmp).maximumColorCount(16).generate()
                // Prefer swatches that are already dark and restrained; the
                // clamp will handle the rest either way.
                (p.darkMutedSwatch ?: p.mutedSwatch ?: p.darkVibrantSwatch
                    ?: p.dominantSwatch)?.rgb
            }.getOrNull()
        }

        target = if (extracted != null) {
            tame(extracted).also { ambientCache.put(key, it.toArgb0()) }
        } else {
            fallback
        }
    }

    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = tween(Motion.Large, easing = Motion.Emphasized),
        label = "ambient"
    )
    return animated
}

private fun Color.toArgb0(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt().coerceIn(0, 255),
    (red * 255f).toInt().coerceIn(0, 255),
    (green * 255f).toInt().coerceIn(0, 255),
    (blue * 255f).toInt().coerceIn(0, 255)
)
