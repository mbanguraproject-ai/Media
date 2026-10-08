package com.media.app

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

// ============================================================================
//  PHANTOM - Ultra's reactive artwork, Android 13 and later
//
//  The cover deforms like a speaker cone. A GPU shader re-samples the cover
//  through a displacement field built from what the music is doing right now
//  (BeatPulse.kt):
//
//    THE CONE   the kick's spring. Out, the middle of the cover swells
//               towards you while its edge stays put, like a woofer's cone
//               in its surround; on the rebound it sinks back past rest.
//    RIPPLES    each kick sends a ring wave out from the centre across the
//               surface, decaying as it travels.
//    SHIMMER    the hi-hats set a fine standing ripple trembling over it.
//    LIGHT      the surface is lit from the top left by its own slope, so the
//               swell has a bright shoulder and a shaded one and reads as a
//               shape in depth, not a zoom.
//    SPLIT      on the hardest kicks the colour channels part at the rim for
//               a frame or two.
//
//  The edge of the cover never moves (the displacement falls to zero at the
//  rim), so the deformation stays inside the rounded tile.
//
//  Cost: three texture reads and a handful of sines per pixel of the cover,
//  only while it is moving - at rest the effect is removed. A driver that
//  rejects the shader turns it off for good and Ultra draws as Premium.
// ============================================================================

private const val PHANTOM_AGSL = """
uniform shader content;
uniform float2 size;
uniform float push;
uniform float ripple;
uniform float age;
uniform float shimmer;
uniform float time;
uniform float split;

half4 main(float2 p) {
    float2 c = size * 0.5;
    float R = min(size.x, size.y) * 0.5;
    float2 d = (p - c) / R;
    float r = length(d);
    float2 dir = r > 0.0001 ? d / r : float2(0.0, 0.0);

    // The cone: zero at the rim, strongest in the middle.
    float f = clamp(1.0 - r * r, 0.0, 1.0);
    float bulge = push * 0.13 * f * f;
    float slope = push * 0.13 * (-4.0 * r * f);

    // A ring wave out from the centre after a kick.
    float w = r - age * 1.9;
    float env = ripple * exp(-w * w * 22.0) * exp(-age * 2.6) * f;
    float wave = env * sin(w * 26.0) * 0.028;
    slope += env * cos(w * 26.0) * 26.0 * 0.028;

    // Hi-hats: a fine tremble over the surface.
    float fine = shimmer * 0.0032 * sin(d.x * 61.0 + time * 37.0) * sin(d.y * 53.0 - time * 41.0);

    float2 q = c + (d * (1.0 - bulge) - dir * (wave + fine)) * R;

    // Lit from the top left by the surface's own slope.
    float2 g = dir * slope;
    float light = clamp(1.0 + 1.6 * dot(g, float2(0.55, 0.83)), 0.72, 1.38);

    half4 col;
    if (split > 0.001) {
        float2 o = dir * split * 0.012 * R * r;
        half4 cr = content.eval(q + o);
        half4 cg = content.eval(q);
        half4 cb = content.eval(q - o);
        col = half4(cr.r, cg.g, cb.b, cg.a);
    } else {
        col = content.eval(q);
    }
    return half4(col.rgb * light, col.a);
}
"""

/** Whether this Android can run the cone at all. */
val phantomSupported: Boolean
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** One cover's cone. Typed Any so this class never names RuntimeShader below Android 13. */
class PhantomCone {
    private var impl: Any? = null
    private var failed = false

    /** False once the shader has failed: the caller then draws the Premium way. */
    val available: Boolean get() = phantomSupported && !failed

    /** The effect for this frame, or null when at rest or unavailable. */
    fun effect(width: Float, height: Float, beat: BeatState): androidx.compose.ui.graphics.RenderEffect? {
        if (!available || width <= 0f || height <= 0f) return null
        val now = beat.frameNanos
        if (now == 0L) return null
        val age = if (beat.kickNanos == 0L) 9f else (now - beat.kickNanos) / 1e9f
        val push = beat.cone
        val shimmer = (beat.high * 0.9f).coerceIn(0f, 1f)
        val ripple = if (age < 1.3f) beat.kickPower else 0f
        if (kotlin.math.abs(push) < 0.01f && ripple == 0f && shimmer < 0.02f) return null
        return try {
            val s = (impl as? PhantomShader) ?: PhantomShader().also { impl = it }
            s.effect(
                width, height,
                push = push.coerceIn(-0.6f, 1.3f),
                ripple = ripple,
                age = age,
                shimmer = shimmer,
                time = (now % 600_000_000_000L) / 1e9f,
                split = ((push - 0.75f) * 2.4f).coerceIn(0f, 1f)
            )
        } catch (t: Throwable) {
            failed = true
            impl = null
            null
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class PhantomShader {
    // Compiled here; a driver that rejects it throws, and PhantomCone turns
    // the cone off for good.
    private val shader = RuntimeShader(PHANTOM_AGSL)

    fun effect(
        width: Float, height: Float,
        push: Float, ripple: Float, age: Float, shimmer: Float, time: Float, split: Float
    ): androidx.compose.ui.graphics.RenderEffect {
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("push", push)
        shader.setFloatUniform("ripple", ripple)
        shader.setFloatUniform("age", age)
        shader.setFloatUniform("shimmer", shimmer)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("split", split)
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/**
 * The cone on whatever this modifies, read in the draw phase only: it reruns
 * on each frame of the beat clock without recomposing anything.
 */
fun Modifier.phantomCone(cone: PhantomCone, beat: BeatState, on: Boolean): Modifier =
    if (!on) this else graphicsLayer {
        renderEffect = cone.effect(size.width, size.height, beat)
    }
