package com.media.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Equalizer
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.res.stringResource

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
// (MINI_HEIGHT - MINI_ART) / 2: the art is inset this far on every side, so
// the trailing control mirrors it.
private const val MINI_INSET = 8
// How long the light flash (Premium and Ultra) takes to cross the cover.
private const val FLASH_NS = 420_000_000L

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerSurface(
    state: PlayerState,
    vm: PlayerViewModel,
    expanded: Boolean,
    onFullscreen: () -> Unit,
    artItem: AppMediaItem?,
    artForQueue: (QueueEntry) -> AppMediaItem?,
    // Not redundant with the row hearts: Now Playing is where you are actually
    // listening when you decide you love a track. Making someone navigate back
    // to a list to favourite it is the worse design.
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    beat: BeatState,
    // This track's real 20Hz bass envelope, or null for video / spoken word
    // / analysis still running. The scrubber draws a flat track rather than
    // a fabricated wave when it is absent.
    envelope: FloatArray? = null,
    bottomInset: androidx.compose.ui.unit.Dp,
    // False while a card in the video feed holds the one video surface. This
    // composable is mounted whenever anything is playing, so without it the
    // collapsed pill would attach a second PlayerView to the same player and
    // win the surface outright.
    videoSurface: Boolean = true,
    onExpandedChange: (Boolean) -> Unit,
    // Swipe the pill down: playback stops and the pill leaves.
    onDismiss: () -> Unit = {},
    // The full library row for what is playing: lyrics lookups need the
    // album and folder, which the session's own metadata does not carry.
    libraryItem: AppMediaItem? = null,
    onlineLookups: Boolean = false,
    onEnableOnline: () -> Unit = {},
    onOpenAudioPath: () -> Unit = {},
    onOpenSound: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val reduced = LocalReducedMotion.current
    val q = LocalQuality.current
    val scope = rememberCoroutineScope()
    // Bounded: Motion.spatial() is underdamped (0.82) and WILL overshoot past
    // 1.0, which drove Modifier.padding negative and crashed on expand.
    val expansion = remember {
        Animatable(if (expanded) 1f else 0f).apply { updateBounds(0f, 1f) }
    }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    // Lyrics take the artwork's place rather than opening a new screen: the
    // words belong with the music, and the transport stays under the thumb.
    var showLyrics by remember { mutableStateOf(false) }
    LaunchedEffect(state.isVideo) { if (state.isVideo) showLyrics = false }
    val lyr by animateFloatAsState(
        if (showLyrics && !state.isVideo) 1f else 0f,
        tween(if (reduced) 0 else Motion.Standard), label = "lyrics"
    )
    val ctxForSource = androidx.compose.ui.platform.LocalContext.current
    val sourceItem = libraryItem ?: artItem
    var source by remember(sourceItem?.uri, state.isVideo) { mutableStateOf<SourceFormat?>(null) }
    LaunchedEffect(sourceItem?.uri, state.isVideo) {
        source = if (sourceItem != null && !state.isVideo) AudioInfo.source(ctxForSource, sourceItem).first else null
    }
    val controlsPx = remember { mutableStateOf(0) }
    // Lyrics for the under-artwork line. Same engine and cache as the full
    // view, so opening it afterwards costs nothing.
    val lyricsRevision by LyricsEngine.revision.collectAsState()
    val previewItem = libraryItem ?: artItem
    var previewLyrics by remember(previewItem?.id) { mutableStateOf<LyricsState?>(null) }
    LaunchedEffect(previewItem?.id, onlineLookups, lyricsRevision, state.isVideo) {
        val it = previewItem
        previewLyrics = if (it == null || state.isVideo) null
            else LyricsEngine.load(ctxForSource, it, online = onlineLookups)
    }
    val previewOffset = remember(previewItem?.id, lyricsRevision) {
        previewItem?.let { LyricsStore.offset(it.id) } ?: 0L
    }
    var showQueue by remember { mutableStateOf(false) }
    val queue by vm.queue.collectAsState()

    // Premium and Ultra: the phone's motion tilts the cover, and the panel
    // runs at its top refresh rate - both only while Now Playing is open.
    val tilt = rememberTilt(enabled = q.parallax && expanded && !state.isVideo && !reduced)
    TopRefreshRate(enabled = q.topRefresh && expanded)

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
    // Passing null skips the decode and the Palette pass entirely, so the
    // tiers that draw no wash and no bloom do not pay to extract a colour
    // they will never use.
    val ambient = rememberAmbientColor(
        if (q.ambientGradient || q.dynamicArtLighting) artItem else null
    )
    // The cover's own colours (Backdrop.kt): the waveform, the glow behind
    // the art, its shadow, the rings and the lyric glow follow the song.
    // Animated, so a skip recolours the room instead of cutting it.
    val artColors = rememberArtColors(if (state.isVideo) null else (libraryItem ?: artItem))
    val appAccent = MediaColors.Accent
    val npAccent by animateColorAsState(artColors?.accent ?: appAccent, tween(Motion.Large), label = "npAccent")
    val glow by animateColorAsState(artColors?.glow ?: appAccent, tween(Motion.Large), label = "npGlow")
    // NOT read here. beat.level updates 60x/second, so reading it in
    // composition scope recomposed this entire surface every frame - which
    // starved the drag gesture and made the player feel stuck. Every consumer
    // below reads it inside a draw or layer lambda instead, so the pulse costs
    // a redraw, never a recomposition.

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
        // Near-black glass. Elevated (#15151A) still read as a grey slab on
        // the true-black floor; the pill is now a hair off black with a
        // whisper of the cover's colour, and its edge comes from a hairline
        // and a faint top light rather than from being grey.
        val darkTheme = MediaColors.Ink.luminance() < 0.5f
        val pillSurface = if (darkTheme) lerpColor(Color(0xFF09090B), ambient, 0.35f) else MediaColors.Elevated
        val bg = lerpColor(pillSurface, MediaColors.Ink, e)
        val glass = (1f - e * 3f).coerceIn(0f, 1f)

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

        // ---- pill gestures ----
        //
        // The old handler decided direction PER EVENT and let any expansion
        // above zero claim the rest of the drag. A sideways swipe that began
        // with the faintest upward drift lifted expansion to 0.002, every
        // later event went to the vertical branch, and the skip never fired.
        // A swipe down did nothing at all, and a drag that closed Now Playing
        // all the way never told the scaffold, so showPlayer stayed true
        // behind a collapsed pill.
        //
        // Now the axis is locked ONCE from the drag's own direction, the pill
        // follows the finger so it shows what letting go will do, and every
        // vertical drag settles AND reports where it landed.
        val widthPx = with(density) { maxWidth.toPx() }
        val pillPx = with(density) { MINI_HEIGHT.dp.toPx() }
        val lockPx = with(density) { 6.dp.toPx() }
        val flingPx = with(density) { 900.dp.toPx() }      // per second
        val dismissPx = with(density) { 44.dp.toPx() }
        val pillX = remember { Animatable(0f) }
        val pillY = remember { Animatable(0f) }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        // State, not values: the gesture coroutine outlives recompositions
        // and must call whatever the newest callbacks are.
        val latestExpanded = rememberUpdatedState(onExpandedChange)
        val latestDismiss = rememberUpdatedState(onDismiss)

        Box(
            Modifier
                // fillMaxSize FIRST: without it the Box shrink-wraps its content,
                // so the expanded state collapsed to the height of whatever was
                // inside it instead of filling the screen. Padding after fill
                // insets the painted/interactive area down to the pill at e=0.
                .fillMaxSize()
                .padding(start = sidePad, end = sidePad, top = topPad, bottom = botPad)
                // BEFORE the layer that moves the pill: a gesture read inside a
                // layer it is translating sees its own movement subtracted from
                // every delta, and the pill stutters behind the finger.
                .pillGestures(
                    heightPx = heightPx, widthPx = widthPx, swipePx = swipePx,
                    pillPx = pillPx, lockPx = lockPx, flingPx = flingPx, dismissPx = dismissPx,
                    expansion = expansion, pillX = pillX, pillY = pillY,
                    scope = scope, haptics = haptics, vm = vm,
                    latestExpanded = latestExpanded, latestDismiss = latestDismiss
                )
                .graphicsLayer {
                    // Only the docked pill moves with the finger; Now Playing
                    // never slides sideways.
                    translationX = pillX.value
                    translationY = pillY.value
                    val fadeX = abs(pillX.value) / (widthPx * 0.5f)
                    val fadeY = pillY.value / (pillPx * 2f)
                    alpha = (1f - maxOf(fadeX, fadeY)).coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(corner))
                .background(bg)
                .then(
                    if (darkTheme && glass > 0f) Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.05f * glass), Color.Transparent),
                                endY = pillPx * 0.9f
                            )
                        )
                        .border(0.5.dp, Color.White.copy(alpha = 0.10f * glass), RoundedCornerShape(corner))
                    else Modifier
                )
                .then(
                    if (e < 0.02f) Modifier.pressScale(scaleDown = 0.985f) {
                        onExpandedChange(true)
                    } else Modifier
                )
        ) {
            PlayerLight(
                e = e, q = q, isVideo = state.isVideo, expanded = expanded,
                backdropItem = libraryItem ?: artItem, artColors = artColors,
                beat = beat, tilt = tilt, ambient = ambient, glow = glow, heightPx = heightPx,
                artX = artX, artY = artY, artW = artW, artH = artH
            )

            PlayerArtwork(
                e = e, q = q, isVideo = state.isVideo, lyr = lyr,
                beat = beat, tilt = tilt, glow = glow,
                artX = artX, artY = artY, artW = artW, artH = artH, artCorner = artCorner,
                artItem = artItem, reduced = reduced, videoSurface = videoSurface, vm = vm
            )

            // ---------------- collapsed chrome ----------------
            if (miniAlpha > 0.01f) {
                MiniChrome(miniAlpha = miniAlpha, state = state, darkTheme = darkTheme, vm = vm)
            }

            // ---------------- expanded chrome ----------------
            if (fullAlpha > 0.01f) {
                NowPlayingTopBar(
                    state = state, fullAlpha = fullAlpha, showLyrics = showLyrics,
                    onCollapse = { onExpandedChange(false) },
                    onFullscreen = onFullscreen,
                    onToggleLyrics = { showLyrics = !showLyrics },
                    onOpenSound = onOpenSound,
                    onOpenQueue = { showQueue = true }
                )

                // Text over a coloured backdrop gets a soft shadow so it holds
                // on a bright cover as well as a dark one.
                val titleShadow = if (q.livingBackdrop && !state.isVideo)
                    Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 14f) else null

                // Lyrics, in the space between the top bar and the controls.
                val lyricsItem = libraryItem ?: artItem
                if (lyr > 0.01f && lyricsItem != null && !state.isVideo) {
                    val statusTopL = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    LyricsPanel(
                        item = lyricsItem,
                        positionMs = state.positionMs,
                        online = onlineLookups,
                        onSeek = { vm.seekTo(it) },
                        onEnableOnline = onEnableOnline,
                        tint = npAccent,
                        modifier = Modifier.fillMaxSize()
                            .padding(top = statusTopL + 56.dp, bottom = with(density) { controlsPx.value.toDp() })
                            .alpha(fullAlpha * lyr)
                    )
                }

                NowPlayingControls(
                    fullAlpha = fullAlpha, state = state, vm = vm, controlsPx = controlsPx,
                    previewLyrics = previewLyrics, previewOffset = previewOffset,
                    showLyrics = showLyrics, onOpenLyrics = { showLyrics = true },
                    titleShadow = titleShadow, source = source,
                    isFavorite = isFavorite, onToggleFavorite = onToggleFavorite,
                    onOpenAudioPath = onOpenAudioPath,
                    envelope = envelope, beat = beat, npAccent = npAccent,
                    onOpenSleep = { showSleepSheet = true },
                    onOpenSpeed = { showSpeedSheet = true }
                )
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
    if (showSpeedSheet) {
        SpeedSheet(state = state, vm = vm, onDismiss = { showSpeedSheet = false })
    }
}

// ----------------------------------------------------------------------------
//  The pieces of the surface, each its own function.
//
//  This was one lambda of ~750 lines, which compiles to one method. In 4.5
//  HomeScaffold, grown the same way, needed more than 256 Dalvik registers
//  in the build that crashed (the verifier named v258); past that limit the
//  compiler moved an object reference with a plain `move`, ART rejected the
//  whole class, and the app could not start. Small functions keep every
//  method far below that range, in debug builds as well as release.
// ----------------------------------------------------------------------------

/**
 * The pill's drag handling. The axis is locked once per drag; sideways skips,
 * down on the docked pill dismisses it, vertical opens and closes Now Playing.
 * Keyed on the surface size only, as before: the running gesture keeps the
 * values it started with, and reads the callbacks through [latestExpanded] and
 * [latestDismiss] so it always calls the newest ones.
 */
private fun Modifier.pillGestures(
    heightPx: Float,
    widthPx: Float,
    swipePx: Float,
    pillPx: Float,
    lockPx: Float,
    flingPx: Float,
    dismissPx: Float,
    expansion: Animatable<Float, AnimationVector1D>,
    pillX: Animatable<Float, AnimationVector1D>,
    pillY: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    haptics: HapticFeedback,
    vm: PlayerViewModel,
    latestExpanded: State<(Boolean) -> Unit>,
    latestDismiss: State<() -> Unit>
): Modifier = pointerInput(heightPx, widthPx) {
    var axis = 0            // 0 undecided, 1 sideways, 2 vertical
    var startedOpen = false
    var totalX = 0f
    var totalY = 0f
    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
    detectDragGestures(
        onDragStart = {
            axis = 0; totalX = 0f; totalY = 0f
            startedOpen = expansion.value > 0.001f
            tracker.resetTracking()
        },
        onDrag = { change, delta ->
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            totalX += delta.x
            totalY += delta.y
            if (axis == 0) {
                axis = when {
                    startedOpen -> 2
                    abs(totalX) < lockPx && abs(totalY) < lockPx -> 0
                    abs(totalX) > abs(totalY) -> 1
                    else -> 2
                }
            }
            when {
                axis == 1 -> scope.launch { pillX.snapTo(totalX) }
                // Pulling the docked pill down: it follows, and
                // far enough lets it go.
                axis == 2 && !startedOpen && totalY > 0f && expansion.value <= 0f ->
                    scope.launch { pillY.snapTo(totalY) }
                axis == 2 -> scope.launch {
                    if (pillY.value != 0f) pillY.snapTo(0f)
                    expansion.snapTo(
                        (expansion.value - delta.y / heightPx * 1.6f).coerceIn(0f, 1f)
                    )
                }
            }
        },
        onDragCancel = {
            scope.launch { pillX.animateTo(0f, Motion.spatial()) }
            scope.launch { pillY.animateTo(0f, Motion.spatial()) }
            if (axis == 2) {
                val target = if (expansion.value > 0.4f) 1f else 0f
                scope.launch { expansion.animateTo(target, Motion.spatial()) }
                latestExpanded.value(target == 1f)
            }
            axis = 0
        },
        onDragEnd = {
            val v = tracker.calculateVelocity()
            when {
                axis == 1 -> {
                    val flung = abs(v.x) > flingPx && (v.x > 0f) == (totalX > 0f)
                    if (abs(totalX) > swipePx || flung) {
                        // Right is the previous SONG, left the
                        // next. Never a restart: that is what
                        // the Previous button is for.
                        val dir = if (totalX > 0f) 1f else -1f
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                        )
                        scope.launch {
                            pillX.animateTo(dir * widthPx * 0.45f, tween(Motion.Fast))
                            if (dir > 0f) vm.previousTrack() else vm.next()
                            // The new track arrives from the
                            // other side.
                            pillX.snapTo(-dir * widthPx * 0.22f)
                            pillX.animateTo(0f, Motion.spatial())
                        }
                    } else {
                        scope.launch { pillX.animateTo(0f, Motion.spatial()) }
                    }
                }
                !startedOpen && pillY.value > 0f -> {
                    if (pillY.value > dismissPx || v.y > flingPx) {
                        haptics.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                        )
                        scope.launch {
                            pillY.animateTo(pillPx * 2.5f, tween(Motion.Fast))
                            latestDismiss.value()
                            // If nothing was playing to stop,
                            // the pill must not stay parked
                            // off-screen.
                            kotlinx.coroutines.delay(700)
                            pillY.snapTo(0f)
                        }
                    } else {
                        scope.launch { pillY.animateTo(0f, Motion.spatial()) }
                    }
                }
                axis == 2 -> {
                    // Always settle AND report - including a
                    // drag that ended exactly closed.
                    val target = when {
                        v.y < -flingPx -> 1f
                        v.y > flingPx -> 0f
                        expansion.value > 0.4f -> 1f
                        else -> 0f
                    }
                    scope.launch { expansion.animateTo(target, Motion.spatial()) }
                    latestExpanded.value(target == 1f)
                }
            }
            axis = 0
        }
    )
}

/** The light under the cover: living backdrop, ambient wash, beat rings, edge light. */
@Composable
private fun BoxScope.PlayerLight(
    e: Float,
    q: QualityProfile,
    isVideo: Boolean,
    expanded: Boolean,
    backdropItem: AppMediaItem?,
    artColors: ArtColors?,
    beat: BeatState,
    tilt: Tilt,
    ambient: Color,
    glow: Color,
    heightPx: Float,
    artX: androidx.compose.ui.unit.Dp,
    artY: androidx.compose.ui.unit.Dp,
    artW: androidx.compose.ui.unit.Dp,
    artH: androidx.compose.ui.unit.Dp
) {
    val density = LocalDensity.current
    val ink = MediaColors.Ink
    // The cover's light, made once per song and shared with the room behind
    // the other screens (CoverLight.kt).
    val light = rememberCoverLight(if (isVideo) null else backdropItem)
    // The living backdrop: the cover itself as light, under everything.
    // Fades in with expansion like the wash; how much it moves is the
    // tier's call (Backdrop.kt).
    if (e > 0.01f && q.livingBackdrop && !isVideo) {
        LivingBackdrop(
            light = light,
            colors = artColors,
            beat = beat,
            reactive = q.reactiveLevel >= 2,
            drift = q.backdropDrift && expanded,
            turn = q.backdropTurn,
            fluid = q.fluidFlow,
            fieldPools = q.lightField,
            grain = q.grain,
            tilt = if (q.parallax) tilt else null,
            modifier = Modifier.matchParentSize().clearAndSetSemantics { }
                .graphicsLayer { alpha = e }
        )
    }
    // §12/§4: ambient wash, strongest behind the artwork and gone by
    // mid-screen. Fades in with expansion so the collapsed pill keeps
    // its flat surface. Sits UNDER everything else.
    if (e > 0.01f && q.ambientGradient) {
        Box(
            Modifier.matchParentSize().clearAndSetSemantics { }.background(
                Brush.verticalGradient(
                    colors = listOf(
                        // Lighter over the living backdrop: it is
                        // already coloured light, the wash only tints.
                        ambient.copy(alpha = (if (q.livingBackdrop && !isVideo) 0.45f else 0.85f) * e),
                        ambient.copy(alpha = (if (q.livingBackdrop && !isVideo) 0.12f else 0.30f) * e),
                        ink.copy(alpha = 0f)
                    ),
                    // heightPx, not maxHeight: BoxWithConstraintsScope
                    // isn't reachable as an implicit receiver from
                    // inside the inner Box's BoxScope.
                    endY = heightPx * 0.62f
                )
            )
        )
    }

    // Bloom behind the artwork — grows and brightens on the beat.
    // Drawn before the art so it reads as light spilling out from
    // behind it rather than a ring stuck on top.
    // Shockwave rings ride outside the bloom and are always mounted
    // while expanded — they animate off their own birth timestamps.
    if (e > 0.5f && q.reactiveLevel >= 2) {
        // Full-size layer, centre computed from the artwork rather than
        // from the Canvas. The old version sized its box to ~1.5x the
        // screen and offset it negative, so Compose clamped it and the
        // rings visibly originated from the right instead of the cover.
        val cx = with(density) { (artX + artW / 2f).toPx() }
        val cy = with(density) { (artY + artH / 2f).toPx() }
        val r0 = with(density) { (minOf(artW, artH) / 2f).toPx() }
        BeatRings(
            beat = beat,
            color = glow,
            centerPx = Offset(cx, cy),
            baseRadiusPx = r0,
            strength = 1f,
            modifier = Modifier.matchParentSize().clearAndSetSemantics { }
        )
    }
    // Every tier lights the cover from behind: one texture draw a frame is
    // cheap, and it is what makes the cover feel lit rather than merely
    // bouncing.
    if (e > 0.35f && q.reactiveLevel >= 1 && !isVideo) {
        // A Canvas, not a Box with a conditional background: `if
        // (level > 0.01f)` was a COMPOSITION-level branch on a value
        // that changes 60x/second, so this mounted and unmounted every
        // frame. Reading beat.level inside the draw scope keeps the
        // whole effect in the draw phase, and the layer keeps that redraw
        // to this canvas instead of the whole player.
        //
        // The edge light: the colour at each edge of the cover spills out
        // behind it, capped so a white cover's halo stays off the title.
        // Until the cover's light is ready, and for a cover with none, the
        // old glow in one colour stands in.
        val cxB = with(density) { (artX + artW / 2f).toPx() }
        val cyB = with(density) { (artY + artH / 2f).toPx() }
        val baseB = with(density) { minOf(artW, artH).toPx() }
        val fade = ((e - 0.35f) / 0.65f).coerceIn(0f, 1f)
        val darkTheme = ink.luminance() < 0.5f
        val edge = light?.edge
        val cap = light?.let { edgeLightCap(it, ink.lumaSrgb(), darkTheme) } ?: 1f
        val depth = q.parallax
        Canvas(Modifier.matchParentSize().graphicsLayer().clearAndSetSemantics { }) {
            val lv = beat.level
            if (edge != null) {
                // With depth the light sits under the cover, so it slides a
                // little the other way as the cover tilts.
                val k = ((e - 0.5f) * 2f).coerceIn(0f, 1f)
                val ox = if (depth) -tilt.x * baseB * 0.05f * k else 0f
                val oy = if (depth) -tilt.y * baseB * 0.05f * k else 0f
                drawEdgeLight(
                    edge, Offset(cxB + ox, cyB + oy), baseB, lv,
                    alpha = (0.62f + 0.38f * lv).coerceAtMost(1f) * cap * fade
                )
            } else {
                val r = baseB * (0.58f + 0.30f * lv)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glow.copy(alpha = (0.24f + 0.76f * lv).coerceAtMost(1f) * fade),
                            glow.copy(alpha = (0.08f + 0.34f * lv) * fade),
                            Color.Transparent
                        ),
                        center = Offset(cxB, cyB),
                        radius = r
                    ),
                    radius = r,
                    center = Offset(cxB, cyB)
                )
            }
        }
    }
}

/** The one artwork element that morphs from the pill's thumbnail to the hero cover. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PlayerArtwork(
    e: Float,
    q: QualityProfile,
    isVideo: Boolean,
    lyr: Float,
    beat: BeatState,
    tilt: Tilt,
    glow: Color,
    artX: androidx.compose.ui.unit.Dp,
    artY: androidx.compose.ui.unit.Dp,
    artW: androidx.compose.ui.unit.Dp,
    artH: androidx.compose.ui.unit.Dp,
    artCorner: androidx.compose.ui.unit.Dp,
    artItem: AppMediaItem?,
    reduced: Boolean,
    videoSurface: Boolean,
    vm: PlayerViewModel
) {
    val density = LocalDensity.current
    // Outside the layer lambda: in there `density` would be the
    // layer scope's own.
    val tiltCamera = 14f * density.density
    Box(
        Modifier.offset(x = artX, y = artY).size(width = artW, height = artH)
            // 10% at full level. With the punch curve the value sits
            // near zero between hits, so this reads as a strike rather
            // than a constant wobble.
            .graphicsLayer {
                // beat.level read HERE, inside the layer lambda: this
                // re-runs on the draw pass only, never recomposing.
                // 3%, not 10% - artwork that visibly grows reads as a
                // bounce; the bloom and rings carry the beat.
                val s = 1f + beat.level * 0.03f
                scaleX = s; scaleY = s
                // Gives way to the lyrics; the pill keeps its cover.
                alpha = 1f - lyr * e
                // Premium and Ultra: the cover tilts with the phone,
                // a few degrees, only once it is the hero.
                if (q.parallax) {
                    val k = ((e - 0.5f) * 2f).coerceIn(0f, 1f)
                    rotationY = tilt.x * 7f * k
                    rotationX = -tilt.y * 7f * k
                    cameraDistance = tiltCamera
                }
            }
            // Depth grows as it expands: a pill needs almost none, the
            // hero cover is the strongest thing on the screen.
            .shadow(
                elevation = lerpDp(Elevation.low, Elevation.high, e),
                shape = RoundedCornerShape(artCorner),
                clip = false,
                // A coloured shadow reads as light under the cover on
                // a dark room; black shadow on black is invisible.
                ambientColor = if (isVideo) Color.Black else glow,
                spotColor = if (isVideo) Color.Black else glow
            )
            .clip(RoundedCornerShape(artCorner))
            // Light ON the cover: the parallax sheen, and on Premium
            // and Ultra with Reactive artwork a flash that sweeps it on
            // a hard hit. Drawn after the content, inside the clip.
            .drawWithContent {
                drawContent()
                if (e > 0.5f && !isVideo && (q.parallax || q.reactiveLevel >= 3)) {
                    val hit = beat.lastHitNanos
                    val since = beat.frameNanos - hit
                    val flash = if (q.reactiveLevel >= 3 && hit != 0L && since in 0..FLASH_NS) {
                        val f = 1f - since / FLASH_NS.toFloat()
                        f * f * beat.lastHitPower
                    } else 0f
                    val k = ((e - 0.5f) * 2f).coerceIn(0f, 1f)
                    drawCoverLight(
                        tiltX = tilt.x * k, tiltY = tilt.y * k,
                        sheen = q.parallax,
                        flash = flash * k,
                        flashPos = (since / FLASH_NS.toFloat()).coerceIn(0f, 1f)
                    )
                }
            }
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
                isVideo && videoSurface -> AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            setBackgroundColor(android.graphics.Color.BLACK)
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { it.player = vm.boundPlayer() },
                    // Hand the surface back explicitly instead of
                    // letting surfaceDestroyed null the output.
                    onRelease = { it.player = null },
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
}

/** The docked pill's title, play control and progress line. */
@Composable
private fun BoxScope.MiniChrome(
    miniAlpha: Float,
    state: PlayerState,
    darkTheme: Boolean,
    vm: PlayerViewModel
) {
    // Mirror-symmetric. The artwork sits 8dp in from the left; the
    // play control's centre now sits exactly as far in from the
    // right as the artwork's does from the left. Before, the 26dp
    // icon was padded 16dp AND grown to a 48dp touch box, so its
    // visible edge stood ~27dp in against the art's 8dp and the
    // whole pill read as shifted left.
    val inset = MINI_INSET.dp
    Row(
        Modifier.fillMaxWidth().height(MINI_HEIGHT.dp)
            .padding(start = (MINI_INSET + MINI_ART + 12).dp, end = inset - 2.dp)
            .alpha(miniAlpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(shownTitle(state.currentTitle), style = Typo.Primary, color = MediaColors.Cream,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(shownArtist(state.currentArtist), style = Typo.Secondary, color = MediaColors.CreamDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Space.sm))
        // Matching CENTRES was not enough. Measured on a Redmi
        // 10C the outer margins were equal to the pixel, but the
        // artwork is a solid 44dp block 8dp from the left edge and
        // a bare pause glyph is ~12dp wide with ~22dp of air to its
        // right - the eye reads visible edges, so the pill looked
        // tight on the left and loose on the right. The control now
        // has a visible 44dp disc, the artwork's exact size, whose
        // edge sits the same 8dp from the right: a true mirror.
        // The 48dp touch box with end padding inset - 2dp puts the
        // 44dp disc's edge at exactly `inset`.
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .pressScale(haptic = true) { vm.togglePlayPause() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(MINI_ART.dp).clip(CircleShape)
                    .background(
                        if (darkTheme) Color.White.copy(alpha = 0.09f)
                        else Color.Black.copy(alpha = 0.06f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                PlayPauseIcon(
                    playing = state.isPlaying, tint = MediaColors.Cream,
                    contentDescription = stringResource(R.string.action_play_pause),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
    // §11: a thin progress line along the bottom edge, inset to
    // where the capsule's curve allows it. Edge to edge, the round
    // ends clipped it to a stub that started off-centre.
    val prog = if (state.durationMs > 0)
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    // Read the colours HERE: MediaColors.* are @Composable getters
    // and the DrawScope lambda is not a composable context.
    val accent = MediaColors.Accent
    Canvas(
        Modifier.fillMaxWidth().height(2.dp)
            .align(Alignment.BottomCenter)
            .padding(horizontal = 22.dp)
            .offset(y = (-3).dp)
            .alpha(miniAlpha)
    ) {
        // Progress only - no grey track behind it.
        val y = size.height / 2
        if (prog > 0f) drawLine(
            color = accent,
            start = Offset(0f, y),
            end = Offset(size.width * prog, y),
            strokeWidth = size.height, cap = StrokeCap.Round
        )
    }
}

/** Now Playing's top bar: collapse, video controls, lyrics, EQ, queue. */
@Composable
private fun NowPlayingTopBar(
    state: PlayerState,
    fullAlpha: Float,
    showLyrics: Boolean,
    onCollapse: () -> Unit,
    onFullscreen: () -> Unit,
    onToggleLyrics: () -> Unit,
    onOpenSound: () -> Unit,
    onOpenQueue: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding()
            .padding(Space.sm, Space.sm).alpha(fullAlpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.KeyboardArrowDown, stringResource(R.string.action_collapse), tint = MediaColors.Cream,
            modifier = Modifier.size(28.dp).pressScale { onCollapse() }
        )
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.now_playing_title), style = Typo.Tertiary, color = MediaColors.CreamDim)
        Spacer(Modifier.weight(1f))
        // PiP only exists for video, and only where the device
        // supports it - no dead button on an audio track.
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (state.isVideo) {
            Icon(
                Icons.Filled.Fullscreen, stringResource(R.string.action_fullscreen), tint = MediaColors.Cream,
                modifier = Modifier.size(26.dp)
                    .pressScale(haptic = true, onClick = onFullscreen)
            )
            Spacer(Modifier.width(Space.md))
        }
        if (state.isVideo && Pip.isSupported(ctx)) {
            Icon(
                Icons.Filled.PictureInPictureAlt, stringResource(R.string.action_pip),
                tint = MediaColors.Cream,
                modifier = Modifier.size(26.dp).pressScale(haptic = true) {
                    (ctx as? android.app.Activity)?.let {
                        Pip.enter(it, state.videoWidth, state.videoHeight)
                    }
                }
            )
            Spacer(Modifier.width(Space.md))
        }
        // Video has no favourites concept here and the row is
        // already carrying fullscreen + PiP.
        if (!state.isVideo) {
            Icon(
                Icons.Filled.Lyrics, stringResource(if (showLyrics) R.string.action_hide_lyrics else R.string.action_lyrics),
                tint = if (showLyrics) MediaColors.Accent else MediaColors.Cream,
                modifier = Modifier.size(24.dp).pressScale(haptic = true) { onToggleLyrics() }
            )
            Spacer(Modifier.width(Space.md))
            // The Sound screen, one tap from what is playing.
            // Accent while the EQ is on, like shuffle and repeat.
            val eqOn = SoundEngine.settings.collectAsState().value.eqEnabled
            Icon(
                Icons.Filled.Equalizer, stringResource(R.string.action_equalizer),
                tint = if (eqOn) MediaColors.Accent else MediaColors.Cream,
                modifier = Modifier.size(24.dp).pressScale(haptic = true, onClick = onOpenSound)
            )
            Spacer(Modifier.width(Space.md))
        }
        Icon(
            Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.action_queue), tint = MediaColors.Cream,
            modifier = Modifier.size(28.dp).pressScale(haptic = true) { onOpenQueue() }
        )
    }
}

/** Everything under the artwork: the sung line, title, scrubber, transport. */
@Composable
private fun BoxScope.NowPlayingControls(
    fullAlpha: Float,
    state: PlayerState,
    vm: PlayerViewModel,
    controlsPx: MutableState<Int>,
    previewLyrics: LyricsState?,
    previewOffset: Long,
    showLyrics: Boolean,
    onOpenLyrics: () -> Unit,
    titleShadow: Shadow?,
    source: SourceFormat?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenAudioPath: () -> Unit,
    envelope: FloatArray?,
    beat: BeatState,
    npAccent: Color,
    onOpenSleep: () -> Unit,
    onOpenSpeed: () -> Unit
) {
    Column(
        // Measured BEFORE the nav-bar padding. Measured after it,
        // controlsPx left out the nav bar's height, the lyrics
        // panel ran that far too low, and its Synced / offset row
        // sat on top of the title and the heart.
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { controlsPx.value = it.height }
            .navigationBarsPadding().alpha(fullAlpha)
    ) {
        // The line being sung, under the artwork, the way Spotify
        // shows it. Only for real synced lyrics, and hidden while
        // the full lyrics are open. Tap opens them.
        val nowLine = remember(previewLyrics, state.positionMs, previewOffset) {
            (previewLyrics as? LyricsState.Synced)?.lines?.let { lines ->
                Lrc.activeIndex(lines, state.positionMs, previewOffset)
                    .takeIf { it >= 0 }?.let { lines[it].text }
            }
        }
        if (!state.isVideo && !showLyrics && previewLyrics is LyricsState.Synced) {
            androidx.compose.animation.AnimatedContent(
                targetState = nowLine.orEmpty(),
                transitionSpec = {
                    (fadeIn(tween(Motion.Standard)) togetherWith fadeOut(tween(Motion.Fast)))
                },
                label = "lyricLine",
                modifier = Modifier.fillMaxWidth()
                    .padding(start = Space.xl, end = Space.xl, bottom = Space.md)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onOpenLyrics() }
            ) { line ->
                Text(
                    line.ifBlank { "\u266A" },
                    style = Typo.Primary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = MediaColors.Cream,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
        NowPlayingTitle(
            state = state, showLyrics = showLyrics, titleShadow = titleShadow, source = source,
            isFavorite = isFavorite, onToggleFavorite = onToggleFavorite,
            onOpenAudioPath = onOpenAudioPath
        )

        if (state.durationMs > 0) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl)) {
                val loopNow by ProPlayback.loop.collectAsState()
                val loop = if (isPro()) loopNow else null
                Scrubber(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    onSeek = { vm.seekTo(it) },
                    envelope = envelope,
                    beat = beat,
                    activeColor = npAccent,
                    loopAMs = loop?.aMs ?: -1L,
                    loopBMs = loop?.bMs ?: -1L
                )
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(fmtClock(state.positionMs), style = Typo.Tertiary,
                        color = MediaColors.CreamFaint)
                    Text(fmtClock(state.durationMs), style = Typo.Tertiary,
                        color = MediaColors.CreamFaint)
                }
            }
        }

        NowPlayingTransport(state = state, vm = vm, onOpenSleep = onOpenSleep, onOpenSpeed = onOpenSpeed)
    }
}

/** Title, artist, the heart, and the source badge. */
@Composable
private fun NowPlayingTitle(
    state: PlayerState,
    showLyrics: Boolean,
    titleShadow: Shadow?,
    source: SourceFormat?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenAudioPath: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(Space.xl, 0.dp, Space.xl, Space.md)) {
        // Heart beside the title, under the art: where the
        // thumb is, not up in the top bar.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(shownTitle(state.currentTitle), style = Typo.Section.copy(shadow = titleShadow), color = MediaColors.Cream,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(shownArtist(state.currentArtist), style = Typo.Body.copy(shadow = titleShadow), color = MediaColors.CreamDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Not over lyrics: the words are the subject there,
            // and a heart beside them is clutter.
            if (!state.isVideo && !showLyrics) {
                Spacer(Modifier.width(Space.md))
                FavoriteButton(isFavorite, Modifier.size(28.dp), onToggleFavorite)
            }
        }
        // What the file really is, quietly. Tap for the whole
        // path from file to headphones.
        source?.badge?.let { badge ->
            Text(
                if (source?.hiRes == true) "Hi-Res \u00b7 $badge" else badge,
                style = Typo.Tertiary,
                color = if (source?.hiRes == true) MediaColors.Accent else MediaColors.CreamFaint,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                // Plain click: pressScale's 48dp minimum would
                // push the transport down by a whole row.
                modifier = Modifier.padding(top = Space.xxs)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenAudioPath
                    )
            )
        }
    }
}

/** Previous / play / next, then shuffle, repeat, the A-B loop, speed and the sleep timer. */
@Composable
private fun NowPlayingTransport(
    state: PlayerState,
    vm: PlayerViewModel,
    onOpenSleep: () -> Unit,
    onOpenSpeed: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(Space.xl, Space.md),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.SkipPrevious, stringResource(R.string.action_previous), tint = MediaColors.Cream,
            modifier = Modifier.size(34.dp).pressScale(haptic = true) { vm.previous() })
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MediaColors.Cream)
                .pressScale(scaleDown = 0.92f, haptic = true) { vm.togglePlayPause() },
            contentAlignment = Alignment.Center
        ) {
            PlayPauseIcon(state.isPlaying, MediaColors.OnInverse,
                Modifier.size(34.dp), stringResource(R.string.action_play_pause))
        }
        Icon(Icons.Filled.SkipNext, stringResource(R.string.action_next), tint = MediaColors.Cream,
            modifier = Modifier.size(34.dp).pressScale(haptic = true) { vm.next() })
    }

    Row(
        Modifier.fillMaxWidth().padding(Space.xl, 0.dp, Space.xl, Space.xl),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Shuffle, stringResource(R.string.action_shuffle),
            tint = if (state.shuffle) MediaColors.Accent else MediaColors.CreamDim,
            modifier = Modifier.size(IconSize.lg).pressScale(haptic = true) { vm.toggleShuffle() })
        Icon(
            if (state.repeatMode == 1) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            stringResource(R.string.action_repeat),
            tint = if (state.repeatMode != 0) MediaColors.Accent else MediaColors.CreamDim,
            modifier = Modifier.size(IconSize.lg).pressScale(haptic = true) { vm.cycleRepeat() }
        )
        LoopButton(vm)
        Text(
            speedLabel(state.speed), style = Typo.Primary,
            color = if (state.speed != 1.0f) MediaColors.Accent else MediaColors.CreamDim,
            modifier = Modifier.pressScale(haptic = true, onClick = onOpenSpeed)
        )
        if (state.sleepActive && !state.sleepEndOfTrack) {
            Text(fmtClock(state.sleepRemainingMs), style = Typo.Primary,
                color = MediaColors.Accent,
                modifier = Modifier.pressScale { onOpenSleep() })
        } else {
            Icon(Icons.Filled.Bedtime, stringResource(R.string.action_sleep_timer),
                tint = if (state.sleepActive) MediaColors.Accent else MediaColors.CreamDim,
                modifier = Modifier.size(IconSize.lg).pressScale { onOpenSleep() })
        }
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
fun PipVideoOnly(vm: PlayerViewModel, state: PlayerState, artItem: AppMediaItem?) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (state.isVideo) {
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
        } else if (artItem != null) {
            // A PlayerView with no video track is a black rectangle. If we end
            // up in PiP on audio anyway - a system trigger, a race as the track
            // changes - show the cover rather than an empty window.
            CoverArt(artItem, Modifier.fillMaxSize(), corner = 0, targetPx = 384)
        }
    }
}
