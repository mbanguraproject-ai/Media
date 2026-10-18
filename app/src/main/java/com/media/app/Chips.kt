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
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.sp
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
//  pages a swipe moves between - use ViewTabs below instead.
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
            .pressScale(onClick = onClick)
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
//  VIEW TABS
//
//  The top-level views of a tab, and the pages a sideways swipe moves
//  between: Home's Music / Video / Podcasts / Audiobooks, Library's Albums /
//  Artists / Recordings, Playlists' Mine / Smart. One control on all three,
//  so the tabs read as one app with one layout: the header, these, the
//  pages.
//
//  Set in type, not boxed. 5.9 shipped a boxed segmented control with a
//  filled teal block: a widget dropped under the header rather than part of
//  it, and the loudest thing on a screen whose colour belongs to the
//  artwork. These are words on the header's own gutter - the first starts
//  where the Via mark starts - the current one in full cream, the rest
//  receding, with one accent line under the current word and a hairline
//  across the screen that closes the header off from the list.
//
//  The line moves WITH the finger: drawn from the pager's live position, so
//  halfway through a swipe it sits halfway between the two words, and it
//  stretches or shrinks to each word's own width on the way. Its soft light
//  rising off it is the only glow on the screen.
//
//  Every view is always on screen: the words size to themselves and fit
//  four across a 360dp phone in the shipped languages; a longer one scrolls
//  the row rather than cutting a word, and the current view is kept in
//  sight. No vibration on a tap (Interactions.kt), and no ripple: the word
//  lighting up is the answer.
// ============================================================================

private val TabRowHeight = 44.dp
private val TabGap = 22.dp
private val LineHeight = 2.dp
private val GlowHeight = 14.dp

@Composable
fun ViewTabs(
    labels: List<String>,
    pager: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit
) {
    val accent = MediaColors.Accent
    val lit = MediaColors.Cream
    val idle = MediaColors.CreamFaint
    val hairline = MediaColors.Fill
    val gutter = Space.xl
    val scroll = rememberScrollState()
    // Each word's left edge and width in the row, from layout. Drawing reads
    // them, so the line is placed in the draw phase without recomposing.
    val starts = androidx.compose.runtime.remember(labels.size) {
        androidx.compose.runtime.mutableStateListOf<Float>().apply { repeat(labels.size) { add(0f) } }
    }
    val widths = androidx.compose.runtime.remember(labels.size) {
        androidx.compose.runtime.mutableStateListOf<Float>().apply { repeat(labels.size) { add(0f) } }
    }
    // A row too long for the screen keeps the current word in sight, a
    // gutter in from the edge.
    val gutterPx = with(androidx.compose.ui.platform.LocalDensity.current) { gutter.toPx() }
    androidx.compose.runtime.LaunchedEffect(pager.settledPage, scroll.maxValue) {
        if (scroll.maxValue == 0) return@LaunchedEffect
        val i = pager.settledPage.coerceIn(0, labels.lastIndex)
        scroll.animateScrollTo((starts[i] - gutterPx).toInt().coerceIn(0, scroll.maxValue))
    }
    val position = pager.currentPage + pager.currentPageOffsetFraction

    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth().height(TabRowHeight)) {
        // The hairline across the whole screen, under the words.
        androidx.compose.foundation.layout.Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(0.5.dp).background(hairline)
        )
        Row(
            Modifier
                .fillMaxHeight()
                .horizontalScroll(scroll)
                .drawBehind {
                    if (widths.isEmpty() || widths.all { it == 0f }) return@drawBehind
                    val at = (pager.currentPage + pager.currentPageOffsetFraction)
                        .coerceIn(0f, (labels.size - 1).toFloat())
                    val from = kotlin.math.floor(at).toInt()
                    val to = (from + 1).coerceAtMost(labels.lastIndex)
                    val t = at - from
                    val x = starts[from] + (starts[to] - starts[from]) * t
                    val w = widths[from] + (widths[to] - widths[from]) * t
                    val line = LineHeight.toPx()
                    val glow = GlowHeight.toPx()
                    // Light rising off the line, fading out upwards.
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to accent.copy(alpha = 0f),
                            1f to accent.copy(alpha = 0.16f),
                            startY = size.height - line - glow,
                            endY = size.height - line
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(x, size.height - line - glow),
                        size = androidx.compose.ui.geometry.Size(w, glow)
                    )
                    drawRoundRect(
                        color = accent,
                        topLeft = androidx.compose.ui.geometry.Offset(x, size.height - line),
                        size = androidx.compose.ui.geometry.Size(w, line),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(line / 2f)
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(gutter))
            labels.forEachIndexed { i, label ->
                // 1 on the current word, 0 a whole page away or more.
                val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
                val ink = androidx.compose.ui.graphics.lerp(idle, lit, near)
                Text(
                    label,
                    style = Typo.Primary.copy(
                        fontWeight = if (near > 0.5f) FontWeight.SemiBold else FontWeight.Medium,
                        letterSpacing = (-0.1).sp
                    ),
                    color = ink,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .onPlaced { c ->
                            starts[i] = c.positionInParent().x
                            widths[i] = c.size.width.toFloat()
                        }
                        .semantics {
                            this.selected = pager.currentPage == i
                            role = Role.Tab
                        }
                        .clickable(
                            interactionSource = androidx.compose.runtime.remember {
                                androidx.compose.foundation.interaction.MutableInteractionSource()
                            },
                            indication = null
                        ) { onPick(i) }
                        // The touch area runs the row's full height.
                        .padding(vertical = 12.dp)
                )
                if (i < labels.lastIndex) Spacer(Modifier.width(TabGap))
            }
            Spacer(Modifier.width(gutter))
        }
    }
}
