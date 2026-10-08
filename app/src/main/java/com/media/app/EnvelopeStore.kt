package com.media.app

import android.util.LruCache
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ============================================================================
//  ENVELOPE CACHE
//
//  Analysis is LAZY: a track is only ever analysed when it actually plays.
//  A background sweep would have the pulse ready instantly for anything you
//  return to, but it costs idle CPU and battery on every device for tracks the
//  user may never open again. A one-off warmup on first play is the cheaper
//  trade; if the warmup ever reads as a flaw we can add the sweep behind it.
// ============================================================================

/**
 * LITTLE_ENDIAN, not nativeOrder: the blob outlives the process and could in
 * principle be read back on a differently-ordered device (restore, backup).
 * Fixing the order costs nothing and removes the whole class of problem.
 */
internal fun FloatArray.toEnvelopeBlob(): ByteArray {
    val bb = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    bb.asFloatBuffer().put(this)
    return bb.array()
}

internal fun ByteArray.toEnvelopeFloats(): FloatArray {
    val fb = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(fb.remaining()).also { fb.get(it) }
}

// Small: only the current track and whatever you just skipped past need to be
// resident. The DB is the real cache.
private val envelopeMemory = LruCache<Long, SoundShape>(8)

/**
 * The sound shape of [item] (SoundShape.kt), or null while it is still being
 * computed (or if the file can't be decoded). Stored interleaved, three
 * floats a frame, in the same envelope table; a row whose `hz` is not
 * EnvelopeAnalyzer.STORED_TAG is from an older analysis and is redone.
 *
 * LaunchedEffect keys on the media id, so skipping tracks cancels an
 * in-progress analysis for free — EnvelopeAnalyzer checks ensureActive().
 */
@Composable
fun rememberSoundShape(item: AppMediaItem?): SoundShape? {
    val context = LocalContext.current
    // Seed from memory so returning to a recent track pulses immediately.
    var envelope by remember(item?.id) {
        mutableStateOf(item?.id?.let { envelopeMemory.get(it) })
    }

    LaunchedEffect(item?.id, item?.dateModified) {
        val target = item ?: return@LaunchedEffect
        if (envelopeMemory.get(target.id) != null) return@LaunchedEffect

        val db = OverrideDatabase.get(context)
        val dao = db.envelopeDao()

        val cached = withContext(Dispatchers.IO) { runCatching { dao.get(target.id) }.getOrNull() }
        if (cached != null &&
            cached.hz == EnvelopeAnalyzer.STORED_TAG &&
            cached.dateModified == target.dateModified
        ) {
            val shape = withContext(Dispatchers.Default) {
                SoundShape.fromInterleaved(cached.data.toEnvelopeFloats())
            }
            if (shape != null) {
                envelopeMemory.put(target.id, shape)
                envelope = shape
                return@LaunchedEffect
            }
        }
        // Stale: the file changed under us, or the analysis resolution did.
        if (cached != null) withContext(Dispatchers.IO) {
            runCatching { dao.delete(target.id) }
        }

        val computed = EnvelopeAnalyzer.analyze(context, target.uri) ?: return@LaunchedEffect
        envelopeMemory.put(target.id, computed)
        envelope = computed
        withContext(Dispatchers.IO) {
            runCatching {
                dao.put(
                    AudioEnvelope(
                        mediaId = target.id,
                        hz = EnvelopeAnalyzer.STORED_TAG,
                        dateModified = target.dateModified,
                        data = computed.interleaved().toEnvelopeBlob()
                    )
                )
            }
        }
    }
    return envelope
}
