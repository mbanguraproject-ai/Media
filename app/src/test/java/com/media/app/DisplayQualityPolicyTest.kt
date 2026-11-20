package com.media.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the rendering policy used by Now Playing.
 *
 * These tests intentionally exercise the existing policy only. They do not
 * instantiate Media3, the playback service, audio processors, or Compose UI.
 */
class DisplayQualityPolicyTest {

    @Test
    fun autoUsesDetectedCeiling() {
        assertEquals(QualityLevel.ENHANCED, resolveLevel(QualityMode.AUTO, QualityLevel.ENHANCED))
        assertEquals(QualityLevel.PREMIUM, resolveLevel(QualityMode.AUTO, QualityLevel.PREMIUM))
        assertEquals(QualityLevel.ULTRA, resolveLevel(QualityMode.AUTO, QualityLevel.ULTRA))
    }

    @Test
    fun manualQualitySelectionIsPreserved() {
        assertEquals(QualityLevel.ENHANCED, resolveLevel(QualityMode.ENHANCED, QualityLevel.ULTRA))
        assertEquals(QualityLevel.PREMIUM, resolveLevel(QualityMode.PREMIUM, QualityLevel.ENHANCED))
        assertEquals(QualityLevel.ULTRA, resolveLevel(QualityMode.ULTRA, QualityLevel.ENHANCED))
    }

    @Test
    fun everyQualityTierHasACompleteRenderingProfile() {
        QualityLevel.values().forEach { level ->
            val profile = profileFor(level)
            assertEquals(level, profile.level)
            assertTrue(profile.artScale > 0f)
            assertTrue(profile.waveformBars > 0)
            assertTrue(profile.motionScale > 0f)
            assertTrue(profile.gutter.value >= 0f)
            assertTrue(profile.rowArt.value > 0f)
            assertTrue(profile.reactiveLevel in 0..4)
            assertTrue(profile.lightField >= 0)
        }
    }

    @Test
    fun reliefOnlyRemovesExpensiveVisualEffects() {
        val original = profileFor(QualityLevel.ULTRA)
        val relieved = original.relieved(3)

        assertEquals(original.level, relieved.level)
        assertEquals(original.artScale, relieved.artScale)
        assertEquals(original.gutter, relieved.gutter)
        assertEquals(original.rowArt, relieved.rowArt)
        assertEquals(original.waveformBars, relieved.waveformBars)
        assertEquals(original.ambientGradient, relieved.ambientGradient)
        assertEquals(original.dynamicArtLighting, relieved.dynamicArtLighting)
        assertEquals(original.reactiveLevel, relieved.reactiveLevel)
        assertFalse(relieved.fluidFlow)
        assertFalse(relieved.backdropTurn)
        assertFalse(relieved.lyricFocus)
        assertEquals(0, relieved.lightField)
        assertFalse(relieved.backdropDrift)
    }

    @Test
    fun nonPositiveReliefLeavesProfileUnchanged() {
        val original = profileFor(QualityLevel.PREMIUM)
        assertEquals(original, original.relieved(0))
        assertEquals(original, original.relieved(-1))
    }

    @Test
    fun unknownStoredQualityFallsBackToAuto() {
        assertEquals(QualityMode.AUTO, QualityMode.fromName(null))
        assertEquals(QualityMode.AUTO, QualityMode.fromName("unknown"))
        assertEquals(QualityMode.ULTRA, QualityMode.fromName("ULTRA"))
    }
}
