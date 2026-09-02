package com.media.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ============================================================================
//  ADAPTIVE HOME SECTIONS (§8, §40)
//
//  "Only display sections that contain useful content. Do not show empty
//  sections. The home screen should adapt to the user."
//
//  So sections are BUILT, not declared. Each has a minimum item count and
//  simply doesn't exist below it — a shelf holding one card reads as broken,
//  not as personalised. A fresh library shows none of these; the home screen
//  fills in as listening history accumulates (§40).
// ============================================================================

private const val SHELF_LIMIT = 12

sealed interface HomeSection {
    val title: String

    data class Tracks(override val title: String, val items: List<AppMediaItem>) : HomeSection
    data class Albums(override val title: String, val items: List<Album>) : HomeSection
}

/**
 * Order matters: the most actionable thing (something you left unfinished)
 * comes first, then recency, then taste, then discovery.
 */
fun buildHomeSections(
    music: List<AppMediaItem>,
    favorites: Set<Long>,
    lastPlayed: Map<Long, Long>,
    playCounts: Map<Long, Int>,
    positions: Map<Long, PlaybackPosition>
): List<HomeSection> {
    if (music.isEmpty()) return emptyList()
    val byId = music.associateBy { it.id }
    val sections = mutableListOf<HomeSection>()

    // 1. Continue listening — genuinely mid-track, not "barely started" and not
    //    "basically finished". One item is still worth showing here.
    val continuing = positions.values
        .filter { p ->
            p.durationMs > 0 &&
                p.positionMs > p.durationMs * 0.05 &&
                p.positionMs < p.durationMs * 0.95
        }
        .sortedByDescending { it.updatedAt }
        .mapNotNull { byId[it.mediaId] }
        .take(SHELF_LIMIT)
    if (continuing.isNotEmpty()) sections += HomeSection.Tracks("Continue listening", continuing)

    val continuingIds = continuing.map { it.id }.toSet()

    // 2. Recently played, minus anything already offered above.
    val recent = lastPlayed.entries
        .sortedByDescending { it.value }
        .mapNotNull { byId[it.key] }
        .filter { it.id !in continuingIds }
        .take(SHELF_LIMIT)
    if (recent.size >= 3) sections += HomeSection.Tracks("Recently played", recent)

    // 3. Favourites.
    val faves = music.filter { it.id in favorites }.take(SHELF_LIMIT)
    if (faves.size >= 3) sections += HomeSection.Tracks("Your favourites", faves)

    // 4. Most played. Threshold of 2 plays: playing something once is not a
    //    preference, and a "most played" shelf of one-time plays is noise.
    val most = playCounts.entries
        .filter { it.value >= 2 }
        .sortedByDescending { it.value }
        .mapNotNull { byId[it.key] }
        .take(SHELF_LIMIT)
    if (most.size >= 3) sections += HomeSection.Tracks("Most played", most)

    // 5. Recently added — only meaningful once the library is big enough that
    //    "recent" is a smaller set than "all".
    if (music.size >= 20) {
        val added = music.sortedByDescending { it.dateAdded }.take(SHELF_LIMIT)
        sections += HomeSection.Tracks("Recently added", added)
    }

    // 6. Albums you keep coming back to — ranked by total plays across tracks.
    val albums = MediaRepository.albumsOf(music)
        .map { album -> album to album.tracks.sumOf { playCounts[it.id] ?: 0 } }
        .filter { it.second >= 3 && it.first.name != UNKNOWN_ALBUM }
        .sortedByDescending { it.second }
        .map { it.first }
        .take(SHELF_LIMIT)
    if (albums.size >= 2) sections += HomeSection.Albums("Albums you keep coming back to", albums)

    return sections
}

// ---------------------------------------------------------------- shelves ---

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
fun TrackShelf(
    items: List<AppMediaItem>,
    currentUri: String?,
    // Null = no pulse. Only the ONE playing card reacts; every other card in
    // every shelf stays completely still.
    beat: BeatState? = null,
    onPlay: (Int) -> Unit,
    onLongPress: (AppMediaItem) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Space.xl),
        horizontalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        items(items.size) { i ->
            val item = items[i]
            val playing = currentUri == item.uri.toString()
            ShelfCard(
                title = item.title,
                subtitle = item.artist,
                highlighted = playing,
                // Half the player's swing: at 132dp a 10% scale would collide
                // with its neighbours. The ring carries the impact instead.
                pulse = if (playing) beat else null,
                art = {
                    CoverArt(item, Modifier.fillMaxSize(), corner = 14,
                        targetPx = 384)
                },
                onClick = { onPlay(i) },
                onLongClick = { onLongPress(item) }
            )
        }
    }
}

@Composable
fun AlbumShelf(items: List<Album>, onOpen: (Album) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Space.xl),
        horizontalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        items(items.size) { i ->
            val album = items[i]
            ShelfCard(
                title = album.name,
                subtitle = album.artist,
                highlighted = false,
                art = {
                    album.tracks.firstOrNull()?.let {
                        CoverArt(it, Modifier.fillMaxSize(), corner = 14,
                            targetPx = 384)
                    }
                },
                onClick = { onOpen(album) },
                onLongClick = null
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCard(
    title: String,
    subtitle: String,
    highlighted: Boolean,
    pulse: BeatState? = null,
    art: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val level = pulse?.level ?: 0f
    val accent = MediaColors.Accent
    Column(
        Modifier.width(132.dp).then(
            if (onLongClick != null)
                Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            else Modifier.clickable(onClick = onClick)
        )
    ) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            if (pulse != null) {
                // Rings sit inside the card's own bounds so a hit can't bleed
                // over the neighbouring cards in the row.
                val px = with(LocalDensity.current) { 132.dp.toPx() }
                BeatRings(
                    beat = pulse, color = accent, strength = 0.75f,
                    centerPx = Offset(px / 2f, px / 2f),
                    baseRadiusPx = px / 2f,
                    modifier = Modifier.fillMaxSize().clearAndSetSemantics { }
                )
            }
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val s = 1f + level * 0.05f
                        scaleX = s; scaleY = s
                    }
                    // Artwork sat flat on the background. A shadow lifts the
                    // cover off the surface without adding any chrome.
                    // clip=false so the shadow renders OUTSIDE the bounds.
                    .shadow(
                        elevation = Elevation.mid,
                        shape = RoundedCornerShape(14.dp),
                        clip = false,
                        ambientColor = Color.Black,
                        spotColor = Color.Black
                    )
            ) { art() }
        }
        Spacer(Modifier.height(Space.md))
        Text(
            title, style = Typo.Primary,
            color = if (highlighted) MediaColors.Accent else MediaColors.Cream,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            subtitle, style = Typo.Secondary, color = MediaColors.CreamFaint,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
