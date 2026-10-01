package com.media.app

import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
//  PROVIDERS
//
//  One object per service, each behind the same shape: take what the file
//  says, return candidates, never decide. Deciding is Matching's job, so a
//  provider can be swapped (blueprint §15: "keep provider-specific code
//  behind interfaces") without touching the rules that choose between them.
//
//  A null return means the service could not be asked; an empty list means
//  it was asked and knew nothing. The two are cached differently.
// ============================================================================

data class MbRelease(
    val id: String,
    val title: String,
    val artist: String,
    val date: String?,
    val releaseGroupId: String?,
    val primaryType: String?,
    val status: String?,
    val trackCount: Int?,
    /** MusicBrainz' own search score, 0..100. */
    val score: Int = 0
) {
    val year: String? get() = date?.take(4)?.takeIf { it.length == 4 }
}

data class MbRecording(
    val id: String,
    val title: String,
    val artist: String,
    val lengthMs: Long?,
    val releases: List<MbRelease>,
    val isrcs: List<String>,
    val score: Int = 0
)

/** A string field, with JSON null and "" both meaning absent (org.json turns null into "null"). */
internal fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

object MusicBrainz {
    private const val BASE = "https://musicbrainz.org/ws/2"

    suspend fun searchRecordings(title: String, artist: String?, limit: Int = 10): List<MbRecording>? {
        val q = buildString {
            append("recording:\"").append(Matching.luceneEscape(title)).append('"')
            if (!artist.isNullOrBlank()) append(" AND artist:\"").append(Matching.luceneEscape(artist)).append('"')
        }
        return when (val r = Net.get("$BASE/recording/?" + Net.query("query" to q, "fmt" to "json", "limit" to limit))) {
            is NetResult.Ok -> runCatching { parseRecordings(r.text) }.getOrNull()
            NetResult.NotFound -> emptyList()
            is NetResult.Failed -> null
        }
    }

    suspend fun searchReleases(album: String, artist: String?, limit: Int = 10): List<MbRelease>? {
        val q = buildString {
            append("release:\"").append(Matching.luceneEscape(album)).append('"')
            if (!artist.isNullOrBlank()) append(" AND artist:\"").append(Matching.luceneEscape(artist)).append('"')
        }
        return when (val r = Net.get("$BASE/release/?" + Net.query("query" to q, "fmt" to "json", "limit" to limit))) {
            is NetResult.Ok -> runCatching { parseReleases(r.text) }.getOrNull()
            NetResult.NotFound -> emptyList()
            is NetResult.Failed -> null
        }
    }

    /** "Artist A feat. Artist B" from an artist-credit array. */
    internal fun credit(arr: JSONArray?): String {
        if (arr == null) return ""
        return buildString {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                append(o.str("name") ?: o.optJSONObject("artist")?.str("name").orEmpty())
                append(o.str("joinphrase").orEmpty())
            }
        }.trim()
    }

    internal fun release(o: JSONObject): MbRelease {
        val rg = o.optJSONObject("release-group")
        return MbRelease(
            id = o.str("id").orEmpty(),
            title = o.str("title").orEmpty(),
            artist = credit(o.optJSONArray("artist-credit")),
            date = o.str("date"),
            releaseGroupId = rg?.str("id"),
            primaryType = rg?.str("primary-type"),
            status = o.str("status"),
            trackCount = if (o.has("track-count") && !o.isNull("track-count")) o.optInt("track-count") else null,
            score = o.optInt("score", 0)
        )
    }

    internal fun parseReleases(json: String): List<MbRelease> {
        val arr = JSONObject(json).optJSONArray("releases") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::release) }
            .filter { it.id.isNotEmpty() }
    }

    internal fun parseRecordings(json: String): List<MbRecording> {
        val arr = JSONObject(json).optJSONArray("recordings") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val artist = credit(o.optJSONArray("artist-credit"))
            val rels = o.optJSONArray("releases")
            val releases = if (rels == null) emptyList() else (0 until rels.length()).mapNotNull { j ->
                rels.optJSONObject(j)?.let { r ->
                    // A recording's release list omits the credit when it is
                    // the recording's own.
                    release(r).let { if (it.artist.isEmpty()) it.copy(artist = artist) else it }
                }
            }
            val isrcs = o.optJSONArray("isrcs")
            MbRecording(
                id = o.str("id").orEmpty(),
                title = o.str("title").orEmpty(),
                artist = artist,
                lengthMs = if (o.has("length") && !o.isNull("length")) o.optLong("length") else null,
                releases = releases,
                isrcs = if (isrcs == null) emptyList() else (0 until isrcs.length()).mapNotNull { isrcs.optString(it).takeIf { s -> s.isNotEmpty() && s != "null" } },
                score = o.optInt("score", 0)
            )
        }.filter { it.id.isNotEmpty() }
    }
}

object CoverArtArchive {
    private const val BASE = "https://coverartarchive.org"

    fun releaseGroupFront(id: String, size: Int) = "$BASE/release-group/$id/front-$size"
    fun releaseFront(id: String, size: Int) = "$BASE/release/$id/front-$size"

    /** Image bytes, trying the largest size first. Null when there is none. */
    suspend fun fetchFront(releaseGroupId: String?, releaseId: String?, sizes: List<Int> = listOf(1200, 500)): ByteArray? {
        val urls = buildList {
            for (s in sizes) {
                if (releaseId != null) add(releaseFront(releaseId, s))
                if (releaseGroupId != null) add(releaseGroupFront(releaseGroupId, s))
            }
        }
        for (u in urls) {
            when (val r = Net.get(u, maxBytes = 12 * 1024 * 1024, accept = "image/*")) {
                is NetResult.Ok -> if (r.body.size > 64) return r.body
                else -> Unit
            }
        }
        return null
    }
}

/** One LRCLIB record. */
data class LrcLibRecord(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val durationSec: Double?,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?
)

object LrcLib {
    private const val BASE = "https://lrclib.net/api"

    /** Exact signature lookup. NotFound is a real answer: nothing is known. */
    suspend fun get(title: String, artist: String, album: String?, durationSec: Long?): Pair<NetResult, LrcLibRecord?> {
        val url = "$BASE/get?" + Net.query(
            "track_name" to title, "artist_name" to artist,
            "album_name" to album, "duration" to durationSec
        )
        val r = Net.get(url)
        return r to (if (r is NetResult.Ok) runCatching { record(JSONObject(r.text)) }.getOrNull() else null)
    }

    suspend fun search(title: String, artist: String?): List<LrcLibRecord>? {
        val url = "$BASE/search?" + Net.query("track_name" to title, "artist_name" to artist)
        return when (val r = Net.get(url)) {
            is NetResult.Ok -> runCatching { parseSearch(r.text) }.getOrNull()
            NetResult.NotFound -> emptyList()
            is NetResult.Failed -> null
        }
    }

    internal fun record(o: JSONObject) = LrcLibRecord(
        id = o.optLong("id"),
        trackName = o.str("trackName").orEmpty(),
        artistName = o.str("artistName").orEmpty(),
        albumName = o.str("albumName"),
        durationSec = if (o.has("duration") && !o.isNull("duration")) o.optDouble("duration") else null,
        instrumental = o.optBoolean("instrumental", false),
        plainLyrics = o.str("plainLyrics")?.takeIf { it.isNotBlank() },
        syncedLyrics = o.str("syncedLyrics")?.takeIf { it.isNotBlank() }
    )

    internal fun parseSearch(json: String): List<LrcLibRecord> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::record) }
    }
}

// ============================================================================
//  STORE CATALOGUES: iTunes and Deezer
//
//  The fallback when MusicBrainz has no confident release or Cover Art
//  Archive has no picture for it - which is most of the time for regional
//  releases, singles and anything recent. Both are public, keyless search
//  APIs, the same ones other players use for covers. There is no public
//  image search from Google to ask instead: its search pages are not an API,
//  and scraping them is against its terms and gets blocked.
// ============================================================================

/** A cover offered by a store. [trackTitle] is set when it came from a song search. */
data class StoreCover(
    val provider: String,          // "itunes" | "deezer"
    val title: String,             // the album
    val artist: String,
    val trackTitle: String?,
    val durationMs: Long?,
    val year: String?,
    val imageUrl: String,
    val thumbUrl: String
)

object ITunes {
    private const val BASE = "https://itunes.apple.com/search"

    suspend fun albums(album: String, artist: String?): List<StoreCover>? =
        search(listOfNotNull(artist, album).joinToString(" "), "album")

    suspend fun songs(title: String, artist: String?): List<StoreCover>? =
        search(listOfNotNull(artist, title).joinToString(" "), "song")

    private suspend fun search(term: String, entity: String): List<StoreCover>? {
        val url = "$BASE?" + Net.query("term" to term, "media" to "music", "entity" to entity, "limit" to 10)
        return when (val r = Net.get(url, accept = "application/json, text/javascript, */*")) {
            is NetResult.Ok -> runCatching { parse(r.text) }.getOrNull()
            NetResult.NotFound -> emptyList()
            is NetResult.Failed -> null
        }
    }

    internal fun parse(json: String): List<StoreCover> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val art = o.str("artworkUrl100") ?: o.str("artworkUrl60") ?: return@mapNotNull null
            StoreCover(
                provider = "itunes",
                title = o.str("collectionName") ?: return@mapNotNull null,
                artist = o.str("artistName").orEmpty(),
                trackTitle = o.str("trackName"),
                durationMs = if (o.has("trackTimeMillis") && !o.isNull("trackTimeMillis")) o.optLong("trackTimeMillis") else null,
                year = o.str("releaseDate")?.take(4)?.takeIf { it.length == 4 },
                imageUrl = sized(art, 1200) ?: return@mapNotNull null,
                thumbUrl = sized(art, 300) ?: art
            )
        }
    }

    private val SIZE = Regex("""/\d+x\d+[a-z]*(-\d+)?\.(jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE)

    /**
     * Apple's artwork urls name their own size, and any size up to the
     * master can be asked for. Null when the url is not in that form: a
     * 100px thumbnail must never be saved as a "fixed" cover.
     */
    internal fun sized(url: String, px: Int): String? =
        if (SIZE.containsMatchIn(url)) SIZE.replace(url, "/${px}x${px}bb.jpg") else null
}

object Deezer {
    private const val BASE = "https://api.deezer.com"

    private fun field(name: String, value: String) = "$name:\"${value.replace("\"", "")}\""

    suspend fun albums(album: String, artist: String?): List<StoreCover>? {
        val q = listOfNotNull(artist?.let { field("artist", it) }, field("album", album)).joinToString(" ")
        return fetch("$BASE/search/album?" + Net.query("q" to q, "limit" to 10), album = true)
    }

    suspend fun tracks(title: String, artist: String?): List<StoreCover>? {
        val q = listOfNotNull(artist?.let { field("artist", it) }, field("track", title)).joinToString(" ")
        return fetch("$BASE/search?" + Net.query("q" to q, "limit" to 10), album = false)
    }

    private suspend fun fetch(url: String, album: Boolean): List<StoreCover>? =
        when (val r = Net.get(url)) {
            is NetResult.Ok -> runCatching { parse(r.text, album) }.getOrNull()
            NetResult.NotFound -> emptyList()
            is NetResult.Failed -> null
        }

    /** Null on Deezer's in-band error (quota, bad query): it could not be asked. */
    internal fun parse(json: String, album: Boolean): List<StoreCover>? {
        val root = JSONObject(json)
        if (root.has("error")) return null
        val arr = root.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val alb = if (album) o else o.optJSONObject("album") ?: return@mapNotNull null
            val xl = alb.str("cover_xl") ?: alb.str("cover_big") ?: return@mapNotNull null
            StoreCover(
                provider = "deezer",
                title = alb.str("title") ?: return@mapNotNull null,
                artist = o.optJSONObject("artist")?.str("name").orEmpty(),
                trackTitle = if (album) null else o.str("title"),
                durationMs = if (!album && o.has("duration")) o.optLong("duration") * 1000L else null,
                year = alb.str("release_date")?.take(4)?.takeIf { it.length == 4 },
                imageUrl = xl,
                thumbUrl = alb.str("cover_medium") ?: xl
            )
        }
    }
}

// ============================================================================
//  MORE LYRICS: NetEase (synced) and lyrics.ovh (plain)
//
//  Asked only after LRCLIB has had every chance, and held to the same rule:
//  a NetEase song must match the file's title, artist and length before its
//  lyrics are kept. lyrics.ovh is a direct artist + title lookup with no
//  length to check, so it only ever supplies plain text, labelled as such.
// ============================================================================

data class NeteaseSong(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?
)

object NetEase {
    private const val BASE = "https://music.163.com/api"
    private val HEADERS = mapOf("Referer" to "https://music.163.com/")

    suspend fun search(title: String, artist: String?): List<NeteaseSong>? {
        val url = "$BASE/search/get/web?" + Net.query(
            "s" to listOfNotNull(title, artist).joinToString(" "), "type" to 1, "limit" to 8, "offset" to 0
        )
        return when (val r = Net.get(url, headers = HEADERS)) {
            is NetResult.Ok -> runCatching { parseSearch(r.text) }.getOrNull()
            NetResult.NotFound -> emptyList()
            is NetResult.Failed -> null
        }
    }

    internal fun parseSearch(json: String): List<NeteaseSong> {
        val songs = JSONObject(json).optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return (0 until songs.length()).mapNotNull { i ->
            val o = songs.optJSONObject(i) ?: return@mapNotNull null
            val artists = o.optJSONArray("artists")
            NeteaseSong(
                id = o.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null,
                title = o.str("name") ?: return@mapNotNull null,
                artist = if (artists == null) "" else (0 until artists.length())
                    .mapNotNull { artists.optJSONObject(it)?.str("name") }.joinToString(", "),
                album = o.optJSONObject("album")?.str("name"),
                durationMs = o.optLong("duration").takeIf { it > 0 }
            )
        }
    }

    /** The song's LRC, or null when NetEase has none. */
    suspend fun lyric(id: Long): String? {
        val r = Net.get("$BASE/song/lyric?" + Net.query("id" to id, "lv" to 1, "kv" to 1, "tv" to -1), headers = HEADERS)
            as? NetResult.Ok ?: return null
        return runCatching {
            val o = JSONObject(r.text)
            if (o.optBoolean("nolyric") || o.optBoolean("uncollected")) null
            else o.optJSONObject("lrc")?.str("lyric")?.let(::clean)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private val CREDIT = Regex(
        """^(作词|作曲|编曲|制作人|混音|母带|和声|录音|監製|作詞|編曲|lyricist|lyrics by|composer|composed by|arranger|producer|produced by)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )

    /** NetEase prefixes the credits as timed lines; they are not lyrics. */
    internal fun clean(lrc: String): String =
        lrc.lineSequence().filterNot { CREDIT.containsMatchIn(it.substringAfterLast(']').trim()) }.joinToString("\n")
}

object LyricsOvh {
    private fun seg(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Plain lyrics, or null when there are none (or the service is down). */
    suspend fun get(artist: String, title: String): String? {
        val r = Net.get("https://api.lyrics.ovh/v1/${seg(artist)}/${seg(title)}") as? NetResult.Ok ?: return null
        return runCatching {
            JSONObject(r.text).str("lyrics")?.replace("\r\n", "\n")?.trim()?.takeIf { it.length > 20 }
        }.getOrNull()
    }
}
