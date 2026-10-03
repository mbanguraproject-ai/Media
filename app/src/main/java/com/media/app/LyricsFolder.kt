package com.media.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.media.app.Matching.isUsable

// ============================================================================
//  LYRICS FOLDER
//
//  Android 11 and later hide other apps' non-media files from MediaStore, so
//  a .lrc collection on the phone is invisible to a plain scan - which is
//  why the sidecar lookup "usually finds nothing". The one door the system
//  leaves open is a folder the listener picks: they grant it once in
//  Settings, the grant survives restarts, and every .lrc (and .txt) inside it,
//  however deep, becomes a lyrics source.
//
//  A file matches a track by name, in this order:
//      the track's own file name        "03 Laho.mp3"  -> "03 Laho.lrc"
//      "Artist - Title"                  "Shallipopi - Laho.lrc"
//      "Title - Artist"                  "Laho - Shallipopi.lrc"
//      "Title"                           "Laho.lrc"
//  compared after Matching.normalize, so case, accents and punctuation do
//  not get in the way. A .lrc beats a .txt of the same name.
// ============================================================================

object LyricsFolder {
    private const val PREFS = "lyrics"
    private const val KEY = "folder"
    private const val MAX_FILES = 20_000
    private const val MAX_DEPTH = 6

    @Volatile private var indexed: Uri? = null
    @Volatile private var index: Map<String, Uri> = emptyMap()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? =
        prefs(context).getString(KEY, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** A readable name for Settings: the folder's last path segment. */
    fun label(context: Context): String? = folder(context)?.let { uri ->
        runCatching {
            DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/')
                .ifBlank { context.getString(R.string.lyrics_folder_selected) }
        }.getOrDefault(context.getString(R.string.lyrics_folder_selected))
    }

    /** Stores the picked tree and keeps read access across restarts. */
    fun set(context: Context, tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        folder(context)?.takeIf { it != tree }?.let { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        prefs(context).edit().putString(KEY, tree.toString()).apply()
        indexed = null
        LyricsStore.init(context)
    }

    fun clear(context: Context) {
        folder(context)?.let { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        prefs(context).edit().remove(KEY).apply()
        indexed = null
        index = emptyMap()
    }

    /** The lyrics file for [item], as text, or null. Blocking; call on IO. */
    fun find(context: Context, item: AppMediaItem, tags: FileTags?): String? {
        val tree = folder(context) ?: return null
        val map = indexFor(context, tree)
        if (map.isEmpty()) return null
        val title = item.title.takeIf { it.isUsable() } ?: tags?.title
        val artist = item.artist.takeIf { it.isUsable() } ?: tags?.artist
        val keys = buildList {
            fileBase(context, item)?.let { add(it) }
            if (title != null && artist != null) {
                add("$artist - $title")
                add("$title - $artist")
                add("${Matching.primaryArtist(artist)} - ${Matching.searchTitle(title)}")
            }
            if (title != null) {
                add(title)
                add(Matching.searchTitle(title))
            }
        }
        val uri = keys.asSequence().map { Matching.normalize(it) }.filter { it.isNotEmpty() }
            .mapNotNull { map[it] }.firstOrNull() ?: return null
        return LyricsEngine.readText(context, uri)
    }

    private fun fileBase(context: Context, item: AppMediaItem): String? = runCatching {
        context.contentResolver.query(item.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }?.substringBeforeLast('.')
    }.getOrNull()

    /** Built once per folder per process: a walk of the tree, names only. */
    @Synchronized
    private fun indexFor(context: Context, tree: Uri): Map<String, Uri> {
        if (indexed == tree) return index
        val out = HashMap<String, Uri>()
        val lrc = HashSet<String>()
        runCatching {
            val cr = context.contentResolver
            val stack = ArrayDeque<Pair<String, Int>>()
            stack.addLast(DocumentsContract.getTreeDocumentId(tree) to 0)
            var seen = 0
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            while (stack.isNotEmpty() && seen < MAX_FILES) {
                val (docId, depth) = stack.removeLast()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                cr.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext() && seen < MAX_FILES) {
                        seen++
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < MAX_DEPTH) stack.addLast(id to depth + 1)
                            continue
                        }
                        val isLrc = name.endsWith(".lrc", ignoreCase = true)
                        if (!isLrc && !name.endsWith(".txt", ignoreCase = true)) continue
                        val key = Matching.normalize(name.substringBeforeLast('.'))
                        if (key.isEmpty()) continue
                        // A .lrc wins over a .txt of the same name.
                        if (isLrc || key !in lrc) {
                            out[key] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            if (isLrc) lrc.add(key)
                        }
                    }
                }
            }
        }
        index = out
        indexed = tree
        return out
    }

    /** How many lyrics files the folder holds, for Settings. Blocking. */
    fun count(context: Context): Int {
        val tree = folder(context) ?: return 0
        return indexFor(context, tree).size
    }
}
