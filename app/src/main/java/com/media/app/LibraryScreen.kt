package com.media.app
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// §9's filter set. SONGS/ALBUMS/ARTISTS are three views of the same music
// pillar; the rest map straight to a pillar.
private enum class LibTab(val label: String, val pillar: Pillar?) {
    SONGS("Songs", Pillar.MUSIC),
    ALBUMS("Albums", Pillar.MUSIC),
    ARTISTS("Artists", Pillar.MUSIC),
    PODCASTS("Podcasts", Pillar.PODCAST),
    AUDIOBOOKS("Audiobooks", Pillar.AUDIOBOOK),
    RECORDINGS("Recordings", Pillar.RECORDING),
    VIDEO("Video", Pillar.VIDEO)
}

@Composable
fun LibraryScreen(
    all: List<AppMediaItem>,
    state: PlayerState,
    initialPillar: Pillar? = null,
    lastPlayed: Map<Long, Long> = emptyMap(),
    playCounts: Map<Long, Int> = emptyMap(),
    onPlay: (List<AppMediaItem>, Int) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onEdit: (AppMediaItem) -> Unit,
    onClose: () -> Unit
) {
    var tab by remember {
        mutableStateOf(LibTab.values().firstOrNull { it.pillar == initialPillar } ?: LibTab.SONGS)
    }
    var sort by remember { mutableStateOf(SortKey.NAME) }
    var sortOpen by remember { mutableStateOf(false) }

    val pillarItems = remember(tab, all) { all.filter { it.pillar == tab.pillar } }
    val shown = remember(pillarItems, sort, lastPlayed, playCounts) {
        pillarItems.sortedFor(sort, lastPlayed, playCounts)
    }
    // `tab` MUST be a key. remember() compares keys with equals(), and
    // Songs/Albums/Artists all filter Pillar.MUSIC — so pillarItems is
    // structurally equal across the three and the cached emptyList() was
    // being returned no matter which tab you selected.
    val albums = remember(tab, pillarItems) {
        if (tab == LibTab.ALBUMS) MediaRepository.albumsOf(pillarItems) else emptyList()
    }
    val artists = remember(tab, pillarItems) {
        if (tab == LibTab.ARTISTS) MediaRepository.artistsOf(pillarItems) else emptyList()
    }

    // Library is a primary tab destination now, so it wears the same mood
    // gradient as Home / Playlists / Search rather than a flat ink fill.
    Column(Modifier.fillMaxSize().background(moodBackground()).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(Space.sm, Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MediaColors.Cream) }
            Text("Library", style = Typo.Section, color = MediaColors.Cream,
                modifier = Modifier.weight(1f))
            // §9 sort. Only meaningful on flat track lists — albums and artists
            // have their own inherent ordering.
            if (tab != LibTab.ALBUMS && tab != LibTab.ARTISTS) {
                Box {
                    IconButton({ sortOpen = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, "Sort", tint = MediaColors.CreamDim)
                    }
                    DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }) {
                        SortKey.values().forEach { key ->
                            DropdownMenuItem(
                                text = {
                                    Text(key.label, style = Typo.Primary,
                                        color = if (key == sort) MediaColors.Accent else MediaColors.Cream)
                                },
                                onClick = { sort = key; sortOpen = false }
                            )
                        }
                    }
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(Space.xl, Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            items(LibTab.values().size) { i ->
                val f = LibTab.values()[i]
                val selected = f == tab
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) MediaColors.Cream else MediaColors.InkRaised)
                        .clickable { tab = f }
                        .padding(horizontal = Space.lg, vertical = Space.sm)
                ) {
                    Text(
                        f.label,
                        style = Typo.Label,
                        color = if (selected) MediaColors.Ink else MediaColors.CreamDim
                    )
                }
            }
        }

        when (tab) {
            LibTab.ALBUMS -> AlbumGrid(albums, onOpenAlbum)
            LibTab.ARTISTS -> ArtistList(artists, onOpenArtist)
            // Video is two different things wearing one name. A 9:16 clip
            // shown in a 16:9 row is mostly letterbox, and a landscape film in
            // a tall card is a stamp. They get separate treatments, the way
            // every video app that handles both does it.
            LibTab.VIDEO -> if (shown.isEmpty()) {
                CenterNote("No video yet")
            } else {
                val vertical = remember(shown) { shown.filter { it.isVertical } }
                val landscape = remember(shown) { shown.filterNot { it.isVertical } }
                LazyColumn(contentPadding = PaddingValues(bottom = bottomSafePadding(gap = 100.dp))) {
                    if (vertical.isNotEmpty()) {
                        item { SectionHeader("Shorts") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Space.xl),
                                horizontalArrangement = Arrangement.spacedBy(Space.md)
                            ) {
                                items(vertical.size) { i ->
                                    ShortCard(
                                        item = vertical[i],
                                        playing = state.currentUri == vertical[i].uri.toString(),
                                        onClick = { onPlay(vertical, i) },
                                        onLongPress = { onEdit(vertical[i]) }
                                    )
                                }
                            }
                        }
                    }
                    if (landscape.isNotEmpty()) {
                        if (vertical.isNotEmpty()) item { SectionHeader("Videos") }
                        items(landscape.size) { i ->
                            VideoRow(
                                item = landscape[i],
                                playing = state.currentUri == landscape[i].uri.toString(),
                                onClick = { onPlay(landscape, i) },
                                onLongPress = { onEdit(landscape[i]) }
                            )
                        }
                    }
                }
            }
            else -> if (shown.isEmpty()) {
                CenterNote("Nothing in ${tab.label.lowercase()} yet")
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = bottomSafePadding(gap = 100.dp))) {
                    items(shown.size) { idx ->
                        LibraryRow(
                            item = shown[idx],
                            isPlaying = state.currentUri == shown[idx].uri.toString() && state.isPlaying,
                            onClick = { onPlay(shown, idx) },
                            onLongPress = { onEdit(shown[idx]) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    item: AppMediaItem,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(Space.xl, Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverArt(item, Modifier.size(52.dp), corner = 8)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = Typo.Primary,
                color = MediaColors.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.artist, style = Typo.Secondary,
                color = MediaColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isPlaying) {
            PlayingEqualizer(true, MediaColors.Accent, Modifier.size(16.dp, 14.dp))
        }
    }
}


// ---------------------------------------------------------------- video -----

/**
 * Portrait clip. 9:16, browsed sideways - the shape and the gesture people
 * already expect from shorts. Title sits over the frame because a tall card
 * has nowhere else to put it without wasting height.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortCard(
    item: AppMediaItem,
    playing: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Box(
        Modifier.width(124.dp).height(220.dp)
            .clip(RoundedCornerShape(Radius.md))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
    ) {
        CoverArt(item, Modifier.fillMaxSize(), corner = 0, targetPx = 512)
        // Scrim only at the foot, so the frame stays legible.
        Box(
            Modifier.fillMaxWidth().height(88.dp).align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                    )
                )
        )
        Column(Modifier.align(Alignment.BottomStart).padding(Space.sm)) {
            Text(
                item.title, style = Typo.Tertiary,
                color = if (playing) MediaColors.Accent else Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                fmtTrack(item.durationMs), style = Typo.Micro,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Landscape clip. 16:9 thumbnail with the duration burned into the corner and
 * the title beside it - the row shape every video app converged on because it
 * shows the frame at the ratio it was shot in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    item: AppMediaItem,
    playing: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(Space.xl, Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(132.dp).aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(Radius.sm))
        ) {
            CoverArt(item, Modifier.fillMaxSize(), corner = 0, targetPx = 384)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(fmtTrack(item.durationMs), style = Typo.Micro, color = Color.White)
            }
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                item.title, style = Typo.Primary,
                color = if (playing) MediaColors.Accent else MediaColors.Cream,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (item.width > 0) {
                Spacer(Modifier.height(Space.xxs))
                Text(
                    "${item.width} \u00d7 ${item.height}",
                    style = Typo.Tertiary, color = MediaColors.CreamFaint
                )
            }
        }
    }
}
