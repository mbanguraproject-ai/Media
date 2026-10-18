package com.media.app

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

// ============================================================================
//  SWIPE BETWEEN TABS
//
//  The four tabs sit side by side, in the bottom bar's order, and a sideways
//  swipe walks along them. Inside a tab the swipe moves between its views
//  first (Music -> Video -> Podcasts -> Audiobooks, Albums -> Artists ->
//  Recordings, Mine -> Smart); pulled on past the last view, it carries on
//  into the next tab, and past the first view, back into the one before.
//
//  The pager inside a tab hands on whatever drag it cannot use - at its
//  first or last page, all of it - as nested scroll, and this collects that.
//  A screen with no pager (Settings) has nothing to hand it on, so the same
//  modifier also reads plain horizontal drags; a pager, when there is one,
//  takes the pointer first and the two never both fire.
//
//  A deliberate pull is needed - a fifth of a typical screen, or a flick -
//  so a page swipe that overshoots by a little stays on the page.
// ============================================================================

private val PullToSwitch = 80.dp
private const val FlickToSwitch = 1_400f   // px/s

@Composable
fun Modifier.tabSwipe(onPrevious: (() -> Unit)?, onNext: (() -> Unit)?): Modifier {
    val threshold = with(LocalDensity.current) { PullToSwitch.toPx() }
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)

    fun settle(pull: Float, velocity: Float) {
        when {
            pull < 0f && (pull < -threshold || velocity < -FlickToSwitch) -> next?.invoke()
            pull > 0f && (pull > threshold || velocity > FlickToSwitch) -> previous?.invoke()
        }
    }

    val connection = remember(threshold) {
        object : NestedScrollConnection {
            var pull = 0f
            // Scroll that arrives between a fling's start and end is the
            // pager settling, not the finger.
            var flinging = false

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (flinging || available.x == 0f) return Offset.Zero
                pull += available.x
                return Offset(available.x, 0f)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                flinging = true
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                flinging = false
                val p = pull
                pull = 0f
                settle(p, available.x)
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .pointerInput(threshold) {
            var pull = 0f
            detectHorizontalDragGestures(
                onDragStart = { pull = 0f },
                onDragEnd = { settle(pull, 0f); pull = 0f },
                onDragCancel = { pull = 0f }
            ) { change, amount ->
                pull += amount
                change.consume()
            }
        }
}
