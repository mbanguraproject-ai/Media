package com.media.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.platform.LocalContext
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

private val G_FILL = MediaColors.FillSubtle
private val G_BORDER = MediaColors.Fill

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
    // §26 context actions. View album/artist are nullable: a track with no
    // album metadata has nowhere to navigate to, so the row is simply absent
    // rather than present-and-dead.
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onViewAlbum: (() -> Unit)? = null,
    onViewArtist: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Box(
        Modifier.fillMaxSize().background(MediaColors.Scrim).clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(MediaColors.Modal).border(1.dp, G_BORDER, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(Space.xl, Space.lg, Space.xl, Space.xl)
        ) {
            // grabber
            Box(Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp)
                .clip(CircleShape).background(MediaColors.FillStrong))
            Spacer(Modifier.height(Space.lg))
            // Header carries the artwork so the sheet is unmistakably about
            // the track you long-pressed.
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(item, Modifier.size(46.dp), corner = 10,
                    targetPx = 144, showBadge = false)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = Typo.Section, color = MediaColors.Cream,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.artist, style = Typo.Secondary, color = MediaColors.CreamFaint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(Space.lg))

            // §26 primary actions, surfaced first; the toggles below are
            // progressive disclosure for the less frequent choices.
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                QuickAction(Icons.Filled.PlaylistPlay, "Play next", Modifier.weight(1f)) {
                    onPlayNext(); onDismiss()
                }
                QuickAction(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue", Modifier.weight(1f)) {
                    onAddToQueue(); onDismiss()
                }
                QuickAction(Icons.Filled.Share, "Share", Modifier.weight(1f)) {
                    runCatching {
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = item.mimeType.ifBlank { "audio/*" }
                                putExtra(Intent.EXTRA_STREAM, item.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share track"
                        ))
                    }
                    onDismiss()
                }
            }
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
                        ToggleRow(Icons.AutoMirrored.Filled.QueueMusic, pl.name, MediaColors.Accent, inList) {
                            onTogglePlaylist(pl, !inList)
                        }
                    }
                }
            }

            // Divider + navigation / edit actions.
            Spacer(Modifier.height(Space.md))
            Box(Modifier.fillMaxWidth().height(1.dp).background(G_BORDER))
            Spacer(Modifier.height(Space.sm))
            if (onViewAlbum != null) {
                ActionRow(Icons.Filled.Album, "View album") { onViewAlbum(); onDismiss() }
            }
            if (onViewArtist != null) {
                ActionRow(Icons.Filled.Person, "View artist") { onViewArtist(); onDismiss() }
            }
            ActionRow(Icons.Filled.OpenInNew, "Open file") {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(item.uri, item.mimeType.ifBlank { "*/*" })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
                onDismiss()
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onEditDetails).padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MediaColors.FillStrong),
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
private fun QuickAction(
    icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MediaColors.Fill)
            .pressScale(haptic = true, onClick = onClick)
            .padding(vertical = Space.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = MediaColors.Cream, modifier = Modifier.size(IconSize.md))
        Spacer(Modifier.height(Space.xs))
        Text(label, style = Typo.Tertiary, color = MediaColors.CreamDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MediaColors.FillStrong),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = MediaColors.Cream, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.width(Space.md))
        Text(label, style = Typo.Primary, color = MediaColors.Cream, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = MediaColors.CreamFaint,
            modifier = Modifier.size(22.dp))
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
