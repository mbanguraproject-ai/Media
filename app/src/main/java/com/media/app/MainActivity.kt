package com.media.app
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
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
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
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
import kotlinx.coroutines.CoroutineScope
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

    // Play's update sheet reports back here (InAppUpdate.kt). Registered as a
    // field: Android requires it before the activity starts.
    private val updateLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { InAppUpdate.onFlowResult(this, it.resultCode) }

    // Each time the app comes to the front: a finished download shows its
    // banner, and once per app open a new version is offered.
    override fun onResume() {
        super.onResume()
        InAppUpdate.check(this, updateLauncher)
    }

    // The language picked in Settings, on Android 12 and below (Language.kt).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.keepProcessDefault(this)
    }

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
        // Transparent bars with NO system scrim. Plain enableEdgeToEdge()
        // uses the "auto" style, which on 3-button navigation turns contrast
        // enforcement back on - overriding the theme - so Android drew its
        // own dark band behind the buttons, cutting Now Playing's colour off
        // at the bottom. The dark style with a transparent scrim does not.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        Enrichment.init(this)
        setContent {
            val settings by SettingsStore.flow(this).collectAsState(initial = MediaSettings())
            // Dark-only app: bars always use light icons.
            SetStatusBarIcons(true)
            // Active mood: re-themes the whole app (accent + glow) and is settable
            // from the home screen via LocalMoodSetter. Purely visual.
            var mood by remember { mutableStateOf(Mood.default) }
            // Detected once per launch: RAM, cores, refresh rate, HDR, API
            // level. Never re-read on recomposition - none of it changes while
            // the app is open.
            val capability = remember { detectCapability(this) }
            val ceiling = remember(capability) { ceilingFor(capability) }
            val requested = remember(settings.qualityMode, ceiling) {
                resolveLevel(settings.qualityMode, ceiling)
            }
            // Sustained frame performance can step this DOWN on Auto. It
            // never touches playback, and it never overrides a manual pick -
            // that surfaces in Settings as an offer instead.
            val adaptive = rememberAdaptiveQuality(
                requested = requested,
                autoMode = settings.qualityMode == QualityMode.AUTO,
                refreshHz = capability.refreshRateHz
            )
            val quality = remember(adaptive.level, adaptive.relief) {
                profileFor(adaptive.level).relieved(adaptive.relief)
            }
            MediaTheme(
                themeMode = settings.themeMode,
                fontScale = settings.fontScale,
                mood = mood,
                quality = quality
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalMoodSetter provides { mood = it },
                    LocalAdaptiveQuality provides adaptive,
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
    // shouldShowRequestPermissionRationale is false BOTH before the first ask
    // and after a permanent denial, so it only means "blocked" once we know we
    // have asked. Without this the gate's button silently does nothing.
    var askedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
        askedOnce = true
    }

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
        introSeen == true && !granted -> {
            val act = context as? android.app.Activity
            val blocked = askedOnce && act != null && requiredPermissions().none {
                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(act, it)
            }
            PermissionGate(
                blocked = blocked,
                onGrant = { launcher.launch(requiredPermissions()) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                }
            )
        }
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
            Text(stringResource(R.string.tagline),
                style = MaterialTheme.typography.titleLarge, color = MediaColors.CreamDim)
            Spacer(Modifier.height(Space.xl))
            WelcomeLine(stringResource(R.string.welcome_line_1))
            Spacer(Modifier.height(Space.md))
            WelcomeLine(stringResource(R.string.welcome_line_2))
            Spacer(Modifier.height(Space.md))
            WelcomeLine(stringResource(R.string.welcome_line_3))
        }
        Box(
            Modifier.align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(30.dp)).background(MediaColors.Cream)
                .clickable(onClick = onContinue)
                .padding(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Text(stringResource(R.string.action_got_it), style = MaterialTheme.typography.titleMedium, color = MediaColors.Ink)
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
            Text(stringResource(R.string.perm_explainer_title), style = MaterialTheme.typography.displaySmall, color = MediaColors.Cream)
            Spacer(Modifier.height(Space.lg))
            Text(
                stringResource(R.string.perm_explainer_body),
                style = MaterialTheme.typography.bodyLarge, color = MediaColors.CreamDim
            )
            Spacer(Modifier.height(Space.md))
            Text(
                stringResource(R.string.perm_explainer_privacy),
                style = MaterialTheme.typography.bodyLarge, color = MediaColors.CreamDim
            )
        }
        Box(
            Modifier.align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(30.dp)).background(MediaColors.Cream)
                .clickable(onClick = onContinue)
                .padding(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Text(stringResource(R.string.action_understood), style = MaterialTheme.typography.titleMedium, color = MediaColors.Ink)
        }
    }
}

@Composable
private fun PermissionGate(
    blocked: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    // This used to offer one button that called launch() unconditionally.
    // After two denials Android returns instantly without showing a dialog, so
    // the button did nothing at all and the user was stuck on this screen with
    // no explanation and no way forward. When that happens we say so, and send
    // them to the one place that can actually fix it.
    Box(
        Modifier.fillMaxSize().background(moodBackground()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Space.xxl)
        ) {
            Icon(
                Icons.Outlined.LibraryMusic, null,
                tint = MediaColors.Accent, modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(Space.xl))
            Text(
                stringResource(if (blocked) R.string.perm_blocked_title else R.string.perm_needed_title),
                style = Typo.Section, color = MediaColors.Cream,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                stringResource(if (blocked) R.string.perm_blocked_body else R.string.perm_needed_body),
                style = Typo.Secondary, color = MediaColors.CreamDim,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(Space.xxl))
            Box(
                Modifier.clip(CircleShape).background(MediaColors.Accent)
                    .pressScale(haptic = true, onClick = if (blocked) onOpenSettings else onGrant)
                    .padding(horizontal = Space.xxl, vertical = Space.md)
            ) {
                Text(
                    stringResource(if (blocked) R.string.action_open_settings else R.string.action_allow_access),
                    style = Typo.Label, color = Color.White
                )
            }
        }
    }
}

// Height of the bottom nav row (excludes the system nav-bar inset, which is
// applied separately via navigationBarsPadding). The mini-player floats a
// small gap above this — both derive from BottomBarHeight so they never drift.
// 64dp: 58 left the icon+label pair touching both edges of the bar.
private val BottomBarHeight = 64.dp
private val MiniPlayerGap = 12.dp

/**
 * Every screen, sheet and selection the home scaffold can show, in one
 * holder.
 *
 * WHY THIS EXISTS. HomeScaffold was one composable of ~900 lines holding all
 * of this as separate remembered states, with every screen, sheet and the
 * player inlined into it. Compiled, that was a single method needing more
 * than 256 registers, and past that limit the compiler moved an object
 * reference with a plain `move`. Android's verifier rejects that outright -
 * "copy-cat1 v0<-v258 type=Reference: AppMediaItem" - so 4.5 crashed on
 * launch. The build that crashed was unminified (its trace keeps real names
 * and line numbers), and unminified DEX keeps every local alive for the
 * debugger, so it needs far more registers than R8's release output of the
 * same code. The state now lives here and each part of the screen is its
 * own composable, so no method comes near the limit in either build.
 * Behaviour is unchanged: these are the same mutableStateOf values, just
 * held together.
 */
@Stable
class HomeNav {
    var showPlayer by mutableStateOf(false)
    // Via Share. Held at this level because the header opens it, the back
    // handler closes it, and the video feed has to know it is open - a sheet
    // over Home counts as an overlay, so the surface goes back to the pill.
    var showShare by mutableStateOf(false)
    // How many feed cards currently hold the one video surface (see
    // HomeScaffold).
    var feedSurfaceOwners by mutableStateOf(0)
    var showSearch by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showLibrary by mutableStateOf(false)
    var openAlbum by mutableStateOf<Album?>(null)
    var openArtist by mutableStateOf<Artist?>(null)
    var libraryPillar by mutableStateOf<Pillar?>(null)
    var currentTab by mutableStateOf(0)
    var showPlaylists by mutableStateOf(false)
    var openPlaylist by mutableStateOf<Playlist?>(null)
    var videoFullscreen by mutableStateOf(false)
    var addToItem by mutableStateOf<AppMediaItem?>(null)
    var showTerms by mutableStateOf(false)
    var showAbout by mutableStateOf(false)
    // Bumping it (rescan) re-runs the MediaStore scan.
    var reloadKey by mutableStateOf(0)
    var editItem by mutableStateOf<AppMediaItem?>(null)
    // Enrichment overlays: the artwork fixer, track details, the audio path
    // and the Sound screen.
    var detailsItem by mutableStateOf<AppMediaItem?>(null)
    var artworkItem by mutableStateOf<AppMediaItem?>(null)
    var showAudioPath by mutableStateOf(false)
    var showSound by mutableStateOf(false)
    // The Via Pro page. Topmost of all: a locked control anywhere opens it.
    var showPro by mutableStateOf(false)
    var homePillar by mutableStateOf(Pillar.MUSIC)
    var adsReady by mutableStateOf(false)
    var playerHidden by mutableStateOf(false)
    var playerWasOpen by mutableStateOf(false)

    val anyOverlay: Boolean
        get() = showPro || showSound || artworkItem != null || detailsItem != null || showAudioPath ||
            addToItem != null || editItem != null || showTerms || showAbout ||
            showPlayer || showSearch || showSettings || openPlaylist != null ||
            showPlaylists || openAlbum != null || openArtist != null || showLibrary ||
            showShare

    // Android back: ONE handler with an explicit priority order, topmost first.
    // This was a chain of nine BackHandlers whose enabled-guards had to be kept
    // mutually exclusive by hand — and Terms/About had no handler at all, so
    // back exited the app instead of closing them. Returning from a tab screen
    // also resets currentTab so the nav highlight doesn't lie.
    fun back() {
        when {
            showPro -> showPro = false
            showSound -> showSound = false
            artworkItem != null -> artworkItem = null
            detailsItem != null -> detailsItem = null
            showAudioPath -> showAudioPath = false
            showShare -> showShare = false
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
}

/**
 * The library and the views derived from it, as the scaffold's parts read
 * them. Built in HomeScaffold from the same flows as before.
 */
class HomeLibrary(
    val allAudio: List<AppMediaItem>,
    val allVideo: List<AppMediaItem>,
    val videoCount: Int,
    val music: List<AppMediaItem>,
    val allById: Map<Long, AppMediaItem>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val favorites: Set<Long>,
    val playlists: List<Playlist>,
    val playlistMembers: List<PlaylistMember>,
    val moodMembers: List<MoodMember>,
    val moodCounts: Map<String, Int>,
    val overrides: Map<Long, MediaOverride>,
    val lastPlayed: Map<Long, Long>,
    val playCounts: Map<Long, Int>
)

@UnstableApi
@Composable
fun HomeScaffold(vm: PlayerViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val nav = remember { HomeNav() }
    val ambient = remember { AppAmbient() }
    val shareState by ShareSession.state.collectAsState()
    // Once per context. remember must hold a value, so it holds the context.
    remember(context) { ShareSession.attach(context); context }
    // THE ONE VIDEO SURFACE.
    //
    // The mini-player and Now Playing are a single composable that is always
    // mounted, and for a video item it attaches its own PlayerView to the one
    // shared MediaController. A second PlayerView on the same player wins the
    // surface outright, which is why a card in the feed froze on its last
    // frame and then went black while the audio carried on.
    //
    // So exactly one of them is allowed to exist: while a feed card holds the
    // surface the mini-player is not composed at all, and the moment the card
    // lets go - scrolled away, an overlay opened, the full player opened - it
    // comes back and takes over.
    val feedVideoLive = nav.feedSurfaceOwners > 0

    val db = remember { OverrideDatabase.get(context) }
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.flow(context).collectAsState(initial = MediaSettings())

    // Record a play (after 5s) into history; upsert = auto-dedup + move to front by timestamp.
    LaunchedEffect(Unit) {
        vm.onQualifyingPlay = { mediaId ->
            scope.launch {
                db.historyDao().record(mediaId, System.currentTimeMillis())
            }
            InAppReview.recordPlay(context)
        }
    }
    // The review card, if it is due, at a natural pause: Now Playing just
    // closed. Never while something is being looked at or adjusted.
    LaunchedEffect(nav.showPlayer) {
        if (nav.showPlayer) { nav.playerWasOpen = true; return@LaunchedEffect }
        if (nav.playerWasOpen) {
            nav.playerWasOpen = false
            kotlinx.coroutines.delay(600)   // let the collapse finish first
            (context as? android.app.Activity)?.let { InAppReview.maybeAsk(it) }
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
    LaunchedEffect(nav.reloadKey) {
        scanning = true
        rawAudio = MediaRepository.loadAudio(context)
        video = MediaRepository.loadVideo(context)
        scanning = false
    }

    // Override application is pure and cheap, so an edit re-maps in place
    // without re-querying MediaStore.
    val allAudio = remember(rawAudio, overrides) { MediaRepository.applyOverrides(rawAudio, overrides) }
    // Video edits went into the database and never came back out: overrides
    // were applied to the audio list only, so renaming a video wrote a row and
    // changed nothing on screen. Silent, which is the worst kind.
    val allVideo = remember(video, overrides) { MediaRepository.applyOverrides(video, overrides) }
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

    val allById = remember(allAudio, allVideo) { (allAudio + allVideo).associateBy { it.id } }
    // Consent first, SDK second. Kicked off once, after the permission gate,
    // so it never lands on top of onboarding.
    // Entitlement: cached value seeds the flow so no ad can flash before Play
    // answers; Billing then confirms, restores or revokes it.
    val cachedAdFree by SettingsStore.adFreeFlow(context).collectAsState(initial = true)
    LaunchedEffect(cachedAdFree) { Billing.seed(cachedAdFree) }
    LaunchedEffect(Unit) {
        Billing.start(context) { owned ->
            scope.launch { SettingsStore.setAdFree(context, owned) }
        }
    }

    LaunchedEffect(Unit) {
        (context as? android.app.Activity)?.let { act ->
            Ads.startConsentThenInit(act) { nav.adsReady = true }
        }
    }

    // ---- beat pulse: ONE driver, shared by the player and Home ----
    // Runs whenever something is playing, not just when the player is open,
    // because the playing card on Home consumes the same level.
    val playingItem = rememberArtItem(state)
    // Video never pulses: the artwork box holds a PlayerView, so scaling and
    // blooming it distorts the picture. Passing null also skips the decode
    // entirely rather than analysing a video's audio track for nothing.
    // The pulse is a MUSIC feature. Video has a picture that must not be
    // scaled, and spoken word has no beat - an audiobook throbbing on the
    // reader's syllables looks broken rather than alive.
    val reactiveArtOn by SettingsStore.reactiveArtFlow(context).collectAsState(initial = false)
    // Analysis and the artwork pulse are separate questions now. The waveform
    // scrubber needs the envelope for ANY music track; the Reactive artwork
    // setting only governs whether the COVER moves. Still music-only: video
    // has a picture, and spoken word has no waveform worth drawing.
    val analysable = playingItem?.pillar == Pillar.MUSIC && !state.isVideo
    val envelope = rememberEnvelope(if (analysable) playingItem else null)
    val reactive = reactiveArtOn && analysable
    // Volume is the power control: silent means completely still, and turning
    // it up brings both the movement and the hit count with it.
    val musicVolume = rememberMusicVolume()
    val beat = rememberBeatPulse(
        state, envelope,
        active = state.hasItem && reactive,
        volume = musicVolume
    )

    // Hoisted to scaffold scope: these were being called INSIDE tap handlers,
    // so every "View album" / "View artist" regrouped the entire library on
    // the main thread. Computed once per library instead (§38).
    val albumsAll = remember(allAudio) { MediaRepository.albumsOf(allAudio) }
    val artistsAll = remember(allAudio) { MediaRepository.artistsOf(allAudio) }

    // ---- enrichment ----
    // Remembered: HomeScaffold recomposes twice a second with the position,
    // and a fresh flow each time would restart the collection each time.
    val onlineLookups by remember(context) { SettingsStore.onlineFlow(context) }.collectAsState(initial = false)
    // The library row for what is playing: the session only carries title,
    // artist and uri, and lyrics and artwork need the album too.
    val libraryPlaying = playingItem?.let { allById[it.id] }
    // A cover fix applies to the whole album when there is a real one.
    val artScope: (AppMediaItem) -> List<AppMediaItem> = { item ->
        if (ArtworkStore.groupKey(item).startsWith("album-"))
            albumsAll.firstOrNull { it.id == item.albumId }?.tracks ?: listOf(item)
        else listOf(item)
    }
    // Bad covers repair themselves as they play, when lookups are on.
    LaunchedEffect(libraryPlaying?.id, onlineLookups) {
        val item = libraryPlaying ?: return@LaunchedEffect
        if (onlineLookups) ArtworkRepair.fixIfNeeded(context, item, artScope(item).map { it.id })
    }
    // ---- the room's light (AppBackdrop.kt) ----
    // While a song plays, every screen sits on its cover the way Now Playing
    // does - the same cover Now Playing uses, the library row first. The room
    // goes back to the plain floor when nothing is playing: stopped, or
    // paused until the mini-player hides. Video keeps the plain floor.
    AppAmbientDriver(
        ambient,
        item = if (state.isVideo) null else (libraryPlaying ?: playingItem),
        active = state.hasItem && !state.isVideo && !nav.playerHidden
    )

    // And a repaired cover reaches the notification straight away.
    val artVersion by ArtworkStore.version.collectAsState()
    LaunchedEffect(artVersion, playingItem?.id) { playingItem?.let { vm.refreshArtwork(it.id) } }

    // ---- picture-in-picture ----
    KeepScreenOnWhileVideo(state.isVideo, state.isPlaying)
    val inPip = LocalInPip.current
    val pipActivity = context as? android.app.Activity
    LaunchedEffect(state.isVideo) { if (!state.isVideo) nav.videoFullscreen = false }
    // Runs for audio TOO, so auto-enter is switched back off when video ends.
    LaunchedEffect(state.videoWidth, state.videoHeight, state.isVideo) {
        pipActivity?.let {
            Pip.update(it, state.videoWidth, state.videoHeight, autoEnter = state.isVideo)
        }
    }
    // Landscape fullscreen owns the screen outright: no scaffold, no chrome.
    if (nav.videoFullscreen && state.isVideo && !inPip) {
        FullscreenVideo(state = state, vm = vm, onExit = { nav.videoFullscreen = false })
        return
    }
    if (inPip) {
        // PiP shrinks the ENTIRE activity, so everything except the video has
        // to go - otherwise the corner window is a postage stamp of Home.
        PipVideoOnly(vm, state, playingItem)
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
    // homePillar and playerHidden used to be declared here, after the early
    // returns, so fullscreen video, PiP and the first-scan reveal discarded
    // them and Home came back on Music with the mini-player showing. They
    // live in HomeNav now; this keeps that lifetime exactly.
    DisposableEffect(nav) {
        onDispose { nav.homePillar = Pillar.MUSIC; nav.playerHidden = false }
    }

    // The pillars, at the top level instead of two screens down.
    val byPillar = remember(allAudio, allVideo) { (allAudio + allVideo).groupBy { it.pillar } }
    val pillars = remember(byPillar) {
        listOf(Pillar.MUSIC, Pillar.VIDEO, Pillar.PODCAST,
               Pillar.AUDIOBOOK, Pillar.RECORDING)
            .filter { !byPillar[it].isNullOrEmpty() }
    }
    // A rescan can empty the pillar you were standing on.
    LaunchedEffect(pillars) {
        if (pillars.isNotEmpty() && nav.homePillar !in pillars) nav.homePillar = pillars.first()
    }

    val anyOverlay = nav.anyOverlay

    // WHO MAY HOLD THE VIDEO SURFACE - decided here, in composition, before
    // either PlayerView is built.
    //
    // Deciding it from an effect was the bug. The card and the mini-player
    // both attach in the same frame, Home composes first and the mini-player
    // second, so the mini-player took the surface; the effect that hid it then
    // ran a frame later, and tearing its PlayerView down set the player's
    // video output back to null. Result: audio playing, card black, and the
    // card never re-attaching because its `player` reference had not changed.
    // Going fullscreen and back only worked because it rebuilt the view.
    //
    // This is plain derived state, so it is already true on the frame the
    // video becomes current and the mini-player simply never attaches.
    val feedOwnsVideo = state.isVideo && nav.homePillar == Pillar.VIDEO && !anyOverlay
    BackHandler(enabled = anyOverlay) { nav.back() }

    val lib = HomeLibrary(
        allAudio = allAudio, allVideo = allVideo, videoCount = video.size, music = music,
        allById = allById, albums = albumsAll, artists = artistsAll, favorites = favorites,
        playlists = playlists, playlistMembers = allPlaylistMembers, moodMembers = allMoodMembers,
        moodCounts = moodCounts, overrides = overrides, lastPlayed = lastPlayedMap, playCounts = playCountMap
    )

    // Outer container: home + all overlays render inside; the mini-player and
    // bottom nav sit at the end so they PERSIST above every screen. The parts
    // are drawn in exactly the order they were when they were all inline.
    // Every screen in here draws the room's light behind it.
    val openPro = remember(nav) { { nav.showPro = true } }
    CompositionLocalProvider(LocalAppAmbient provides ambient, LocalOpenPro provides openPro) {
        Box(Modifier.fillMaxSize()) {
            HomeFeed(
                nav = nav, lib = lib, state = state, vm = vm, db = db, scope = scope,
                mood = mood, setMood = setMood, moodMembers = moodMembers, byPillar = byPillar,
                pillars = pillars, positions = positions, scanning = scanning,
                sharing = shareState.sharing, feedOwnsVideo = feedOwnsVideo
            )
            HomeScreens(
                nav = nav, lib = lib, state = state, vm = vm, db = db, scope = scope,
                settings = settings, setMood = setMood
            )
            HomeDetails(nav = nav, lib = lib, state = state, vm = vm, db = db, scope = scope)
            HomeChrome(nav = nav, state = state, vm = vm)
            HomePlayer(
                nav = nav, lib = lib, state = state, vm = vm, db = db, scope = scope,
                playingItem = playingItem, libraryPlaying = libraryPlaying, beat = beat,
                envelope = envelope, feedOwnsVideo = feedOwnsVideo, feedVideoLive = feedVideoLive,
                onlineLookups = onlineLookups, reactiveArtOn = reactiveArtOn, sharing = shareState.sharing
            )
            HomeSheets(
                nav = nav, lib = lib, state = state, vm = vm, db = db, scope = scope,
                onlineLookups = onlineLookups, artScope = artScope,
                playingItem = playingItem, libraryPlaying = libraryPlaying
            )
        }
    }
}

// ---------------------------------------------------------------------------
//  The scaffold's parts. Each was a stretch of HomeScaffold, moved here as is.
// ---------------------------------------------------------------------------

@Composable
private fun HomeFeed(
    nav: HomeNav,
    lib: HomeLibrary,
    state: PlayerState,
    vm: PlayerViewModel,
    db: OverrideDatabase,
    scope: CoroutineScope,
    mood: Mood,
    setMood: (Mood) -> Unit,
    moodMembers: Set<Long>,
    byPillar: Map<Pillar, List<AppMediaItem>>,
    pillars: List<Pillar>,
    positions: Map<Long, PlaybackPosition>,
    scanning: Boolean,
    sharing: Boolean,
    feedOwnsVideo: Boolean
) {
    Box(Modifier.fillMaxSize().screenBackground()) {
        // Bottom inset = real nav-bar inset + chrome offset (bottom bar + mini-
        // player), so the last shelf always clears the chrome on any device
        // (gesture or 3-button). No magic number.
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        // Favorites mood shows only favorited tracks; every other mood shows all music.
        // Home now has a sort, driven by the three cards below the mood chips.
        var homeSort by remember { mutableStateOf(SortKey.NAME) }

        val shown = remember(
            byPillar, nav.homePillar, mood, moodMembers, homeSort, lib.lastPlayed, lib.playCounts
        ) {
            val inPillar = byPillar[nav.homePillar].orEmpty()
            // Moods are a music idea. They never filter video or spoken word.
            val base = if (nav.homePillar == Pillar.MUSIC && mood.holdsSongs)
                inPillar.filter { moodMembers.contains(it.id) } else inPillar
            base.sortedFor(homeSort, lib.lastPlayed, lib.playCounts)
        }

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // FIXED top region — does not scroll. Songs scroll underneath it.
            // The scroll-driven collapse went with the big header: there is
            // nothing left up here that is worth shrinking.
            val homeListState = rememberLazyListState()

            StashHeader(
                onSearch = { nav.showSearch = true },
                sharing = sharing,
                onShare = { nav.showShare = true },
                onPro = { nav.showPro = true }
            )

            PillarStrip(pillars, nav.homePillar) { nav.homePillar = it }

            // SHELVES GONE. "Continue listening", "Recently played", "Your
            // favourites", "Most played", "Recently added" and "Albums you keep
            // coming back to" were up to six horizontal rails stacked above the
            // library, each under its own 19sp header - so the list you opened
            // the app to reach started a screen and a half down.
            //
            // The one actionable item among them was the track you did not
            // finish. That is the bar below; everything else was a rail of
            // cards restating a list already on this screen.
            val resume = remember(lib.music, positions) {
                positions.values
                    .filter {
                        it.durationMs > 0 &&
                            it.positionMs > it.durationMs * 0.05 &&
                            it.positionMs < it.durationMs * 0.95
                    }
                    .maxByOrNull { it.updatedAt }
                    ?.let { p -> lib.music.firstOrNull { it.id == p.mediaId }?.to(p) }
            }

            // Present only while a collection opened from Playlists is actually
            // filtering the list, so the filter can never be invisible state.
            if (nav.homePillar == Pillar.MUSIC && mood.holdsSongs) {
                FilterBar(stringResource(mood.labelRes), shown.size) { setMood(Mood.ALL) }
            }

            if (scanning) {
                LibrarySkeleton()
            } else if (shown.isEmpty()) {
                EmptyState(
                    mood = mood,
                    onRescan = { MediaRepository.refresh(); nav.reloadKey++ },
                    onClearMood = { setMood(Mood.ALL) }
                )
            } else {
                LazyColumn(
                    state = homeListState,
                    contentPadding = PaddingValues(bottom = 170.dp + navBottom),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        resume?.let { (track, pos) ->
                            ResumeBar(
                                item = track,
                                progress = (pos.positionMs.toFloat() / pos.durationMs)
                                    .coerceIn(0f, 1f),
                                onPlay = { vm.playAt(track, pos.positionMs) }
                            )
                        }
                        // Sort segments parked. The selected pill was the loudest
                        // object on the screen, above the artwork it sat over.
                        // homeSort stays SortKey.NAME (A-Z); SortSegments is still
                        // defined in HomeContent.kt for when it comes back.
                        CountAndShuffle(
                            count = shown.size,
                            nounPlural = pillarCountRes(nav.homePillar),
                            onShuffle = {
                                if (shown.isNotEmpty()) {
                                    if (!state.shuffle) vm.toggleShuffle()
                                    vm.play(shown, (shown.indices).random())
                                }
                            }
                        )
                    }
                    items(shown.size) { idx ->
                        val track = shown[idx]
                        if (nav.homePillar == Pillar.VIDEO) {
                            val onIt = state.currentUri == track.uri.toString()
                            VideoCard(
                                item = track,
                                isCurrent = onIt,
                                isPlaying = onIt && state.isPlaying,
                                progress = if (onIt && state.durationMs > 0L)
                                    state.positionMs.toFloat() / state.durationMs else 0f,
                                canRenderVideo = feedOwnsVideo,
                                onSurfaceOwned = { own ->
                                    nav.feedSurfaceOwners += if (own) 1 else -1
                                },
                                vm = vm,
                                onClick = { vm.playOrToggle(shown, idx) },
                                onPlayPause = { vm.playOrToggle(shown, idx) },
                                onSeek = { f ->
                                    if (state.durationMs > 0L)
                                        vm.seekTo((f * state.durationMs).toLong())
                                },
                                onExpand = { nav.showPlayer = true },
                                onMenu = { nav.addToItem = track }
                            )
                        } else {
                            TrackRow(
                                item = track,
                                isPlaying = state.currentUri == track.uri.toString() && state.isPlaying,
                                isFavorite = lib.favorites.contains(track.id),
                                onClick = {
                                    vm.playOrToggle(shown, idx)
                                    if (track.type == MediaType.VIDEO) nav.showPlayer = true
                                },
                                onLongPress = { nav.addToItem = track },
                                onMenu = { nav.addToItem = track },
                                onToggleFav = {
                                    scope.launch {
                                        if (lib.favorites.contains(track.id)) db.moodDao().remove(Mood.FAVORITES.key, track.id)
                                        else db.moodDao().add(MoodMember(Mood.FAVORITES.key, track.id, System.currentTimeMillis()))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun HomeScreens(
    nav: HomeNav,
    lib: HomeLibrary,
    state: PlayerState,
    vm: PlayerViewModel,
    db: OverrideDatabase,
    scope: CoroutineScope,
    settings: MediaSettings,
    setMood: (Mood) -> Unit
) {
    val context = LocalContext.current
    if (nav.showSearch) {
        SearchScreen(
            all = lib.allAudio + lib.allVideo,
            onPlay = { list, idx ->
                vm.play(list, idx)
                nav.showSearch = false
                if (list[idx].type == MediaType.VIDEO) nav.showPlayer = true
            },
            onOpenAlbum = { nav.openAlbum = it; nav.showSearch = false },
            onOpenArtist = { nav.openArtist = it; nav.showSearch = false },
            onBrowseLibrary = { nav.showSearch = false; nav.showLibrary = true; nav.currentTab = 1 },
            onClose = { nav.showSearch = false }
        )
    }
    if (nav.showPlaylists) {
        PlaylistsScreen(
            playlists = lib.playlists,
            moodCounts = lib.moodCounts,
            onOpenMood = { m -> setMood(m); nav.showPlaylists = false; nav.currentTab = 0 },
            onCreatePlaylist = { name ->
                scope.launch { db.playlistDao().create(Playlist(name = name, createdAt = System.currentTimeMillis())) }
            },
            onOpenPlaylist = { pl -> nav.openPlaylist = pl },
            onDeletePlaylist = { pl ->
                if (nav.openPlaylist?.id == pl.id) nav.openPlaylist = null
                scope.launch { db.playlistDao().clearMembers(pl.id); db.playlistDao().deletePlaylist(pl.id) }
            },
        )
    }
    nav.openPlaylist?.let { pl ->
        // Members carry addedAt, so honour insertion order rather than whatever
        // order the flat observeAllMembers query happens to return.
        val byId = remember(lib.allAudio, lib.allVideo) { (lib.allAudio + lib.allVideo).associateBy { it.id } }
        val tracks = remember(lib.playlistMembers, pl.id, byId) {
            lib.playlistMembers.filter { it.playlistId == pl.id }
                .sortedBy { it.addedAt }
                .mapNotNull { byId[it.mediaId] }
        }
        PlaylistDetailScreen(
            playlist = pl,
            tracks = tracks,
            state = state,
            favorites = lib.favorites,
            onPlay = { idx -> vm.playOrToggle(tracks, idx) },
            onShuffle = {
                if (tracks.isNotEmpty()) {
                    if (!state.shuffle) vm.toggleShuffle()
                    vm.play(tracks, tracks.indices.random())
                }
            },
            onLongPress = { nav.addToItem = it },
            onToggleFav = { track ->
                scope.launch {
                    if (lib.favorites.contains(track.id)) db.moodDao().remove(Mood.FAVORITES.key, track.id)
                    else db.moodDao().add(MoodMember(Mood.FAVORITES.key, track.id, System.currentTimeMillis()))
                }
            },
            onClose = { nav.openPlaylist = null }
        )
    }
    if (nav.showSettings) {
        SettingsScreen(
            audioCount = lib.allAudio.size,
            videoCount = lib.videoCount,
            settings = settings,
            onFontScaleChange = { scale -> scope.launch { SettingsStore.setFontScale(context, scale) } },
            onRescan = {
                MediaRepository.refresh()
                nav.reloadKey++
            },
            adsReady = nav.adsReady,
            onOpenTerms = { nav.showTerms = true },
            onOpenAbout = { nav.showAbout = true },
            onClose = { nav.showSettings = false },
            onOpenSound = { nav.showSound = true },
            onRepairArtwork = { ArtworkRepair.startLibraryRepair(context, lib.music) }
        )
    }
    if (nav.showLibrary) {
        LibraryScreen(
            all = lib.allAudio + lib.allVideo,
            state = state,
            initialPillar = nav.libraryPillar,
            lastPlayed = lib.lastPlayed,
            playCounts = lib.playCounts,
            onPlay = { list, idx ->
                vm.playOrToggle(list, idx)
                if (list[idx].type == MediaType.VIDEO) nav.showPlayer = true
            },
            onOpenAlbum = { nav.openAlbum = it },
            onOpenArtist = { nav.openArtist = it },
            onEdit = { nav.editItem = it },
            onClose = { nav.showLibrary = false; nav.libraryPillar = null; nav.currentTab = 0 }
        )
    }
}

@Composable
private fun HomeDetails(
    nav: HomeNav,
    lib: HomeLibrary,
    state: PlayerState,
    vm: PlayerViewModel,
    db: OverrideDatabase,
    scope: CoroutineScope
) {
    nav.openAlbum?.let { album ->
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
            onLongPress = { nav.addToItem = it },
            // §26 "View artist" — jump straight across from the album header.
            onOpenArtist = {
                lib.artists.firstOrNull { a ->
                    a.id == album.tracks.firstOrNull()?.artistId
                }?.let { nav.openArtist = it; nav.openAlbum = null }
            },
            onClose = { nav.openAlbum = null }
        )
    }
    nav.openArtist?.let { artist ->
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
            onOpenAlbum = { nav.openAlbum = it; nav.openArtist = null },
            onLongPress = { nav.addToItem = it },
            onRenameArtist = { newName ->
                // One row per track, written as a single transaction. Existing
                // overrides are preserved: a track whose TITLE was already
                // corrected keeps that correction and only gains the artist.
                scope.launch {
                    val ids = artist.tracks.map { it.id }
                    val existing = db.dao().getFor(ids).associateBy { it.mediaId }
                    db.dao().upsertAll(
                        ids.map { id ->
                            existing[id]?.copy(customArtist = newName)
                                ?: MediaOverride(mediaId = id, customArtist = newName)
                        }
                    )
                    nav.openArtist = null
                }
            },
            onClose = { nav.openArtist = null }
        )
    }
    nav.editItem?.let { item ->
        EditSheet(
            item = item,
            hasOverride = lib.overrides.containsKey(item.id),
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
                nav.editItem = null
            },
            onReset = {
                scope.launch { db.dao().delete(item.id) }
                nav.editItem = null
            },
            onDismiss = { nav.editItem = null }
        )
    }
}

@Composable
private fun BoxScope.HomeChrome(nav: HomeNav, state: PlayerState, vm: PlayerViewModel) {
    // ---- PERSISTENT chrome: mini-player + bottom nav, above all overlays ----
    LaunchedEffect(state.isPlaying, state.currentUri) {
        if (state.isPlaying) nav.playerHidden = false
        else if (state.hasItem) { delay(10_000); nav.playerHidden = true }
    }
    BottomBar(Modifier.align(Alignment.BottomCenter), current = nav.currentTab) { tab ->
        // Every tab first clears ALL overlays (mutually exclusive), then opens its own.
        nav.showPlaylists = false; nav.showSearch = false; nav.showSettings = false
        nav.showLibrary = false; nav.libraryPillar = null; nav.openPlaylist = null
        nav.openAlbum = null; nav.openArtist = null
        nav.currentTab = tab
        when (tab) {
            1 -> nav.showLibrary = true
            2 -> nav.showPlaylists = true
            3 -> nav.showSettings = true
            // 0 Home -> the home surface itself, no overlay.
            // Search is no longer a tab — it lives in the header.
        }
    }
    // ---- Surfaces that must sit ABOVE the persistent chrome ----
    // Order matters: everything below is drawn after BottomBar, so the nav bar
    // and mini-player no longer paint over the full player and the info pages.
    if (nav.showTerms) {
        TermsScreen(onClose = { nav.showTerms = false })
    }
    if (nav.showAbout) {
        AboutScreen(version = BuildConfig.VERSION_NAME, onClose = { nav.showAbout = false })
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
        onRescan = { MediaRepository.refresh(); nav.reloadKey++; vm.clearError() },
        onDismiss = { vm.clearError() }
    )
    // A downloaded update, in the same place. A playback error goes first.
    UpdateBanner(
        visible = playbackError == null,
        modifier = Modifier.align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = BottomBarHeight + MiniPlayerGap + 68.dp)
    )
}

@UnstableApi
@Composable
private fun HomePlayer(
    nav: HomeNav,
    lib: HomeLibrary,
    state: PlayerState,
    vm: PlayerViewModel,
    db: OverrideDatabase,
    scope: CoroutineScope,
    playingItem: AppMediaItem?,
    libraryPlaying: AppMediaItem?,
    beat: BeatState,
    envelope: FloatArray?,
    feedOwnsVideo: Boolean,
    feedVideoLive: Boolean,
    onlineLookups: Boolean,
    reactiveArtOn: Boolean,
    sharing: Boolean
) {
    val context = LocalContext.current
    // §11/§15: ONE surface. Sits above the bottom bar so the expanded state
    // is never painted over, and collapses to a pill docked above it.
    // The pill appeared and disappeared instantly - the one piece of chrome
    // that arrives unannounced while you are looking elsewhere. It now slides
    // up from behind the nav bar and leaves the same way.
    androidx.compose.animation.AnimatedVisibility(
        visible = state.hasItem && !nav.playerHidden && !feedVideoLive,
        // Fixed ~55dp of travel, NOT { it }: the lambda receives the container
        // height, and this container is the full screen because PlayerSurface
        // fills it when expanded. Aligning it BottomCenter instead would stop
        // the expanded player filling the screen at all.
        enter = androidx.compose.animation.slideInVertically(
            animationSpec = Motion.spatial(), initialOffsetY = { 160 }
        ) + androidx.compose.animation.fadeIn(tween(Motion.Standard)),
        exit = androidx.compose.animation.slideOutVertically(
            animationSpec = Motion.spatial(), targetOffsetY = { 160 }
        ) + androidx.compose.animation.fadeOut(tween(Motion.Fast))
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        PlayerSurface(
            state = state,
            vm = vm,
            expanded = nav.showPlayer,
            onFullscreen = { nav.videoFullscreen = true },
            artItem = playingItem,
            // Media3's timeline only carries title/artist/uri, so map back to
            // the library item to get real cover art in the queue.
            artForQueue = { e -> lib.allById[e.mediaId] },
            isFavorite = playingItem?.let { lib.favorites.contains(it.id) } == true,
            onToggleFavorite = {
                playingItem?.let { item ->
                    scope.launch {
                        if (lib.favorites.contains(item.id))
                            db.moodDao().remove(Mood.FAVORITES.key, item.id)
                        else
                            db.moodDao().add(
                                MoodMember(Mood.FAVORITES.key, item.id, System.currentTimeMillis())
                            )
                    }
                }
            },
            beat = beat,
            envelope = envelope,
            videoSurface = !feedOwnsVideo,
            bottomInset = navBottom + BottomBarHeight + MiniPlayerGap,
            onExpandedChange = { nav.showPlayer = it },
            onDismiss = { nav.showPlayer = false; vm.dismiss() },
            libraryItem = libraryPlaying,
            onlineLookups = onlineLookups,
            onEnableOnline = { scope.launch { SettingsStore.setOnline(context, true) } },
            onOpenAudioPath = { nav.showAudioPath = true },
            onOpenSound = { nav.showSound = true }
        )
    }
    // One-time nudge for reactive artwork. Only when the player is actually
    // open, only when the feature is off, and only once - a hint that keeps
    // reappearing is an advert.
    val hintSeen by SettingsStore.playerHintSeenFlow(context).collectAsState(initial = true)
    if (nav.showPlayer && !hintSeen && !reactiveArtOn) {
        Box(
            Modifier.fillMaxSize().statusBarsPadding().padding(Space.xl, 64.dp, Space.xl, 0.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                Modifier.clip(RoundedCornerShape(Radius.lg))
                    .background(MediaColors.Floating)
                    .border(1.dp, MediaColors.Fill, RoundedCornerShape(Radius.lg))
                    .padding(Space.lg)
            ) {
                Text(stringResource(R.string.reactive_hint_title), style = Typo.Primary, color = MediaColors.Cream)
                Spacer(Modifier.height(Space.xxs))
                Text(
                    stringResource(R.string.reactive_hint_body),
                    style = Typo.Secondary, color = MediaColors.CreamDim
                )
                Spacer(Modifier.height(Space.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Box(
                        Modifier.clip(CircleShape).background(MediaColors.Accent)
                            .pressScale(haptic = true) {
                                scope.launch {
                                    SettingsStore.setReactiveArt(context, true)
                                    SettingsStore.setPlayerHintSeen(context)
                                }
                            }
                            .padding(horizontal = Space.lg, vertical = Space.sm)
                    ) { Text(stringResource(R.string.action_turn_it_on), style = Typo.Label, color = Color.White) }
                    Box(
                        Modifier.clip(CircleShape)
                            .border(1.dp, MediaColors.Fill, CircleShape)
                            .pressScale(haptic = true) {
                                scope.launch { SettingsStore.setPlayerHintSeen(context) }
                            }
                            .padding(horizontal = Space.lg, vertical = Space.sm)
                    ) { Text(stringResource(R.string.action_not_now), style = Typo.Label, color = MediaColors.CreamDim) }
                }
            }
        }
    }

    // Two players in one room is not a feature. Handing a file to the TV
    // pauses this one.
    LaunchedEffect(sharing) {
        if (sharing && state.isPlaying) vm.togglePlayPause()
    }
    if (nav.showShare) {
        // Via Share gets the REAL queue, not just the current track, so Next
        // and end-of-track work on the TV the way they do on the phone. The
        // index is found by uri rather than carried over, because an entry
        // whose item is no longer in the library drops out of the mapping.
        val sessionQueue by vm.queue.collectAsState()
        val shareQueue = remember(sessionQueue, lib.allById) {
            sessionQueue.mapNotNull { lib.allById[it.mediaId] }
        }
        val shareIndex = remember(shareQueue, state.currentUri) {
            shareQueue.indexOfFirst { it.uri.toString() == state.currentUri }.coerceAtLeast(0)
        }
        ShareSheet(
            queue = shareQueue,
            startIndex = shareIndex,
            onDismiss = { nav.showShare = false }
        )
    }
}

@Composable
private fun HomeSheets(
    nav: HomeNav,
    lib: HomeLibrary,
    state: PlayerState,
    vm: PlayerViewModel,
    db: OverrideDatabase,
    scope: CoroutineScope,
    onlineLookups: Boolean,
    artScope: (AppMediaItem) -> List<AppMediaItem>,
    playingItem: AppMediaItem?,
    libraryPlaying: AppMediaItem?
) {
    // Deleting is the one thing in here that touches the user's storage, so
    // it goes through the system's own consent flow and the library is
    // rescanned only once the file is really gone.
    val deleteMedia = rememberMediaDeleter { gone ->
        if (state.currentUri == gone.uri.toString()) vm.skipFailedItem()
        MediaRepository.refresh()
        nav.reloadKey++
    }
    nav.addToItem?.let { item ->
        val itemMoods = lib.moodMembers.filter { it.mediaId == item.id }.map { it.moodKey }.toSet()
        val itemPlaylists = lib.playlistMembers.filter { it.mediaId == item.id }.map { it.playlistId }.toSet()
        AddToSheet(
            item = item,
            memberMoods = itemMoods,
            playlists = lib.playlists,
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
            onEditDetails = { nav.editItem = item; nav.addToItem = null },
            onPlayNext = { vm.playNext(item) },
            onAddToQueue = { vm.addToQueue(item) },
            // Null when the track has no real album/artist metadata — the row
            // is then absent rather than present and dead.
            onViewAlbum = if (item.album != UNKNOWN_ALBUM) ({
                lib.albums.firstOrNull { it.id == item.albumId }
                    ?.let { nav.openAlbum = it; nav.openArtist = null }
            }) else null,
            onViewArtist = if (item.artist != UNKNOWN_ARTIST) ({
                lib.artists.firstOrNull { it.id == item.artistId }
                    ?.let { nav.openArtist = it; nav.openAlbum = null }
            }) else null,
            onDelete = { deleteMedia(item) },
            onDismiss = { nav.addToItem = null },
            onDetails = if (item.type == MediaType.AUDIO) ({ nav.detailsItem = item }) else null,
            onArtwork = if (item.type == MediaType.AUDIO) ({ nav.artworkItem = item }) else null
        )
    }
    nav.detailsItem?.let { item ->
        TrackDetailsSheet(
            item = item,
            onlineAllowed = onlineLookups,
            onApply = { title, artist ->
                // An ordinary edit: stored as an override, the file untouched.
                scope.launch {
                    db.dao().upsert(
                        lib.overrides[item.id]?.copy(customTitle = title, customArtist = artist)
                            ?: MediaOverride(mediaId = item.id, customTitle = title, customArtist = artist)
                    )
                }
                vm.updateCurrentMetadata(item.id, title, artist)
                nav.detailsItem = null
            },
            onFixArtwork = { nav.artworkItem = item; nav.detailsItem = null },
            onDismiss = { nav.detailsItem = null }
        )
    }
    nav.artworkItem?.let { item ->
        ArtworkSheet(
            item = item,
            scope = artScope(item),
            onlineAllowed = onlineLookups,
            onDismiss = { nav.artworkItem = null }
        )
    }
    if (nav.showAudioPath) {
        (libraryPlaying ?: playingItem)?.let { item ->
            AudioPathSheet(
                item = item,
                onOpenSound = { nav.showSound = true; nav.showAudioPath = false },
                onDismiss = { nav.showAudioPath = false }
            )
        }
    }
    if (nav.showSound) {
        SoundScreen(onClose = { nav.showSound = false })
    }
    if (nav.showPro) {
        ProScreen(onClose = { nav.showPro = false })
    }
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
            if (filtered) stringResource(R.string.empty_mood_title, stringResource(mood.labelRes)) else stringResource(R.string.empty_library_title),
            style = Typo.Section, color = MediaColors.Cream
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            when {
                mood == Mood.FAVORITES -> stringResource(R.string.empty_favorites_body)
                filtered -> stringResource(R.string.empty_mood_body)
                else -> stringResource(R.string.empty_library_body)
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
                stringResource(if (filtered) R.string.action_show_all_tracks else R.string.action_rescan_library),
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
            // The room's light runs on under the bar, lined up with the
            // screen above it, a step darker so the bar still reads as one.
            .screenBackground(extraScrim = 0.10f)
            .navigationBarsPadding()
    ) {
        // Hairline on the TOP EDGE only. .border() drew a 0.5dp box on all
        // four sides, which is why the bar read as a slab with an outline
        // rather than a surface the content scrolls under.
        // A black shadow gradient over a near-black floor reads as a visible
        // STRIPE, not depth. Now that the background floor is darker than
        // NavSurface, the bar separates on its own tonally - the hairline is
        // all the definition it needs.
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(MediaColors.Fill))
        Row(
            // weight(1f) centred each tab in its own quarter, so the outer two
            // always sat an eighth of the screen in from the edges. Fixed-width
            // tabs with SpaceBetween anchor the first and last to the margins
            // and distribute the slack between them instead. 68dp x4 still
            // fits a 320dp screen with room to spare.
            Modifier.fillMaxWidth().height(BottomBarHeight)
                .padding(horizontal = Space.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tab = Modifier.width(68.dp)
            NavTab(Icons.Filled.Home, Icons.Outlined.Home, stringResource(R.string.nav_home), current == 0, tab) { onSelect(0) }
            // Was LibraryBooks - a stack of BOOKS, in a music app. The core
            // set has a music-library icon; nothing exotic was needed.
            NavTab(Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic,
                stringResource(R.string.nav_library), current == 1, tab) { onSelect(1) }
            NavTab(Icons.AutoMirrored.Filled.QueueMusic, Icons.AutoMirrored.Outlined.QueueMusic, stringResource(R.string.nav_playlists), current == 2, tab) { onSelect(2) }
            // Tune is the EQUALISER glyph (sliders) - in a music app that
            // reads as an EQ feature, not "more". Menu (hamburger) signals a
            // drawer that doesn't exist. The tab is settings; show a gear.
            NavTab(Icons.Filled.Settings, Icons.Outlined.Settings, stringResource(R.string.nav_more), current == 3, tab) { onSelect(3) }
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
        containerColor = MediaColors.Modal,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MediaColors.CreamFaint) }
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(Space.xl, Space.sm, Space.xl, Space.xl)) {
            Text(stringResource(R.string.sleep_title), style = MaterialTheme.typography.titleLarge, color = MediaColors.Cream)
            Spacer(Modifier.height(Space.lg))
            listOf(15, 30, 45, 60).forEach { m ->
                SleepRow(plural(R.plurals.sleep_minutes, m), active = state.sleepActive && !state.sleepEndOfTrack) { onPick(m) }
            }
            SleepRow(stringResource(R.string.sleep_end_of_track), active = state.sleepEndOfTrack) { onEndOfTrack() }
            if (state.sleepActive) {
                Spacer(Modifier.height(Space.sm))
                Box(
                    Modifier.fillMaxWidth().clickable { onCancelTimer() }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.sleep_turn_off), style = MaterialTheme.typography.bodyLarge, color = MediaColors.CreamDim)
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
        if (active) Icon(Icons.Filled.Check, stringResource(R.string.cd_active), tint = MediaColors.Accent, modifier = Modifier.size(20.dp))
    }
}

