package com.media.app

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
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

    /** TEST ID — replace with your own AdMob native unit before release. */
    const val NATIVE_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"

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
fun rememberNativeAd(enabled: Boolean): NativeAd? {
    val context = LocalContext.current
    var ad by remember { mutableStateOf<NativeAd?>(null) }

    DisposableEffect(enabled) {
        if (!enabled || !Ads.canRequestAds(context)) {
            return@DisposableEffect onDispose { }
        }
        val loader = AdLoader.Builder(context, Ads.NATIVE_UNIT_ID)
            .forNativeAd { loaded -> ad = loaded }
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    // Top-right keeps AdChoices clear of the card's own text.
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .build()
            )
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) { ad = null }
            })
            .build()
        loader.loadAd(AdRequest.Builder().build())

        onDispose {
            // NativeAd holds native resources; leaking it leaks memory.
            ad?.destroy()
            ad = null
        }
    }
    return ad
}
