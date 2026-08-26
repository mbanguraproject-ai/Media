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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.res.stringResource
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

/** True while the activity is running in a picture-in-picture window. */
val LocalInPip = androidx.compose.runtime.staticCompositionLocalOf { false }

class MainActivity : ComponentActivity() {

    private val inPip = mutableStateOf(false)

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

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
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalMoodSetter provides { mood = it },
                    LocalInPip provides inPip.value
                ) {
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
            Text(stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayLarge, color = MediaColors.Cream)
            Spacer(Modifier.height(Space.md))
            Text("Your library, lit by what's playing.",
                style = MaterialTheme.typography.titleLarge, color = MediaColors.CreamDim)
            Spacer(Modifier.height(Space.xl))
            WelcomeLine("Music, podcasts, audiobooks and video — together.")
            Spacer(Modifier.height(Space.md))
            WelcomeLine("Artwork moves with the beat. Moods retint the whole app.")
            Spacer(Modifier.height(Space.md))
            WelcomeLine("Everything plays locally. No accounts, nothing to sign in to.")
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
                stringResource(R.string.app_name) +
                " plays the songs, podcasts, audiobooks and videos already on your phone. " +
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
// 64dp: 58 left the icon+label pair touching both edges of the bar.
private val BottomBarHeight = 64.dp
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
    var openAlbum by remember { mutableStateOf<Album?>(null) }
    var openArtist by remember { mutableStateOf<Artist?>(null) }
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

    // Re-instated: this fed only dead code before, now it drives §8's
    // "Continue listening".
    val positions by remember {
        db.positionDao().observeAll().map { list -> list.associateBy { it.mediaId } }
    }.collectAsState(initial = emptyMap())
    val allHistory by remember { db.historyDao().observeAllHistory() }
        .collectAsState(initial = emptyList())
    val lastPlayedMap = remember(allHistory) { allHistory.associate { it.mediaId to it.lastPlayed } }
    val playCountMap = remember(allHistory) { allHistory.associate { it.mediaId to it.playCount } }

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

    // ---- beat pulse: ONE driver, shared by the player and Home ----
    // Runs whenever something is playing, not just when the player is open,
    // because the playing card on Home consumes the same level.
    val allById = remember(allAudio, video) { (allAudio + video).associateBy { it.id } }
    // Consent first, SDK second. Kicked off once, after the permission gate,
    // so it never lands on top of onboarding.
    // Entitlement: cached value seeds the flow so no ad can flash before Play
    // answers; Billing then confirms, restores or revokes it.
    val cachedAdFree by SettingsStore.adFreeFlow(context).collectAsState(initial = true)
    val adFree by Billing.adFree.collectAsState()
    LaunchedEffect(cachedAdFree) { Billing.seed(cachedAdFree) }
    LaunchedEffect(Unit) {
        Billing.start(context) { owned ->
            scope.launch { SettingsStore.setAdFree(context, owned) }
        }
    }

    var adsReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        (context as? android.app.Activity)?.let { act ->
            Ads.startConsentThenInit(act) { adsReady = true }
        }
    }
    val nativeAd = rememberNativeAd(enabled = adsReady && !adFree)

    val playingItem = rememberArtItem(state)
    val envelope = rememberEnvelope(playingItem)
    // Volume is the power control: silent means completely still, and turning
    // it up brings both the movement and the hit count with it.
    val musicVolume = rememberMusicVolume()
    val beat = rememberBeatPulse(state, envelope, active = state.hasItem, volume = musicVolume)

    // Hoisted to scaffold scope: these were being called INSIDE tap handlers,
    // so every "View album" / "View artist" regrouped the entire library on
    // the main thread. Computed once per library instead (§38).
    val albumsAll = remember(allAudio) { MediaRepository.albumsOf(allAudio) }
    val artistsAll = remember(allAudio) { MediaRepository.artistsOf(allAudio) }

    // ---- picture-in-picture ----
    KeepScreenOnWhileVideo(state.isVideo, state.isPlaying)
    val inPip = LocalInPip.current
    val pipActivity = context as? android.app.Activity
    LaunchedEffect(state.videoWidth, state.videoHeight, state.isVideo) {
        if (state.isVideo && pipActivity != null) {
            Pip.update(pipActivity, state.videoWidth, state.videoHeight)
        }
    }
    if (inPip) {
        // PiP shrinks the ENTIRE activity, so everything except the video has
        // to go - otherwise the corner window is a postage stamp of Home.
        PipVideoOnly(vm)
        return
    }

    // §42: the very first successful scan is a reveal, not a dump into a list.
    // Placed at the top of the scaffold so it fully replaces the UI for one
    // run; every launch after this takes the skeleton path instead.
    val revealSeen by SettingsStore.revealSeenFlow(context).collectAsState(initial = true)
    if (!revealSeen && !scanning && music.isNotEmpty()) {
        val albumsNow = remember(music) { MediaRepository.albumsOf(music).size }
        val artistsNow = remember(music) { MediaRepository.artistsOf(music).size }
        FirstScanReveal(
            trackCount = music.size,
            albumCount = albumsNow,
            artistCount = artistsNow,
            onDone = { scope.launch { SettingsStore.setRevealSeen(context) } }
        )
        return
    }

    // Android back: ONE handler with an explicit priority order, topmost first.
    // This was a chain of nine BackHandlers whose enabled-guards had to be kept
    // mutually exclusive by hand — and Terms/About had no handler at all, so
    // back exited the app instead of closing them. Returning from a tab screen
    // also resets currentTab so the nav highlight doesn't lie.
    val anyOverlay = addToItem != null || editItem != null || showTerms || showAbout ||
        showPlayer || showSearch || showSettings || openPlaylist != null ||
        showPlaylists || openAlbum != null || openArtist != null || showLibrary
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
            openAlbum != null -> openAlbum = null
            openArtist != null -> openArtist = null
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
        // Home now has a sort, driven by the three cards below the mood chips.
        var homeSort by remember { mutableStateOf(SortKey.NAME) }
        val shown = remember(music, mood, moodMembers, homeSort, lastPlayedMap, playCountMap) {
            val base = if (mood.holdsSongs) music.filter { moodMembers.contains(it.id) } else music
            base.sortedFor(homeSort, lastPlayedMap, playCountMap)
        }

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // FIXED top region — does not scroll. Songs scroll underneath it.
            // §16: header state derives from scroll position. derivedStateOf
            // means only the header recomposes as you scroll, not the tree.
            val homeListState = rememberLazyListState()
            val collapse by remember {
                derivedStateOf {
                    if (homeListState.firstVisibleItemIndex > 0) 1f
                    else (homeListState.firstVisibleItemScrollOffset / 220f).coerceIn(0f, 1f)
                }
            }

            StashHeader(
                onSearch = { showSearch = true },
                onRescan = { MediaRepository.refresh(); reloadKey++ },
                collapse = collapse
            )
            // Mood chips stay put: they are the control surface, and §16 wants
            // sticky elements where useful.
            MoodChips(active = mood, onPick = { setMood(it) })
            MoodBanner(mood, collapse = collapse)
            // §8: sections are derived from the library, not declared. Empty
            // ones simply don't exist, so Home fills in as history accumulates.
            val sections = remember(music, favorites, lastPlayedMap, playCountMap, positions) {
                buildHomeSections(music, favorites, lastPlayedMap, playCountMap, positions)
            }

            // SCROLLING content — stats, shelves and tracks all move together
            // so the shelves aren't pinned above a scrolling list.
            if (scanning) {
                LibrarySkeleton()
            } else if (shown.isEmpty()) {
                EmptyState(
                    mood = mood,
                    onRescan = { MediaRepository.refresh(); reloadKey++ },
                    onClearMood = { setMood(Mood.ALL) }
                )
            } else {
                LazyColumn(
                    state = homeListState,
                    contentPadding = PaddingValues(bottom = 170.dp + navBottom),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(sections.size) { s ->
                        // ONE ad, after the first shelf. Placed inside the
                        // feed so it scrolls out of the way; nothing on Now
                        // Playing, no banner stacked under the mini-player.
                        if (s == 2 && nativeAd != null) {
                            NativeAdCard(
                                ad = nativeAd,
                                modifier = Modifier.padding(
                                    start = Space.xl, end = Space.xl, top = Space.lg
                                )
                            )
                        }
                        when (val sec = sections[s]) {
                            is HomeSection.Tracks -> {
                                SectionHeader(sec.title)
                                TrackShelf(
                                    items = sec.items,
                                    currentUri = state.currentUri,
                                    beat = beat,
                                    onPlay = { i -> vm.playOrToggle(sec.items, i) },
                                    onLongPress = { addToItem = it }
                                )
                            }
                            is HomeSection.Albums -> {
                                SectionHeader(sec.title)
                                AlbumShelf(sec.items) { openAlbum = it }
                            }
                        }
                    }
                    item {
                        // Fallback slot: with fewer than three sections index 2
                        // never occurs, so the ad would simply never render.
                        if (sections.size < 3 && nativeAd != null) {
                            NativeAdCard(
                                ad = nativeAd,
                                modifier = Modifier.padding(
                                    start = Space.xl, end = Space.xl, top = Space.lg
                                )
                            )
                        }
                        if (sections.isNotEmpty()) SectionHeader("All tracks")
                        SortSegments(selected = homeSort, onSelect = { homeSort = it })
                        CountAndShuffle(count = shown.size, onShuffle = {
                            if (shown.isNotEmpty()) {
                                if (!state.shuffle) vm.toggleShuffle()
                                vm.play(shown, (shown.indices).random())
                            }
                        })
                    }
                    items(shown.size) { idx ->
                        val track = shown[idx]
                        TrackRow(
                            item = track,
                            isPlaying = state.currentUri == track.uri.toString() && state.isPlaying,
                            isFavorite = favorites.contains(track.id),
                            onClick = { vm.playOrToggle(shown, idx) },
                            onLongPress = { addToItem = track },
                            onMenu = { addToItem = track },
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
            onOpenAlbum = { openAlbum = it; showSearch = false },
            onOpenArtist = { openArtist = it; showSearch = false },
            onBrowseLibrary = { showSearch = false; showLibrary = true; currentTab = 1 },
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
            lastPlayed = lastPlayedMap,
            playCounts = playCountMap,
            onPlay = { list, idx ->
                vm.playOrToggle(list, idx)
                if (list[idx].type == MediaType.VIDEO) showPlayer = true
            },
            onOpenAlbum = { openAlbum = it },
            onOpenArtist = { openArtist = it },
            onEdit = { editItem = it },
            onClose = { showLibrary = false; libraryPillar = null; currentTab = 0 }
        )
    }
    openAlbum?.let { album ->
        AlbumDetailScreen(
            album = album,
            state = state,
            onPlay = { idx -> vm.playOrToggle(album.tracks, idx) },
            onShuffle = {
                if (album.tracks.isNotEmpty()) {
                    if (!state.shuffle) vm.toggleShuffle()
                    vm.play(album.tracks, album.tracks.indices.random())
                }
            },
            onLongPress = { addToItem = it },
            // §26 "View artist" — jump straight across from the album header.
            onOpenArtist = {
                artistsAll.firstOrNull { a ->
                    a.id == album.tracks.firstOrNull()?.artistId
                }?.let { openArtist = it; openAlbum = null }
            },
            onClose = { openAlbum = null }
        )
    }
    openArtist?.let { artist ->
        val artistAlbums = remember(artist) { MediaRepository.albumsOf(artist.tracks) }
        ArtistDetailScreen(
            artist = artist,
            albums = artistAlbums,
            state = state,
            onPlay = { idx -> vm.playOrToggle(artist.tracks, idx) },
            onShuffle = {
                if (artist.tracks.isNotEmpty()) {
                    if (!state.shuffle) vm.toggleShuffle()
                    vm.play(artist.tracks, artist.tracks.indices.random())
                }
            },
            onOpenAlbum = { openAlbum = it; openArtist = null },
            onLongPress = { addToItem = it },
            onClose = { openArtist = null }
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
    BottomBar(Modifier.align(Alignment.BottomCenter), current = currentTab) { tab ->
        // Every tab first clears ALL overlays (mutually exclusive), then opens its own.
        showPlaylists = false; showSearch = false; showSettings = false
        showLibrary = false; libraryPillar = null; openPlaylist = null
        openAlbum = null; openArtist = null
        currentTab = tab
        when (tab) {
            1 -> showLibrary = true
            2 -> showPlaylists = true
            3 -> showSettings = true
            // 0 Home -> the home surface itself, no overlay.
            // Search is no longer a tab — it lives in the header.
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
    // §22: sits above the player pill, below everything else. Non-blocking —
    // the queue, scroll position and current screen are all preserved.
    val playbackError by vm.error.collectAsState()
    ErrorBanner(
        error = playbackError,
        modifier = Modifier.align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = BottomBarHeight + MiniPlayerGap + 68.dp),
        onRetry = { vm.retryPlayback() },
        onSkip = { vm.skipFailedItem() },
        onRescan = { MediaRepository.refresh(); reloadKey++; vm.clearError() },
        onDismiss = { vm.clearError() }
    )

    // §11/§15: ONE surface. Sits above the bottom bar so the expanded state
    // is never painted over, and collapses to a pill docked above it.
    if (state.hasItem && !playerHidden) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        PlayerSurface(
            state = state,
            vm = vm,
            expanded = showPlayer,
            artItem = playingItem,
            // Media3's timeline only carries title/artist/uri, so map back to
            // the library item to get real cover art in the queue.
            artForQueue = { e -> allById[e.mediaId] },
            beat = beat,
            bottomInset = navBottom + BottomBarHeight + MiniPlayerGap,
            onExpandedChange = { showPlayer = it }
        )
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
            onPlayNext = { vm.playNext(item) },
            onAddToQueue = { vm.addToQueue(item) },
            // Null when the track has no real album/artist metadata — the row
            // is then absent rather than present and dead.
            onViewAlbum = if (item.album != UNKNOWN_ALBUM) ({
                albumsAll.firstOrNull { it.id == item.albumId }
                    ?.let { openAlbum = it; openArtist = null }
            }) else null,
            onViewArtist = if (item.artist != UNKNOWN_ARTIST) ({
                artistsAll.firstOrNull { it.id == item.artistId }
                    ?.let { openArtist = it; openAlbum = null }
            }) else null,
            onDismiss = { addToItem = null }
        )
    }
    } // close outer Box
}

@Composable
private fun EmptyState(mood: Mood, onRescan: () -> Unit, onClearMood: () -> Unit) {
    // §20: every empty state is designed, and each one names its OWN cause.
    // An empty Favourites mood is a different situation from an empty library
    // and deserves different words and a different way out.
    val filtered = mood != Mood.ALL
    Column(
        Modifier.fillMaxWidth().padding(Space.xl, 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (filtered) "Nothing in ${mood.label} yet" else "Your library is waiting",
            style = Typo.Section, color = MediaColors.Cream
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            when {
                mood == Mood.FAVORITES -> "Save songs you love and they'll appear here."
                filtered -> "Add tracks to this mood from any track's menu."
                else -> "Add music to your device, then rescan to build your library."
            },
            style = Typo.Secondary, color = MediaColors.CreamDim
        )
        Spacer(Modifier.height(Space.xl))
        Box(
            Modifier.clip(CircleShape).background(MediaColors.Accent)
                .pressScale(haptic = true, onClick = if (filtered) onClearMood else onRescan)
                .padding(horizontal = Space.xl, vertical = Space.md)
        ) {
            Text(
                if (filtered) "Show all tracks" else "Rescan library",
                style = Typo.Label, color = Color.White
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
    Column(
        modifier.fillMaxWidth()
            .background(MediaColors.NavSurface)
            .navigationBarsPadding()
    ) {
        // Hairline on the TOP EDGE only. .border() drew a 0.5dp box on all
        // four sides, which is why the bar read as a slab with an outline
        // rather than a surface the content scrolls under.
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(MediaColors.Fill))
        Row(
            // Inset from the screen edges: the outer tabs were running flush
            // into the bezel with nothing to breathe against.
            Modifier.fillMaxWidth().height(BottomBarHeight)
                .padding(horizontal = Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab(Icons.Filled.Home, Icons.Outlined.Home, "Home", current == 0, Modifier.weight(1f)) { onSelect(0) }
            NavTab(Icons.AutoMirrored.Filled.LibraryBooks, Icons.AutoMirrored.Outlined.LibraryBooks,
                "Library", current == 1, Modifier.weight(1f)) { onSelect(1) }
            NavTab(Icons.Filled.QueueMusic, Icons.Outlined.QueueMusic, "Playlists", current == 2, Modifier.weight(1f)) { onSelect(2) }
            NavTab(Icons.Filled.Menu, Icons.Outlined.Menu, "More", current == 3, Modifier.weight(1f)) { onSelect(3) }
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
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        modifier = modifier.fillMaxHeight().padding(vertical = Space.sm)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
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

