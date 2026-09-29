package com.media.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ============================================================================
//  SOUND
//
//  Settings' own layout - micro section labels, full-width rows, the accent
//  only on what is on - holding the DSP chain the Audio Path sheet reports.
//  Every change is saved as it happens and the service picks it up live, so
//  what you hear while dragging is what you keep.
// ============================================================================

@Composable
fun SoundScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SoundEngine.load(context) }
    val s by SoundEngine.settings.collectAsState()
    val status by SoundEngine.status.collectAsState()
    fun update(next: SoundSettings) = SoundEngine.save(context, next)
    val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    Column(
        Modifier.fillMaxSize().background(moodBackground(flat = true)).statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth().padding(Space.sm, Space.sm), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MediaColors.Cream) }
            Spacer(Modifier.width(Space.xs))
            Text("Sound", style = Typo.Section, color = MediaColors.Cream)
        }

        Section("Equalizer")
        Toggle("Equalizer", "Ten bands, applied to everything that plays", s.eqEnabled) {
            update(s.copy(eqEnabled = it))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = Space.xl, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            SoundEngine.PRESETS.forEach { (name, gains) ->
                Chip(name, selected = s.eqEnabled && s.preset == name) {
                    update(s.copy(eqEnabled = true, preset = name, bands = gains))
                }
            }
            if (s.preset == "Custom") Chip("Custom", selected = s.eqEnabled) {}
        }
        SoundEngine.BANDS.forEachIndexed { i, hz ->
            val g = s.bands.getOrElse(i) { 0f }
            SliderRow(
                label = if (hz >= 1000) "${(hz / 1000).roundToInt()} kHz" else "${hz.roundToInt()} Hz",
                value = g, range = -SoundEngine.BAND_RANGE_DB..SoundEngine.BAND_RANGE_DB,
                readout = "%+.0f dB".format(java.util.Locale.ROOT, g),
                enabled = s.eqEnabled
            ) { v ->
                val bands = s.bands.toMutableList().also { it[i] = (v * 2).roundToInt() / 2f }
                update(s.copy(bands = bands, preset = "Custom"))
            }
        }

        Section("Loudness")
        if (!modern) {
            Note("Preamp, ReplayGain and the limiter need Android 9 or newer.")
        }
        SliderRow(
            label = "Preamp", value = s.preampDb, range = -12f..12f,
            readout = "%+.1f dB".format(java.util.Locale.ROOT, s.preampDb), enabled = modern
        ) { update(s.copy(preampDb = (it * 2).roundToInt() / 2f)) }
        Column(Modifier.fillMaxWidth().padding(Space.xl, Space.md)) {
            Text("ReplayGain", style = Typo.Body, color = if (modern) MediaColors.Cream else MediaColors.CreamFaint)
            Text("Plays every track at the same loudness, using the gain tags in the file",
                style = Typo.Tertiary, color = MediaColors.CreamFaint)
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                ReplayGainMode.entries.forEach { m ->
                    Chip(m.label, selected = s.replayGain == m, modifier = Modifier.weight(1f)) {
                        if (modern) update(s.copy(replayGain = m))
                    }
                }
            }
            status.replayGainDb?.let {
                Spacer(Modifier.height(Space.xs))
                Text("Now playing: %+.2f dB".format(java.util.Locale.ROOT, it), style = Typo.Tertiary, color = MediaColors.Accent)
            }
        }
        Toggle("Limiter", "Catches peaks so gain and EQ boosts never clip", s.limiter, enabled = modern) {
            update(s.copy(limiter = it))
        }

        Section("Effects")
        SliderRow(
            label = "Bass", value = s.bass / 10f, range = 0f..100f,
            readout = "${s.bass / 10}%", enabled = true
        ) { update(s.copy(bass = (it * 10).roundToInt())) }
        SliderRow(
            label = "Spatial", value = s.spatial / 10f, range = 0f..100f,
            readout = "${s.spatial / 10}%", enabled = true
        ) { update(s.copy(spatial = (it * 10).roundToInt())) }
        Note("Bass and Spatial depend on what this phone's audio hardware supports; on some devices they only work with headphones.")

        Box(Modifier.fillMaxWidth().padding(Space.xl, Space.lg), contentAlignment = Alignment.CenterStart) {
            Text("Reset to flat", style = Typo.Label, color = MediaColors.CreamDim,
                modifier = Modifier.pressScale(haptic = true) { update(SoundSettings()) })
        }
        Spacer(Modifier.height(bottomSafePadding(gap = 100.dp)))
    }
}

@Composable
private fun Section(text: String) {
    Text(text.uppercase(), style = Typo.Micro, color = MediaColors.CreamDim,
        modifier = Modifier.padding(Space.xl, Space.xl, Space.xl, Space.sm))
}

@Composable
private fun Note(text: String) {
    Text(text, style = Typo.Tertiary, color = MediaColors.CreamFaint,
        modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.xs))
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(Space.xl, Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Typo.Body, color = if (enabled) MediaColors.Cream else MediaColors.CreamFaint)
            Text(subtitle, style = Typo.Tertiary, color = MediaColors.CreamFaint)
        }
        Spacer(Modifier.width(Space.md))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
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
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) MediaColors.Accent else MediaColors.Elevated)
            .border(0.5.dp, if (selected) MediaColors.Accent else MediaColors.InkHairline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = Typo.Label, color = if (selected) MediaColors.OnAccent else MediaColors.CreamDim)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: String,
    enabled: Boolean,
    onChange: (Float) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.xl),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = Typo.Secondary,
            color = if (enabled) MediaColors.CreamDim else MediaColors.CreamFaint,
            modifier = Modifier.width(64.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MediaColors.Cream,
                activeTrackColor = MediaColors.Accent,
                inactiveTrackColor = MediaColors.Fill,
                disabledThumbColor = MediaColors.CreamFaint,
                disabledActiveTrackColor = MediaColors.FillStrong,
                disabledInactiveTrackColor = MediaColors.Fill
            ),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Space.sm))
        Text(readout, style = Typo.Tertiary, color = MediaColors.CreamFaint,
            modifier = Modifier.width(56.dp).padding(start = Space.xs))
    }
}
