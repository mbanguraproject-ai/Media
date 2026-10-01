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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.remember
import kotlin.math.log10
import kotlin.math.pow
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
        Toggle("Equalizer", "Ten sliders shape one smooth curve, applied to everything that plays", s.eqEnabled) {
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
        ResponseCurve(s, status.speaker)
        SoundEngine.BANDS.forEachIndexed { i, hz ->
            val g = s.bands.getOrElse(i) { 0f }
            SliderRow(
                label = if (hz >= 1000) "${(hz / 1000).roundToInt()} kHz" else "${hz.roundToInt()} Hz",
                value = g, range = -SoundEngine.BAND_RANGE_DB..SoundEngine.BAND_RANGE_DB,
                // One decimal: the sliders move in half-dB steps, and "+1 dB"
                // for a +0.5 setting was a readout that lied by half.
                readout = "%+.1f dB".format(java.util.Locale.ROOT, g),
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
        // Said plainly, from what the chain is doing right now.
        Note(
            if (status.speaker)
                "Tuned for the phone speaker: lifts the punch range it can actually play, and trims the deep sub-bass it can't."
            else
                "Tuned for headphones: the full low end, down to 31 Hz."
        )
        SliderRow(
            label = "Spatial", value = s.spatial / 10f, range = 0f..100f,
            readout = "${s.spatial / 10}%", enabled = true
        ) { update(s.copy(spatial = (it * 10).roundToInt())) }
        Note(
            when {
                !status.spatial -> "This phone has no virtualiser, so Spatial can't run here."
                status.speaker -> "Spatial needs headphones. Android switches it off on the phone speaker, which plays in mono."
                s.spatial > 0 && status.spatialActive -> "Spatial is on."
                s.spatial > 0 -> "Spatial is set, but Android isn't virtualising on this output."
                else -> "Widens the stereo image on headphones."
            }
        )

        Section("Engine")
        Toggle(
            "Compatibility mode",
            "Use Android's classic equaliser. Try this if the EQ makes no difference on your phone.",
            s.classic
        ) { update(s.copy(classic = it)) }
        Note(
            when (status.engine) {
                "Dynamics" -> if (status.eqBands >= 31)
                    "Running on Dynamics Processing: the curve rendered at 31 bands (1/3 octave), with preamp and limiter."
                    else "Running on Dynamics Processing: ${status.eqBands} bands, with preamp and limiter."
                "Classic" -> "Running on the classic equaliser: the curve is sampled at this phone's ${status.eqBands} bands."
                else -> "Start playing something to see the engine in use."
            }
        )

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

/**
 * The response actually applied right now - the sliders, the bass shelf for
 * this output, joined by the same smooth curve the engine renders - from 20Hz
 * to 20kHz on a log scale, +/-15dB.
 */
@Composable
private fun ResponseCurve(s: SoundSettings, speaker: Boolean) {
    val accent = MediaColors.Accent
    val grid = MediaColors.Fill
    val points = remember(s, speaker) { SoundEngine.effectiveBands(s, speaker) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.sm)) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val w = size.width
            val h = size.height
            val range = 15f
            val lo = log10(20f)
            val hi = log10(20000f)
            fun yOf(db: Float) = h / 2f - (db.coerceIn(-range, range) / range) * (h / 2f)
            fun xOf(hz: Float) = (log10(hz) - lo) / (hi - lo) * w
            for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
                drawLine(grid, Offset(0f, yOf(db)), Offset(w, yOf(db)), strokeWidth = if (db == 0f) 2f else 1f)
            }
            for (f in listOf(100f, 1000f, 10000f)) drawLine(grid, Offset(xOf(f), 0f), Offset(xOf(f), h), strokeWidth = 1f)
            val path = Path()
            val steps = 160
            for (i in 0..steps) {
                val hz = 10f.pow(lo + (hi - lo) * i / steps)
                val x = w * i / steps
                val y = yOf(EqCurve.at(hz, SoundEngine.BANDS, points))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            for ((i, f) in SoundEngine.BANDS.withIndex()) {
                drawCircle(accent, radius = 2.5.dp.toPx(), center = Offset(xOf(f), yOf(points[i])))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.xxs), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("20 Hz", style = Typo.Micro, color = MediaColors.CreamFaint)
            Text(if (speaker) "Tuned for the phone speaker" else "Tuned for headphones",
                style = Typo.Micro, color = MediaColors.CreamDim)
            Text("20 kHz", style = Typo.Micro, color = MediaColors.CreamFaint)
        }
    }
}
