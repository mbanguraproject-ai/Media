package com.media.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext



// A two-stop diagonal gradient derived from the base tint — a designed surface,
// not a flat block. The second stop is a lightened shift of the same hue.

// ============================================================================
//  ART LOADING
//  Previously every CoverArt instance spun up its own MediaMetadataRetriever
//  and decoded the embedded picture at FULL resolution with no cache. A 3000px
//  cover is ~36MB in ARGB_8888, so a scrolling list of them was an OOM waiting
//  to happen. Now: bounded LRU cache, sampled decode, and release() in finally.
// ============================================================================
private object ArtCache {
    // ~1/8th of heap, clamped to something sane on both tiny and large devices.
    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 8)
        .toInt().coerceIn(4096, 32768)

    private val hits = object : LruCache<String, ImageBitmap>(maxKb) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }

    // Files with no art at all: remembered so scrolling past them repeatedly
    // doesn't re-run the (expensive) extraction every single time.
    private val misses = LruCache<String, Boolean>(512)

    fun get(key: String): ImageBitmap? = hits.get(key)
    fun put(key: String, bmp: ImageBitmap) { hits.put(key, bmp) }
    fun isKnownMiss(key: String): Boolean = misses.get(key) != null
    fun markMiss(key: String) { misses.put(key, true) }
}

// Largest power-of-two subsample that still leaves us at or above target px.
private fun sampleSizeFor(bounds: BitmapFactory.Options, target: Int): Int {
    var sample = 1
    val h = bounds.outHeight
    val w = bounds.outWidth
    if (h > target || w > target) {
        while ((h / 2) / sample >= target && (w / 2) / sample >= target) sample *= 2
    }
    return sample
}

private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, target) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

private fun decodeSampled(context: Context, uri: Uri, target: Int): Bitmap? {
    val cr = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, target) }
    return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}

private fun downscale(bmp: Bitmap, target: Int): Bitmap {
    val longest = maxOf(bmp.width, bmp.height)
    if (longest <= target) return bmp
    val ratio = target.toFloat() / longest
    val scaled = Bitmap.createScaledBitmap(
        bmp, (bmp.width * ratio).toInt().coerceAtLeast(1),
        (bmp.height * ratio).toInt().coerceAtLeast(1), true
    )
    if (scaled !== bmp) bmp.recycle()
    return scaled
}

// Cheapest source first, file extraction last.
internal fun loadArt(context: Context, item: AppMediaItem, target: Int): ImageBitmap? {
    // 1. Audio: the MediaStore album-art URI. Far cheaper than opening the file.
    if (item.type == MediaType.AUDIO) {
        item.artworkUri?.let { uri ->
            runCatching { decodeSampled(context, uri, target) }.getOrNull()
                ?.let { return it.asImageBitmap() }
        }
    }
    // 2. Video on API 29+: the system thumbnail is pre-sized and already cached
    //    by the platform.
    if (item.type == MediaType.VIDEO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            context.contentResolver.loadThumbnail(item.uri, Size(target, target), null)
        }.getOrNull()?.let { return it.asImageBitmap() }
    }
    // 3. Last resort: pull it out of the file. release() lives in finally so a
    //    throw in setDataSource can't leak the native handle.
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, item.uri)
        val embedded = retriever.embeddedPicture
        val bmp = when {
            embedded != null -> decodeSampled(embedded, target)
            // 1s in avoids the black first frame; OPTION_CLOSEST_SYNC is fast.
            item.type == MediaType.VIDEO ->
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { downscale(it, target) }
            else -> null
        }
        bmp?.asImageBitmap()
    } catch (t: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

@Composable
fun CoverArt(
    item: AppMediaItem,
    modifier: Modifier = Modifier,
    corner: Int = 12,
    targetPx: Int = 384
) {
    val context = LocalContext.current
    val key = "${item.uri}@$targetPx"
    // Seed straight from cache so a re-scroll shows art with no loading flash.
    var art by remember(key) { mutableStateOf(ArtCache.get(key)) }
    var loading by remember(key) { mutableStateOf(art == null && !ArtCache.isKnownMiss(key)) }

    LaunchedEffect(key) {
        if (art != null || ArtCache.isKnownMiss(key)) { loading = false; return@LaunchedEffect }
        loading = true
        val loaded = withContext(Dispatchers.IO) { loadArt(context, item, targetPx) }
        if (loaded != null) ArtCache.put(key, loaded) else ArtCache.markMiss(key)
        art = loaded
        loading = false
    }

    Box(
        modifier = modifier.clip(RoundedCornerShape(corner.dp)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = art
        if (loading) {
            // §21: the placeholder IS the final fallback, same seed. If the file
            // turns out to have no art there is no swap at all; if it does, the
            // incoming image is already tonally matched.
            GenerativeArtwork(item, Modifier.fillMaxSize())
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // §5: composed generative identity, not a letter in a colored box.
            GenerativeArtwork(item, Modifier.fillMaxSize())
        }

    }
}
