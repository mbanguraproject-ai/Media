package com.media.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.geometry.Size as TileSize
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// ============================================================================
//  NOTIFICATION / LOCK-SCREEN ARTWORK
//
//  THE BUG: MediaRepository set every audio track's artworkUri to
//  content://media/external/audio/albumart/<albumId> - a legacy, undocumented
//  MediaStore path that scoped storage broke on Android 10+. PlayerViewModel
//  handed that straight to MediaMetadata.setArtworkUri().
//
//  In-app art still worked, because CoverArt.loadArt() tries that uri, fails
//  silently, then falls back to MediaMetadataRetriever.embeddedPicture. The
//  notification had NO fallback - media3's default loader tried the dead uri,
//  got nothing, and drew a blank square.
//
//  It looked per-song. It was per-ALBUM: albums that happened to be registered
//  in the legacy albumart table worked, everything else went white.
//
//  THE FIX: the session resolves artwork itself, in the same order as the
//  in-app loader, and ends at the default tile rather than at nothing. No code
//  path is left that yields a blank square.
// ============================================================================

private const val TARGET = 512

/**
 * The Artwork.kt tile as a Bitmap, for the notification and lock screen. It
 * runs the very same drawDefaultArtwork on a Compose draw scope over the
 * bitmap, so the two cannot differ. (Until 5.1 this was a second, hand-copied
 * drawing, which is how the notification kept the old 4.x note.)
 */
object DefaultArtwork {
    private val tiles = LruCache<String, Bitmap>(6)

    fun render(size: Int = TARGET, kind: ArtworkKind = ArtworkKind.MUSIC): Bitmap {
        val key = "$size/$kind"
        tiles.get(key)?.let { return it }
        return draw(size, kind).also { tiles.put(key, it) }
    }

    private fun draw(s: Int, kind: ArtworkKind): Bitmap {
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), TileSize(s.toFloat(), s.toFloat())
        ) {
            drawDefaultArtwork(kind)
        }
        return bmp
    }

    /** The kind PlayerViewModel puts on the artwork uri, else from the mime type. */
    fun kindOf(uri: Uri, isVideo: Boolean): ArtworkKind =
        uri.getQueryParameter(KIND_PARAM)?.let { k -> ArtworkKind.entries.firstOrNull { it.name == k } }
            ?: if (isVideo) ArtworkKind.VIDEO else ArtworkKind.MUSIC

    /** Query parameter on a session artwork uri naming its ArtworkKind. */
    const val KIND_PARAM = "kind"
}

/**
 * Resolves session artwork: repaired cover -> embedded picture -> MediaStore
 * thumbnail -> default tile. The last step is why the notification can no
 * longer be blank.
 */
@UnstableApi
class ViaBitmapLoader(private val context: Context) : BitmapLoader {

    private val io: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    // Media3 asks for the same uri repeatedly (the notification rebuilds on
    // every play/pause). Without this, each one re-opens the file.
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    override fun supportsMimeType(mimeType: String): Boolean =
        mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        io.submit(Callable {
            BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: DefaultArtwork.render()
        })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        io.submit(Callable { resolve(uri) })

    private fun resolve(uri: Uri): Bitmap {
        // The key keeps the ?art= stamp, so a repaired cover is a cache miss
        // and the notification redraws; the file itself is the bare uri.
        val key = uri.toString()
        cache.get(key)?.let { return it }
        val base = if (uri.query != null) uri.buildUpon().clearQuery().build() else uri
        val isVideo = runCatching {
            context.contentResolver.getType(base)?.startsWith("video") == true
        }.getOrDefault(false)
        val bmp = repaired(base) ?: embedded(base) ?: thumbnail(base)
            ?: DefaultArtwork.render(kind = DefaultArtwork.kindOf(uri, isVideo))
        cache.put(key, bmp)
        return bmp
    }

    /**
     * A cover fixed in the app. Decoded HERE, in our own process, and handed
     * to the session as a Bitmap - which is why it reaches the notification
     * when a file:// uri to the same image never could.
     */
    private fun repaired(uri: Uri): Bitmap? {
        val id = libraryId(uri) ?: return null
        val file = ArtworkStore.fileFor(id) ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { downscale(it) }
        }.getOrNull()
    }

    private fun embedded(uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.embeddedPicture?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { downscale(it) }
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun thumbnail(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            context.contentResolver.loadThumbnail(uri, Size(TARGET, TARGET), null)
        }.getOrNull()
    }

    private fun downscale(b: Bitmap): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= TARGET) return b
        val k = TARGET.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            b, (b.width * k).toInt().coerceAtLeast(1),
            (b.height * k).toInt().coerceAtLeast(1), true
        )
        if (scaled !== b) b.recycle()
        return scaled
    }
}
