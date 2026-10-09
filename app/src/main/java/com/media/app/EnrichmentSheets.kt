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
import androidx.compose.ui.res.stringResource

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
            Text(shownTitle(item.title), style = Typo.Section, color = MediaColors.Cream,
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

@Composable
private fun issueText(s: ArtStatus?): String = when {
    s == null -> stringResource(R.string.art_checking)
    s.fixed != null -> when (s.fixed.source) {
        "custom" -> stringResource(R.string.art_own_cover)
        else -> stringResource(R.string.art_fixed_from, providerName(s.fixed.source), (s.fixed.confidence * 100).toInt())
    }
    s.issue == ArtIssue.MISSING -> stringResource(R.string.art_missing)
    s.issue == ArtIssue.LOW_RES -> stringResource(R.string.art_low_res, s.width, s.height)
    s.issue == ArtIssue.PLACEHOLDER -> stringResource(R.string.art_placeholder)
    else -> stringResource(R.string.art_fine, s.width, s.height)
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
    // A string resource: set from callbacks, shown in the current language.
    var message by remember { mutableStateOf<Int?>(null) }
    val ids = remember(scope) { scope.map { it.id }.ifEmpty { listOf(item.id) } }

    fun search() {
        searching = true; failed = false; message = null
        launch.launch {
            val tags = withContext(Dispatchers.IO) { readTags(context, item.uri) }
            val found = ArtworkRepair.candidates(item, tags, thorough = true)
            searching = false; searched = true
            if (found == null) failed = true else candidates = found
        }
    }
    // Online lookups on: search straight away. Off: the button is the consent.
    LaunchedEffect(item.id) { if (onlineAllowed) search() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) launch.launch {
            val ok = ArtworkRepair.applyCustom(context, item, ids, uri)
            message = if (ok) R.string.art_cover_updated else R.string.art_image_unreadable
        }
    }

    GlassSheet(onDismiss) {
        SheetHeader(item, issueText(status))
        if (scope.size > 1) {
            Spacer(Modifier.height(Space.xs))
            Text(plural(R.plurals.art_applies_album, scope.size, scope.size, item.album), style = Typo.Tertiary,
                color = MediaColors.CreamFaint)
        }
        Spacer(Modifier.height(Space.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            TileAction(Icons.Filled.Search, stringResource(if (searched) R.string.action_search_again else R.string.art_find_cover),
                Modifier.weight(1f), enabled = !searching) { search() }
            TileAction(Icons.Filled.Image, stringResource(R.string.art_from_gallery), Modifier.weight(1f)) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            TileAction(Icons.Filled.Restore, stringResource(R.string.art_files_own), Modifier.weight(1f),
                enabled = status?.fixed != null) {
                ArtworkStore.remove(ids)
                message = R.string.art_back_to_own
            }
        }
        message?.let {
            Spacer(Modifier.height(Space.md))
            Text(stringResource(it), style = Typo.Secondary, color = MediaColors.Accent)
        }
        Spacer(Modifier.height(Space.md))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            when {
                searching -> Text(stringResource(R.string.art_searching), style = Typo.Secondary, color = MediaColors.CreamFaint,
                    modifier = Modifier.padding(vertical = Space.md))
                failed -> Text(stringResource(R.string.art_unreachable),
                    style = Typo.Secondary, color = MediaColors.CreamFaint, modifier = Modifier.padding(vertical = Space.md))
                searched && candidates.isEmpty() -> Text(
                    stringResource(R.string.art_none_found),
                    style = Typo.Secondary, color = MediaColors.CreamFaint, modifier = Modifier.padding(vertical = Space.md))
                candidates.isNotEmpty() -> {
                    Label(stringResource(R.string.art_covers_found), top = false)
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
                                        message = if (ok) R.string.art_cover_updated else R.string.art_no_full_size
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
                    Text(stringResource(R.string.art_applying), style = Typo.Tertiary, color = MediaColors.Cream)
                }
            }
        }
        Spacer(Modifier.height(Space.xs))
        Text(c.title, style = Typo.Tertiary, color = MediaColors.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(c.providerLabel, c.year, stringResource(R.string.percent_value, (c.score * 100).toInt())).joinToString(" · "),
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
    var identifyNote by remember { mutableStateOf<Int?>(null) }

    GlassSheet(onDismiss) {
        SheetHeader(item, shownArtist(item.artist))
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            val (fmt, tags) = info ?: (null to null)
            Label(stringResource(R.string.label_audio))
            Fact(stringResource(R.string.fact_format), fmt?.badge ?: stringResource(R.string.reading))
            Fact(stringResource(R.string.fact_quality), when {
                fmt == null -> null
                fmt.hiRes -> stringResource(R.string.quality_hires_lossless)
                fmt.lossless == true -> stringResource(R.string.quality_lossless)
                fmt.lossless == false -> stringResource(R.string.quality_lossy)
                else -> null
            }, accent = fmt?.hiRes == true)
            Fact(stringResource(R.string.fact_channels), fmt?.channels?.let {
                if (it == 1) stringResource(R.string.channels_mono)
                else if (it == 2) stringResource(R.string.channels_stereo)
                else plural(R.plurals.channels_count, it)
            })
            Fact(stringResource(R.string.fact_bitrate), fmt?.bitrateKbps?.let { "$it kbps" })
            Fact(stringResource(R.string.fact_length), fmt?.durationMs?.let { fmtClock(it) })
            Fact(stringResource(R.string.fact_size), fmt?.sizeBytes?.let { "%.1f MB".format(java.util.Locale.ROOT, it / 1048576.0) })
            Fact(stringResource(R.string.fact_container), tags?.container)
            Fact(stringResource(R.string.fact_replaygain), listOfNotNull(
                tags?.replayGainTrack?.let { stringResource(R.string.rg_track, it) },
                tags?.replayGainAlbum?.let { stringResource(R.string.rg_album, it) }
            ).joinToString(" · ").ifEmpty { null })

            Label(stringResource(R.string.label_tags_in_file))
            Fact(stringResource(R.string.field_title), tags?.title)
            Fact(stringResource(R.string.field_artist), tags?.artist)
            Fact(stringResource(R.string.fact_album), tags?.album)
            Fact(stringResource(R.string.fact_album_artist), tags?.albumArtist)
            Fact(stringResource(R.string.fact_year), tags?.date)
            Fact(stringResource(R.string.fact_genre), tags?.genre)
            Fact(stringResource(R.string.fact_composer), tags?.composer)
            Fact(stringResource(R.string.fact_track), listOfNotNull(
                tags?.track?.let { stringResource(R.string.track_number, it) },
                tags?.disc?.let { stringResource(R.string.disc_number, it) }
            ).joinToString(", ").ifEmpty { null })
            Fact("ISRC", tags?.isrc)
            if (tags != null && tags.title == null && tags.artist == null) {
                Text(stringResource(R.string.tags_none_note),
                    style = Typo.Tertiary, color = MediaColors.CreamFaint)
            }
            Fact(stringResource(R.string.fact_file), item.relPath.ifBlank { null })

            Label(stringResource(R.string.label_identifiers))
            Fact(stringResource(R.string.fact_recording), tags?.mbRecordingId)
            Fact(stringResource(R.string.fact_release), tags?.mbReleaseId)
            Fact(stringResource(R.string.fact_release_group), tags?.mbReleaseGroupId)
            if (tags?.mbRecordingId == null && tags?.mbReleaseId == null) {
                Text(stringResource(R.string.ids_none), style = Typo.Tertiary, color = MediaColors.CreamFaint)
            }

            Label(stringResource(R.string.label_artwork))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(issueText(art), style = Typo.Secondary,
                    color = if (art?.issue == ArtIssue.NONE) MediaColors.Cream else MediaColors.Warning,
                    modifier = Modifier.weight(1f))
                Text(stringResource(R.string.action_fix), style = Typo.Label, color = MediaColors.Accent,
                    modifier = Modifier.pressScale(haptic = true) { onFixArtwork() })
            }

            Label(stringResource(R.string.label_lyrics))
            Text(when (val l = lyrics) {
                LyricsState.Loading -> stringResource(R.string.lyrics_checking)
                is LyricsState.Synced -> l.confidence?.let {
                    stringResource(R.string.lyrics_synced_from_match, stringResource(l.source.labelRes), (it * 100).toInt())
                } ?: stringResource(R.string.lyrics_synced_from, stringResource(l.source.labelRes))
                is LyricsState.Plain -> stringResource(R.string.lyrics_plain_from, stringResource(l.source.labelRes))
                LyricsState.Instrumental -> stringResource(R.string.lyrics_instrumental)
                is LyricsState.None -> stringResource(R.string.lyrics_none_stored)
            }, style = Typo.Secondary, color = MediaColors.Cream)

            Label(stringResource(R.string.label_identify))
            val m = match
            when {
                identifying -> Text(stringResource(R.string.identify_asking), style = Typo.Secondary, color = MediaColors.CreamFaint)
                m != null -> {
                    val (rec, score) = m
                    Text("${rec.title} — ${rec.artist}", style = Typo.Primary, color = MediaColors.Cream)
                    val rel = rec.releases.firstOrNull()
                    Text(listOfNotNull(rel?.title, rel?.year, stringResource(R.string.match_percent, (score * 100).toInt())).joinToString(" · "),
                        style = Typo.Tertiary,
                        color = if (score >= Matching.CONFIDENT) MediaColors.Accent else MediaColors.CreamFaint)
                    val changes = rec.title != item.title || rec.artist != item.artist
                    Spacer(Modifier.height(Space.sm))
                    if (changes) {
                        Text(stringResource(R.string.identify_use), style = Typo.Label, color = MediaColors.Accent,
                            modifier = Modifier.pressScale(haptic = true) { onApply(rec.title, rec.artist) })
                        Text(stringResource(R.string.identify_saved_note), style = Typo.Tertiary,
                            color = MediaColors.CreamFaint)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Check, null, tint = MediaColors.Success, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Space.xs))
                            Text(stringResource(R.string.identify_already), style = Typo.Tertiary, color = MediaColors.CreamDim)
                        }
                    }
                }
                else -> {
                    identifyNote?.let { Text(stringResource(it), style = Typo.Tertiary, color = MediaColors.CreamFaint) }
                    Text(
                        stringResource(if (onlineAllowed) R.string.identify_lookup else R.string.identify_lookup_consent),
                        style = Typo.Label, color = MediaColors.Accent,
                        modifier = Modifier.padding(top = Space.xs).pressScale(haptic = true) {
                            identifying = true; identifyNote = null
                            launch.launch {
                                val t = tags
                                val title = item.title.takeIf { it.isUsable() } ?: t?.title
                                val artist = item.artist.takeIf { it.isUsable() } ?: t?.artist
                                val recs = if (title != null) MusicBrainz.searchRecordings(title, artist) else emptyList()
                                identifying = false
                                if (recs == null) { identifyNote = R.string.identify_unreachable; return@launch }
                                val q = Matching.Query(title, artist, item.album, item.durationMs.takeIf { it > 0 })
                                val best = recs.map { it to Matching.score(q, Matching.Candidate(it.title, it.artist, it.releases.firstOrNull()?.title, it.lengthMs)) }
                                    .maxByOrNull { it.second }
                                if (best == null || best.second < Matching.PLAUSIBLE) identifyNote = R.string.identify_no_match
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
    val tuned by SoundEngine.route.collectAsState()
    val fmt = info?.first

    GlassSheet(onDismiss) {
        Text(stringResource(R.string.path_title), style = Typo.Section, color = MediaColors.Cream)
        Spacer(Modifier.height(Space.xxs))
        Text(stringResource(R.string.path_subtitle), style = Typo.Secondary, color = MediaColors.CreamFaint)
        Spacer(Modifier.height(Space.md))
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Stage(stringResource(R.string.stage_source), fmt?.badge ?: stringResource(R.string.reading),
                listOfNotNull(
                    if (fmt?.hiRes == true) stringResource(R.string.quality_hires_lossless)
                    else if (fmt?.lossless == true) stringResource(R.string.quality_lossless)
                    else if (fmt?.lossless == false) stringResource(R.string.quality_lossy) else null,
                    fmt?.channels?.let {
                        if (it == 2) stringResource(R.string.channels_stereo)
                        else if (it == 1) stringResource(R.string.channels_mono)
                        else plural(R.plurals.channels_count, it)
                    }
                ).joinToString(" · "), highlight = fmt?.hiRes == true)
            Stage(stringResource(R.string.stage_decoder),
                stringResource(if ((fmt?.bitDepth ?: 16) > 16) R.string.decoder_float else R.string.decoder_pcm16),
                stringResource(if ((fmt?.bitDepth ?: 16) > 16) R.string.decoder_float_note else R.string.decoder_int_note))
            val chain = buildList {
                // In signal order: Depth and Space in the player, then the mixer's chain.
                // They run on stereo; a mono or surround file goes past them.
                val channels = fmt?.channels
                val stereo = channels == null || channels == 2
                if (tuned.external && stereo && settings.depth > 0) add(stringResource(R.string.chain_depth, settings.depth / 10))
                if (tuned.external && stereo && settings.space > 0) add(stringResource(R.string.chain_space, settings.space / 10))
                if (sound.gainDb != 0f) add(sound.replayGainDb?.let {
                    stringResource(R.string.chain_gain_rg, sound.gainDb, it)
                } ?: stringResource(R.string.chain_gain, sound.gainDb))
                if (tuned.external && settings.eqEnabled && sound.equalizer) add(stringResource(R.string.chain_eq, presetLabel(settings.preset)))
                if (sound.limiterOn) add(stringResource(R.string.chain_limiter))
            }
            Stage(stringResource(R.string.stage_sound),
                if (chain.isEmpty()) stringResource(R.string.sound_bitperfect) else chain.joinToString(" → "),
                stringResource(if (chain.isEmpty()) R.string.sound_none_note else R.string.sound_applied_note),
                action = stringResource(R.string.action_adjust), onAction = onOpenSound)
            val resampled = route.mixerRate != null && fmt?.sampleRate != null && fmt.sampleRate != route.mixerRate
            Stage(stringResource(R.string.stage_mixer),
                route.mixerRate?.let { stringResource(R.string.mixer_rate, khz(it)) } ?: stringResource(R.string.mixer),
                if (resampled) stringResource(R.string.mixer_resampled, khz(fmt?.sampleRate ?: 0))
                else stringResource(R.string.mixer_no_resample))
            Stage(stringResource(R.string.stage_output), route.name,
                listOfNotNull(
                    route.kindLabel,
                    if (route.bluetooth) stringResource(R.string.output_bt_reencoded) else null,
                    if (route.usb && route.deviceRates.isNotEmpty())
                        stringResource(R.string.output_up_to, khz(route.deviceRates.max())) else null
                ).joinToString(" · "), last = true)
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
