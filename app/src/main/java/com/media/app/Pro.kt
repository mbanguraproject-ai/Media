package com.media.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Playback configuration and state shared by the player UI and playback service.
 * This is independent of monetization: all supported controls are available
 * without an entitlement check.
 */

// ------------------------------------------------------------------ settings

/** An A-B loop on one track. [repeats] is total plays of the section; 0 = endless. */
data class AbLoop(val mediaId: String, val aMs: Long, val bMs: Long?, val repeats: Int)

data class ProSettings(
    val skipSilence: Boolean = false,
    val softPause: Boolean = true,
    val smartResume: Boolean = true,
    val mono: Boolean = false,
    /** -1 fully left .. 0 centre .. 1 fully right. */
    val balance: Float = 0f,
    /** Semitones, -6..6. Applied with the speed, independently of it. */
    val pitch: Int = 0,
    /** Plays of a section before the loop lets go; 0 = endless. */
    val loopRepeats: Int = 0
)

object ProPlayback {
    private const val PREFS = "pro_playback"
    private val _settings = MutableStateFlow(ProSettings())
    val settings: StateFlow<ProSettings> = _settings.asStateFlow()

    private val _loop = MutableStateFlow<AbLoop?>(null)
    /** The current loop, or one waiting for its B point. Null when off. */
    val loop: StateFlow<AbLoop?> = _loop.asStateFlow()

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = ProSettings(
            skipSilence = p.getBoolean("skipSilence", false),
            softPause = p.getBoolean("softPause", true),
            smartResume = p.getBoolean("smartResume", true),
            mono = p.getBoolean("mono", false),
            balance = p.getFloat("balance", 0f).coerceIn(-1f, 1f),
            pitch = p.getInt("pitch", 0).coerceIn(-6, 6),
            loopRepeats = p.getInt("loopRepeats", 0).coerceAtLeast(0)
        )
        loaded = true
    }

    fun update(context: Context, change: (ProSettings) -> ProSettings) {
        _settings.update(change)
        val s = _settings.value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("skipSilence", s.skipSilence)
            .putBoolean("softPause", s.softPause)
            .putBoolean("smartResume", s.smartResume)
            .putBoolean("mono", s.mono)
            .putFloat("balance", s.balance)
            .putInt("pitch", s.pitch)
            .putInt("loopRepeats", s.loopRepeats)
            .apply()
    }

    /**
     * The loop button: off -> A set here -> A-B looping -> off. [positionMs]
     * is where playback is now. Returns the new state.
     */
    fun tapLoop(mediaId: String?, positionMs: Long): AbLoop? {
        val now = _loop.value
        val next = when {
            mediaId == null -> null
            now == null || now.mediaId != mediaId -> AbLoop(mediaId, positionMs.coerceAtLeast(0L), null, _settings.value.loopRepeats)
            now.bMs == null && positionMs > now.aMs + MIN_LOOP_MS -> now.copy(bMs = positionMs)
            now.bMs == null -> now   // too close to A to be a section yet
            else -> null
        }
        _loop.value = next
        return next
    }

    fun clearLoop() { _loop.value = null }

    /** The shortest section a loop may be, so a double tap is not a loop. */
    const val MIN_LOOP_MS = 500L
}

/** Pitch in semitones as the factor the player takes. */
fun pitchFactor(semitones: Int): Float = Math.pow(2.0, semitones / 12.0).toFloat()
