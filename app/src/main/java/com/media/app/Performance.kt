package com.media.app

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

// ============================================================================
//  SUSTAINED FRAME PERFORMANCE
//
//  The blueprint asks for sustained frame performance to be monitored and for
//  expensive rendering to adapt when it is not being met - without ever
//  interrupting playback.
//
//  WHY NOT withFrameNanos: a `while (true) { withFrameNanos { } }` loop is the
//  obvious implementation and it is wrong twice over. It REQUESTS a frame every
//  vsync, so the app can never idle - a permanent 90fps render for a monitor.
//  And it measures the wrong thing: with nothing changing on screen every frame
//  is trivially cheap, so a static library would report perfect health.
//
//  Window.OnFrameMetricsAvailableListener (API 24, so always available here) is
//  passive. It reports frames the app actually drew, with the real end-to-end
//  duration, and says nothing at all while the app is idle. No frames means no
//  measurement, which is correct: an app drawing nothing has no problem.
//
//  PLAYBACK IS NEVER TOUCHED. This changes a rendering profile and nothing
//  else. It does not pause, re-prepare, seek, or reconfigure the player - the
//  audio pipeline has no idea this exists.
//
//  A MANUAL CHOICE IS NEVER OVERRIDDEN. The doc is explicit that an override is
//  allowed and that sustained trouble should OFFER Auto rather than force it.
//  So on Auto this steps the tier down; on a manual pick it raises `strained`
//  and leaves the choice alone for Settings to surface.
//
//  It only ever steps DOWN, and only within a session. Recovering automatically
//  would oscillate: stepping up restores the cost that caused the drop, which
//  drops frames, which steps down again. A fresh launch re-measures from the
//  detected ceiling.
//
//  AT THE FLOOR IT LIGHTENS. Enhanced has no tier below it, and since 4.8 it
//  drifts and carries a light field, so a step down had nowhere to go. On Auto
//  it now sheds its costliest pieces one at a time instead (relieved() in
//  DisplayQuality.kt): the flow, turn and per-line lyric blur, then the light
//  field, then the drift. Settings says so, as it reports a step down.
// ============================================================================

/** Frames counted per evaluation window. Only frames actually drawn count. */
private const val FrameWindow = 240

/** Share of frames over budget that makes a window "bad". */
private const val LateRatio = 0.25f

/** Consecutive bad windows before acting. One bad window is a scroll, not a verdict. */
private const val Strikes = 2

/** A frame may run 30% over vsync before it counts as late. */
private const val BudgetGrace = 1.30f

data class AdaptiveQuality(
    /** What to actually render. */
    val level: QualityLevel,
    /** What the user or the ceiling asked for. */
    val requested: QualityLevel,
    /** Sustained trouble seen at a manually-chosen level. */
    val strained: Boolean,
    /** Lighter steps taken at the floor tier, 0..MAX_RELIEF (QualityProfile.relieved). */
    val relief: Int = 0
) {
    val steppedDown: Boolean get() = level.ordinal < requested.ordinal
    val lightened: Boolean get() = relief > 0
}

val LocalAdaptiveQuality = compositionLocalOf {
    AdaptiveQuality(QualityLevel.ENHANCED, QualityLevel.ENHANCED, false)
}

private fun QualityLevel.stepDown(): QualityLevel =
    QualityLevel.values()[(ordinal - 1).coerceAtLeast(0)]

@Composable
fun rememberAdaptiveQuality(
    requested: QualityLevel,
    autoMode: Boolean,
    refreshHz: Float
): AdaptiveQuality {
    // Keyed on `requested`: changing the setting restarts from the new level
    // rather than inheriting an earlier session's downgrade.
    var level by remember(requested) { mutableStateOf(requested) }
    var strained by remember(requested) { mutableStateOf(false) }
    var relief by remember(requested) { mutableStateOf(0) }
    val context = LocalContext.current

    // The device's REAL refresh rate, never an assumed 60 or 120.
    val budgetNanos = remember(refreshHz) {
        (1_000_000_000.0 / refreshHz.coerceIn(24f, 240f) * BudgetGrace).toLong()
    }

    DisposableEffect(requested, autoMode, budgetNanos, context) {
        val window: Window? = (context as? Activity)?.window
        if (window == null) return@DisposableEffect onDispose { }

        // The listener is invoked on this thread, so the counters below are
        // only ever touched by it. State changes are posted to main.
        val thread = HandlerThread("aura-frame-metrics").apply { start() }
        val handler = Handler(thread.looper)
        val main = Handler(Looper.getMainLooper())

        var seen = 0
        var late = 0
        var strikes = 0

        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            // A first-draw frame carries one-off layout and inflation cost. It
            // is real, but it is not the sustained performance we are judging.
            val firstDraw = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L
            if (total > 0L && !firstDraw) {
                seen++
                if (total > budgetNanos) late++
            }
            if (seen >= FrameWindow) {
                val ratio = late.toFloat() / seen
                seen = 0
                late = 0
                strikes = if (ratio >= LateRatio) strikes + 1 else 0
                if (strikes >= Strikes) {
                    strikes = 0
                    main.post {
                        if (autoMode) {
                            if (level.ordinal > 0) level = level.stepDown()
                            else if (relief < MAX_RELIEF) relief++
                        } else {
                            // Never overridden. Settings offers Auto instead.
                            strained = true
                        }
                    }
                }
            }
        }

        window.addOnFrameMetricsAvailableListener(listener, handler)
        onDispose {
            runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }
    }

    return AdaptiveQuality(level, requested, strained, relief)
}
