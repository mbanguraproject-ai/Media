package com.media.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

// ============================================================================
//  DEFAULT ARTWORK - ONE APPEARANCE
//
//  Replaces the five-composition system (rings / waveform / bands / strata /
//  bloom, picked by `seed % 5`, hue wandering +/-32 degrees off the active
//  mood accent). That gave every cover-less track a different tile, in colours
//  that fought both the real artwork beside them and the app itself.
//
//  One treatment now, varying by nothing:
//      deep neutral ground -> soft top-left sheen -> centred mark
//
//  Two marks only, because a music note on a video is wrong: audio gets a
//  note, video gets a play triangle. Same ground, palette and weight. Nothing
//  samples the theme, so a cover-less track looks identical everywhere.
// ============================================================================

object ArtworkTokens {
    val Top = Color(0xFF232631)
    val Bottom = Color(0xFF131419)
    val Sheen = Color(0xFFFFFFFF)
    val Mark = Color(0xFFFFFFFF)
    const val SheenAlpha = 0.07f
    const val MarkAlpha = 0.28f
    const val EdgeAlpha = 0.06f
}

private fun DrawScope.drawGround() {
    drawRect(
        Brush.linearGradient(
            colors = listOf(ArtworkTokens.Top, ArtworkTokens.Bottom),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )
    )
    val c = Offset(size.width * 0.18f, size.height * 0.10f)
    val r = size.maxDimension * 0.92f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                ArtworkTokens.Sheen.copy(alpha = ArtworkTokens.SheenAlpha),
                Color.Transparent
            ),
            center = c,
            radius = r
        ),
        radius = r,
        center = c
    )
}

private fun DrawScope.drawNote(m: Float, cx: Float, cy: Float) {
    val color = ArtworkTokens.Mark
    val a = ArtworkTokens.MarkAlpha
    val headR = m * 0.105f
    val headC = Offset(cx - m * 0.070f, cy + m * 0.150f)
    val stemW = m * 0.030f
    val stemX = headC.x + headR - stemW
    val stemTop = cy - m * 0.205f
    drawCircle(color = color, radius = headR, center = headC, alpha = a)
    drawRoundRect(
        color = color,
        topLeft = Offset(stemX, stemTop),
        size = Size(stemW, headC.y - stemTop),
        cornerRadius = CornerRadius(stemW * 0.5f),
        alpha = a
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(stemX, stemTop),
        size = Size(m * 0.165f, m * 0.058f),
        cornerRadius = CornerRadius(m * 0.029f),
        alpha = a
    )
}

private fun DrawScope.drawPlay(m: Float, cx: Float, cy: Float) {
    val h = m * 0.30f
    val w = m * 0.26f
    val left = cx - w * 0.42f
    val p = Path().apply {
        moveTo(left, cy - h * 0.5f)
        lineTo(left + w, cy)
        lineTo(left, cy + h * 0.5f)
        close()
    }
    drawPath(p, color = ArtworkTokens.Mark, alpha = ArtworkTokens.MarkAlpha)
}

private fun DrawScope.drawEdge() {
    val w = (size.minDimension * 0.006f).coerceAtLeast(1f)
    drawRect(
        color = ArtworkTokens.Mark.copy(alpha = ArtworkTokens.EdgeAlpha),
        style = Stroke(width = w)
    )
}

fun DrawScope.drawDefaultArtwork(isVideo: Boolean) {
    drawGround()
    val m = size.minDimension
    if (isVideo) drawPlay(m, size.width * 0.5f, size.height * 0.5f)
    else drawNote(m, size.width * 0.5f, size.height * 0.5f)
    drawEdge()
}

// Signatures unchanged so CoverArt.kt and QueueSheet.kt keep compiling.
// `id` is accepted and deliberately ignored: nothing varies per track now.

@Composable
fun GenerativeArtwork(
    id: Long,
    title: String,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false
) {
    val label = "No artwork for $title"
    Canvas(modifier.semantics { contentDescription = label }) {
        drawDefaultArtwork(isVideo)
    }
}

@Composable
fun GenerativeArtwork(item: AppMediaItem, modifier: Modifier = Modifier) =
    GenerativeArtwork(item.id, item.title, modifier, item.type == MediaType.VIDEO)
