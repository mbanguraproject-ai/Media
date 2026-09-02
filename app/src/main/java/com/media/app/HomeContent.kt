package com.media.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp

// Flat solid tile color seeded by title — clean, no gradient noise, no fallback junk.


private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when { h < 12 -> "Good Morning"; h < 17 -> "Good Afternoon"; else -> "Good Evening" }
}

// ------------------------------------------------------------------ HEADER
@Composable
fun StashHeader(
    onSearch: () -> Unit,
    onRescan: () -> Unit,
    // §16: 0 = full, 1 = compact. Driven by scroll, not by a hide/show toggle —
    // the doc explicitly rejects "distracting scroll-hide experiments".
    collapse: Float = 0f
) {
    val c = collapse.coerceIn(0f, 1f)
    Row(
        Modifier.fillMaxWidth()
            .padding(
                start = Space.xl, end = Space.lg,
                top = lerp(Space.lg, Space.sm, c),
                bottom = lerp(Space.md, Space.sm, c)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {

            // Title shrinks Display -> Section rather than cutting between two
            // styles, so the transition is continuous.
            Text(
                "Your Library",
                style = Typo.Display.copy(
                    fontSize = lerp(Typo.Display.fontSize, Typo.Section.fontSize, c),
                    lineHeight = lerp(Typo.Display.lineHeight, Typo.Section.lineHeight, c)
                ),
                color = MediaColors.Cream,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            // Identity FIRST. The greeting sat above the title, putting the
            // less useful line where the eye lands. It reads better as context
            // beneath the name - and it is still what goes on scroll.
            if (c < 0.99f) {
                Text(
                    greeting(), style = Typo.Secondary, color = MediaColors.CreamDim,
                    maxLines = 1,
                    modifier = Modifier
                        .height(lerp(18.dp, 0.dp, c))
                        .alpha((1f - c * 2f).coerceIn(0f, 1f))
                )
            }
        }
        Spacer(Modifier.width(Space.md))
        // Search lives HERE and nowhere else — it left the bottom bar so four
        // tabs can divide the width evenly instead of five crowding it.
        CircleButton(Icons.Filled.Search, "Search", onSearch)
        Spacer(Modifier.width(Space.sm))
        CircleButton(Icons.Filled.Refresh, "Rescan", onRescan)
    }
}

@Composable
private fun CircleButton(icon: ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .background(MediaColors.Fill)
            .border(1.dp, MediaColors.FillStrong, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, cd, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
    }
}

// ------------------------------------------------------------------ MOOD CHIPS
@Composable
fun MoodChips(active: Mood, onPick: (Mood) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Space.xl),
        horizontalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        items(Mood.values().toList()) { mood ->
            val selected = mood == active
            // Unselected chips carried a full border AND a fill, so five of
            // them competed with the one that mattered. Off state is now a
            // hairline with no fill; only the selected chip is solid.
            val bg by animateColorAsState(
                if (selected) mood.chipOn else Color.Transparent,
                tween(Motion.Standard, easing = Motion.Smooth), label = "chipBg"
            )
            val border by animateColorAsState(
                if (selected) Color.Transparent else MediaColors.Fill,
                tween(Motion.Standard, easing = Motion.Smooth), label = "chipBorder"
            )
            val fg by animateColorAsState(
                if (selected) Color.White else MediaColors.CreamDim,
                tween(Motion.Standard, easing = Motion.Smooth), label = "chipFg"
            )
            Box(
                Modifier.clip(RoundedCornerShape(Radius.pill))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(Radius.pill))
                    .pressScale(haptic = true) { onPick(mood) }
                    // Same padding for every chip, so "All" is no longer
                    // visually heavier than the rest just for being first.
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(mood.label, style = Typo.Label, color = fg, maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------ MOOD BANNER
@Composable
fun MoodBanner(mood: Mood, collapse: Float = 0f) {
    val msg = mood.banner ?: return
    val c = collapse.coerceIn(0f, 1f)
    // Banner is atmosphere, not navigation — it leaves entirely on collapse.
    if (c > 0.98f) return
    val icon = when (mood) {
        Mood.LATE_NIGHT -> Icons.Filled.Bedtime
        Mood.WORKOUT -> Icons.Filled.Bolt
        Mood.FOCUS -> Icons.Filled.TrackChanges
        else -> Icons.Filled.MusicNote
    }
    Row(
        Modifier.fillMaxWidth()
            .padding(
                start = Space.xl, end = Space.xl,
                top = lerp(Space.md, 0.dp, c), bottom = lerp(Space.xs, 0.dp, c)
            )
            .alpha(1f - c)
            .clip(RoundedCornerShape(Radius.md))
            .background(mood.accent.copy(alpha = 0.16f))
            .padding(horizontal = Space.lg, vertical = lerp(15.dp, 0.dp, c)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = mood.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.md))
        Text(msg, style = Typo.Label, color = MediaColors.Cream, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.MusicNote, null, tint = mood.accent.copy(alpha = 0.6f),
            modifier = Modifier.size(15.dp))
    }
}

// ------------------------------------------------------------------ STAT CARDS
data class Stat(val icon: ImageVector, val label: String, val count: Int, val tint: Color)

@Composable
fun SortSegments(selected: SortKey, onSelect: (SortKey) -> Unit) {
    // Was three stat CARDS carrying counts, sitting above two shelves and an
    // ad. Card + number reads as a statistic, and the list it reordered was a
    // screen and a half below - so tapping appeared to do nothing.
    // A segmented control, placed directly above the list it sorts, says what
    // it is without needing a label.
    val opts = listOf(
        SortKey.RECENTLY_PLAYED to "Recent",
        SortKey.NAME to "A\u2013Z",
        SortKey.MOST_PLAYED to "Most played"
    )
    Row(
        Modifier.fillMaxWidth().padding(Space.xl, Space.sm, Space.xl, Space.sm)
            .clip(RoundedCornerShape(Radius.pill))
            .background(MediaColors.FillSubtle)
            .border(1.dp, MediaColors.Fill, RoundedCornerShape(Radius.pill))
            .padding(3.dp)
    ) {
        opts.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(if (on) MediaColors.Accent else Color.Transparent)
                    .pressScale(haptic = true) { onSelect(key) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, style = Typo.Label,
                    color = if (on) Color.White else MediaColors.CreamDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
@Composable
fun CountAndShuffle(count: Int, onShuffle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(Space.xl, Space.lg, Space.xl, Space.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$count tracks", style = Typo.Secondary, color = MediaColors.CreamDim)
        Row(
            Modifier.clip(RoundedCornerShape(20.dp))
                .border(1.dp, MediaColors.Accent.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                .clickable(onClick = onShuffle)
                .padding(horizontal = Space.lg, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Shuffle, null, tint = MediaColors.Accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(Space.sm))
            Text("Shuffle", style = Typo.Label, color = MediaColors.Accent)
        }
    }
}

// ------------------------------------------------------------------ TRACK ROW
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    item: AppMediaItem,
    isPlaying: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onToggleFav: () -> Unit,
    // Same destination as the long-press. Long-press alone is invisible -
    // Play next, Add to queue, View album and Share were unreachable for
    // anyone who didn't already know to hold a row down.
    onMenu: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(Space.xl, 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // §10: rows carry real artwork. Falls back to a generative composition
        // when the file has none. No badge at this size — it would be clutter.
        CoverArt(
            item = item,
            modifier = Modifier.size(50.dp),
            corner = 12,
            targetPx = 144
        )
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = Typo.Primary,
                color = if (isPlaying) MediaColors.Accent else MediaColors.Cream,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.xxs))
            // Duration moved into the subtitle to free the trailing edge for a
            // visible menu affordance without crowding the row.
            Text("${item.artist}  \u00b7  ${fmtDur(item.durationMs)}", style = Typo.Secondary,
                color = MediaColors.CreamFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Space.sm))
        if (isPlaying) {
            PlayingEqualizer(
                playing = true, color = MediaColors.Accent,
                modifier = Modifier.size(16.dp, 14.dp)
            )
            Spacer(Modifier.width(Space.sm))
        }
        FavoriteButton(isFavorite, Modifier.size(21.dp), onToggleFav)
        if (onMenu != null) {
            // Both carry a 48dp touch target from pressScale, so without a real
            // gap the two hit areas sit shoulder to shoulder and the icons read
            // as one control.
            Spacer(Modifier.width(Space.md))
            Icon(
                Icons.Filled.MoreVert, "More options", tint = MediaColors.CreamFaint,
                modifier = Modifier.size(21.dp).pressScale(haptic = true, onClick = onMenu)
            )
        }
    }
}

private fun fmtDur(ms: Long): String {
    val s = (ms / 1000).toInt(); val m = s / 60; val sec = s % 60
    return "%d:%02d".format(m, sec)
}
