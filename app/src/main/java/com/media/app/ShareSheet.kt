package com.media.app

import android.content.Context
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
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
//  AURA SHARE - the controls
//
//  Every call here is blocking network work on a device that may not answer,
//  so all of it runs on IO and the UI only ever reads state. A renderer that
//  has been unplugged should make a row go quiet, not freeze the sheet.
// ============================================================================

class ShareController(private val app: Context) {
    var devices by mutableStateOf<List<Renderer>>(emptyList()); private set
    var scanning by mutableStateOf(false); private set
    var active by mutableStateOf<Renderer?>(null); private set
    var item by mutableStateOf<AppMediaItem?>(null); private set
    var playing by mutableStateOf(false); private set
    var positionMs by mutableStateOf(0L); private set
    var note by mutableStateOf<String?>(null)

    private val server = ShareServer(app)

    /** This phone's LAN address while scanning, so an empty result is legible. */
    var host by mutableStateOf<String?>(null); private set

    suspend fun scan() {
        scanning = true
        note = null
        // "Nothing answered" is the wrong thing to say to someone on mobile
        // data. There is no local network to answer from, and no amount of
        // looking will change that.
        val net = withContext(Dispatchers.IO) { AuraShare.lanNetwork(app) }
        if (net == null) {
            devices = emptyList()
            host = null
            scanning = false
            note = "Aura Share needs Wi-Fi. This phone is on mobile data, " +
                "which has no local network for a TV to be on."
            return
        }
        val found = withContext(Dispatchers.IO) { AuraShare.discover(app) }
        host = withContext(Dispatchers.IO) { AuraShare.localAddress(app) }
        devices = found
        scanning = false
        if (found.isEmpty()) note =
            "Nothing answered on this network" + (host?.let { " (this phone is $it)" } ?: "") +
                ". The TV has to be on the same Wi-Fi with its media sharing turned on - " +
                "it is called DLNA, Screen Share or AllShare depending on the brand."
    }

    suspend fun start(renderer: Renderer, media: AppMediaItem) {
        note = null
        val host = AuraShare.localAddress(app)
        if (host == null) {
            note = "This phone has no address on the network right now."
            return
        }
        val ok = withContext(Dispatchers.IO) {
            val url = server.serve(media, host) ?: return@withContext false
            AuraShare.play(renderer, url, media.title, media.mimeType, media.durationMs)
        }
        if (ok) {
            active = renderer
            item = media
            playing = true
            positionMs = 0L
        } else {
            server.stop()
            note = "${renderer.name} would not take that file. Some devices " +
                "refuse formats they cannot decode - MKV and HEVC are the usual two."
        }
    }

    suspend fun toggle() {
        val r = active ?: return
        val wasPlaying = playing
        val ok = withContext(Dispatchers.IO) {
            if (wasPlaying) AuraShare.pause(r) else AuraShare.resume(r)
        }
        if (ok) playing = !wasPlaying
    }

    suspend fun seek(fraction: Float) {
        val r = active ?: return
        val total = item?.durationMs ?: return
        if (total <= 0L) return
        val ms = (fraction.coerceIn(0f, 1f) * total).toLong()
        positionMs = ms
        withContext(Dispatchers.IO) { AuraShare.seek(r, ms) }
    }

    suspend fun stop() {
        val r = active
        active = null
        item = null
        playing = false
        positionMs = 0L
        withContext(Dispatchers.IO) {
            if (r != null) AuraShare.stop(r)
            server.stop()
        }
    }

    /**
     * Ask the renderer where it is, once a second. This is the only feedback
     * the protocol gives, and it is reported in whole seconds - which is fine
     * for a progress line and is exactly why picture and sound can never be
     * split across two devices.
     */
    suspend fun poll() {
        while (active != null) {
            val r = active ?: break
            val (pos, state) = withContext(Dispatchers.IO) {
                AuraShare.position(r) to AuraShare.transportState(r)
            }
            if (pos >= 0) positionMs = pos
            when (state) {
                "PLAYING" -> playing = true
                "PAUSED_PLAYBACK" -> playing = false
                "STOPPED" -> if (positionMs > 0L) stop()
            }
            delay(1000)
        }
    }
}

@Composable
fun ShareSheet(
    share: ShareController,
    nowPlaying: AppMediaItem?,
    onDismiss: () -> Unit
) {
    LaunchedEffect(Unit) { if (share.active == null) share.scan() }
    LaunchedEffect(share.active) { share.poll() }

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
                // Swallow taps so the scrim underneath does not close the
                // sheet when you touch the sheet itself.
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .padding(Space.xl)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Aura Share", style = Typo.Section, color = MediaColors.Cream)
                Spacer(Modifier.weight(1f))
                if (share.scanning) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp), color = MediaColors.Accent, strokeWidth = 2.dp
                    )
                } else {
                    val scope = rememberCoroutineScope()
                    Icon(
                        Icons.Filled.Refresh, "Search again",
                        tint = MediaColors.CreamDim,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { scope.launch { share.scan() } }
                            .padding(Space.sm)
                            .size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                "Plays on the other device, controlled from here. Both need the same Wi-Fi.",
                style = Typo.Secondary, color = MediaColors.CreamFaint
            )
            Spacer(Modifier.height(Space.lg))

            val current = share.active
            if (current != null) {
                ActiveRenderer(share, current)
            } else {
                if (nowPlaying == null) {
                    Text(
                        "Start something playing first, then pick where to send it.",
                        style = Typo.Secondary, color = MediaColors.CreamDim
                    )
                    Spacer(Modifier.height(Space.md))
                }
                val scope = rememberCoroutineScope()
                share.devices.forEach { r ->
                    DeviceRow(r, enabled = nowPlaying != null) {
                        nowPlaying?.let { media -> scope.launch { share.start(r, media) } }
                    }
                }
            }

            share.note?.let {
                Spacer(Modifier.height(Space.md))
                Text(it, style = Typo.Secondary, color = MediaColors.CreamDim)
            }
            Spacer(Modifier.height(Space.md))
        }
    }
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
private fun ActiveRenderer(share: ShareController, r: Renderer) {
    val scope = rememberCoroutineScope()
    val total = share.item?.durationMs ?: 0L
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CastConnected, null, tint = MediaColors.Accent,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = Typo.Primary, color = MediaColors.Cream, maxLines = 1)
            Text(
                share.item?.title.orEmpty(), style = Typo.Secondary,
                color = MediaColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
    Spacer(Modifier.height(Space.md))

    var dragFrac by remember { mutableStateOf(-1f) }
    val frac = if (dragFrac >= 0f) dragFrac
    else if (total > 0L) (share.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(r.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    dragFrac = (down.position.x / w).coerceIn(0f, 1f)
                    down.consume()
                    while (true) {
                        val e = awaitPointerEvent()
                        val p = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) break
                        dragFrac = (p.position.x / w).coerceIn(0f, 1f)
                        p.consume()
                    }
                    val target = dragFrac
                    dragFrac = -1f
                    scope.launch { share.seek(target) }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(Radius.pill))
                .background(MediaColors.Fill)
        ) {
            Box(
                Modifier.fillMaxWidth(frac).fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.pill)).background(MediaColors.Accent)
            )
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${fmtClock(share.positionMs)} / ${fmtClock(total)}",
            style = Typo.Micro, color = MediaColors.CreamFaint
        )
        Spacer(Modifier.weight(1f))
        Icon(
            if (share.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (share.playing) "Pause" else "Play",
            tint = MediaColors.Cream,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { scope.launch { share.toggle() } }
                .padding(Space.sm)
                .size(24.dp)
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            "Stop",
            style = Typo.Label, color = MediaColors.Accent,
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .border(1.dp, MediaColors.Fill, RoundedCornerShape(Radius.pill))
                .clickable { scope.launch { share.stop() } }
                .padding(horizontal = Space.lg, vertical = Space.sm)
        )
    }
}
