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
