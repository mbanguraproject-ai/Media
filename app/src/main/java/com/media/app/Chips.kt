package com.media.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ============================================================================
//  CHIPS
//
//  The app's way of showing a set of filters or choices and which one is
//  current: Sound's presets, Settings' choices. A tab's own views - the
//  pages a swipe moves between - use the SegmentedBar below instead.
//
//  A pill per choice. The current one is filled with the accent and set in
//  semibold; the rest sit on a faint fill with a hairline edge, so the row
//  reads as a set of buttons, not a line of text with one word underlined -
//  which is what it was, and why it looked dated next to every other player.
//  Colour changes animate, a tap gives the press feedback the rest of the app
//  gives, and the touch target is 48dp however short the chip.
// ============================================================================

private val ChipHeight = 36.dp
private val ChipIcon = 16.dp

@Composable
fun ViaChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    val fill by animateColorAsState(
        if (selected) MediaColors.Accent else MediaColors.FillSubtle,
        tween(Motion.Standard), label = "chipFill"
    )
    val ink by animateColorAsState(
        if (selected) MediaColors.OnAccent else MediaColors.CreamDim,
        tween(Motion.Standard), label = "chipInk"
    )
    val edge by animateColorAsState(
        if (selected) MediaColors.Accent else MediaColors.Fill,
        tween(Motion.Standard), label = "chipEdge"
    )
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        modifier
            .semantics {
                this.selected = selected
                role = Role.Tab
            }
            .pressScale(haptic = true, onClick = onClick)
            .height(ChipHeight)
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .padding(start = if (icon != null) 12.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(ChipIcon))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = Typo.Label.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A scrolling row of chips, one per label, the [selected] one filled. [icons]
 * is optional and matched to labels by position.
 */
@Composable
fun ChipRow(
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    icons: List<ImageVector?> = emptyList(),
    onPick: (Int) -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.xl),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            ViaChip(
                label = label,
                selected = index == selected,
                icon = icons.getOrNull(index),
                onClick = { onPick(index) }
            )
        }
    }
}

// ============================================================================
//  SEGMENTED BAR
//
//  The top-level views of a tab, and the pages a sideways swipe moves
//  between: Home's Music / Video / Podcasts / Audiobooks, Library's Albums /
//  Artists / Recordings, Playlists' Mine / Smart. One control on all three,
//  so the tabs read as one app with one layout: the header, this bar, the
//  pages.
//
//  Every choice is always on screen. A scrolling chip row hid Audiobooks
//  off the right edge on most phones, and a view you cannot see is a view
//  you do not know is there. Equal segments, the icon over a short label,
//  fit four on a 320dp screen in every shipped language.
//
//  The lit segment slides WITH the finger, not after it: the bar reads the
//  pager's live position, so halfway through a swipe it sits halfway
//  between the two views, and each label's colour crosses over the same way.
// ============================================================================

private val SegmentBarHeight = 50.dp
private val SegmentInset = 3.dp

@Composable
fun SegmentedBar(
    labels: List<String>,
    icons: List<ImageVector>,
    pager: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit
) {
    val shape = RoundedCornerShape(Radius.md)
    val inner = RoundedCornerShape(Radius.md - SegmentInset)
    val accent = MediaColors.Accent
    val onAccent = MediaColors.OnAccent
    val idle = MediaColors.CreamDim
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xl, vertical = Space.xs)
            .height(SegmentBarHeight)
            .clip(shape)
            .background(MediaColors.FillSubtle)
            .border(1.dp, MediaColors.Fill, shape)
            .padding(SegmentInset)
    ) {
        val count = labels.size.coerceAtLeast(1)
        val segment = maxWidth / count
        // Read in the layout phase: the indicator moves every frame of a
        // swipe without recomposing the bar.
        androidx.compose.foundation.layout.Box(
            Modifier
                .offset {
                    val at = (pager.currentPage + pager.currentPageOffsetFraction)
                        .coerceIn(0f, (count - 1).toFloat())
                    androidx.compose.ui.unit.IntOffset((segment.toPx() * at).toInt(), 0)
                }
                .width(segment)
                .fillMaxHeight()
                .clip(inner)
                .background(accent)
        )
        val position = pager.currentPage + pager.currentPageOffsetFraction
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                // 1 on the lit segment, 0 a whole segment away or more.
                val lit = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
                val ink = androidx.compose.ui.graphics.lerp(idle, onAccent, lit)
                androidx.compose.foundation.layout.Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(inner)
                        .semantics {
                            this.selected = pager.currentPage == i
                            role = Role.Tab
                        }
                        .clickable(
                            interactionSource = androidx.compose.runtime.remember {
                                androidx.compose.foundation.interaction.MutableInteractionSource()
                            },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                            )
                            onPick(i)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    icons.getOrNull(i)?.let {
                        Icon(it, null, tint = ink, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        label,
                        style = Typo.Tertiary.copy(
                            fontWeight = if (lit > 0.5f) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Space.xs)
                    )
                }
            }
        }
    }
}
