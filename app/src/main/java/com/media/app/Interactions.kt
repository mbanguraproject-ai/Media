package com.media.app

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// ============================================================================
//  MICRO-INTERACTIONS (§13, §32, §35) + REDUCED MOTION (§31)
//
//  Reduced motion is read from the system animator scale and threaded through
//  a CompositionLocal. Per §31, reduced motion never removes FUNCTION — it
//  swaps elaborate transitions for instant state changes. Every animated
//  primitive here checks it.
// ============================================================================

val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * §13: press → slight scale-down, release → spring back.
 * Indication is suppressed because the scale IS the feedback; a ripple on top
 * reads as two competing responses to one touch.
 */
@Composable
fun Modifier.pressScale(
    scaleDown: Float = 0.96f,
    haptic: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val hf = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) scaleDown else 1f,
        animationSpec = Motion.bouncy(),
        label = "pressScale"
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        // §35: restores the 48dp minimum this lost when it replaced
        // IconButton. Expands the touch area only — never shrinks a large one.
        .minimumInteractiveComponentSize()
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled
        ) {
            if (haptic) hf.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
}

/**
 * §13: the icon morphs between play and pause rather than hard-swapping.
 * Compose has no vector path morph, so this crossfades with a slight rotation
 * and scale — reads as a transformation, not a replacement.
 */
@Composable
fun PlayPauseIcon(
    playing: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val reduced = LocalReducedMotion.current
    val progress by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = if (reduced) tween(0) else tween(Motion.Fast, easing = Motion.Emphasized),
        label = "playPause"
    )
    androidx.compose.foundation.layout.Box(modifier) {
        Icon(
            Icons.Filled.PlayArrow, contentDescription, tint = tint,
            modifier = Modifier.matchParentSize().graphicsLayer {
                alpha = 1f - progress
                val s = 0.85f + 0.15f * (1f - progress)
                scaleX = s; scaleY = s
                rotationZ = -30f * progress
            }
        )
        Icon(
            Icons.Filled.Pause, null, tint = tint,
            modifier = Modifier.matchParentSize().graphicsLayer {
                alpha = progress
                val s = 0.85f + 0.15f * progress
                scaleX = s; scaleY = s
                rotationZ = 30f * (1f - progress)
            }
        )
    }
}

/**
 * §13: favourite taps pop the icon and shift colour. Restrained — one short
 * overshoot, no particles.
 */
@Composable
fun FavoriteButton(
    favorite: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    val reduced = LocalReducedMotion.current
    var pulse by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pulse && !reduced) 1.25f else 1f,
        animationSpec = Motion.bouncy(),
        label = "favScale"
    )
    val tint by animateColorAsState(
        targetValue = if (favorite) Color(0xFFEC4899) else MediaColors.CreamFaint,
        animationSpec = tween(if (reduced) 0 else Motion.Fast),
        label = "favTint"
    )
    LaunchedEffect(pulse) {
        if (pulse) { kotlinx.coroutines.delay(Motion.Fast.toLong()); pulse = false }
    }
    Icon(
        imageVector = if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        contentDescription = if (favorite) "Remove from favourites" else "Add to favourites",
        tint = tint,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pressScale(scaleDown = 0.88f, haptic = true) { pulse = true; onToggle() }
    )
}

/**
 * §10: the playing row breathes. Bars animate while audio plays and settle to
 * a static resting shape when paused — not an "ugly static colored dot".
 */
@Composable
fun PlayingEqualizer(
    playing: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val reduced = LocalReducedMotion.current
    val animate = playing && !reduced
    val transition = rememberInfiniteTransition(label = "eq")

    // Staggered durations give an organic, non-marching rhythm.
    val durations = intArrayOf(520, 380, 460, 320)
    val resting = floatArrayOf(0.45f, 0.75f, 0.35f, 0.60f)
    val levels = List(4) { i ->
        if (animate) {
            transition.animateFloat(
                initialValue = 0.22f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durations[i], easing = Motion.Smooth),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "eqBar$i"
            ).value
        } else {
            resting[i]
        }
    }

    Canvas(
        modifier.semantics {
            contentDescription = if (playing) "Now playing" else "Paused"
        }
    ) {
        val gap = size.width / 7f
        val w = gap
        for (i in 0 until 4) {
            val h = size.height * levels[i]
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (gap + w * 0.55f), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w * 0.5f),
                alpha = if (playing) 1f else 0.45f
            )
        }
    }
}
