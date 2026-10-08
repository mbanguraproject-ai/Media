package com.media.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.review.ReviewManagerFactory

// ============================================================================
//  IN-APP REVIEW
//
//  Google Play's In-App Review card, asked for at a natural pause - when the
//  listener closes Now Playing - and only once they have really used the app:
//  20 songs played past the five-second mark, over at least three days since
//  the first one, and never more often than every 120 days. Play applies its
//  own quota on top and may show nothing; the app never says it is about to
//  ask, never asks twice in a row, and never gates anything on a review.
//
//  The "Rate Via" row in Settings opens the Play listing instead. Google is
//  explicit that a button must not call the in-app flow, because the quota
//  can make it a button that silently does nothing.
// ============================================================================

object InAppReview {
    private const val PREFS = "review"
    private const val MIN_PLAYS = 20
    private const val MIN_DAYS = 3L
    private const val GAP_DAYS = 120L
    private const val DAY_MS = 86_400_000L

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A song played past five seconds. */
    fun recordPlay(context: Context) {
        val p = prefs(context)
        val edit = p.edit().putInt("plays", p.getInt("plays", 0) + 1)
        if (p.getLong("first", 0L) == 0L) edit.putLong("first", System.currentTimeMillis())
        edit.apply()
    }

    private fun due(context: Context): Boolean {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val first = p.getLong("first", 0L)
        val last = p.getLong("last", 0L)
        return p.getInt("plays", 0) >= MIN_PLAYS &&
            first != 0L && now - first >= MIN_DAYS * DAY_MS &&
            (last == 0L || now - last >= GAP_DAYS * DAY_MS)
    }

    /** Asks Play for the review card if the moment is right. Safe to call often. */
    fun maybeAsk(activity: Activity) {
        if (!due(activity)) return
        // Marked first: whatever Play decides, this moment has been used.
        prefs(activity).edit().putLong("last", System.currentTimeMillis()).apply()
        val manager = runCatching { ReviewManagerFactory.create(activity) }.getOrNull() ?: return
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful || activity.isFinishing) return@addOnCompleteListener
            runCatching { manager.launchReviewFlow(activity, request.result) }
        }
    }

    /** The Play listing, in the Play app when there is one. */
    fun openListing(context: Context) {
        val pkg = context.packageName
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(market) }.isFailure) runCatching { context.startActivity(web) }
    }
}
