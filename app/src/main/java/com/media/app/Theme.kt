package com.media.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
//  MOOD SYSTEM
//  Each mood re-themes the whole home surface: an accent, a soft glow tint for
//  the background wash, and a short banner message. This is a PURELY VISUAL
//  layer — it does not filter or change playback logic.
// ============================================================================
enum class Mood(
    val label: String,
    val accent: Color,
    val glow: Color,        // mid gradient tint
    val glowTop: Color,     // brighter top-of-screen tint (light source)
    val banner: String?,    // status message under the chips (null = no banner)
    val chipOn: Color       // selected-chip fill
) {
    ALL(
        label = "All",
        accent = Color(0xFF7C5CFF),
        glow = Color(0xFF3D2F72),
        glowTop = Color(0xFF6B54B0),
        banner = null,
        chipOn = Color(0xFF7C5CFF)
    ),
    LATE_NIGHT(
        label = "Late Night",
        accent = Color(0xFF9B7CFF),
        glow = Color(0xFF2C2358),
        glowTop = Color(0xFF453A82),
        banner = "Now playing Late Night mix",
        chipOn = Color(0xFF7C5CFF)
    ),
    WORKOUT(
        label = "Workout",
        accent = Color(0xFFF5A623),
        glow = Color(0xFF3A2E14),
        glowTop = Color(0xFF5C4820),
        banner = "Workout mode activated",
        chipOn = Color(0xFFF5A623)
    ),
    FOCUS(
        label = "Focus",
        accent = Color(0xFF2DD4BF),
        glow = Color(0xFF123E38),
        glowTop = Color(0xFF1C5E54),
        banner = "Focus mode \u2014 stay in the zone",
        chipOn = Color(0xFF2DD4BF)
    ),
    FAVORITES(
        label = "Favorites",
        accent = Color(0xFFEC4899),
        glow = Color(0xFF3E1B34),
        glowTop = Color(0xFF5E2A4E),
        banner = null,
        chipOn = Color(0xFFEC4899)
    );

    // Stable DB key for membership (matches mood_members.moodKey). ALL is never stored.
    val key: String get() = name.lowercase()
    val holdsSongs: Boolean get() = this != ALL

    companion object {
        val default = ALL
        fun fromKey(k: String): Mood = values().firstOrNull { it.key == k } ?: ALL
    }
}

// The current mood, provided via CompositionLocal so any composable can read it
// (accent color, glow, banner) without prop-drilling.
val LocalMood = androidx.compose.runtime.staticCompositionLocalOf { Mood.default }

// A setter for the active mood, provided by MainActivity so the home screen's
// mood chips can switch the whole-app theme without prop-drilling.
val LocalMoodSetter = androidx.compose.runtime.staticCompositionLocalOf<(Mood) -> Unit> { {} }

// ---- Semantic color tokens (resolved per theme) ----
class Palette(
    val bg: Color,          // background
    val raised: Color,      // cards, sheets
    val hairline: Color,    // dividers, borders
    val text: Color,        // primary text
    val textDim: Color,     // secondary
    val textFaint: Color,   // tertiary / labels
    val accent: Color,      // active state, progress (mood overrides at call site)
    val onAccent: Color,    // text/icon on accent
    val onInverse: Color    // icon on a light fill
)

// Dark — deep near-black with a cool base. The mood glow paints over this.
val DarkPalette = Palette(
    bg = Color(0xFF17141F),          // deep desaturated purple-navy (mockup base)
    raised = Color(0xFF1E1B2E),      // slightly lifted card
    hairline = Color(0xFF2E2A40),    // soft violet-grey rule
    text = Color(0xFFF3F1F7),
    textDim = Color(0xFF9C97AE),
    textFaint = Color(0xFF6A6580),
    accent = Color(0xFF2DD4BF),      // teal primary (mood may override)
    onAccent = Color(0xFF06231F),
    onInverse = Color(0xFF17141F)
)

// Light kept as a graceful fallback (screenshots are dark-first).
val LightPalette = Palette(
    bg = Color(0xFFF4F4F7),
    raised = Color(0xFFFFFFFF),
    hairline = Color(0xFFE4E4EC),
    text = Color(0xFF16161C),
    textDim = Color(0xFF5C5C68),
    textFaint = Color(0xFF9A9AA8),
    accent = Color(0xFF17B3A0),
    onAccent = Color(0xFFFFFFFF),
    onInverse = Color(0xFFFFFFFF)
)

val LocalPalette = androidx.compose.runtime.staticCompositionLocalOf { DarkPalette }

// Back-compat shim: existing code references MediaColors.X. Names are UNCHANGED
// so every screen keeps compiling; only the resolved values are new.
object MediaColors {
    val Ink @Composable get() = LocalPalette.current.bg
    val InkRaised @Composable get() = LocalPalette.current.raised
    val InkHairline @Composable get() = LocalPalette.current.hairline
    val Cream @Composable get() = LocalPalette.current.text
    val CreamDim @Composable get() = LocalPalette.current.textDim
    val CreamFaint @Composable get() = LocalPalette.current.textFaint
    // Accent now follows the active MOOD, so active states recolor per mood.
    val Accent @Composable get() = LocalMood.current.accent
    val OnAccent @Composable get() = LocalPalette.current.onAccent
    val OnInverse @Composable get() = LocalPalette.current.onInverse
    // New tokens for the redesign:
    val Glow @Composable get() = LocalMood.current.glow
}

// Full-height gradient with real depth: a luminous mood light at the very top,
// holding richly through the upper half, meeting the deep black at CENTER, then
// settling to near-black at the bottom. Extra stops keep it seamless (no banding).
@Composable
fun moodBackground(): Brush = Brush.verticalGradient(
    0.00f to LocalMood.current.glowTop,   // luminous light source
    0.40f to LocalMood.current.glow,      // rich purple holds lower now
    0.62f to Color(0xFF1B1628),           // meet point pushed past center
    0.82f to Color(0xFF120D1B),
    1.00f to Color(0xFF0B0810)            // deep floor (slightly lifted, crisper)
)

// ---- Typography: Inter only, bolder + tighter for the new look ----
val Inter = FontFamily(Font(R.font.inter_variable))
// Fraunces retained as an alias so CoverArt.kt still resolves until reskinned.
val Fraunces = Inter

private fun typography(scale: Float) = Typography(
    displayLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = (32 * scale).sp, letterSpacing = (-0.8).sp),
    displaySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold,
        fontSize = (26 * scale).sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = (18 * scale).sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = (14 * scale).sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = (15 * scale).sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = (12 * scale).sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium,
        fontSize = (10 * scale).sp, letterSpacing = 0.3.sp),
)

object Space {
    val xs = 4.dp; val sm = 8.dp; val md = 12.dp
    val lg = 16.dp; val xl = 22.dp; val xxl = 32.dp
}

@Composable
fun bottomSafePadding(gap: Dp = Space.xl): Dp =
    gap + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
fun MediaTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    fontScale: Float = 1.0f,
    mood: Mood = Mood.default,
    content: @Composable () -> Unit
) {
    // Dark-only by design. Any saved LIGHT/SYSTEM value is ignored so the app
    // always renders the intended dark UI (the mockup is dark-first).
    val palette = DarkPalette
    val scheme = darkColorScheme(primary = mood.accent, background = palette.bg,
        surface = palette.raised, onBackground = palette.text,
        onSurface = palette.text, onPrimary = palette.onAccent)

    androidx.compose.runtime.CompositionLocalProvider(
        LocalPalette provides palette,
        LocalMood provides mood
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography(fontScale), content = content)
    }
}
