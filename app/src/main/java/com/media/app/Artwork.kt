package com.media.app

import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

// ============================================================================
//  DEFAULT ARTWORK - ONE APPEARANCE, ONE MARK PER KIND
//
//  What a track with no cover shows: a slate tile, a soft glow of the brand
//  teal, and a mark for what the file is.
//
//  The marks are Google's Material Rounded icons (Apache 2.0, already in the
//  app through material-icons-extended): the single note for music, the
//  podcast mark, an open book for audiobooks, a microphone for recordings, a
//  play triangle for video - the same set the Home chips use. 5.1 drew its
//  own beamed notes from circles and bars; they held up in a list and looked
//  crude at Now Playing size, next to other players' crisp glyphs. These are
//  drawn by type designers and stay sharp at any size.
//
//  ONE drawing for every surface: the Compose tile in the app and the bitmap
//  the notification and lock screen get (ArtworkBitmap.kt) both run
//  drawDefaultArtwork, so they cannot drift apart again - which is exactly
//  what happened in 5.1, when the notification kept the 4.x note.
// ============================================================================

object ArtworkTokens {
    val Top = Color(0xFF2C3140)
    val Bottom = Color(0xFF171A22)
    val Glow = Color(0xFF2DD4BF)
    val MarkTop = Color(0xFFFFFFFF)
    val MarkBottom = Color(0xFFBFEEE6)
    const val GlowAlpha = 0.20f
    const val MarkTopAlpha = 0.88f
    const val MarkBottomAlpha = 0.66f
    const val EdgeAlpha = 0.07f
    /** The icon's 24-unit box against the tile's short side. */
    const val MarkScale = 0.46f
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

private fun iconFor(kind: ArtworkKind): ImageVector = when (kind) {
    ArtworkKind.MUSIC -> Icons.Rounded.MusicNote
    ArtworkKind.PODCAST -> Icons.Rounded.Podcasts
    ArtworkKind.AUDIOBOOK -> Icons.Rounded.AutoStories
    ArtworkKind.RECORDING -> Icons.Rounded.Mic
    ArtworkKind.VIDEO -> Icons.Rounded.PlayArrow
}

/**
 * The icon's outline as one Path in its 24-unit box. Built fresh for each
 * caller (a few dozen path commands): the notification draws on a worker
 * thread and the app on the main one, and a Path is not shared across them.
 */
fun artworkMark(kind: ArtworkKind): Path {
    val out = Path()
    fun walk(group: VectorGroup) {
        for (node in group) when (node) {
            is VectorPath -> out.addPath(PathParser().addPathNodes(node.pathData).toPath())
            is VectorGroup -> walk(node)
        }
    }
    walk(iconFor(kind).root)
    return out
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

private fun DrawScope.drawEdge() {
    val w = (size.minDimension * 0.01f).coerceAtLeast(1f)
    drawRect(color = Color.White.copy(alpha = ArtworkTokens.EdgeAlpha), style = Stroke(width = w))
}

/** The whole tile. [mark] is artworkMark(kind), passed in so a list can keep it. */
fun DrawScope.drawDefaultArtwork(kind: ArtworkKind, mark: Path = artworkMark(kind)) {
    drawGround()
    val m = size.minDimension * ArtworkTokens.MarkScale
    val k = m / 24f
    translate(center.x - m / 2f, center.y - m / 2f) {
        scale(k, k, pivot = Offset.Zero) {
            // In the icon's own 24 units: the glyphs span about 3..21.
            drawPath(
                mark,
                Brush.verticalGradient(
                    listOf(
                        ArtworkTokens.MarkTop.copy(alpha = ArtworkTokens.MarkTopAlpha),
                        ArtworkTokens.MarkBottom.copy(alpha = ArtworkTokens.MarkBottomAlpha)
                    ),
                    startY = 3f, endY = 21f
                )
            )
        }
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
    val mark = remember(kind) { artworkMark(kind) }
    Canvas(modifier.semantics { contentDescription = label }) {
        drawDefaultArtwork(kind, mark)
    }
}

@Composable
fun GenerativeArtwork(item: AppMediaItem, modifier: Modifier = Modifier) =
    GenerativeArtwork(item.id, item.title, modifier, artworkKindOf(item))
