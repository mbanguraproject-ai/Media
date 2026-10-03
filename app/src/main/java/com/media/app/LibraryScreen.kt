package com.media.app
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

// §9's filter set. SONGS/ALBUMS/ARTISTS are three views of the same music
// pillar; the rest map straight to a pillar.
// Library is the structured way in - the two views a flat list cannot give
// you. Songs, Podcasts, Audiobooks, Recordings and Video all left when the
// pillar strip landed on Home: the same list in two places is not two
// features, it is one feature and a decision nobody made.
private enum class LibTab(val label: Int) {
    ALBUMS(R.string.label_albums),
    ARTISTS(R.string.label_artists)
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
    var tab by remember { mutableStateOf(LibTab.ALBUMS) }
    val pillarItems = remember(all) { all.filter { it.pillar == Pillar.MUSIC } }
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
    Column(Modifier.fillMaxSize().screenBackground().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(Space.sm, Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MediaColors.Cream) }
            Text(stringResource(R.string.library_title), style = Typo.Section, color = MediaColors.Cream,
                modifier = Modifier.weight(1f))
            // Sorting went with the flat lists. Albums and artists
            // carry an order of their own.
        }

        TabStrip(
            labels = LibTab.values().map { stringResource(it.label) },
            selected = LibTab.values().indexOf(tab)
        ) { tab = LibTab.values()[it] }

        when (tab) {
            LibTab.ALBUMS -> AlbumGrid(albums, onOpenAlbum)
            LibTab.ARTISTS -> ArtistList(artists, onOpenArtist)
        }
    }
}
