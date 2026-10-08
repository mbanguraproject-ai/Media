package com.media.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
//  The app's ONE way of showing a set of views or filters and which one is
//  current: Home's Music / Video / Podcasts, Library's Albums / Artists,
//  Playlists' Mine / Smart, Sound's presets.
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
