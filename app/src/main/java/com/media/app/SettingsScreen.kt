package com.media.app
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    audioCount: Int,
    videoCount: Int,
    settings: MediaSettings,
    onFontScaleChange: (Float) -> Unit,
    onRescan: () -> Unit,
    adsReady: Boolean,
    onOpenTerms: () -> Unit,
    onOpenAbout: () -> Unit,
    onClose: () -> Unit,
    onOpenSound: () -> Unit = {},
    onRepairArtwork: () -> Unit = {}
) {
    val context = LocalContext.current
    val settingsScope = rememberCoroutineScope()
    // Repo moved owners; the old bangscc10-dev Pages URL is stale. The path
    // keeps the app's old name (Aura): it is where the policy is published.
    val privacyUrl = "https://mebs.app/privacy/aura"
    Column(
        // Settings is text and empty space with no artwork to justify a
        // gradient, so the illumination is compressed to a near-flat field.
        Modifier.fillMaxSize().screenBackground().statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Space.sm, Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MediaColors.Cream) }
        }

        ProfileMark(audioCount, videoCount)

        SectionLabel(stringResource(R.string.settings_appearance))
        LanguageRow()
        FontSizePicker(settings.fontScale, onFontScaleChange)

        // A banner, in a card. THE ROUNDED CORNERS ARE THE CARD'S, not the
        // ad's - cropping an ad is altering it, and AdMob does not allow that -
        // so the ad sits whole inside a container that has the shape.
        //
        // And it sits a full gutter below Text size rather than against it. An
        // ad within a thumb's width of a control is how accidental clicks
        // happen, and accidental clicks are how AdMob accounts get closed.
        val bannerHidden by Billing.adFree.collectAsState()
        if (!bannerHidden) ViaBanner(ready = adsReady)

        // Library first: Storage and Rescan are what people come to Settings
        // for. They were below Sound and Artwork, a long scroll down.
        SectionLabel(stringResource(R.string.settings_library))
        SettingRow(Icons.Outlined.Storage, stringResource(R.string.settings_storage),
            stringResource(R.string.settings_storage_value, audioCount, videoCount), navigates = false) {}
        RescanRow(onRescan)

        DisplayQualitySection(
            current = settings.qualityMode,
            onPick = { mode ->
                settingsScope.launch { SettingsStore.setQualityMode(context, mode) }
            }
        )

        SectionLabel(stringResource(R.string.settings_playback))
        // OFF by default. It is the most opinionated thing the app does, and
        // an effect someone dislikes is worse than one they never found.
        val reactiveArt by SettingsStore.reactiveArtFlow(context).collectAsState(initial = false)
        ToggleRow(
            icon = Icons.Outlined.GraphicEq,
            title = stringResource(R.string.settings_reactive),
            subtitle = stringResource(R.string.settings_reactive_sub),
            checked = reactiveArt,
            onChange = { on -> settingsScope.launch { SettingsStore.setReactiveArt(context, on) } }
        )

        SectionLabel(stringResource(R.string.settings_sound))
        val sound by SoundEngine.settings.collectAsState()
        SettingRow(
            Icons.Outlined.Tune, stringResource(R.string.settings_sound),
            if (sound.eqEnabled) presetLabel(sound.preset) else null,
            subtitle = stringResource(R.string.settings_sound_sub)
        ) { onOpenSound() }

        SectionLabel(stringResource(R.string.settings_art_lyrics))
        val online by remember(context) { SettingsStore.onlineFlow(context) }.collectAsState(initial = false)
        ToggleRow(
            icon = Icons.Outlined.Language,
            title = stringResource(R.string.settings_online),
            subtitle = stringResource(R.string.settings_online_sub),
            checked = online,
            onChange = { on -> settingsScope.launch { SettingsStore.setOnline(context, on) } }
        )
        val repair by ArtworkRepair.progress.collectAsState()
        val artVersion by ArtworkStore.version.collectAsState()
        val fixedCount = remember(artVersion) { ArtworkStore.fixedCount() }
        SettingRow(
            Icons.Outlined.AutoFixHigh,
            stringResource(if (repair.running) R.string.repair_stop else R.string.repair_start),
            when {
                repair.running -> stringResource(R.string.repair_progress, repair.done, repair.total)
                fixedCount > 0 -> stringResource(R.string.repair_fixed, fixedCount)
                else -> null
            },
            subtitle = when {
                repair.running -> stringResource(R.string.repair_checking,
                    repair.current ?: stringResource(R.string.repair_your_library))
                repair.offline -> stringResource(R.string.repair_offline)
                repair.total > 0 && !repair.running ->
                    plural(R.plurals.repair_last_run, repair.total, repair.fixed, repair.total)
                online -> stringResource(R.string.repair_sub)
                else -> stringResource(R.string.repair_needs_online)
            },
            navigates = online || repair.running
        ) { if (repair.running) ArtworkRepair.cancel() else onRepairArtwork() }

        // Android hides other apps' .lrc files from a plain scan, so a lyrics
        // collection on the phone is only reachable through a folder the
        // listener grants once.
        var lyricsFolder by remember { mutableStateOf(LyricsFolder.label(context)) }
        var lyricsCount by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(lyricsFolder) {
            lyricsCount = if (lyricsFolder == null) null
                else withContext(Dispatchers.IO) { LyricsFolder.count(context) }
        }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) {
                LyricsFolder.set(context, tree)
                lyricsFolder = LyricsFolder.label(context)
            }
        }
        SettingRow(
            Icons.Outlined.Lyrics,
            stringResource(R.string.lyrics_folder),
            lyricsCount?.let { plural(R.plurals.count_files, it) },
            subtitle = lyricsFolder?.let { stringResource(R.string.lyrics_folder_sub_set, it) }
                ?: stringResource(R.string.lyrics_folder_sub)
        ) { runCatching { folderPicker.launch(null) } }
        if (lyricsFolder != null) {
            SettingRow(Icons.Outlined.LinkOff, stringResource(R.string.lyrics_folder_stop), null, navigates = true) {
                LyricsFolder.clear(context)
                lyricsFolder = null
            }
        }

        // Via Pro. Always here, owned or not: owners come back to see what
        // they have, and the Pro page holds the price (Play's, localised),
        // the comparison and Restore.
        val pro = isPro()
        val openPro = LocalOpenPro.current
        val product by Billing.product.collectAsState()
        val price = product?.oneTimePurchaseOfferDetails?.formattedPrice
        SectionLabel(stringResource(R.string.pro_section))
        SettingRow(
            Icons.Outlined.WorkspacePremium,
            stringResource(if (pro) R.string.pro_active_title else R.string.pro_get),
            if (pro) null else price,
            subtitle = stringResource(if (pro) R.string.pro_settings_active_sub else R.string.pro_settings_sub)
        ) { openPro() }

        // The privacy policy promises that consent "can be changed later in
        // the app's settings". This is that place. The consent row appears
        // where the law requires it (EEA, UK, Switzerland, and the US states
        // set up in AdMob) - UMP decides from the user's location - and opens
        // Google's own form, which both withdraws GDPR consent and carries
        // the US "do not sell or share" opt-out.
        SectionLabel(stringResource(R.string.settings_privacy))
        var privacyNote by remember { mutableStateOf<Int?>(null) }
        if (Ads.privacyChoicesRequired(context)) {
            SettingRow(
                Icons.Outlined.PrivacyTip, stringResource(R.string.settings_ad_choices), null,
                subtitle = stringResource(R.string.settings_ad_choices_sub)
            ) {
                (context as? android.app.Activity)?.let { act ->
                    Ads.showPrivacyChoices(act) { err ->
                        privacyNote = err?.let { R.string.settings_ad_choices_error }
                    }
                }
            }
        }
        SettingRow(
            Icons.Outlined.PermIdentity, stringResource(R.string.settings_ad_id), null,
            subtitle = stringResource(R.string.settings_ad_id_sub)
        ) { Ads.openDeviceAdSettings(context) }
        SettingRow(Icons.Outlined.Shield, stringResource(R.string.settings_privacy_policy), null) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyUrl))) }
        }
        privacyNote?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MediaColors.Warning,
                modifier = Modifier.padding(Space.xl, 0.dp, Space.xl, Space.sm))
        }

        SectionLabel(stringResource(R.string.settings_about))
        SettingRow(Icons.Outlined.Info, stringResource(R.string.settings_about_app), null) { onOpenAbout() }
        SettingRow(
            Icons.Outlined.StarOutline, stringResource(R.string.settings_rate_app), null,
            subtitle = stringResource(R.string.settings_rate_sub)
        ) { InAppReview.openListing(context) }
        SettingRow(Icons.Outlined.Description, stringResource(R.string.terms_title), null) { onOpenTerms() }
        SettingRow(Icons.Outlined.Info, stringResource(R.string.settings_version), BuildConfig.VERSION_NAME, navigates = false) {}

        Spacer(Modifier.height(40.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall,
            color = MediaColors.CreamFaint,
            modifier = Modifier.padding(Space.xl))
        Text(stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamFaint,
            modifier = Modifier.padding(start = Space.xl).padding(bottom = bottomSafePadding(gap = 100.dp)))
    }
}

// --------------------------------------------------------- DISPLAY QUALITY
//
// Auto is the recommendation; the five named levels are an override.
//
// The diagnostics underneath are deliberate. Every other setting here is a
// preference, but this one's correct value depends on hardware the user cannot
// see, so the app shows the hardware it actually read and the ceiling it
// derived from it. A setting that silently decides something this visible
// should be able to show its working.
// "1 videos" is the kind of thing a reviewer screenshots. Also drops a half
// that reads zero, rather than announcing what someone does not have.
@Composable
private fun librarySummary(audio: Int, video: Int): String {
    val a = plural(R.plurals.count_tracks, audio)
    val v = plural(R.plurals.count_videos, video)
    return when {
        audio == 0 && video == 0 -> stringResource(R.string.library_nothing_yet)
        video == 0 -> a
        audio == 0 -> v
        else -> a + " \u00B7 " + v
    }
}

@Composable
private fun DisplayQualitySection(current: QualityMode, onPick: (QualityMode) -> Unit) {
    val context = LocalContext.current
    // Hardware does not change while the app is open, so this is read once.
    val cap = remember { detectCapability(context) }
    val ceiling = remember(cap) { ceilingFor(cap) }
    val active = resolveLevel(current, ceiling)
    val adaptive = LocalAdaptiveQuality.current
    // A prediction and a measurement. Once the measurement has spoken,
    // the prediction is noise.
    val overReach = current != QualityMode.AUTO &&
        active.ordinal > ceiling.ordinal && !adaptive.strained

    SectionLabel(stringResource(R.string.dq_title))

    QualityOption(
        title = stringResource(R.string.dq_auto),
        // The CEILING, not the active level. Reading `active` here meant that
        // picking Essential manually made the Auto row claim "currently
        // Essential" - describing the manual choice instead of what Auto
        // would actually resolve to, which is the one thing this row exists
        // to answer.
        subtitle = stringResource(R.string.dq_auto_sub, stringResource(ceiling.label)),
        selected = current == QualityMode.AUTO,
        onClick = { onPick(QualityMode.AUTO) }
    )
    QualityLevel.values().forEach { level ->
        val mode = QualityMode.valueOf(level.name)
        val above = level.ordinal > ceiling.ordinal
        QualityOption(
            title = stringResource(level.label),
            subtitle = stringResource(if (above) R.string.dq_above else level.goal),
            selected = current == mode,
            warn = above,
            onClick = { onPick(mode) }
        )
    }

    // What the tier actually draws, in plain words. A level whose effect you
    // cannot name is a level nobody can choose between.
    Column(Modifier.padding(Space.xl, Space.md, Space.xl, 0.dp)) {
        Text(
            stringResource(R.string.dq_gives_you, stringResource(active.label)),
            style = MaterialTheme.typography.labelMedium,
            color = MediaColors.CreamDim
        )
        Spacer(Modifier.height(Space.xs))
        active.features.forEach { f ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text("\u2022", style = MaterialTheme.typography.bodyMedium, color = MediaColors.Accent,
                    modifier = Modifier.width(16.dp))
                Text(stringResource(f), style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamFaint)
            }
        }
    }

    if (overReach) {
        Text(
            stringResource(R.string.dq_over_reach, stringResource(active.label)),
            style = MaterialTheme.typography.bodyMedium,
            color = MediaColors.Warning,
            modifier = Modifier.padding(Space.xl, Space.sm, Space.xl, 0.dp)
        )
    }

    // A tier that quietly changed itself is worse than one that never adapts,
    // so the app says what it did and why.
    if (adaptive.steppedDown || adaptive.lightened) {
        Text(
            stringResource(
                if (adaptive.steppedDown) R.string.dq_stepped_down else R.string.dq_lightened,
                stringResource(adaptive.level.label)
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MediaColors.Warning,
            modifier = Modifier.padding(Space.xl, Space.sm, Space.xl, 0.dp)
        )
    } else if (adaptive.strained) {
        // Measured trouble at a MANUAL level. The blueprint allows the
        // override and says to offer Auto rather than force it, so this is an
        // offer with a button, not a silent correction.
        Column(Modifier.padding(Space.xl, Space.sm, Space.xl, 0.dp)) {
            Text(
                stringResource(R.string.dq_strained, stringResource(adaptive.level.label)),
                style = MaterialTheme.typography.bodyMedium,
                color = MediaColors.Warning
            )
            Spacer(Modifier.height(Space.sm))
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(MediaColors.Accent)
                    .clickable { onPick(QualityMode.AUTO) }
                    .padding(horizontal = Space.lg, vertical = 9.dp)
            ) {
                Text(
                    stringResource(R.string.dq_use_auto),
                    style = MaterialTheme.typography.labelMedium,
                    color = MediaColors.OnAccent
                )
            }
        }
    }

    val ramText =
        if (cap.totalRamMb >= 1024) "%.1f GB".format(cap.totalRamMb / 1024f)
        else "${cap.totalRamMb} MB"
    val facts = buildString {
        append(stringResource(R.string.dq_facts, ramText, cap.cpuCores, cap.refreshRateHz.toInt()))
        if (cap.supportsHdr) append(" \u00b7 ").append(stringResource(R.string.dq_hdr))
        if (cap.isLowRamDevice) append(" \u00b7 ").append(stringResource(R.string.dq_low_ram))
    }
    Text(
        facts,
        style = MaterialTheme.typography.labelSmall,
        color = MediaColors.CreamDim,
        modifier = Modifier.padding(Space.xl, Space.md, Space.xl, 0.dp)
    )
    Text(
        stringResource(R.string.dq_ceiling, stringResource(ceiling.label), cap.score),
        style = MaterialTheme.typography.labelSmall,
        color = MediaColors.CreamFaint,
        modifier = Modifier.padding(Space.xl, 2.dp, Space.xl, 0.dp)
    )
    // Required by the blueprint's product-language rule, and true: this setting
    // changes how Via renders. It cannot turn an LCD into an OLED, and saying
    // otherwise would be a claim the app cannot support.
    Text(
        stringResource(R.string.dq_honest),
        style = MaterialTheme.typography.labelSmall,
        color = MediaColors.CreamFaint,
        modifier = Modifier.padding(Space.xl, Space.sm, Space.xl, Space.sm)
    )
}

@Composable
private fun QualityOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    warn: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.xl, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    1.5.dp,
                    if (selected) MediaColors.Accent else MediaColors.CreamFaint,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(MediaColors.Accent))
            }
        }
        Spacer(Modifier.width(Space.lg))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MediaColors.Accent else MediaColors.Cream
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (warn) MediaColors.Warning else MediaColors.CreamDim
            )
        }
    }
}

@Composable
private fun RescanRow(onRescan: () -> Unit) {
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var statusValue by remember { mutableStateOf<Int?>(null) }

    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !scanning) {
                scope.launch {
                    scanning = true
                    statusValue = R.string.rescan_scanning
                    delay(900)           // let the scanning state be seen
                    onRescan()
                    statusValue = R.string.rescan_done
                    scanning = false
                    delay(1600)
                    statusValue = null
                }
            }
            .padding(Space.xl, Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Refresh, null, tint = MediaColors.CreamDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.md))
        Text(stringResource(R.string.rescan_title), style = MaterialTheme.typography.bodyLarge, color = MediaColors.Cream,
            modifier = Modifier.weight(1f))
        if (scanning) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MediaColors.Accent
            )
            Spacer(Modifier.width(Space.sm))
        }
        if (statusValue != null) {
            Text(stringResource(statusValue!!), style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamDim)
        } else {
            Icon(Icons.Filled.ChevronRight, null, tint = MediaColors.CreamFaint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(Space.xl, Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MediaColors.CreamDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MediaColors.Cream)
            Text(subtitle, style = Typo.Tertiary, color = MediaColors.CreamFaint)
        }
        Spacer(Modifier.width(Space.md))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MediaColors.Accent,
                uncheckedThumbColor = MediaColors.CreamFaint,
                uncheckedTrackColor = MediaColors.Fill,
                uncheckedBorderColor = MediaColors.FillStrong
            )
        )
    }
}

@Composable
private fun FontSizePicker(current: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Space.xl, Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TextFields, null, tint = MediaColors.CreamDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.md))
            Text(stringResource(R.string.text_size), style = MaterialTheme.typography.bodyLarge, color = MediaColors.Cream)
        }
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            listOf(
                0.9f to stringResource(R.string.text_size_compact),
                1.0f to stringResource(R.string.text_size_default),
                1.15f to stringResource(R.string.text_size_large)
            ).forEach { (scale, label) ->
                val sel = kotlin.math.abs(scale - current) < 0.01f
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        // Was a white pill, sitting directly above teal radio
                        // buttons: two different selection languages on one
                        // screen. Teal marks state everywhere; white is kept
                        // for exactly one thing, the play button.
                        .background(if (sel) MediaColors.Accent else MediaColors.Elevated)
                        .border(0.5.dp, if (sel) MediaColors.Accent else MediaColors.InkHairline, RoundedCornerShape(10.dp))
                        .clickable { onChange(scale) }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium,
                        color = if (sel) MediaColors.OnAccent else MediaColors.CreamDim)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ LANGUAGE
//
// Automatic follows the phone, and is where everyone starts. A pick here is
// for Via alone. On Android 13+ it is the same setting as the system's own
// per-app language, so it can be changed from either place (Language.kt).
// Languages are listed in their own names: someone looking for theirs must
// be able to read it whatever the app is currently in.
@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    val picked = remember { AppLanguage.picked(context) }
    var open by remember { mutableStateOf(false) }
    SettingRow(
        Icons.Outlined.Translate, stringResource(R.string.language_title),
        picked?.nativeName ?: stringResource(R.string.language_automatic),
        subtitle = if (picked == null)
            stringResource(R.string.language_follows_phone, AppLanguage.deviceLanguageName()) else null
    ) { open = true }
    if (open) {
        LanguagePicker(
            current = picked,
            onPick = { option ->
                open = false
                if (option?.tag != picked?.tag) {
                    (context as? android.app.Activity)?.let { AppLanguage.pick(it, option) }
                }
            },
            onDismiss = { open = false }
        )
    }
}

@Composable
private fun LanguagePicker(
    current: AppLanguage.Option?,
    onPick: (AppLanguage.Option?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MediaColors.Modal,
        title = { Text(stringResource(R.string.language_title), color = MediaColors.Cream) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LanguageChoice(
                    stringResource(R.string.language_automatic),
                    stringResource(R.string.language_follows_phone, AppLanguage.deviceLanguageName()),
                    selected = current == null
                ) { onPick(null) }
                AppLanguage.options.forEach { option ->
                    LanguageChoice(option.nativeName, null, selected = current?.tag == option.tag) {
                        onPick(option)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onDismiss) {
                Text(stringResource(R.string.action_cancel), color = MediaColors.CreamDim)
            }
        }
    )
}

@Composable
private fun LanguageChoice(name: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.xs, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .border(1.5.dp, if (selected) MediaColors.Accent else MediaColors.CreamFaint, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(MediaColors.Accent))
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MediaColors.Accent else MediaColors.Cream)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamDim)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    // Was titleLarge at full Cream: a 19sp bright heading repeated seven times
    // down one screen - the same giant-heading problem the home header had.
    // Home already treats a section heading as a signpost rather than a
    // headline; Settings never got that pass. Uppercase micro at a dimmed
    // colour groups the list without competing with the rows inside it.
    Text(
        text.uppercase(),
        style = Typo.Micro,
        color = MediaColors.CreamDim,
        modifier = Modifier.padding(Space.xl, Space.xl, Space.xl, Space.sm)
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    value: String?,
    // Optional second line. The support row needs to state what you get;
    // "Support Via" alone doesn't say ads go away.
    subtitle: String? = null,
    navigates: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = navigates, onClick = onClick)
            .padding(Space.xl, Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MediaColors.CreamDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MediaColors.Cream)
            if (subtitle != null) {
                Text(subtitle, style = Typo.Tertiary, color = MediaColors.CreamFaint)
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamDim)
            Spacer(Modifier.width(Space.sm))
        }
        if (navigates) {
            Icon(Icons.Filled.ChevronRight, null, tint = MediaColors.CreamFaint, modifier = Modifier.size(18.dp))
        }
    }
}

// ------------------------------------------------------------------ PROFILE
//
// Was a 64dp disc with "M" in it, top left. The M was a monogram for a name
// the app has never known and never asks for - it stood for nothing, and it
// was the first thing on the page.
//
// The mark goes here instead, centred, and this is the one place in the app
// where a user can put something of their own. Tap it, pick a photo, done;
// tap Remove and the bird comes back. Nothing else in the app changes - the
// header keeps the mark, because that is identity, not preference.
//
// No plate behind the bird. It is a cut-out on black, exactly as in the
// header, and a disc behind it would be a frame around something that already
// has a shape. A photograph does get the disc, because a rectangle needs one.
@Composable
private fun ProfileMark(audioCount: Int, videoCount: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stamp by SettingsStore.avatarStampFlow(context).collectAsState(initial = 0L)
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }

    // Keyed on the stamp, so saving or removing re-reads the file and nothing
    // else does. Decoding on the main thread here would stutter the scroll.
    LaunchedEffect(stamp) {
        picture = withContext(Dispatchers.IO) { Avatar.load(context) }
    }

    // The system photo picker: no storage permission, no access to anything
    // the user did not choose, and it falls back to the document picker on
    // devices that do not have it.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { Avatar.save(context, uri) }
                // Only stamp on success. A failed decode leaves the mark up
                // rather than leaving an empty circle behind.
                if (saved) SettingsStore.setAvatarStamp(context, System.currentTimeMillis())
            }
        }
    }
    val pick = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Column(
        Modifier.fillMaxWidth().padding(Space.xl, Space.md, Space.xl, Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val shot = picture
        if (shot != null) {
            Image(
                bitmap = shot,
                contentDescription = stringResource(R.string.avatar_change_cd),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .border(1.dp, MediaColors.InkHairline, CircleShape)
                    .clickable { pick() }
            )
        } else {
            Image(
                painter = painterResource(R.drawable.via_mark),
                contentDescription = stringResource(R.string.avatar_add),
                modifier = Modifier.height(96.dp).clickable { pick() }
            )
        }
        Spacer(Modifier.height(Space.md))
        Text(
            stringResource(R.string.profile_your_library),
            style = MaterialTheme.typography.titleLarge,
            color = MediaColors.Cream
        )
        Text(
            librarySummary(audioCount, videoCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MediaColors.CreamDim
        )
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (shot != null) R.string.avatar_change else R.string.avatar_add),
                style = MaterialTheme.typography.labelMedium,
                color = MediaColors.Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .border(1.dp, MediaColors.Fill, RoundedCornerShape(Radius.pill))
                    .clickable { pick() }
                    .padding(horizontal = Space.lg, vertical = Space.sm)
            )
            if (shot != null) {
                Spacer(Modifier.width(Space.sm))
                Text(
                    stringResource(R.string.action_remove),
                    style = MaterialTheme.typography.labelMedium,
                    color = MediaColors.CreamDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .border(1.dp, MediaColors.Fill, RoundedCornerShape(Radius.pill))
                        .clickable {
                            scope.launch {
                                withContext(Dispatchers.IO) { Avatar.clear(context) }
                                SettingsStore.setAvatarStamp(context, 0L)
                            }
                        }
                        .padding(horizontal = Space.lg, vertical = Space.sm)
                )
            }
        }
    }
}
