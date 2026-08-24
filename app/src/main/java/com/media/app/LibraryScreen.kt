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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class LibFilter(val label: String, val pillar: Pillar?) {
    ALL("All", null),
    MUSIC("Music", Pillar.MUSIC),
    PODCASTS("Podcasts", Pillar.PODCAST),
    AUDIOBOOKS("Audiobooks", Pillar.AUDIOBOOK),
    VIDEO("Video", Pillar.VIDEO)
}

@Composable
fun LibraryScreen(
    all: List<AppMediaItem>,
    state: PlayerState,
    initialPillar: Pillar? = null,
    onPlay: (List<AppMediaItem>, Int) -> Unit,
    onEdit: (AppMediaItem) -> Unit,
    onClose: () -> Unit
) {
    var filter by remember {
        mutableStateOf(
            LibFilter.values().firstOrNull { it.pillar == initialPillar } ?: LibFilter.ALL
        )
    }
    val shown = remember(filter, all) {
        if (filter.pillar == null) all else all.filter { it.pillar == filter.pillar }
    }

    // Library is a primary tab destination now, so it wears the same mood
    // gradient as Home / Playlists / Search rather than a flat ink fill.
    Column(Modifier.fillMaxSize().background(moodBackground()).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(Space.sm, Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MediaColors.Cream) }
            Text("Library", style = MaterialTheme.typography.titleLarge, color = MediaColors.Cream)
        }

        LazyRow(
            contentPadding = PaddingValues(Space.xl, Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            items(LibFilter.values().size) { i ->
                val f = LibFilter.values()[i]
                val selected = f == filter
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) MediaColors.Cream else MediaColors.InkRaised)
                        .clickable { filter = f }
                        .padding(horizontal = Space.lg, vertical = Space.sm)
                ) {
                    Text(
                        f.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected) MediaColors.Ink else MediaColors.CreamDim
                    )
                }
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing in ${filter.label.lowercase()} yet",
                    style = MaterialTheme.typography.bodyLarge, color = MediaColors.CreamFaint)
            }
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
            Text(item.title, style = MaterialTheme.typography.titleMedium,
                color = MediaColors.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.artist, style = MaterialTheme.typography.bodyMedium,
                color = MediaColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isPlaying) {
            Icon(Icons.Filled.Pause, "Playing", tint = MediaColors.Accent,
                modifier = Modifier.size(20.dp))
        }
    }
}

