package com.media.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

// ============================================================================
//  VIA SHARE - the sheet
//
//  A window onto ShareSession and nothing more. It holds no state of its own
//  beyond a finger mid-drag, so closing it cannot stop the film and reopening
//  it cannot show something stale.
// ============================================================================

@Composable
fun ShareSheet(
    queue: List<AppMediaItem>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    val s by ShareSession.state.collectAsState()
    LaunchedEffect(Unit) { if (!s.sharing) ShareSession.scan() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MediaColors.Scrim)
            .clickable(onClick = onDismiss)
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl))
                .background(MediaColors.Modal)
                // Swallow taps, or touching the sheet closes it.
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .padding(Space.xl)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.share_name), style = Typo.Section, color = MediaColors.Cream)
                Spacer(Modifier.weight(1f))
                when {
                    s.scanning -> CircularProgressIndicator(
                        Modifier.size(18.dp), color = MediaColors.Accent, strokeWidth = 2.dp
                    )
                    !s.sharing -> RoundIcon(Icons.Filled.Refresh, stringResource(R.string.action_search_again)) {
                        ShareSession.scan()
                    }
                }
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                stringResource(R.string.share_sheet_sub),
                style = Typo.Secondary, color = MediaColors.CreamFaint
            )
            Spacer(Modifier.height(Space.lg))

            val active = s.active
            if (active != null) {
                Playing(s, active)
            } else {
                if (queue.isEmpty()) {
                    Text(
                        stringResource(R.string.share_start_first),
                        style = Typo.Secondary, color = MediaColors.CreamDim
                    )
                    Spacer(Modifier.height(Space.md))
                }
                s.devices.forEach { r ->
                    DeviceRow(r, enabled = queue.isNotEmpty()) {
                        ShareSession.start(r, queue, startIndex)
                    }
                }
            }

            s.note?.let {
                Spacer(Modifier.height(Space.md))
                Text(it.resolve(), style = Typo.Secondary, color = MediaColors.CreamDim)
            }
            Spacer(Modifier.height(Space.md))
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Icon(
        icon, label, tint = MediaColors.CreamDim,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(Space.sm)
            .size(20.dp)
    )
}

@Composable
private fun DeviceRow(r: Renderer, enabled: Boolean, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable(enabled = enabled, onClick = onPick)
            .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Tv, null,
            tint = if (enabled) MediaColors.Accent else MediaColors.CreamFaint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                r.name, style = Typo.Primary,
                color = if (enabled) MediaColors.Cream else MediaColors.CreamDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (r.model.isNotBlank()) {
                Text(
                    r.model, style = Typo.Secondary, color = MediaColors.CreamFaint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun Playing(s: ShareState, r: Renderer) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CastConnected, null, tint = MediaColors.Accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = Typo.Primary, color = MediaColors.Cream, maxLines = 1)
            Text(
                s.item?.title?.let { shownTitle(it) }.orEmpty(), style = Typo.Secondary,
                color = MediaColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (s.queue.size > 1) {
            Text(
                stringResource(R.string.share_queue_position, s.index + 1, s.queue.size),
                style = Typo.Micro, color = MediaColors.CreamFaint
            )
        }
    }
    Spacer(Modifier.height(Space.md))

    val total = if (s.durationMs > 0) s.durationMs else s.item?.durationMs ?: 0L
    Scrub(
        fraction = if (total > 0L) (s.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f,
        key = r.id
    ) { ShareSession.seek(it) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${fmtClock(s.positionMs)} / ${fmtClock(total)}",
            style = Typo.Micro, color = MediaColors.CreamFaint
        )
        Spacer(Modifier.weight(1f))
        Transport(Icons.Filled.SkipPrevious, stringResource(R.string.action_previous), s.hasPrevious) { ShareSession.previous() }
        Transport(
            if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (s.playing) R.string.action_pause else R.string.action_play), true
        ) { ShareSession.toggle() }
        Transport(Icons.Filled.SkipNext, stringResource(R.string.action_next), s.hasNext) { ShareSession.next() }
    }

    // Only renderers that advertise RenderingControl get a slider. A control
    // that silently does nothing is worse than no control.
    if (s.volume >= 0) {
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (s.volume == 0) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                null, tint = MediaColors.CreamFaint, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Space.md))
            Box(Modifier.weight(1f)) {
                Scrub(fraction = s.volume / 100f, key = "vol-" + r.id) {
                    ShareSession.setVolume((it * 100f).toInt())
                }
            }
        }
    }

    Spacer(Modifier.height(Space.md))
    Text(
        stringResource(R.string.action_stop),
        style = Typo.Label, color = MediaColors.Accent,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .border(1.dp, MediaColors.Fill, RoundedCornerShape(Radius.pill))
            .clickable { ShareSession.stop() }
            .padding(horizontal = Space.lg, vertical = Space.sm)
    )
}

@Composable
private fun Transport(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Icon(
        icon, label,
        tint = if (enabled) MediaColors.Cream else MediaColors.CreamFaint.copy(alpha = 0.4f),
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(Space.sm)
            .size(24.dp)
    )
}

/** Drag anywhere along the line; one commit, on release. */
@Composable
private fun Scrub(fraction: Float, key: Any, onCommit: (Float) -> Unit) {
    var dragFrac by remember(key) { mutableStateOf(-1f) }
    val head = if (dragFrac >= 0f) dragFrac else fraction
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(key) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    dragFrac = (down.position.x / w).coerceIn(0f, 1f)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val p = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) break
                        dragFrac = (p.position.x / w).coerceIn(0f, 1f)
                        p.consume()
                    }
                    val target = dragFrac
                    dragFrac = -1f
                    onCommit(target)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(MediaColors.Fill)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(head.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(MediaColors.Accent)
            )
        }
    }
}
