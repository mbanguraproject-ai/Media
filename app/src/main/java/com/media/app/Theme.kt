package com.media.app

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
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
    val glow: Color,        // deep body of the gradient
    val glowCore: Color,    // rich colour where content actually sits
    val glowTop: Color,     // narrow luminous edge at the very top
    val banner: String?,    // status message under the chips (null = no banner)
    val chipOn: Color       // selected-chip fill
) {
    ALL(
        label = "All",
        accent = Color(0xFF7C5CFF),
        glow = Color(0xFF140D2E),
        glowCore = Color(0xFF201346),
        glowTop = Color(0xFF6B54B0),
        banner = null,
        chipOn = Color(0xFF7C5CFF)
    ),
    LATE_NIGHT(
        label = "Late Night",
        accent = Color(0xFF9B7CFF),
        glow = Color(0xFF120C2E),
        glowCore = Color(0xFF1A1247),
        glowTop = Color(0xFF453A82),
        banner = "Now playing Late Night mix",
        chipOn = Color(0xFF7C5CFF)
    ),
    WORKOUT(
        label = "Workout",
        accent = Color(0xFFF5A623),
        glow = Color(0xFF31240A),
        glowCore = Color(0xFF4D370C),
        glowTop = Color(0xFF5C4820),
        banner = "Workout mode activated",
        chipOn = Color(0xFFF5A623)
    ),
    FOCUS(
        label = "Focus",
        accent = Color(0xFF2DD4BF),
        glow = Color(0xFF09322C),
        glowCore = Color(0xFF0C4D43),
        glowTop = Color(0xFF1C5E54),
        banner = "Focus mode \u2014 stay in the zone",
        chipOn = Color(0xFF2DD4BF)
    ),
    FAVORITES(
        label = "Favorites",
        accent = Color(0xFFEC4899),
        glow = Color(0xFF2D0E24),
        glowCore = Color(0xFF471237),
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
    val bg: Color,          // background — the floor
    val surface: Color,     // primary surface: list rows, inline fills
    val elevated: Color,    // cards, panels sitting above content
    val floating: Color,    // mini-player, FABs, anything over scroll
    val modal: Color,       // sheets and dialogs
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
    surface = Color(0xFF1C1928),     // +1 step
    elevated = Color(0xFF221E31),    // +2
    floating = Color(0xFF29243A),    // +3 — reads above scrolling content
    modal = Color(0xFF2F2942),       // +4 — sheets sit highest
    hairline = Color(0xFF2E2A40),    // soft violet-grey rule
    text = Color(0xFFF3F1F7),
    textDim = Color(0xFF9C97AE),
    textFaint = Color(0xFF6A6580),
    accent = Color(0xFF2DD4BF),      // teal primary (mood may override)
    onAccent = Color(0xFF06231F),
    onInverse = Color(0xFF17141F)
)

// Light kept as a graceful fallback (screenshots are dark-first).
// Light stays a stub until it gets designed properly (§49) rather than inverted.
val LightPalette = Palette(
    bg = Color(0xFFF4F4F7),
    surface = Color(0xFFFAFAFC),
    elevated = Color(0xFFFFFFFF),
    floating = Color(0xFFFFFFFF),
    modal = Color(0xFFFFFFFF),
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
    val InkRaised @Composable get() = LocalPalette.current.elevated   // legacy alias

    // §4/§52: these were repeated as raw hex across four files. Semantic
    // names, one definition. 0x17FFFFFF was collapsed into Fill — it sat one
    // step from 0x14FFFFFF for no reason anyone could state.
    // A formal status set. Only Danger existed, so anything non-error had no
    // token at all and would have been reached for as a hex literal - which is
    // exactly the drift the QC pass cleaned up.
    val Success = Color(0xFF34D399)         // completed, saved, granted
    val Warning = Color(0xFFF5A623)         // degraded but usable
    val Danger = Color(0xFFEF4444)          // destructive / error
    val Error = Danger                      // alias: same colour, clearer intent
    val Divider = Color(0x1AFFFFFF)         // hairlines between rows/sections
    val Favorite = Color(0xFFEC4899)        // the heart, and only the heart
    val FillSubtle = Color(0x0FFFFFFF)      // barely-there glass
    val Fill = Color(0x14FFFFFF)            // standard glass fill
    val FillStrong = Color(0x1FFFFFFF)      // pressed / raised glass
    val Scrim = Color(0xCC000000)           // behind modal sheets
    val NavSurface = Color(0xFF120D1B)      // bottom bar, deliberately below bg
    val Surface @Composable get() = LocalPalette.current.surface
    val Elevated @Composable get() = LocalPalette.current.elevated
    val Floating @Composable get() = LocalPalette.current.floating
    val Modal @Composable get() = LocalPalette.current.modal
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
// §47/§48: the background is the FLOOR. It was not — glowTop sat at 51%
// lightness while card surfaces sit at 15%, so every card at the top of Home
// read as a dark hole punched into a light field and the 8%-white glass fills
// went milky. Layer order was inverted.
//
// Fixed by keeping a NARROW luminous edge at the very top (the light source
// that gave the screen its character) and dropping to deep, saturated colour
// by 13% — before any content starts. Saturation went UP as lightness came
// down, so it reads rich rather than muddy.
fun moodBackground(): Brush = Brush.verticalGradient(
    0.00f to LocalMood.current.glowTop,   // luminous edge, behind the status bar
    0.13f to LocalMood.current.glowCore,  // deep + saturated, where content sits
    0.46f to LocalMood.current.glow,      // deeper still
    0.72f to Color(0xFF120E20),
    1.00f to Color(0xFF07050C)            // near-black floor
)

// ============================================================================
//  TYPOGRAPHY (§3)
//  Five semantic levels, exactly as the spec names them. Hierarchy comes from
//  WEIGHT + TRACKING + COLOR, not from size alone — Primary and Secondary sit
//  only 2sp apart and separate cleanly on weight (600 vs 400).
//  Every level carries an explicit lineHeight, and every level scales with the
//  user's text-size preference. 38 raw `fontSize =` literals used to bypass
//  this, which is why "Text size: Large" did nothing to most screens.
// ============================================================================
val Inter = FontFamily(Font(R.font.inter_variable))
// Fraunces retained as an alias so CoverArt.kt still resolves until reskinned.
val Fraunces = Inter

class TypeScale(
    val displayLarge: TextStyle,  // onboarding hero
    val display: TextStyle,       // "Good evening", screen titles
    val section: TextStyle,       // "Continue listening" shelf headers
    val primary: TextStyle,       // track / album / artist name
    val secondary: TextStyle,     // artist / album metadata
    val tertiary: TextStyle,      // duration / format / file info
    val body: TextStyle,          // running prose (About, Terms)
    val label: TextStyle,         // chips, buttons
    val micro: TextStyle          // letterspaced caps eyebrows ("MOODS")
)

private fun level(
    size: Float, weight: FontWeight, tracking: Float, lineRatio: Float, scale: Float
) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = (size * scale).sp,
    letterSpacing = tracking.sp,
    lineHeight = (size * scale * lineRatio).sp
)

fun typeScale(scale: Float) = TypeScale(
    displayLarge = level(34f, FontWeight.Bold,     -0.9f, 1.18f, scale),
    display      = level(28f, FontWeight.Bold,     -0.6f, 1.20f, scale),
    section      = level(19f, FontWeight.SemiBold, -0.3f, 1.26f, scale),
    primary      = level(15f, FontWeight.SemiBold, -0.1f, 1.33f, scale),
    secondary    = level(13f, FontWeight.Normal,    0.0f, 1.38f, scale),
    tertiary     = level(11.5f, FontWeight.Medium,  0.2f, 1.30f, scale),
    body         = level(15f, FontWeight.Normal,    0.0f, 1.47f, scale),
    label        = level(13f, FontWeight.SemiBold,  0.1f, 1.23f, scale),
    micro        = level(10.5f, FontWeight.SemiBold, 0.8f, 1.33f, scale)
)

val LocalTypeScale = androidx.compose.runtime.staticCompositionLocalOf { typeScale(1f) }

// Composable getters, same shim pattern as MediaColors.
object Typo {
    val DisplayLarge @Composable get() = LocalTypeScale.current.displayLarge
    val Display   @Composable get() = LocalTypeScale.current.display
    val Section   @Composable get() = LocalTypeScale.current.section
    val Primary   @Composable get() = LocalTypeScale.current.primary
    val Secondary @Composable get() = LocalTypeScale.current.secondary
    val Tertiary  @Composable get() = LocalTypeScale.current.tertiary
    val Body      @Composable get() = LocalTypeScale.current.body
    val Label     @Composable get() = LocalTypeScale.current.label
    val Micro     @Composable get() = LocalTypeScale.current.micro
}

// Material3 Typography is DERIVED from the same scale, so Material components
// and hand-built ones can never drift apart.
private fun typography(scale: Float): Typography {
    val t = typeScale(scale)
    return Typography(
        displayLarge = t.displayLarge,
        displaySmall = t.display,
        titleLarge = t.section,
        titleMedium = t.primary,
        bodyLarge = t.body,
        bodyMedium = t.secondary,
        labelSmall = t.tertiary,
        labelMedium = t.label
    )
}

object Motion {
    const val Fast = 160        // 120–180ms: taps, toggles, icon morphs
    const val Standard = 260    // 220–320ms: sheets, fades, tab changes
    const val Large = 420       // 350–500ms: shared elements, screen transitions

    // Emphasized decelerate: quick departure, long settle. The default.
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    // Symmetrical, for things that move and return (press states).
    val Smooth = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    // Spatial: things that MOVE. Settles without visible wobble.
    fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = 0.82f, stiffness = 380f)
    // Bouncy: things that RESPOND to a finger. Slight overshoot is the point.
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = 700f)
    // Snappy: small state flips that should feel instant but not robotic.
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 900f)
}

// ============================================================================
//  GEOMETRY (§33) — 8dp grid, one radius scale, one icon scale.
//  Space.xl was 22dp, which sat off-grid; it is 24dp now. Every screen shifts
//  2dp as a result. That is the point: measured, not arbitrary.
// ============================================================================
object Space {
    val xxs = 2.dp; val xs = 4.dp; val sm = 8.dp; val md = 12.dp
    val lg = 16.dp; val xl = 24.dp; val xxl = 32.dp; val xxxl = 48.dp
}

object Radius {
    val xs = 6.dp    // chips, badges
    val sm = 10.dp   // small thumbnails
    val md = 14.dp   // rows, list tiles
    val lg = 20.dp   // cards, panels
    val xl = 28.dp   // sheets, mini-player
    val pill = 999.dp
}

object IconSize {
    val sm = 16.dp   // inline, secondary
    val md = 20.dp   // standard UI
    val lg = 24.dp   // nav, primary actions
    val xl = 32.dp   // transport controls
}

// Elevation is expressed as tonal surface steps, not shadows (§47).
object Elevation {
    val none = 0.dp; val low = 2.dp; val mid = 8.dp; val high = 16.dp
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
        surface = palette.elevated, onBackground = palette.text,
        onSurface = palette.text, onPrimary = palette.onAccent)

    androidx.compose.runtime.CompositionLocalProvider(
        LocalPalette provides palette,
        LocalMood provides mood,
        LocalTypeScale provides typeScale(fontScale),
        LocalReducedMotion provides rememberReducedMotion()
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography(fontScale), content = content)
    }
}
