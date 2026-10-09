package com.media.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ============================================================================
//  OPEN WITH VIA
//
//  The manifest tells Android that Via opens audio and video (any audio/*,
//  any video/*, and Ogg, which some apps label application/ogg), from a
//  content uri - Downloads, a file manager, a browser's finished download, a
//  chat app's attachment - or a file uri from older apps. So when a file is
//  tapped, Via is in the "Open with" list.
//
//  MainActivity hands the intent here. The file is played at once, whether
//  or not it is in the library and whether or not Via has been given access
//  to the library: the sending app's grant on that one uri is enough. Now
//  Playing opens on it as soon as the app's main screen is up - straight
//  away, or after the first-run screens on a new install.
//
//  The read grant lasts while Via's task does. A sender that offers a
//  persistable grant (Files, Downloads) has it kept for good, so a long
//  video can still be seeked after Via was swiped away.
// ============================================================================

object OpenWith {

    /** A file handed to Via: where it is, what to call it, and whether it is a video. */
    class Request(val uri: Uri, val name: String, val video: Boolean)

    private val _pending = MutableStateFlow<Request?>(null)
    /** The file waiting to be played; the player takes it once it is connected. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private val _showPlayer = MutableStateFlow(false)
    /** True until the main screen has opened Now Playing for an opened file. */
    val showPlayer: StateFlow<Boolean> = _showPlayer.asStateFlow()

    /** Takes a VIEW intent for an audio or video file. False for anything else. */
    fun take(context: Context, intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        if (uri.scheme != "content" && uri.scheme != "file") return false
        val resolver = context.contentResolver
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val mime = (intent.type ?: runCatching { resolver.getType(uri) }.getOrNull() ?: guessMime(uri))
            .orEmpty().lowercase()
        // Only what Via plays. The manifest already filters, but a sender can
        // label a file anything.
        val video = mime.startsWith("video/") || mime == "application/x-matroska"
        val audio = mime.startsWith("audio/") || mime == "application/ogg" || mime == "application/x-ogg" ||
            mime == "application/x-flac"
        if (!video && !audio) return false
        _pending.value = Request(uri, displayName(context, uri), video)
        _showPlayer.value = true
        return true
    }

    /** The player has started [r]. */
    fun played(r: Request) {
        if (_pending.value === r) _pending.value = null
    }

    /** Now Playing is open on it. */
    fun shown() {
        _showPlayer.value = false
    }

    /** The file's own name, without its extension: what to show until its tags are read. */
    private fun displayName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
        return name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: name.orEmpty()
    }

    private fun guessMime(uri: Uri): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.lowercase() ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    /**
     * A stable id for a file outside the library, which has no MediaStore id:
     * negative, so it can never meet a library id, and the same for the same
     * file, so its cached analysis is its own.
     */
    fun idFor(uri: String): Long = -((uri.hashCode().toLong() and 0x7fffffffL) + 1L)
}

/**
 * Whether [item] is a video: a MediaStore video uri says /video/, and a file
 * opened with Via is marked in its metadata.
 */
fun isVideoItem(item: androidx.media3.common.MediaItem): Boolean =
    item.localConfiguration?.uri?.toString()?.contains("/video/") == true ||
        item.mediaMetadata.mediaType == androidx.media3.common.MediaMetadata.MEDIA_TYPE_VIDEO

/**
 * The library (MediaStore) id in [uri], or null for a file from anywhere
 * else. Only MediaStore's own uris end in a library id: a file opened from
 * Downloads can end in a number too (content://...downloads.../document/1234),
 * and taken for library track 1234 it would borrow that track's cover, play
 * count and saved place.
 */
fun libraryId(uri: android.net.Uri?): Long? =
    if (uri != null && uri.authority == android.provider.MediaStore.AUTHORITY) uri.lastPathSegment?.toLongOrNull() else null
