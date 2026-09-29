package com.media.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ============================================================================
//  LYRICS IN NOW PLAYING
//
//  Synced: the playing line in full cream, the lines around it dimmed, the
//  list gliding so the current line sits a third of the way down. Tapping a
//  line seeks there. The offset pills nudge timing half a second at a time
//  and remember the nudge for that track.
//
//  Plain: shown as text, labelled "Not synced", never animated as though it
//  were timed.
//
//  It sits in the artwork's place and borrows nothing new from the theme:
//  cream, dim, faint and the one accent.
// ============================================================================

private const val OFFSET_STEP_MS = 500L

@Composable
fun LyricsPanel(
    item: AppMediaItem,
    positionMs: Long,
    online: Boolean,
    onSeek: (Long) -> Unit,
    onEnableOnline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val revision by LyricsEngine.revision.collectAsState()
    var force by remember(item.id) { mutableIntStateOf(0) }
    var state by remember(item.id) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    LaunchedEffect(item.id, online, force) {
        if (state !is LyricsState.Synced && state !is LyricsState.Plain) state = LyricsState.Loading
        state = LyricsEngine.load(context, item, online = online || force > 0, force = force > 0)
    }
    // Offset changes re-read only the stored offset, not the lyrics.
    var offset by remember(item.id) { mutableLongStateOf(LyricsStore.offset(item.id)) }
    LaunchedEffect(revision, item.id) { offset = LyricsStore.offset(item.id) }

    Box(modifier) {
        when (val s = state) {
            LyricsState.Loading -> Centered("Looking for lyrics…")
            is LyricsState.Synced -> Synced(s.lines, positionMs, offset, onSeek) { delta ->
                val next = (offset + delta).coerceIn(-10_000L, 10_000L)
                offset = next
                LyricsEngine.setOffset(item.id, next)
            }
            is LyricsState.Plain -> Plain(s.text, s.source)
            LyricsState.Instrumental -> Centered("Instrumental")
            is LyricsState.None -> Column(
                Modifier.fillMaxSize().padding(horizontal = Space.xl),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    when {
                        s.offline -> "Couldn't reach the lyrics service"
                        !online -> "No lyrics in this file"
                        else -> "No lyrics found for this track"
                    },
                    style = Typo.Primary, color = MediaColors.CreamDim, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    if (!online) "Turn on online lookups to search LRCLIB for synced lyrics."
                    else "Add a .lrc file next to the track, or try again later.",
                    style = Typo.Tertiary, color = MediaColors.CreamFaint, textAlign = TextAlign.Center
                )
                if (s.online) {
                    Spacer(Modifier.height(Space.md))
                    Pill(if (!online) "Search online" else "Search again") {
                        if (!online) onEnableOnline()
                        force++
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = Typo.Primary, color = MediaColors.CreamFaint)
    }
}

@Composable
private fun Pill(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape)
            .background(if (active) MediaColors.Accent else MediaColors.Fill)
            .pressScale(haptic = true, onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.xs)
    ) {
        Text(label, style = Typo.Label, color = if (active) MediaColors.OnAccent else MediaColors.CreamDim)
    }
}

@Composable
private fun Synced(
    lines: List<LyricLine>,
    positionMs: Long,
    offsetMs: Long,
    onSeek: (Long) -> Unit,
    onNudge: (Long) -> Unit
) {
    val active = Lrc.activeIndex(lines, positionMs, offsetMs)
    val list = rememberLazyListState()
    val density = LocalDensity.current
    var containerPx by remember { mutableIntStateOf(0) }
    // Follow playback, unless the listener is dragging the list right now.
    // The top content padding is a third of the height, so scrolling an item
    // to offset 0 rests it a third of the way down.
    LaunchedEffect(active, containerPx) {
        if (active < 0 || containerPx == 0 || list.isScrollInProgress) return@LaunchedEffect
        list.animateScrollToItem(active, 0)
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(horizontal = Space.xl, vertical = with(density) { (containerPx / 3).toDp() }),
                verticalArrangement = Arrangement.spacedBy(Space.md),
                modifier = Modifier.fillMaxSize().onSizeChanged { containerPx = it.height }
            ) {
                itemsIndexed(lines) { i, line ->
                    val isNow = i == active
                    val color by animateColorAsState(
                        when {
                            isNow -> MediaColors.Cream
                            i < active -> MediaColors.CreamFaint
                            else -> MediaColors.CreamDim
                        },
                        tween(Motion.Standard), label = "lyric"
                    )
                    Text(
                        text = line.text.ifBlank { "♪" },
                        style = if (isNow) Typo.Section.copy(fontWeight = FontWeight.SemiBold) else Typo.Section,
                        color = color,
                        modifier = Modifier.fillMaxWidth()
                            .graphicsLayer { alpha = if (isNow) 1f else 0.92f }
                            // Plain click, not pressScale: its 48dp minimum
                            // would space short lines far apart.
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSeek((line.timeMs - offsetMs).coerceAtLeast(0L)) }
                    )
                }
            }
            // Soft edges so lines fade in and out rather than being cut.
            val ink = MediaColors.Ink
            Box(Modifier.fillMaxWidth().height(28.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(ink.copy(alpha = 0.9f), Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(28.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, ink.copy(alpha = 0.9f)))))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Synced", style = Typo.Micro, color = MediaColors.Accent)
            Spacer(Modifier.weight(1f))
            Pill("− 0.5s") { onNudge(-OFFSET_STEP_MS) }
            Spacer(Modifier.width(Space.xs))
            Text(
                if (offsetMs == 0L) "In time" else "%+.1fs".format(java.util.Locale.ROOT, offsetMs / 1000f),
                style = Typo.Tertiary, color = if (offsetMs == 0L) MediaColors.CreamFaint else MediaColors.Accent,
                modifier = Modifier.pressScale { if (offsetMs != 0L) onNudge(-offsetMs) }
            )
            Spacer(Modifier.width(Space.xs))
            Pill("+ 0.5s") { onNudge(OFFSET_STEP_MS) }
        }
    }
}

@Composable
private fun Plain(text: String, source: LyricsSource) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl, vertical = Space.md)
        ) {
            Text(text, style = Typo.Body, color = MediaColors.CreamDim)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("NOT SYNCED", style = Typo.Micro, color = MediaColors.CreamFaint)
            Spacer(Modifier.weight(1f))
            Text(source.label, style = Typo.Tertiary, color = MediaColors.CreamFaint)
        }
    }
}
