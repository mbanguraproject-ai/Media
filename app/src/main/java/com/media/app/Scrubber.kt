package com.media.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

// ============================================================================
//  SCRUBBER — the track's real waveform
//
//  Not decoration. EnvelopeAnalyzer already computes a 50Hz bass envelope for
//  every music file and caches it in Room; BeatPulse already reads it to drive
//  the artwork. The same curve drawn along the transport is THIS track's actual
//  shape - loud passages are tall because they are loud.
//
//  Falls back to a plain track when there is no envelope (video, spoken word,
//  or analysis still running). It draws a flat line rather than inventing a
//  waveform, because a fake waveform is worse than none: it would animate
//  convincingly while telling you nothing about the audio.
//
//  Bar count comes from the display-quality tier - Enhanced draws 72, Ultra
//  draws 120. This is the first place a tier genuinely changes what is on
//  screen rather than only how sharp it is.
//
//  Behaviour this replaces: Material's Slider called seekTo() from
//  onValueChange, which fires continuously during a drag - dozens of ExoPlayer
//  seeks per scrub, each tearing down the audio pipeline (and flushing the
//  decoder on video). The drag is local here; one seek happens, on release.
//  Move events are consumed so a scrub is never read as drag-to-collapse by
//  the player sheet.
//
//  Geometry note: the track is inset by a FIXED amount at both ends. The
//  mapping from x to time never moves, so the audio cannot drift away from
//  the finger mid-drag.
// ============================================================================

private val TouchHeight = 52.dp
private val BarGap = 2.dp
// Ultra's 120 bars: 2dp gaps would leave each bar under 1dp on a phone; 1dp
// gaps keep them a little wider than Premium's 96 (1.8dp against 1.5dp on a
// typical phone) while drawing a finer wave.
private val FineBarGap = 1.dp
private const val FINE_BARS = 120
private val BarMin = 3.dp        // silence is still a visible line
private val BarMax = 34.dp
private val PlainTrack = 4.dp
private val Inset = 6.dp
private val LoopMark = 2.dp
private val HeadWidth = 2.5.dp
private val PlainHeadHeight = 16.dp

/**
 * Peak-downsample to [n] buckets, normalised so the loudest bar is 1.
 *
 * Peak rather than mean: averaging flattens transients, and a waveform without
 * its transients reads as a lumpy sausage. Normalising matters because a
 * quietly-mastered track would otherwise render as a nearly flat line.
 */
internal fun FloatArray.toBars(n: Int): FloatArray {
    if (isEmpty() || n <= 0) return FloatArray(0)
    val out = FloatArray(n)
    val per = size.toFloat() / n
    var peak = 0f
    for (i in 0 until n) {
        val a = (i * per).toInt().coerceIn(0, lastIndex)
        val b = ((i + 1) * per).toInt().coerceIn(a + 1, size)
        var m = 0f
        for (k in a until b) if (this[k] > m) m = this[k]
        out[i] = m
        if (m > peak) peak = m
    }
    if (peak > 0.0001f) for (i in out.indices) out[i] = out[i] / peak
    return out
}

@Composable
fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    // The track's real envelope. Null = plain track, never a fabricated wave.
    envelope: FloatArray? = null,
    // Live level, so the bar under the playhead breathes with the music.
    beat: BeatState? = null,
    activeColor: Color = MediaColors.Accent,
    inactiveColor: Color = MediaColors.InkHairline,
    thumbColor: Color = MediaColors.Cream,
    // A Via Pro A-B loop on this track: A, and B once it is set (else -1).
    loopAMs: Long = -1L,
    loopBMs: Long = -1L,
    onInteract: () -> Unit = {}
) {
    val reduced = LocalReducedMotion.current
    val barCount = LocalQuality.current.waveformBars
    val bars = remember(envelope, barCount) { envelope?.toBars(barCount) }

    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }

    val playFrac =
        if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else 0f
    // While dragging the head follows the FINGER, not playback. Reading
    // position here is what makes a scrubber feel like it fights back.
    val frac = if (dragging) dragFrac else playFrac

    val grow by animateFloatAsState(
        targetValue = if (dragging && !reduced) 1f else 0f,
        label = "scrubGrow"
    )

    val spoken = stringResource(R.string.scrubber_spoken, fmtClock((frac * durationMs).toLong()), fmtClock(durationMs))

    Box(
        modifier
            .fillMaxWidth()
            .height(TouchHeight)
            .semantics { contentDescription = spoken }
            .pointerInput(durationMs) {
                if (durationMs <= 0L) return@pointerInput
                val insetPx = Inset.toPx()
                fun fracAt(x: Float): Float {
                    val span = (size.width.toFloat() - insetPx * 2f).coerceAtLeast(1f)
                    return ((x - insetPx) / span).coerceIn(0f, 1f)
                }
                awaitEachGesture {
                    // requireUnconsumed = false: the sheet above may already
                    // have looked at this down event.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    dragFrac = fracAt(down.position.x)      // a tap seeks too
                    onInteract()
                    while (true) {
                        val event = awaitPointerEvent()
                        val p = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) break
                        dragFrac = fracAt(p.position.x)
                        // Consumed so the player sheet does not read a scrub
                        // as drag-to-collapse.
                        p.consume()
                        onInteract()
                    }
                    onSeek((dragFrac * durationMs).toLong())
                    dragging = false
                }
            }
    ) {
        // Its own layer: the bar under the playhead moves with the beat
        // every frame, and that redraw should not take the player with it.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val cy = size.height / 2f
            val insetPx = Inset.toPx()
            val span = (size.width - insetPx * 2f).coerceAtLeast(1f)
            val headX = insetPx + span * frac
            // Read inside draw: level changes 60x a second, so reading it in
            // composition would recompose the whole player every frame.
            // The cone's push: the playhead's bar thumps with the cover, in phase.
            val lv = beat?.push ?: 0f

            // The loop, under everything: a soft band from A to B, and a
            // hairline at each end. With only A set, just A's line.
            if (loopAMs >= 0L && durationMs > 0L) {
                val ax = insetPx + span * (loopAMs.toFloat() / durationMs).coerceIn(0f, 1f)
                val bx = if (loopBMs > loopAMs) insetPx + span * (loopBMs.toFloat() / durationMs).coerceIn(0f, 1f) else -1f
                val top = cy - size.height * 0.42f
                val bandH = size.height * 0.84f
                if (bx > ax) {
                    drawRoundRect(
                        color = activeColor.copy(alpha = 0.16f),
                        topLeft = Offset(ax, top), size = Size(bx - ax, bandH),
                        cornerRadius = CornerRadius(LoopMark.toPx() * 2f)
                    )
                }
                for (x in listOf(ax, bx)) if (x >= 0f) {
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(x - LoopMark.toPx() / 2f, top), size = Size(LoopMark.toPx(), bandH),
                        cornerRadius = CornerRadius(LoopMark.toPx() / 2f)
                    )
                }
            }

            if (bars != null && bars.isNotEmpty()) {
                val n = bars.size
                val gap = (if (n >= FINE_BARS) FineBarGap else BarGap).toPx()
                val barW = ((span - gap * (n - 1)) / n).coerceAtLeast(1f)
                val minH = BarMin.toPx()
                val maxH = BarMax.toPx() * (1f + 0.10f * grow)
                val headIndex = (frac * n).toInt().coerceIn(0, n - 1)

                for (i in 0 until n) {
                    var h = minH + (maxH - minH) * bars[i]
                    // Only the bar at the playhead reacts, and only while the
                    // pulse is running. Every bar breathing would be a lava
                    // lamp, not a transport.
                    if (i == headIndex) h *= (1f + 0.34f * lv)
                    val x = insetPx + i * (barW + gap)
                    drawRoundRect(
                        color = if (i <= headIndex) activeColor else inactiveColor,
                        topLeft = Offset(x, cy - h / 2f),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2f)
                    )
                }
            } else {
                // No envelope: an honest flat track, not an invented wave.
                val h = PlainTrack.toPx() * (1f + 0.5f * grow)
                val r = h / 2f
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = Offset(insetPx, cy - r),
                    size = Size(span, h),
                    cornerRadius = CornerRadius(r)
                )
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(insetPx, cy - r),
                    size = Size((headX - insetPx).coerceAtLeast(0f), h),
                    cornerRadius = CornerRadius(r)
                )
            }

            // NO cursor over the waveform. The colour boundary between played
            // and unplayed bars already IS the position, and a white bar drawn
            // on top of it cut the wave in half - it read as damage rather
            // than as a control.
            //
            // The plain track has no bars to carry that information, so video
            // keeps its pill.
            if (bars == null) {
                val hw = HeadWidth.toPx() * (1f + 0.45f * grow)
                val hh = PlainHeadHeight.toPx() * (1f + 0.16f * grow)
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(headX - hw / 2f, cy - hh / 2f),
                    size = Size(hw, hh),
                    cornerRadius = CornerRadius(hw / 2f)
                )
            }
        }
    }
}
