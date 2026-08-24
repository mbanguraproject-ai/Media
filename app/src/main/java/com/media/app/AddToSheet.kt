package com.media.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val G_FILL = Color(0x0FFFFFFF)
private val G_BORDER = Color(0x17FFFFFF)

// Glass "Add to..." sheet. Shows the song moods + user playlists with live
// checkmarks. Toggling writes straight to the DB — Home updates instantly.
@Composable
fun AddToSheet(
    item: AppMediaItem,
    memberMoods: Set<String>,              // mood keys this song is already in
    playlists: List<Playlist>,
    memberPlaylists: Set<Long>,            // playlist ids this song is already in
    onToggleMood: (Mood, Boolean) -> Unit, // (mood, nowMember)
    onTogglePlaylist: (Playlist, Boolean) -> Unit,
    onEditDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Color(0xFF1B1628)).border(1.dp, G_BORDER, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(Space.xl, Space.lg, Space.xl, Space.xl)
        ) {
            // grabber
            Box(Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp)
                .clip(CircleShape).background(Color(0x33FFFFFF)))
            Spacer(Modifier.height(Space.lg))
            Text("Add to", style = Typo.Tertiary, color = MediaColors.CreamFaint)
            Text(item.title, style = Typo.Section, color = MediaColors.Cream,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.lg))

            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                // Moods (song-holding only)
                item {
                    Text("MOODS", style = Typo.Micro,
                        color = MediaColors.CreamFaint, modifier = Modifier.padding(bottom = Space.sm))
                }
                items(Mood.values().filter { it.holdsSongs }) { mood ->
                    val inList = memberMoods.contains(mood.key)
                    val icon = when (mood) {
                        Mood.LATE_NIGHT -> Icons.Filled.Bedtime
                        Mood.WORKOUT -> Icons.Filled.Bolt
                        Mood.FOCUS -> Icons.Filled.TrackChanges
                        else -> Icons.Filled.Favorite
                    }
                    ToggleRow(icon, mood.label, mood.accent, inList) { onToggleMood(mood, !inList) }
                }

                if (playlists.isNotEmpty()) {
                    item {
                        Text("PLAYLISTS", style = Typo.Micro,
                            color = MediaColors.CreamFaint,
                            modifier = Modifier.padding(top = Space.md, bottom = Space.sm))
                    }
                    items(playlists) { pl ->
                        val inList = memberPlaylists.contains(pl.id)
                        ToggleRow(Icons.Filled.QueueMusic, pl.name, MediaColors.Accent, inList) {
                            onTogglePlaylist(pl, !inList)
                        }
                    }
                }
            }

            // Divider + Edit details action.
            Spacer(Modifier.height(Space.md))
            Box(Modifier.fillMaxWidth().height(1.dp).background(G_BORDER))
            Spacer(Modifier.height(Space.sm))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onEditDetails).padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x1FFFFFFF)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Edit, null, tint = MediaColors.Cream, modifier = Modifier.size(19.dp)) }
                Spacer(Modifier.width(Space.md))
                Text("Edit details", style = Typo.Primary,
                    color = MediaColors.Cream, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, null, tint = MediaColors.CreamFaint, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, label: String, tint: Color, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(Space.md))
        Text(label, style = Typo.Primary, color = MediaColors.Cream,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(
            if (checked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            null, tint = if (checked) tint else MediaColors.CreamFaint, modifier = Modifier.size(24.dp)
        )
    }
}
