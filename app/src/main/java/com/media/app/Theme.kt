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
import androidx.compose.ui.graphics.SolidColor
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
// ============================================================================
//  ONE ACCENT. ONE GROUND.
//
//  Every Mood used to carry its own accent AND its own surface tint, so
//  switching mood recoloured the entire app: five themes (violet, blue, amber,
//  teal, pink) competing with each other and with every piece of cover art on
//  screen at once. Colour now comes from the artwork. The app itself is
//  neutral, and the accent marks one thing: what is active.
//
//  An object, not top-level vals - enum constructors run at class-load time and
//  would read uninitialised file-level properties.
// ============================================================================
object Aura {
    val Accent = Color(0xFF2DD4BF)   // active state, progress, selection
    val Ground = Color(0xFF08090B)   // near black — the one flat floor
}

enum class Mood(
    val label: String,
    val accent: Color,
    // ONE value. A background that changes down the screen means no text or
    // card colour is correct everywhere - which is why contrast, card
    // separation and muddy warm hues kept coming back as separate bugs. They
    // were one bug. Mood is a room temperature now: low enough saturation that
    // you notice it when it changes, never enough to compete with artwork.
    val surface: Color,
    val banner: String?,    // status message under the chips (null = no banner)
    val chipOn: Color       // selected-chip fill
) {
    // Colour is identical for every entry now. These stay only as COLLECTIONS
    // (mood_members rows); they carry no theme of their own. The banners went
    // with the colours - "Workout mode activated" was chrome announcing itself.
    ALL(       "All",        Aura.Accent, Aura.Ground, null, Aura.Accent),
    LATE_NIGHT("Late Night", Aura.Accent, Aura.Ground, null, Aura.Accent),
    WORKOUT(   "Workout",    Aura.Accent, Aura.Ground, null, Aura.Accent),
    FOCUS(     "Focus",      Aura.Accent, Aura.Ground, null, Aura.Accent),
    FAVORITES( "Favorites",  Aura.Accent, Aura.Ground, null, Aura.Accent);

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
    // Was a purple-navy cast (#17141F and friends). A tinted ground under
    // full-colour album art muddies every warm cover on the screen, so the
    // floor is neutral now and the artwork supplies the colour.
    bg = Aura.Ground,                // #08090B, near black
    surface = Color(0xFF0D0E11),     // +1 step
    elevated = Color(0xFF121418),    // +2 - cards, panels
    floating = Color(0xFF171A1F),    // +3 - reads above scrolling content
    modal = Color(0xFF1C1F25),       // +4 - sheets sit highest
    hairline = Color(0xFF23262D),    // neutral rule
    text = Color(0xFFF2F3F5),
    textDim = Color(0xFFA8AEBA),
    // 5.8:1 on `elevated`, the worst surface it lands on. The deeper floor
    // bought contrast back rather than spending it.
    textFaint = Color(0xFF8B929E),
    accent = Aura.Accent,            // the ONLY accent in the app
    onAccent = Color(0xFF06231F),
    onInverse = Aura.Ground          // was a leftover purple
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
    // DERIVED, not fixed. A hardcoded value was chosen against the old
    // gradient floor; once the background went flat it became DARKER than
    // every page, so the bar read as a hole instead of a raised surface - and
    // its purple hue sat under a warm Workout page. Lifting the mood's own
    // surface keeps it one consistent step above, in the right hue, forever.
    // Was lifted 5.5% toward white, which on a near-black floor reads as a
    // grey slab bolted to the bottom of the screen. The bar IS the floor now;
    // the 0.5dp hairline above it is the only separation it needs, and the
    // mini-player still floats because it sits on `floating`, three steps up.
    val NavSurface @Composable get() = LocalPalette.current.bg
    val Surface @Composable get() = LocalPalette.current.surface
    val Elevated @Composable get() = LocalPalette.current.elevated
    val Floating @Composable get() = LocalPalette.current.floating
    val Modal @Composable get() = LocalPalette.current.modal
    val InkHairline @Composable get() = LocalPalette.current.hairline
    val Cream @Composable get() = LocalPalette.current.text
    val CreamDim @Composable get() = LocalPalette.current.textDim
    val CreamFaint @Composable get() = LocalPalette.current.textFaint
    // ONE accent, from the palette. It no longer follows the mood, because
    // a theme that changes with a filter chip is five themes, not one.
    val Accent @Composable get() = LocalPalette.current.accent
    val OnAccent @Composable get() = LocalPalette.current.onAccent
    val OnInverse @Composable get() = LocalPalette.current.onInverse
    // New tokens for the redesign:
}

// ONE flat surface. Not a gradient.
//
// Spotify has been #121212 for a decade; Apple Music and YouTube Music are
// flat too. That is not timidity - a background that varies down the screen
// means every row sits on a different value, so no text or card colour is
// right everywhere. Contrast failures, cards blending in, and warm moods going
// muddy were not three bugs, they were one bug three times.
//
// Colour now comes from the only thing that should vary: the artwork. The
// mood tints the room by a few percent, and the accent carries its identity.
// The one place a gradient is earned is behind Now Playing artwork, driven by
// that cover - and that already exists.
@Composable
fun moodBackground(flat: Boolean = true): Brush =
    SolidColor(LocalPalette.current.bg)

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
    val scheme = darkColorScheme(primary = palette.accent, background = palette.bg,
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
