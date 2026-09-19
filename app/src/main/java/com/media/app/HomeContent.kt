package com.media.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Flat solid tile color seeded by title — clean, no gradient noise, no fallback junk.


// ------------------------------------------------------------------ HEADER
//
// Was a 28sp screen title stacked over a time-of-day greeting, with search and
// rescan beside them: two large texts and two controls shouting at the top of
// every screen. A player's header is not the product - the library is.
//
// Identity, one control, done. The mark is the launcher icon (three concentric
// rings, the Aura mark) rather than a second logo invented for the header. It
// carries ~25% built-in padding, so 32dp of box renders ~16dp of ring, which
// is what pairs with the wordmark's cap height.
//
// Rescan is gone from here entirely. It already exists at Settings > Rescan
// device, so the header carried a duplicate control for something you touch
// roughly once a month.
@Composable
fun StashHeader(onSearch: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.lg, end = Space.lg, top = Space.md, bottom = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,       // the wordmark beside it reads it out
            tint = MediaColors.Accent,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            "Aura",
            style = Typo.Section.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            ),
            color = MediaColors.Cream,
            maxLines = 1
        )
        Spacer(Modifier.weight(1f))
        CircleButton(Icons.Filled.Search, "Search", onClick = onSearch)
    }
}

@Composable
private fun CircleButton(icon: ImageVector, cd: String, onClick: () -> Unit) {
    // `spinning` went with the rescan button - nothing in the header animates
    // any more, which is the point of calling it calm.
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(1.dp, MediaColors.Fill, CircleShape)
            .pressScale(haptic = true, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, cd,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(16.dp)
        )
    }
}

// ------------------------------------------------------------------ RESUME
//
// The one genuinely actionable thing the six home shelves offered was the
// track you did not finish. It is one slim bar now, not a rail of cards under
// a 19sp header.
@Composable
fun ResumeBar(item: AppMediaItem, progress: Float, onPlay: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.sm)
            .clip(RoundedCornerShape(Radius.md))
            .background(MediaColors.FillSubtle)
            .border(1.dp, MediaColors.Fill, RoundedCornerShape(Radius.md))
            .pressScale(haptic = true, onClick = onPlay)
            .padding(Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(item, Modifier.size(44.dp), corner = 10, targetPx = 144)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text("RESUME", style = Typo.Micro, color = MediaColors.Accent)
            Spacer(Modifier.height(3.dp))
            Text(
                item.title, style = Typo.Primary, color = MediaColors.Cream,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(7.dp))
            Box(
                Modifier.fillMaxWidth().height(2.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(MediaColors.Fill)
            ) {
                Box(
                    Modifier.fillMaxWidth(progress).fillMaxHeight()
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(MediaColors.Accent)
                )
            }
        }
        Spacer(Modifier.width(Space.md))
        Icon(
            Icons.Filled.PlayArrow, null, tint = MediaColors.Cream,
            modifier = Modifier.size(26.dp)
        )
    }
}

// ------------------------------------------------------------------ FILTER
//
// Replaces the five permanent mood chips. Those sat under the header on every
// launch, recolouring the app and asking to be pressed; four of the five did
// nothing most of the time. Collections live in Playlists now, and this row
// exists ONLY while one of them is actually filtering the list.
@Composable
fun FilterBar(label: String, count: Int, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.sm, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = Typo.Label, color = MediaColors.Cream)
        Spacer(Modifier.width(Space.sm))
        Text("$count tracks", style = Typo.Secondary, color = MediaColors.CreamFaint)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .pressScale(haptic = true, onClick = onClear)
                .padding(horizontal = Space.md, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Close, null, tint = MediaColors.CreamDim,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(Space.xs))
            Text("Clear", style = Typo.Label, color = MediaColors.CreamDim)
        }
    }
}

// ----------------------------------------------------------------- SECTION
//
// Moved here from HomeSections.kt, which existed to build the home shelves.
// With the shelves gone this was the only live thing left in a 269-line file,
// so the file went and the function stayed. Still used by Library and Search.
@Composable
fun SectionHeader(title: String) {
    // Was Typo.Section at full Cream, which competed with the artwork it was
    // labelling. A section heading is a signpost, not a headline.
    Text(
        title,
        style = Typo.Section.copy(fontWeight = FontWeight.Medium),
        color = MediaColors.CreamDim,
        modifier = Modifier.padding(Space.xl, Space.xl, Space.xl, Space.sm)
    )
}

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
