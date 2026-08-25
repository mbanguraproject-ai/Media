package com.media.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

// ============================================================================
//  QUEUE (§25)
//
//  Reorder does NOT mutate the list while dragging. Each row's displacement is
//  derived from where the dragged item currently sits, then animated — so rows
//  glide out of the way instead of snapping. The move is committed to Media3
//  once on drop.
//
//  This also sidesteps LazyColumn's duplicate-key crash: a Media3 queue may
//  legitimately contain the same track twice, so there is no stable unique key
//  to give it.
// ============================================================================

private val ROW_HEIGHT = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<QueueEntry>,
    // Resolves a queue entry back to a library item so real cover art can be
    // shown; the Media3 timeline only carries title/artist/uri.
    artFor: (QueueEntry) -> AppMediaItem?,
    currentIndex: Int,
    onPlayIndex: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClearUpNext: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current
    val rowPx = with(density) { ROW_HEIGHT.toPx() }

    // -1 when idle. `from` is the original index, `offset` the live drag delta.
    var dragFrom by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    val dragTo = if (dragFrom < 0) -1
    else (dragFrom + (dragOffset / rowPx).roundToInt()).coerceIn(0, queue.lastIndex.coerceAtLeast(0))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MediaColors.Modal,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MediaColors.CreamFaint) }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Space.xl, 0.dp, Space.lg, Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Queue", style = Typo.Section, color = MediaColors.Cream)
                val upNext = (queue.size - currentIndex - 1).coerceAtLeast(0)
                Text(
                    if (upNext == 1) "1 track up next" else "$upNext tracks up next",
                    style = Typo.Tertiary, color = MediaColors.CreamFaint
                )
            }
            if (queue.size > currentIndex + 1) {
                Text(
                    "Clear", style = Typo.Label, color = MediaColors.Accent,
                    modifier = Modifier.pressScale(haptic = true, onClick = onClearUpNext)
                        .padding(Space.sm)
                )
            }
        }

        if (queue.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Nothing queued", style = Typo.Body, color = MediaColors.CreamFaint)
            }
            return@ModalBottomSheet
        }

        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 460.dp),
            contentPadding = PaddingValues(bottom = Space.xl)
        ) {
            items(queue.size) { i ->
                val entry = queue[i]
                val isDragged = i == dragFrom

                // Rows between the drag's origin and its current target shift
                // by exactly one row height, in the opposite direction.
                val shift = when {
                    dragFrom < 0 -> 0f
                    isDragged -> 0f
                    dragFrom < i && i <= dragTo -> -rowPx
                    dragTo <= i && i < dragFrom -> rowPx
                    else -> 0f
                }
                val animShift by animateFloatAsState(
                    targetValue = shift, animationSpec = Motion.spatial(), label = "queueShift"
                )
                val lift by animateFloatAsState(
                    targetValue = if (isDragged) 1.03f else 1f,
                    animationSpec = Motion.spatial(), label = "queueLift"
                )

                // §36: swipe to remove. Keyed on index+uri so the dismiss
                // state can't survive onto whatever row shifts into this slot
                // after a removal.
                key(i, entry.uri) {
                    val dismiss = rememberSwipeToDismissBoxState(
                        confirmValueChange = { v ->
                            if (v == SwipeToDismissBoxValue.EndToStart) { onRemove(i); true }
                            else false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismiss,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Row(
                                Modifier.fillMaxSize()
                                    .background(MediaColors.Danger.copy(alpha = 0.22f))
                                    .padding(horizontal = Space.xl),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Delete, null, tint = MediaColors.Danger,
                                    modifier = Modifier.size(IconSize.lg))
                            }
                        },
                        modifier = Modifier
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragged) dragOffset else animShift
                                scaleX = lift; scaleY = lift
                                shadowElevation = if (isDragged) 16f else 0f
                            }
                    ) {
                QueueRow(
                    entry = entry,
                    art = artFor(entry),
                    isCurrent = i == currentIndex,
                    isPast = i < currentIndex,
                    modifier = Modifier,
                    onPlay = { onPlayIndex(i) },
                    onRemove = { onRemove(i) },
                    dragModifier = Modifier.pointerInput(queue.size) {
                        detectDragGestures(
                            onDragStart = { dragFrom = i; dragOffset = 0f },
                            onDrag = { change, delta ->
                                change.consume(); dragOffset += delta.y
                            },
                            onDragEnd = {
                                if (dragFrom >= 0 && dragTo != dragFrom) onMove(dragFrom, dragTo)
                                dragFrom = -1; dragOffset = 0f
                            },
                            onDragCancel = { dragFrom = -1; dragOffset = 0f }
                        )
                    }
                )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    art: AppMediaItem?,
    isCurrent: Boolean,
    isPast: Boolean,
    modifier: Modifier,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    dragModifier: Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            // MUST be opaque. SwipeToDismissBox paints backgroundContent
            // BEHIND this row, so a transparent row shows the red delete panel
            // permanently instead of only while swiping.
            .background(if (isCurrent) MediaColors.Elevated else MediaColors.Modal)
            .clickable(onClick = onPlay)
            .padding(horizontal = Space.xl),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.sm))) {
            // Was generative-only, so queue rows never showed real covers.
            if (art != null) {
                CoverArt(art, Modifier.fillMaxSize(), corner = 0,
                    targetPx = 144)
            } else {
                GenerativeArtwork(entry.mediaId, entry.title, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title, style = Typo.Primary,
                color = when {
                    isCurrent -> MediaColors.Accent
                    isPast -> MediaColors.CreamFaint
                    else -> MediaColors.Cream
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.artist.ifBlank { UNKNOWN_ARTIST }, style = Typo.Secondary,
                color = MediaColors.CreamFaint, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Filled.Delete, "Remove from queue", tint = MediaColors.CreamFaint,
            modifier = Modifier.size(IconSize.md).pressScale(haptic = true, onClick = onRemove)
        )
        Spacer(Modifier.width(Space.md))
        // Dedicated grip: keeps the reorder gesture off the row itself, so tap
        // to play and drag to reorder can't fight each other.
        Icon(
            Icons.Filled.DragHandle, "Reorder", tint = MediaColors.CreamDim,
            modifier = Modifier.size(IconSize.lg).then(dragModifier)
        )
    }
}
