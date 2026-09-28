package com.media.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream

// ============================================================================
//  THE ONE PICTURE THAT IS THEIRS
//
//  A picked image is COPIED into app-private storage, not referenced by URI.
//  Two reasons, both of which would otherwise show up as a blank avatar weeks
//  later and be impossible to reproduce:
//
//    A photo-picker URI is a temporary grant. It dies with the process unless
//    persistable permission is taken, and the photo picker does not hand out
//    persistable grants at all.
//
//    Even a persistable URI points at a file the user can delete, move to a
//    different account, or lose when they switch phones.
//
//  512px of JPEG is about 60KB in filesDir. It is backed up with the app,
//  it cannot rot, and deleting it is one file operation.
//
//  Stored square and centre-cropped at save time rather than at draw time, so
//  the crop is decided once instead of on every recomposition.
// ============================================================================
object Avatar {
    private const val NAME = "avatar.jpg"
    private const val SIDE = 512

    private fun file(context: Context) = File(context.filesDir, NAME)

    /** Decodes the stored picture, or null when there is none. IO thread. */
    fun load(context: Context): ImageBitmap? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
        } catch (t: Throwable) {
            null
        }
    }

    /** Copies [uri] in, centre-cropped square and capped at SIDE px. IO thread. */
    fun save(context: Context, uri: Uri): Boolean {
        return try {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            // Subsample on the way in. A 12MP phone photo is 48MB decoded at
            // full size, which is an OOM on a 2GB device for a 104dp circle.
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false

            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(
                src, (src.width - side) / 2, (src.height - side) / 2, side, side
            )
            if (square !== src) src.recycle()
            val out =
                if (side > SIDE) Bitmap.createScaledBitmap(square, SIDE, SIDE, true) else square
            if (out !== square) square.recycle()

            // Write to a temp file and rename. A crash mid-encode would
            // otherwise leave a half-written JPEG that decodes to garbage.
            val tmp = File(context.filesDir, NAME + ".tmp")
            FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            out.recycle()
            tmp.renameTo(file(context))
        } catch (t: Throwable) {
            false
        }
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (t: Throwable) {
        }
    }
}
