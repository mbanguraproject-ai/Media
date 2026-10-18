package com.media.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

// ============================================================================
//  SEARCH (§19)
//
//  "Search must be extremely fast. When opened, focus immediately."
//
//  Matching runs against a PRE-LOWERCASED index built once per library change,
//  not per keystroke. The old version called .contains(query, true) twice per
//  item on every character typed, which case-folds the whole library each
//  time — fine at 200 tracks, not at §38's 10,000.
// ============================================================================

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    all: List<AppMediaItem>,
    onPlay: (List<AppMediaItem>, Int) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onBrowseLibrary: () -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // §19: focus immediately, don't make people tap the field they just opened.
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    // Built once per library, not per keystroke.
    val index = remember(all) {
        all.map { it to "${it.title} ${it.artist} ${it.album}".lowercase() }
    }
    val albums = remember(all) { MediaRepository.albumsOf(all) }
    val artists = remember(all) { MediaRepository.artistsOf(all) }

    val q = query.trim().lowercase()
    val trackHits = remember(q, index) {
        if (q.isBlank()) emptyList() else index.filter { it.second.contains(q) }.map { it.first }
    }
    val albumHits = remember(q, albums) {
        if (q.isBlank()) emptyList()
        else albums.filter { it.name.lowercase().contains(q) || it.artist.lowercase().contains(q) }
            .take(8)
    }
    val artistHits = remember(q, artists) {
        if (q.isBlank()) emptyList()
        else artists.filter { it.name.lowercase().contains(q) }.take(8)
    }
    val nothing = q.isNotBlank() && trackHits.isEmpty() && albumHits.isEmpty() && artistHits.isEmpty()

    Column(Modifier.fillMaxSize().screenBackground().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(Space.md, Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MediaColors.Cream)
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(Radius.md))
                    .background(MediaColors.Elevated).padding(Space.md, 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, null, tint = MediaColors.CreamDim,
                    modifier = Modifier.size(IconSize.md))
                Spacer(Modifier.width(Space.sm))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.search_hint), style = Typo.Body, color = MediaColors.CreamFaint)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = Typo.Body.copy(color = MediaColors.Cream),
                        cursorBrush = SolidColor(MediaColors.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Filled.Close, stringResource(R.string.action_clear), tint = MediaColors.CreamDim,
                        modifier = Modifier.size(IconSize.md).clickable { query = "" })
                }
            }
        }

        when {
            q.isBlank() -> CenterHint(stringResource(R.string.search_empty_hint))

            // §19's exact prescription: name the situation, then offer a way out.
            nothing -> Column(
                Modifier.fillMaxSize().padding(Space.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.search_no_matches_title), style = Typo.Section, color = MediaColors.Cream)
                Spacer(Modifier.height(Space.sm))
                Text(
                    stringResource(R.string.search_no_matches_body, query),
                    style = Typo.Secondary, color = MediaColors.CreamFaint
                )
                Spacer(Modifier.height(Space.xl))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Box(
                        Modifier.clip(CircleShape).background(MediaColors.Accent)
                            .pressScale(haptic = true) { query = "" }
                            .padding(horizontal = Space.xl, vertical = Space.md)
                    ) { Text(stringResource(R.string.action_search_again), style = Typo.Label, color = Color.White) }
                    Box(
                        Modifier.clip(CircleShape).background(MediaColors.Elevated)
                            .pressScale(haptic = true, onClick = onBrowseLibrary)
                            .padding(horizontal = Space.xl, vertical = Space.md)
                    ) { Text(stringResource(R.string.search_browse), style = Typo.Label, color = MediaColors.Cream) }
                }
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = bottomSafePadding(gap = 100.dp))
            ) {
                // §19 "meaningful result sections". Artists and albums first —
                // they're fewer and more specific, so a match there is a
                // stronger signal than one of forty track matches.
                if (artistHits.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.label_artists)) }
                    items(artistHits.size) { i ->
                        val a = artistHits[i]
                        ResultRow(
                            title = shownArtist(a.name),
                            subtitle = plural(R.plurals.count_songs, a.trackCount),
                            art = a.tracks.firstOrNull(),
                            circular = true
                        ) { onOpenArtist(a) }
                    }
                }
                if (albumHits.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.label_albums)) }
                    items(albumHits.size) { i ->
                        val al = albumHits[i]
                        ResultRow(
                            title = shownAlbum(al.name),
                            subtitle = shownArtist(al.artist),
                            art = al.tracks.firstOrNull(),
                            circular = false
                        ) { onOpenAlbum(al) }
                    }
                }
                if (trackHits.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.label_tracks)) }
                    items(trackHits.size) { i ->
                        val t = trackHits[i]
                        ResultRow(
                            title = shownTitle(t.title),
                            subtitle = shownArtist(t.artist),
                            art = t,
                            circular = false
                        ) { onPlay(trackHits, i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    art: AppMediaItem?,
    circular: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(Space.xl, Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(if (circular) CircleShape else RoundedCornerShape(Radius.sm))) {
            if (art != null) {
                CoverArt(art, Modifier.fillMaxSize(), corner = 0, targetPx = 144)
            }
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = Typo.Primary, color = MediaColors.Cream,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Typo.Secondary, color = MediaColors.CreamDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = Typo.Body, color = MediaColors.CreamFaint)
    }
}
