package com.media.app

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

// ============================================================================
//  ADS
//
//  ONE native card, in the Home feed, after the first shelf. Nothing on Now
//  Playing, no banner competing with the mini-player, no interstitial between
//  tracks. A player is used with the screen off — permanent chrome would be in
//  the way far longer than it would ever be seen.
//
//  IDs below are GOOGLE'S OFFICIAL TEST IDS. They must stay until you swap in
//  your own from the AdMob console. Loading live ads in a debug build, or
//  tapping your own live ads, gets accounts suspended — this is the single
//  most common way people lose their AdMob account.
// ============================================================================

object Ads {

    /**
     * LIVE unit. In debug builds this falls back to Google's test unit - never
     * request live ads from a development build, and never tap your own live
     * ads. Both are the fastest routes to an AdMob suspension.
     */
    /**
     * PASTE YOUR LIVE BANNER UNIT HERE. A banner needs its own unit from the
     * AdMob console - the old native unit is a different format and will never
     * fill a banner slot. While this is empty the app simply shows no ad,
     * which is the correct behaviour for a release with nothing to serve.
     */
    private const val LIVE_BANNER_UNIT = ""

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
                // Fires whether a form was shown or not. Either way we may now
                // initialise — and if consent was refused the SDK serves
                // non-personalised ads rather than nothing.
                initIfNeeded(activity)
                onReady()
            }
        }, {
            // Consent lookup failed (offline, etc). Don't block the app.
            initIfNeeded(activity)
            onReady()
        })
    }

    private fun initIfNeeded(context: Context) {
        if (initialised.compareAndSet(false, true)) {
            MobileAds.initialize(context) { }
        }
    }

    fun canRequestAds(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).canRequestAds()
}

/**
 * Loads a single native ad and holds it for the composition's lifetime.
 *
 * Returns null while loading, and on any failure — the caller renders nothing
 * rather than a gap, so a failed load costs the user no layout at all.
 */
@Composable
fun AuraBanner(ready: Boolean, modifier: Modifier = Modifier) {
    // No unit, no banner. Shipping an AdView pointed at an empty or wrong-format
    // unit id produces an invisible box and a stream of no-fill errors, and
    // reusing the NATIVE unit for a banner is a format mismatch, not a shortcut.
    if (!ready || Ads.BANNER_UNIT_ID.isBlank()) return
    val widthDp = LocalConfiguration.current.screenWidthDp - 64
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            AdView(ctx).apply {
                adUnitId = Ads.BANNER_UNIT_ID
                // Adaptive, not the fixed 320x50: it asks for the height that
                // suits this screen width, so the ad is never letterboxed
                // inside a box the wrong shape.
                setAdSize(
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                        ctx, widthDp.coerceAtLeast(200)
                    )
                )
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { it.destroy() }
    )
}
