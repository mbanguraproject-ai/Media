package com.media.app

import android.content.Context
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ============================================================================
//  RETENTION: THE LISTENING STREAK
//
//  Days in a row with at least one real play (the same five seconds that
//  feed play history), and how many plays this week. Shown as the first tile
//  of Home's "Jump back in" row: a reason to come back tomorrow, and a nudge
//  - "play today to keep it" - on the day the streak is at risk. Nothing
//  else: no notifications, no badges on the icon. A player that pesters you
//  to open it gets uninstalled, not opened.
//
//  On the device only, like everything else Via keeps. Days are local days,
//  so a streak does not break at midnight UTC for someone in Lagos or Manila.
//  java.time is API 26 and Via starts at 24, hence the arithmetic.
// ============================================================================

/** The streak as Home shows it. [days] is 0 once it has lapsed. */
data class Streak(val days: Int = 0, val playedToday: Boolean = false, val weekPlays: Int = 0)

object Retention {
    private const val PREFS = "retention"
    private const val KEY_LAST_DAY = "lastDay"
    private const val KEY_STREAK = "streak"
    private const val KEY_BEST = "best"
    // "day:count,day:count" for the last seven days. Small enough that a
    // database table would be ceremony.
    private const val KEY_WEEK = "week"
    private const val DAY_MS = 86_400_000L

    private val _streak = MutableStateFlow(Streak())
    val streak: StateFlow<Streak> = _streak.asStateFlow()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun today(): Long {
        val now = System.currentTimeMillis()
        return (now + TimeZone.getDefault().getOffset(now)) / DAY_MS
    }

    private fun parseWeek(raw: String?): MutableMap<Long, Int> =
        raw.orEmpty().split(',').mapNotNull { part ->
            val (d, n) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val day = d.toLongOrNull() ?: return@mapNotNull null
            val count = n.toIntOrNull() ?: return@mapNotNull null
            day to count
        }.toMap().toMutableMap()

    /** Re-reads the streak: on launch, and each time Via comes back (the day may have turned). */
    fun refresh(context: Context) {
        val p = prefs(context)
        val today = today()
        val last = p.getLong(KEY_LAST_DAY, -10)
        val week = parseWeek(p.getString(KEY_WEEK, null))
        _streak.value = Streak(
            // Yesterday still counts: the streak is alive until today ends.
            days = if (last >= today - 1) p.getInt(KEY_STREAK, 0) else 0,
            playedToday = last == today,
            weekPlays = week.filterKeys { it > today - 7 }.values.sum()
        )
    }

    /** A real play (PlayerViewModel.onQualifyingPlay). */
    fun recordPlay(context: Context) {
        val p = prefs(context)
        val today = today()
        val last = p.getLong(KEY_LAST_DAY, -10)
        val streak = when (last) {
            today -> p.getInt(KEY_STREAK, 1).coerceAtLeast(1)
            today - 1 -> p.getInt(KEY_STREAK, 0) + 1
            else -> 1
        }
        val week = parseWeek(p.getString(KEY_WEEK, null))
            .filterKeys { it > today - 7 }.toMutableMap()
        week[today] = (week[today] ?: 0) + 1
        p.edit()
            .putLong(KEY_LAST_DAY, today)
            .putInt(KEY_STREAK, streak)
            .putInt(KEY_BEST, maxOf(streak, p.getInt(KEY_BEST, 0)))
            .putString(KEY_WEEK, week.entries.joinToString(",") { "${it.key}:${it.value}" })
            .apply()
        refresh(context)
    }
}
