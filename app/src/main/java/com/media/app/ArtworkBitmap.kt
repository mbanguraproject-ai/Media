package com.media.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
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

/** The Artwork.kt tile, rendered to a Bitmap. Same geometry, same values. */
object DefaultArtwork {
    private const val TOP = 0xFF232631.toInt()
    private const val BOTTOM = 0xFF131419.toInt()
    private const val SHEEN = 0x12FFFFFF   // white @ 7%
    private const val MARK = 0x47FFFFFF    // white @ 28%
    private const val EDGE = 0x0FFFFFFF    // white @ 6%

    private val tiles = LruCache<String, Bitmap>(4)

    fun render(size: Int = TARGET, isVideo: Boolean = false): Bitmap {
        val key = "$size/$isVideo"
        tiles.get(key)?.let { return it }
        return draw(size, isVideo).also { tiles.put(key, it) }
    }

    private fun draw(s: Int, isVideo: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val f = s.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = f * 0.5f
        val cy = f * 0.5f

        p.shader = LinearGradient(0f, 0f, f, f, TOP, BOTTOM, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, f, f, p)

        val sr = f * 0.92f
        p.shader = RadialGradient(f * 0.18f, f * 0.10f, sr, SHEEN, 0x00FFFFFF,
            Shader.TileMode.CLAMP)
        c.drawCircle(f * 0.18f, f * 0.10f, sr, p)
        p.shader = null

        p.color = MARK
        if (isVideo) {
            val h = f * 0.30f
            val w = f * 0.26f
            val left = cx - w * 0.42f
            c.drawPath(Path().apply {
                moveTo(left, cy - h * 0.5f)
                lineTo(left + w, cy)
                lineTo(left, cy + h * 0.5f)
                close()
            }, p)
        } else {
            val headR = f * 0.105f
            val hx = cx - f * 0.070f
            val hy = cy + f * 0.150f
            val stemW = f * 0.030f
            val stemX = hx + headR - stemW
            val stemTop = cy - f * 0.205f
            c.drawCircle(hx, hy, headR, p)
            c.drawRoundRect(RectF(stemX, stemTop, stemX + stemW, hy),
                stemW * 0.5f, stemW * 0.5f, p)
            c.drawRoundRect(RectF(stemX, stemTop, stemX + f * 0.165f, stemTop + f * 0.058f),
                f * 0.029f, f * 0.029f, p)
        }

        p.color = EDGE
        p.style = Paint.Style.STROKE
        p.strokeWidth = (f * 0.006f).coerceAtLeast(1f)
        c.drawRect(0f, 0f, f, f, p)
        return bmp
    }
}

/**
 * Resolves session artwork: embedded picture -> MediaStore thumbnail ->
 * default tile. The last step is why the notification can no longer be blank.
 */
@UnstableApi
class AuraBitmapLoader(private val context: Context) : BitmapLoader {

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
                ?: DefaultArtwork.render(isVideo = false)
        })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        io.submit(Callable { resolve(uri) })

    private fun resolve(uri: Uri): Bitmap {
        val key = uri.toString()
        cache.get(key)?.let { return it }
        val isVideo = runCatching {
            context.contentResolver.getType(uri)?.startsWith("video") == true
        }.getOrDefault(false)
        val bmp = embedded(uri) ?: thumbnail(uri) ?: DefaultArtwork.render(isVideo = isVideo)
        cache.put(key, bmp)
        return bmp
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
