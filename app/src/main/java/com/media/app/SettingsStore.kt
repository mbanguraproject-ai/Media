package com.media.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "media_settings")

enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class MediaSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val fontScale: Float = 1.0f   // 0.9 = compact, 1.0 = default, 1.15 = large
)

object SettingsStore {
    private val THEME = stringPreferencesKey("theme_mode")
    private val FONT = floatPreferencesKey("font_scale")
    private val INTRO_SEEN = booleanPreferencesKey("intro_seen")
    private val REVEAL_SEEN = booleanPreferencesKey("library_reveal_seen")
    private val AD_FREE = booleanPreferencesKey("ad_free")
    private val PLAYER_HINT = booleanPreferencesKey("player_hint_seen")
    private val REACTIVE_ART = booleanPreferencesKey("reactive_artwork")

    fun flow(context: Context): Flow<MediaSettings> =
        context.dataStore.data.map { p ->
            MediaSettings(
                themeMode = when (p[THEME]) {
                    "LIGHT" -> ThemeMode.LIGHT
                    "SYSTEM" -> ThemeMode.SYSTEM
                    "DARK" -> ThemeMode.DARK
                    else -> ThemeMode.DARK
                },
                fontScale = p[FONT] ?: 1.0f
            )
        }

    suspend fun setTheme(context: Context, mode: ThemeMode) {
        context.dataStore.edit { it[THEME] = mode.name }
    }

    suspend fun setFontScale(context: Context, scale: Float) {
        context.dataStore.edit { it[FONT] = scale }
    }

    fun introSeenFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[INTRO_SEEN] ?: false }

    suspend fun setIntroSeen(context: Context) {
        context.dataStore.edit { it[INTRO_SEEN] = true }
    }

    // §42: the first successful scan is a reveal, shown once and never again.
    fun revealSeenFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[REVEAL_SEEN] ?: false }

    suspend fun setRevealSeen(context: Context) {
        context.dataStore.edit { it[REVEAL_SEEN] = true }
    }

    // Cache only. Play Billing stays the source of truth and clears this if a
    // purchase is refunded; it exists so the first frame is already ad-free.
    fun adFreeFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[AD_FREE] ?: false }

    suspend fun setAdFree(context: Context, value: Boolean) {
        context.dataStore.edit { it[AD_FREE] = value }
    }

    // Shown once, the first time the full player is opened. A feature that is
    // off by default and buried in settings is a feature nobody finds.
    fun playerHintSeenFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[PLAYER_HINT] ?: false }

    suspend fun setPlayerHintSeen(context: Context) {
        context.dataStore.edit { it[PLAYER_HINT] = true }
    }

    // Artwork that moves with the music. OFF by default: it is the most
    // opinionated thing the app does, and an effect someone dislikes is worse
    // than an effect they never found.
    fun reactiveArtFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[REACTIVE_ART] ?: false }

    suspend fun setReactiveArt(context: Context, value: Boolean) {
        context.dataStore.edit { it[REACTIVE_ART] = value }
    }
}
