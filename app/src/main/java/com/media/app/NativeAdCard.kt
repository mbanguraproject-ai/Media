package com.media.app

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView

// ============================================================================
//  NATIVE AD CARD (feed slot)
//
//  Renders nothing at all when there is no ad. That is deliberate: a failed or
//  slow load must cost the user zero layout, not a placeholder gap that shifts
//  the feed when it fills.
//
//  Every asset view has to be registered on the NativeAdView and setNativeAd
//  called LAST — Google's SDK requires that order, and getting it wrong means
//  impressions silently don't count.
// ============================================================================

@Composable
fun NativeAdCard(ad: NativeAd?, modifier: Modifier = Modifier) {
    if (ad == null) return

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            LayoutInflater.from(ctx).inflate(R.layout.native_ad_card, null) as NativeAdView
        },
        update = { view ->
            val headline = view.findViewById<TextView>(R.id.ad_headline)
            val advertiser = view.findViewById<TextView>(R.id.ad_advertiser)
            val body = view.findViewById<TextView>(R.id.ad_body)
            val cta = view.findViewById<TextView>(R.id.ad_cta)
            val icon = view.findViewById<ImageView>(R.id.ad_icon)

            headline.text = ad.headline
            view.headlineView = headline

            // Optional assets: hide the view rather than leave an empty line.
            val who = ad.advertiser ?: ad.store
            if (who.isNullOrBlank()) {
                advertiser.visibility = View.GONE
            } else {
                advertiser.text = who
                advertiser.visibility = View.VISIBLE
                view.advertiserView = advertiser
            }

            if (ad.body.isNullOrBlank()) {
                body.visibility = View.GONE
            } else {
                body.text = ad.body
                body.visibility = View.VISIBLE
                view.bodyView = body
            }

            if (ad.callToAction.isNullOrBlank()) {
                cta.visibility = View.GONE
            } else {
                cta.text = ad.callToAction
                cta.visibility = View.VISIBLE
                view.callToActionView = cta
            }

            val ic = ad.icon
            if (ic == null) {
                icon.visibility = View.GONE
            } else {
                icon.setImageDrawable(ic.drawable)
                icon.visibility = View.VISIBLE
                view.iconView = icon
            }

            // MUST be last: registers the impression and wires click handling.
            view.setNativeAd(ad)
        }
    )
}
