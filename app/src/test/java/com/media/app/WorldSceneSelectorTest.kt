package com.media.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldSceneSelectorTest {

    @Test
    fun missingOrAchromaticArtworkUsesMinimalScene() {
        assertEquals(
            WorldSceneFamily.MINIMAL,
            WorldSceneSelector.select(ArtworkColorSignature.fromArgb(emptyList())).family
        )
        assertEquals(
            WorldSceneFamily.MINIMAL,
            WorldSceneSelector.select(ArtworkColorSignature.fromArgb(listOf(0xff777777.toInt()))).family
        )
    }

    @Test
    fun paletteHueSelectsExpectedVisualTreatment() {
        val cases = listOf(
            0xffff0000.toInt() to WorldSceneFamily.DREAM,
            0xffff8000.toInt() to WorldSceneFamily.URBAN,
            0xff00ff00.toInt() to WorldSceneFamily.NATURE,
            0xff0000ff.toInt() to WorldSceneFamily.OCEAN,
            0xff8000ff.toInt() to WorldSceneFamily.COSMIC
        )

        cases.forEach { (argb, expected) ->
            val signature = ArtworkColorSignature.fromArgb(listOf(argb))
            assertEquals(expected, WorldSceneSelector.select(signature).family)
        }
    }

    @Test
    fun selectionIsDeterministicAndProfileValuesAreBounded() {
        val signature = ArtworkColorSignature.fromArgb(
            listOf(0xff2448d8.toInt(), 0xff3924a8.toInt(), 0xff1d73c8.toInt())
        )
        val first = WorldSceneSelector.select(signature)
        val second = WorldSceneSelector.select(signature)

        assertEquals(first, second)
        assertNotNull(first.family)
        assertTrue(first.depth in 0f..1f)
        assertTrue(first.horizon in 0f..1f)
        assertTrue(first.glow in 0f..1f)
        assertTrue(first.motion in 0f..1f)
        assertTrue(first.particleDensity in 0f..1f)
    }

    @Test
    fun sceneReactiveLevelKeepsSilenceStillAndBoundsBassResponse() {
        val expressive = WorldSceneSelector.select(
            ArtworkColorSignature.fromArgb(listOf(0xffff0000.toInt()))
        )
        assertEquals(0f, sceneReactiveLevel(0f, expressive), 0f)
        assertEquals(0f, sceneReactiveLevel(-1f, expressive), 0f)
        assertEquals(1f, sceneReactiveLevel(2f, expressive), 0f)
        assertTrue(sceneReactiveLevel(0.5f, expressive) > 0.5f)

        val minimal = WorldSceneSelector.select(
            ArtworkColorSignature.fromArgb(listOf(0xff777777.toInt()))
        )
        assertTrue(sceneReactiveLevel(0.5f, minimal) in 0f..1f)
    }

    @Test
    fun sceneAtmosphereIsBoundedAndMinimalArtworkStaysNeutral() {
        WorldSceneFamily.values().forEach { family ->
            val scene = when (family) {
                WorldSceneFamily.DREAM -> WorldSceneSelector.select(ArtworkColorSignature.fromArgb(listOf(0xffff0000.toInt())))
                WorldSceneFamily.URBAN -> WorldSceneSelector.select(ArtworkColorSignature.fromArgb(listOf(0xffff8000.toInt())))
                WorldSceneFamily.NATURE -> WorldSceneSelector.select(ArtworkColorSignature.fromArgb(listOf(0xff00ff00.toInt())))
                WorldSceneFamily.OCEAN -> WorldSceneSelector.select(ArtworkColorSignature.fromArgb(listOf(0xff0000ff.toInt())))
                WorldSceneFamily.COSMIC -> WorldSceneSelector.select(ArtworkColorSignature.fromArgb(listOf(0xff8000ff.toInt())))
                WorldSceneFamily.MINIMAL -> WorldSceneSelector.select(ArtworkColorSignature.fromArgb(listOf(0xff777777.toInt())))
            }
            val atmosphere = sceneAtmosphere(scene)
            assertTrue(atmosphere.horizonFraction in 0.18f..0.82f)
            assertTrue(atmosphere.bandFraction in 0.10f..0.20f)
            assertTrue(atmosphere.opacity in 0f..0.12f)
            assertTrue(atmosphere.bloomRadiusFraction in 0.36f..0.56f)
            assertTrue(atmosphere.bloomOpacity in 0f..0.045f)
            if (family == WorldSceneFamily.MINIMAL) {
                assertEquals(0f, atmosphere.opacity, 0f)
                assertEquals(0f, atmosphere.bloomOpacity, 0f)
            }
        }
    }

    @Test
    fun nowPlayingBloomIsBoundedSilentAndDisabledForMinimalScenes() {
        WorldSceneFamily.values().forEach { family ->
            val argb = when (family) {
                WorldSceneFamily.DREAM -> 0xffff0000.toInt()
                WorldSceneFamily.URBAN -> 0xffff8000.toInt()
                WorldSceneFamily.NATURE -> 0xff00ff00.toInt()
                WorldSceneFamily.OCEAN -> 0xff0000ff.toInt()
                WorldSceneFamily.COSMIC -> 0xff8000ff.toInt()
                WorldSceneFamily.MINIMAL -> 0xff777777.toInt()
            }
            val scene = WorldSceneSelector.select(ArtworkColorSignature.fromArgb(listOf(argb)))
            assertTrue(sceneBloomOpacity(scene, 0f) in 0f..0.05f)
            assertTrue(sceneBloomOpacity(scene, 1f) in 0f..0.05f)
            assertTrue(sceneBloomRadiusFraction(scene) in 0.42f..0.58f)
            if (family == WorldSceneFamily.MINIMAL) {
                assertEquals(0f, sceneBloomOpacity(scene, 0f), 0f)
                assertEquals(0f, sceneBloomOpacity(scene, 1f), 0f)
            } else {
                assertEquals(0f, sceneBloomOpacity(scene, 0f) - scene.glow * 0.025f, 0.0001f)
                assertTrue(sceneBloomOpacity(scene, 1f) > sceneBloomOpacity(scene, 0f))
            }
        }
    }

    @Test
    fun sceneParallaxFactorIsBoundedAndUsesArtworkDepth() {
        val factors = WorldSceneFamily.values().map { family ->
            val argb = when (family) {
                WorldSceneFamily.DREAM -> 0xffff0000.toInt()
                WorldSceneFamily.URBAN -> 0xffff8000.toInt()
                WorldSceneFamily.NATURE -> 0xff00ff00.toInt()
                WorldSceneFamily.OCEAN -> 0xff0000ff.toInt()
                WorldSceneFamily.COSMIC -> 0xff8000ff.toInt()
                WorldSceneFamily.MINIMAL -> 0xff777777.toInt()
            }
            sceneParallaxFactor(
                WorldSceneSelector.select(ArtworkColorSignature.fromArgb(listOf(argb)))
            )
        }
        factors.forEach { assertTrue(it in 0.08f..0.18f) }
        assertTrue(factors.distinct().size > 1)
    }

    @Test
    fun visibleAuraRemainsPresentForEverySceneIncludingMissingArtwork() {
        WorldSceneFamily.values().forEach { family ->
            val argb = when (family) {
                WorldSceneFamily.DREAM -> 0xffff0000.toInt()
                WorldSceneFamily.URBAN -> 0xffff8000.toInt()
                WorldSceneFamily.NATURE -> 0xff00ff00.toInt()
                WorldSceneFamily.OCEAN -> 0xff0000ff.toInt()
                WorldSceneFamily.COSMIC -> 0xff8000ff.toInt()
                WorldSceneFamily.MINIMAL -> 0xff777777.toInt()
            }
            val scene = WorldSceneSelector.select(ArtworkColorSignature.fromArgb(listOf(argb)))
            assertTrue(sceneVisibleAuraOpacity(scene) in 0.18f..0.26f)
        }
        val missingArtwork = WorldSceneSelector.select(ArtworkColorSignature.fromArgb(emptyList()))
        assertTrue(sceneVisibleAuraOpacity(missingArtwork) >= 0.18f)
    }

    @Test
    fun nearRedHueWrapsAcrossZeroWithoutChangingClassification() {
        val signature = ArtworkColorSignature.fromArgb(
            listOf(0xffff0000.toInt(), 0xffff0033.toInt())
        )
        assertEquals(WorldSceneFamily.DREAM, WorldSceneSelector.select(signature).family)
    }
}
