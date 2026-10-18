package com.media.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
//  APP OPEN, every time Via comes to the front: a cold start, or coming back
//  from another app or the home screen. It is shown only if it is ready
//  within a few seconds of the open - an ad that turns up halfway through
//  picking a song is an interruption, not an app-open ad, and Google treats
//  it as one. A warm open with nothing loaded loads one and gives it the
//  same few seconds; the next open has one waiting either way.
//
//  NOT AN OPEN: coming back from something Via itself put on top - a picker,
//  Play's purchase or update sheet, the browser, the share sheet, an ad that
//  was tapped. MainActivity reports each of those as it starts them
//  ([expectReturn]); returning from one is the listener finishing a task,
//  and an ad there would land on them mid-step. Away for half an hour
//  counts as a real open again.
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

    // A cold start has consent, the SDK and the ad itself to wait for.
    private const val COLD_OPEN_WINDOW_MS = 8_000L
    private const val WARM_OPEN_WINDOW_MS = 4_000L
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
    /** Show the app-open ad the moment it is ready, until this time. */
    private var showOpenUntil = 0L

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

    // What the app is doing, kept current by HomeScaffold.
    @Volatile var audioPlaying = false
    @Volatile var videoPlaying = false
    @Volatile var playerOpen = false

    private fun now() = SystemClock.elapsedRealtime()
    private fun log(msg: String) = Log.i(TAG, msg)
    private fun adFree() = Billing.adFree.value

    /** From MainActivity.onCreate and onResume: the activity ads are shown over. */
    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
        // Back on screen without Via ever leaving the foreground (a
        // translucent sheet, a dialog): nothing to skip next time.
        returnExpected = false
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

    fun detach(activity: Activity) {
        if (this.activity?.get() === activity) this.activity = null
    }

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
        showOpenUntil = 0L
        leftAt = now()
        if (returnExpected) {
            returnExpected = false
            skipNextOpen = true
        }
    }

    private fun onAppOpened() {
        foreground = true
        if (skipNextOpen) {
            skipNextOpen = false
            if (now() - leftAt < RETURN_GRACE_MS) {
                log("app open skipped: back from something Via opened")
                return
            }
        }
        showOpenUntil = now() + if (ready) WARM_OPEN_WINDOW_MS else COLD_OPEN_WINDOW_MS
        tryShowAppOpen()
    }

    /** Why a full-screen ad cannot go up right now, or null if it can. */
    private fun blocker(act: Activity?): String? = when {
        !ready -> "ads not ready"
        !foreground -> "Via is in the background"
        adFree() -> "Via Pro"
        act == null || act.isFinishing || act.isDestroyed -> "no activity"
        act.isInPictureInPictureMode -> "picture-in-picture"
        showing -> "an ad is already showing"
        videoPlaying -> "a video is playing"
        else -> null
    }

    // ------------------------------------------------------------- app open

    private fun appOpenFresh() = appOpen != null && now() - appOpenAt < APP_OPEN_TTL_MS

    private fun tryShowAppOpen() {
        if (now() > showOpenUntil) return
        val act = activity?.get()
        blocker(act)?.let { log("app open not shown: $it"); return }
        if (!appOpenFresh()) {
            appOpen = null
            loadAppOpen()
            return
        }
        val ad = appOpen ?: return
        appOpen = null
        showOpenUntil = 0L
        ad.fullScreenContentCallback = callback("app open") { loadAppOpen() }
        present(act!!) { ad.show(it) }
    }

    private fun loadAppOpen() {
        val act = activity?.get() ?: return
        if (!ready || adFree() || appOpenLoading || appOpenFresh()) return
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
                    tryShowAppOpen()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    appOpenLoading = false
                    log("app open failed to load: ${error.code} ${error.message}")
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
        val why = blocker(act) ?: when {
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
        }
    }

    private fun callback(kind: String, next: () -> Unit) = object : FullScreenContentCallback() {
        override fun onAdShowedFullScreenContent() {
            lastShownAt = now()
            log("$kind shown")
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
