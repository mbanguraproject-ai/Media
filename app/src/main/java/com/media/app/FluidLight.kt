package com.media.app

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap

// ============================================================================
//  FLUID LIGHT - Ultra, Android 13 and later
//
//  The cover as liquid light. A GPU shader samples the smoothed cover
//  (CoverLight.kt) through a warp field that moves slowly, so the cover's own
//  colours stretch, fold and drift into one another instead of one picture
//  panning and turning. Two layers of flow, the second warped by the first,
//  under one slow turn of the whole field; with Reactive artwork the bass
//  pushes the flow further for as long as it hits.
//
//  It loops without a seam: every frequency is a whole multiple of the loop's
//  phase, so the field at the end of a loop is the field at its start
//  (checked by rendering both: they differ by rounding, at most 1/255).
//
//  Cost: one full-screen pass of eight sines and cosines and one texture read
//  per pixel. A shader the driver refuses, or any error building it, turns the
//  flow off for that surface and it draws as Premium does; nothing here can
//  stop the app.
//
//  Android 13 is where AGSL shaders arrived (RuntimeShader). Below it, Ultra
//  turns the cover as Premium does.
// ============================================================================

private const val FLUID_AGSL = """
uniform shader cover;
uniform float2 coverSize;
uniform float2 room;
uniform float phase;
uniform float energy;

const float TAU = 6.2831853;

half4 main(float2 fragCoord) {
    float side = max(room.x, room.y) * 1.35;
    float2 p = (fragCoord - room * 0.5) / side;
    float a = TAU * phase;
    float c = cos(a);
    float s = sin(a);
    p = float2(c * p.x - s * p.y, s * p.x + c * p.y);
    float k = 0.075 + 0.04 * energy;
    float2 q = float2(sin(6.0 * p.y + 2.0 * a) + sin(4.0 * p.x - 3.0 * a),
                      cos(5.0 * p.x - 2.0 * a) + cos(7.0 * p.y + a));
    float2 r = float2(sin(5.0 * (p.y + k * q.y) + 3.0 * a),
                      cos(6.0 * (p.x + k * q.x) - 2.0 * a));
    float2 w = p + k * q + 0.6 * k * r;
    return cover.eval((w + 0.5) * coverSize);
}
"""

/** One loop of the flow, including one full turn. */
internal const val FLUID_LOOP_MS = 180_000

/** Whether this Android can run the flow at all. */
val fluidSupported: Boolean
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * One flowing cover. Each surface that flows keeps its own, and the room
 * keeps two, so a song and the one it replaces can flow in the same frame.
 */
class FluidLight {
    // Typed Any so this class never names RuntimeShader below Android 13.
    private var impl: Any? = null
    private var failed = false

    /**
     * The brush that paints [cover] flowing at [phase] (0..1 over a loop)
     * across an area of [room], or null where the flow cannot run - the
     * caller then draws the cover the Premium way.
     */
    fun brush(cover: ImageBitmap, room: Size, phase: Float, energy: Float): Brush? {
        if (failed || !fluidSupported || room.width <= 0f || room.height <= 0f) return null
        return try {
            val shader = (impl as? FluidShader) ?: FluidShader().also { impl = it }
            shader.update(cover, room, phase, energy)
        } catch (t: Throwable) {
            failed = true
            impl = null
            null
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class FluidShader {
    // Compiled here; a driver that rejects it throws, and FluidLight turns
    // the flow off for good.
    private val shader = RuntimeShader(FLUID_AGSL)
    private val brush = ShaderBrush(shader)
    private var source: ImageBitmap? = null

    fun update(cover: ImageBitmap, room: Size, phase: Float, energy: Float): Brush {
        if (cover !== source) {
            // Mirrored, so the warp can reach past the cover's edge without a
            // seam, and linear: inside a shader the paint's filtering does not
            // apply, and without this the 128px texture would show its pixels.
            val input = BitmapShader(cover.asAndroidBitmap(), Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            input.filterMode = BitmapShader.FILTER_MODE_LINEAR
            shader.setInputShader("cover", input)
            shader.setFloatUniform("coverSize", cover.width.toFloat(), cover.height.toFloat())
            source = cover
        }
        shader.setFloatUniform("room", room.width, room.height)
        shader.setFloatUniform("phase", phase)
        shader.setFloatUniform("energy", energy.coerceIn(0f, 1f))
        return brush
    }
}
