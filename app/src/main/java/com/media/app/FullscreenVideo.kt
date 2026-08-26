package com.media.app

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

// ============================================================================
//  FULLSCREEN VIDEO
//
//  Landscape, edge to edge, system bars hidden, controls that fade themselves
//  out and come back on tap. This is the mode people stay in - everything else
//  about video is a way of getting here.
//
//  Orientation and the system bars are BOTH restored in onDispose. Leaving the
//  activity locked to landscape, or the status bar hidden, after exiting is the
//  classic way this feature breaks the rest of an app.
// ============================================================================

private const val HIDE_AFTER_MS = 3200L

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun FullscreenVideo(
    state: PlayerState,
    vm: PlayerViewModel,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current

    var controlsVisible by remember { mutableStateOf(true) }
    var fillScreen by remember { mutableStateOf(false) }
    // Bumped on every interaction; the auto-hide timer keys off it so any tap
    // restarts the countdown rather than queueing another one.
    var interactionTick by remember { mutableStateOf(0) }

    // Landscape + immersive on entry, both undone on exit.
    DisposableEffect(Unit) {
        val originalOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation =
                originalOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Controls fade out while playing; a paused video keeps them up, because a
    // paused player with no visible controls looks broken.
    LaunchedEffect(controlsVisible, interactionTick, state.isPlaying) {
        if (controlsVisible && state.isPlaying) {
            delay(HIDE_AFTER_MS)
            controlsVisible = false
        }
    }

    BackHandler { onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        interactionTick++
                    },
                    onDoubleTap = { offset ->
                        // Left half rewinds, right half skips. The standard
                        // gesture, and the one people try first.
                        val delta = if (offset.x < size.width / 2f) -10_000L else 10_000L
                        vm.seekTo((state.positionMs + delta).coerceIn(0L, state.durationMs))
                        interactionTick++
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = {
                it.player = vm.boundPlayer()
                // FIT letterboxes and shows the whole frame; ZOOM fills the
                // screen and crops. Both are wanted, so it is a toggle.
                it.resizeMode = if (fillScreen) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                // Scrims top and bottom only - a full overlay would dim the
                // picture, which is the thing people came to look at.
                Box(
                    Modifier.fillMaxWidth().height(96.dp).align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)
                            )
                        )
                )
                Box(
                    Modifier.fillMaxWidth().height(140.dp).align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                            )
                        )
                )

                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .systemBarsPadding().padding(Space.lg, Space.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "Exit fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp).pressScale(haptic = true, onClick = onExit)
                    )
                    Spacer(Modifier.width(Space.lg))
                    Text(
                        state.currentTitle, style = Typo.Primary, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (Pip.isSupported(context)) {
                        Icon(
                            Icons.Filled.PictureInPictureAlt, "Picture in picture",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp).pressScale(haptic = true) {
                                activity?.let { Pip.enter(it, state.videoWidth, state.videoHeight) }
                            }
                        )
                        Spacer(Modifier.width(Space.lg))
                    }
                    Icon(
                        Icons.Filled.AspectRatio,
                        if (fillScreen) "Fit to screen" else "Fill screen",
                        tint = if (fillScreen) MediaColors.Accent else Color.White,
                        modifier = Modifier.size(24.dp).pressScale(haptic = true) {
                            fillScreen = !fillScreen
                            interactionTick++
                        }
                    )
                }

                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(Space.xxl),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Replay10, "Back 10 seconds", tint = Color.White,
                        modifier = Modifier.size(34.dp).pressScale(haptic = true) {
                            vm.seekTo((state.positionMs - 10_000L).coerceAtLeast(0L))
                            interactionTick++
                        }
                    )
                    Icon(
                        Icons.Filled.SkipPrevious, "Previous", tint = Color.White,
                        modifier = Modifier.size(30.dp).pressScale(haptic = true) {
                            vm.previous(); interactionTick++
                        }
                    )
                    Box(
                        Modifier.size(66.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .pressScale(scaleDown = 0.92f, haptic = true) {
                                vm.togglePlayPause(); interactionTick++
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        PlayPauseIcon(state.isPlaying, Color.White, Modifier.size(36.dp), "Play/Pause")
                    }
                    Icon(
                        Icons.Filled.SkipNext, "Next", tint = Color.White,
                        modifier = Modifier.size(30.dp).pressScale(haptic = true) {
                            vm.next(); interactionTick++
                        }
                    )
                    Icon(
                        Icons.Filled.Forward10, "Forward 10 seconds", tint = Color.White,
                        modifier = Modifier.size(34.dp).pressScale(haptic = true) {
                            vm.seekTo((state.positionMs + 10_000L).coerceAtMost(state.durationMs))
                            interactionTick++
                        }
                    )
                }

                if (state.durationMs > 0) {
                    Column(
                        Modifier.align(Alignment.BottomCenter)
                            .systemBarsPadding().padding(Space.xl, 0.dp, Space.xl, Space.lg)
                    ) {
                        Slider(
                            value = state.positionMs.toFloat()
                                .coerceIn(0f, state.durationMs.toFloat()),
                            onValueChange = { vm.seekTo(it.toLong()); interactionTick++ },
                            valueRange = 0f..state.durationMs.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MediaColors.Accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                            )
                        )
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text(fmtClock(state.positionMs), style = Typo.Tertiary,
                                color = Color.White.copy(alpha = 0.85f))
                            Text(fmtClock(state.durationMs), style = Typo.Tertiary,
                                color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                }
            }
        }
    }
}
