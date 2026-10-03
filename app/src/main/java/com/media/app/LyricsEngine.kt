package com.media.app

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import com.media.app.Matching.isUsable

// ============================================================================
//  LYRICS
//
//  First hit wins:
//      lyrics you added yourself (a file you picked, or text you pasted)
//      embedded synced (SYLT, or LRC text in the lyrics tag)
//      a .lrc file beside the track
//      a .lrc in the lyrics folder you chose in Settings
//      cached synced
//      online synced: LRCLIB exact, LRCLIB search, LRCLIB search on the
//                     cleaned title and lead artist, then NetEase
//      embedded plain
//      cached plain
//      online plain: LRCLIB, then lyrics.ovh
//
//  One online miss used to be the end; now each source gets its turn, and a
//  plain answer found early is held while the others are asked for synced.
//
//  An online answer is checked against the file - title, artist, album and
//  length - before it is kept, and one that fails is not attached, however
//  good its lyrics look. Timing is only ever claimed when stamps exist: plain
//  lyrics are shown as plain.
//
//  Everything accepted is cached, including "nothing was found", so a track
//  costs one lookup and then works offline for good.
// ============================================================================

// The label is a string resource, shown in the app's language. Stored
// lyrics record the source by name, never by label.
enum class LyricsSource(@androidx.annotation.StringRes val labelRes: Int) {
    EMBEDDED(R.string.lyrics_src_embedded),
    LRC_FILE(R.string.lyrics_src_lrc_file),
    FOLDER(R.string.lyrics_src_folder),
    LRCLIB(R.string.lyrics_src_lrclib),
    NETEASE(R.string.lyrics_src_netease),
    LYRICS_OVH(R.string.lyrics_src_ovh),
    USER(R.string.lyrics_src_user)
}

sealed interface LyricsState {
    object Loading : LyricsState
    data class Synced(
        val lines: List<LyricLine>,
        val source: LyricsSource,
        val offsetMs: Long,
        val confidence: Float? = null
    ) : LyricsState
    data class Plain(val text: String, val source: LyricsSource) : LyricsState
    object Instrumental : LyricsState
    /** [online] says whether an online search is possible from here. */
    data class None(val online: Boolean, val offline: Boolean = false) : LyricsState
}

/** One track's cached answer. [text] is LRC for synced lyrics. */
internal data class LyricsRecord(
    val source: LyricsSource,
    val synced: Boolean,
    val text: String,
    val confidence: Float?,
    val instrumental: Boolean,
    val miss: Boolean,
    val at: Long
)

object LyricsStore {
    private lateinit var dir: File
    @Volatile private var ready = false
    private val offsets = HashMap<Long, Long>()

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            dir = File(context.applicationContext.filesDir, "lyrics").apply { mkdirs() }
            runCatching {
                val f = File(dir, "offsets.json")
                if (f.isFile) {
                    val o = JSONObject(f.readText())
                    o.keys().forEach { k -> k.toLongOrNull()?.let { offsets[it] = o.getLong(k) } }
                }
            }
            ready = true
        }
    }

    @Synchronized fun offset(trackId: Long): Long = offsets[trackId] ?: 0L

    @Synchronized fun setOffset(trackId: Long, ms: Long) {
        if (!ready) return
        if (ms == 0L) offsets.remove(trackId) else offsets[trackId] = ms
        runCatching {
            val o = JSONObject().apply { offsets.forEach { (k, v) -> put(k.toString(), v) } }
            File(dir, "offsets.json").writeText(o.toString())
        }
    }

    internal fun get(trackId: Long): LyricsRecord? {
        if (!ready) return null
        val f = File(dir, "$trackId.json")
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            LyricsRecord(
                source = runCatching { LyricsSource.valueOf(o.getString("source")) }.getOrDefault(LyricsSource.LRCLIB),
                synced = o.optBoolean("synced"),
                text = o.optString("text"),
                confidence = if (o.has("confidence")) o.optDouble("confidence").toFloat() else null,
                instrumental = o.optBoolean("instrumental"),
                miss = o.optBoolean("miss"),
                at = o.optLong("at")
            )
        }.getOrNull()
    }

    internal fun put(trackId: Long, r: LyricsRecord) {
        if (!ready) return
        runCatching {
            val o = JSONObject().apply {
                put("v", 1); put("source", r.source.name); put("synced", r.synced); put("text", r.text)
                r.confidence?.let { put("confidence", it.toDouble()) }
                put("instrumental", r.instrumental); put("miss", r.miss); put("at", r.at)
            }
            val tmp = File(dir, "$trackId.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(File(dir, "$trackId.json"))
        }
    }

    fun forget(trackId: Long) {
        if (ready) File(dir, "$trackId.json").delete()
    }
}

object LyricsEngine {

    /** A "nothing found" answer is trusted this long before asking again. */
    private const val MISS_TTL_MS = 7L * 86_400_000L

    /** LRCLIB's exact lookup is already a signature match; still, verify. */
    private const val GET_MIN = 0.75f

    private val _revision = MutableStateFlow(0)
    /** Bumps when a track's lyrics or offset change from outside the panel. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    suspend fun load(
        context: Context,
        item: AppMediaItem,
        online: Boolean,
        force: Boolean = false
    ): LyricsState = withContext(Dispatchers.IO) {
        val offset = LyricsStore.offset(item.id)

        // 0. The listener's own lyrics outrank everything.
        LyricsStore.get(item.id)?.takeIf { it.source == LyricsSource.USER && !it.miss }?.let { u ->
            if (u.synced) {
                val p = Lrc.parse(u.text)
                if (p.synced) return@withContext LyricsState.Synced(p.lines, LyricsSource.USER, offset, 1f)
            }
            val plain = Lrc.plain(u.text).ifBlank { u.text }
            if (plain.isNotBlank()) return@withContext LyricsState.Plain(plain, LyricsSource.USER)
        }

        val tags = readTags(context, item.uri)

        // 1. Embedded synced.
        tags?.syncedLyrics?.takeIf { it.size >= 3 }?.let {
            return@withContext LyricsState.Synced(it, LyricsSource.EMBEDDED, offset)
        }
        tags?.lyrics?.takeIf { Lrc.looksSynced(it) }?.let { text ->
            val p = Lrc.parse(text)
            if (p.synced) return@withContext LyricsState.Synced(p.lines, LyricsSource.EMBEDDED, offset)
        }

        // 2. A sidecar .lrc.
        sidecar(context, item)?.let { text ->
            val p = Lrc.parse(text)
            if (p.synced) return@withContext LyricsState.Synced(p.lines, LyricsSource.LRC_FILE, offset)
        }

        // 2b. The lyrics folder the listener pointed us at.
        LyricsFolder.find(context, item, tags)?.let { text ->
            val p = Lrc.parse(text)
            if (p.synced) return@withContext LyricsState.Synced(p.lines, LyricsSource.FOLDER, offset)
            val plain = Lrc.plain(text)
            if (plain.isNotBlank()) return@withContext LyricsState.Plain(plain, LyricsSource.FOLDER)
        }

        // 3. Cached synced (and an instrumental verdict).
        var cached = LyricsStore.get(item.id)
        if (force) cached = null
        if (cached != null && !cached.miss) {
            if (cached.instrumental) return@withContext LyricsState.Instrumental
            if (cached.synced) {
                val p = Lrc.parse(cached.text)
                if (p.synced) return@withContext LyricsState.Synced(p.lines, cached.source, offset, cached.confidence)
            }
        }

        // 4. Online synced - once, unless a recent miss says not to bother.
        var offline = false
        val freshMiss = cached?.miss == true && System.currentTimeMillis() - cached.at < MISS_TTL_MS
        val haveCachedPlain = cached != null && !cached.miss && !cached.synced
        if (online && !freshMiss && !haveCachedPlain) {
            when (val r = fetch(item, tags)) {
                is Fetch.Found -> {
                    LyricsStore.put(item.id, r.record)
                    // Anything else showing this track's lyrics re-reads them.
                    _revision.value++
                    cached = r.record
                    if (r.record.instrumental) return@withContext LyricsState.Instrumental
                    if (r.record.synced) {
                        val p = Lrc.parse(r.record.text)
                        if (p.synced) {
                            return@withContext LyricsState.Synced(p.lines, r.record.source, offset, r.record.confidence)
                        }
                    }
                }
                Fetch.Missing -> {
                    val miss = LyricsRecord(LyricsSource.LRCLIB, false, "", null, false, true, System.currentTimeMillis())
                    // Never let a miss erase an earlier plain answer.
                    if (cached == null || cached.miss) LyricsStore.put(item.id, miss)
                }
                Fetch.Offline -> offline = true
            }
        }

        // 5. Embedded plain.
        tags?.lyrics?.let { text ->
            val plain = Lrc.plain(text)
            if (plain.isNotBlank()) return@withContext LyricsState.Plain(plain, LyricsSource.EMBEDDED)
        }

        // 6/7. Cached or just-fetched plain.
        cached?.takeIf { !it.miss && !it.synced && it.text.isNotBlank() }?.let {
            return@withContext LyricsState.Plain(it.text, it.source)
        }

        LyricsState.None(online = !online || freshMiss || offline, offline = offline)
    }

    fun setOffset(trackId: Long, ms: Long) {
        LyricsStore.setOffset(trackId, ms)
        _revision.value++
    }

    /**
     * Lyrics the listener supplied: a .lrc or .txt they picked, or text they
     * pasted. Timed LRC stays synced; anything else is kept as plain text.
     * False when there is nothing usable in it.
     */
    fun setUserLyrics(trackId: Long, text: String): Boolean {
        val t = text.removePrefix("\uFEFF").replace("\r\n", "\n").trim()
        if (t.isEmpty()) return false
        val synced = Lrc.looksSynced(t) && Lrc.parse(t).synced
        if (!synced && Lrc.plain(t).isBlank()) return false
        LyricsStore.put(trackId, LyricsRecord(LyricsSource.USER, synced, t, 1f, false, false, System.currentTimeMillis()))
        _revision.value++
        return true
    }

    /** Drops the listener's lyrics; the normal search order takes over again. */
    fun clearUserLyrics(trackId: Long) {
        LyricsStore.forget(trackId)
        _revision.value++
    }

    /** Reads a picked file as text: UTF-8, BOM stripped, capped at 512KB. */
    fun readText(context: Context, uri: android.net.Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val bytes = s.readBytes()
            if (bytes.size > 512 * 1024) null else String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        }
    }.getOrNull()

    // ------------------------------------------------------------ the fetch

    private sealed interface Fetch {
        class Found(val record: LyricsRecord) : Fetch
        object Missing : Fetch
        object Offline : Fetch
    }

    private fun pick(a: String?, b: String?): String? =
        a?.takeIf { it.isUsable() } ?: b?.takeIf { it.isUsable() }

    private suspend fun fetch(item: AppMediaItem, tags: FileTags?): Fetch {
        // The listener's own correction wins; the file's tags fill gaps.
        val title = pick(item.title, tags?.title) ?: return Fetch.Missing
        val artist = pick(item.artist, tags?.artist) ?: return Fetch.Missing
        val album = pick(item.album, tags?.album)
        val durationMs = item.durationMs.takeIf { it > 0 } ?: tags?.durationMs
        val query = Matching.Query(title, artist, album, durationMs)
        var reached = false
        // A plain answer is held, not returned: a later source may have synced.
        var plain: LyricsRecord? = null
        var instrumental: LyricsRecord? = null

        fun consider(r: LrcLibRecord, s: Float): LyricsRecord? {
            val rec = recordOf(r, s)
            return when {
                rec.synced -> rec
                rec.instrumental -> { if (instrumental == null) instrumental = rec; null }
                rec.text.isNotBlank() -> { if (plain == null) plain = rec; null }
                else -> null
            }
        }

        // 1. LRCLIB's exact signature lookup.
        val (res, exact) = LrcLib.get(title, artist, album, durationMs?.let { (it + 500) / 1000 })
        if (res !is NetResult.Failed) reached = true
        if (exact != null) {
            val s = scoreOf(query, exact)
            if (s >= GET_MIN) consider(exact, s)?.let { return Fetch.Found(it) }
        }

        // 2. LRCLIB search: as tagged, then on the cleaned title and the lead
        //    artist ("Laho (feat. X) - Remix" by "A, B & C" -> "Laho" by "A").
        val cleanTitle = Matching.searchTitle(title)
        val leadArtist = Matching.primaryArtist(artist)
        val tries = linkedSetOf(title to artist, cleanTitle to artist, cleanTitle to leadArtist)
        for ((t, a) in tries) {
            val found = LrcLib.search(t, a) ?: continue
            reached = true
            val best = found
                .map { it to scoreOf(query, it) }
                .filter { (r, s) -> s >= Matching.CONFIDENT && (r.syncedLyrics != null || r.plainLyrics != null || r.instrumental) }
                .sortedWith(compareByDescending<Pair<LrcLibRecord, Float>> { it.first.syncedLyrics != null }.thenByDescending { it.second })
            for ((r, s) in best) consider(r, s)?.let { return Fetch.Found(it) }
        }

        // 3. NetEase, for synced lyrics LRCLIB does not have - strong on
        //    Asian and African catalogues. Its song must match the file.
        NetEase.search(cleanTitle, leadArtist)?.let { songs ->
            reached = true
            val ranked = songs
                .map { it to Matching.score(query, Matching.Candidate(it.title, it.artist, it.album, it.durationMs)) }
                .filter { it.second >= Matching.CONFIDENT }
                .sortedByDescending { it.second }
                .take(2)
            for ((song, score) in ranked) {
                val lrc = NetEase.lyric(song.id) ?: continue
                if (Lrc.looksSynced(lrc) && Lrc.parse(lrc).synced) {
                    return Fetch.Found(LyricsRecord(LyricsSource.NETEASE, true, lrc, score, false, false, System.currentTimeMillis()))
                }
                if (plain == null) {
                    val text = Lrc.plain(lrc)
                    if (text.isNotBlank()) plain = LyricsRecord(LyricsSource.NETEASE, false, text, score, false, false, System.currentTimeMillis())
                }
            }
        }

        // Nothing synced anywhere: an instrumental verdict, then plain text.
        instrumental?.let { return Fetch.Found(it) }
        plain?.let { return Fetch.Found(it) }

        // 4. lyrics.ovh: plain only, looked up by artist and title directly.
        LyricsOvh.get(leadArtist, cleanTitle)?.let { text ->
            return Fetch.Found(LyricsRecord(LyricsSource.LYRICS_OVH, false, text, null, false, false, System.currentTimeMillis()))
        }

        return if (reached) Fetch.Missing else Fetch.Offline
    }

    private fun scoreOf(q: Matching.Query, r: LrcLibRecord): Float =
        Matching.score(q, Matching.Candidate(
            r.trackName, r.artistName, r.albumName, r.durationSec?.let { (it * 1000).toLong() }
        ))

    private fun recordOf(r: LrcLibRecord, score: Float): LyricsRecord {
        val synced = r.syncedLyrics?.takeIf { Lrc.looksSynced(it) }
        return LyricsRecord(
            source = LyricsSource.LRCLIB,
            synced = synced != null,
            text = synced ?: r.plainLyrics.orEmpty(),
            confidence = score,
            instrumental = r.instrumental && synced == null && r.plainLyrics == null,
            miss = false,
            at = System.currentTimeMillis()
        )
    }

    // ---------------------------------------------------------------- .lrc

    /**
     * "Song.mp3" -> "Song.lrc" in the same folder. MediaStore only shows
     * another app's non-media files to apps with broad storage access, so on
     * Android 13+ this usually finds nothing, and that is fine: it is one
     * source of seven.
     */
    private fun sidecar(context: Context, item: AppMediaItem): String? =
        sidecarIndexed(context, item) ?: sidecarFile(context, item)

    /**
     * The same file read by path. MediaStore does not index .lrc files on
     * many devices at all, while the file is right there beside the track;
     * direct reads work on Android 10 and below, and on some later devices.
     */
    @Suppress("DEPRECATION")   // DATA is the only way to the path, and a miss is fine
    private fun sidecarFile(context: Context, item: AppMediaItem): String? = runCatching {
        val path = context.contentResolver.query(item.uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        val track = File(path)
        val base = track.nameWithoutExtension
        val dir = track.parentFile ?: return null
        listOf("$base.lrc", "$base.LRC").map { File(dir, it) }.firstOrNull { it.isFile && it.canRead() }
            ?.takeIf { it.length() < 512 * 1024 }?.readText()?.removePrefix("\uFEFF")
    }.getOrNull()

    private fun sidecarIndexed(context: Context, item: AppMediaItem): String? = runCatching {
        val cr = context.contentResolver
        val name = cr.query(item.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return null
        val base = name.substringBeforeLast('.')
        val files = MediaStore.Files.getContentUri("external")
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val id = cr.query(files, arrayOf(MediaStore.MediaColumns._ID), sel, arrayOf("$base.lrc", item.relPath), null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        cr.openInputStream(android.content.ContentUris.withAppendedId(files, id))?.use { s ->
            s.readBytes().takeIf { it.size < 512 * 1024 }?.let { String(it, Charsets.UTF_8) }
        }
    }.getOrNull()
}
