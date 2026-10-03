package com.media.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource

private val G_FILL = MediaColors.FillSubtle
private val G_BORDER = MediaColors.Fill

@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    moodCounts: Map<String, Int>,
    onOpenMood: (Mood) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit
) {
    var tab by remember { mutableStateOf(0) } // 0 My Playlists, 1 Smart
    var showCreate by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().screenBackground()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Title row
            Row(
                Modifier.fillMaxWidth().padding(Space.xl, Space.lg, Space.lg, Space.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.playlists_title), style = Typo.Display,
                    color = MediaColors.Cream, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp))
                        .background(MediaColors.Accent)
                        .clickable { showCreate = true }
                        .padding(horizontal = Space.lg, vertical = Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_new), style = Typo.Label, color = Color.White)
                }
            }

            // Segmented glass control
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.xl)
                    .clip(RoundedCornerShape(14.dp))
                    .background(G_FILL)
                    .border(1.dp, G_BORDER, RoundedCornerShape(14.dp))
                    .padding(4.dp)
            ) {
                listOf(stringResource(R.string.playlists_tab_mine), stringResource(R.string.playlists_tab_smart)).forEachIndexed { i, label ->
                    val sel = tab == i
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                            .background(if (sel) MediaColors.Accent.copy(alpha = 0.9f) else Color.Transparent)
                            .clickable { tab = i }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, style = Typo.Label,
                            color = if (sel) Color.White else MediaColors.CreamDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(Space.lg))

            when (tab) {
                0 -> MyPlaylistsTab(playlists, onOpenPlaylist, onDeletePlaylist) { showCreate = true }
                else -> SmartTab(moodCounts, onOpenMood)
            }
        }
    }

    if (showCreate) {
        CreatePlaylistSheet(onCreate = { name -> onCreatePlaylist(name); showCreate = false },
            onDismiss = { showCreate = false })
    }
}

// ---------------- MY PLAYLISTS ----------------
@Composable
private fun MyPlaylistsTab(
    playlists: List<Playlist>,
    onOpen: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
    onCreate: () -> Unit
) {
    if (playlists.isEmpty()) {
        EmptyBlock(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            title = stringResource(R.string.playlists_empty_title),
            subtitle = stringResource(R.string.playlists_empty_body),
            cta = stringResource(R.string.action_create_playlist),
            onCta = onCreate
        )
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = 170.dp)) {
            items(playlists) { pl ->
                Row(
                    Modifier.fillMaxWidth().padding(Space.xl, 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(G_FILL)
                        .border(1.dp, G_BORDER, RoundedCornerShape(14.dp))
                        .clickable { onOpen(pl) }
                        .padding(Space.lg, 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(11.dp))
                            .background(MediaColors.Accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MediaColors.Accent, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(Space.md))
                    Text(pl.name, style = Typo.Primary,
                        color = MediaColors.Cream, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton({ onDelete(pl) }) {
                        Icon(Icons.Filled.DeleteOutline, stringResource(R.string.action_delete), tint = MediaColors.CreamFaint, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

// ---------------- SMART (moods) ----------------
@Composable
private fun SmartTab(moodCounts: Map<String, Int>, onOpenMood: (Mood) -> Unit) {
    val moods = Mood.values().filter { it.holdsSongs } // exclude ALL
    LazyColumn(contentPadding = PaddingValues(bottom = 170.dp)) {
        item {
            Text(stringResource(R.string.playlists_moods_hint), style = Typo.Tertiary, color = MediaColors.CreamFaint,
                modifier = Modifier.padding(Space.xl, 0.dp, Space.xl, Space.sm))
        }
        items(moods) { mood ->
            val count = moodCounts[mood.key] ?: 0
            val icon = when (mood) {
                Mood.LATE_NIGHT -> Icons.Filled.Bedtime
                Mood.WORKOUT -> Icons.Filled.Bolt
                Mood.FOCUS -> Icons.Filled.TrackChanges
                else -> Icons.Filled.Favorite
            }
            Row(
                Modifier.fillMaxWidth().padding(Space.xl, 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(mood.accent.copy(alpha = 0.10f))
                    .border(1.dp, mood.accent.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                    .clickable { onOpenMood(mood) }
                    .padding(Space.lg, 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(11.dp))
                        .background(mood.accent.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, null, tint = mood.accent, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(mood.labelRes), style = Typo.Primary, color = MediaColors.Cream)
                    Text(plural(R.plurals.count_songs, count), style = Typo.Tertiary, color = MediaColors.CreamFaint)
                }
                Icon(Icons.Filled.PlayArrow, stringResource(R.string.action_play), tint = mood.accent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ---------------- PLAYLIST DETAIL ----------------
// The screen that never existed: onOpenPlaylist was a no-op, so a playlist you
// had added songs to could never be opened, played, or inspected. Track rows
// are the same TrackRow used on Home, so long-press -> AddToSheet is how you
// remove a song (untick the playlist) — no bespoke remove affordance needed.
@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    tracks: List<AppMediaItem>,
    state: PlayerState,
    favorites: Set<Long>,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onLongPress: (AppMediaItem) -> Unit,
    onToggleFav: (AppMediaItem) -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().screenBackground().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(Space.sm, Space.sm, Space.lg, Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MediaColors.Cream)
            }
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = Typo.Display, color = MediaColors.Cream,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(plural(R.plurals.count_songs, tracks.size),
                    style = Typo.Tertiary, color = MediaColors.CreamFaint)
            }
        }

        if (tracks.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(Space.xl, Space.sm, Space.xl, Space.md),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .background(MediaColors.Accent)
                        .clickable { onPlay(0) }
                        .padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_play), style = Typo.Label, color = Color.White)
                }
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .border(1.dp, MediaColors.Accent.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                        .clickable(onClick = onShuffle)
                        .padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Shuffle, null, tint = MediaColors.Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_shuffle), style = Typo.Label, color = MediaColors.Accent)
                }
            }
        }

        if (tracks.isEmpty()) {
            EmptyBlock(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = stringResource(R.string.playlist_empty_title),
                subtitle = stringResource(R.string.playlist_empty_body),
                cta = null, onCta = {}
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = bottomSafePadding(gap = 100.dp))) {
                items(tracks.size) { idx ->
                    val track = tracks[idx]
                    TrackRow(
                        item = track,
                        isPlaying = state.currentUri == track.uri.toString() && state.isPlaying,
                        isFavorite = favorites.contains(track.id),
                        onClick = { onPlay(idx) },
                        onLongPress = { onLongPress(track) },
                        onMenu = { onLongPress(track) },
                        onToggleFav = { onToggleFav(track) }
                    )
                }
            }
        }
    }
}

// ---------------- shared empty state ----------------
@Composable
fun EmptyBlock(icon: ImageVector, title: String, subtitle: String, cta: String?, onCta: () -> Unit) {
    Column(
        Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(MediaColors.Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = MediaColors.Accent, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(Space.xl))
        Text(title, style = Typo.Section, color = MediaColors.Cream)
        Spacer(Modifier.height(Space.sm))
        Text(subtitle, style = Typo.Secondary, color = MediaColors.CreamDim)
        if (cta != null) {
            Spacer(Modifier.height(Space.xl))
            Box(
                Modifier.clip(RoundedCornerShape(22.dp)).background(MediaColors.Accent)
                    .clickable(onClick = onCta).padding(horizontal = 28.dp, vertical = 14.dp)
            ) { Text(cta, style = Typo.Primary, color = Color.White) }
        }
        Spacer(Modifier.height(120.dp))
    }
}

// ---------------- create-playlist dialog ----------------
// A real Dialog window, not a Box inside the Playlists screen. The Box was
// drawn BEFORE the scaffold's persistent chrome, so the mini-player and the
// nav bar sat on top of it, bright and live: a sideways swipe meant to wave
// the box away landed on the pill and skipped or restarted the song. It also
// had no Back handling (Back closed the whole Playlists screen) and nothing
// kept it above the keyboard. Its own window fixes all three: it is above
// everything, Back dismisses only it, and the window manager keeps it clear
// of the keyboard.
@Composable
private fun CreatePlaylistSheet(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    fun submit() { if (name.isNotBlank()) onCreate(name.trim()) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(20.dp))
                .background(MediaColors.Modal).border(1.dp, G_BORDER, RoundedCornerShape(20.dp))
                .padding(Space.xl),
        ) {
            Text(stringResource(R.string.new_playlist_title), style = Typo.Section, color = MediaColors.Cream)
            Spacer(Modifier.height(Space.lg))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(G_FILL).border(1.dp, G_BORDER, RoundedCornerShape(12.dp))
                    .padding(Space.lg, 14.dp)
            ) {
                if (name.isEmpty()) Text(stringResource(R.string.playlist_name_hint), style = Typo.Body, color = MediaColors.CreamFaint)
                BasicTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true,
                    textStyle = Typo.Body.copy(color = MediaColors.Cream),
                    cursorBrush = SolidColor(MediaColors.Accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
            Spacer(Modifier.height(Space.lg))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onDismiss)
                    .padding(horizontal = Space.lg, vertical = 10.dp)) {
                    Text(stringResource(R.string.action_cancel), style = Typo.Label, color = MediaColors.CreamDim)
                }
                Spacer(Modifier.width(Space.sm))
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(if (name.isBlank()) MediaColors.Accent.copy(alpha = 0.4f) else MediaColors.Accent)
                        .clickable(enabled = name.isNotBlank()) { submit() }
                        .padding(horizontal = Space.lg, vertical = 10.dp)
                ) { Text(stringResource(R.string.action_create), style = Typo.Label, color = Color.White) }
            }
        }
        // Straight to typing: the keyboard comes up with the box. Inside the
        // dialog's own composition, once its window exists.
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(60)
            runCatching { focus.requestFocus() }
        }
    }
}
