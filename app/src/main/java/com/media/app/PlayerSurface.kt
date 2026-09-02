package com.media.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import kotlinx.coroutines.launch
import kotlin.math.abs

// ============================================================================
//  PLAYER SURFACE (§11, §12, §15, §46)
//
//  ONE surface, not two screens. Everything is driven by `expansion`:
//    0.0 = mini pill docked above the nav bar
//    1.0 = full-screen Now Playing
//
//  The artwork is a single element whose size, position and corner radius
//  interpolate across that range, so it PHYSICALLY TRANSFORMS between the two
//  states rather than one view being swapped for another (§11, §15).
//
//  Vertical drag drives `expansion` directly, so the surface tracks the finger
//  continuously instead of playing a canned animation on release.
// ============================================================================

private const val MINI_HEIGHT = 60
private const val PILL_MARGIN = 12
private const val MINI_ART = 44

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerSurface(
    state: PlayerState,
    vm: PlayerViewModel,
    expanded: Boolean,
    onFullscreen: () -> Unit,
    artItem: AppMediaItem?,
    artForQueue: (QueueEntry) -> AppMediaItem?,
    beat: BeatState,
    bottomInset: androidx.compose.ui.unit.Dp,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    // Bounded: Motion.spatial() is underdamped (0.82) and WILL overshoot past
    // 1.0, which drove Modifier.padding negative and crashed on expand.
    val expansion = remember {
        Animatable(if (expanded) 1f else 0f).apply { updateBounds(0f, 1f) }
    }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val queue by vm.queue.collectAsState()

    // External toggles (tap, back press) animate; drag drives it directly.
    LaunchedEffect(expanded) {
        val target = if (expanded) 1f else 0f
        if (expansion.value != target) {
            if (reduced) expansion.snapTo(target)
            else expansion.animateTo(target, Motion.spatial())
        }
    }

    // Belt and braces: nothing downstream may ever see a value outside 0..1.
    val e = expansion.value.coerceIn(0f, 1f)
    // §12: the environment takes its tone from the current cover.
    val ambient = rememberAmbientColor(artItem)
    val level = beat.level

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.toPx() }
        val swipePx = with(density) { 60.dp.toPx() }

        // ---- container: pill -> full screen, expressed as lerped insets ----
        // Guard tiny screens / large insets: this must never go negative either.
        val collapsedTop = (maxHeight - bottomInset - MINI_HEIGHT.dp).coerceAtLeast(0.dp)
        val topPad = lerpDp(collapsedTop, 0.dp, e)
        val sidePad = lerpDp(PILL_MARGIN.dp, 0.dp, e)
        val botPad = lerpDp(bottomInset, 0.dp, e)
        val corner = lerpDp(28.dp, 0.dp, e)
        val bg = lerpColor(MediaColors.Floating, MediaColors.Ink, e)
        val ink = MediaColors.Ink

        // ---- shared artwork geometry ----
        // Width AND height, not one square value. Video letterboxed inside a
        // square box wasted roughly half the area a 16:9 clip could fill; the
        // box now takes the video's real aspect as it expands, so RESIZE_MODE_FIT
        // has nothing left to letterbox.
        val videoAspect =
            if (state.isVideo && state.videoWidth > 0 && state.videoHeight > 0)
                state.videoWidth.toFloat() / state.videoHeight
            else 1f
        // Aspect itself is interpolated, so the pill stays square and only
        // becomes cinematic as it opens - the morph is unaffected.
        val aspectNow = 1f + (videoAspect - 1f) * e
        val expandedW = if (state.isVideo) maxWidth else maxWidth * 0.76f

        var artW = lerpDp(MINI_ART.dp, expandedW, e)
        var artH = artW / aspectNow
        // Portrait video would otherwise run past the controls and off-screen.
        val artHCap = maxHeight * 0.62f
        if (artH > artHCap) {
            artH = artHCap
            artW = artH * aspectNow
        }

        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // Video centres in the space above the controls; audio keeps its
        // hero position under the top bar.
        val expandedY =
            if (state.isVideo) ((maxHeight - artH) / 2f - 40.dp).coerceAtLeast(statusTop + 56.dp)
            else statusTop + 56.dp

        val artX = lerpDp(8.dp, (maxWidth - artW) / 2f, e)
        val artY = lerpDp(8.dp, expandedY, e)
        // Video loses its rounding as it fills the frame.
        val artCorner = lerpDp(22.dp, if (state.isVideo) 0.dp else 18.dp, e)

        val miniAlpha = (1f - e * 2.2f).coerceIn(0f, 1f)
        val fullAlpha = ((e - 0.45f) / 0.55f).coerceIn(0f, 1f)

        var dragX by remember { mutableStateOf(0f) }

        Box(
            Modifier
                // fillMaxSize FIRST: without it the Box shrink-wraps its content,
                // so the expanded state collapsed to the height of whatever was
                // inside it instead of filling the screen. Padding after fill
                // insets the painted/interactive area down to the pill at e=0.
                .fillMaxSize()
                .padding(start = sidePad, end = sidePad, top = topPad, bottom = botPad)
                .clip(RoundedCornerShape(corner))
                .background(bg)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, delta ->
                            change.consume()
                            // Vertical drives expansion; horizontal is only a
                            // skip gesture while collapsed (§36).
                            if (abs(delta.y) >= abs(delta.x) || expansion.value > 0f) {
                                scope.launch {
                                    expansion.snapTo(
                                        (expansion.value - delta.y / heightPx * 1.6f)
                                            .coerceIn(0f, 1f)
                                    )
                                }
                            } else {
                                dragX += delta.x
                            }
                        },
                        onDragEnd = {
                            when {
                                expansion.value in 0.001f..0.999f -> {
                                    val target = if (expansion.value > 0.4f) 1f else 0f
                                    scope.launch { expansion.animateTo(target, Motion.spatial()) }
                                    onExpandedChange(target == 1f)
                                }
                                dragX <= -swipePx -> vm.next()
                                dragX >= swipePx -> vm.previous()
                            }
                            dragX = 0f
                        }
                    )
                }
                .then(
                    if (e < 0.02f) Modifier.pressScale(scaleDown = 0.985f) {
                        onExpandedChange(true)
                    } else Modifier
                )
        ) {
            // §12/§4: ambient wash, strongest behind the artwork and gone by
            // mid-screen. Fades in with expansion so the collapsed pill keeps
            // its flat surface. Sits UNDER everything else.
            if (e > 0.01f) {
                Box(
                    Modifier.matchParentSize().clearAndSetSemantics { }.background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ambient.copy(alpha = 0.95f * e),
                                ambient.copy(alpha = 0.45f * e),
                                ink.copy(alpha = 0f)
                            ),
                            // heightPx, not maxHeight: BoxWithConstraintsScope
                            // isn't reachable as an implicit receiver from
                            // inside the inner Box's BoxScope.
                            endY = heightPx * 0.72f
                        )
                    )
                )
            }

            // Bloom behind the artwork — grows and brightens on the beat.
            // Drawn before the art so it reads as light spilling out from
            // behind it rather than a ring stuck on top.
            // Shockwave rings ride outside the bloom and are always mounted
            // while expanded — they animate off their own birth timestamps.
            if (e > 0.5f) {
                // Full-size layer, centre computed from the artwork rather than
                // from the Canvas. The old version sized its box to ~1.5x the
                // screen and offset it negative, so Compose clamped it and the
                // rings visibly originated from the right instead of the cover.
                val cx = with(density) { (artX + artW / 2f).toPx() }
                val cy = with(density) { (artY + artH / 2f).toPx() }
                val r0 = with(density) { (minOf(artW, artH) / 2f).toPx() }
                BeatRings(
                    beat = beat,
                    color = ambient,
                    centerPx = Offset(cx, cy),
                    baseRadiusPx = r0,
                    strength = 1f,
                    modifier = Modifier.matchParentSize().clearAndSetSemantics { }
                )
            }
            if (level > 0.01f) {
                // Bloom pushed well past the old 0.16-0.38: it now clearly
                // swells past the artwork edge on a hit instead of hinting.
                val grow = minOf(artW, artH) * (0.24f + 0.46f * level)
                Box(
                    Modifier
                        .clearAndSetSemantics { }
                        .offset(x = artX - grow / 2f, y = artY - grow / 2f)
                        .size(width = artW + grow, height = artH + grow)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    ambient.copy(alpha = 0.85f * level),
                                    ambient.copy(alpha = 0.34f * level),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            // ---------------- shared artwork ----------------
            Box(
                Modifier.offset(x = artX, y = artY).size(width = artW, height = artH)
                    // 10% at full level. With the punch curve the value sits
                    // near zero between hits, so this reads as a strike rather
                    // than a constant wobble.
                    .graphicsLayer {
                        val s = 1f + level * 0.10f
                        scaleX = s; scaleY = s
                    }
                    // Depth grows as it expands: a pill needs almost none, the
                    // hero cover is the strongest thing on the screen.
                    .shadow(
                        elevation = lerpDp(Elevation.low, Elevation.high, e),
                        shape = RoundedCornerShape(artCorner),
                        clip = false,
                        ambientColor = Color.Black,
                        spotColor = Color.Black
                    )
                    .clip(RoundedCornerShape(artCorner))
            ) {
                // §12: track changes cross-dissolve with a scale drift. Artwork
                // is never abruptly swapped.
                AnimatedContent(
                    targetState = artItem,
                    transitionSpec = {
                        if (reduced) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else {
                            (fadeIn(tween(Motion.Standard)) +
                                scaleIn(initialScale = 1.08f, animationSpec = tween(Motion.Standard))) togetherWith
                                (fadeOut(tween(Motion.Standard)) +
                                    scaleOut(targetScale = 0.94f, animationSpec = tween(Motion.Standard)))
                        }
                    },
                    label = "artwork"
                ) { item ->
                    when {
                        // Video renders INSIDE the morphing box, so it scales
                        // and travels with everything else. factory runs once,
                        // so the surface is never recreated mid-animation.
                        state.isVideo -> AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            update = { it.player = vm.boundPlayer() },
                            modifier = Modifier.fillMaxSize()
                        )
                        item != null -> CoverArt(
                            item, Modifier.fillMaxSize(), corner = 0,
                            targetPx = if (e > 0.5f) 768 else 144
                        )
                        else -> Box(Modifier.fillMaxSize().background(MediaColors.Ink))
                    }
                }
            }

            // ---------------- collapsed chrome ----------------
            if (miniAlpha > 0.01f) {
                Row(
                    Modifier.fillMaxWidth().height(MINI_HEIGHT.dp)
                        .padding(start = (MINI_ART + 16).dp, end = Space.sm)
                        .alpha(miniAlpha),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(state.currentTitle, style = Typo.Primary, color = MediaColors.Cream,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.currentArtist, style = Typo.Secondary, color = MediaColors.CreamDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PlayPauseIcon(
                        playing = state.isPlaying, tint = MediaColors.Cream,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(26.dp).pressScale(haptic = true) { vm.togglePlayPause() }
                    )
                }
                // §11: a thin progress indicator along the bottom edge.
                val prog = if (state.durationMs > 0)
                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                // Read the accent HERE: MediaColors.* are @Composable getters
                // and the DrawScope lambda is not a composable context.
                val accent = MediaColors.Accent
                Canvas(
                    Modifier.fillMaxWidth().height(2.dp)
                        .align(Alignment.BottomCenter).alpha(miniAlpha)
                ) {
                    drawLine(
                        color = accent,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width * prog, size.height / 2),
                        strokeWidth = size.height, cap = StrokeCap.Round
                    )
                }
            }

            // ---------------- expanded chrome ----------------
            if (fullAlpha > 0.01f) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding()
                        .padding(Space.sm, Space.sm).alpha(fullAlpha),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown, "Collapse", tint = MediaColors.Cream,
                        modifier = Modifier.size(28.dp).pressScale { onExpandedChange(false) }
                    )
                    Spacer(Modifier.weight(1f))
                    Text("Now playing", style = Typo.Tertiary, color = MediaColors.CreamDim)
                    Spacer(Modifier.weight(1f))
                    // PiP only exists for video, and only where the device
                    // supports it - no dead button on an audio track.
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    if (state.isVideo) {
                        Icon(
                            Icons.Filled.Fullscreen, "Fullscreen", tint = MediaColors.Cream,
                            modifier = Modifier.size(26.dp)
                                .pressScale(haptic = true, onClick = onFullscreen)
                        )
                        Spacer(Modifier.width(Space.md))
                    }
                    if (state.isVideo && Pip.isSupported(ctx)) {
                        Icon(
                            Icons.Filled.PictureInPictureAlt, "Picture in picture",
                            tint = MediaColors.Cream,
                            modifier = Modifier.size(26.dp).pressScale(haptic = true) {
                                (ctx as? android.app.Activity)?.let {
                                    Pip.enter(it, state.videoWidth, state.videoHeight)
                                }
                            }
                        )
                        Spacer(Modifier.width(Space.md))
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = MediaColors.Cream,
                        modifier = Modifier.size(28.dp).pressScale(haptic = true) { showQueue = true }
                    )
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .navigationBarsPadding().alpha(fullAlpha)
                ) {
                    Column(Modifier.fillMaxWidth().padding(Space.xl, 0.dp, Space.xl, Space.md)) {
                        Text(state.currentTitle, style = Typo.Section, color = MediaColors.Cream,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(state.currentArtist, style = Typo.Body, color = MediaColors.CreamDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    if (state.durationMs > 0) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl)) {
                            Slider(
                                value = state.positionMs.toFloat().coerceIn(0f, state.durationMs.toFloat()),
                                onValueChange = { vm.seekTo(it.toLong()) },
                                valueRange = 0f..state.durationMs.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = MediaColors.Cream,
                                    activeTrackColor = MediaColors.Accent,
                                    inactiveTrackColor = MediaColors.InkHairline
                                )
                            )
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text(fmtClock(state.positionMs), style = Typo.Tertiary,
                                    color = MediaColors.CreamFaint)
                                Text(fmtClock(state.durationMs), style = Typo.Tertiary,
                                    color = MediaColors.CreamFaint)
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(Space.xl, Space.md),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.SkipPrevious, "Previous", tint = MediaColors.Cream,
                            modifier = Modifier.size(34.dp).pressScale(haptic = true) { vm.previous() })
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(MediaColors.Cream)
                                .pressScale(scaleDown = 0.92f, haptic = true) { vm.togglePlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            PlayPauseIcon(state.isPlaying, MediaColors.OnInverse,
                                Modifier.size(34.dp), "Play/Pause")
                        }
                        Icon(Icons.Filled.SkipNext, "Next", tint = MediaColors.Cream,
                            modifier = Modifier.size(34.dp).pressScale(haptic = true) { vm.next() })
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(Space.xl, 0.dp, Space.xl, Space.xl),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Shuffle, "Shuffle",
                            tint = if (state.shuffle) MediaColors.Accent else MediaColors.CreamDim,
                            modifier = Modifier.size(IconSize.lg).pressScale(haptic = true) { vm.toggleShuffle() })
                        Icon(
                            if (state.repeatMode == 1) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            "Repeat",
                            tint = if (state.repeatMode != 0) MediaColors.Accent else MediaColors.CreamDim,
                            modifier = Modifier.size(IconSize.lg).pressScale(haptic = true) { vm.cycleRepeat() }
                        )
                        Text(
                            "${state.speed}x".replace(".0x", "x"), style = Typo.Primary,
                            color = if (state.speed != 1.0f) MediaColors.Accent else MediaColors.CreamDim,
                            modifier = Modifier.pressScale(haptic = true) { vm.cycleSpeed() }
                        )
                        if (state.sleepActive && !state.sleepEndOfTrack) {
                            Text(fmtClock(state.sleepRemainingMs), style = Typo.Primary,
                                color = MediaColors.Accent,
                                modifier = Modifier.pressScale { showSleepSheet = true })
                        } else {
                            Icon(Icons.Filled.Bedtime, "Sleep timer",
                                tint = if (state.sleepActive) MediaColors.Accent else MediaColors.CreamDim,
                                modifier = Modifier.size(IconSize.lg).pressScale { showSleepSheet = true })
                        }
                    }
                }
            }
        }
    }

    if (showQueue) {
        QueueSheet(
            queue = queue,
            artFor = artForQueue,
            currentIndex = state.queueIndex,
            onPlayIndex = { vm.playQueueIndex(it) },
            onMove = { from, to -> vm.moveQueueItem(from, to) },
            onRemove = { vm.removeQueueItem(it) },
            onClearUpNext = { vm.clearUpNext() },
            onDismiss = { showQueue = false }
        )
    }

    if (showSleepSheet) {
        SleepTimerSheet(
            state = state,
            onPick = { mins -> vm.startSleepTimer(mins); showSleepSheet = false },
            onEndOfTrack = { vm.startSleepEndOfTrack(); showSleepSheet = false },
            onCancelTimer = { vm.cancelSleepTimer(); showSleepSheet = false },
            onDismiss = { showSleepSheet = false }
        )
    }
}

/** Lightweight item so CoverArt can drive off the session's current URI. */
@Composable
fun rememberArtItem(state: PlayerState): AppMediaItem? =
    remember(state.currentUri, state.currentTitle) {
        state.currentUri?.let { uri ->
            AppMediaItem(
                id = uri.substringAfterLast('/').toLongOrNull() ?: 0L,
                title = state.currentTitle, artist = state.currentArtist,
                durationMs = state.durationMs, uri = android.net.Uri.parse(uri),
                type = if (state.isVideo) MediaType.VIDEO else MediaType.AUDIO,
                pillar = Pillar.MUSIC
            )
        }
    }

internal fun fmtClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Bare video surface for picture-in-picture. No chrome at all - in PiP the
 * window is a few hundred pixels wide and anything else is noise.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PipVideoOnly(vm: PlayerViewModel) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { it.player = vm.boundPlayer() },
            modifier = Modifier.fillMaxSize()
        )
    }
}
