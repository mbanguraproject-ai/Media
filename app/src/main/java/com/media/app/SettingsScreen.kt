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
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val settingsScope = rememberCoroutineScope()
    // Repo moved owners; the old bangscc10-dev Pages URL is stale.
    val privacyUrl = "https://mebs.app/privacy/aura"
    Column(
        // Settings is text and empty space with no artwork to justify a
        // gradient, so the illumination is compressed to a near-flat field.
        Modifier.fillMaxSize().background(moodBackground(flat = true)).statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Space.sm, Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MediaColors.Cream) }
        }

        ProfileMark(audioCount, videoCount)

        SectionLabel("Appearance")
        FontSizePicker(settings.fontScale, onFontScaleChange)

        // A banner, in a card. THE ROUNDED CORNERS ARE THE CARD'S, not the
        // ad's - cropping an ad is altering it, and AdMob does not allow that -
        // so the ad sits whole inside a container that has the shape.
        //
        // And it sits a full gutter below Text size rather than against it. An
        // ad within a thumb's width of a control is how accidental clicks
        // happen, and accidental clicks are how AdMob accounts get closed.
        val bannerHidden by Billing.adFree.collectAsState()
        if (adsReady && !bannerHidden) {
            Spacer(Modifier.height(Space.lg))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.xl)
                    .clip(RoundedCornerShape(Radius.lg))
                    .background(MediaColors.Elevated)
                    .padding(Space.sm),
                contentAlignment = Alignment.Center
            ) {
                AuraBanner(ready = adsReady)
            }
            Spacer(Modifier.height(Space.sm))
        }

        DisplayQualitySection(
            current = settings.qualityMode,
            onPick = { mode ->
                settingsScope.launch { SettingsStore.setQualityMode(context, mode) }
            }
        )

        SectionLabel("Playback")
        // OFF by default. It is the most opinionated thing the app does, and
        // an effect someone dislikes is worse than one they never found.
        val reactiveArt by SettingsStore.reactiveArtFlow(context).collectAsState(initial = false)
        ToggleRow(
            icon = Icons.Outlined.GraphicEq,
            title = "Reactive artwork",
            subtitle = "Cover art responds to the music as it plays",
            checked = reactiveArt,
            onChange = { on -> settingsScope.launch { SettingsStore.setReactiveArt(context, on) } }
        )

        SectionLabel("Library")
        SettingRow(Icons.Outlined.Storage, "Storage", "$audioCount + $videoCount items", navigates = false) {}
        RescanRow(onRescan)

        // Price comes from Play, never hardcoded - it is localised and can
        // change without a release.
        val adFree by Billing.adFree.collectAsState()
        val product by Billing.product.collectAsState()
        val price = product?.oneTimePurchaseOfferDetails?.formattedPrice
        if (adFree) {
            SectionLabel("Supporter")
            SettingRow(
                Icons.Outlined.Favorite, "Thank you", null,
                subtitle = "Ads are off for good", navigates = false
            ) {}
        } else if (price != null) {
            SectionLabel("Support")
            // Framed as backing the app rather than buying an annoyance away:
            // one card in a feed is a weak thing to charge for, and the ask
            // reads better as support with ad removal as the thank-you.
            SettingRow(
                Icons.Outlined.Favorite, "Support Aura", price,
                subtitle = "One-time \u00b7 removes ads forever"
            ) {
                (context as? android.app.Activity)?.let { Billing.purchase(it) }
            }
        }

        SectionLabel("About")
        SettingRow(Icons.Outlined.Info, "About " + stringResource(R.string.app_name), null) { onOpenAbout() }
        SettingRow(Icons.Outlined.Description, "Terms of Use", null) { onOpenTerms() }
        SettingRow(Icons.Outlined.Shield, "Privacy Policy", null) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyUrl)))
        }
        SettingRow(Icons.Outlined.Info, "Version", BuildConfig.VERSION_NAME, navigates = false) {}

        Spacer(Modifier.height(40.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall,
            color = MediaColors.CreamFaint,
            modifier = Modifier.padding(Space.xl))
        Text("Your library, lit by what\'s playing.",
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

    SectionLabel("Display quality")

    QualityOption(
        title = "Auto",
        // The CEILING, not the active level. Reading `active` here meant that
        // picking Essential manually made the Auto row claim "currently
        // Essential" - describing the manual choice instead of what Auto
        // would actually resolve to, which is the one thing this row exists
        // to answer.
        subtitle = "Matches this device \u2014 ${ceiling.label}",
        selected = current == QualityMode.AUTO,
        onClick = { onPick(QualityMode.AUTO) }
    )
    QualityLevel.values().forEach { level ->
        val mode = QualityMode.valueOf(level.name)
        val above = level.ordinal > ceiling.ordinal
        QualityOption(
            title = level.label,
            subtitle = if (above) "Above this device's measured ceiling" else level.goal,
            selected = current == mode,
            warn = above,
            onClick = { onPick(mode) }
        )
    }

    if (overReach) {
        Text(
            "${active.label} is above what this device measured. Frames may drop.",
            style = MaterialTheme.typography.bodyMedium,
            color = MediaColors.Warning,
            modifier = Modifier.padding(Space.xl, Space.sm, Space.xl, 0.dp)
        )
    }

    // A tier that quietly changed itself is worse than one that never adapts,
    // so the app says what it did and why.
    if (adaptive.steppedDown) {
        Text(
            "Stepped down to ${adaptive.level.label} to hold a steady frame rate. " +
                "Playback was not affected.",
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
                "${adaptive.level.label} is dropping frames on this device.",
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
                    "Use Auto",
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
        append(ramText); append(" RAM \u00b7 ")
        append(cap.cpuCores); append(" cores \u00b7 ")
        append(cap.refreshRateHz.toInt()); append(" Hz")
        if (cap.supportsHdr) append(" \u00b7 HDR")
        if (cap.isLowRamDevice) append(" \u00b7 low-RAM device")
    }
    Text(
        facts,
        style = MaterialTheme.typography.labelSmall,
        color = MediaColors.CreamDim,
        modifier = Modifier.padding(Space.xl, Space.md, Space.xl, 0.dp)
    )
    Text(
        "Measured ceiling: ${ceiling.label} (score ${cap.score}/8)",
        style = MaterialTheme.typography.labelSmall,
        color = MediaColors.CreamFaint,
        modifier = Modifier.padding(Space.xl, 2.dp, Space.xl, 0.dp)
    )
    // Required by the blueprint's product-language rule, and true: this setting
    // changes how Aura renders. It cannot turn an LCD into an OLED, and saying
    // otherwise would be a claim the app cannot support.
    Text(
        "Display quality changes how Aura renders. It does not change your screen.",
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
    var statusValue by remember { mutableStateOf<String?>(null) }

    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !scanning) {
                scope.launch {
                    scanning = true
                    statusValue = "Scanning your media…"
                    delay(900)           // let the scanning state be seen
                    onRescan()
                    statusValue = "Library updated"
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
        Text("Rescan device", style = MaterialTheme.typography.bodyLarge, color = MediaColors.Cream,
            modifier = Modifier.weight(1f))
        if (scanning) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MediaColors.Accent
            )
            Spacer(Modifier.width(Space.sm))
        }
        if (statusValue != null) {
            Text(statusValue!!, style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamDim)
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
            Text("Text size", style = MaterialTheme.typography.bodyLarge, color = MediaColors.Cream)
        }
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            listOf(0.9f to "Compact", 1.0f to "Default", 1.15f to "Large").forEach { (scale, label) ->
                val sel = kotlin.math.abs(scale - current) < 0.01f
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        // Was a white pill, sitting directly above teal radio
                        // buttons: two different selection languages on one
                        // screen. Teal marks state everywhere; white is kept
                        // for exactly one thing, the play button.
                        .background(if (sel) MediaColors.Accent else MediaColors.InkRaised)
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
    // "Support Aura" alone doesn't say ads go away.
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
                contentDescription = "Change your picture",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .border(1.dp, MediaColors.InkHairline, CircleShape)
                    .clickable { pick() }
            )
        } else {
            Image(
                painter = painterResource(R.drawable.aura_mark),
                contentDescription = "Add your picture",
                modifier = Modifier.height(96.dp).clickable { pick() }
            )
        }
        Spacer(Modifier.height(Space.md))
        Text(
            "Your library",
            style = MaterialTheme.typography.titleLarge,
            color = MediaColors.Cream
        )
        Text(
            "$audioCount tracks \u00B7 $videoCount videos",
            style = MaterialTheme.typography.bodyMedium,
            color = MediaColors.CreamDim
        )
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (shot != null) "Change picture" else "Add your picture",
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
                    "Remove",
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
