package com.media.app
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

