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
    onPlaylists: () -> Unit,
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
            // Greeting is the first thing to go: it is context, not identity.
            if (c < 0.99f) {
                Text(
                    greeting(), style = Typo.Secondary, color = MediaColors.CreamDim,
                    maxLines = 1,
                    modifier = Modifier
                        .height(lerp(18.dp, 0.dp, c))
                        .alpha((1f - c * 2f).coerceIn(0f, 1f))
                )
                Spacer(Modifier.height(lerp(Space.xxs, 0.dp, c)))
            }
            // Title shrinks Display -> Section rather than cutting between two
            // styles, so the transition is continuous.
            Text(
                "Your Media",
                style = Typo.Display.copy(
                    fontSize = lerp(Typo.Display.fontSize, Typo.Section.fontSize, c),
                    lineHeight = lerp(Typo.Display.lineHeight, Typo.Section.lineHeight, c)
                ),
                color = MediaColors.Cream,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(Space.md))
        CircleButton(Icons.Filled.QueueMusic, "Playlists", onPlaylists)
        Spacer(Modifier.width(Space.sm))
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
            Box(
                Modifier.clip(RoundedCornerShape(22.dp))
                    .background(if (selected) mood.chipOn else MediaColors.FillSubtle)
                    .border(1.dp,
                        if (selected) Color.Transparent else MediaColors.Fill,
                        RoundedCornerShape(22.dp))
                    .clickable { onPick(mood) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(mood.label, style = Typo.Label,
                    color = if (selected) Color.White else MediaColors.CreamDim)
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
fun StatCards(recentlyPlayed: Int, allTracks: Int, favorites: Int) {
    // Labels match what the numbers actually are. "Most Played" was the row
    // count of a LIMIT 10 history query (so it maxed out at 10) and "Recently
    // Added" was simply the total track count.
    val stats = listOf(
        Stat(Icons.Filled.AccessTime, "Recently Played", recentlyPlayed, Color(0xFF2DD4BF)),
        Stat(Icons.Filled.LibraryMusic, "All Tracks", allTracks, Color(0xFF2DD4BF)),
        Stat(Icons.Filled.Favorite, "Favorites", favorites, MediaColors.Favorite),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = Space.xl),
        horizontalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        items(stats) { st ->
            Row(
                Modifier.width(205.dp).clip(RoundedCornerShape(16.dp))
                    .background(MediaColors.FillSubtle)
                    .border(1.dp, MediaColors.Fill, RoundedCornerShape(16.dp))
                    .padding(Space.lg, 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                        .background(st.tint.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) { Icon(st.icon, null, tint = st.tint, modifier = Modifier.size(19.dp)) }
                Spacer(Modifier.width(Space.md))
                Column {
                    Text(st.label, style = Typo.Primary, color = MediaColors.Cream,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${st.count}", style = Typo.Section, color = st.tint)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ COUNT + SHUFFLE
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
    onToggleFav: () -> Unit
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
            targetPx = 144,
            showBadge = false
        )
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = Typo.Primary,
                color = if (isPlaying) MediaColors.Accent else MediaColors.Cream,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.xxs))
            Text(item.artist, style = Typo.Secondary,
                color = MediaColors.CreamFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Space.sm))
        // §10: an animated equalizer replaces the duration on the playing row.
        if (isPlaying) {
            PlayingEqualizer(
                playing = true, color = MediaColors.Accent,
                modifier = Modifier.size(16.dp, 14.dp)
            )
        } else {
            Text(fmtDur(item.durationMs), style = Typo.Tertiary, color = MediaColors.CreamFaint)
        }
        Spacer(Modifier.width(Space.md))
        FavoriteButton(isFavorite, Modifier.size(21.dp), onToggleFav)
    }
}

private fun fmtDur(ms: Long): String {
    val s = (ms / 1000).toInt(); val m = s / 60; val sec = s % 60
    return "%d:%02d".format(m, sec)
}
