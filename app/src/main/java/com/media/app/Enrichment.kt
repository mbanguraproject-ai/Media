package com.media.app

import android.content.Context

/**
 * One call at startup, from both the activity and the playback service (they
 * share a process, but either can be first). Everything it touches is cheap:
 * two small JSON indexes and a handful of preferences.
 */
object Enrichment {
    fun init(context: Context) {
        Net.userAgent = "Media/${BuildConfig.VERSION_NAME} ( https://mebs.app )"
        ArtworkStore.init(context)
        LyricsStore.init(context)
        SoundEngine.load(context)
    }
}
