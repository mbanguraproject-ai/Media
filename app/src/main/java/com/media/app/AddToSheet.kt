package com.media.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
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
import androidx.compose.ui.res.stringResource

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
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    // Enrichment. Null hides the row (video has neither).
    onDetails: (() -> Unit)? = null,
    onArtwork: (() -> Unit)? = null
) {
    val context = LocalContext.current
    // The sheet never scrolled. Header, four quick actions, the moods, every
    // playlist and eight action rows were one fixed Column, so on a phone
    // the height of a Redmi 10C the last rows - "Edit details" among them -
    // were simply below the bottom of the screen. Edit is a quick action now,
    // and everything under the header scrolls.
    val maxSheet = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.86f).dp
    Box(
        Modifier.fillMaxSize().background(MediaColors.Scrim).clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxSheet)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(MediaColors.Modal).border(1.dp, G_BORDER, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                // Swallows taps so they do not fall through to the scrim. A
                // disabled clickable does not consume, so it never did.
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {}
                .navigationBarsPadding()
                .padding(Space.xl, Space.lg, Space.xl, Space.md)
        ) {
            // grabber
            Box(Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp)
                .clip(CircleShape).background(MediaColors.FillStrong))
            Spacer(Modifier.height(Space.lg))
            // Header carries the artwork so the sheet is unmistakably about
            // the track you long-pressed.
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(item, Modifier.size(46.dp), corner = 10,
                    targetPx = 144)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(shownTitle(item.title), style = Typo.Section, color = MediaColors.Cream,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shownArtist(item.artist), style = Typo.Secondary, color = MediaColors.CreamFaint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(Space.lg))

            // §26 primary actions, surfaced first; the toggles below are
            // progressive disclosure for the less frequent choices.
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                QuickAction(Icons.AutoMirrored.Filled.PlaylistPlay, stringResource(R.string.action_play_next), Modifier.weight(1f)) {
                    onPlayNext(); onDismiss()
                }
                QuickAction(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.action_add_to_queue), Modifier.weight(1f)) {
                    onAddToQueue(); onDismiss()
                }
                QuickAction(Icons.Filled.Edit, stringResource(R.string.action_edit_info), Modifier.weight(1f)) {
                    onEditDetails()
                }
                val shareTitle = stringResource(R.string.share_track_title)
                QuickAction(Icons.Filled.Share, stringResource(R.string.action_share), Modifier.weight(1f)) {
                    runCatching {
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = item.mimeType.ifBlank { "audio/*" }
                                putExtra(Intent.EXTRA_STREAM, item.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, shareTitle
                        ))
                    }
                    onDismiss()
                }
            }
            Spacer(Modifier.height(Space.lg))

            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                // Moods (song-holding only)
                Text(stringResource(R.string.label_moods_caps), style = Typo.Micro,
                    color = MediaColors.CreamFaint, modifier = Modifier.padding(bottom = Space.sm))
                Mood.values().filter { it.holdsSongs }.forEach { mood ->
                    val inList = memberMoods.contains(mood.key)
                    val icon = when (mood) {
                        Mood.LATE_NIGHT -> Icons.Filled.Bedtime
                        Mood.WORKOUT -> Icons.Filled.Bolt
                        Mood.FOCUS -> Icons.Filled.TrackChanges
                        else -> Icons.Filled.Favorite
                    }
                    ToggleRow(icon, stringResource(mood.labelRes), mood.accent, inList) { onToggleMood(mood, !inList) }
                }

                if (playlists.isNotEmpty()) {
                    Text(stringResource(R.string.label_playlists_caps), style = Typo.Micro,
                        color = MediaColors.CreamFaint,
                        modifier = Modifier.padding(top = Space.md, bottom = Space.sm))
                    playlists.forEach { pl ->
                        val inList = memberPlaylists.contains(pl.id)
                        ToggleRow(Icons.AutoMirrored.Filled.QueueMusic, pl.name, MediaColors.Accent, inList) {
                            onTogglePlaylist(pl, !inList)
                        }
                    }
                }

                // Divider + navigation / edit actions.
                Spacer(Modifier.height(Space.md))
                Box(Modifier.fillMaxWidth().height(1.dp).background(G_BORDER))
                Spacer(Modifier.height(Space.sm))
                if (onViewAlbum != null) {
                    ActionRow(Icons.Filled.Album, stringResource(R.string.action_view_album)) { onViewAlbum(); onDismiss() }
                }
                if (onViewArtist != null) {
                    ActionRow(Icons.Filled.Person, stringResource(R.string.action_view_artist)) { onViewArtist(); onDismiss() }
                }
                ActionRow(Icons.Filled.Edit, stringResource(R.string.action_edit_details)) { onEditDetails() }
                if (onArtwork != null) {
                    ActionRow(Icons.Filled.Image, stringResource(R.string.action_fix_artwork)) { onArtwork(); onDismiss() }
                }
                if (onDetails != null) {
                    ActionRow(Icons.Filled.Info, stringResource(R.string.action_track_details)) { onDetails(); onDismiss() }
                }
                ActionRow(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.action_open_file)) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(item.uri, item.mimeType.ifBlank { "*/*" })
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }
                    onDismiss()
                }
                // Last, and the only red thing on the sheet. A destructive row
                // that looks like every other row is a mis-tap waiting to happen.
                ActionRow(
                    Icons.Filled.DeleteOutline, stringResource(R.string.action_delete_from_device),
                    tint = MediaColors.Danger
                ) { onDelete(); onDismiss() }
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
private fun ActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = MediaColors.Cream,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MediaColors.FillStrong),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.width(Space.md))
        Text(label, style = Typo.Primary, color = tint, modifier = Modifier.weight(1f))
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
