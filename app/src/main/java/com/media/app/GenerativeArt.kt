package com.media.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

// ============================================================================
//  GENERATIVE ARTWORK (§5)
//
//  Files without embedded art get a composed visual identity instead of a
//  letter in a colored box. Five compositions, chosen deterministically, so a
//  given track ALWAYS renders the same artwork across launches and rescans.
//
//  Palette discipline (§2, §43): hue varies per item but saturation and
//  lightness stay clamped low. That gives a varied library that still reads as
//  one coherent product — never the six-color orange/teal/pink rotation the
//  old tileColor() produced, and never neon.
//
//  Everything is Canvas draw ops. No bitmap decode, no allocation per frame,
//  so this is safe to render in every row of a long list.
// ============================================================================

private fun seedOf(id: Long, title: String): Int {
    val base = if (id != 0L) id else title.hashCode().toLong()
    // Avalanche: without this, sequential MediaStore ids give near-identical
    // hues and a scrolling library looks like one long gradient.
    var h = base * -7046029254386353131L
    h = h xor (h ushr 32)
    h *= -4658895280553007687L
    h = h xor (h ushr 29)
    return (h and 0x7FFFFFFF).toInt()
}

/** deep base, mid tone, lifted accent — all restrained by construction. */
private fun paletteFor(seed: Int): Triple<Color, Color, Color> {
    val hue = (seed % 360).toFloat()
    return Triple(
        Color.hsl(hue, 0.24f, 0.14f),
        Color.hsl((hue + 16f) % 360f, 0.30f, 0.28f),
        Color.hsl((hue + 42f) % 360f, 0.36f, 0.48f)
    )
}

// ---- compositions ----------------------------------------------------------

/** Concentric rings, vinyl-inspired. */
private fun DrawScope.rings(rnd: Random, mid: Color, light: Color) {
    val c = Offset(
        size.width * (0.28f + rnd.nextFloat() * 0.44f),
        size.height * (0.30f + rnd.nextFloat() * 0.40f)
    )
    val maxR = size.minDimension * 0.74f
    val count = 5 + rnd.nextInt(4)
    for (i in 0 until count) {
        val t = (i + 1f) / count
        drawCircle(
            color = if (i % 2 == 0) light else mid,
            radius = maxR * t,
            center = c,
            alpha = 0.10f + 0.16f * (1f - t),
            style = Stroke(width = size.minDimension * (0.012f + 0.022f * (1f - t)))
        )
    }
}

/** Bars under a smooth sine envelope — reads as sound, not noise. */
private fun DrawScope.waveform(rnd: Random, mid: Color, light: Color) {
    val bars = 16 + rnd.nextInt(10)
    val freq = 1.2f + rnd.nextFloat() * 2.0f
    val phase = rnd.nextFloat() * 2f * PI.toFloat()
    val gap = size.width / bars
    val w = gap * 0.42f
    val midY = size.height * 0.5f
    for (i in 0 until bars) {
        val p = i.toFloat() / bars
        val a = sin(p * freq * 2f * PI.toFloat() + phase) * 0.5f + 0.5f
        val h = size.height * (0.12f + 0.62f * a)
        drawRoundRect(
            color = if (i % 3 == 0) light else mid,
            topLeft = Offset(i * gap + (gap - w) * 0.5f, midY - h * 0.5f),
            size = Size(w, h),
            cornerRadius = CornerRadius(w * 0.5f),
            alpha = 0.22f + 0.20f * a
        )
    }
}

/** Soft diagonal tonal bands. */
private fun DrawScope.bands(rnd: Random, mid: Color, light: Color) {
    val n = 4 + rnd.nextInt(4)
    val angle = -24f + rnd.nextFloat() * 48f
    rotate(angle) {
        val step = size.height * 2f / n
        for (i in 0 until n) {
            drawRect(
                color = if (i % 2 == 0) light else mid,
                topLeft = Offset(-size.width * 0.5f, -size.height * 0.5f + i * step),
                size = Size(size.width * 2f, step * (0.34f + 0.12f * (i % 3))),
                alpha = 0.09f + 0.05f * (i % 3)
            )
        }
    }
}

/** Nested rounded shapes, drifting off-centre. */
private fun DrawScope.strata(rnd: Random, mid: Color, light: Color) {
    val n = 3 + rnd.nextInt(3)
    val dx = (rnd.nextFloat() - 0.5f) * size.width * 0.24f
    val dy = (rnd.nextFloat() - 0.5f) * size.height * 0.24f
    for (i in n downTo 1) {
        val t = i.toFloat() / n
        val w = size.width * 0.84f * t
        val h = size.height * 0.84f * t
        drawRoundRect(
            color = if (i % 2 == 0) mid else light,
            topLeft = Offset(
                (size.width - w) * 0.5f + dx * (1f - t),
                (size.height - h) * 0.5f + dy * (1f - t)
            ),
            size = Size(w, h),
            cornerRadius = CornerRadius(size.minDimension * 0.15f * t),
            alpha = 0.11f + 0.10f * (1f - t)
        )
    }
}

/** Offset radial bloom with a few quiet specks. */
private fun DrawScope.bloom(rnd: Random, mid: Color, light: Color) {
    val c = Offset(
        size.width * (0.20f + rnd.nextFloat() * 0.60f),
        size.height * (0.20f + rnd.nextFloat() * 0.60f)
    )
    val r = size.minDimension * 0.88f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                light.copy(alpha = 0.40f),
                mid.copy(alpha = 0.14f),
                Color.Transparent
            ),
            center = c,
            radius = r
        ),
        radius = r,
        center = c
    )
    for (i in 0 until 3) {
        drawCircle(
            color = light,
            radius = size.minDimension * (0.025f + rnd.nextFloat() * 0.045f),
            center = Offset(size.width * rnd.nextFloat(), size.height * rnd.nextFloat()),
            alpha = 0.10f
        )
    }
}

// ---- entry point -----------------------------------------------------------

/**
 * Deterministic placeholder artwork. Same [id]/[title] always yields the same
 * composition and palette, so a track's visual identity is stable everywhere
 * it appears — row, grid tile, mini-player, full player.
 */
@Composable
fun GenerativeArtwork(id: Long, title: String, modifier: Modifier = Modifier) {
    val seed = remember(id, title) { seedOf(id, title) }
    Canvas(modifier) {
        val rnd = Random(seed)
        val (deep, mid, light) = paletteFor(seed)
        drawRect(Brush.linearGradient(listOf(deep, mid)))
        when (seed % 5) {
            0 -> rings(rnd, mid, light)
            1 -> waveform(rnd, mid, light)
            2 -> bands(rnd, mid, light)
            3 -> strata(rnd, mid, light)
            else -> bloom(rnd, mid, light)
        }
        // Unifying top-down scrim: keeps overlaid text legible on every variant.
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f))
            )
        )
    }
}

@Composable
fun GenerativeArtwork(item: AppMediaItem, modifier: Modifier = Modifier) =
    GenerativeArtwork(item.id, item.title, modifier)
