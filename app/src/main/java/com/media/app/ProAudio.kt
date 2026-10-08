package com.media.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import java.nio.ByteBuffer

// ============================================================================
//  PRO AUDIO - the playback half of Via Pro, inside PlaybackService
//
//  Everything here acts on the player itself, so it applies however playback
//  is driven: the app, the notification, the lock screen, a Bluetooth button.
//
//  BALANCE / MONO   An audio processor in the player's chain. It mixes the
//                   two channels per sample, live: a change applies to the
//                   next buffer, no restart. It handles 16-bit and float PCM
//                   in stereo and steps aside for anything else. Like speed,
//                   it cannot act on high-resolution files that take the
//                   float output path (see PlaybackService), and Settings
//                   says so.
//
//  SOFT PLAY/PAUSE  The session's player is wrapped: pause fades the volume
//                   down over a fifth of a second and then pauses; play
//                   starts silent and fades up. The volume it found is put
//                   back exactly, so the sleep timer's own fade is undisturbed.
//
//  SMART RESUME     In the same wrapper. Resuming a recording longer than ten
//                   minutes after a pause of 30 seconds or more steps back
//                   5 seconds, 10 after five minutes, 15 after an hour - so
//                   a lecture, a book or a recitation picks up where your
//                   attention left it. Music under ten minutes is untouched.
//
//  A-B LOOP         A PlayerMessage at B, which the player delivers on its
//                   own clock exactly when playback reaches B, every time:
//                   no polling, no drift. It seeks to A until the section has
//                   played as many times as asked, then lets go.
// ============================================================================

/**
 * Mono and left-right balance for a stereo stream. Pure sample arithmetic,
 * kept apart from the processor so it can be checked off the device.
 */
object StereoMix {
    /** Left and right gains for a balance in -1..1. Centre is 1 and 1. */
    fun gains(balance: Float): Pair<Float, Float> {
        val b = balance.coerceIn(-1f, 1f)
        return (if (b > 0f) 1f - b else 1f) to (if (b < 0f) 1f + b else 1f)
    }

    /** Mixes little-endian 16-bit stereo frames from [input] into [out]. */
    fun mix16(input: ByteBuffer, out: ByteBuffer, mono: Boolean, balance: Float) {
        val (lg, rg) = gains(balance)
        while (input.remaining() >= 4) {
            var l = readShortLE(input).toFloat()
            var r = readShortLE(input).toFloat()
            if (mono) { val m = (l + r) * 0.5f; l = m; r = m }
            writeShortLE(out, clip16(l * lg))
            writeShortLE(out, clip16(r * rg))
        }
    }

    /** The same for 32-bit float stereo frames in native order. */
    fun mixFloat(input: ByteBuffer, out: ByteBuffer, mono: Boolean, balance: Float) {
        val (lg, rg) = gains(balance)
        while (input.remaining() >= 8) {
            var l = input.float
            var r = input.float
            if (mono) { val m = (l + r) * 0.5f; l = m; r = m }
            out.putFloat(l * lg)
            out.putFloat(r * rg)
        }
    }

    private fun readShortLE(b: ByteBuffer): Short {
        val lo = b.get().toInt() and 0xFF
        val hi = b.get().toInt()
        return ((hi shl 8) or lo).toShort()
    }

    private fun writeShortLE(b: ByteBuffer, v: Short) {
        val i = v.toInt()
        b.put((i and 0xFF).toByte())
        b.put(((i shr 8) and 0xFF).toByte())
    }

    private fun clip16(v: Float): Short = v.coerceIn(-32768f, 32767f).toInt().toShort()
}

@UnstableApi
class BalanceProcessor : BaseAudioProcessor() {
    @Volatile var mono = false
    @Volatile var balance = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val handled = inputAudioFormat.channelCount == 2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        // Always active for what it handles, so switching mono or balance on
        // mid-song needs no reconfiguration; when both are neutral it copies.
        return if (handled) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val m = mono
        val b = balance
        when {
            !m && b == 0f -> out.put(inputBuffer)
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT -> StereoMix.mix16(inputBuffer, out, m, b)
            else -> {
                inputBuffer.order(java.nio.ByteOrder.nativeOrder())
                StereoMix.mixFloat(inputBuffer, out, m, b)
            }
        }
        // Whatever could not form a whole frame is consumed too; the sink
        // only ever hands whole frames, so this is nothing in practice.
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}

/** How far smart resume steps back after a pause of [pausedMs]. 0 = not at all. */
fun smartRewindMs(pausedMs: Long, durationMs: Long): Long = when {
    durationMs < 10 * 60_000L -> 0L
    pausedMs >= 60 * 60_000L -> 15_000L
    pausedMs >= 5 * 60_000L -> 10_000L
    pausedMs >= 30_000L -> 5_000L
    else -> 0L
}

/**
 * The player the media session hands out: soft play/pause and smart resume
 * on top of the real one. Settings are read live from [pro].
 */
@UnstableApi
class ProPlayer(
    private val exo: ExoPlayer,
    private val pro: () -> Boolean,
    private val settings: () -> ProSettings
) : ForwardingPlayer(exo) {

    private val main = Handler(Looper.getMainLooper())
    private var fading: Runnable? = null
    /** The volume to return to after a fade; null when no fade is running. */
    private var restoreTo: Float? = null
    private var pausedAt = 0L

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Any pause counts - a tap, a call, headphones unplugged.
                if (!isPlaying && !exo.playWhenReady) pausedAt = SystemClock.elapsedRealtime()
            }
        })
    }

    override fun play() {
        val s = settings()
        val on = pro()
        if (on && s.smartResume && pausedAt != 0L) {
            val back = smartRewindMs(SystemClock.elapsedRealtime() - pausedAt, exo.duration.coerceAtLeast(0L))
            if (back > 0L) exo.seekTo((exo.currentPosition - back).coerceAtLeast(0L))
        }
        pausedAt = 0L
        if (on && s.softPause && !exo.isPlaying) {
            val target = endFade()
            exo.volume = 0f
            super.play()
            ramp(0f, target, FADE_IN_MS) { }
        } else {
            endFade()
            super.play()
        }
    }

    override fun pause() {
        if (pro() && settings().softPause && exo.isPlaying) {
            val from = endFade()
            ramp(from, 0f, FADE_OUT_MS) {
                super.pause()
                exo.volume = from
            }
        } else {
            endFade()
            super.pause()
        }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    /** Stops any fade, puts its volume back, and returns the volume to use. */
    private fun endFade(): Float {
        fading?.let { main.removeCallbacks(it) }
        fading = null
        val v = restoreTo ?: exo.volume
        restoreTo = null
        exo.volume = v
        return v
    }

    private fun ramp(from: Float, to: Float, ms: Long, done: () -> Unit) {
        restoreTo = if (to == 0f) from else to
        val start = SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                val t = ((SystemClock.uptimeMillis() - start).toFloat() / ms).coerceIn(0f, 1f)
                exo.volume = from + (to - from) * t
                if (t < 1f) {
                    main.postDelayed(this, FRAME_MS)
                } else {
                    fading = null
                    restoreTo = null
                    done()
                }
            }
        }
        fading = step
        main.post(step)
    }

    /**
     * Stops a fade in progress and puts its volume back. Not `release()`:
     * that is Player's own, and the session's release of this wrapper is
     * what releases the real player.
     */
    fun cancelFades() {
        fading?.let { main.removeCallbacks(it) }
        fading = null
        restoreTo?.let { exo.volume = it }
        restoreTo = null
    }

    private companion object {
        const val FADE_OUT_MS = 200L
        const val FADE_IN_MS = 260L
        const val FRAME_MS = 16L
    }
}

/** Runs the A-B loop on [exo] from the loop state it is given. */
@UnstableApi
class LoopRunner(private val exo: ExoPlayer, private val onFinished: () -> Unit) {
    private var message: PlayerMessage? = null
    private var played = 0

    fun apply(loop: AbLoop?) {
        message?.cancel()
        message = null
        played = 0
        val b = loop?.bMs ?: return
        val item = exo.currentMediaItem ?: return
        if (item.mediaId != loop.mediaId || b <= loop.aMs) return
        val a = loop.aMs
        message = exo.createMessage { _, _ ->
            played++
            if (loop.repeats > 0 && played >= loop.repeats) onFinished()
            else exo.seekTo(a)
        }
            .setPosition(exo.currentMediaItemIndex, b)
            .setLooper(Looper.getMainLooper())
            // Delivered every time playback passes B, not just the first.
            .setDeleteAfterDelivery(false)
            .send()
        // Set while already past B: go back to A now.
        if (exo.currentPosition > b) exo.seekTo(a)
    }

    fun release() {
        message?.cancel()
        message = null
    }
}
