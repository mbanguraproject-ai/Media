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
}
