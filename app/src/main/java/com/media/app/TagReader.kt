package com.media.app

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

// ============================================================================
//  TAG READER
//
//  MediaStore gives a title, an artist and an album. Everything the blueprint
//  asks for beyond that - lyrics, ReplayGain, MusicBrainz identifiers, the
//  real bit depth of a FLAC - is in the file and nowhere else, and
//  MediaMetadataRetriever exposes none of it.
//
//  So the file is read directly, by format:
//      ID3v2 (MP3, and inside WAV/AIFF)   frames, TXXX, USLT, SYLT, UFID
//      FLAC                               STREAMINFO, Vorbis comments
//      Ogg Vorbis / Opus                  identification + comment packets
//      MP4 / M4A (AAC, ALAC)              ilst atoms, stsd sample entry
//      WAV / AIFF                         fmt / COMM chunks
//
//  Random access only: a tag is read frame by frame, so a 5 MB embedded cover
//  is skipped over rather than read. Nothing here writes to a file - the
//  blueprint's "never silently overwrite tags" is enforced by there being no
//  writer at all.
// ============================================================================

/** Random-access bytes. A file channel in the app, a byte array in tests. */
interface ByteSource {
    val size: Long
    /** Up to [len] bytes from [pos]; fewer at the end, empty past it. */
    fun read(pos: Long, len: Int): ByteArray
}

class ArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(pos: Long, len: Int): ByteArray {
        if (pos < 0 || pos >= bytes.size || len <= 0) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), pos + len).toInt()
        return bytes.copyOfRange(pos.toInt(), end)
    }
}

class ChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long = runCatching { channel.size() }.getOrDefault(0L)
    override fun read(pos: Long, len: Int): ByteArray {
        if (pos < 0 || pos >= size || len <= 0) return ByteArray(0)
        val want = minOf(len.toLong(), size - pos).toInt()
        val buf = ByteBuffer.allocate(want)
        var at = pos
        while (buf.hasRemaining()) {
            val n = channel.read(buf, at)
            if (n <= 0) break
            at += n
        }
        return buf.array().copyOf(buf.position())
    }
}

/** One timed lyric line. */
data class LyricLine(val timeMs: Long, val text: String)

/** Everything a file says about itself, as far as the reader could tell. */
data class FileTags(
    val container: String? = null,
    val codec: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val date: String? = null,
    val genre: String? = null,
    val composer: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val isrc: String? = null,
    val mbRecordingId: String? = null,
    val mbReleaseId: String? = null,
    val mbReleaseGroupId: String? = null,
    val mbArtistId: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val durationMs: Long? = null,
    val lossless: Boolean? = null,
    val replayGainTrack: Float? = null,
    val replayGainAlbum: Float? = null,
    val peakTrack: Float? = null,
    val peakAlbum: Float? = null,
    val lyrics: String? = null,
    val syncedLyrics: List<LyricLine>? = null,
    val hasPicture: Boolean = false
) {
    fun mergedWith(o: FileTags): FileTags = FileTags(
        container = container ?: o.container,
        codec = codec ?: o.codec,
        title = title ?: o.title,
        artist = artist ?: o.artist,
        album = album ?: o.album,
        albumArtist = albumArtist ?: o.albumArtist,
        date = date ?: o.date,
        genre = genre ?: o.genre,
        composer = composer ?: o.composer,
        track = track ?: o.track,
        disc = disc ?: o.disc,
        isrc = isrc ?: o.isrc,
        mbRecordingId = mbRecordingId ?: o.mbRecordingId,
        mbReleaseId = mbReleaseId ?: o.mbReleaseId,
        mbReleaseGroupId = mbReleaseGroupId ?: o.mbReleaseGroupId,
        mbArtistId = mbArtistId ?: o.mbArtistId,
        sampleRate = sampleRate ?: o.sampleRate,
        bitDepth = bitDepth ?: o.bitDepth,
        channels = channels ?: o.channels,
        durationMs = durationMs ?: o.durationMs,
        lossless = lossless ?: o.lossless,
        replayGainTrack = replayGainTrack ?: o.replayGainTrack,
        replayGainAlbum = replayGainAlbum ?: o.replayGainAlbum,
        peakTrack = peakTrack ?: o.peakTrack,
        peakAlbum = peakAlbum ?: o.peakAlbum,
        lyrics = lyrics ?: o.lyrics,
        syncedLyrics = syncedLyrics ?: o.syncedLyrics,
        hasPicture = hasPicture || o.hasPicture
    )
}

object TagReader {

    /** Tags larger than this are not read whole even when unsynchronised. */
    private const val MAX_TAG = 16 * 1024 * 1024
    /** A lyrics frame bigger than this is not lyrics. */
    private const val MAX_TEXT_FRAME = 512 * 1024

    fun read(src: ByteSource): FileTags = try {
        readOrThrow(src)
    } catch (e: IOException) {
        FileTags()
    } catch (e: RuntimeException) {
        // A malformed file must never take the player down with it.
        FileTags()
    }

    private fun readOrThrow(src: ByteSource): FileTags {
        val head = src.read(0, 12)
        if (head.size < 4) return FileTags()
        val magic = String(head, 0, 4, Charsets.ISO_8859_1)
        return when {
            magic.startsWith("ID3") -> {
                val (tags, end) = id3(src, 0)
                // ID3 in front of FLAC exists in the wild (bad taggers).
                val after = src.read(end, 4)
                if (after.size == 4 && String(after, Charsets.ISO_8859_1) == "fLaC") {
                    tags.mergedWith(flac(src, end))
                } else {
                    tags.copy(container = "MP3", codec = tags.codec ?: "MP3", lossless = false)
                }
            }
            magic == "fLaC" -> flac(src, 0)
            magic == "OggS" -> ogg(src)
            magic == "RIFF" && head.size >= 12 && String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE" -> wav(src)
            magic == "FORM" && head.size >= 12 &&
                String(head, 8, 4, Charsets.ISO_8859_1).let { it == "AIFF" || it == "AIFC" } -> aiff(src)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1).let {
                it == "ftyp" || it == "moov" || it == "mdat" || it == "free" || it == "wide"
            } -> mp4(src)
            // Raw MPEG frames with no tag: sync word.
            (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 ->
                FileTags(container = "MP3", codec = "MP3", lossless = false)
            else -> FileTags()
        }
    }

    // ------------------------------------------------------------------ ID3v2

    private fun synchsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun be24(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at + 2].toInt() and 0xFF)

    private fun be16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun le32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF)) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    /** Removes the 0x00 stuffed after every 0xFF by unsynchronisation. */
    private fun unsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if ((b[i].toInt() and 0xFF) == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    /** Returns the tags and the offset just past the ID3 tag. */
    internal fun id3(src: ByteSource, at: Long): Pair<FileTags, Long> {
        val h = src.read(at, 10)
        if (h.size < 10) return FileTags() to at
        val major = h[3].toInt() and 0xFF
        val flags = h[5].toInt() and 0xFF
        val size = synchsafe(h, 6)
        val end = at + 10 + size + if (flags and 0x10 != 0) 10 else 0
        if (major !in 2..4 || size <= 0) return FileTags() to end

        val tagUnsync = flags and 0x80 != 0
        // v2.3 unsynchronisation applies to the whole tag, so the tag has to
        // be read whole. Otherwise frames are read one at a time.
        val whole: ByteSource? = if (tagUnsync && major < 4) {
            if (size > MAX_TAG) return FileTags() to end
            ArraySource(unsync(src.read(at + 10, size)))
        } else null
        val base = if (whole != null) 0L else at + 10
        val body = whole ?: src
        val limit = base + size

        var pos = base
        if (flags and 0x40 != 0 && major >= 3) {
            val eh = body.read(pos, 4)
            if (eh.size < 4) return FileTags() to end
            pos += if (major == 4) synchsafe(eh, 0).toLong() else be32(eh, 0).toLong() + 4
        }

        val acc = Id3Acc()
        val headerLen = if (major == 2) 6 else 10
        while (pos + headerLen <= limit) {
            val fh = body.read(pos, headerLen)
            if (fh.size < headerLen || fh[0].toInt() == 0) break
            val id: String
            val fsize: Int
            var fflags = 0
            if (major == 2) {
                id = String(fh, 0, 3, Charsets.ISO_8859_1)
                fsize = be24(fh, 3)
            } else {
                id = String(fh, 0, 4, Charsets.ISO_8859_1)
                fsize = if (major == 4) synchsafe(fh, 4) else be32(fh, 4)
                fflags = be16(fh, 8)
            }
            if (fsize <= 0 || pos + headerLen + fsize > limit) break
            val dataAt = pos + headerLen
            pos = dataAt + fsize
            val key = ID3V22[id] ?: id
            if (key == "APIC" || key == "PIC") { acc.picture = true; continue }
            if (key !in WANTED || fsize > MAX_TEXT_FRAME) continue
            var data = body.read(dataAt, fsize)
            if (major == 4) {
                // Data length indicator, then per-frame unsynchronisation.
                if (fflags and 0x0001 != 0 && data.size >= 4) data = data.copyOfRange(4, data.size)
                if (fflags and 0x0002 != 0) data = unsync(data)
                // Compressed or encrypted frames are left alone.
                if (fflags and 0x000C != 0) continue
            } else if (major == 3 && fflags and 0x00C0 != 0) continue
            acc.frame(key, data)
        }
        return acc.build() to end
    }

    private val ID3V22 = mapOf(
        "TT2" to "TIT2", "TP1" to "TPE1", "TAL" to "TALB", "TP2" to "TPE2", "TRK" to "TRCK",
        "TPA" to "TPOS", "TYE" to "TYER", "TCO" to "TCON", "TCM" to "TCOM", "TXX" to "TXXX",
        "ULT" to "USLT", "SLT" to "SYLT", "UFI" to "UFID", "TRC" to "TSRC"
    )

    private val WANTED = setOf(
        "TIT2", "TPE1", "TALB", "TPE2", "TRCK", "TPOS", "TYER", "TDRC", "TCON", "TCOM", "TSRC",
        "TXXX", "USLT", "SYLT", "UFID"
    )

    private fun charset(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    /** Index just past the terminator of an encoded string starting at [from]. */
    private fun terminated(b: ByteArray, from: Int, enc: Int): Pair<String, Int> {
        val wide = enc == 1 || enc == 2
        var i = from
        if (wide) {
            while (i + 1 < b.size && !(b[i].toInt() == 0 && b[i + 1].toInt() == 0)) i += 2
            val s = String(b, from, (i - from).coerceAtLeast(0), charset(enc))
            return s to (i + 2).coerceAtMost(b.size)
        }
        while (i < b.size && b[i].toInt() != 0) i++
        return String(b, from, i - from, charset(enc)) to (i + 1).coerceAtMost(b.size)
    }

    private fun text(b: ByteArray, from: Int, enc: Int): String {
        if (from >= b.size) return ""
        return String(b, from, b.size - from, charset(enc)).trimEnd('\u0000').trim()
    }

    private class Id3Acc {
        val t = HashMap<String, String>()
        val txxx = HashMap<String, String>()
        var lyrics: String? = null
        var synced: List<LyricLine>? = null
        var recording: String? = null
        var picture = false

        fun frame(id: String, d: ByteArray) {
            if (d.isEmpty()) return
            when (id) {
                "TXXX" -> {
                    val enc = d[0].toInt()
                    val (desc, next) = terminated(d, 1, enc)
                    txxx[desc.trim().lowercase()] = text(d, next, enc).substringBefore('\u0000')
                }
                "USLT" -> {
                    if (d.size < 5) return
                    val enc = d[0].toInt()
                    val (_, next) = terminated(d, 4, enc)
                    val body = text(d, next, enc)
                    // Keep the longest: some files carry an empty USLT first.
                    if (body.isNotBlank() && body.length > (lyrics?.length ?: 0)) lyrics = body
                }
                "SYLT" -> {
                    if (d.size < 6) return
                    val enc = d[0].toInt()
                    val format = d[4].toInt()
                    if (format != 2) return   // only millisecond stamps
                    var (_, i) = terminated(d, 6, enc)
                    val out = ArrayList<LyricLine>()
                    while (i < d.size) {
                        val (line, next) = terminated(d, i, enc)
                        if (next + 4 > d.size) break
                        out += LyricLine(be32(d, next).toLong() and 0xFFFFFFFFL, line.trimEnd('\n', '\r'))
                        i = next + 4
                    }
                    if (out.size >= 2) synced = out.sortedBy { it.timeMs }
                }
                "UFID" -> {
                    val (owner, next) = terminated(d, 0, 0)
                    if (owner.contains("musicbrainz.org") && next < d.size) {
                        recording = String(d, next, d.size - next, Charsets.ISO_8859_1).trim('\u0000').trim()
                    }
                }
                else -> {
                    val enc = d[0].toInt()
                    // v2.4 multi-value frames are NUL separated; the first wins.
                    t[id] = text(d, 1, enc).split('\u0000').first().trim()
                }
            }
        }

        fun build(): FileTags = FileTags(
            title = t["TIT2"].nz(),
            artist = t["TPE1"].nz(),
            album = t["TALB"].nz(),
            albumArtist = t["TPE2"].nz(),
            date = (t["TDRC"] ?: t["TYER"]).nz(),
            genre = t["TCON"].nz()?.let(::id3Genre),
            composer = t["TCOM"].nz(),
            track = t["TRCK"].leadingInt(),
            disc = t["TPOS"].leadingInt(),
            isrc = t["TSRC"].nz(),
            mbRecordingId = recording.nz(),
            mbReleaseId = txxx["musicbrainz album id"].nz(),
            mbReleaseGroupId = txxx["musicbrainz release group id"].nz(),
            mbArtistId = txxx["musicbrainz artist id"].nz()?.substringBefore('/'),
            replayGainTrack = gainOf(txxx["replaygain_track_gain"]),
            replayGainAlbum = gainOf(txxx["replaygain_album_gain"]),
            peakTrack = txxx["replaygain_track_peak"]?.trim()?.toFloatOrNull(),
            peakAlbum = txxx["replaygain_album_peak"]?.trim()?.toFloatOrNull(),
            lyrics = lyrics,
            syncedLyrics = synced,
            hasPicture = picture
        )
    }

    /** "(17)" and "(17)Rock" are ID3v1 genre references. */
    private fun id3Genre(g: String): String {
        val m = Regex("""^\((\d+)\)(.*)$""").find(g) ?: return g
        val rest = m.groupValues[2].trim()
        if (rest.isNotEmpty()) return rest
        return ID3V1_GENRES.getOrNull(m.groupValues[1].toInt()) ?: g
    }

    private val ID3V1_GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz",
        "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno",
        "Industrial", "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno",
        "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental",
        "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise", "AlternRock", "Bass", "Soul",
        "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream",
        "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk",
        "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical",
        "Rock & Roll", "Hard Rock"
    )

    // ------------------------------------------------------ Vorbis comments

    /** A Vorbis comment block (FLAC, Ogg Vorbis, Opus): little-endian lengths. */
    internal fun vorbisComments(b: ByteArray, from: Int): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var i = from
        if (i + 4 > b.size) return out
        val vendor = le32(b, i).toInt()
        i += 4 + vendor
        if (vendor < 0 || i + 4 > b.size) return out
        val count = le32(b, i).toInt()
        i += 4
        repeat(count.coerceAtMost(10_000)) {
            if (i + 4 > b.size) return out
            val len = le32(b, i).toInt()
            i += 4
            if (len < 0 || i + len > b.size) return out
            val entry = String(b, i, len, Charsets.UTF_8)
            i += len
            val eq = entry.indexOf('=')
            if (eq > 0) out.getOrPut(entry.substring(0, eq).uppercase()) { mutableListOf() } += entry.substring(eq + 1)
        }
        return out
    }

    internal fun fromVorbis(c: Map<String, List<String>>): FileTags {
        fun v(k: String) = c[k]?.firstOrNull().nz()
        val lyricText = v("LYRICS") ?: v("UNSYNCEDLYRICS") ?: v("UNSYNCED LYRICS")
        return FileTags(
            title = v("TITLE"),
            artist = c["ARTIST"]?.filter { it.isNotBlank() }?.joinToString(", ").nz(),
            album = v("ALBUM"),
            albumArtist = v("ALBUMARTIST") ?: v("ALBUM ARTIST"),
            date = v("DATE") ?: v("YEAR") ?: v("ORIGINALDATE"),
            genre = v("GENRE"),
            composer = v("COMPOSER"),
            track = v("TRACKNUMBER").leadingInt(),
            disc = v("DISCNUMBER").leadingInt(),
            isrc = v("ISRC"),
            mbRecordingId = v("MUSICBRAINZ_TRACKID"),
            mbReleaseId = v("MUSICBRAINZ_ALBUMID"),
            mbReleaseGroupId = v("MUSICBRAINZ_RELEASEGROUPID"),
            mbArtistId = v("MUSICBRAINZ_ARTISTID"),
            replayGainTrack = gainOf(v("REPLAYGAIN_TRACK_GAIN")),
            replayGainAlbum = gainOf(v("REPLAYGAIN_ALBUM_GAIN")),
            peakTrack = v("REPLAYGAIN_TRACK_PEAK")?.toFloatOrNull(),
            peakAlbum = v("REPLAYGAIN_ALBUM_PEAK")?.toFloatOrNull(),
            lyrics = lyricText,
            hasPicture = c.containsKey("METADATA_BLOCK_PICTURE") || c.containsKey("COVERART")
        )
    }

    // ------------------------------------------------------------------- FLAC

    private fun flac(src: ByteSource, at: Long): FileTags {
        var pos = at + 4
        var tags = FileTags(container = "FLAC", codec = "FLAC", lossless = true)
        var guard = 0
        while (guard++ < 128) {
            val h = src.read(pos, 4)
            if (h.size < 4) break
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = be24(h, 1)
            val dataAt = pos + 4
            when (type) {
                0 -> {
                    val s = src.read(dataAt, 18)
                    if (s.size >= 18) {
                        val rate = ((s[10].toInt() and 0xFF) shl 12) or ((s[11].toInt() and 0xFF) shl 4) or
                            ((s[12].toInt() and 0xF0) shr 4)
                        val channels = ((s[12].toInt() and 0x0E) shr 1) + 1
                        val bits = (((s[12].toInt() and 0x01) shl 4) or ((s[13].toInt() and 0xF0) shr 4)) + 1
                        val total = ((s[13].toLong() and 0x0F) shl 32) or (be32(s, 14).toLong() and 0xFFFFFFFFL)
                        tags = tags.copy(
                            sampleRate = rate.takeIf { it > 0 },
                            channels = channels,
                            bitDepth = bits,
                            durationMs = if (rate > 0 && total > 0) total * 1000 / rate else null
                        )
                    }
                }
                4 -> if (len in 1..MAX_TEXT_FRAME * 4) {
                    tags = fromVorbis(vorbisComments(src.read(dataAt, len), 0)).mergedWith(tags)
                }
                6 -> tags = tags.copy(hasPicture = true)
            }
            pos = dataAt + len
            if (last) break
        }
        return tags
    }

    // -------------------------------------------------------------------- Ogg

    /** Reassembles the first few packets of the first logical stream. */
    internal fun oggPackets(src: ByteSource, max: Int = 3): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        var current = java.io.ByteArrayOutputStream()
        var pos = 0L
        var serial = -1L
        var pages = 0
        while (packets.size < max && pages++ < 512) {
            val h = src.read(pos, 27)
            if (h.size < 27 || String(h, 0, 4, Charsets.ISO_8859_1) != "OggS") break
            val pageSerial = le32(h, 14)
            val segments = h[26].toInt() and 0xFF
            val table = src.read(pos + 27, segments)
            if (table.size < segments) break
            val bodyLen = table.sumOf { it.toInt() and 0xFF }
            val body = src.read(pos + 27 + segments, bodyLen)
            if (serial == -1L) serial = pageSerial
            if (pageSerial == serial) {
                var off = 0
                for (s in table) {
                    val n = s.toInt() and 0xFF
                    if (off + n > body.size) break
                    current.write(body, off, n)
                    off += n
                    if (n < 255) {
                        packets += current.toByteArray()
                        current = java.io.ByteArrayOutputStream()
                        if (packets.size >= max) break
                    }
                }
                // A comment packet bigger than 1 MB is an embedded picture we
                // do not want to hold.
                if (current.size() > 1024 * 1024) break
            }
            pos += 27 + segments + bodyLen
        }
        return packets
    }

    private fun ogg(src: ByteSource): FileTags {
        val p = oggPackets(src, 2)
        val id = p.getOrNull(0) ?: return FileTags(container = "Ogg")
        val comments = p.getOrNull(1)
        return when {
            id.size >= 16 && id[0].toInt() == 1 && String(id, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> {
                val base = FileTags(
                    container = "Ogg", codec = "Vorbis", lossless = false,
                    channels = id[11].toInt() and 0xFF,
                    sampleRate = le32(id, 12).toInt()
                )
                if (comments != null && comments.size > 7 && comments[0].toInt() == 3)
                    fromVorbis(vorbisComments(comments, 7)).mergedWith(base) else base
            }
            id.size >= 16 && String(id, 0, 8, Charsets.ISO_8859_1) == "OpusHead" -> {
                // Opus always decodes at 48 kHz; the header's rate is the
                // original input's, which is what a person means by it.
                val base = FileTags(
                    container = "Ogg", codec = "Opus", lossless = false,
                    channels = id[9].toInt() and 0xFF,
                    sampleRate = le32(id, 12).toInt().takeIf { it > 0 } ?: 48000
                )
                if (comments != null && comments.size > 8 && String(comments, 0, 8, Charsets.ISO_8859_1) == "OpusTags")
                    fromVorbis(vorbisComments(comments, 8)).mergedWith(base) else base
            }
            id.size >= 5 && id[0].toInt() == 0x7F && String(id, 1, 4, Charsets.ISO_8859_1) == "FLAC" ->
                FileTags(container = "Ogg", codec = "FLAC", lossless = true)
            else -> FileTags(container = "Ogg")
        }
    }

    // --------------------------------------------------------------- WAV/AIFF

    private fun wav(src: ByteSource): FileTags {
        var tags = FileTags(container = "WAV", codec = "PCM", lossless = true)
        var pos = 12L
        var guard = 0
        while (pos + 8 <= src.size && guard++ < 256) {
            val h = src.read(pos, 8)
            if (h.size < 8) break
            val id = String(h, 0, 4, Charsets.ISO_8859_1)
            val len = le32(h, 4)
            val dataAt = pos + 8
            when (id) {
                "fmt " -> {
                    val f = src.read(dataAt, minOf(len, 40L).toInt())
                    if (f.size >= 16) {
                        val format = le16(f, 0)
                        var bits = le16(f, 14)
                        // WAVE_FORMAT_EXTENSIBLE carries the real depth.
                        if (format == 0xFFFE && f.size >= 20) bits = le16(f, 18).takeIf { it > 0 } ?: bits
                        tags = tags.copy(
                            codec = when (format) { 3 -> "PCM float"; 0x55 -> "MP3"; else -> "PCM" },
                            lossless = format != 0x55,
                            channels = le16(f, 2),
                            sampleRate = le32(f, 4).toInt(),
                            bitDepth = bits
                        )
                    }
                }
                "id3 ", "ID3 " -> tags = id3(src, dataAt).first.mergedWith(tags)
                "LIST" -> {
                    val l = src.read(dataAt, minOf(len, 64 * 1024L).toInt())
                    if (l.size >= 4 && String(l, 0, 4, Charsets.ISO_8859_1) == "INFO") {
                        // Chunk text is weaker than an ID3 chunk; only fill gaps.
                        tags = tags.mergedWith(riffInfo(l))
                    }
                }
            }
            pos = dataAt + len + (len and 1)
        }
        return tags
    }

    private fun riffInfo(l: ByteArray): FileTags {
        val m = HashMap<String, String>()
        var i = 4
        while (i + 8 <= l.size) {
            val id = String(l, i, 4, Charsets.ISO_8859_1)
            val len = le32(l, i + 4).toInt()
            if (len < 0 || i + 8 + len > l.size) break
            m[id] = String(l, i + 8, len, Charsets.ISO_8859_1).trim('\u0000').trim()
            i += 8 + len + (len and 1)
        }
        return FileTags(
            title = m["INAM"].nz(), artist = m["IART"].nz(), album = m["IPRD"].nz(),
            genre = m["IGNR"].nz(), date = m["ICRD"].nz(), track = m["ITRK"].leadingInt()
        )
    }

    private fun aiff(src: ByteSource): FileTags {
        var tags = FileTags(container = "AIFF", codec = "PCM", lossless = true)
        var pos = 12L
        var guard = 0
        while (pos + 8 <= src.size && guard++ < 256) {
            val h = src.read(pos, 8)
            if (h.size < 8) break
            val id = String(h, 0, 4, Charsets.ISO_8859_1)
            val len = be32(h, 4).toLong() and 0xFFFFFFFFL
            val dataAt = pos + 8
            when (id) {
                "COMM" -> {
                    val c = src.read(dataAt, 18)
                    if (c.size >= 18) {
                        tags = tags.copy(
                            channels = be16(c, 0),
                            bitDepth = be16(c, 6),
                            sampleRate = extended80(c, 8).toInt()
                        )
                    }
                }
                "ID3 ", "id3 " -> tags = id3(src, dataAt).first.mergedWith(tags)
            }
            pos = dataAt + len + (len and 1)
        }
        return tags
    }

    /** IEEE 754 80-bit extended, as AIFF stores its sample rate. */
    internal fun extended80(b: ByteArray, at: Int): Double {
        val exp = ((b[at].toInt() and 0x7F) shl 8) or (b[at + 1].toInt() and 0xFF)
        var mant = 0L
        for (i in 0 until 8) mant = (mant shl 8) or (b[at + 2 + i].toLong() and 0xFF)
        if (exp == 0 && mant == 0L) return 0.0
        // The mantissa is unsigned 64-bit; halve it to keep it positive.
        val m = (mant ushr 1).toDouble() * 2.0 + (mant and 1L).toDouble()
        val v = m * Math.pow(2.0, (exp - 16383 - 63).toDouble())
        return if (b[at].toInt() and 0x80 != 0) -v else v
    }

    // -------------------------------------------------------------------- MP4

    private class Atom(val type: String, val at: Long, val dataAt: Long, val end: Long)

    private fun atoms(src: ByteSource, from: Long, to: Long): List<Atom> {
        val out = ArrayList<Atom>()
        var pos = from
        var guard = 0
        while (pos + 8 <= to && guard++ < 4096) {
            val h = src.read(pos, 16)
            if (h.size < 8) break
            var size = be32(h, 0).toLong() and 0xFFFFFFFFL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L && h.size >= 16) {
                size = (be32(h, 8).toLong() shl 32) or (be32(h, 12).toLong() and 0xFFFFFFFFL)
                header = 16
            } else if (size == 0L) {
                size = to - pos
            }
            if (size < header) break
            out += Atom(type, pos, pos + header, minOf(pos + size, to))
            pos += size
        }
        return out
    }

    private fun child(src: ByteSource, parent: Atom, type: String, skip: Long = 0): Atom? =
        atoms(src, parent.dataAt + skip, parent.end).firstOrNull { it.type == type }

    private fun mp4(src: ByteSource): FileTags {
        val top = atoms(src, 0, src.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: return FileTags(container = "MP4")
        var tags = FileTags(container = "MP4")

        // Codec and format from the first audio sample entry.
        for (trak in atoms(src, moov.dataAt, moov.end).filter { it.type == "trak" }) {
            val mdia = child(src, trak, "mdia") ?: continue
            val minf = child(src, mdia, "minf") ?: continue
            val stbl = child(src, minf, "stbl") ?: continue
            val stsd = child(src, stbl, "stsd") ?: continue
            // stsd is a full box: version/flags + entry count before entries.
            val entry = atoms(src, stsd.dataAt + 8, stsd.end).firstOrNull() ?: continue
            val codec = when (entry.type) {
                "mp4a" -> "AAC"
                "alac" -> "ALAC"
                "fLaC" -> "FLAC"
                "Opus" -> "Opus"
                "ac-3" -> "AC-3"
                "ec-3" -> "E-AC-3"
                else -> continue
            }
            val se = src.read(entry.dataAt, 28)
            if (se.size < 28) continue
            var channels = be16(se, 16)
            var bits = be16(se, 18)
            var rate = (be32(se, 24).toLong() and 0xFFFFFFFFL ushr 16).toInt()
            if (codec == "ALAC") {
                // The inner 'alac' box holds the real configuration.
                atoms(src, entry.dataAt + 28, entry.end).firstOrNull { it.type == "alac" }?.let { cfg ->
                    val c = src.read(cfg.dataAt + 4, 24)
                    if (c.size >= 24) {
                        bits = c[5].toInt() and 0xFF
                        channels = c[9].toInt() and 0xFF
                        rate = be32(c, 20)
                    }
                }
            }
            tags = tags.copy(
                codec = codec,
                lossless = codec == "ALAC" || codec == "FLAC",
                channels = channels.takeIf { it > 0 },
                // AAC reports 16 in the sample entry regardless: it has no bit
                // depth, so it is not shown as one.
                bitDepth = if (codec == "ALAC" || codec == "FLAC") bits.takeIf { it > 0 } else null,
                sampleRate = rate.takeIf { it > 0 }
            )
            break
        }

        val udta = child(src, moov, "udta") ?: return tags
        val meta = child(src, udta, "meta") ?: return tags
        // meta is a full box in ISO files; QuickTime writers omit the 4 bytes.
        val ilst = child(src, meta, "ilst", skip = 4) ?: child(src, meta, "ilst") ?: return tags
        val m = HashMap<String, String>()
        val free = HashMap<String, String>()
        var track: Int? = null
        var disc: Int? = null
        var picture = false
        for (item in atoms(src, ilst.dataAt, ilst.end)) {
            if (item.end - item.dataAt > MAX_TEXT_FRAME && item.type != "covr") continue
            if (item.type == "covr") { picture = true; continue }
            val kids = atoms(src, item.dataAt, item.end)
            val data = kids.firstOrNull { it.type == "data" } ?: continue
            val payload = src.read(data.dataAt + 8, (data.end - data.dataAt - 8).toInt().coerceAtLeast(0))
            when (item.type) {
                "trkn" -> if (payload.size >= 4) track = be16(payload, 2).takeIf { it > 0 }
                "disk" -> if (payload.size >= 4) disc = be16(payload, 2).takeIf { it > 0 }
                "----" -> {
                    val name = kids.firstOrNull { it.type == "name" }?.let {
                        String(src.read(it.dataAt + 4, (it.end - it.dataAt - 4).toInt()), Charsets.UTF_8)
                    } ?: continue
                    free[name.lowercase()] = String(payload, Charsets.UTF_8).trim()
                }
                else -> m[item.type] = String(payload, Charsets.UTF_8).trim()
            }
        }
        return tags.copy(
            title = m["©nam"].nz(),
            artist = m["©ART"].nz(),
            album = m["©alb"].nz(),
            albumArtist = m["aART"].nz(),
            date = m["©day"].nz(),
            genre = m["©gen"].nz(),
            composer = m["©wrt"].nz(),
            track = track,
            disc = disc,
            isrc = free["isrc"].nz(),
            lyrics = m["©lyr"].nz(),
            mbRecordingId = free["musicbrainz track id"].nz(),
            mbReleaseId = free["musicbrainz album id"].nz(),
            mbReleaseGroupId = free["musicbrainz release group id"].nz(),
            mbArtistId = free["musicbrainz artist id"].nz(),
            replayGainTrack = gainOf(free["replaygain_track_gain"]),
            replayGainAlbum = gainOf(free["replaygain_album_gain"]),
            peakTrack = free["replaygain_track_peak"]?.toFloatOrNull(),
            peakAlbum = free["replaygain_album_peak"]?.toFloatOrNull(),
            hasPicture = picture
        )
    }
}

// ------------------------------------------------------------------ helpers

private fun String?.nz(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** "3/12" -> 3, "07" -> 7. */
private fun String?.leadingInt(): Int? =
    this?.trim()?.let { Regex("""^\d+""").find(it)?.value?.toIntOrNull() }?.takeIf { it > 0 }

/** "-6.54 dB" -> -6.54. */
internal fun gainOf(s: String?): Float? =
    s?.let { Regex("""[-+]?\d+(?:\.\d+)?""").find(it)?.value?.toFloatOrNull() }
