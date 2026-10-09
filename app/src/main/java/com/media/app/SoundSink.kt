package com.media.app

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ============================================================================
//  SOUND SINK - where Depth and Space run
//
//  The player's audio sink, with the stage (SoundStage.kt) in front of it.
//  Every decoded buffer passes through here before media3 sees it, which is
//  the one point every format shares: media3's own audio processors are
//  skipped for 24-bit and float audio (the hi-res path, see PlaybackService),
//  so a processor there would have left exactly the best files untouched.
//
//  Stereo PCM in 16, 24 or 32-bit integer or float is read into floats,
//  processed, and written back in the SAME format, so media3 configures
//  nothing differently. 16-bit output is dithered (TPDF) while the stage is
//  working, so the room's quiet tail stays smooth instead of grainy.
//  Anything else - mono, surround, compressed passthrough - goes straight
//  through. When the stage has nothing to do, the decoder's own buffer is
//  handed on: not a single sample is rewritten.
//
//  The sink contract: a buffer the sink only partly took is offered again,
//  the same object, until it is all taken. This sink keeps the processed copy
//  for that buffer and offers the copy again; what the inner sink took of the
//  copy is mirrored onto the caller's buffer.
// ============================================================================

@UnstableApi
class SoundSink(sink: AudioSink, private val stage: SoundStage) : ForwardingAudioSink(sink) {

    /** The PCM encoding being processed, or INVALID: pass through. */
    private var encoding = C.ENCODING_INVALID
    private var left = FloatArray(0)
    private var right = FloatArray(0)
    private var out: ByteBuffer = ByteBuffer.allocateDirect(0)

    // The caller's buffer while the inner sink has taken only part of it,
    // where it started, and what was handed on for it (the copy, or itself).
    private var pendingIn: ByteBuffer? = null
    private var pendingStart = 0
    private var pendingOut: ByteBuffer? = null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val pcm = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType &&
            inputFormat.channelCount == 2 && inputFormat.pcmEncoding in HANDLED &&
            inputFormat.sampleRate > 0
        encoding = if (pcm) inputFormat.pcmEncoding else C.ENCODING_INVALID
        // Same rate: the stage keeps its state, so gapless stays gapless.
        if (pcm) stage.configure(inputFormat.sampleRate)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // The rest of a buffer the sink took only part of: offer the same again.
        if (pendingIn === buffer) return forward(buffer, presentationTimeUs, encodedAccessUnitCount)
        pendingIn = null
        pendingOut = null
        val enc = encoding
        val bytes = bytesPer(enc)
        if (enc == C.ENCODING_INVALID || bytes == 0 || buffer.remaining() < 2 * bytes) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        val start = buffer.position()
        val frames = buffer.remaining() / (2 * bytes)
        val used = frames * 2 * bytes
        if (left.size < frames) { left = FloatArray(frames); right = FloatArray(frames) }
        read(buffer, start, frames, enc)
        stage.process(left, right, frames)
        pendingStart = start
        if (!stage.changed) {
            // Nothing done: the decoder's own buffer goes on, untouched.
            pendingOut = buffer
        } else {
            if (out.capacity() < buffer.remaining()) {
                out = ByteBuffer.allocateDirect(buffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)
            }
            out.clear()
            write(out, frames, enc)
            // A trailing part-frame (never sent in practice) goes on as it was.
            for (i in start + used until buffer.limit()) out.put(buffer.get(i))
            out.flip()
            pendingOut = out
        }
        return forward(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    private fun forward(input: ByteBuffer, presentationTimeUs: Long, count: Int): Boolean {
        val o = pendingOut ?: input
        val done = super.handleBuffer(o, presentationTimeUs, count)
        if (o !== input) input.position(pendingStart + o.position())
        pendingIn = if (done) null else input
        if (done) pendingOut = null
        return done
    }

    override fun flush() {
        dropPending()
        stage.reset()
        super.flush()
    }

    override fun reset() {
        dropPending()
        stage.reset()
        super.reset()
    }

    private fun dropPending() {
        pendingIn = null
        pendingOut = null
    }

    // ---- PCM in and out ----------------------------------------------------

    private fun read(b: ByteBuffer, start: Int, frames: Int, enc: Int) {
        val src = b.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var p = start
        when (enc) {
            C.ENCODING_PCM_16BIT -> for (i in 0 until frames) {
                left[i] = src.getShort(p) / 32768f
                right[i] = src.getShort(p + 2) / 32768f
                p += 4
            }
            C.ENCODING_PCM_24BIT -> for (i in 0 until frames) {
                left[i] = get24(src, p) / 8388608f
                right[i] = get24(src, p + 3) / 8388608f
                p += 6
            }
            C.ENCODING_PCM_32BIT -> for (i in 0 until frames) {
                left[i] = (src.getInt(p) / 2147483648.0).toFloat()
                right[i] = (src.getInt(p + 4) / 2147483648.0).toFloat()
                p += 8
            }
            C.ENCODING_PCM_FLOAT -> for (i in 0 until frames) {
                left[i] = src.getFloat(p)
                right[i] = src.getFloat(p + 4)
                p += 8
            }
        }
    }

    private fun write(o: ByteBuffer, frames: Int, enc: Int) {
        when (enc) {
            C.ENCODING_PCM_16BIT -> for (i in 0 until frames) {
                o.putShort(to16(left[i]))
                o.putShort(to16(right[i]))
            }
            C.ENCODING_PCM_24BIT -> for (i in 0 until frames) {
                put24(o, (left[i] * 8388608f).roundToInt().coerceIn(-8388608, 8388607))
                put24(o, (right[i] * 8388608f).roundToInt().coerceIn(-8388608, 8388607))
            }
            C.ENCODING_PCM_32BIT -> for (i in 0 until frames) {
                o.putInt(to32(left[i]))
                o.putInt(to32(right[i]))
            }
            C.ENCODING_PCM_FLOAT -> for (i in 0 until frames) {
                o.putFloat(left[i])
                o.putFloat(right[i])
            }
        }
    }

    private fun to16(v: Float): Short =
        (v * 32768f + stage.dither()).roundToInt().coerceIn(-32768, 32767).toShort()

    private fun to32(v: Float): Int =
        (v * 2147483648.0).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private fun get24(b: ByteBuffer, p: Int): Int {
        val v = (b.get(p).toInt() and 0xFF) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or (b.get(p + 2).toInt() shl 16)
        return v
    }

    private fun put24(o: ByteBuffer, v: Int) {
        o.put((v and 0xFF).toByte())
        o.put(((v shr 8) and 0xFF).toByte())
        o.put(((v shr 16) and 0xFF).toByte())
    }

    private companion object {
        val HANDLED = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)

        fun bytesPer(enc: Int): Int = when (enc) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
    }
}
