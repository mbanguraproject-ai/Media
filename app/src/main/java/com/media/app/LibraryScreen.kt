package com.media.app
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Mic

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// Library is the structured way in - the views a flat list cannot give you -
// plus Recordings. Songs, Podcasts, Audiobooks and Video are Home's formats:
// the same list in two places is not two features, it is one feature and a
// decision nobody made. Recordings went the other way: voice notes and
// WhatsApp audio are kept, not played through, so they sit here, out of the
// way, rather than as a fifth format on Home.
private enum class LibTab(val label: Int, val icon: ImageVector) {
    ALBUMS(R.string.label_albums, Icons.Rounded.Album),
    ARTISTS(R.string.label_artists, Icons.Rounded.Person),
    RECORDINGS(R.string.pillar_recordings, Icons.Rounded.Mic)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    all: List<AppMediaItem>,
    state: PlayerState,
    header: @Composable () -> Unit,
    startAtEnd: Boolean,
    favorites: Set<Long>,
    onPlay: (List<AppMediaItem>, Int) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onMenu: (AppMediaItem) -> Unit,
    onToggleFav: (AppMediaItem) -> Unit,
    onSwipePrevious: () -> Unit,
    onSwipeNext: () -> Unit
) {
    val tabs = LibTab.values()
    val pager = rememberPagerState(initialPage = if (startAtEnd) tabs.lastIndex else 0) { tabs.size }
    val scope = rememberCoroutineScope()
    val music = remember(all) { all.filter { it.pillar == Pillar.MUSIC } }
    val albums = remember(music) { MediaRepository.albumsOf(music) }
    val artists = remember(music) { MediaRepository.artistsOf(music) }
    val recordings = remember(all) {
        // Newest first: a recording is found by when it was made.
        all.filter { it.pillar == Pillar.RECORDING }.sortedByDescending { it.dateAdded }
    }

    // Library is a primary tab destination, so it wears the same header and
    // the same bar as Home: one layout across the tabs. The title it used to
    // carry ("Library", with a back arrow) said what the bottom bar already
    // says, a row of screen spent on it.
    Column(Modifier.fillMaxSize().screenBackground().statusBarsPadding()) {
        header()
        PillTabs(
            labels = tabs.map { stringResource(it.label) },
            icons = tabs.map { it.icon },
            pager = pager
        ) { i -> scope.launch { pager.animateScrollToPage(i) } }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize()
                .tabSwipe(onPrevious = onSwipePrevious, onNext = onSwipeNext)
        ) { page ->
            when (tabs[page]) {
                LibTab.ALBUMS -> AlbumGrid(albums, onOpenAlbum)
                LibTab.ARTISTS -> ArtistList(artists, onOpenArtist)
                LibTab.RECORDINGS -> RecordingList(recordings, state, favorites, onPlay, onMenu, onToggleFav)
            }
        }
    }
}

@Composable
private fun RecordingList(
    recordings: List<AppMediaItem>,
    state: PlayerState,
    favorites: Set<Long>,
    onPlay: (List<AppMediaItem>, Int) -> Unit,
    onMenu: (AppMediaItem) -> Unit,
    onToggleFav: (AppMediaItem) -> Unit
) {
    if (recordings.isEmpty()) {
        CenterNote(stringResource(R.string.recordings_empty))
        return
    }
    LazyColumn(contentPadding = PaddingValues(top = Space.xs, bottom = bottomSafePadding(gap = 170.dp))) {
        items(recordings.size, key = { recordings[it].id }) { i ->
            val item = recordings[i]
            TrackRow(
                item = item,
                isPlaying = state.currentUri == item.uri.toString() && state.isPlaying,
                isFavorite = favorites.contains(item.id),
                onClick = { onPlay(recordings, i) },
                onLongPress = { onMenu(item) },
                onMenu = { onMenu(item) },
                onToggleFav = { onToggleFav(item) }
            )
        }
    }
}
