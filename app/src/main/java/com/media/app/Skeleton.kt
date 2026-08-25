package com.media.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ============================================================================
//  SKELETON LOADING (§21)
//
//  "Use skeleton loading when content structure is known. Skeleton geometry
//  must match final UI."
//
//  So these numbers are not decorative — they are TrackRow's actual geometry:
//  50dp artwork at 12dp corner, Space.xl / 9.dp padding, two stacked text
//  lines. When the real rows arrive nothing shifts.
//
//  Widths vary per row so it reads as content loading rather than a repeating
//  pattern, but they are derived from the index — deterministic, not random,
//  so the skeleton doesn't reshuffle on every recomposition.
// ============================================================================

@Composable
private fun shimmerAlpha(): Float {
    if (LocalReducedMotion.current) return 0.35f
    val t = rememberInfiniteTransition(label = "shimmer")
    return t.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.48f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = Motion.Smooth),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    ).value
}

@Composable
private fun Bar(width: Dp, height: Dp, alpha: Float) {
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(Radius.xs))
            .background(Color.White.copy(alpha = alpha))
    )
}

/** One placeholder row, geometrically identical to TrackRow. */
@Composable
fun TrackRowSkeleton(index: Int) {
    val a = shimmerAlpha()
    // Deterministic variety: three title widths, two subtitle widths.
    val titleWidth = listOf(180.dp, 130.dp, 210.dp)[index % 3]
    val subWidth = listOf(90.dp, 120.dp)[index % 2]

    Row(
        Modifier.fillMaxWidth().padding(Space.xl, 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(50.dp).clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = a))
        )
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Bar(titleWidth, 13.dp, a)
            Spacer(Modifier.height(Space.xs + 2.dp))
            Bar(subWidth, 10.dp, a * 0.7f)
        }
        Spacer(Modifier.width(Space.sm))
        Bar(28.dp, 10.dp, a * 0.7f)
        Spacer(Modifier.width(Space.md))
        Box(
            Modifier.size(21.dp).clip(RoundedCornerShape(Radius.pill))
                .background(Color.White.copy(alpha = a * 0.7f))
        )
    }
}

/**
 * §21: "For nearly instant operations, avoid unnecessary loading indicators."
 * A count is passed rather than filling the screen — eight rows is enough to
 * read as "content is coming" without pretending to know the library size.
 */
@Composable
fun LibrarySkeleton(rows: Int = 8) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Reading your library\u2026",
            style = Typo.Secondary, color = MediaColors.CreamFaint,
            modifier = Modifier.padding(Space.xl, Space.md, Space.xl, Space.sm)
                .alpha(0.9f)
        )
        for (i in 0 until rows) TrackRowSkeleton(i)
    }
}
