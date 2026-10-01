package com.media.app

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// ============================================================================
//  LYRICS IN NOW PLAYING
//
//  Synced: the playing line in full cream at full size, the lines around it
//  smaller and dimmed, the list gliding so the current line sits a third of
//  the way down. On Premium and Ultra the lines further away also go soft,
//  the way a lens focuses on one plane, and on Ultra the sung line glows in
//  the accent. Tapping a line seeks there. The offset control nudges timing
//  half a second at a time and remembers the nudge for that track.
//
//  Plain: shown as text, labelled "Not synced", never animated as though it
//  were timed.
//
//  Your own lyrics: the ⋯ button (or the buttons on the empty state) loads a
//  .lrc or .txt from the phone, or takes pasted text. Timed LRC plays synced.
//  They outrank every other source until you remove them.
//
//  The edges fade by MASKING the list, not by painting ink over it: an ink
//  bar showed as a dark band across the cover-tinted backdrop.
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
    val scope = rememberCoroutineScope()
    val revision by LyricsEngine.revision.collectAsState()
    var force by remember(item.id) { mutableIntStateOf(0) }
    var reload by remember(item.id) { mutableIntStateOf(0) }
    var state by remember(item.id) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    LaunchedEffect(item.id, online, force, reload) {
        if (state !is LyricsState.Synced && state !is LyricsState.Plain) state = LyricsState.Loading
        state = LyricsEngine.load(context, item, online = online || force > 0, force = force > 0)
    }
    // Offset changes re-read only the stored offset, not the lyrics.
    var offset by remember(item.id) { mutableLongStateOf(LyricsStore.offset(item.id)) }
    LaunchedEffect(revision, item.id) { offset = LyricsStore.offset(item.id) }

    var showActions by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var note by remember(item.id) { mutableStateOf<String?>(null) }

    // .lrc has no registered MIME type on most phones, so the picker has to
    // offer everything; the text is checked after it is read.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                LyricsEngine.readText(context, uri)?.let { LyricsEngine.setUserLyrics(item.id, it) } ?: false
            }
            note = if (ok) null else "That file had no lyrics in it"
            if (ok) reload++
        }
    }
    fun pickFile() = runCatching { picker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }
    val userOwned = (state as? LyricsState.Synced)?.source == LyricsSource.USER ||
        (state as? LyricsState.Plain)?.source == LyricsSource.USER

    Box(modifier) {
        when (val s = state) {
            LyricsState.Loading -> Centered("Looking for lyrics…")
            is LyricsState.Synced -> Synced(
                lines = s.lines, source = s.source, positionMs = positionMs, offsetMs = offset,
                onSeek = onSeek, onMore = { showActions = true }
            ) { delta ->
                val next = (offset + delta).coerceIn(-10_000L, 10_000L)
                offset = next
                LyricsEngine.setOffset(item.id, next)
            }
            is LyricsState.Plain -> Plain(s.text, s.source) { showActions = true }
            LyricsState.Instrumental -> Centered("Instrumental")
            is LyricsState.None -> Column(
                Modifier.fillMaxSize().padding(horizontal = Space.xl),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    when {
                        s.offline -> "Couldn't reach the lyrics services"
                        !online -> "No lyrics in this file"
                        else -> "No lyrics found for this track"
                    },
                    style = Typo.Primary, color = MediaColors.CreamDim, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    if (!online) "Search online, or add your own: a .lrc or .txt from your phone, or pasted text."
                    else "Add your own: a .lrc or .txt from your phone, or pasted text. A lyrics folder in Settings is searched too.",
                    style = Typo.Tertiary, color = MediaColors.CreamFaint, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Space.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Pill("From a file") { pickFile() }
                    Pill("Paste") { showPaste = true }
                    if (s.online) {
                        Pill(if (!online) "Search online" else "Search again", active = true) {
                            if (!online) onEnableOnline()
                            force++
                        }
                    }
                }
                note?.let {
                    Spacer(Modifier.height(Space.sm))
                    Text(it, style = Typo.Tertiary, color = MediaColors.Accent)
                }
            }
        }
    }

    if (showActions) {
        LyricsActions(
            canRemove = userOwned,
            onSearch = {
                showActions = false
                if (!online) onEnableOnline()
                force++
            },
            onFile = { showActions = false; pickFile() },
            onPaste = { showActions = false; showPaste = true },
            onRemove = {
                showActions = false
                LyricsEngine.clearUserLyrics(item.id)
                reload++
            },
            onDismiss = { showActions = false }
        )
    }
    if (showPaste) {
        PasteLyrics(
            title = item.title,
            onSave = { text ->
                if (LyricsEngine.setUserLyrics(item.id, text)) {
                    showPaste = false
                    reload++
                }
            },
            onDismiss = { showPaste = false }
        )
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

/** Fades content to transparent at the top and bottom by masking it. */
private fun Modifier.edgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.10f to Color.Black,
                0.86f to Color.Black,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }

@Composable
private fun Synced(
    lines: List<LyricLine>,
    source: LyricsSource,
    positionMs: Long,
    offsetMs: Long,
    onSeek: (Long) -> Unit,
    onMore: () -> Unit,
    onNudge: (Long) -> Unit
) {
    val active = Lrc.activeIndex(lines, positionMs, offsetMs)
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val q = LocalQuality.current
    // Depth of field: real blur exists from Android 12, and only the richer
    // tiers pay for it.
    val focusBlur = q.lyricFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val glow = q.lyricGlow
    val accent = MediaColors.Accent
    var containerPx by remember { mutableIntStateOf(0) }
    // Follow playback, unless the listener is dragging the list right now.
    // The top content padding is a third of the height, so scrolling an item
    // to offset 0 rests it a third of the way down.
    LaunchedEffect(active, containerPx) {
        if (active < 0 || containerPx == 0 || list.isScrollInProgress) return@LaunchedEffect
        list.animateScrollToItem(active, 0)
    }
    val browsing = list.isScrollInProgress

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().edgeFade()) {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(horizontal = Space.xl, vertical = with(density) { (containerPx / 3).toDp() }),
                verticalArrangement = Arrangement.spacedBy(Space.lg),
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
                    val scale by animateFloatAsState(
                        if (isNow) 1f else 0.94f, Motion.spatial(), label = "lyricScale"
                    )
                    val distance = if (active < 0) 0 else abs(i - active)
                    val soft = if (!focusBlur || browsing || distance < 2) 0f
                        else ((distance - 1) * 1.1f).coerceAtMost(3.2f)
                    val base = Typo.Section
                    Text(
                        text = line.text.ifBlank { "♪" },
                        style = if (isNow) base.copy(
                            fontWeight = FontWeight.SemiBold,
                            shadow = if (glow) Shadow(accent.copy(alpha = 0.55f), blurRadius = 28f) else null
                        ) else base,
                        color = color,
                        modifier = Modifier.fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            }
                            .then(if (soft > 0f) Modifier.blur(soft.dp, BlurredEdgeTreatment.Unbounded) else Modifier)
                            // Plain click, not pressScale: its 48dp minimum
                            // would space short lines far apart.
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSeek((line.timeMs - offsetMs).coerceAtLeast(0L)) }
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.md, top = Space.xs, bottom = Space.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("SYNCED", style = Typo.Micro, color = MediaColors.Accent)
                Text(source.label, style = Typo.Tertiary, color = MediaColors.CreamFaint, maxLines = 1)
            }
            // One quiet capsule instead of two loose pills and a label.
            Row(
                Modifier.clip(CircleShape).background(MediaColors.Fill),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CapsuleIcon(Icons.Filled.Remove, "Lyrics earlier") { onNudge(-OFFSET_STEP_MS) }
                Text(
                    if (offsetMs == 0L) "In time" else "%+.1fs".format(java.util.Locale.ROOT, offsetMs / 1000f),
                    style = Typo.Tertiary,
                    color = if (offsetMs == 0L) MediaColors.CreamFaint else MediaColors.Accent,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { if (offsetMs != 0L) onNudge(-offsetMs) }
                )
                CapsuleIcon(Icons.Filled.Add, "Lyrics later") { onNudge(OFFSET_STEP_MS) }
            }
            CapsuleIcon(Icons.Filled.MoreHoriz, "Lyrics options", onClick = onMore)
        }
    }
}

@Composable
private fun CapsuleIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).pressScale(haptic = true, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = MediaColors.CreamDim, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Plain(text: String, source: LyricsSource, onMore: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().edgeFade().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl, vertical = Space.lg)
        ) {
            Text(text, style = Typo.Body, color = MediaColors.CreamDim)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.md, top = Space.xs, bottom = Space.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("NOT SYNCED", style = Typo.Micro, color = MediaColors.CreamFaint)
                Text(source.label, style = Typo.Tertiary, color = MediaColors.CreamFaint, maxLines = 1)
            }
            CapsuleIcon(Icons.Filled.MoreHoriz, "Lyrics options", onClick = onMore)
        }
    }
}

// ------------------------------------------------------------ add your own

@Composable
private fun LyricsActions(
    canRemove: Boolean,
    onSearch: () -> Unit,
    onFile: () -> Unit,
    onPaste: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(20.dp))
                .background(MediaColors.Modal).border(1.dp, MediaColors.Fill, RoundedCornerShape(20.dp))
                .padding(vertical = Space.md)
        ) {
            Text("Lyrics", style = Typo.Section, color = MediaColors.Cream,
                modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.sm))
            // Your own lyrics outrank any search, so searching is offered
            // once they are removed, not alongside them.
            if (!canRemove) ActionLine(Icons.Filled.Search, "Search again online", onSearch)
            ActionLine(Icons.Filled.FolderOpen, "Load from a file (.lrc or .txt)", onFile)
            ActionLine(Icons.Filled.ContentPaste, "Paste lyrics", onPaste)
            if (canRemove) ActionLine(Icons.Filled.DeleteOutline, "Remove the lyrics you added", onRemove, danger = true)
        }
    }
}

@Composable
private fun ActionLine(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Space.xl, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (danger) MediaColors.Danger else MediaColors.Cream
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.md))
        Text(label, style = Typo.Primary, color = tint)
    }
}

@Composable
private fun PasteLyrics(title: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.92f).clip(RoundedCornerShape(20.dp))
                .background(MediaColors.Modal).border(1.dp, MediaColors.Fill, RoundedCornerShape(20.dp))
                .padding(Space.xl)
        ) {
            Text("Lyrics for $title", style = Typo.Section, color = MediaColors.Cream, maxLines = 1)
            Spacer(Modifier.height(Space.xs))
            Text("Timed LRC lines ([01:23.45] …) play synced; anything else shows as plain text.",
                style = Typo.Tertiary, color = MediaColors.CreamFaint)
            Spacer(Modifier.height(Space.md))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp)
                    .clip(RoundedCornerShape(12.dp)).background(MediaColors.FillSubtle)
                    .border(1.dp, MediaColors.Fill, RoundedCornerShape(12.dp))
                    .padding(Space.md)
            ) {
                if (text.isEmpty()) Text("Paste or type the lyrics", style = Typo.Body, color = MediaColors.CreamFaint)
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    textStyle = Typo.Body.copy(color = MediaColors.Cream),
                    cursorBrush = SolidColor(MediaColors.Accent),
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                )
            }
            Spacer(Modifier.height(Space.lg))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onDismiss)
                    .padding(horizontal = Space.lg, vertical = 10.dp)) {
                    Text("Cancel", style = Typo.Label, color = MediaColors.CreamDim)
                }
                Spacer(Modifier.width(Space.sm))
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(if (text.isBlank()) MediaColors.Accent.copy(alpha = 0.4f) else MediaColors.Accent)
                        .clickable(enabled = text.isNotBlank()) { onSave(text) }
                        .padding(horizontal = Space.lg, vertical = 10.dp)
                ) { Text("Save", style = Typo.Label, color = MediaColors.OnAccent) }
            }
        }
    }
}
