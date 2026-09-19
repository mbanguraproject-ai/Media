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
//  AURA DISPLAY QUALITY
//
//  Hardware capability -> device profiling -> a complete design profile.
//
//  The blueprint's hard rule is that a quality level is a COMPLETE design, not
//  a bag of effect switches bolted onto one UI. So QualityProfile carries
//  design tokens - artwork resolution, how far surfaces separate from the
//  floor, corner scale, layout density, motion - and not just booleans. A tier
//  changes what the app LOOKS like, not only what it renders on top.
//
//  It also refuses to pretend: these levels describe Aura's rendering profile.
//  They do not claim to change the panel. An LCD running Ultra is an LCD.
// ============================================================================

enum class QualityLevel(val label: String, val goal: String) {
    ESSENTIAL("Essential", "Maximum compatibility and battery efficiency"),
    STANDARD("Standard", "Balanced visual quality and performance"),
    ENHANCED("Enhanced", "Richer visuals with moderate effects"),
    PREMIUM("Premium", "High-end visuals and smooth motion"),
    ULTRA("Ultra", "Maximum visual fidelity and effects")
}

/** What the user picked. AUTO follows the detected ceiling. */
enum class QualityMode(val label: String) {
    AUTO("Auto"),
    ESSENTIAL("Essential"), STANDARD("Standard"), ENHANCED("Enhanced"),
    PREMIUM("Premium"), ULTRA("Ultra");

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
            var s = when {
                totalRamMb < 2048 -> 0
                totalRamMb < 3072 -> 1
                totalRamMb < 4096 -> 2
                totalRamMb < 6144 -> 3
                totalRamMb < 8192 -> 4
                else -> 5
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
    cap.isLowRamDevice -> QualityLevel.ESSENTIAL
    cap.score <= 1 -> QualityLevel.ESSENTIAL
    cap.score <= 3 -> QualityLevel.STANDARD
    cap.score <= 5 -> QualityLevel.ENHANCED
    cap.score <= 7 -> QualityLevel.PREMIUM
    else -> QualityLevel.ULTRA
}

/** A manual pick is honoured even above the ceiling: the doc allows the override. */
fun resolveLevel(mode: QualityMode, ceiling: QualityLevel): QualityLevel = when (mode) {
    QualityMode.AUTO -> ceiling
    QualityMode.ESSENTIAL -> QualityLevel.ESSENTIAL
    QualityMode.STANDARD -> QualityLevel.STANDARD
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

    // depth
    val surfaceLift: Float,          // how far surfaces step off the floor
    val shadow: Dp,
    val blur: Dp,                    // 0.dp = off
    val ambientGradient: Boolean,
    val dynamicArtLighting: Boolean,

    // geometry — tiers are different layouts, not one layout with effects
    val cornerScale: Float,
    val densityScale: Float,         // row height / padding multiplier

    // motion
    val motionScale: Float,          // multiplies every duration
    val springMotion: Boolean,
    val refreshAware: Boolean,

    // signature visuals
    val waveformBars: Int,
    val particles: Boolean
)

fun profileFor(level: QualityLevel): QualityProfile = when (level) {
    // Compact on purpose: more rows per screen, less scrolling, flatter
    // corners. It should read as a deliberately dense player, not as Ultra
    // with the effects switched off.
    QualityLevel.ESSENTIAL -> QualityProfile(
        level = level,
        artScale = 0.60f,
        surfaceLift = 1.0f, shadow = 0.dp, blur = 0.dp,
        ambientGradient = false, dynamicArtLighting = false,
        cornerScale = 0.70f, densityScale = 0.92f,
        motionScale = 0.55f, springMotion = false, refreshAware = false,
        waveformBars = 24, particles = false
    )
    QualityLevel.STANDARD -> QualityProfile(
        level = level,
        artScale = 0.85f,
        surfaceLift = 1.0f, shadow = 2.dp, blur = 0.dp,
        ambientGradient = false, dynamicArtLighting = false,
        cornerScale = 1.0f, densityScale = 1.0f,
        motionScale = 0.85f, springMotion = false, refreshAware = true,
        waveformBars = 40, particles = false
    )
    QualityLevel.ENHANCED -> QualityProfile(
        level = level,
        artScale = 1.0f,
        surfaceLift = 1.10f, shadow = 6.dp, blur = 12.dp,
        ambientGradient = true, dynamicArtLighting = false,
        cornerScale = 1.10f, densityScale = 1.04f,
        motionScale = 1.0f, springMotion = true, refreshAware = true,
        waveformBars = 56, particles = false
    )
    QualityLevel.PREMIUM -> QualityProfile(
        level = level,
        artScale = 1.25f,
        surfaceLift = 1.20f, shadow = 12.dp, blur = 24.dp,
        ambientGradient = true, dynamicArtLighting = true,
        cornerScale = 1.20f, densityScale = 1.08f,
        motionScale = 1.0f, springMotion = true, refreshAware = true,
        waveformBars = 72, particles = true
    )
    QualityLevel.ULTRA -> QualityProfile(
        level = level,
        artScale = 1.50f,
        surfaceLift = 1.30f, shadow = 18.dp, blur = 36.dp,
        ambientGradient = true, dynamicArtLighting = true,
        cornerScale = 1.30f, densityScale = 1.12f,
        motionScale = 1.0f, springMotion = true, refreshAware = true,
        waveformBars = 96, particles = true
    )
}

val LocalQuality = staticCompositionLocalOf { profileFor(QualityLevel.STANDARD) }
