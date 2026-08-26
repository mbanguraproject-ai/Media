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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    audioCount: Int,
    videoCount: Int,
    settings: MediaSettings,
    onFontScaleChange: (Float) -> Unit,
    onRescan: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenAbout: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    // Repo moved owners; the old bangscc10-dev Pages URL is stale.
    val privacyUrl = "https://mbanguraproject-ai.github.io/Media/privacy.html"
    Column(
        Modifier.fillMaxSize().background(moodBackground()).statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Space.sm, Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MediaColors.Cream) }
        }

        // Profile block
        Column(Modifier.fillMaxWidth().padding(Space.xl, Space.sm, Space.xl, Space.xl)) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(MediaColors.InkRaised),
                contentAlignment = Alignment.Center
            ) {
                Text("M", style = MaterialTheme.typography.displaySmall, color = MediaColors.Cream)
            }
            Spacer(Modifier.height(Space.md))
            Text("Your library", style = MaterialTheme.typography.titleLarge, color = MediaColors.Cream)
            Text("$audioCount tracks · $videoCount videos",
                style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamDim)
        }

        SectionLabel("Appearance")
        FontSizePicker(settings.fontScale, onFontScaleChange)

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
            SettingRow(Icons.Outlined.Verified, "Ads removed", "Thank you", navigates = false) {}
        } else if (price != null) {
            SectionLabel("Support")
            SettingRow(Icons.Outlined.Block, "Remove ads", price) {
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
                        .background(if (sel) MediaColors.Cream else MediaColors.InkRaised)
                        .border(0.5.dp, if (sel) MediaColors.Cream else MediaColors.InkHairline, RoundedCornerShape(10.dp))
                        .clickable { onChange(scale) }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium,
                        color = if (sel) MediaColors.OnInverse else MediaColors.CreamDim)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MediaColors.Cream,
        modifier = Modifier.padding(Space.xl, Space.lg, Space.xl, Space.xs))
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    value: String?,
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
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MediaColors.Cream,
            modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MediaColors.CreamDim)
            Spacer(Modifier.width(Space.sm))
        }
        if (navigates) {
            Icon(Icons.Filled.ChevronRight, null, tint = MediaColors.CreamFaint, modifier = Modifier.size(18.dp))
        }
    }
}
