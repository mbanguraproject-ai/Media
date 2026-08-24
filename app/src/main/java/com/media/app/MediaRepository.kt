package com.media.app

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MediaType { AUDIO, VIDEO }

enum class Pillar { MUSIC, PODCAST, AUDIOBOOK, VIDEO }

data class AppMediaItem(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri,
    val type: MediaType,
    val pillar: Pillar,
    val details: String? = null,
    val artworkUri: Uri? = null,
    // --- album / artist identity (§9) ---
    val album: String = UNKNOWN_ALBUM,
    val albumId: Long = 0L,
    val artistId: Long = 0L,
    val trackNo: Int = 0,
    val year: Int = 0,
    val dateAdded: Long = 0L,     // epoch seconds, from MediaStore
    val mimeType: String = "",
    val relPath: String = ""
)

// An album is a real thing users navigate to, not a string on a row (§9, §15).
data class Album(
    val id: Long,
    val name: String,
    val artist: String,
    val trackCount: Int,
    val durationMs: Long,
    val year: Int,
    val artworkUri: Uri?,
    val tracks: List<AppMediaItem>
)

data class Artist(
    val id: Long,
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val durationMs: Long,
    val artworkUri: Uri?,
    val tracks: List<AppMediaItem>
)

// §9 sort options. RECENTLY_PLAYED / MOST_PLAYED need play history, so they
// take the maps rather than reading the DB themselves.
enum class SortKey(val label: String) {
    RECENTLY_ADDED("Recently added"),
    RECENTLY_PLAYED("Recently played"),
    MOST_PLAYED("Most played"),
    NAME("Name"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DURATION("Duration")
}

fun List<AppMediaItem>.sortedFor(
    key: SortKey,
    lastPlayed: Map<Long, Long> = emptyMap(),
    playCounts: Map<Long, Int> = emptyMap()
): List<AppMediaItem> = when (key) {
    SortKey.RECENTLY_ADDED -> sortedByDescending { it.dateAdded }
    SortKey.RECENTLY_PLAYED -> sortedByDescending { lastPlayed[it.id] ?: 0L }
    SortKey.MOST_PLAYED -> sortedByDescending { playCounts[it.id] ?: 0 }
    SortKey.NAME -> sortedBy { it.title.lowercase() }
    SortKey.ARTIST -> sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNo }))
    SortKey.ALBUM -> sortedWith(compareBy({ it.album.lowercase() }, { it.trackNo }, { it.title.lowercase() }))
    SortKey.DURATION -> sortedByDescending { it.durationMs }
}

const val UNKNOWN_ARTIST = "Unknown artist"
const val UNKNOWN_ALBUM = "Unknown album"

// ============================================================================
//  METADATA NORMALIZATION (§30)
//  Local files are messy: MediaStore hands back the literal "<unknown>", titles
//  are frequently just the filename, and recorder apps produce names like
//  AUD-20260823-001.mp3. None of that should be allowed to reach the UI raw.
// ============================================================================
private const val MEDIASTORE_UNKNOWN = "<unknown>"

// "01 - ", "07. ", "12_" leading track numbers
private val TRACK_PREFIX = Regex("""^\d{1,3}\s*[-._)]\s*""")
// AUD-20260823-001, REC_20240101, VN-20250612-0003, WA-20231105
private val RECORDER = Regex(
    """^(AUD|REC|VN|VOICE|WA|PTT|SND)[-_ ]?(\d{8})[-_ ]?(\d+)?""",
    RegexOption.IGNORE_CASE
)
private val MONTHS = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")

internal fun normalizeTitle(raw: String?): String {
    var t = (raw ?: "").trim()
    if (t.isEmpty() || t == MEDIASTORE_UNKNOWN) return "Untitled"
    // Drop a trailing extension when TITLE fell back to the filename.
    val dot = t.lastIndexOf('.')
    if (dot > 0 && t.length - dot <= 5) t = t.substring(0, dot)

    RECORDER.find(t)?.let { m ->
        val d = m.groupValues[2]
        val seq = m.groupValues[3]
        val pretty = runCatching {
            val mo = MONTHS[d.substring(4, 6).toInt() - 1]
            "${d.substring(6, 8).trimStart('0')} $mo ${d.substring(0, 4)}"
        }.getOrNull()
        val label = if (seq.isNotEmpty()) "Recording $seq" else "Recording"
        return if (pretty != null) "$label — $pretty" else label
    }

    t = t.replace('_', ' ')
        .replace(TRACK_PREFIX, "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return t.ifEmpty { "Untitled" }
}

internal fun normalizeArtist(raw: String?): String =
    raw?.trim()?.takeIf { it.isNotEmpty() && it != MEDIASTORE_UNKNOWN } ?: UNKNOWN_ARTIST

internal fun normalizeAlbum(raw: String?): String =
    raw?.trim()?.takeIf { it.isNotEmpty() && it != MEDIASTORE_UNKNOWN } ?: UNKNOWN_ALBUM

object MediaRepository {

    private const val TEN_MIN_MS = 10 * 60 * 1000L

    private fun classifyAudio(title: String, relPath: String, durationMs: Long): Pillar {
        val path = relPath.lowercase()
        return when {
            path.contains("audiobooks/") -> Pillar.AUDIOBOOK
            path.contains("podcasts/") -> Pillar.PODCAST
            path.contains("music/") -> Pillar.MUSIC
            title.lowercase().contains("podcast") -> Pillar.PODCAST
            durationMs > TEN_MIN_MS -> Pillar.PODCAST
            else -> Pillar.MUSIC
        }
    }

    @Volatile private var audioCache: List<AppMediaItem>? = null
    @Volatile private var videoCache: List<AppMediaItem>? = null

    fun refresh() { audioCache = null; videoCache = null }

    // Raw audio with heuristic classification, no overrides applied.
    // SUSPEND + IO: a full MediaStore cursor scan is real disk work and must
    // never run on the composition thread (it was an ANR on large libraries).
    suspend fun loadAudio(context: Context): List<AppMediaItem> {
        audioCache?.let { return it }
        return withContext(Dispatchers.IO) {
            audioCache ?: queryAudio(context).also { audioCache = it }
        }
    }

    private fun queryAudio(context: Context): List<AppMediaItem> {
        val items = mutableListOf<AppMediaItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val albumNameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val artistIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val albumArtBase = Uri.parse("content://media/external/audio/albumart")
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val title = normalizeTitle(c.getString(titleCol))
                val dur = c.getLong(durCol)
                val relPath = c.getString(pathCol) ?: ""
                val albumId = c.getLong(albumCol)
                val artUri = android.content.ContentUris.withAppendedId(albumArtBase, albumId)
                // MediaStore TRACK encodes disc*1000 + track; we only want track.
                val rawTrack = c.getInt(trackCol)
                items += AppMediaItem(
                    id = id, title = title,
                    artist = normalizeArtist(c.getString(artistCol)),
                    durationMs = dur,
                    uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()),
                    type = MediaType.AUDIO,
                    pillar = classifyAudio(title, relPath, dur),
                    artworkUri = artUri,
                    album = normalizeAlbum(c.getString(albumNameCol)),
                    albumId = albumId,
                    artistId = c.getLong(artistIdCol),
                    trackNo = if (rawTrack > 1000) rawTrack % 1000 else rawTrack,
                    year = c.getInt(yearCol),
                    dateAdded = c.getLong(addedCol),
                    mimeType = c.getString(mimeCol) ?: "",
                    relPath = relPath
                )
            }
        }
        return items
    }

    // Apply user overrides on top of the raw item.
    private fun applyOverride(item: AppMediaItem, ov: MediaOverride?): AppMediaItem {
        if (ov == null) return item
        val newPillar = when (ov.pillar) {
            "MUSIC" -> Pillar.MUSIC
            "PODCAST" -> Pillar.PODCAST
            "AUDIOBOOK" -> Pillar.AUDIOBOOK
            else -> item.pillar
        }
        return item.copy(
            title = ov.customTitle?.takeIf { it.isNotBlank() } ?: item.title,
            artist = ov.customArtist?.takeIf { it.isNotBlank() } ?: item.artist,
            pillar = newPillar,
            details = ov.details?.takeIf { it.isNotBlank() }
        )
    }

    // Pure and cheap — safe to call inside remember(). Keeping this separate
    // from the cursor scan means an override edit re-maps the list without
    // touching MediaStore again.
    fun applyOverrides(raw: List<AppMediaItem>, overrides: Map<Long, MediaOverride>): List<AppMediaItem> =
        raw.map { applyOverride(it, overrides[it.id]) }

    // ---- aggregates (§9) ----
    // Grouped by MediaStore's albumId/artistId rather than by display string, so
    // two albums that share a name don't collapse into one. Pure and cheap.
    fun albumsOf(tracks: List<AppMediaItem>): List<Album> =
        tracks.filter { it.type == MediaType.AUDIO }
            .groupBy { it.albumId }
            .map { (id, list) ->
                val ordered = list.sortedWith(compareBy({ it.trackNo }, { it.title.lowercase() }))
                Album(
                    id = id,
                    name = ordered.first().album,
                    // Albums with several contributors read as "Various artists".
                    artist = ordered.map { it.artist }.distinct().let {
                        if (it.size == 1) it.first() else "Various artists"
                    },
                    trackCount = ordered.size,
                    durationMs = ordered.sumOf { it.durationMs },
                    year = ordered.maxOf { it.year },
                    artworkUri = ordered.firstNotNullOfOrNull { it.artworkUri },
                    tracks = ordered
                )
            }
            .sortedBy { it.name.lowercase() }

    fun artistsOf(tracks: List<AppMediaItem>): List<Artist> =
        tracks.filter { it.type == MediaType.AUDIO }
            .groupBy { it.artistId }
            .map { (id, list) ->
                val ordered = list.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNo }))
                Artist(
                    id = id,
                    name = ordered.first().artist,
                    albumCount = ordered.map { it.albumId }.distinct().size,
                    trackCount = ordered.size,
                    durationMs = ordered.sumOf { it.durationMs },
                    artworkUri = ordered.firstNotNullOfOrNull { it.artworkUri },
                    tracks = ordered
                )
            }
            .sortedBy { it.name.lowercase() }

    suspend fun loadVideo(context: Context): List<AppMediaItem> {
        videoCache?.let { return it }
        return withContext(Dispatchers.IO) {
            videoCache ?: queryVideo(context).also { videoCache = it }
        }
    }

    private fun queryVideo(context: Context): List<AppMediaItem> {
        val items = mutableListOf<AppMediaItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.ARTIST,
            MediaStore.Video.Media.DURATION
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Video.Media.TITLE} ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.ARTIST)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                items += AppMediaItem(
                    id = id,
                    title = normalizeTitle(c.getString(titleCol)),
                    artist = normalizeArtist(c.getString(artistCol)),
                    durationMs = c.getLong(durCol),
                    uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()),
                    type = MediaType.VIDEO,
                    pillar = Pillar.VIDEO
                )
            }
        }
        return items
    }
}
