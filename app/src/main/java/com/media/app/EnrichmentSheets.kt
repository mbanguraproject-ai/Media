package com.media.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.media.app.Matching.isUsable

// ============================================================================
//  ENRICHMENT SHEETS
//
//  Three sheets, built exactly like the long-press "Add to" sheet - same
//  scrim, same 22dp glass top, same grabber, same 38dp icon tiles - so they
//  read as more of the app rather than as a new one bolted on:
//
//    Artwork      what is wrong with this cover, and every way to fix it
//    Details      the file, its tags, its identifiers, where each answer came
//                 from and how sure the app is about it
//    Audio path   source -> decoder -> chain -> mixer -> device
// ============================================================================

private val SHEET_TOP = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

@Composable
internal fun GlassSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MediaColors.Scrim).clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.fillMaxWidth().clip(SHEET_TOP)
                .background(MediaColors.Modal).border(1.dp, MediaColors.Fill, SHEET_TOP)
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(Space.xl, Space.lg, Space.xl, Space.xl)
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp)
                .clip(CircleShape).background(MediaColors.FillStrong))
            Spacer(Modifier.height(Space.lg))
            content()
        }
    }
}

@Composable
private fun SheetHeader(item: AppMediaItem, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CoverArt(item, Modifier.size(46.dp), corner = 10, targetPx = 144)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = Typo.Section, color = MediaColors.Cream,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Typo.Secondary, color = MediaColors.CreamFaint,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TileAction(
    icon: ImageVector, label: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit
) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(MediaColors.Fill)
            .pressScale(haptic = true, enabled = enabled, onClick = onClick)
            .padding(vertical = Space.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (enabled) MediaColors.Cream else MediaColors.CreamFaint,
            modifier = Modifier.size(IconSize.md))
        Spacer(Modifier.height(Space.xs))
        Text(label, style = Typo.Tertiary, color = MediaColors.CreamDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Label(text: String, top: Boolean = true) {
    Text(text.uppercase(), style = Typo.Micro, color = MediaColors.CreamFaint,
        modifier = Modifier.padding(top = if (top) Space.lg else 0.dp, bottom = Space.sm))
}

@Composable
private fun Fact(name: String, value: String?, accent: Boolean = false) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(name, style = Typo.Secondary, color = MediaColors.CreamFaint, modifier = Modifier.width(128.dp))
        Text(value, style = Typo.Secondary, color = if (accent) MediaColors.Accent else MediaColors.Cream,
            modifier = Modifier.weight(1f))
    }
}

private fun issueText(s: ArtStatus?): String = when {
    s == null -> "Checking the cover…"
    s.fixed != null -> when (s.fixed.source) {
        "custom" -> "Your own cover"
        else -> "Fixed from Cover Art Archive · ${(s.fixed.confidence * 100).toInt()}% match"
    }
    s.issue == ArtIssue.MISSING -> "No cover in this file"
    s.issue == ArtIssue.LOW_RES -> "Low resolution · ${s.width}×${s.height}"
    s.issue == ArtIssue.PLACEHOLDER -> "Blank placeholder cover"
    else -> "Cover looks fine · ${s.width}×${s.height}"
}

// ------------------------------------------------------------------ ARTWORK

@Composable
fun ArtworkSheet(
    item: AppMediaItem,
    /** Every track the fix will apply to: the album, or just this one. */
    scope: List<AppMediaItem>,
    onlineAllowed: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val launch = rememberCoroutineScope()
    val version by ArtworkStore.version.collectAsState()
    var status by remember(item.id, version) { mutableStateOf<ArtStatus?>(null) }
    LaunchedEffect(item.id, version) {
        status = withContext(Dispatchers.IO) { ArtworkRepair.inspect(context, item) }
    }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<ArtCandidate>>(emptyList()) }
    var applying by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val ids = remember(scope) { scope.map { it.id }.ifEmpty { listOf(item.id) } }

    fun search() {
        searching = true; failed = false; message = null
        launch.launch {
            val tags = withContext(Dispatchers.IO) { readTags(context, item.uri) }
            val found = ArtworkRepair.candidates(item, tags)
            searching = false; searched = true
            if (found == null) failed = true else candidates = found
        }
    }
    // Online lookups on: search straight away. Off: the button is the consent.
    LaunchedEffect(item.id) { if (onlineAllowed) search() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) launch.launch {
            val ok = ArtworkRepair.applyCustom(context, item, ids, uri)
            message = if (ok) "Cover updated" else "That image couldn't be read"
        }
    }

    GlassSheet(onDismiss) {
        SheetHeader(item, issueText(status))
        if (scope.size > 1) {
            Spacer(Modifier.height(Space.xs))
            Text("Applies to all ${scope.size} tracks of ${item.album}", style = Typo.Tertiary,
                color = MediaColors.CreamFaint)
        }
        Spacer(Modifier.height(Space.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            TileAction(Icons.Filled.Search, if (searched) "Search again" else "Find cover",
                Modifier.weight(1f), enabled = !searching) { search() }
            TileAction(Icons.Filled.Image, "From gallery", Modifier.weight(1f)) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            TileAction(Icons.Filled.Restore, "File's own", Modifier.weight(1f),
                enabled = status?.fixed != null) {
                ArtworkStore.remove(ids)
                message = "Back to the file's own cover"
            }
        }
        message?.let {
            Spacer(Modifier.height(Space.md))
            Text(it, style = Typo.Secondary, color = MediaColors.Accent)
        }
        Spacer(Modifier.height(Space.md))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            when {
                searching -> Text("Asking MusicBrainz…", style = Typo.Secondary, color = MediaColors.CreamFaint,
                    modifier = Modifier.padding(vertical = Space.md))
                failed -> Text("Couldn't reach MusicBrainz. Check the connection and search again.",
                    style = Typo.Secondary, color = MediaColors.CreamFaint, modifier = Modifier.padding(vertical = Space.md))
                searched && candidates.isEmpty() -> Text(
                    "No covers found. Correct the title, artist or album in Edit details and search again, or pick one from your gallery.",
                    style = Typo.Secondary, color = MediaColors.CreamFaint, modifier = Modifier.padding(vertical = Space.md))
                candidates.isNotEmpty() -> {
                    Label("Covers from Cover Art Archive", top = false)
                    candidates.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm),
                            modifier = Modifier.padding(bottom = Space.sm)) {
                            row.forEach { c ->
                                CandidateTile(c, busy = applying == c.key, modifier = Modifier.weight(1f)) {
                                    if (applying != null) return@CandidateTile
                                    applying = c.key
                                    launch.launch {
                                        val ok = ArtworkRepair.apply(item, ids, c)
                                        applying = null
                                        message = if (ok) "Cover updated" else "That release has no full-size cover. Try another."
                                    }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateTile(c: ArtCandidate, busy: Boolean, modifier: Modifier, onPick: () -> Unit) {
    var thumb by remember(c.key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(c.key) {
        thumb = ArtworkRepair.thumbnail(c)?.let { bytes ->
            withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
    }
    val confident = c.score >= Matching.CONFIDENT
    Column(modifier.pressScale(haptic = true, enabled = thumb != null, onClick = onPick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Radius.sm))
                .background(MediaColors.Fill),
            contentAlignment = Alignment.Center
        ) {
            thumb?.let {
                Image(it, c.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (busy) {
                Box(Modifier.fillMaxSize().background(MediaColors.Scrim), contentAlignment = Alignment.Center) {
                    Text("Applying…", style = Typo.Tertiary, color = MediaColors.Cream)
                }
            }
        }
        Spacer(Modifier.height(Space.xs))
        Text(c.title, style = Typo.Tertiary, color = MediaColors.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(c.year, "${(c.score * 100).toInt()}%").joinToString(" · "),
            style = Typo.Tertiary, color = if (confident) MediaColors.Accent else MediaColors.CreamFaint, maxLines = 1
        )
    }
}

// ------------------------------------------------------------------ DETAILS

@Composable
fun TrackDetailsSheet(
    item: AppMediaItem,
    onlineAllowed: Boolean,
    onApply: (title: String, artist: String) -> Unit,
    onFixArtwork: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val launch = rememberCoroutineScope()
    var info by remember(item.id) { mutableStateOf<Pair<SourceFormat, FileTags?>?>(null) }
    LaunchedEffect(item.id) {
        info = AudioInfo.source(context, item)
    }
    var art by remember(item.id) { mutableStateOf<ArtStatus?>(null) }
    LaunchedEffect(item.id) {
        art = withContext(Dispatchers.IO) { ArtworkRepair.inspect(context, item) }
    }
    var lyrics by remember(item.id) { mutableStateOf<LyricsState>(LyricsState.Loading) }
    LaunchedEffect(item.id) {
        lyrics = LyricsEngine.load(context, item, online = false)
    }
    var identifying by remember { mutableStateOf(false) }
    var match by remember { mutableStateOf<Pair<MbRecording, Float>?>(null) }
    var identifyNote by remember { mutableStateOf<String?>(null) }

    GlassSheet(onDismiss) {
        SheetHeader(item, item.artist)
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            val (fmt, tags) = info ?: (null to null)
            Label("Audio")
            Fact("Format", fmt?.badge ?: "Reading…")
            Fact("Quality", when {
                fmt == null -> null
                fmt.hiRes -> "Hi-Res lossless"
                fmt.lossless == true -> "Lossless"
                fmt.lossless == false -> "Lossy"
                else -> null
            }, accent = fmt?.hiRes == true)
            Fact("Channels", fmt?.channels?.let { if (it == 1) "Mono" else if (it == 2) "Stereo" else "$it channels" })
            Fact("Bitrate", fmt?.bitrateKbps?.let { "$it kbps" })
            Fact("Length", fmt?.durationMs?.let { fmtClock(it) })
            Fact("Size", fmt?.sizeBytes?.let { "%.1f MB".format(java.util.Locale.ROOT, it / 1048576.0) })
            Fact("Container", tags?.container)
            Fact("ReplayGain", listOfNotNull(
                tags?.replayGainTrack?.let { "track %+.2f dB".format(java.util.Locale.ROOT, it) },
                tags?.replayGainAlbum?.let { "album %+.2f dB".format(java.util.Locale.ROOT, it) }
            ).joinToString(" · ").ifEmpty { null })

            Label("Tags in the file")
            Fact("Title", tags?.title)
            Fact("Artist", tags?.artist)
            Fact("Album", tags?.album)
            Fact("Album artist", tags?.albumArtist)
            Fact("Year", tags?.date)
            Fact("Genre", tags?.genre)
            Fact("Composer", tags?.composer)
            Fact("Track", listOfNotNull(tags?.track?.let { "Track $it" }, tags?.disc?.let { "disc $it" })
                .joinToString(", ").ifEmpty { null })
            Fact("ISRC", tags?.isrc)
            if (tags != null && tags.title == null && tags.artist == null) {
                Text("This file carries no readable tags; the library shows what Android indexed.",
                    style = Typo.Tertiary, color = MediaColors.CreamFaint)
            }
            Fact("File", item.relPath.ifBlank { null })

            Label("Identifiers")
            Fact("Recording", tags?.mbRecordingId)
            Fact("Release", tags?.mbReleaseId)
            Fact("Release group", tags?.mbReleaseGroupId)
            if (tags?.mbRecordingId == null && tags?.mbReleaseId == null) {
                Text("No MusicBrainz identifiers in the file.", style = Typo.Tertiary, color = MediaColors.CreamFaint)
            }

            Label("Artwork")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(issueText(art), style = Typo.Secondary,
                    color = if (art?.issue == ArtIssue.NONE) MediaColors.Cream else MediaColors.Warning,
                    modifier = Modifier.weight(1f))
                Text("Fix", style = Typo.Label, color = MediaColors.Accent,
                    modifier = Modifier.pressScale(haptic = true) { onFixArtwork() })
            }

            Label("Lyrics")
            Text(when (val l = lyrics) {
                LyricsState.Loading -> "Checking…"
                is LyricsState.Synced -> "Synced · ${l.source.label}" +
                    (l.confidence?.let { " · ${(it * 100).toInt()}% match" } ?: "")
                is LyricsState.Plain -> "Plain text · ${l.source.label}"
                LyricsState.Instrumental -> "Instrumental"
                is LyricsState.None -> "None stored for this track"
            }, style = Typo.Secondary, color = MediaColors.Cream)

            Label("Identify")
            val m = match
            when {
                identifying -> Text("Asking MusicBrainz…", style = Typo.Secondary, color = MediaColors.CreamFaint)
                m != null -> {
                    val (rec, score) = m
                    Text("${rec.title} — ${rec.artist}", style = Typo.Primary, color = MediaColors.Cream)
                    val rel = rec.releases.firstOrNull()
                    Text(listOfNotNull(rel?.title, rel?.year, "${(score * 100).toInt()}% match").joinToString(" · "),
                        style = Typo.Tertiary,
                        color = if (score >= Matching.CONFIDENT) MediaColors.Accent else MediaColors.CreamFaint)
                    val changes = rec.title != item.title || rec.artist != item.artist
                    Spacer(Modifier.height(Space.sm))
                    if (changes) {
                        Text("Use this title and artist", style = Typo.Label, color = MediaColors.Accent,
                            modifier = Modifier.pressScale(haptic = true) { onApply(rec.title, rec.artist) })
                        Text("Saved as your own edit. The file is not changed.", style = Typo.Tertiary,
                            color = MediaColors.CreamFaint)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Check, null, tint = MediaColors.Success, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Space.xs))
                            Text("The library already matches MusicBrainz", style = Typo.Tertiary, color = MediaColors.CreamDim)
                        }
                    }
                }
                else -> {
                    identifyNote?.let { Text(it, style = Typo.Tertiary, color = MediaColors.CreamFaint) }
                    Text(
                        if (onlineAllowed) "Look this track up on MusicBrainz" else "Look this track up on MusicBrainz (sends title, artist and length)",
                        style = Typo.Label, color = MediaColors.Accent,
                        modifier = Modifier.padding(top = Space.xs).pressScale(haptic = true) {
                            identifying = true; identifyNote = null
                            launch.launch {
                                val t = tags
                                val title = item.title.takeIf { it.isUsable() } ?: t?.title
                                val artist = item.artist.takeIf { it.isUsable() } ?: t?.artist
                                val recs = if (title != null) MusicBrainz.searchRecordings(title, artist) else emptyList()
                                identifying = false
                                if (recs == null) { identifyNote = "Couldn't reach MusicBrainz."; return@launch }
                                val q = Matching.Query(title, artist, item.album, item.durationMs.takeIf { it > 0 })
                                val best = recs.map { it to Matching.score(q, Matching.Candidate(it.title, it.artist, it.releases.firstOrNull()?.title, it.lengthMs)) }
                                    .maxByOrNull { it.second }
                                if (best == null || best.second < Matching.PLAUSIBLE) identifyNote = "No confident match found."
                                else match = best
                            }
                        }
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------- AUDIO PATH

@Composable
fun AudioPathSheet(item: AppMediaItem, onOpenSound: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var info by remember(item.id) { mutableStateOf<Pair<SourceFormat, FileTags?>?>(null) }
    LaunchedEffect(item.id) {
        info = AudioInfo.source(context, item)
    }
    val route = remember { AudioInfo.output(context) }
    val sound by SoundEngine.status.collectAsState()
    val settings by SoundEngine.settings.collectAsState()
    val fmt = info?.first

    GlassSheet(onDismiss) {
        Text("Audio path", style = Typo.Section, color = MediaColors.Cream)
        Spacer(Modifier.height(Space.xxs))
        Text("From the file to your ears, as it plays now", style = Typo.Secondary, color = MediaColors.CreamFaint)
        Spacer(Modifier.height(Space.md))
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Stage("Source", fmt?.badge ?: "Reading…",
                listOfNotNull(
                    if (fmt?.hiRes == true) "Hi-Res lossless" else if (fmt?.lossless == true) "Lossless" else if (fmt?.lossless == false) "Lossy" else null,
                    fmt?.channels?.let { if (it == 2) "Stereo" else if (it == 1) "Mono" else "$it ch" }
                ).joinToString(" · "), highlight = fmt?.hiRes == true)
            Stage("Decoder",
                if ((fmt?.bitDepth ?: 16) > 16) "32-bit float" else "16-bit PCM",
                if ((fmt?.bitDepth ?: 16) > 16) "Full resolution kept through to the mixer" else "Integer path")
            val chain = buildList {
                if (sound.gainDb != 0f) add("Gain %+.1f dB".format(java.util.Locale.ROOT, sound.gainDb) +
                    (sound.replayGainDb?.let { " (ReplayGain %+.1f)".format(java.util.Locale.ROOT, it) } ?: ""))
                if (settings.eqEnabled && sound.equalizer) add("EQ: ${settings.preset}")
                if (sound.limiterOn) add("Limiter")
                if (settings.bass > 0 && sound.bass) add("Bass ${settings.bass / 10}%")
                if (settings.spatial > 0 && sound.spatial) add("Spatial ${settings.spatial / 10}%")
            }
            Stage("Sound", if (chain.isEmpty()) "Bit-perfect to the mixer" else chain.joinToString(" → "),
                if (chain.isEmpty()) "No processing is applied" else "Applied in the system audio mixer",
                action = "Adjust", onAction = onOpenSound)
            val resampled = route.mixerRate != null && fmt?.sampleRate != null && fmt.sampleRate != route.mixerRate
            Stage("Mixer",
                route.mixerRate?.let { "Android mixer · ${khz(it)}" } ?: "Android mixer",
                if (resampled) "Resampled from ${khz(fmt?.sampleRate ?: 0)}" else "No resampling needed")
            Stage("Output", route.name,
                buildString {
                    append(route.kind)
                    if (route.bluetooth) append(" · re-encoded by the Bluetooth codec")
                    if (route.usb && route.deviceRates.isNotEmpty())
                        append(" · up to ${khz(route.deviceRates.max())}")
                }, last = true)
        }
    }
}

@Composable
private fun Stage(
    name: String, value: String, detail: String, highlight: Boolean = false, last: Boolean = false,
    action: String? = null, onAction: () -> Unit = {}
) {
    Row(Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(20.dp)) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.size(10.dp).clip(CircleShape)
                .background(if (highlight) MediaColors.Accent else MediaColors.CreamFaint))
            if (!last) Box(Modifier.width(1.dp).height(52.dp).background(MediaColors.Fill))
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f).padding(bottom = Space.md)) {
            Text(name.uppercase(), style = Typo.Micro, color = MediaColors.CreamFaint)
            Text(value, style = Typo.Primary, color = if (highlight) MediaColors.Accent else MediaColors.Cream)
            if (detail.isNotBlank()) Text(detail, style = Typo.Tertiary, color = MediaColors.CreamDim)
        }
        if (action != null) {
            Text(action, style = Typo.Label, color = MediaColors.Accent,
                modifier = Modifier.padding(top = Space.md).pressScale(haptic = true, onClick = onAction))
        }
    }
}
