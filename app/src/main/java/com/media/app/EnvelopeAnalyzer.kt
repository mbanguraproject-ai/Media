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

// ============================================================================
//  SOUND ANALYSIS
//
//  Precomputes a track's sound shape (SoundShape.kt): kick, snare and hi-hat
//  energy fifty times a second, so artwork moves in time with the music.
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

    /**
     * Windows per second. 50Hz = 20ms, so a drum hit lands within a frame of
     * the sound. Until 5.1 it was 20Hz of bass alone; a cached analysis at
     * the old rate no longer matches this and is redone on next play.
     */
    const val HZ = SoundShape.HZ

    /**
     * What a stored analysis is tagged with (the table's `hz` column): the
     * rate, plus 1000 x the format version. 2 = encoder lead-in skipped
     * (5.3). Anything else is redone on next play.
     */
    const val STORED_TAG = 2 * 1000 + HZ

    /** MediaFormat.KEY_ENCODER_DELAY, which only has a name from Android 11. */
    private const val KEY_ENCODER_DELAY = "encoder-delay"

    private const val TIMEOUT_US = 10_000L

    /**
     * Decodes [uri] and returns its sound shape - each band's energy per
     * 1/[HZ] second, normalised - or null if the file can't be decoded.
     *
     * Cancellable: skipping tracks mid-analysis aborts the decode rather than
     * burning CPU on a track nobody is listening to any more.
     */
    suspend fun analyze(
        context: Context,
        uri: Uri,
        maxDurationMs: Long = 10 * 60 * 1000L
    ): SoundShape? = withContext(Dispatchers.IO) {
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

            val meter = BandMeter(sampleRate, HZ)
            // The encoder's lead-in (an MP3's LAME delay, an AAC's priming):
            // silence the decoder hands back first and the player trims off.
            // Counted here, it put every hit 25-50ms late against what you
            // hear. Skipped, the two timelines start on the same sample.
            var skip = if (format.containsKey(KEY_ENCODER_DELAY)) format.getInteger(KEY_ENCODER_DELAY).coerceAtLeast(0) else 0
            val maxWindows = (maxDurationMs / 1000L * HZ).toInt()

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
                        meter.configure(sampleRate)
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
                                    if (skip > 0) skip-- else meter.push(sum / channels)
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
                                    if (skip > 0) skip-- else meter.push(sum / channels)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        // Cap runaway analysis on very long files.
                        if (meter.frames >= maxWindows) outputDone = true
                    }
                }
            }

            meter.shape()
        } catch (t: Throwable) {
            // Unsupported codec, DRM, missing file — all just mean "no pulse".
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
