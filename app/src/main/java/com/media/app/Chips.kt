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
//  pages a swipe moves between - use PillTabs below instead.
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
//  PILL TABS
//
//  The top-level views of a tab, and the pages a sideways swipe moves
//  between: Home's Music / Video / Podcasts / Audiobooks, Library's Albums /
//  Artists / Recordings, Playlists' Mine / Smart. Pills with their icons, as
//  the app's views have always been - one row on all three tabs, right under
//  the header on the same gutter, so the header and its views read as one
//  block.
//
//  Better than the chip row it replaces in three ways:
//  * Every view is on screen. That row scrolled, and Audiobooks sat off the
//    right edge on most phones. Here the labels are measured and the pills'
//    padding is chosen to fit them all across the screen, between a
//    comfortable 14dp and a tight 8dp, and the type steps down 1sp if that
//    is still not enough. Only a very narrow phone in a long language still
//    scrolls, and then the current pill is kept in sight.
//  * Solid pills, no outlines: a quiet surface for the rest and the accent
//    for the current one. Hairline edges around every pill drew a box round
//    each word.
//  * The accent follows the finger. The fill is read from the pager's live
//    position, so mid-swipe it is passing from one pill to the next.
//
//  Every label is set in the same weight so a pill never changes width as
//  it lights up. Chips do not vibrate on a tap - the pill lighting up is the
//  answer - unlike the app's buttons, which do (pressScale's haptic).
// ============================================================================

private val PillHeight = 34.dp
private val PillGap = 6.dp
private val PillIcon = 15.dp
private val PillIconGap = 5.dp
private val PillPadMax = 14.dp
private val PillPadMin = 8.dp

@Composable
fun PillTabs(
    labels: List<String>,
    icons: List<ImageVector>,
    pager: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit
) {
    val accent = MediaColors.Accent
    val onAccent = MediaColors.OnAccent
    val rest = MediaColors.FillStrong
    val ink = MediaColors.Cream
    val full = Typo.Label.copy(fontWeight = FontWeight.SemiBold)
    val gutter = Space.xl
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val scroll = rememberScrollState()
    val position = pager.currentPage + pager.currentPageOffsetFraction

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().padding(top = Space.xxs, bottom = Space.xs)
    ) {
        // The type and padding that fit every pill across this width: the
        // labels themselves, an icon each, the gaps and the gutters, and
        // what is left shared out as padding.
        val (style, pad) = androidx.compose.runtime.remember(labels, maxWidth, full, density) {
            with(density) {
                val fixed = (PillIcon + PillIconGap).toPx() * labels.size +
                    PillGap.toPx() * (labels.size - 1) + gutter.toPx() * 2
                fun padFor(st: androidx.compose.ui.text.TextStyle): Float {
                    val text = labels.sumOf { measurer.measure(it, st).size.width }.toFloat()
                    return (maxWidth.toPx() - fixed - text) / (labels.size * 2)
                }
                val roomy = padFor(full)
                if (roomy >= PillPadMin.toPx()) {
                    full to roomy.coerceAtMost(PillPadMax.toPx()).toDp()
                } else {
                    val small = full.copy(fontSize = full.fontSize * (12f / 13f), lineHeight = full.lineHeight * (12f / 13f))
                    small to padFor(small).coerceIn(PillPadMin.toPx(), PillPadMax.toPx()).toDp()
                }
            }
        }
        // Only matters where the row still overflows.
        androidx.compose.runtime.LaunchedEffect(pager.settledPage, scroll.maxValue) {
            if (scroll.maxValue == 0) return@LaunchedEffect
            val share = pager.settledPage.toFloat() / (labels.size - 1).coerceAtLeast(1)
            scroll.animateScrollTo((scroll.maxValue * share).toInt())
        }
        Row(
            Modifier.horizontalScroll(scroll).padding(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(PillGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            labels.forEachIndexed { i, label ->
                // 1 on the current pill, 0 a whole page away or more.
                val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
                val fill = androidx.compose.ui.graphics.lerp(rest, accent, near)
                val color = androidx.compose.ui.graphics.lerp(ink, onAccent, near)
                Row(
                    Modifier
                        .semantics {
                            this.selected = pager.currentPage == i
                            role = Role.Tab
                        }
                        .pressScale { onPick(i) }
                        .height(PillHeight)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(fill)
                        .padding(horizontal = pad),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    icons.getOrNull(i)?.let {
                        Icon(it, null, tint = color, modifier = Modifier.size(PillIcon))
                        Spacer(Modifier.width(PillIconGap))
                    }
                    Text(label, style = style, color = color, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
