package com.media.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.lang.ref.WeakReference

// ============================================================================
//  FULL-SCREEN ADS: APP OPEN AND INTERSTITIAL
//
//  APP OPEN, OVER THE SPLASH - NEVER IN THE APP. Every open holds a splash
//  until the ad is up: on a launch, the system's own splash (MainActivity's
//  keep-on-screen condition asks [splashHeld]); on a return to Via from
//  another app or the home screen, the same splash drawn by Via itself
//  (OpenCover, up while [cover] is true). The ad goes up over the splash,
//  the splash lifts behind it, and closing the ad shows the app. If no ad
//  is ready within the hold - no network, no fill - the splash lifts and
//  the app opens without one. An ad that arrives after that is kept for the
//  next open; it never lands on top of the app mid-use.
//
//  NOT AN OPEN: coming back from something Via itself put on top - a picker,
//  Play's purchase or update sheet, the browser, the share sheet, an ad that
//  was tapped. MainActivity reports each of those as it starts them
//  ([expectReturn]); returning from one is the listener finishing a task,
//  and an ad there would land on them mid-step. Away for half an hour
//  counts as a real open again. Nor is the first run: the welcome and the
//  permission screens open without one ([cancelOpen]).
//
//  INTERSTITIAL, at every [NAVIGATIONS_PER_INTERSTITIAL]th change of screen
//  (a tab, or an album, artist or playlist opened). The screen changes
//  FIRST and the ad covers it, so closing the ad always lands on the page
//  that was asked for - an ad is never the reason a tap went nowhere. At
//  most one full-screen ad per [MIN_GAP_MS]: a count reached inside the gap
//  waits for the next screen change after it. Google restricts accounts
//  whose interstitials come back-to-back, and losing the account loses
//  every ad, banner included.
//
//  NEVER: for Via Pro; before consent (Ads.startConsentThenInit); in
//  picture-in-picture; while a video is playing; over Now Playing. Music
//  keeps playing underneath, and the ad is muted while it does - two
//  soundtracks at once is not an ad anyone hears.
//
//  Debug builds request Google's TEST units, like the banner: live ads in a
//  development build are how AdMob accounts get suspended. Every load, show
//  and failure is logged under "ViaAds" (adb logcat -s ViaAds), so an ad
//  that never appears says why.
// ============================================================================

object FullScreenAds {

    private const val TAG = "ViaAds"

    private const val LIVE_APP_OPEN_UNIT = "ca-app-pub-9121922395304175/9621509538"
    private const val LIVE_INTERSTITIAL_UNIT = "ca-app-pub-9121922395304175/2529662583"

    private val APP_OPEN_UNIT: String
        get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/9257395921" else LIVE_APP_OPEN_UNIT
    private val INTERSTITIAL_UNIT: String
        get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/1033173712" else LIVE_INTERSTITIAL_UNIT

    /** Screen changes per interstitial. */
    const val NAVIGATIONS_PER_INTERSTITIAL = 2
    /** The least time between two full-screen ads of any kind. */
    const val MIN_GAP_MS = 60_000L

    // How long a splash waits for its ad. A launch has consent, the SDK and
    // the ad itself to wait for; a return usually has the ad loaded already.
    private const val LAUNCH_HOLD_MS = 5_000L
    private const val RETURN_HOLD_MS = 3_000L
    // Google: an app-open ad older than four hours may no longer pay.
    private const val APP_OPEN_TTL_MS = 4 * 3_600_000L
    // Back from a picker or the browser after this long is a new visit.
    private const val RETURN_GRACE_MS = 30 * 60_000L
    private const val RETRY_MIN_MS = 30_000L
    private const val RETRY_MAX_MS = 10 * 60_000L

    // Main thread only, all of it: the SDK calls back there, and so do the
    // lifecycle and Compose.
    private val main = Handler(Looper.getMainLooper())
    private var activity: WeakReference<Activity>? = null
    private var observing = false
    private var ready = false

    private var appOpen: AppOpenAd? = null
    private var appOpenAt = 0L
    private var appOpenLoading = false
    private var appOpenRetry = RETRY_MIN_MS

    private var interstitial: InterstitialAd? = null
    private var interstitialLoading = false
    private var interstitialRetry = RETRY_MIN_MS
    private var navigations = 0

    private var showing = false
    private var lastShownAt = 0L

    private var foreground = false
    private var returnExpected = false
    private var leftAt = 0L
    private var skipNextOpen = false

    // ---- the open being held for its ad ----
    private enum class Hold { NONE, SYSTEM_SPLASH, COVER }
    private var hold = Hold.NONE
    /** The system splash is up for this launch (set in MainActivity.onCreate). */
    private var systemSplash = false
    /** The splash Via draws over itself on a return to the app. Compose reads it. */
    var cover by mutableStateOf(false)
        private set
    private var afterOpen: (() -> Unit)? = null
    private val holdTimeout = Runnable { release("no ad within the hold") }

    // What the app is doing, kept current by HomeScaffold.
    @Volatile var audioPlaying = false
    @Volatile var videoPlaying = false
    @Volatile var playerOpen = false

    private fun now() = SystemClock.elapsedRealtime()
    private fun log(msg: String) = Log.i(TAG, msg)
    private fun adFree() = Billing.adFree.value

    /** From MainActivity.onCreate: the activity ads are shown over. */
    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
        if (observing) return
        observing = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onAppOpened()
                Lifecycle.Event.ON_STOP -> onAppLeft()
                else -> Unit
            }
        })
    }

    /** From MainActivity.onResume, which comes after the open has been handled. */
    fun onActivityResumed(activity: Activity) {
        this.activity = WeakReference(activity)
        // Back on screen without Via ever leaving the foreground (a
        // translucent sheet, a dialog): nothing to skip next time.
        returnExpected = false
        systemSplash = false
    }

    fun detach(activity: Activity) {
        if (this.activity?.get() === activity) this.activity = null
    }

    /** MainActivity installed the system splash: this launch's ad goes over it. */
    fun onSplashInstalled() {
        systemSplash = true
    }

    /**
     * The keep-on-screen condition of the system splash, asked every frame
     * while it is up: true while this launch is still waiting for its ad.
     */
    fun splashHeld(): Boolean {
        if (hold == Hold.SYSTEM_SPLASH && adFree()) release("Via Pro")
        return hold == Hold.SYSTEM_SPLASH
    }

    /** True while an open is waiting on its ad, splash up. */
    val holdingOpen: Boolean get() = hold != Hold.NONE

    /**
     * Runs [block] once the splash has lifted without an ad - straight away
     * if nothing is held. When an ad does go up, the activity resumes again
     * after it closes, and that resume is the moment instead.
     */
    fun afterOpen(block: () -> Unit) {
        if (hold == Hold.NONE) block() else afterOpen = block
    }

    /** This open gets no ad: the first run, or no consent to request ads. */
    fun cancelOpen(reason: String) = release(reason)

    /**
     * Via is about to put something of its own on top - a picker, Play's
     * sheets, the browser, an ad. Coming back from it is not an app open.
     */
    fun expectReturn() {
        returnExpected = true
    }

    /** Consent is resolved and the SDK is up (Ads.startConsentThenInit). */
    fun onAdsReady() {
        if (ready) return
        ready = true
        log("ready")
        loadAppOpen()
        loadInterstitial()
    }

    private fun onAppLeft() {
        foreground = false
        // An ad still loading for this open must not land after it: over
        // the home screen, or another app.
        release("left before the ad")
        leftAt = now()
        if (returnExpected) {
            returnExpected = false
            skipNextOpen = true
        }
    }

    private fun onAppOpened() {
        foreground = true
        val launch = systemSplash
        systemSplash = false
        if (skipNextOpen) {
            skipNextOpen = false
            if (now() - leftAt < RETURN_GRACE_MS) {
                log("app open skipped: back from something Via opened")
                return
            }
        }
        when {
            // A launch holds the system splash even before consent and the
            // SDK are up: they are what it waits for.
            launch -> begin(Hold.SYSTEM_SPLASH, LAUNCH_HOLD_MS)
            // A return with ads never set up in this run (consent pending or
            // refused) has nothing to wait for, so no splash at all.
            !ready || adFree() -> log("app open skipped: ${if (adFree()) "Via Pro" else "ads not ready"}")
            videoPlaying -> log("app open skipped: a video is playing")
            else -> begin(Hold.COVER, RETURN_HOLD_MS)
        }
    }

    private fun begin(kind: Hold, holdMs: Long) {
        hold = kind
        cover = kind == Hold.COVER
        main.removeCallbacks(holdTimeout)
        main.postDelayed(holdTimeout, holdMs)
        log("open: splash held for its ad (${if (kind == Hold.COVER) "return" else "launch"})")
        tryShowAppOpen()
    }

    /** The splash lifts. [shown]: because the ad is up over it. */
    private fun release(reason: String, shown: Boolean = false) {
        if (hold == Hold.NONE) return
        hold = Hold.NONE
        cover = false
        main.removeCallbacks(holdTimeout)
        log("open: splash released - $reason")
        val next = afterOpen
        afterOpen = null
        if (!shown && foreground) next?.invoke()
    }

    // ------------------------------------------------------------- app open

    private fun appOpenFresh() = appOpen != null && now() - appOpenAt < APP_OPEN_TTL_MS

    private fun tryShowAppOpen() {
        if (hold == Hold.NONE) return
        if (adFree()) { release("Via Pro"); return }
        // Consent and the SDK are still coming up on a launch. The hold's
        // timeout bounds the wait; onAdsReady loads, and the load comes back
        // here.
        if (!ready) return
        val act = activity?.get() ?: return
        when {
            !foreground || act.isFinishing || act.isDestroyed -> { release("Via is not on screen"); return }
            act.isInPictureInPictureMode -> { release("picture-in-picture"); return }
            videoPlaying -> { release("a video is playing"); return }
            showing -> return
        }
        if (!appOpenFresh()) {
            appOpen = null
            loadAppOpen()
            return
        }
        val ad = appOpen ?: return
        appOpen = null
        ad.fullScreenContentCallback = callback("app open", onShown = { release("ad shown", shown = true) }) {
            loadAppOpen()
        }
        present(act) { ad.show(it) }
    }

    private fun loadAppOpen() {
        val act = activity?.get() ?: return
        if (!ready || adFree() || appOpenLoading) return
        if (appOpenFresh()) { tryShowAppOpen(); return }
        appOpenLoading = true
        AppOpenAd.load(
            act.applicationContext, APP_OPEN_UNIT, AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenLoading = false
                    appOpenRetry = RETRY_MIN_MS
                    appOpen = ad
                    appOpenAt = now()
                    log("app open loaded")
                    // Shown only if an open is still holding its splash for
                    // it; otherwise it waits for the next open.
                    tryShowAppOpen()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    appOpenLoading = false
                    log("app open failed to load: ${error.code} ${error.message}")
                    // No point holding the splash for an ad that is not coming.
                    release("no ad: ${error.code}")
                    retry(appOpenRetry) { loadAppOpen() }
                    appOpenRetry = (appOpenRetry * 2).coerceAtMost(RETRY_MAX_MS)
                }
            }
        )
    }

    // --------------------------------------------------------- interstitial

    /** A screen changed: a tab, or an album, artist or playlist opened. */
    fun onNavigated() {
        if (!ready || adFree()) return
        navigations++
        if (navigations < NAVIGATIONS_PER_INTERSTITIAL) return
        val act = activity?.get()
        val why = when {
            act == null || act.isFinishing || act.isDestroyed -> "no activity"
            !foreground -> "Via is in the background"
            hold != Hold.NONE -> "an open is waiting for its ad"
            act.isInPictureInPictureMode -> "picture-in-picture"
            showing -> "an ad is already showing"
            videoPlaying -> "a video is playing"
            playerOpen -> "Now Playing is open"
            now() - lastShownAt < MIN_GAP_MS -> "inside the gap after the last ad"
            interstitial == null -> "none loaded yet"
            else -> null
        }
        if (why != null) {
            log("interstitial held: $why")
            if (interstitial == null) loadInterstitial()
            return
        }
        val ad = interstitial ?: return
        interstitial = null
        navigations = 0
        ad.fullScreenContentCallback = callback("interstitial") { loadInterstitial() }
        present(act!!) { ad.show(it) }
    }

    private fun loadInterstitial() {
        val act = activity?.get() ?: return
        if (!ready || adFree() || interstitialLoading || interstitial != null) return
        interstitialLoading = true
        InterstitialAd.load(
            act.applicationContext, INTERSTITIAL_UNIT, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialLoading = false
                    interstitialRetry = RETRY_MIN_MS
                    interstitial = ad
                    log("interstitial loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialLoading = false
                    log("interstitial failed to load: ${error.code} ${error.message}")
                    retry(interstitialRetry) { loadInterstitial() }
                    interstitialRetry = (interstitialRetry * 2).coerceAtMost(RETRY_MAX_MS)
                }
            }
        )
    }

    // --------------------------------------------------------------- shared

    private fun present(act: Activity, show: (Activity) -> Unit) {
        showing = true
        // Music goes on under the ad; the ad keeps quiet while it does.
        runCatching { MobileAds.setAppMuted(audioPlaying) }
        runCatching { show(act) }.onFailure {
            showing = false
            log("show threw: ${it.message}")
            release("the ad could not be shown")
        }
    }

    private fun callback(kind: String, onShown: () -> Unit = {}, next: () -> Unit) =
        object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                lastShownAt = now()
                log("$kind shown")
                onShown()
            }

            override fun onAdDismissedFullScreenContent() {
                showing = false
                lastShownAt = now()
                log("$kind dismissed")
                next()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                showing = false
                log("$kind failed to show: ${error.code} ${error.message}")
                release("the ad could not be shown")
                next()
            }

            override fun onAdClicked() {
                // The ad opens the browser or Play; coming back is not an open.
                expectReturn()
            }
        }

    // Backing off: a unit that cannot fill now will not fill in a second
    // either, and hammering it is its own policy problem.
    private fun retry(delayMs: Long, load: () -> Unit) {
        main.postDelayed({ if (!adFree()) load() }, delayMs)
    }
}
