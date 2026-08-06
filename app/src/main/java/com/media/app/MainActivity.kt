package com.media.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import kotlin.math.roundToInt
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : ComponentActivity() {
    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        var keep = true
        splash.setKeepOnScreenCondition { keep }
        window.decorView.postDelayed({ keep = false }, 850)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by SettingsStore.flow(this).collectAsState(initial = MediaSettings())
            val dark = when (settings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                else -> true
            }
            SetStatusBarIcons(dark)
            MediaTheme(themeMode = settings.themeMode, fontScale = settings.fontScale) {
                Surface(Modifier.fillMaxSize(), color = MediaColors.Ink) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun SetStatusBarIcons(darkTheme: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Transparency + no-contrast-scrim now come from Theme.Media (applied
            // before Compose mounts). Here we only keep what must react to the
            // runtime theme: the light/dark system-bar ICON appearance.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            // Dark theme -> light icons on both bars; Light theme -> dark icons
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

@UnstableApi
@Composable
fun AppRoot(vm: PlayerViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val introSeen by SettingsStore.introSeenFlow(context).collectAsState(initial = null)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> granted = result.values.all { it } }

    // Local step for the first-run flow: 0 = welcome, 1 = permission explainer.
    var onboardStep by remember { mutableStateOf(0) }

    when {
        introSeen == null -> Box(Modifier.fillMaxSize().background(MediaColors.Ink))
        introSeen == true && granted -> HomeScaffold(vm)
        introSeen == true && !granted ->
            PermissionGate { launcher.launch(requiredPermissions()) }
        onboardStep == 0 -> WelcomePage(onContinue = { onboardStep = 1 })
        else -> PermissionExplainerPage(onContinue = {
            scope.launch { SettingsStore.setIntroSeen(context) }
            launcher.launch(requiredPermissions())
        })
    }
}

@Composable
private fun WelcomePage(onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MediaColors.Ink).systemBarsPadding().padding(Space.xl)) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text("Media", style = MaterialTheme.typography.displayLarge, color = MediaColors.Cream)
            Spacer(Modifier.height(Space.md))
            Text("All your media, one calm home.",
                style = MaterialTheme.typography.titleLarge, color = MediaColors.CreamDim)
            Spacer(Modifier.height(Space.xl))
            WelcomeLine("Music, podcasts, audiobooks, and video — together.")
            Spacer(Modifier.height(Space.md))
            WelcomeLine("Everything plays locally. No accounts, no tracking.")
            Spacer(Modifier.height(Space.md))
            WelcomeLine("Calm, editorial, and quietly out of your way.")
        }
        Box(
            Modifier.align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(30.dp)).background(MediaColors.Cream)
                .clickable(onClick = onContinue)
                .padding(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Text("Got it", style = MaterialTheme.typography.titleMedium, color = MediaColors.Ink)
        }
    }
}

@Composable
private fun WelcomeLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MediaColors.Cream)
}

@Composable
private fun PermissionExplainerPage(onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MediaColors.Ink).systemBarsPadding().padding(Space.xl)) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text("One quick thing", style = MaterialTheme.typography.displaySmall, color = MediaColors.Cream)
            Spacer(Modifier.height(Space.lg))
            Text(
                "Media plays the songs, podcasts, audiobooks, and videos already on your phone. " +
                "To find them, it needs permission to read your media.",
                style = MaterialTheme.typography.bodyLarge, color = MediaColors.CreamDim
            )
            Spacer(Modifier.height(Space.md))
            Text(
                "Nothing leaves your device, and nothing is uploaded anywhere.",
                style = MaterialTheme.typography.bodyLarge, color = MediaColors.CreamDim
            )
        }
        Box(
            Modifier.align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(30.dp)).background(MediaColors.Cream)
                .clickable(onClick = onContinue)
                .padding(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Text("Understood", style = MaterialTheme.typography.titleMedium, color = MediaColors.Ink)
        }
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MediaColors.Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Space.xl)) {
            Text("Media", style = MaterialTheme.typography.displaySmall, color = MediaColors.Cream)
            Spacer(Modifier.height(Space.md))
            Text(
                "All your music, podcasts, video, and audiobooks in one home.",
                style = MaterialTheme.typography.bodyLarge,
                color = MediaColors.CreamDim,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(Space.xl))
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MediaColors.Cream, contentColor = MediaColors.Ink
                )
            ) { Text("Grant access") }
        }
    }
}

// Height of the bottom nav row (excludes the system nav-bar inset, which is
// applied separately via navigationBarsPadding). The mini-player floats a
// small gap above this — both derive from BottomBarHeight so they never drift.
private val BottomBarHeight = 58.dp
private val MiniPlayerGap = 8.dp

@UnstableApi
@Composable
fun HomeScaffold(vm: PlayerViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var showPlayer by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPodcasts by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var libraryPillar by remember { mutableStateOf<Pillar?>(null) }
    var showAudiobooks by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    val db = remember { OverrideDatabase.get(context) }
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.flow(context).collectAsState(initial = MediaSettings())

    val recentHistory by remember {
        db.historyDao().observeRecent(10)
    }.collectAsState(initial = emptyList())

    // Record a play (after 5s) into history; upsert = auto-dedup + move to front by timestamp.
    LaunchedEffect(Unit) {
        vm.onQualifyingPlay = { mediaId ->
            scope.launch {
                db.historyDao().record(PlayHistory(mediaId, System.currentTimeMillis()))
            }
        }
    }
    val overrides by remember {
        db.dao().observeAll().map { list -> list.associateBy { it.mediaId } }
    }.collectAsState(initial = emptyMap())
    val positions by remember {
        db.positionDao().observeAll().map { list -> list.associateBy { it.mediaId } }
    }.collectAsState(initial = emptyMap())

    val allAudio = remember(overrides, reloadKey) { MediaRepository.audioWithOverrides(context, overrides) }
    val music = remember(allAudio) { allAudio.filter { it.pillar == Pillar.MUSIC } }
    val podcasts = remember(allAudio) { allAudio.filter { it.pillar == Pillar.PODCAST } }
    val audiobooks = remember(allAudio) { allAudio.filter { it.pillar == Pillar.AUDIOBOOK } }
    val video = remember(reloadKey) { MediaRepository.loadVideo(context) }

    // Edit sheet state
    var editItem by remember { mutableStateOf<AppMediaItem?>(null) }

    // Android back: close the topmost open overlay, step by step.
    BackHandler(enabled = editItem != null) { editItem = null }
    BackHandler(enabled = editItem == null && showPlayer) { showPlayer = false }
    BackHandler(enabled = editItem == null && !showPlayer && showSearch) { showSearch = false }
    BackHandler(enabled = editItem == null && !showPlayer && !showSearch && showSettings) { showSettings = false }
    BackHandler(enabled = editItem == null && !showPlayer && !showSearch && !showSettings && showLibrary) { showLibrary = false; libraryPillar = null }
    BackHandler(enabled = editItem == null && !showPlayer && !showSearch && !showSettings && !showLibrary && showPodcasts) { showPodcasts = false }
    BackHandler(enabled = editItem == null && !showPlayer && !showSearch && !showSettings && !showLibrary && !showPodcasts && showAudiobooks) { showAudiobooks = false }

    val continueItems = remember(recentHistory, music, podcasts, audiobooks, video) {
        val byId = (music + podcasts + audiobooks + video).associateBy { it.id }
        recentHistory.mapNotNull { byId[it.mediaId] }
    }

    Box(Modifier.fillMaxSize().background(MediaColors.Ink)) {
        // Bottom inset = real nav-bar inset + chrome offset (bottom bar + mini-
        // player), so the last shelf always clears the chrome on any device
        // (gesture or 3-button). No magic number.
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            contentPadding = PaddingValues(bottom = 170.dp + navBottom),
            modifier = Modifier.fillMaxSize().statusBarsPadding()
        ) {
            item { HomeHeader(onSearch = { showSearch = true }, onAccount = { showSettings = true }) }

            if (continueItems.isNotEmpty()) {
                item {
                    ShelfHeader("Continue", subtitle = "Pick up where you left off")
                    MediaShelf(continueItems, state, positions, large = true, onEdit = { editItem = it }) { idx ->
                        vm.playOrToggle(continueItems, idx)
                        if (continueItems[idx].type == MediaType.VIDEO) showPlayer = true
                    }
                }
            }

            item { HorizontalDivider(color = MediaColors.InkHairline, modifier = Modifier.padding(horizontal = Space.xl)) }

            if (music.isNotEmpty()) {
                item {
                    ShelfHeader("Music", subtitle = "From your library", onSeeAll = { libraryPillar = Pillar.MUSIC; showLibrary = true })
                    MediaShelf(music, state, positions, onEdit = { editItem = it }) { idx -> vm.playOrToggle(music, idx) }
                }
            }
            if (podcasts.isNotEmpty()) {
                item {
                    ShelfHeader("Podcasts", subtitle = "Shows and episodes", onSeeAll = { libraryPillar = Pillar.PODCAST; showLibrary = true })
                    MediaShelf(podcasts, state, positions, onEdit = { editItem = it }) { idx -> vm.playOrToggle(podcasts, idx) }
                }
            }
            if (audiobooks.isNotEmpty()) {
                item {
                    ShelfHeader("Audiobooks", subtitle = "Listen, chapter by chapter", onSeeAll = { libraryPillar = Pillar.AUDIOBOOK; showLibrary = true })
                    MediaShelf(audiobooks, state, positions, onEdit = { editItem = it }) { idx -> vm.playOrToggle(audiobooks, idx) }
                }
            }
            if (video.isNotEmpty()) {
                item {
                    ShelfHeader("Video", subtitle = "Everything you can watch", onSeeAll = { libraryPillar = Pillar.VIDEO; showLibrary = true })
                    MediaShelf(video, state, positions, wide = true, onEdit = { editItem = it }) { idx ->
                        vm.playOrToggle(video, idx); showPlayer = true
                    }
                }
            }

            if (music.isEmpty() && podcasts.isEmpty() && audiobooks.isEmpty() && video.isEmpty()) {
                item { EmptyState() }
            }
        }

        if (state.hasItem) {
            NowPlayingBar(
                state, vm,
                onExpand = { showPlayer = true },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = BottomBarHeight + MiniPlayerGap, start = Space.md, end = Space.md)
            )
        }
        BottomBar(Modifier.align(Alignment.BottomCenter), onLibraryTab = { showLibrary = true }, onPodcastsTab = { showPodcasts = true }) { showAudiobooks = true }
    }

    if (showPlayer) {
        FullPlayer(state, vm) { showPlayer = false }
    }
    if (showSearch) {
        SearchScreen(
            all = allAudio + video,
            onPlay = { list, idx ->
                vm.play(list, idx)
                showSearch = false
                if (list[idx].type == MediaType.VIDEO) showPlayer = true
            },
            onClose = { showSearch = false }
        )
    }
    if (showSettings) {
        SettingsScreen(
            audioCount = allAudio.size,
            videoCount = video.size,
            settings = settings,
            onThemeChange = { mode -> scope.launch { SettingsStore.setTheme(context, mode) } },
            onFontScaleChange = { scale -> scope.launch { SettingsStore.setFontScale(context, scale) } },
            onRescan = {
                MediaRepository.refresh()
                reloadKey++
            },
            onOpenTerms = { showTerms = true },
            onOpenAbout = { showAbout = true },
            onClose = { showSettings = false }
        )
    }
    if (showPodcasts) {
        PodcastsScreen(
            podcasts = podcasts,
            state = state,
            onPlay = { idx -> vm.playOrToggle(podcasts, idx) },
            onClose = { showPodcasts = false }
        )
    }
    if (showTerms) {
        TermsScreen(onClose = { showTerms = false })
    }
    if (showAbout) {
        AboutScreen(version = "1.0", onClose = { showAbout = false })
    }
    if (showAudiobooks) {
        AudiobooksScreen(
            audiobooks = audiobooks,
            state = state,
            onPlay = { idx -> vm.playOrToggle(audiobooks, idx) },
            onClose = { showAudiobooks = false }
        )
    }
    if (showLibrary) {
        LibraryScreen(
            all = allAudio + video,
            state = state,
            initialPillar = libraryPillar,
            onPlay = { list, idx ->
                vm.playOrToggle(list, idx)
                if (list[idx].type == MediaType.VIDEO) showPlayer = true
            },
            onEdit = { editItem = it },
            onClose = { showLibrary = false; libraryPillar = null }
        )
    }
    editItem?.let { item ->
        EditSheet(
            item = item,
            hasOverride = overrides.containsKey(item.id),
            onSave = { title, artist, details, pillar ->
                scope.launch {
                    db.dao().upsert(
                        MediaOverride(
                            mediaId = item.id,
                            customTitle = title,
                            customArtist = artist,
                            details = details,
                            pillar = pillar
                        )
                    )
                }
                // Push edit into the live playback session if this item is playing now
                vm.updateCurrentMetadata(item.id, title, artist)
                editItem = null
            },
            onReset = {
                scope.launch { db.dao().delete(item.id) }
                editItem = null
            },
            onDismiss = { editItem = null }
        )
    }
}

@Composable
private fun HomeHeader(onSearch: () -> Unit, onAccount: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(Space.xl, Space.xl, Space.xl, Space.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Media", style = MaterialTheme.typography.displaySmall, color = MediaColors.Cream)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            Icon(Icons.Outlined.Search, "Search", tint = MediaColors.CreamDim,
                modifier = Modifier.clickable(onClick = onSearch))
            Icon(Icons.Outlined.AccountCircle, "You", tint = MediaColors.CreamDim,
                modifier = Modifier.clickable(onClick = onAccount))
        }
    }
}

@Composable
private fun ShelfHeader(title: String, subtitle: String? = null, onSeeAll: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(Space.xl, Space.lg, Space.xl, Space.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MediaColors.Cream)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamFaint)
            }
        }
        if (onSeeAll != null) {
            Text("See all", style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamDim,
                modifier = Modifier.clickable(onClick = onSeeAll))
        }
    }
}

@Composable
private fun MediaShelf(
    items: List<AppMediaItem>,
    state: PlayerState,
    positions: Map<Long, PlaybackPosition>,
    large: Boolean = false,
    wide: Boolean = false,
    onEdit: (AppMediaItem) -> Unit,
    onPlay: (Int) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Space.xl, vertical = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        items(items.size) { idx ->
            val item = items[idx]
            val isActive = state.currentUri == item.uri.toString()
            MediaCard(item, large = large, wide = wide,
                savedPosition = positions[item.id],
                isPlaying = isActive && state.isPlaying,
                onLongPress = { onEdit(item) }) { onPlay(idx) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCard(
    item: AppMediaItem,
    large: Boolean,
    wide: Boolean,
    savedPosition: PlaybackPosition?,
    isPlaying: Boolean,
    onLongPress: () -> Unit,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "press")

    // Per-pillar shape: books are tall, video is wide, music/podcasts are square.
    // The wide/large flags (Video shelf / Continue shelf) still take precedence.
    val isBook = item.pillar == Pillar.AUDIOBOOK
    val artW = when {
        wide -> 220.dp
        large -> 150.dp
        else -> 118.dp
    }
    val artH = when {
        wide -> 124.dp
        isBook && !large -> artW * 1.42f   // ~2:3 portrait book ratio
        else -> artW
    }

    Column(
        Modifier
            .width(artW)
            .scale(scale)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        Box {
            CoverArt(item, Modifier.width(artW).height(artH), corner = if (large || wide) 14 else 12)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(Space.sm)
                    .size(34.dp).clip(CircleShape).background(MediaColors.Cream),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) "Pause" else "Play",
                    tint = MediaColors.Ink, modifier = Modifier.size(20.dp)
                )
            }
            // Duration chip — shown on time-based content (podcasts, audiobooks,
            // video), not music. Bottom-left so it clears the play button.
            val showDuration = item.pillar != Pillar.MUSIC && item.durationMs > 0
            if (showDuration) {
                // "X left" when meaningfully in-progress (started, not near the end),
                // otherwise total duration. Uses the saved resume position.
                val remainingMs = savedPosition
                    ?.takeIf { it.positionMs > 30_000L && it.positionMs < item.durationMs - 30_000L }
                    ?.let { item.durationMs - it.positionMs }
                val chipText = if (remainingMs != null) "${fmtLeft(remainingMs)} left"
                               else fmtDuration(item.durationMs)
                Box(
                    Modifier.align(Alignment.BottomStart).padding(Space.sm)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MediaColors.Ink.copy(alpha = 0.55f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        chipText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MediaColors.Cream.copy(alpha = 0.92f)
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.sm))
        Text(item.title, style = MaterialTheme.typography.titleMedium, color = MediaColors.Cream,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.artist, style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!item.details.isNullOrBlank()) {
            Text(item.details, style = MaterialTheme.typography.bodyMedium,
                color = MediaColors.CreamFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun fmtLeft(ms: Long): String {
    val totalMin = (ms / 60000).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        totalMin > 0 -> "$totalMin min"
        else -> "<1 min"
    }
}

private fun fmtDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(Space.xl, 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Nothing here yet", style = MaterialTheme.typography.titleLarge, color = MediaColors.Cream)
        Spacer(Modifier.height(Space.sm))
        Text("Add music or video to your device to see it here.",
            style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamDim)
    }
}

@Composable
private fun NowPlayingBar(
    state: PlayerState, vm: PlayerViewModel, onExpand: () -> Unit, modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val prog = if (state.durationMs > 0)
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f

    // Lightweight AppMediaItem to drive the thumbnail from the current URI.
    val artItem = state.currentUri?.let { uri ->
        AppMediaItem(
            id = uri.substringAfterLast('/').toLongOrNull() ?: 0L,
            title = state.currentTitle, artist = state.currentArtist,
            durationMs = state.durationMs, uri = android.net.Uri.parse(uri),
            type = if (state.isVideo) MediaType.VIDEO else MediaType.AUDIO,
            pillar = Pillar.MUSIC
        )
    }

    var dragX by remember { mutableStateOf(0f) }
    var dragDown by remember { mutableStateOf(0f) }
    val swipeThresholdPx = with(LocalDensity.current) { 60.dp.toPx() }

    Row(
        modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(28.dp), clip = false)
            .clip(RoundedCornerShape(28.dp))
            .background(MediaColors.InkRaised)
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        when {
                            // Downward swipe dominates -> dismiss.
                            dragDown > swipeThresholdPx && dragDown > kotlin.math.abs(dragX) -> {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                vm.dismiss()
                            }
                            // Horizontal swipe -> skip.
                            dragX <= -swipeThresholdPx -> {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                vm.next()
                            }
                            dragX >= swipeThresholdPx -> {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                vm.previous()
                            }
                        }
                        dragX = 0f; dragDown = 0f
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        dragX += delta.x
                        if (delta.y > 0) dragDown += delta.y else dragDown = (dragDown + delta.y).coerceAtLeast(0f)
                    }
                )
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Album-art thumbnail with a progress ring arcing around it.
        Box(contentAlignment = Alignment.Center) {
            if (artItem != null) {
                CoverArt(artItem, Modifier.size(44.dp), corner = 22)
            } else {
                Box(Modifier.size(44.dp).clip(CircleShape).background(MediaColors.Ink))
            }
            val trackColor = MediaColors.InkHairline
            val ringColor = MediaColors.Accent
            Canvas(Modifier.size(52.dp)) {
                val stroke = 3.dp.toPx()
                drawArc(
                    color = trackColor,
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f, sweepAngle = 360f * prog, useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                )
            }
        }

        Spacer(Modifier.width(Space.md))

        Column(Modifier.weight(1f)) {
            Text(state.currentTitle, style = MaterialTheme.typography.titleMedium,
                color = MediaColors.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.currentArtist, style = MaterialTheme.typography.bodyMedium,
                color = MediaColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        IconButton(onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); vm.togglePlayPause() }) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                "Play/Pause", tint = MediaColors.Cream, modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun BottomBar(modifier: Modifier = Modifier, onLibraryTab: () -> Unit, onPodcastsTab: () -> Unit, onAudiobooksTab: () -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(MediaColors.Ink)
            .border(width = 0.5.dp, color = MediaColors.InkHairline)
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(BottomBarHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab(Icons.Filled.Home, "Home", true, Modifier.weight(1f)) {}
            NavTab(Icons.AutoMirrored.Outlined.LibraryBooks, "Library", false, Modifier.weight(1f)) { onLibraryTab() }
            NavTab(Icons.Outlined.Podcasts, "Podcasts", false, Modifier.weight(1f)) { onPodcastsTab() }
            NavTab(Icons.AutoMirrored.Outlined.MenuBook, "Audiobooks", false, Modifier.weight(1f)) { onAudiobooksTab() }
        }
    }
}

@Composable
private fun NavTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tint = if (active) MediaColors.Cream else MediaColors.CreamFaint
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        // center the icon+label group vertically within the bar
    ) {
        Spacer(Modifier.weight(1f))
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        Spacer(Modifier.weight(1f))
    }
}

@UnstableApi
@Composable
private fun FullPlayer(state: PlayerState, vm: PlayerViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var showSleepSheet by remember { mutableStateOf(false) }
    val dragY = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val dragThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 150.dp.toPx() }

    // Lightweight item to drive CoverArt from the current URI.
    val artItem = state.currentUri?.let { uri ->
        AppMediaItem(
            id = uri.substringAfterLast('/').toLongOrNull() ?: 0L,
            title = state.currentTitle, artist = state.currentArtist,
            durationMs = state.durationMs, uri = android.net.Uri.parse(uri),
            type = if (state.isVideo) MediaType.VIDEO else MediaType.AUDIO,
            pillar = Pillar.MUSIC
        )
    }

    Box(
        Modifier.fillMaxSize().background(MediaColors.Ink)
            .offset { IntOffset(0, dragY.value.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragY.value > dragThresholdPx) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onClose()
                            dragScope.launch { dragY.snapTo(0f) }
                        } else {
                            // Below threshold: spring back into place.
                            dragScope.launch { dragY.animateTo(0f, spring()) }
                        }
                    },
                    onVerticalDrag = { _, delta ->
                        dragScope.launch { dragY.snapTo((dragY.value + delta).coerceAtLeast(0f)) }
                    }
                )
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            // HERO — bleeds to the true top edge (behind the status bar). Video
            // fills the surface; audio art is centered. Art has NO top inset:
            // this is the edge-to-edge "wow" surface. Legibility of the status
            // icons over the art is guaranteed by the scrim overlay below.
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (state.isVideo) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
                                val future = MediaController.Builder(ctx, token).buildAsync()
                                future.addListener({ player = future.get() }, MoreExecutors.directExecutor())
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (artItem != null) {
                    CoverArt(artItem, Modifier.fillMaxWidth().aspectRatio(1f).padding(Space.xl), corner = 18)
                }

                // Top scrim: ink -> transparent, tall enough to cover the status
                // bar zone. Keeps clock/battery legible over any album art.
                Box(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .height(120.dp)
                        .background(
                            Brush.verticalGradient(
                                0f to MediaColors.Ink.copy(alpha = 0.55f),
                                1f to Color.Transparent
                            )
                        )
                )

                // Close row floats over the art, inset below the status bar.
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .statusBarsPadding().padding(Space.sm, Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClose) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Close", tint = MediaColors.Cream,
                            modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Now playing",
                        style = MaterialTheme.typography.bodyMedium, color = MediaColors.Cream)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(48.dp))
                }
            }

            // Title block — serif title, editorial
            Column(Modifier.fillMaxWidth().padding(Space.xl, 0.dp, Space.xl, Space.md)) {
                Text(state.currentTitle, style = MaterialTheme.typography.titleLarge,
                    color = MediaColors.Cream, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(state.currentArtist, style = MaterialTheme.typography.bodyLarge,
                    color = MediaColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // Scrubber with time labels
            Column(Modifier.fillMaxWidth().padding(Space.xl, 0.dp)) {
                if (state.durationMs > 0) {
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
                        Text(fmtTime(state.positionMs), style = MaterialTheme.typography.labelSmall, color = MediaColors.CreamFaint)
                        Text(fmtTime(state.durationMs), style = MaterialTheme.typography.labelSmall, color = MediaColors.CreamFaint)
                    }
                }
            }

            // Primary transport
            Row(
                Modifier.fillMaxWidth().padding(Space.xl, Space.md),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton({ view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); vm.previous() }) { Icon(Icons.Filled.SkipPrevious, "Previous", tint = MediaColors.Cream, modifier = Modifier.size(34.dp)) }
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(MediaColors.Cream)
                        .clickable { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); vm.togglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause",
                        tint = MediaColors.OnInverse, modifier = Modifier.size(34.dp))
                }
                IconButton({ view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); vm.next() }) { Icon(Icons.Filled.SkipNext, "Next", tint = MediaColors.Cream, modifier = Modifier.size(34.dp)) }
            }

            // Secondary row: shuffle / repeat / speed
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(Space.xl, 0.dp, Space.xl, Space.xl),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton({ vm.toggleShuffle() }) {
                    Icon(Icons.Filled.Shuffle, "Shuffle",
                        tint = if (state.shuffle) MediaColors.Accent else MediaColors.CreamDim,
                        modifier = Modifier.size(22.dp))
                }
                IconButton({ vm.cycleRepeat() }) {
                    Icon(
                        if (state.repeatMode == 1) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        "Repeat",
                        tint = if (state.repeatMode != 0) MediaColors.Accent else MediaColors.CreamDim,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.cycleSpeed() }
                        .padding(horizontal = Space.md, vertical = Space.xs),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${state.speed}x".replace(".0x", "x"),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.speed != 1.0f) MediaColors.Accent else MediaColors.CreamDim)
                }
                // Sleep timer slot: inactive = moon; countdown = live mm:ss; end-of-track = accent moon.
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { showSleepSheet = true }
                        .padding(horizontal = Space.md, vertical = Space.xs),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        state.sleepActive && !state.sleepEndOfTrack ->
                            Text(fmtTime(state.sleepRemainingMs),
                                style = MaterialTheme.typography.titleMedium, color = MediaColors.Accent)
                        else ->
                            Icon(Icons.Filled.Bedtime, "Sleep timer",
                                tint = if (state.sleepActive) MediaColors.Accent else MediaColors.CreamDim,
                                modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }

    if (showSleepSheet) {
        SleepTimerSheet(
            state = state,
            onPick = { minutes -> vm.startSleepTimer(minutes); showSleepSheet = false },
            onEndOfTrack = { vm.startSleepEndOfTrack(); showSleepSheet = false },
            onCancelTimer = { vm.cancelSleepTimer(); showSleepSheet = false },
            onDismiss = { showSleepSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    state: PlayerState,
    onPick: (Int) -> Unit,
    onEndOfTrack: () -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MediaColors.InkRaised,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MediaColors.CreamFaint) }
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(Space.xl, Space.sm, Space.xl, Space.xl)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge, color = MediaColors.Cream)
            Spacer(Modifier.height(Space.lg))
            listOf(15, 30, 45, 60).forEach { m ->
                SleepRow("$m minutes", active = state.sleepActive && !state.sleepEndOfTrack) { onPick(m) }
            }
            SleepRow("End of current track", active = state.sleepEndOfTrack) { onEndOfTrack() }
            if (state.sleepActive) {
                Spacer(Modifier.height(Space.sm))
                Box(
                    Modifier.fillMaxWidth().clickable { onCancelTimer() }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Turn off timer", style = MaterialTheme.typography.bodyLarge, color = MediaColors.CreamDim)
                }
            }
        }
    }
}

@Composable
private fun SleepRow(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge,
            color = if (active) MediaColors.Accent else MediaColors.Cream, modifier = Modifier.weight(1f))
        if (active) Icon(Icons.Filled.Check, "Active", tint = MediaColors.Accent, modifier = Modifier.size(20.dp))
    }
}

private fun fmtTime(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(m, sec)
}
