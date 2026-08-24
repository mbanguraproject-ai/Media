package com.media.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

// ============================================================================
//  BASS ENVELOPE ANALYSIS
//
//  Precomputes a bass-energy curve for a track so artwork can pulse in time
//  with the music.
//
//  WHY NOT android.media.audiofx.Visualizer: it is the obvious tool and it
//  requires RECORD_AUDIO. Android classifies output capture as recording, with
//  no exemption. This app declares no INTERNET and claims nothing leaves the
//  device; asking for the microphone to animate a picture would make that claim
//  false and would be flagged at review. So: decode the file ourselves.
//
//  The result is deterministic (a track always pulses identically), needs no
//  permission beyond the media read we already hold, and costs a single array
//  lookup per frame at render time — which is what §37's 60fps target needs.
// ============================================================================

object EnvelopeAnalyzer {

    /** Windows per second. 20Hz = 50ms resolution: smooth, and small to store. */
    const val HZ = 20

    /** Bass band. Kick drums and bass lines live below this. */
    private const val BASS_CUTOFF_HZ = 200f

    private const val TIMEOUT_US = 10_000L

    /**
     * Decodes [uri] and returns normalised bass energy per 1/[HZ] second,
     * or null if the file can't be decoded.
     *
     * Cancellable: skipping tracks mid-analysis aborts the decode rather than
     * burning CPU on a track nobody is listening to any more.
     */
    suspend fun analyze(
        context: Context,
        uri: Uri,
        maxDurationMs: Long = 10 * 60 * 1000L
    ): FloatArray? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) return@withContext null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var pcmFloat = false

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var windowSize = (sampleRate / HZ).coerceAtLeast(1)
            var alpha = onePoleAlpha(BASS_CUTOFF_HZ, sampleRate)
            val maxWindows = (maxDurationMs / 1000L * HZ).toInt()

            val out = ArrayList<Float>(2048)
            var lp = 0f              // low-pass state, carried across buffers
            var acc = 0.0            // running sum of squares in this window
            var accCount = 0

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Real streams change format mid-decode. Re-read
                        // everything the maths depends on.
                        val nf = codec.outputFormat
                        sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        pcmFloat = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            nf.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                        windowSize = (sampleRate / HZ).coerceAtLeast(1)
                        alpha = onePoleAlpha(BASS_CUTOFF_HZ, sampleRate)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* keep pumping */ }

                    else -> if (outIdx >= 0) {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val ordered = buf.order(ByteOrder.nativeOrder())

                            if (pcmFloat) {
                                val fb = ordered.asFloatBuffer()
                                val n = fb.remaining()
                                var i = 0
                                while (i < n) {
                                    var sum = 0f; var c = 0
                                    while (c < channels && i < n) { sum += fb.get(i); i++; c++ }
                                    lp += alpha * (sum / channels - lp)
                                    acc += (lp * lp).toDouble(); accCount++
                                    if (accCount >= windowSize) {
                                        out.add(sqrt(acc / accCount).toFloat())
                                        acc = 0.0; accCount = 0
                                    }
                                }
                            } else {
                                val sb = ordered.asShortBuffer()
                                val n = sb.remaining()
                                var i = 0
                                while (i < n) {
                                    var sum = 0f; var c = 0
                                    while (c < channels && i < n) {
                                        sum += sb.get(i) / 32768f; i++; c++
                                    }
                                    lp += alpha * (sum / channels - lp)
                                    acc += (lp * lp).toDouble(); accCount++
                                    if (accCount >= windowSize) {
                                        out.add(sqrt(acc / accCount).toFloat())
                                        acc = 0.0; accCount = 0
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        // Cap runaway analysis on very long files.
                        if (out.size >= maxWindows) outputDone = true
                    }
                }
            }

            if (out.isEmpty()) null else normalise(out.toFloatArray())
        } catch (t: Throwable) {
            // Unsupported codec, DRM, missing file — all just mean "no pulse".
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** One-pole low-pass coefficient for [cutoffHz] at [sampleRate]. */
    private fun onePoleAlpha(cutoffHz: Float, sampleRate: Int): Float {
        val dt = 1f / sampleRate
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        return dt / (rc + dt)
    }

    /**
     * Scale to 0..1 against the 95th percentile rather than the maximum: one
     * transient shouldn't flatten the whole track. The 0.7 gamma lifts quiet
     * passages so the pulse stays visible without the loud parts clipping.
     */
    private fun normalise(arr: FloatArray): FloatArray {
        val sorted = arr.copyOf().also { it.sort() }
        val p95 = sorted[(sorted.size * 0.95f).toInt().coerceIn(0, sorted.lastIndex)]
        val scale = if (p95 > 1e-6f) 1f / p95 else 1f
        for (i in arr.indices) arr[i] = (arr[i] * scale).coerceIn(0f, 1f).pow(0.7f)
        return arr
    }
}
