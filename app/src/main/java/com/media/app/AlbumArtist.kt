package com.media.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ============================================================================
//  ALBUMS & ARTISTS (§9, §15, §45)
//  These are the destinations the data model in MediaRepository exists to
//  serve. Album artwork is sourced from the album's first track so it reuses
//  the same LRU cache and generative fallback as everywhere else.
// ============================================================================

private fun fmtTotal(ms: Long): String {
    val min = ms / 60000
    return if (min >= 60) "${min / 60} hr ${min % 60} min" else "$min min"
}

internal fun fmtTrack(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ---------------------------------------------------------------- browse ----

@Composable
fun AlbumGrid(albums: List<Album>, onOpen: (Album) -> Unit) {
    if (albums.isEmpty()) {
        CenterNote("No albums yet")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(Space.xl, Space.sm, Space.xl, bottomSafePadding(gap = 100.dp)),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.lg)
    ) {
        items(albums.size) { i ->
            val a = albums[i]
            Column(Modifier.clickable { onOpen(a) }) {
                a.tracks.firstOrNull()?.let {
                    CoverArt(
                        item = it,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                            .shadow(
                                elevation = Elevation.mid,
                                shape = RoundedCornerShape(14.dp),
                                clip = false,
                                ambientColor = Color.Black,
                                spotColor = Color.Black
                            ),
                        corner = 14, targetPx = 384
                    )
                }
                Spacer(Modifier.height(Space.md))
                Text(a.name, style = Typo.Primary, color = MediaColors.Cream,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.artist, style = Typo.Secondary, color = MediaColors.CreamFaint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ArtistList(artists: List<Artist>, onOpen: (Artist) -> Unit) {
    if (artists.isEmpty()) {
        CenterNote("No artists yet")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = bottomSafePadding(gap = 100.dp))) {
        items(artists.size) { i ->
            val a = artists[i]
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(Space.xl, Space.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular crop distinguishes an artist from an album at a glance.
                Box(Modifier.size(52.dp).clip(CircleShape)) {
                    a.tracks.firstOrNull()?.let {
                        CoverArt(it, Modifier.fillMaxSize(), corner = 0,
                            targetPx = 144)
                    }
                }
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = Typo.Primary, color = MediaColors.Cream,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append(if (a.albumCount == 1) "1 album" else "${a.albumCount} albums")
                            append(" \u00b7 ")
                            append(if (a.trackCount == 1) "1 song" else "${a.trackCount} songs")
                        },
                        style = Typo.Secondary, color = MediaColors.CreamFaint
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- detail ----

@Composable
fun AlbumDetailScreen(
    album: Album,
    state: PlayerState,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onLongPress: (AppMediaItem) -> Unit,
    onOpenArtist: (() -> Unit)? = null,
    onClose: () -> Unit
) {
    DetailScaffold(
        title = album.name,
        subtitle = album.artist,
        meta = buildString {
            if (album.year > 0) append("${album.year} \u00b7 ")
            append(if (album.trackCount == 1) "1 song" else "${album.trackCount} songs")
            append(" \u00b7 ").append(fmtTotal(album.durationMs))
        },
        artItem = album.tracks.firstOrNull(),
        circularArt = false,
        onSubtitleClick = onOpenArtist,
        onPlay = { onPlay(0) },
        onShuffle = onShuffle,
        onClose = onClose
    ) {
        // Track numbers rather than artwork: within an album the running order
        // is the useful information, and every row would show the same cover.
        items(album.tracks.size) { i ->
            val t = album.tracks[i]
            val playing = state.currentUri == t.uri.toString()
            NumberedRow(
                number = if (t.trackNo > 0) t.trackNo else i + 1,
                title = t.title,
                subtitle = if (t.artist != album.artist) t.artist else null,
                duration = fmtTrack(t.durationMs),
                playing = playing,
                onClick = { onPlay(i) },
                onLongPress = { onLongPress(t) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameArtistSheet(
    current: String,
    trackCount: Int,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(current) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MediaColors.Modal,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MediaColors.FillStrong) }
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(Space.xl, 0.dp, Space.xl, Space.xxl)) {
            Text("Rename artist", style = Typo.Section, color = MediaColors.Cream)
            Spacer(Modifier.height(Space.xs))
            Text(
                "Applies to all " + trackCount +
                    " tracks currently filed under this artist. Download sites often " +
                    "tag a whole batch with one name, so this fixes them together.",
                style = Typo.Secondary, color = MediaColors.CreamDim
            )
            Spacer(Modifier.height(Space.xl))
            Box(
                Modifier.fillMaxWidth()
                    .background(MediaColors.Ink, RoundedCornerShape(10.dp))
                    .border(0.5.dp, MediaColors.Fill, RoundedCornerShape(10.dp))
                    .padding(Space.md, 12.dp)
            ) {
                if (name.isEmpty()) {
                    Text("Artist name", style = Typo.Body, color = MediaColors.CreamFaint)
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = Typo.Body.copy(color = MediaColors.Cream),
                    cursorBrush = SolidColor(MediaColors.Accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(Space.xl))
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        if (name.isBlank()) MediaColors.Fill else MediaColors.Accent,
                        RoundedCornerShape(12.dp)
                    )
                    .pressScale(haptic = true) {
                        if (name.isNotBlank()) { onRename(name.trim()); onDismiss() }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Rename",
                    style = Typo.Label,
                    color = if (name.isBlank()) MediaColors.CreamFaint else Color.White
                )
            }
        }
    }
}

@Composable
fun ArtistDetailScreen(
    artist: Artist,
    albums: List<Album>,
    state: PlayerState,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onLongPress: (AppMediaItem) -> Unit,
    onRenameArtist: (String) -> Unit,
    onClose: () -> Unit
) {
    var renaming by remember { mutableStateOf(false) }

    DetailScaffold(
        title = artist.name,
        subtitle = null,
        meta = buildString {
            append(if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums")
            append(" \u00b7 ")
            append(if (artist.trackCount == 1) "1 song" else "${artist.trackCount} songs")
            append(" \u00b7 ").append(fmtTotal(artist.durationMs))
        },
        artItem = artist.tracks.firstOrNull(),
        circularArt = true,
        onSubtitleClick = null,
        onPlay = { onPlay(0) },
        onShuffle = onShuffle,
        onClose = onClose,
        action = {
            // Download sites stamp one artist across a whole batch, so the
            // wrong tracks are already grouped here under the wrong name.
            // Fixing them together is the only humane way to do it.
            Icon(
                Icons.Filled.Edit, "Rename artist",
                tint = MediaColors.Cream,
                modifier = Modifier.size(22.dp).pressScale(haptic = true) { renaming = true }
            )
        }
    ) {
        if (albums.size > 1) {
            item {
                Column {
                    Text("Albums", style = Typo.Section, color = MediaColors.Cream,
                        modifier = Modifier.padding(Space.xl, Space.md, Space.xl, Space.sm))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Space.xl),
                        horizontalArrangement = Arrangement.spacedBy(Space.md)
                    ) {
                        items(albums.size) { i ->
                            val a = albums[i]
                            Column(Modifier.width(124.dp).clickable { onOpenAlbum(a) }) {
                                a.tracks.firstOrNull()?.let {
                                    CoverArt(it, Modifier.fillMaxWidth().aspectRatio(1f),
                                        corner = 12, targetPx = 384)
                                }
                                Spacer(Modifier.height(Space.xs))
                                Text(a.name, style = Typo.Secondary, color = MediaColors.CreamDim,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Text("Songs", style = Typo.Section, color = MediaColors.Cream,
                        modifier = Modifier.padding(Space.xl, Space.lg, Space.xl, Space.sm))
                }
            }
        }
        items(artist.tracks.size) { i ->
            val t = artist.tracks[i]
            NumberedRow(
                number = i + 1,
                title = t.title,
                subtitle = t.album.takeIf { it != UNKNOWN_ALBUM },
                duration = fmtTrack(t.durationMs),
                playing = state.currentUri == t.uri.toString(),
                onClick = { onPlay(i) },
                onLongPress = { onLongPress(t) }
            )
        }
    }

    if (renaming) {
        RenameArtistSheet(
            current = artist.name,
            trackCount = artist.trackCount,
            onRename = onRenameArtist,
            onDismiss = { renaming = false }
        )
    }
}

// --------------------------------------------------------------- shared -----

@Composable
private fun DetailScaffold(
    title: String,
    subtitle: String?,
    meta: String,
    artItem: AppMediaItem?,
    circularArt: Boolean,
    onSubtitleClick: (() -> Unit)?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onClose: () -> Unit,
    // Optional trailing action in the top bar. Artist detail uses it to fix a
    // whole batch of mis-tagged files at once.
    action: (@Composable () -> Unit)? = null,
    body: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().background(moodBackground()).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(Space.sm, Space.sm), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MediaColors.Cream)
            }
            if (action != null) {
                Spacer(Modifier.weight(1f))
                action()
                Spacer(Modifier.width(Space.sm))
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = bottomSafePadding(gap = 100.dp))) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Space.xl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (artItem != null) {
                        Box(
                            Modifier.fillMaxWidth(0.62f).aspectRatio(1f)
                                .then(if (circularArt) Modifier.clip(CircleShape) else Modifier)
                        ) {
                            CoverArt(artItem, Modifier.fillMaxSize(),
                                corner = if (circularArt) 0 else 18,
                                targetPx = 768)
                        }
                    }
                    Spacer(Modifier.height(Space.lg))
                    Text(title, style = Typo.Display, color = MediaColors.Cream,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(
                            subtitle, style = Typo.Primary, color = MediaColors.Accent,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = if (onSubtitleClick != null)
                                Modifier.clickable(onClick = onSubtitleClick) else Modifier
                        )
                    }
                    Spacer(Modifier.height(Space.xxs))
                    Text(meta, style = Typo.Tertiary, color = MediaColors.CreamFaint)
                    Spacer(Modifier.height(Space.lg))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm)
                    ) {
                        ActionButton("Play", Icons.Filled.PlayArrow, filled = true,
                            modifier = Modifier.weight(1f), onClick = onPlay)
                        ActionButton("Shuffle", Icons.Filled.Shuffle, filled = false,
                            modifier = Modifier.weight(1f), onClick = onShuffle)
                    }
                    Spacer(Modifier.height(Space.md))
                }
            }
            body()
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.pill))
            .then(
                if (filled) Modifier.background(MediaColors.Accent)
                else Modifier.border(1.dp, MediaColors.Accent.copy(alpha = 0.55f),
                    RoundedCornerShape(Radius.pill))
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (filled) Color.White else MediaColors.Accent,
            modifier = Modifier.size(IconSize.md))
        Spacer(Modifier.width(Space.sm))
        Text(label, style = Typo.Label, color = if (filled) Color.White else MediaColors.Accent)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberedRow(
    number: Int,
    title: String,
    subtitle: String?,
    duration: String,
    playing: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(Space.xl, Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
            Text("$number", style = Typo.Tertiary,
                color = if (playing) MediaColors.Accent else MediaColors.CreamFaint)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Typo.Primary,
                color = if (playing) MediaColors.Accent else MediaColors.Cream,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = Typo.Secondary, color = MediaColors.CreamFaint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(Space.sm))
        if (playing) {
            PlayingEqualizer(true, MediaColors.Accent, Modifier.size(16.dp, 14.dp))
        } else {
            Text(duration, style = Typo.Tertiary, color = MediaColors.CreamFaint)
        }
    }
}

@Composable
fun CenterNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = Typo.Body, color = MediaColors.CreamFaint)
    }
}
