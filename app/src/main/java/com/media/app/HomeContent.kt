package com.media.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp

// Flat solid tile color seeded by title — clean, no gradient noise, no fallback junk.


// ------------------------------------------------------------------ HEADER
//
// Was a 28sp "Your library" stacked over a time-of-day greeting, with search
// and rescan beside them: two large texts and two controls shouting at the top
// of every screen. A player's header is not the product - the library is.
//
// Identity, one control, done. The mark is the launcher icon - the same bird,
// generated from the same photograph - not a second logo invented for the
// header. It is the colour asset, untinted: the bird already carries the
// accent, and tinting it would throw away the only colour on this screen that
// is not somebody's artwork.
//
// The gutter is Space.xl, not Space.lg. The rows below it were always on xl,
// so the mark used to start 8dp left of every piece of artwork under it.
//
// Rescan is gone from here entirely. It already exists at Settings > Rescan
// device, so the header was carrying a duplicate control for something you
// touch roughly once a month.
//
// The controls end where the content ends. Measured on a Redmi 10C, the mark
// started 49px from the left edge and the Shuffle pill below ended 50px from
// the right, but the search glyph stopped at 75px: about 16dp short, made of
// the 48dp touch minimum around a 38dp control (5dp), its 8dp padding, and
// the 3.5 of 24 units the Search glyph leaves empty on its right. The row now
// gives back exactly that, so the glyph's visible edge sits on the gutter and
// mirrors the mark. Touch targets stay 48dp; they overhang into the gutter.
private val HeaderGlyph = 22.dp
private val HeaderTouch = 48.dp
private val HeaderTouchInset = (HeaderTouch - HeaderGlyph) / 2
// Material's Search path ends at x = 20.49 of its 24-unit box.
private val SearchGlyphRightGap = HeaderGlyph * (3.51f / 24f)

@Composable
fun StashHeader(onSearch: () -> Unit, sharing: Boolean, onShare: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = Space.xl,
                end = (Space.xl - HeaderTouchInset - SearchGlyphRightGap).coerceAtLeast(0.dp),
                top = Space.sm, bottom = Space.sm
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.aura_mark),
            // The mark carries the name on its own now, so it has to say so.
            contentDescription = "Aura",
            modifier = Modifier.height(54.dp)
        )
        Spacer(Modifier.weight(1f))
        // Lit while something is playing elsewhere. A cast control that looks
        // the same connected and disconnected is the reason people cannot
        // tell why their phone is silent.
        Icon(
            if (sharing) Icons.Filled.CastConnected else Icons.Filled.Cast,
            if (sharing) "Playing on another device" else "Aura Share",
            tint = if (sharing) MediaColors.Accent else MediaColors.Cream,
            modifier = Modifier
                .size(HeaderTouch)
                .clip(CircleShape)
                .pressScale(haptic = true, onClick = onShare)
                .padding(HeaderTouchInset)
        )
        Icon(
            Icons.Filled.Search, "Search",
            tint = MediaColors.Cream,
            modifier = Modifier
                .size(HeaderTouch)
                .clip(CircleShape)
                .pressScale(haptic = true, onClick = onSearch)
                .padding(HeaderTouchInset)
        )
    }
}

// ------------------------------------------------------------------ PILLARS
//
// Music, Video, Podcasts and Audiobooks were four taps deep: bottom nav ->
// Library -> a tab row inside that. They are the top level of what this app
// actually holds, so they belong at the top level of the screen.
//
// Only pillars with something in them appear, and with fewer than two there
// is no strip at all: a phone with no podcasts should never see the word
// Podcasts, and a music-only library should not carry a row with one item.
//
// The underline is drawn rather than laid out. A Box sized to the label needs
// an intrinsic measurement, and intrinsics inside a scrollable row are how you
// get a crash on the one device with a long language and a large font scale.
private val PillarNames = mapOf(
    Pillar.MUSIC to ("Music" to "tracks"),
    Pillar.VIDEO to ("Video" to "videos"),
    Pillar.PODCAST to ("Podcasts" to "episodes"),
    Pillar.AUDIOBOOK to ("Audiobooks" to "audiobooks"),
    Pillar.RECORDING to ("Recordings" to "recordings")
)

fun pillarLabel(p: Pillar): String = PillarNames[p]?.first ?: "Music"
fun pillarNoun(p: Pillar): String = PillarNames[p]?.second ?: "tracks"

@Composable
fun PillarStrip(available: List<Pillar>, current: Pillar, onPick: (Pillar) -> Unit) {
    // One item is not a choice, and a row with one word in it reads as a
    // heading nobody can act on.
    if (available.size < 2) return
    TabStrip(available.map { pillarLabel(it) }, available.indexOf(current)) {
        onPick(available[it])
    }
}

/**
 * The app's ONE way of showing a set of views and which is current.
 *
 * Library used to do this with a filled pill - white when selected - which
 * made the loudest object on the screen a tab label, and put white somewhere
 * other than the play button, which is the only thing allowed to have it.
 */
@Composable
fun TabStrip(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val accent = MediaColors.Accent
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.xl),
        horizontalArrangement = Arrangement.spacedBy(Space.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            val isOn = index == selected
            Text(
                label,
                style = Typo.Primary,
                color = if (isOn) MediaColors.Cream else MediaColors.CreamFaint,
                maxLines = 1,
                modifier = Modifier
                    .clickable { onPick(index) }
                    .drawBehind {
                        if (isOn) {
                            val t = 2.dp.toPx()
                            drawRoundRect(
                                color = accent,
                                topLeft = Offset(0f, size.height - t),
                                size = Size(size.width, t),
                                cornerRadius = CornerRadius(t / 2f)
                            )
                        }
                    }
                    .padding(top = Space.xs, bottom = Space.sm)
            )
        }
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
            .padding(horizontal = Space.xl, vertical = Space.sm)
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
        Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.sm, bottom = Space.xs),
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
fun CountAndShuffle(count: Int, noun: String = "tracks", onShuffle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(Space.xl, Space.lg, Space.xl, Space.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$count $noun", style = Typo.Secondary, color = MediaColors.CreamDim)
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
    val q = LocalQuality.current
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = Space.xl, vertical = q.rowPadV),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // §10: rows carry real artwork. Falls back to a generative composition
        // when the file has none. No badge at this size — it would be clutter.
        CoverArt(
            item = item,
            modifier = Modifier.size(q.rowArt),
            corner = q.rowArtCorner,
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
