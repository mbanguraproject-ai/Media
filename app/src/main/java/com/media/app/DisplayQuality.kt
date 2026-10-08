package com.media.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ============================================================================
//  VIA DISPLAY QUALITY
//
//  Hardware capability -> device profiling -> a complete design profile.
//
//  The blueprint's hard rule is that a quality level is a COMPLETE design, not
//  a bag of effect switches bolted onto one UI. So QualityProfile carries
//  design tokens - artwork resolution, how far surfaces separate from the
//  floor, corner scale, layout density, motion - and not just booleans. A tier
//  changes what the app LOOKS like, not only what it renders on top.
//
//  It also refuses to pretend: these levels describe Via's rendering profile.
//  They do not claim to change the panel. An LCD running Ultra is an LCD.
// ============================================================================

// Essential and Standard are gone. They existed to be compact and flat on a
// weak device and they looked it - a dense grey list nobody would choose. A
// tier you would not ship as the whole app is not a tier, it is a penalty, so
// the floor is Enhanced and the work of protecting a slow phone is left to
// the frame monitor, which measures instead of guessing (Performance.kt).
//
// 4.8 moved every tier up one. What was Premium is now Enhanced, what was
// Ultra is now Premium, and Ultra is new: the cover as liquid light. Every
// tier also gained the edge light, smooth light on every Android version
// (CoverLight.kt), and cheaper frames.
//
// Names, goals and feature lines are string resources: Settings shows them
// in the app's language.
enum class QualityLevel(
    @androidx.annotation.StringRes val label: Int,
    @androidx.annotation.StringRes val goal: Int,
    val features: List<Int>
) {
    ENHANCED(
        R.string.quality_enhanced, R.string.quality_enhanced_goal,
        listOf(
            R.string.quality_enhanced_feature_1,
            R.string.quality_enhanced_feature_2,
            R.string.quality_enhanced_feature_3,
            R.string.quality_enhanced_feature_4,
            R.string.quality_enhanced_feature_5,
            R.string.quality_enhanced_feature_6,
            R.string.quality_enhanced_feature_7,
            R.string.quality_enhanced_feature_8
        )
    ),
    PREMIUM(
        R.string.quality_premium, R.string.quality_premium_goal,
        listOf(
            R.string.quality_premium_feature_1,
            R.string.quality_premium_feature_2,
            R.string.quality_premium_feature_3,
            R.string.quality_premium_feature_4,
            R.string.quality_premium_feature_5,
            R.string.quality_premium_feature_6,
            R.string.quality_premium_feature_7
        )
    ),
    ULTRA(
        R.string.quality_ultra, R.string.quality_ultra_goal,
        listOf(
            R.string.quality_ultra_feature_1,
            R.string.quality_ultra_feature_2,
            R.string.quality_ultra_feature_3,
            R.string.quality_ultra_feature_4,
            R.string.quality_ultra_feature_5,
            R.string.quality_ultra_feature_6
        )
    )
}

/** What the user picked. AUTO follows the detected ceiling. */
enum class QualityMode(val label: String) {
    AUTO("Auto"),
    ENHANCED("Enhanced"), PREMIUM("Premium"), ULTRA("Ultra");

    companion object {
        fun fromName(s: String?): QualityMode =
            values().firstOrNull { it.name == s } ?: AUTO
    }
}

// ---------------------------------------------------------------- hardware --

data class DeviceCapability(
    val totalRamMb: Int,
    val cpuCores: Int,
    val refreshRateHz: Float,
    val widthPx: Int,
    val heightPx: Int,
    val isLowRamDevice: Boolean,
    val supportsHdr: Boolean,
    val sdkInt: Int
) {
    /**
     * A 0..8 score from signals that actually predict sustained frame
     * performance. Deliberately NOT panel marketing names: "AMOLED" says
     * nothing about whether this phone can hold 60fps under blur.
     *
     * RAM sets the base and everything else is a modifier, because a flat
     * additive score over-rewards what every phone already has - 8 cores and a
     * recent API level are free points, and they dragged mid-range hardware up
     * into Premium. Old API levels and weak CPUs now subtract instead.
     */
    val score: Int
        get() {
            if (isLowRamDevice) return 0
            // Thresholds sit BELOW the nominal sizes on purpose. totalMem is
            // what the kernel leaves for userspace, always well under the RAM
            // printed on the box: a 4 GB phone reports ~3.6 GB, 6 GB reports
            // ~5.6 GB. Round numbers (< 4096) therefore filed every nominal
            // 4 GB device into the 3 GB bucket - an off-by-one against every
            // device, found on a real 3.6 GB handset that scored Standard when
            // it should have scored Enhanced.
            var s = when {
                totalRamMb < 1900 -> 0      // under 2 GB
                totalRamMb < 2700 -> 1      // 2 GB class
                totalRamMb < 3500 -> 2      // 3 GB class
                totalRamMb < 5500 -> 3      // 4 GB class
                totalRamMb < 7400 -> 4      // 6 GB class
                else -> 5                   // 8 GB and up
            }
            if (cpuCores <= 4) s -= 1
            if (refreshRateHz >= 89f) s += 1        // 90Hz panels
            if (refreshRateHz >= 119f) s += 1       // 120Hz and above
            if (sdkInt < Build.VERSION_CODES.O) s -= 2
            else if (sdkInt < Build.VERSION_CODES.Q) s -= 1
            if (supportsHdr) s += 1
            return s.coerceIn(0, 8)
        }
}

@Suppress("DEPRECATION")   // defaultDisplay is the only route below API 30
private fun displayOf(context: Context): Display? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    } else {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }
}.getOrNull()

/** Read once at startup. Nothing here allocates or blocks. */
fun detectCapability(context: Context): DeviceCapability {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val mem = ActivityManager.MemoryInfo().also { info ->
        runCatching { am?.getMemoryInfo(info) }
    }
    val display = displayOf(context)
    val metrics = context.resources.displayMetrics
    return DeviceCapability(
        totalRamMb = (mem.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(512),
        cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        refreshRateHz = display?.refreshRate?.takeIf { it > 1f } ?: 60f,
        widthPx = metrics.widthPixels,
        heightPx = metrics.heightPixels,
        isLowRamDevice = am?.isLowRamDevice ?: false,
        supportsHdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { display?.isHdr == true }.getOrDefault(false),
        sdkInt = Build.VERSION.SDK_INT
    )
}

/** The highest level this device is trusted to hold. AUTO never exceeds it. */
fun ceilingFor(cap: DeviceCapability): QualityLevel = when {
    cap.isLowRamDevice -> QualityLevel.ENHANCED
    cap.score <= 5 -> QualityLevel.ENHANCED
    cap.score <= 7 -> QualityLevel.PREMIUM
    else -> QualityLevel.ULTRA
}

/** A manual pick is honoured even above the ceiling: the doc allows the override. */
fun resolveLevel(mode: QualityMode, ceiling: QualityLevel): QualityLevel = when (mode) {
    QualityMode.AUTO -> ceiling
    QualityMode.ENHANCED -> QualityLevel.ENHANCED
    QualityMode.PREMIUM -> QualityLevel.PREMIUM
    QualityMode.ULTRA -> QualityLevel.ULTRA
}

// ----------------------------------------------------------- design profile --

/**
 * The complete rendering + design spec for one tier.
 *
 * Geometry and density live here on purpose. If a tier only changed effects,
 * every tier would be the same screen with more paint on it - which is the one
 * thing the blueprint forbids.
 */
data class QualityProfile(
    val level: QualityLevel,

    // artwork fidelity: multiplies every CoverArt decode target
    val artScale: Float,

    // Depth. Only what costs something, and only what exists.
    //
    // surfaceLift, shadow and particles were here and are gone. Surface
    // separation is free to render, so varying it per tier would only make
    // the lower tiers look worse for no saving - and the palette is meant to
    // be one design, not five. A shadow is invisible against a #000000 floor.
    // And `particles` named a feature this app has never had: a field that
    // promises an effect nothing implements is the same lie as a fake
    // waveform, just hidden in a data class.
    //
    // `blur` went with them until it is actually built - Modifier.blur is a
    // silent no-op below API 31, so it needs a real implementation and a real
    // fallback, not a Dp sitting in a profile.
    //
    // `refreshAware` went too: respecting the panel's true refresh rate is
    // not a tier choice, it is a rule, and Performance.kt applies it at every
    // level.
    val ambientGradient: Boolean,    // full-screen artwork wash on Now Playing
    val dynamicArtLighting: Boolean, // beat-driven bloom and shockwave rings

    // Geometry — tiers are different LAYOUTS, not one layout with effects.
    // Stated per tier rather than derived from a multiplier: a single
    // densityScale float sounded like a system but produced values like
    // 1.84dp and scaled hairlines along with everything else. These are
    // designed numbers.
    // 16dp on every tier since 5.1. 4.8 had taken it to 28-32dp, and rows
    // with that much air each side stopped well short of the screen edge
    // while every other player runs its lists edge to edge. Tiers still
    // differ in row height and artwork size.
    val gutter: Dp,                  // the one screen-side padding, app-wide
    val rowPadV: Dp,                 // vertical padding inside a list row
    val rowArt: Dp,                  // artwork size in a list row
    val rowArtCorner: Int,           // CoverArt takes its corner as Int dp

    // motion
    val motionScale: Float,          // multiplies every duration
    val springMotion: Boolean,

    // signature visuals
    val waveformBars: Int,

    // The visual engine (Backdrop.kt). Each of these is implemented, and
    // each costs something, which is why they are per tier.
    //
    // `backdropBlur` went in 4.8: it blurred the whole screen every frame to
    // hide the steps of a 20px texture, and never fully did. The texture is
    // now smoothed once per song instead (CoverLight.kt), which is smooth on
    // every Android version and costs nothing per frame.
    val livingBackdrop: Boolean,     // the cover as light behind Now Playing and the app
    val backdropDrift: Boolean,      // ...slowly moving
    val backdropTurn: Boolean,       // ...and turning
    val fluidFlow: Boolean,          // ...flowing like liquid (FluidLight.kt, Android 13+)
    val lightField: Int,             // drifting pools of the cover's tones (needs drift)
    val grain: Boolean,              // anti-banding grain over the backdrop
    // Reactive artwork (BeatPulse.kt): 2 the kick - the cover's spring,
    // rings, edge light, backdrop breath; 3 the whole kit - a harder punch
    // and a deeper shadow on the kick, snare rings and the flash, hi-hat
    // glints; 4 the cover deforms like a speaker cone (Phantom.kt, Android
    // 13+, else as 3).
    val reactiveLevel: Int,
    val parallax: Boolean,           // tilt with the phone, specular sheen, backdrop as a deeper layer
    val lyricFocus: Boolean,         // depth-of-field blur on lyric lines away from the sung one
    val lyricGlow: Boolean,          // accent glow on the sung line
    val topRefresh: Boolean          // ask for the panel's top refresh rate in Now Playing
)

fun profileFor(level: QualityLevel): QualityProfile = when (level) {
    // Premium until 4.8. A 4 GB phone runs this on Auto now: the per-frame
    // blur that made it expensive is gone, the screens behind it no longer
    // redraw with it, and if frames still drop the monitor lightens it
    // (relieved below) instead of leaving it struggling.
    QualityLevel.ENHANCED -> QualityProfile(
        level = level,
        artScale = 1.25f,
        ambientGradient = true, dynamicArtLighting = true,
        gutter = 16.dp, rowPadV = 12.dp, rowArt = 56.dp, rowArtCorner = 14,
        motionScale = 1.10f, springMotion = true,
        waveformBars = 72,
        livingBackdrop = true, backdropDrift = true, backdropTurn = false, fluidFlow = false,
        lightField = 3, grain = true,
        reactiveLevel = 2, parallax = false, lyricFocus = true, lyricGlow = false, topRefresh = false
    )
    // Ultra until 4.8, and its parallax now has depth: the backdrop moves
    // behind the tilting cover as its own layer.
    QualityLevel.PREMIUM -> QualityProfile(
        level = level,
        artScale = 1.50f,
        ambientGradient = true, dynamicArtLighting = true,
        gutter = 16.dp, rowPadV = 14.dp, rowArt = 60.dp, rowArtCorner = 16,
        motionScale = 1.20f, springMotion = true,
        waveformBars = 96,
        livingBackdrop = true, backdropDrift = true, backdropTurn = true, fluidFlow = false,
        lightField = 4, grain = true,
        reactiveLevel = 3, parallax = true, lyricFocus = true, lyricGlow = true, topRefresh = true
    )
    // New in 4.8: the cover flows. Below Android 13 the flow cannot run and
    // the backdrop turns as Premium's does.
    QualityLevel.ULTRA -> QualityProfile(
        level = level,
        artScale = 1.50f,
        ambientGradient = true, dynamicArtLighting = true,
        gutter = 16.dp, rowPadV = 14.dp, rowArt = 64.dp, rowArtCorner = 18,
        motionScale = 1.20f, springMotion = true,
        waveformBars = 120,
        livingBackdrop = true, backdropDrift = true, backdropTurn = true, fluidFlow = true,
        lightField = 5, grain = true,
        reactiveLevel = 4, parallax = true, lyricFocus = true, lyricGlow = true, topRefresh = true
    )
}

/** How many lighter steps [relieved] has. */
const val MAX_RELIEF = 3

/**
 * [this] with its costliest pieces taken off, [steps] at a time, for the
 * frame monitor to use at the floor tier where there is no tier below to
 * step down to. In order of what they cost per frame:
 *   1  the flow and the turn, and the per-line blur on lyrics
 *   2  the light field
 *   3  the drift: the backdrop holds still, as Enhanced did before 4.8
 * The design - layout, colour, edge light, the cover's light - stays.
 */
fun QualityProfile.relieved(steps: Int): QualityProfile {
    if (steps <= 0) return this
    var p = copy(fluidFlow = false, backdropTurn = false, lyricFocus = false)
    if (steps >= 2) p = p.copy(lightField = 0)
    if (steps >= 3) p = p.copy(backdropDrift = false)
    return p
}

val LocalQuality = staticCompositionLocalOf { profileFor(QualityLevel.ENHANCED) }
