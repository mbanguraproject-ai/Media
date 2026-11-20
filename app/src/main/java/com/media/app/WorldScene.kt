package com.media.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A compact, deterministic description of the artwork palette.
 *
 * This is deliberately a visual signature, not an attempt to infer the
 * literal subject of a cover. Scene families are treatments selected from
 * measured palette characteristics; they never label the music itself.
 */
data class ArtworkColorSignature(
    val hueDegrees: Float?,
    val saturation: Float,
    val luminance: Float
) {
    companion object {
        fun fromArgb(colors: List<Int>): ArtworkColorSignature {
            if (colors.isEmpty()) return ArtworkColorSignature(null, 0f, 0f)

            var hueX = 0.0
            var hueY = 0.0
            var hueWeight = 0.0
            var saturationTotal = 0.0
            var luminanceTotal = 0.0

            colors.forEach { color ->
                val r = ((color shr 16) and 0xff) / 255.0
                val g = ((color shr 8) and 0xff) / 255.0
                val b = (color and 0xff) / 255.0
                val high = max(r, max(g, b))
                val low = min(r, min(g, b))
                val delta = high - low
                val lightness = (high + low) / 2.0
                val saturation = if (delta == 0.0) 0.0 else
                    delta / (1.0 - kotlin.math.abs(2.0 * lightness - 1.0)).coerceAtLeast(1e-9)

                val hue = when {
                    delta == 0.0 -> 0.0
                    high == r -> 60.0 * (((g - b) / delta) % 6.0)
                    high == g -> 60.0 * (((b - r) / delta) + 2.0)
                    else -> 60.0 * (((r - g) / delta) + 4.0)
                }.let { if (it < 0.0) it + 360.0 else it }

                val weight = saturation
                hueX += cos(Math.toRadians(hue)) * weight
                hueY += sin(Math.toRadians(hue)) * weight
                hueWeight += weight
                saturationTotal += saturation
                luminanceTotal += 0.2126 * r + 0.7152 * g + 0.0722 * b
            }

            val hue = if (hueWeight < 0.08) null else {
                (Math.toDegrees(atan2(hueY, hueX)) + 360.0) % 360.0
            }

            return ArtworkColorSignature(
                hueDegrees = hue?.toFloat(),
                saturation = (saturationTotal / colors.size).toFloat().coerceIn(0f, 1f),
                luminance = (luminanceTotal / colors.size).toFloat().coerceIn(0f, 1f)
            )
        }

        fun from(colors: ArtColors?): ArtworkColorSignature =
            fromArgb(
                colors?.let { palette ->
                    buildList {
                        palette.accent?.let { add(it.toArgb()) }
                        palette.glow?.let { add(it.toArgb()) }
                        palette.field.forEach { add(it.toArgb()) }
                    }
                }.orEmpty()
            )
    }
}

enum class WorldSceneFamily {
    DREAM, URBAN, NATURE, OCEAN, COSMIC, MINIMAL
}

/**
 * Renderer-independent scene tokens. A later renderer can consume these
 * without knowing how artwork was decoded or how playback is implemented.
 * All motion values are bounded presentation parameters, not audio signals.
 */
data class WorldSceneProfile(
    val family: WorldSceneFamily,
    val depth: Float,
    val horizon: Float,
    val glow: Float,
    val motion: Float,
    val particleDensity: Float
)

object WorldSceneSelector {
    private val profiles = mapOf(
        WorldSceneFamily.DREAM to WorldSceneProfile(WorldSceneFamily.DREAM, 0.62f, 0.42f, 0.78f, 0.34f, 0.20f),
        WorldSceneFamily.URBAN to WorldSceneProfile(WorldSceneFamily.URBAN, 0.48f, 0.56f, 0.62f, 0.22f, 0.10f),
        WorldSceneFamily.NATURE to WorldSceneProfile(WorldSceneFamily.NATURE, 0.72f, 0.48f, 0.56f, 0.28f, 0.16f),
        WorldSceneFamily.OCEAN to WorldSceneProfile(WorldSceneFamily.OCEAN, 0.80f, 0.38f, 0.70f, 0.30f, 0.12f),
        WorldSceneFamily.COSMIC to WorldSceneProfile(WorldSceneFamily.COSMIC, 0.88f, 0.32f, 0.86f, 0.38f, 0.26f),
        WorldSceneFamily.MINIMAL to WorldSceneProfile(WorldSceneFamily.MINIMAL, 0.30f, 0.50f, 0.24f, 0.08f, 0f)
    )

    fun select(signature: ArtworkColorSignature): WorldSceneProfile {
        val hue = signature.hueDegrees?.let { ((it % 360f) + 360f) % 360f }
        val family = when {
            hue == null || signature.saturation < 0.16f -> WorldSceneFamily.MINIMAL
            hue >= 285f || hue < 15f -> WorldSceneFamily.DREAM
            hue < 55f -> WorldSceneFamily.URBAN
            hue < 165f -> WorldSceneFamily.NATURE
            hue < 255f -> WorldSceneFamily.OCEAN
            else -> WorldSceneFamily.COSMIC
        }
        return profiles.getValue(family)
    }
}


/**
 * Applies the selected scene's motion character to the existing measured
 * bass envelope. It never creates a signal: silence remains still and the
 * result is bounded for stable rendering on every device.
 */
internal fun sceneReactiveLevel(room: Float, scene: WorldSceneProfile): Float =
    (room.coerceIn(0f, 1f) * (0.80f + scene.motion.coerceIn(0f, 1f))).coerceIn(0f, 1f)


/** One remembered scene decision shared by every artwork-driven renderer. */
@Composable
fun rememberWorldScene(colors: ArtColors?): WorldSceneProfile =
    remember(colors) { WorldSceneSelector.select(ArtworkColorSignature.from(colors)) }
