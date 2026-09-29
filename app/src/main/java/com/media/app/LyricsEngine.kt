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
//  In the order the blueprint sets, first hit wins:
//      embedded synced (SYLT, or LRC text in the lyrics tag)
//      a .lrc file beside the track
//      cached synced
//      online synced (LRCLIB)
//      embedded plain
//      cached plain
//      online plain
//
//  An online answer is checked against the file - title, artist, album and
//  length - before it is kept, and one that fails is not attached, however
//  good its lyrics look. Timing is only ever claimed when stamps exist: plain
//  lyrics are shown as plain.
//
//  Everything accepted is cached, including "nothing was found", so a track
//  costs one lookup and then works offline for good.
// ============================================================================

enum class LyricsSource(val label: String) {
    EMBEDDED("Embedded in the file"),
    LRC_FILE("From a .lrc file"),
    LRCLIB("From LRCLIB")
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
        val tags = readTags(context, item.uri)
        val offset = LyricsStore.offset(item.id)

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
                    cached = r.record
                    if (r.record.instrumental) return@withContext LyricsState.Instrumental
                    if (r.record.synced) {
                        val p = Lrc.parse(r.record.text)
                        if (p.synced) {
                            return@withContext LyricsState.Synced(p.lines, LyricsSource.LRCLIB, offset, r.record.confidence)
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

        val (res, exact) = LrcLib.get(title, artist, album, durationMs?.let { (it + 500) / 1000 })
        if (exact != null) {
            val s = scoreOf(query, exact)
            if (s >= GET_MIN) return Fetch.Found(recordOf(exact, s))
        } else if (res is NetResult.Failed) {
            return Fetch.Offline
        }

        val found = LrcLib.search(title, artist) ?: return Fetch.Offline
        val best = found
            .map { it to scoreOf(query, it) }
            .filter { (r, s) -> s >= Matching.CONFIDENT && (r.syncedLyrics != null || r.plainLyrics != null || r.instrumental) }
            // Prefer synced when two candidates are equally good.
            .maxWithOrNull(compareBy<Pair<LrcLibRecord, Float>> { it.second }.thenBy { it.first.syncedLyrics != null })
            ?: return Fetch.Missing
        return Fetch.Found(recordOf(best.first, best.second))
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
    private fun sidecar(context: Context, item: AppMediaItem): String? = runCatching {
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
