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
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val G_FILL = Color(0x0FFFFFFF)
private val G_BORDER = Color(0x17FFFFFF)

@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    moodCounts: Map<String, Int>,
    onOpenMood: (Mood) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onClose: () -> Unit
) {
    var tab by remember { mutableStateOf(0) } // 0 My Playlists, 1 Smart, 2 Folders
    var showCreate by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(moodBackground())) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Title row
            Row(
                Modifier.fillMaxWidth().padding(Space.xl, Space.lg, Space.lg, Space.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Playlists", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp, color = MediaColors.Cream, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp))
                        .background(MediaColors.Accent)
                        .clickable { showCreate = true }
                        .padding(horizontal = Space.lg, vertical = Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
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
                listOf("My Playlists", "Smart", "Folders").forEachIndexed { i, label ->
                    val sel = tab == i
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                            .background(if (sel) MediaColors.Accent.copy(alpha = 0.9f) else Color.Transparent)
                            .clickable { tab = i }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 13.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (sel) Color.White else MediaColors.CreamDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(Space.lg))

            when (tab) {
                0 -> MyPlaylistsTab(playlists, onOpenPlaylist, onDeletePlaylist) { showCreate = true }
                1 -> SmartTab(moodCounts, onOpenMood)
                else -> FoldersTab()
            }
        }

        // Back button top-left (over the title's left padding)
        IconButton(onClose, Modifier.statusBarsPadding().padding(Space.sm)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MediaColors.Cream)
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
            icon = Icons.Filled.QueueMusic,
            title = "No playlists yet",
            subtitle = "Create your first playlist",
            cta = "Create Playlist",
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
                    ) { Icon(Icons.Filled.QueueMusic, null, tint = MediaColors.Accent, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(Space.md))
                    Text(pl.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = MediaColors.Cream, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton({ onDelete(pl) }) {
                        Icon(Icons.Filled.DeleteOutline, "Delete", tint = MediaColors.CreamFaint, modifier = Modifier.size(20.dp))
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
            Text("Tap a mood to play it on Home", fontSize = 12.sp, color = MediaColors.CreamFaint,
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
                    Text(mood.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MediaColors.Cream)
                    Text(if (count == 1) "1 song" else "$count songs", fontSize = 12.sp, color = MediaColors.CreamFaint)
                }
                Icon(Icons.Filled.PlayArrow, "Play", tint = mood.accent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ---------------- FOLDERS ----------------
@Composable
private fun FoldersTab() {
    EmptyBlock(
        icon = Icons.Filled.FolderOpen,
        title = "No folders yet",
        subtitle = "Group tracks into folders",
        cta = null, onCta = {}
    )
}

// ---------------- shared empty state ----------------
@Composable
private fun EmptyBlock(icon: ImageVector, title: String, subtitle: String, cta: String?, onCta: () -> Unit) {
    Column(
        Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(MediaColors.Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = MediaColors.Accent, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(Space.xl))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MediaColors.Cream)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, fontSize = 14.sp, color = MediaColors.CreamDim)
        if (cta != null) {
            Spacer(Modifier.height(Space.xl))
            Box(
                Modifier.clip(RoundedCornerShape(22.dp)).background(MediaColors.Accent)
                    .clickable(onClick = onCta).padding(horizontal = 28.dp, vertical = 14.dp)
            ) { Text(cta, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
        }
        Spacer(Modifier.height(120.dp))
    }
}

// ---------------- create-playlist sheet ----------------
@Composable
private fun CreatePlaylistSheet(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth(0.86f).clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1B1628)).border(1.dp, G_BORDER, RoundedCornerShape(20.dp))
                .clickable(enabled = false) {}.padding(Space.xl),
        ) {
            Text("New Playlist", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MediaColors.Cream)
            Spacer(Modifier.height(Space.lg))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(G_FILL).border(1.dp, G_BORDER, RoundedCornerShape(12.dp))
                    .padding(Space.lg, 14.dp)
            ) {
                if (name.isEmpty()) Text("Playlist name", fontSize = 15.sp, color = MediaColors.CreamFaint)
                BasicTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MediaColors.Cream, fontSize = 15.sp),
                    cursorBrush = SolidColor(MediaColors.Accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(Space.lg))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onDismiss)
                    .padding(horizontal = Space.lg, vertical = 10.dp)) {
                    Text("Cancel", fontSize = 14.sp, color = MediaColors.CreamDim)
                }
                Spacer(Modifier.width(Space.sm))
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(if (name.isBlank()) MediaColors.Accent.copy(alpha = 0.4f) else MediaColors.Accent)
                        .clickable(enabled = name.isNotBlank()) { onCreate(name.trim()) }
                        .padding(horizontal = Space.lg, vertical = 10.dp)
                ) { Text("Create", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
            }
        }
    }
}
