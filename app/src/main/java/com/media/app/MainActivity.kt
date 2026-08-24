package com.media.app
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer

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
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
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
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
            // Dark-only app: bars always use light icons.
            SetStatusBarIcons(true)
            // Active mood: re-themes the whole app (accent + glow) and is settable
            // from the home screen via LocalMoodSetter. Purely visual.
            var mood by remember { mutableStateOf(Mood.default) }
            MediaTheme(themeMode = settings.themeMode, fontScale = settings.fontScale, mood = mood) {
                androidx.compose.runtime.CompositionLocalProvider(LocalMoodSetter provides { mood = it }) {
                    Surface(Modifier.fillMaxSize(), color = MediaColors.Ink) {
                        AppRoot()
                    }
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
    // Re-check on every resume: a user who denies, grants manually in system
    // Settings, then returns would otherwise stay stuck on the gate forever.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = requiredPermissions().all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val introSeen by SettingsStore.introSeenFlow(context).collectAsState(initial = null)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> granted = result.values.all { it } }

    // POST_NOTIFICATIONS is OPTIONAL and deliberately NOT part of
    // requiredPermissions(): it gates the media notification + lock-screen
    // controls, not the app itself. Denying it must never block the UI, so it
    // is asked separately, once, after media access is already granted.
    // Without this request the Media3 notification silently never posts on
    // Android 13+.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* denial is fine: playback works, just no notification */ }

    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
    var showLibrary by remember { mutableStateOf(false) }
    var libraryPillar by remember { mutableStateOf<Pillar?>(null) }
    var currentTab by remember { mutableStateOf(0) }
    var showPlaylists by remember { mutableStateOf(false) }
    var openPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var addToItem by remember { mutableStateOf<AppMediaItem?>(null) }
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
                db.historyDao().record(mediaId, System.currentTimeMillis())
            }
        }
    }
    val overrides by remember {
        db.dao().observeAll().map { list -> list.associateBy { it.mediaId } }
    }.collectAsState(initial = emptyMap())

    // MediaStore scan runs on IO and lands back as state. Bumping reloadKey
    // (rescan) re-runs it. `scanning` distinguishes "still loading" from
    // "genuinely empty" so the empty state can't flash on launch.
    var rawAudio by remember { mutableStateOf<List<AppMediaItem>>(emptyList()) }
    var video by remember { mutableStateOf<List<AppMediaItem>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    LaunchedEffect(reloadKey) {
        scanning = true
        rawAudio = MediaRepository.loadAudio(context)
        video = MediaRepository.loadVideo(context)
        scanning = false
    }

    // Override application is pure and cheap, so an edit re-maps in place
    // without re-querying MediaStore.
    val allAudio = remember(rawAudio, overrides) { MediaRepository.applyOverrides(rawAudio, overrides) }
    val music = remember(allAudio) { allAudio.filter { it.pillar == Pillar.MUSIC } }

    // Active mood + its whole-app theme setter (from MainActivity).
    val setMood = LocalMoodSetter.current
    val mood = LocalMood.current

    // Favorites set (drives the heart icon everywhere).
    val favorites by remember {
        db.moodDao().observeMood(Mood.FAVORITES.key).map { list -> list.map { it.mediaId }.toSet() }
    }.collectAsState(initial = emptySet())

    // Live membership for the CURRENTLY selected mood (Workout/Late Night/Focus/Favorites).
    // Re-subscribes whenever the mood changes, so Home updates instantly.
    val moodMembers by remember(mood) {
        if (mood.holdsSongs)
            db.moodDao().observeMood(mood.key).map { list -> list.map { it.mediaId }.toSet() }
        else
            kotlinx.coroutines.flow.flowOf(emptySet())
    }.collectAsState(initial = emptySet())

    // All mood memberships (for smart-tab counts + add-to checkmarks).
    val allMoodMembers by remember {
        db.moodDao().observeAll()
    }.collectAsState(initial = emptyList())
    val moodCounts = remember(allMoodMembers) {
        allMoodMembers.groupingBy { it.moodKey }.eachCount()
    }
    // User playlists + all playlist memberships.
    val playlists by remember { db.playlistDao().observePlaylists() }.collectAsState(initial = emptyList())
    val allPlaylistMembers by remember { db.playlistDao().observeAllMembers() }.collectAsState(initial = emptyList())

    // Edit sheet state
    var editItem by remember { mutableStateOf<AppMediaItem?>(null) }

    // Android back: ONE handler with an explicit priority order, topmost first.
    // This was a chain of nine BackHandlers whose enabled-guards had to be kept
    // mutually exclusive by hand — and Terms/About had no handler at all, so
    // back exited the app instead of closing them. Returning from a tab screen
    // also resets currentTab so the nav highlight doesn't lie.
    val anyOverlay = addToItem != null || editItem != null || showTerms || showAbout ||
        showPlayer || showSearch || showSettings || openPlaylist != null ||
        showPlaylists || showLibrary
    BackHandler(enabled = anyOverlay) {
        when {
            addToItem != null -> addToItem = null
            editItem != null -> editItem = null
            showTerms -> showTerms = false
            showAbout -> showAbout = false
            showPlayer -> showPlayer = false
            showSearch -> { showSearch = false; currentTab = 0 }
            showSettings -> { showSettings = false; currentTab = 0 }
            openPlaylist != null -> openPlaylist = null
            showPlaylists -> { showPlaylists = false; currentTab = 0 }
            showLibrary -> { showLibrary = false; libraryPillar = null; currentTab = 0 }
        }
    }

    // Outer container: home + all overlays render inside; the mini-player and
    // bottom nav sit at the end so they PERSIST above every screen.
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().background(moodBackground())) {
        // Bottom inset = real nav-bar inset + chrome offset (bottom bar + mini-
        // player), so the last shelf always clears the chrome on any device
        // (gesture or 3-button). No magic number.
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        // Favorites mood shows only favorited tracks; every other mood shows all music.
        val shown = if (mood.holdsSongs) music.filter { moodMembers.contains(it.id) } else music

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // FIXED top region — does not scroll. Songs scroll underneath it.
            StashHeader(
                onSearch = { showSearch = true },
                onRescan = { MediaRepository.refresh(); reloadKey++ },
                onPlaylists = { showPlaylists = true; currentTab = 2 }
            )
            MoodChips(active = mood, onPick = { setMood(it) })
            MoodBanner(mood)
            Spacer(Modifier.height(Space.md))
            StatCards(
                recentlyPlayed = recentHistory.size,
                allTracks = music.size,
                favorites = favorites.size
            )
            CountAndShuffle(count = shown.size, onShuffle = {
                if (shown.isNotEmpty()) {
                    if (!state.shuffle) vm.toggleShuffle()
                    vm.play(shown, (shown.indices).random())
                }
            })

            // SCROLLING list — only the tracks move.
            if (scanning) {
                ScanningState()
            } else if (shown.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 170.dp + navBottom),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(shown.size) { idx ->
                        val track = shown[idx]
                        TrackRow(
                            item = track,
                            isPlaying = state.currentUri == track.uri.toString() && state.isPlaying,
                            isFavorite = favorites.contains(track.id),
                            onClick = { vm.playOrToggle(shown, idx) },
                            onLongPress = { addToItem = track },
                            onToggleFav = {
                                scope.launch {
                                    if (favorites.contains(track.id)) db.moodDao().remove(Mood.FAVORITES.key, track.id)
                                    else db.moodDao().add(MoodMember(Mood.FAVORITES.key, track.id, System.currentTimeMillis()))
                                }
                            }
                        )
                    }
                }
            }
        }

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
    if (showPlaylists) {
        PlaylistsScreen(
            playlists = playlists,
            moodCounts = moodCounts,
            onOpenMood = { m -> setMood(m); showPlaylists = false; currentTab = 0 },
            onCreatePlaylist = { name ->
                scope.launch { db.playlistDao().create(Playlist(name = name, createdAt = System.currentTimeMillis())) }
            },
            onOpenPlaylist = { pl -> openPlaylist = pl },
            onDeletePlaylist = { pl ->
                if (openPlaylist?.id == pl.id) openPlaylist = null
                scope.launch { db.playlistDao().clearMembers(pl.id); db.playlistDao().deletePlaylist(pl.id) }
            },
            onClose = { showPlaylists = false; currentTab = 0 }
        )
    }
    openPlaylist?.let { pl ->
        // Members carry addedAt, so honour insertion order rather than whatever
        // order the flat observeAllMembers query happens to return.
        val byId = remember(allAudio, video) { (allAudio + video).associateBy { it.id } }
        val tracks = remember(allPlaylistMembers, pl.id, byId) {
            allPlaylistMembers.filter { it.playlistId == pl.id }
                .sortedBy { it.addedAt }
                .mapNotNull { byId[it.mediaId] }
        }
        PlaylistDetailScreen(
            playlist = pl,
            tracks = tracks,
            state = state,
            favorites = favorites,
            onPlay = { idx -> vm.playOrToggle(tracks, idx) },
            onShuffle = {
                if (tracks.isNotEmpty()) {
                    if (!state.shuffle) vm.toggleShuffle()
                    vm.play(tracks, tracks.indices.random())
                }
            },
            onLongPress = { addToItem = it },
            onToggleFav = { track ->
                scope.launch {
                    if (favorites.contains(track.id)) db.moodDao().remove(Mood.FAVORITES.key, track.id)
                    else db.moodDao().add(MoodMember(Mood.FAVORITES.key, track.id, System.currentTimeMillis()))
                }
            },
            onClose = { openPlaylist = null }
        )
    }
    if (showSettings) {
        SettingsScreen(
            audioCount = allAudio.size,
            videoCount = video.size,
            settings = settings,
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
            onClose = { showLibrary = false; libraryPillar = null; currentTab = 0 }
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

    // ---- PERSISTENT chrome: mini-player + bottom nav, above all overlays ----
    var playerHidden by remember { mutableStateOf(false) }
    LaunchedEffect(state.isPlaying, state.currentUri) {
        if (state.isPlaying) playerHidden = false
        else if (state.hasItem) { delay(10_000); playerHidden = true }
    }
    AnimatedVisibility(
        visible = state.hasItem && !playerHidden,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        NowPlayingBar(
            state, vm,
            onExpand = { showPlayer = true },
            modifier = Modifier.navigationBarsPadding().padding(bottom = BottomBarHeight + MiniPlayerGap, start = Space.md, end = Space.md)
        )
    }
    BottomBar(Modifier.align(Alignment.BottomCenter), current = currentTab) { tab ->
        // Every tab first clears ALL overlays (mutually exclusive), then opens its own.
        showPlaylists = false; showSearch = false; showSettings = false
        showLibrary = false; libraryPillar = null; openPlaylist = null
        currentTab = tab
        when (tab) {
            1 -> showLibrary = true
            2 -> showPlaylists = true
            3 -> showSearch = true
            4 -> showSettings = true
            // 0 Home -> the home surface itself, no overlay
        }
    }
    // ---- Surfaces that must sit ABOVE the persistent chrome ----
    // Order matters: everything below is drawn after BottomBar, so the nav bar
    // and mini-player no longer paint over the full player and the info pages.
    if (showTerms) {
        TermsScreen(onClose = { showTerms = false })
    }
    if (showAbout) {
        AboutScreen(version = BuildConfig.VERSION_NAME, onClose = { showAbout = false })
    }
    if (showPlayer) {
        FullPlayer(state, vm) { showPlayer = false }
    }
    addToItem?.let { item ->
        val itemMoods = allMoodMembers.filter { it.mediaId == item.id }.map { it.moodKey }.toSet()
        val itemPlaylists = allPlaylistMembers.filter { it.mediaId == item.id }.map { it.playlistId }.toSet()
        AddToSheet(
            item = item,
            memberMoods = itemMoods,
            playlists = playlists,
            memberPlaylists = itemPlaylists,
            onToggleMood = { m, nowMember ->
                scope.launch {
                    if (nowMember) db.moodDao().add(MoodMember(m.key, item.id, System.currentTimeMillis()))
                    else db.moodDao().remove(m.key, item.id)
                }
            },
            onTogglePlaylist = { pl, nowMember ->
                scope.launch {
                    if (nowMember) db.playlistDao().addMember(PlaylistMember(pl.id, item.id, System.currentTimeMillis()))
                    else db.playlistDao().removeMember(pl.id, item.id)
                }
            },
            onEditDetails = { editItem = item; addToItem = null },
            onDismiss = { addToItem = null }
        )
    }
    } // close outer Box
}

@Composable
private fun ScanningState() {
    Column(
        Modifier.fillMaxWidth().padding(Space.xl, 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MediaColors.Accent
        )
        Spacer(Modifier.height(Space.lg))
        Text("Reading your library\u2026",
            style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamDim)
    }
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
private fun BottomBar(
    modifier: Modifier = Modifier,
    current: Int,
    onSelect: (Int) -> Unit
) {
    // Glass bar floating over the gradient: translucent fill + hairline top edge.
    Column(
        modifier.fillMaxWidth()
            .background(Color(0xFF120D1B))
            .border(width = 0.5.dp, color = Color(0x14FFFFFF))
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(BottomBarHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab(Icons.Filled.Home, Icons.Outlined.Home, "Home", current == 0, Modifier.weight(1f)) { onSelect(0) }
            NavTab(Icons.AutoMirrored.Filled.LibraryBooks, Icons.AutoMirrored.Outlined.LibraryBooks,
                "Library", current == 1, Modifier.weight(1f)) { onSelect(1) }
            NavTab(Icons.Filled.QueueMusic, Icons.Outlined.QueueMusic, "Playlists", current == 2, Modifier.weight(1f)) { onSelect(2) }
            NavTab(Icons.Filled.Search, Icons.Outlined.Search, "Search", current == 3, Modifier.weight(1f)) { onSelect(3) }
            NavTab(Icons.Filled.Menu, Icons.Outlined.Menu, "More", current == 4, Modifier.weight(1f)) { onSelect(4) }
        }
    }
}

@Composable
private fun NavTab(
    iconActive: androidx.compose.ui.graphics.vector.ImageVector,
    iconIdle: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Interaction-driven animation: on select, icon pops (scale spring), tint
    // fades to teal, and a glass pill grows behind it. No idle motion.
    val accent = MediaColors.Accent
    val tint by animateColorAsState(
        if (active) accent else MediaColors.CreamFaint,
        animationSpec = tween(220), label = "navTint"
    )
    val scale by animateFloatAsState(
        if (active) 1.18f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.42f, stiffness = 620f
        ), label = "navScale"
    )
    val pill by animateFloatAsState(
        if (active) 1f else 0f, animationSpec = tween(220), label = "navPill"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            // Glass pill behind the active icon.
            Box(
                Modifier.size(width = 46.dp, height = 30.dp)
                    .graphicsLayer { alpha = pill; scaleX = 0.6f + 0.4f * pill }
                    .clip(RoundedCornerShape(50))
                    .background(accent.copy(alpha = 0.16f))
            )
            Icon(
                if (active) iconActive else iconIdle, label, tint = tint,
                modifier = Modifier.size(23.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            )
        }
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
                    // Full-bleed hero: ask for a larger decode than the 384px
                    // list default so it isn't soft on high-density screens.
                    CoverArt(artItem, Modifier.fillMaxWidth().aspectRatio(1f).padding(Space.xl),
                        corner = 18, targetPx = 768)
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
