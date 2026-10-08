package com.media.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource

// ============================================================================
//  DEFAULT ARTWORK - ONE APPEARANCE, ONE MARK PER KIND
//
//  What a track with no cover shows. Until 5.1 it was a near-black tile with
//  a thin note at 28% white, and next to other players' fallbacks it read as
//  an empty slot rather than a cover. Now:
//
//      slate ground -> a soft glow of the brand teal -> a bright mark
//
//  The mark says what the file is: two beamed notes for music, a microphone
//  for podcasts, an open book for audiobooks, a waveform for recordings, a
//  play triangle for video. Same ground, glow and weight for all, so a list
//  of cover-less tracks is calm and consistent. It is the brand teal, fixed,
//  not the mood's accent: a cover-less track looks the same everywhere.
//
//  The geometry here is exactly what was prototyped in Skia (Android's own
//  renderer) beside other players' tiles before it was written.
// ============================================================================

object ArtworkTokens {
    val Top = Color(0xFF2C3140)
    val Bottom = Color(0xFF171A22)
    val Glow = Color(0xFF2DD4BF)
    val MarkTop = Color(0xFFFFFFFF)
    val MarkBottom = Color(0xFFBFEEE6)
    const val GlowAlpha = 0.22f
    const val MarkTopAlpha = 0.92f
    const val MarkBottomAlpha = 0.70f
    const val EdgeAlpha = 0.07f
    /** The mark's size against the tile's short side. */
    const val MarkScale = 0.50f
}

/** What the mark depicts. */
enum class ArtworkKind { MUSIC, PODCAST, AUDIOBOOK, RECORDING, VIDEO }

fun artworkKindOf(item: AppMediaItem): ArtworkKind = when {
    item.type == MediaType.VIDEO -> ArtworkKind.VIDEO
    else -> when (item.pillar) {
        Pillar.PODCAST -> ArtworkKind.PODCAST
        Pillar.AUDIOBOOK -> ArtworkKind.AUDIOBOOK
        Pillar.RECORDING -> ArtworkKind.RECORDING
        Pillar.VIDEO -> ArtworkKind.VIDEO
        Pillar.MUSIC -> ArtworkKind.MUSIC
    }
}

private fun DrawScope.drawGround() {
    drawRect(
        Brush.linearGradient(
            colors = listOf(ArtworkTokens.Top, ArtworkTokens.Bottom),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )
    )
    val r = size.minDimension * 0.62f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(ArtworkTokens.Glow.copy(alpha = ArtworkTokens.GlowAlpha), Color.Transparent),
            center = center,
            radius = r
        ),
        radius = r,
        center = center
    )
}

private fun DrawScope.markBrush(): Brush = Brush.verticalGradient(
    colors = listOf(
        ArtworkTokens.MarkTop.copy(alpha = ArtworkTokens.MarkTopAlpha),
        ArtworkTokens.MarkBottom.copy(alpha = ArtworkTokens.MarkBottomAlpha)
    ),
    startY = size.height * 0.25f,
    endY = size.height * 0.75f
)

/** Maps a 24-unit icon grid, centred, onto a mark of side [m]. */
private class Grid(m: Float, c: Offset) {
    val s = m / 24f
    private val ox = c.x - 12f * s
    private val oy = c.y - 12f * s
    fun x(v: Float) = ox + v * s
    fun y(v: Float) = oy + v * s
    fun p(x: Float, y: Float) = Offset(x(x), y(y))
    fun rect(l: Float, t: Float, r: Float, b: Float) = Rect(x(l), y(t), x(r), y(b))
}

private fun DrawScope.drawNotes(g: Grid, brush: Brush) {
    val path = Path().apply {
        // The beam, slanting up to the right.
        moveTo(g.x(8.6f), g.y(5.4f))
        lineTo(g.x(19.6f), g.y(2.6f))
        lineTo(g.x(19.6f), g.y(5.6f))
        lineTo(g.x(8.6f), g.y(8.4f))
        close()
        addRoundRect(RoundRect(g.rect(8.6f, 6.4f, 10.6f, 17.6f), CornerRadius(g.s)))
        addRoundRect(RoundRect(g.rect(17.6f, 3.6f, 19.6f, 15.2f), CornerRadius(g.s)))
    }
    drawPath(path, brush)
    for (head in listOf(g.p(7.0f, 17.8f), g.p(16.0f, 15.4f))) {
        rotate(-22f, pivot = head) {
            drawOval(
                brush,
                topLeft = Offset(head.x - 3.4f * g.s, head.y - 2.6f * g.s),
                size = Size(6.8f * g.s, 5.2f * g.s)
            )
        }
    }
}

private fun DrawScope.drawMic(g: Grid, brush: Brush) {
    drawRoundRect(brush, topLeft = g.p(9f, 2.5f), size = Size(6f * g.s, 11.5f * g.s), cornerRadius = CornerRadius(3f * g.s))
    // The cradle: the lower half of a ring around the capsule.
    drawArc(
        brush, startAngle = 0f, sweepAngle = 180f, useCenter = false,
        topLeft = g.p(5.5f, 4.5f), size = Size(13f * g.s, 12.5f * g.s),
        style = Stroke(width = 1.8f * g.s, cap = StrokeCap.Round)
    )
    drawRoundRect(brush, topLeft = g.p(11.1f, 17f), size = Size(1.8f * g.s, 3.6f * g.s), cornerRadius = CornerRadius(0.9f * g.s))
    drawRoundRect(brush, topLeft = g.p(8f, 20f), size = Size(8f * g.s, 1.8f * g.s), cornerRadius = CornerRadius(0.9f * g.s))
}

private fun DrawScope.drawBook(g: Grid, brush: Brush) {
    val left = Path().apply {
        moveTo(g.x(2.5f), g.y(5f)); lineTo(g.x(11.2f), g.y(6.6f))
        lineTo(g.x(11.2f), g.y(19.8f)); lineTo(g.x(2.5f), g.y(18.2f)); close()
    }
    val right = Path().apply {
        moveTo(g.x(12.8f), g.y(6.6f)); lineTo(g.x(21.5f), g.y(5f))
        lineTo(g.x(21.5f), g.y(18.2f)); lineTo(g.x(12.8f), g.y(19.8f)); close()
    }
    drawPath(left, brush)
    drawPath(right, brush)
}

private fun DrawScope.drawWave(g: Grid, brush: Brush) {
    val heights = floatArrayOf(6f, 12f, 18f, 11f, 5f)
    val w = 2.4f
    val gap = 1.9f
    val total = heights.size * w + (heights.size - 1) * gap
    var x = 12f - total / 2f
    for (h in heights) {
        drawRoundRect(
            brush, topLeft = g.p(x, 12f - h / 2f), size = Size(w * g.s, h * g.s),
            cornerRadius = CornerRadius(w / 2f * g.s)
        )
        x += w + gap
    }
}

private fun DrawScope.drawPlay(g: Grid, brush: Brush) {
    val path = Path().apply {
        moveTo(g.x(8.5f), g.y(5.5f)); lineTo(g.x(19f), g.y(12f)); lineTo(g.x(8.5f), g.y(18.5f)); close()
    }
    drawPath(path, brush)
}

private fun DrawScope.drawEdge() {
    val w = (size.minDimension * 0.01f).coerceAtLeast(1f)
    drawRect(color = Color.White.copy(alpha = ArtworkTokens.EdgeAlpha), style = Stroke(width = w))
}

fun DrawScope.drawDefaultArtwork(kind: ArtworkKind) {
    drawGround()
    val g = Grid(size.minDimension * ArtworkTokens.MarkScale, center)
    val brush = markBrush()
    when (kind) {
        ArtworkKind.MUSIC -> drawNotes(g, brush)
        ArtworkKind.PODCAST -> drawMic(g, brush)
        ArtworkKind.AUDIOBOOK -> drawBook(g, brush)
        ArtworkKind.RECORDING -> drawWave(g, brush)
        ArtworkKind.VIDEO -> drawPlay(g, brush)
    }
    drawEdge()
}

@Composable
fun GenerativeArtwork(
    id: Long,
    title: String,
    modifier: Modifier = Modifier,
    kind: ArtworkKind = ArtworkKind.MUSIC
) {
    val label = stringResource(R.string.artwork_none_for, title)
    Canvas(modifier.semantics { contentDescription = label }) {
        drawDefaultArtwork(kind)
    }
}

@Composable
fun GenerativeArtwork(item: AppMediaItem, modifier: Modifier = Modifier) =
    GenerativeArtwork(item.id, item.title, modifier, artworkKindOf(item))
