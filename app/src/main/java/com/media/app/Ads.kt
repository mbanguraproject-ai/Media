package com.media.app

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

// ============================================================================
//  ADS
//
//  ONE banner, in Settings, under Text size. The native card used to sit
//  mid-list between two rows, which is the one place a person is scrolling
//  fast and aiming at targets: obstructive to use and a mis-tap generator.
//  Settings is somewhere you arrive deliberately and read.
//
//  Nothing on Home, nothing on Now Playing, no interstitial between tracks.
//  A player is used with the screen off; permanent chrome would be in the
//  way far longer than it would ever be seen.
//
//  Debug builds always request Google's official TEST unit; only release
//  builds request the live banner. Loading live ads in a debug build, or
//  tapping your own live ads, gets accounts suspended — this is the single
//  most common way people lose their AdMob account.
// ============================================================================

object Ads {

    /**
     * The live BANNER unit (the earlier native unit is a different format and
     * can never fill a banner slot). Release builds only: debug builds use
     * Google's test unit, because requesting live ads from a development
     * build, or tapping your own live ads, is the fastest route to an AdMob
     * suspension.
     */
    private const val LIVE_BANNER_UNIT = "ca-app-pub-9121922395304175/2583208911"

    val BANNER_UNIT_ID: String
        get() = if (BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111"
                else LIVE_BANNER_UNIT

    private val initialised = AtomicBoolean(false)

    /**
     * Google requires a consent flow for the EEA and UK. Serving ads without
     * one can get an AdMob account restricted, so the SDK is only initialised
     * once consent has been resolved — never before.
     */
    fun startConsentThenInit(activity: Activity, onReady: () -> Unit) {
        val params = ConsentRequestParameters.Builder().build()
        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                // Fires whether a form was shown or not. Ads are requested
                // only when UMP says they may be: canRequestAds() was defined
                // here and never called, so a failed or unanswered form in the
                // EEA/UK still went on to request ads - the exact case Google
                // restricts accounts for. A refusal still allows
                // non-personalised ads, and canRequestAds() says so.
                if (info.canRequestAds()) initThen(activity, onReady)
            }
        }, {
            // The lookup failed (offline, say). The answer from a previous
            // launch is still on the device; it decides.
            if (info.canRequestAds()) initThen(activity, onReady)
        })
    }

    /**
     * Starts the SDK OFF the main thread - Google's own guidance, because
     * initialisation reads storage and talks to Play services and can stall
     * the first frames - and reports ready only once it has finished, so the
     * first banner request never races it.
     */
    private fun initThen(activity: Activity, onReady: () -> Unit) {
        val app = activity.applicationContext
        if (!initialised.compareAndSet(false, true)) {
            onReady()
            return
        }
        Thread {
            MobileAds.initialize(app) {
                activity.runOnUiThread { onReady() }
            }
        }.apply { name = "ads-init" }.start()
    }

    fun canRequestAds(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).canRequestAds()

    /**
     * True where the law requires a way to change or withdraw consent: the
     * EEA, the UK and Switzerland, and the US states whose message is set up
     * in AdMob. UMP decides from the user's location; the answer is known
     * once the consent check at startup has run.
     */
    fun privacyChoicesRequired(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /**
     * Google's own privacy-options form: change or withdraw consent for
     * personalised ads (GDPR), or opt out of the sale or sharing of personal
     * information (US state laws). [onDone] gets an error message when the
     * form could not be shown.
     */
    fun showPrivacyChoices(activity: Activity, onDone: (String?) -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error -> onDone(error?.message) }
    }

    /** Android's device-wide ad settings: reset or delete the advertising ID. */
    fun openDeviceAdSettings(context: Context) {
        val intents = listOf(
            android.content.Intent("com.google.android.gms.settings.ADS_PRIVACY"),
            android.content.Intent(android.provider.Settings.ACTION_PRIVACY_SETTINGS)
        )
        for (i in intents) {
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i) }.isSuccess) return
        }
    }
}

/**
 * One adaptive banner, drawn only once it has actually loaded: while loading,
 * and on any failure, nothing is drawn rather than a gap, so a failed load
 * costs the user no layout at all.
 */
@Composable
fun ViaBanner(ready: Boolean, modifier: Modifier = Modifier) {
    // No unit, no banner. Shipping an AdView pointed at an empty or wrong-format
    // unit id produces an invisible box and a stream of no-fill errors, and
    // reusing the NATIVE unit for a banner is a format mismatch, not a shortcut.
    if (!ready || Ads.BANNER_UNIT_ID.isBlank()) return

    // THE CARD BELONGS TO THE AD, NOT TO THE SCREEN. Drawn by the caller it
    // was an empty grey slab in Settings whenever there was no ad in it - and
    // there is no ad in it far more often than you would think: a brand new
    // unit does not fill for hours, a user with no connection never fills, and
    // no-fill is a normal daily outcome. An AdView also reserves its adaptive
    // height the moment it exists, so "it will collapse on its own" is false.
    // Nothing is drawn until onAdLoaded actually fires.
    var loaded by remember { mutableStateOf(false) }
    // The width the card actually leaves: the screen less the gutter and the
    // card's own 8dp padding on both sides. This was screen - 64, which is
    // only right for a 24dp gutter; on 28dp (Enhanced) and 32dp (Premium, Ultra) the
    // AdView came out 8-16dp wider than its card and was cropped - and
    // cropping an ad is altering it, which AdMob does not allow.
    val gutter = Space.xl
    val widthDp = LocalConfiguration.current.screenWidthDp - ((gutter.value + Space.sm.value) * 2).toInt()
    // Paused with the screen, as the SDK asks, so a banner in a backgrounded
    // app does not keep refreshing.
    var adView by remember { mutableStateOf<AdView?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, adView) {
        val view = adView
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> view?.pause()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> view?.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (loaded) Modifier
                    // The gutter above belongs to the ad too, or an unfilled
                    // banner still pushes what is below it down by 16dp.
                    .padding(top = Space.lg)
                    .padding(horizontal = Space.xl)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(MediaColors.Elevated)
                    .padding(Space.sm)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // Keyed on the width: a tier change re-creates the view at the new
        // size rather than leaving an ad sized for the old card.
        key(widthDp) { AndroidView(
            factory = { ctx ->
                val view = AdView(ctx)
                adView = view
                // GONE until it has something to show. The request still runs
                // while hidden; AdMob counts the impression when it becomes
                // visible, which is exactly when onAdLoaded flips it.
                view.visibility = View.GONE
                view.adUnitId = Ads.BANNER_UNIT_ID
                // Adaptive, not the fixed 320x50: it asks for the height that
                // suits this screen width, so the ad is never letterboxed
                // inside a box the wrong shape.
                view.setAdSize(
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                        ctx, widthDp.coerceAtLeast(200)
                    )
                )
                view.adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        view.visibility = View.VISIBLE
                        loaded = true
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        view.visibility = View.GONE
                        loaded = false
                    }
                }
                view.loadAd(AdRequest.Builder().build())
                view
            },
            onRelease = { if (adView === it) adView = null; it.destroy() }
        ) }
    }
}
