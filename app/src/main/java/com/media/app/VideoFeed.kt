package com.media.app

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

// ============================================================================
//  VIDEO FEED
//
//  Video in a 56dp list row was the wrong shape for the thing it was listing.
//  Under the Video pillar the list becomes full-width 16:9 cards, and a tap
//  plays the video IN the card rather than throwing you into the fullscreen
//  player. Deciding to watch something and committing to a fullscreen mode are
//  two different decisions, and only one of them should need undoing.
//
//  ONE SURFACE, ONE OWNER. There is a single MediaController for the whole
//  app, and a PlayerView takes the video surface when you attach it. Two
//  PlayerViews on one player means the second one wins and the first goes
//  black - so the card renders its player ONLY while it owns the surface, and
//  falls back to the poster frame the moment the fullscreen player wants it.
//  Coming back rebuilds the view, which re-attaches cleanly; re-asserting the
//  same Player instance would not, because PlayerView.setPlayer returns early
//  when nothing changed.
//
//  Scrolling a playing card off screen disposes it and releases the surface.
//  Audio keeps going - it belongs to the service, not to this view - and the
//  mini-player is still there to get back to it.
//
//  Cards are always 16:9, even for a portrait recording, which then letterboxes
//  inside the card. Cropping a vertical video to landscape throws away most of
//  the frame, and giving it a full-width portrait card makes one item fill the
//  whole screen.
// ============================================================================

private const val CARD_ASPECT = 16f / 9f

private fun videoMeta(item: AppMediaItem): String {
    val who = item.artist.takeIf { it.isNotBlank() && it != UNKNOWN_ARTIST }
    val added = if (item.dateAdded > 0L) {
        DateUtils.getRelativeTimeSpanString(
            item.dateAdded * 1000L,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    } else null
    return listOfNotNull(who, added).joinToString("  \u00B7  ")
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoCard(
    item: AppMediaItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    progress: Float,
    // False whenever something else is over Home and should own the surface.
    canRenderVideo: Boolean,
    vm: PlayerViewModel,
    // Raised while this card holds the one video surface, so the mini-player
    // can stand down. Counted rather than set, because two cards briefly
    // overlap when playback moves from one to the next and whichever composes
    // last would otherwise decide.
    onSurfaceOwned: (Boolean) -> Unit,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onExpand: () -> Unit,
    onMenu: () -> Unit
) {
    val live = isCurrent && canRenderVideo

    // Controls get out of the way. Two black discs sitting on the picture for
    // the whole runtime are the most distracting thing on the card, and they
    // are wanted for about a second at a time. Tapping the picture brings them
    // back for three seconds.
    //
    // Paused is the exception and it is not negotiable: with the controls
    // hidden and playback stopped, the play button is the only way back, so it
    // stays put until playback resumes.
    var reveal by remember(item.id) { mutableStateOf(0) }
    var chrome by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(reveal, isPlaying, live) {
        when {
            !live -> chrome = false
            !isPlaying -> chrome = true
            reveal == 0 -> chrome = false
            else -> {
                chrome = true
                delay(CHROME_LINGER_MS)
                chrome = false
            }
        }
    }

    DisposableEffect(live) {
        if (live) onSurfaceOwned(true)
        onDispose { if (live) onSurfaceOwned(false) }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = Space.lg)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_ASPECT)
                .background(Color.Black)
                // No ripple: a grey wash blooming across a film every time you
                // ask for the controls is worse than the controls.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { if (isCurrent) reveal++ else onClick() }
        ) {
            if (live) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            setBackgroundColor(android.graphics.Color.BLACK)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { it.player = vm.boundPlayer() },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize()
                )
                InlineControls(
                    chrome = chrome,
                    isPlaying = isPlaying,
                    progress = progress,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onExpand = onExpand,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            } else {
                CoverArt(item, Modifier.fillMaxSize(), corner = 0, targetPx = 720)
                Text(
                    fmtClock(item.durationMs),
                    style = Typo.Micro,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Space.sm)
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Space.xl, end = Space.sm, top = Space.md)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = Typo.Primary,
                    color = MediaColors.Cream,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = videoMeta(item)
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        meta,
                        style = Typo.Secondary,
                        color = MediaColors.CreamDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                Icons.Filled.MoreVert, "More options",
                tint = MediaColors.CreamFaint,
                modifier = Modifier
                    .padding(start = Space.sm)
                    .clip(CircleShape)
                    .clickable(onClick = onMenu)
                    .padding(Space.sm)
                    .size(20.dp)
            )
        }
    }
}

private const val CHROME_LINGER_MS = 3000L

// The two buttons come and go; the progress line does not. Knowing where you
// are in a video is not a control, it is the one thing you read without
// asking, and hiding it would mean tapping the picture to find out.
@Composable
private fun InlineControls(
    chrome: Boolean,
    isPlaying: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        // No scrim. A gradient the full width of the card to make two icons
        // legible darkens the picture to pay for the picture's own controls.
        // Each icon carries a small disc instead, which is the only area that
        // needs to stop being video.
        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(220))
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.sm, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable(onClick = onPlayPause)
                    .padding(Space.sm)
                    .size(22.dp)
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.Fullscreen, "Open in the player",
                tint = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable(onClick = onExpand)
                    .padding(Space.sm)
                    .size(22.dp)
            )
        }
        }
        // A 2dp line is not a control. The strip that takes the touch is 18dp
        // and transparent, with the line drawn along its bottom edge, so the
        // finger has something to hit without the card growing a visible bar.
        //
        // The head follows the FINGER while dragging and playback only
        // afterwards, and exactly one seek is issued, on release. Seeking per
        // move event tears down the decoder dozens of times per drag.
        var dragging by remember { mutableStateOf(false) }
        var dragFrac by remember { mutableStateOf(0f) }
        val head = if (dragging) dragFrac else progress.coerceIn(0f, 1f)
        // At rest there is no track, only how far you have got. A grey line
        // across the full width of every card is the brightest thing on the
        // screen and it is telling you nothing you asked for. It fades in
        // under your finger, where knowing the whole range is the point.
        val trackAlpha by animateFloatAsState(
            targetValue = if (dragging) 0.30f else 0f,
            label = "scrubTrack"
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        dragging = true
                        dragFrac = (down.position.x / w).coerceIn(0f, 1f)
                        // Consumed, or the list underneath reads the drag as a
                        // scroll and the card slides away under the finger.
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val p = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!p.pressed) break
                            dragFrac = (p.position.x / w).coerceIn(0f, 1f)
                            p.consume()
                        }
                        onSeek(dragFrac)
                        dragging = false
                    }
                },
            contentAlignment = Alignment.BottomStart
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (dragging) 4.dp else 2.dp)
                    .background(Color.White.copy(alpha = trackAlpha))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(head)
                        .fillMaxHeight()
                        .background(MediaColors.Accent)
                )
            }
        }
    }
}
