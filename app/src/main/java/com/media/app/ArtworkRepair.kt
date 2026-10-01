package com.media.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import com.media.app.Matching.isUsable

// ============================================================================
//  ARTWORK REPAIR
//
//  The library's worst-looking problem, fixed in three layers:
//
//    1. FIND   ArtworkRepair.inspect() reads the cover a track would show and
//              names what is wrong with it: missing, low resolution, or a
//              flat placeholder square.
//    2. FIX    candidates() asks MusicBrainz which release this is and Cover
//              Art Archive for that release's front cover, then Deezer and
//              Apple Music's catalogues when that comes up short. Every
//              candidate is scored against the file's own tags; only a
//              confident match is ever applied without the listener choosing
//              it, and a confident match with no picture falls through to the
//              next one instead of ending the attempt.
//    3. SHOW   a fixed cover is a JPEG in the app's own storage. CoverArt
//              reads it before anything else, and so does the session's
//              bitmap loader - which is what the notification and lock screen
//              draw from. The old custom-cover attempt died because a file://
//              uri cannot cross into the system's process; a Bitmap handed
//              over by our own loader can.
//
//  The files themselves are never touched. A fix is a layer on top, and
//  "Use the file's own cover" removes it.
// ============================================================================

/** One applied cover. [source] is "caa" (Cover Art Archive), "deezer", "itunes" or "custom". */
data class ArtEntry(
    val key: String,
    val source: String,
    val confidence: Float,
    val releaseId: String?,
    val releaseGroupId: String?,
    val title: String?,
    val width: Int,
    val height: Int,
    val at: Long
)

object ArtworkStore {
    private const val MAX_SIDE = 1200
    private lateinit var dir: File
    @Volatile private var ready = false

    private val trackToKey = HashMap<Long, String>()
    private val entries = HashMap<String, ArtEntry>()
    private val misses = HashMap<String, Long>()

    private val _version = MutableStateFlow(0)
    /** Bumps on every change, so covers on screen can re-read themselves. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            dir = File(context.applicationContext.filesDir, "artwork").apply { mkdirs() }
            load()
            ready = true
        }
    }

    /**
     * One cover per album only where the album is REAL (AlbumCoherence);
     * per track otherwise. "Has an album name" was the old test, and a
     * download site's tag ("Trendysongz.com") or a folder of singles is an
     * album name too: one fix then painted every song in it with one cover,
     * whoever the artist.
     */
    fun groupKey(item: AppMediaItem): String =
        if (AlbumCoherence.isCoherent(item.albumId)) "album-${item.albumId}"
        else "track-${item.id}"

    /**
     * Undoes album-wide fixes on albums that are not real albums. Automatic
     * and searched covers are removed, so those tracks go back to their own
     * pictures and the next repair works per track; a cover the listener
     * picked by hand is left where they put it.
     */
    fun reconcile() {
        if (!ready || !AlbumCoherence.known) return
        var changed = false
        synchronized(this) {
            val bad = entries.filter { (k, e) ->
                k.startsWith("album-") && e.source != "custom" &&
                    !AlbumCoherence.isCoherent(k.removePrefix("album-").toLongOrNull() ?: 0L)
            }.keys
            if (bad.isNotEmpty()) {
                trackToKey.entries.removeAll { it.value in bad }
                bad.forEach { k -> entries.remove(k); File(dir, "$k.jpg").delete() }
                misses.keys.removeAll { it in bad }
                persist()
                changed = true
            }
        }
        if (changed) _version.value++
    }

    @Synchronized fun entryFor(trackId: Long): ArtEntry? {
        if (!ready) return null
        return trackToKey[trackId]?.let { entries[it] }
    }

    fun fileFor(trackId: Long): File? {
        val e = entryFor(trackId) ?: return null
        return File(dir, "${e.key}.jpg").takeIf { it.isFile }
    }

    /** Changes whenever this track's cover does; 0 when it has no fix. */
    fun stamp(trackId: Long): Long = entryFor(trackId)?.at ?: 0L

    @Synchronized fun missedRecently(key: String, days: Int = 30): Boolean {
        val at = misses[key] ?: return false
        return System.currentTimeMillis() - at < days * 86_400_000L
    }

    @Synchronized fun markMiss(key: String) {
        if (!ready) return
        misses[key] = System.currentTimeMillis()
        persist()
    }

    /**
     * Normalises [bytes] to a JPEG no larger than 1200px and makes it the cover
     * for every id in [trackIds]. False when the bytes are not an image.
     */
    fun save(
        key: String,
        trackIds: Collection<Long>,
        bytes: ByteArray,
        source: String,
        confidence: Float,
        releaseId: String? = null,
        releaseGroupId: String? = null,
        title: String? = null
    ): Boolean {
        if (!ready || trackIds.isEmpty()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val longest = maxOf(decoded.width, decoded.height)
        val bmp = if (longest > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(decoded, (decoded.width * k).toInt().coerceAtLeast(1),
                (decoded.height * k).toInt().coerceAtLeast(1), true)
        } else decoded
        val tmp = File(dir, "$key.tmp")
        val ok = runCatching {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }.getOrDefault(false)
        if (bmp !== decoded) decoded.recycle()
        bmp.recycle()
        if (!ok) { tmp.delete(); return false }
        val target = File(dir, "$key.jpg")
        if (!tmp.renameTo(target)) { tmp.delete(); return false }
        synchronized(this) {
            entries[key] = ArtEntry(
                key, source, confidence, releaseId, releaseGroupId, title,
                bounds.outWidth, bounds.outHeight, System.currentTimeMillis()
            )
            trackIds.forEach { trackToKey[it] = key }
            misses.remove(key)
            persist()
        }
        _version.value++
        return true
    }

    /** Back to the file's own cover for these tracks. */
    fun remove(trackIds: Collection<Long>) {
        if (!ready) return
        synchronized(this) {
            val keys = trackIds.mapNotNull { trackToKey.remove(it) }.toSet()
            for (k in keys) {
                if (trackToKey.values.none { it == k }) {
                    entries.remove(k)
                    File(dir, "$k.jpg").delete()
                }
            }
            persist()
        }
        _version.value++
    }

    @Synchronized fun fixedCount(): Int = entries.size

    private fun load() {
        val f = File(dir, "index.json")
        if (!f.isFile) return
        runCatching {
            val o = JSONObject(f.readText())
            o.optJSONObject("tracks")?.let { t ->
                t.keys().forEach { k -> k.toLongOrNull()?.let { trackToKey[it] = t.getString(k) } }
            }
            o.optJSONObject("entries")?.let { es ->
                es.keys().forEach { k ->
                    val e = es.getJSONObject(k)
                    entries[k] = ArtEntry(
                        key = k,
                        source = e.optString("source", "caa"),
                        confidence = e.optDouble("confidence", 0.0).toFloat(),
                        releaseId = e.optString("releaseId").ifEmpty { null },
                        releaseGroupId = e.optString("releaseGroupId").ifEmpty { null },
                        title = e.optString("title").ifEmpty { null },
                        width = e.optInt("width"),
                        height = e.optInt("height"),
                        at = e.optLong("at")
                    )
                }
            }
            // Misses from before the store fallbacks existed are forgotten:
            // "MusicBrainz had nothing" is no longer "nothing can be found".
            if (o.optInt("v", 1) >= 2) {
                o.optJSONObject("misses")?.let { m -> m.keys().forEach { k -> misses[k] = m.getLong(k) } }
            }
        }
    }

    private fun persist() {
        val o = JSONObject()
        o.put("v", 2)
        o.put("tracks", JSONObject().apply { trackToKey.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("entries", JSONObject().apply {
            entries.forEach { (k, e) ->
                put(k, JSONObject().apply {
                    put("source", e.source); put("confidence", e.confidence.toDouble())
                    e.releaseId?.let { put("releaseId", it) }
                    e.releaseGroupId?.let { put("releaseGroupId", it) }
                    e.title?.let { put("title", it) }
                    put("width", e.width); put("height", e.height); put("at", e.at)
                })
            }
        })
        o.put("misses", JSONObject().apply { misses.forEach { (k, v) -> put(k, v) } })
        val tmp = File(dir, "index.tmp")
        runCatching {
            tmp.writeText(o.toString())
            tmp.renameTo(File(dir, "index.json"))
        }
    }
}

/**
 * Which albums are real albums: one artist's numbered tracks under a proper
 * name. Everything else that MediaStore files under one album id - a
 * download site's tag, "Unknown album", a folder of singles by different
 * artists - shares a name, not a cover. Those tracks show their own picture
 * and are repaired one at a time.
 *
 * Strict on purpose. Getting it wrong one way costs a few extra lookups;
 * getting it wrong the other way paints the wrong cover on songs.
 */
object AlbumCoherence {
    @Volatile private var coherent: Set<Long> = emptySet()
    @Volatile var known = false
        private set

    // A domain or a "free download" tag is a site, not an album.
    private val SITE = Regex(
        """(www\.|https?:|\b[\w-]+\.(com|net|org|ng|co|info|xyz|me|io|live|top|site|online|tv|fm|cc|biz|app)\b|download|mp3)""",
        RegexOption.IGNORE_CASE
    )

    fun update(items: List<AppMediaItem>) {
        coherent = items.asSequence()
            .filter { it.type == MediaType.AUDIO && it.albumId != 0L }
            .groupBy { it.albumId }
            .filter { (_, tracks) -> isRealAlbum(tracks) }
            .keys
        known = true
    }

    fun isCoherent(albumId: Long): Boolean = albumId != 0L && albumId in coherent

    internal fun isRealAlbum(tracks: List<AppMediaItem>): Boolean {
        val name = tracks.first().album
        if (!name.isUsable() || name == UNKNOWN_ALBUM || SITE.containsMatchIn(name)) return false
        // One lead artist. An unknown artist counts as its own value, so
        // "Unknown artist" beside "Taylor Swift" is two artists, not one.
        val artists = tracks.map { Matching.normalize(Matching.primaryArtist(it.artist)) }.toSet()
        if (artists.size > 1) return false
        // A single track is trivially its own album. Several need real track
        // numbers: singles from one artist dumped in one folder have none.
        if (tracks.size == 1) return true
        return tracks.map { it.trackNo }.filter { it > 0 }.distinct().size >= 2
    }
}

/** What a track's cover looks like right now, and why it might need fixing. */
data class ArtStatus(val issue: ArtIssue, val width: Int, val height: Int, val fixed: ArtEntry?)

/**
 * One cover the listener can pick. A MusicBrainz candidate names a release
 * and its picture comes from Cover Art Archive; a store candidate carries
 * its own image urls.
 */
data class ArtCandidate(
    val releaseId: String?,
    val releaseGroupId: String?,
    val title: String,
    val artist: String,
    val year: String?,
    val score: Float,
    val provider: String = "caa",
    val imageUrl: String? = null,
    val thumbUrl: String? = null
) {
    val key: String get() = releaseGroupId ?: releaseId ?: imageUrl ?: title
    val providerLabel: String get() = providerName(provider)
}

fun providerName(source: String): String = when (source) {
    "deezer" -> "Deezer"
    "itunes" -> "Apple Music"
    "custom" -> "Your own"
    else -> "Cover Art Archive"
}

data class RepairProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val fixed: Int = 0,
    val current: String? = null,
    /** Set when the run stopped because the network did. */
    val offline: Boolean = false
)

enum class AutoFix { FIXED, NO_MATCH, OFFLINE, NOT_NEEDED }

object ArtworkRepair {

    // ------------------------------------------------------------ inspection

    /** Reads the cover [item] would show and grades it. Blocking; call on IO. */
    fun inspect(context: Context, item: AppMediaItem): ArtStatus {
        ArtworkStore.entryFor(item.id)?.let { e -> return ArtStatus(ArtIssue.NONE, e.width, e.height, e) }
        // Same order CoverArt loads in: the album's picture only for a real
        // album (otherwise it is another song's cover), then this file's own.
        if (AlbumCoherence.isCoherent(item.albumId)) {
            item.artworkUri?.let { uri -> gradeUri(context, uri)?.let { return it } }
        }
        val r = MediaMetadataRetriever()
        val bytes = try {
            r.setDataSource(context, item.uri)
            r.embeddedPicture
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
        return if (bytes == null) ArtStatus(ArtIssue.MISSING, 0, 0, null) else gradeBytes(bytes)
    }

    private fun gradeUri(context: Context, uri: Uri): ArtStatus? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0) return null
        val small = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, 16)
            })
        }
        grade(bounds.outWidth, bounds.outHeight, small)
    }.getOrNull()

    private fun gradeBytes(bytes: ByteArray): ArtStatus {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return ArtStatus(ArtIssue.MISSING, 0, 0, null)
        val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, 16)
        })
        return grade(bounds.outWidth, bounds.outHeight, small)
    }

    private fun grade(w: Int, h: Int, small: Bitmap?): ArtStatus {
        val pixels = small?.let { b ->
            val s = Bitmap.createScaledBitmap(b, 8, 8, true)
            IntArray(64).also { s.getPixels(it, 0, 8, 0, 0, 8, 8); if (s !== b) s.recycle() }
        }
        small?.recycle()
        return ArtStatus(ArtworkQuality.assess(w, h, pixels), w, h, null)
    }

    private fun sampleFor(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (minOf(w, h) / (s * 2) >= target) s *= 2
        return s
    }

    // ------------------------------------------------------------ candidates

    private fun pick(primary: String?, fallback: String?): String? =
        primary?.takeIf { it.isUsable() } ?: fallback?.takeIf { it.isUsable() }

    /**
     * Covers that could be this track's, best first. Null when no service
     * could be reached; empty when they were and knew nothing.
     *
     * MusicBrainz first, because a release it knows is exact. Then the
     * stores: Cover Art Archive has no picture for a large share of the
     * releases MusicBrainz knows, and regional releases, singles and new
     * music are often not in MusicBrainz at all. One miss used to be the
     * end of it; now Deezer and Apple Music are asked too, each answer is
     * scored against the file the same way, and [thorough] (the picker)
     * asks every service so there is something to choose from.
     */
    suspend fun candidates(item: AppMediaItem, tags: FileTags?, thorough: Boolean = false): List<ArtCandidate>? {
        val title = pick(item.title, tags?.title)
        val artist = pick(item.artist, tags?.albumArtist ?: tags?.artist)
        val album = pick(item.album, tags?.album)
        val durationMs = item.durationMs.takeIf { it > 0 } ?: tags?.durationMs
        val found = LinkedHashMap<String, ArtCandidate>()
        var reached = false

        fun offer(c: ArtCandidate) {
            val prev = found[c.key]
            if (prev == null || prev.score < c.score) found[c.key] = c
        }
        fun best() = found.values.maxOfOrNull { it.score } ?: 0f
        /** Confident answers that do not depend on Cover Art Archive having a picture. */
        fun confidentStore() = found.values.count { it.imageUrl != null && it.score >= Matching.CONFIDENT }

        // The file already knows its release: that is the answer.
        if (tags != null && (tags.mbReleaseId != null || tags.mbReleaseGroupId != null)) {
            offer(ArtCandidate(tags.mbReleaseId, tags.mbReleaseGroupId, album ?: title ?: "",
                artist ?: "", tags.date?.take(4), 1f))
        }

        if (album != null) {
            val rels = MusicBrainz.searchReleases(album, artist)
            if (rels != null) reached = true
            rels.orEmpty().forEach { r ->
                val a = Matching.similarity(album, r.title)
                val s = if (artist != null) 0.6f * a + 0.4f * Matching.similarity(artist, r.artist) else a * 0.9f
                offer(ArtCandidate(r.id, r.releaseGroupId, r.title, r.artist, r.year,
                    (s + if (r.status == "Official") 0.02f else 0f).coerceAtMost(1f)))
            }
        }

        if (title != null && (best() < Matching.CONFIDENT || found.size < 3)) {
            val recs = MusicBrainz.searchRecordings(title, artist)
            if (recs != null) reached = true
            recs.orEmpty().forEach { rec ->
                val s = Matching.score(
                    Matching.Query(title, artist, album, durationMs),
                    Matching.Candidate(rec.title, rec.artist, null, rec.lengthMs)
                )
                if (s < Matching.PLAUSIBLE) return@forEach
                rec.releases
                    .sortedWith(compareByDescending<MbRelease> { it.status == "Official" }
                        .thenByDescending { it.primaryType == "Album" || it.primaryType == "Single" })
                    .take(4)
                    .forEach { r ->
                        // A known album that disagrees with this release costs
                        // it: the song is right, the cover may not be.
                        val albumFit = if (album != null) Matching.similarity(album, r.title) else 1f
                        offer(ArtCandidate(r.id, r.releaseGroupId, r.title, r.artist.ifEmpty { rec.artist },
                            r.year, s * (0.8f + 0.2f * albumFit)))
                    }
            }
        }

        // ---- the stores ----
        fun offerStore(list: List<StoreCover>?) {
            if (list == null) return
            reached = true
            list.forEach { c ->
                val s = if (c.trackTitle == null) {
                    // An album hit: judged on the album and its artist.
                    if (album == null) return@forEach
                    val a = Matching.similarity(album, c.title)
                    if (artist != null) 0.6f * a + 0.4f * Matching.similarity(artist, c.artist) else a * 0.9f
                } else {
                    // A song hit: the song must match, and a known album that
                    // disagrees costs it, exactly as for MusicBrainz.
                    val song = Matching.score(
                        Matching.Query(title, artist, album, durationMs),
                        Matching.Candidate(c.trackTitle, c.artist, null, c.durationMs)
                    )
                    val albumFit = if (album != null) Matching.similarity(album, c.title) else 1f
                    song * (0.8f + 0.2f * albumFit)
                }
                if (s < Matching.PLAUSIBLE - 0.1f) return@forEach
                offer(ArtCandidate(null, null, c.title, c.artist, c.year, s.coerceIn(0f, 1f),
                    provider = c.provider, imageUrl = c.imageUrl, thumbUrl = c.thumbUrl))
            }
        }
        // Store searches do better on the plain title and the lead artist:
        // "Laho (feat. X) [Official Video]" finds nothing; "Laho" does.
        val qTitle = title?.let { Matching.searchTitle(it) }
        val qArtist = artist?.let { Matching.primaryArtist(it) }

        if (album != null) offerStore(Deezer.albums(album, qArtist))
        if (qTitle != null && (thorough || confidentStore() == 0)) offerStore(Deezer.tracks(qTitle, qArtist))
        // Apple's limit is strict (about 20 a minute), so it is asked only
        // when nothing confident has turned up yet, or when choosing by hand.
        if (thorough || confidentStore() == 0) {
            if (album != null) offerStore(ITunes.albums(album, qArtist))
            if (qTitle != null && (thorough || confidentStore() == 0)) offerStore(ITunes.songs(qTitle, qArtist))
        }

        if (!reached && found.isEmpty()) return null
        return found.values.sortedByDescending { it.score }.take(12)
    }

    private val thumbs = LruCache<String, ByteArray>(32)

    /** A small preview of a candidate, or null when the service has none. */
    suspend fun thumbnail(c: ArtCandidate): ByteArray? {
        thumbs.get(c.key)?.let { return it }
        val bytes = if (c.thumbUrl != null) image(c.thumbUrl)
            else CoverArtArchive.fetchFront(c.releaseGroupId, c.releaseId, sizes = listOf(250))
        bytes ?: return null
        thumbs.put(c.key, bytes)
        return bytes
    }

    private suspend fun image(url: String): ByteArray? =
        (Net.get(url, maxBytes = 12 * 1024 * 1024, accept = "image/*") as? NetResult.Ok)
            ?.body?.takeIf { it.size > 64 }

    // --------------------------------------------------------------- applying

    suspend fun apply(item: AppMediaItem, scope: Collection<Long>, c: ArtCandidate): Boolean {
        val bytes = if (c.imageUrl != null) image(c.imageUrl)
            else CoverArtArchive.fetchFront(c.releaseGroupId, c.releaseId)
        bytes ?: return false
        return withContext(Dispatchers.IO) {
            ArtworkStore.save(
                ArtworkStore.groupKey(item), scope, bytes, c.provider, c.score,
                c.releaseId, c.releaseGroupId, c.title
            )
        }
    }

    suspend fun applyCustom(context: Context, item: AppMediaItem, scope: Collection<Long>, image: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(image)?.use { it.readBytes() }
            }.getOrNull() ?: return@withContext false
            ArtworkStore.save(ArtworkStore.groupKey(item), scope, bytes, "custom", 1f)
        }

    /**
     * Applies the best confident candidate. A MusicBrainz match whose
     * release has no picture in Cover Art Archive no longer ends the attempt:
     * the next confident candidate - very often a store's - is tried.
     */
    suspend fun autoFix(context: Context, item: AppMediaItem, scope: Collection<Long>): AutoFix {
        val key = ArtworkStore.groupKey(item)
        val tags = withContext(Dispatchers.IO) { readTags(context, item.uri) }
        val list = candidates(item, tags) ?: return AutoFix.OFFLINE
        for (c in list.filter { it.score >= Matching.CONFIDENT }.take(6)) {
            if (apply(item, scope, c)) return AutoFix.FIXED
        }
        ArtworkStore.markMiss(key)
        return AutoFix.NO_MATCH
    }

    // ------------------------------------------------------ quiet background

    private val tried = HashSet<String>()

    /**
     * Called as a track starts. Inspects its cover and, if it is bad and a
     * confident replacement exists, applies it. Once per album per session.
     */
    suspend fun fixIfNeeded(context: Context, item: AppMediaItem, scope: Collection<Long>) {
        if (item.type != MediaType.AUDIO || item.pillar != Pillar.MUSIC) return
        val key = ArtworkStore.groupKey(item)
        synchronized(tried) { if (!tried.add(key)) return }
        if (ArtworkStore.entryFor(item.id) != null || ArtworkStore.missedRecently(key)) return
        if (!item.title.isUsable() && !item.album.isUsable()) return
        val status = withContext(Dispatchers.IO) { inspect(context, item) }
        if (status.issue == ArtIssue.NONE) return
        autoFix(context, item, scope)
    }

    // --------------------------------------------------------- whole library

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _progress = MutableStateFlow(RepairProgress())
    val progress: StateFlow<RepairProgress> = _progress.asStateFlow()

    fun startLibraryRepair(context: Context, music: List<AppMediaItem>) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = bg.launch {
            val groups = music.filter { it.type == MediaType.AUDIO && it.pillar == Pillar.MUSIC }
                .groupBy { ArtworkStore.groupKey(it) }
                .filterKeys { !ArtworkStore.missedRecently(it) }
                .values
                .filter { g -> ArtworkStore.entryFor(g.first().id) == null }
            _progress.value = RepairProgress(running = true, total = groups.size)
            var fixed = 0
            for ((i, g) in groups.withIndex()) {
                if (!isActive) break
                val rep = g.first()
                _progress.value = _progress.value.copy(done = i, current = rep.album.takeIf { it.isUsable() } ?: rep.title)
                val status = runCatching { inspect(app, rep) }.getOrNull() ?: continue
                if (status.issue == ArtIssue.NONE) continue
                if (!rep.title.isUsable() && !rep.album.isUsable()) continue
                when (autoFix(app, rep, g.map { it.id })) {
                    AutoFix.FIXED -> fixed++
                    AutoFix.OFFLINE -> {
                        _progress.value = RepairProgress(running = false, done = i, total = groups.size,
                            fixed = fixed, offline = true)
                        return@launch
                    }
                    else -> Unit
                }
                _progress.value = _progress.value.copy(fixed = fixed)
            }
            _progress.value = RepairProgress(running = false, done = groups.size, total = groups.size, fixed = fixed)
        }
    }

    fun cancel() {
        job?.cancel()
        _progress.value = _progress.value.copy(running = false, current = null)
    }
}

/** Tags for a content uri, or null when the file cannot be opened. Blocking. */
fun readTags(context: Context, uri: Uri): FileTags? = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch -> TagReader.read(ChannelSource(ch)) }
    }
}.getOrNull()
