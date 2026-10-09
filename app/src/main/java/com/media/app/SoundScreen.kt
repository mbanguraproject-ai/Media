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
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

// ============================================================================
//  SOUND
//
//  Settings' own layout - micro section labels, full-width rows, the accent
//  only on what is on - holding the DSP chain the Audio Path sheet reports.
//  Every change is saved as it happens and the service picks it up live, so
//  what you hear while dragging is what you keep.
//
//  It opens on the output: the headphones, speaker or car playing right now,
//  whose own tuning everything under it is. On the phone speaker there is
//  nothing to tune and the screen says so instead of offering dead sliders.
// ============================================================================

@Composable
fun SoundScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    // The output now, and again whenever one comes or goes while this is
    // open - with a second look, as a device appears a moment before audio
    // is routed to it.
    DisposableEffect(Unit) {
        SoundEngine.load(context)
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val look = Runnable { SoundEngine.load(context) }
        val watch = object : android.media.AudioDeviceCallback() {
            fun again() {
                SoundEngine.load(context)
                handler.removeCallbacks(look)
                handler.postDelayed(look, 700)
            }
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) = again()
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) = again()
        }
        am.registerAudioDeviceCallback(watch, handler)
        onDispose {
            am.unregisterAudioDeviceCallback(watch)
            handler.removeCallbacksAndMessages(null)
        }
    }
    val s by SoundEngine.settings.collectAsState()
    val route by SoundEngine.route.collectAsState()
    val status by SoundEngine.status.collectAsState()
    fun update(next: SoundSettings) = SoundEngine.save(context, next)
    val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    Column(
        Modifier.fillMaxSize().screenBackground().statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth().padding(Space.sm, Space.sm), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MediaColors.Cream) }
            Spacer(Modifier.width(Space.xs))
            Text(stringResource(R.string.sound_title), style = Typo.Section, color = MediaColors.Cream)
        }

        Section(stringResource(R.string.sound_output))
        OutputCard(route, s.output) { update(s.copy(output = it)) }

        if (route.external) {
            Section(stringResource(R.string.sound_stage))
            AmountSlider(stringResource(R.string.sound_depth), s.depth) { update(s.copy(depth = it)) }
            Note(stringResource(when (s.output) {
                OutputClass.HEADPHONES -> R.string.sound_depth_headphones
                OutputClass.SPEAKER -> R.string.sound_depth_speaker
                OutputClass.CAR -> R.string.sound_depth_car
            }))
            Spacer(Modifier.height(Space.sm))
            AmountSlider(stringResource(R.string.sound_space), s.space) { update(s.copy(space = it)) }
            Note(stringResource(when (s.output) {
                OutputClass.HEADPHONES -> R.string.sound_space_headphones
                OutputClass.SPEAKER -> R.string.sound_space_speaker
                OutputClass.CAR -> R.string.sound_space_car
            }))

            Section(stringResource(R.string.sound_eq))
            Toggle(stringResource(R.string.sound_eq), stringResource(R.string.sound_eq_sub), s.eqEnabled) {
                update(s.copy(eqEnabled = it))
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = Space.xl, vertical = Space.sm),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                SoundEngine.PRESETS.forEach { (name, gains) ->
                    Chip(presetLabel(name), selected = s.eqEnabled && s.preset == name) {
                        update(s.copy(eqEnabled = true, preset = name, bands = gains))
                    }
                }
                if (s.preset == "Custom") Chip(presetLabel("Custom"), selected = s.eqEnabled) {}
            }
            ResponseCurve(s, route)
            SoundEngine.BANDS.forEachIndexed { i, hz ->
                val g = s.bands.getOrElse(i) { 0f }
                SliderRow(
                    label = if (hz >= 1000) "${(hz / 1000).roundToInt()} kHz" else "${hz.roundToInt()} Hz",
                    value = g, range = -SoundEngine.BAND_RANGE_DB..SoundEngine.BAND_RANGE_DB,
                    // One decimal: the sliders move in half-dB steps, and "+1 dB"
                    // for a +0.5 setting was a readout that lied by half.
                    readout = stringResource(R.string.db_value, g),
                    enabled = s.eqEnabled
                ) { v ->
                    val bands = s.bands.toMutableList().also { it[i] = (v * 2).roundToInt() / 2f }
                    update(s.copy(bands = bands, preset = "Custom"))
                }
            }
        }

        Section(stringResource(R.string.sound_loudness))
        if (!modern) {
            Note(stringResource(R.string.sound_needs_p))
        }
        SliderRow(
            label = stringResource(R.string.sound_preamp), value = s.preampDb, range = -12f..12f,
            readout = stringResource(R.string.db_value, s.preampDb), enabled = modern
        ) { update(s.copy(preampDb = (it * 2).roundToInt() / 2f)) }
        Column(Modifier.fillMaxWidth().padding(Space.xl, Space.md)) {
            Text(stringResource(R.string.sound_replaygain), style = Typo.Body, color = if (modern) MediaColors.Cream else MediaColors.CreamFaint)
            Text(stringResource(R.string.sound_replaygain_sub),
                style = Typo.Tertiary, color = MediaColors.CreamFaint)
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                ReplayGainMode.entries.forEach { m ->
                    Chip(stringResource(m.label), selected = s.replayGain == m, modifier = Modifier.weight(1f)) {
                        if (modern) update(s.copy(replayGain = m))
                    }
                }
            }
            status.replayGainDb?.let {
                Spacer(Modifier.height(Space.xs))
                Text(stringResource(R.string.sound_rg_now, it), style = Typo.Tertiary, color = MediaColors.Accent)
            }
        }
        Toggle(stringResource(R.string.sound_limiter), stringResource(R.string.sound_limiter_sub), s.limiter, enabled = modern) {
            update(s.copy(limiter = it))
        }

        Section(stringResource(R.string.sound_engine))
        Toggle(
            stringResource(R.string.sound_compat),
            stringResource(R.string.sound_compat_sub),
            s.classic
        ) { update(s.copy(classic = it)) }
        Note(
            when (status.engine) {
                "Dynamics" -> if (status.eqBands >= 31) stringResource(R.string.sound_engine_dynamics_31)
                    else stringResource(R.string.sound_engine_dynamics, status.eqBands)
                "Classic" -> stringResource(R.string.sound_engine_classic, status.eqBands)
                else -> stringResource(R.string.sound_engine_idle)
            }
        )

        ProPlaybackSection()

        Box(Modifier.fillMaxWidth().padding(Space.xl, Space.lg), contentAlignment = Alignment.CenterStart) {
            // This output back to flat (still the same kind of device), and
            // loudness back to its defaults.
            Text(stringResource(R.string.sound_reset_flat), style = Typo.Label, color = MediaColors.CreamDim,
                modifier = Modifier.pressScale(haptic = true) { update(SoundSettings(output = s.output)) })
        }
        Spacer(Modifier.height(bottomSafePadding(gap = 100.dp)))
    }
}

/** Depth or Space: its name and amount on one line, the slider full width under it. */
@Composable
private fun AmountSlider(title: String, amount: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl)) {
        SliderLabel(title, stringResource(R.string.percent_value, amount / 10))
        Slider(
            value = amount / 10f,
            onValueChange = { onChange((it * 10).roundToInt().coerceIn(0, 1000)) },
            valueRange = 0f..100f,
            colors = proSliderColors()
        )
    }
}

/**
 * What is playing the sound. Headphones, a speaker or a car: its name, and
 * what kind of device it is, which shapes Depth and Space - guessed from the
 * device, corrected with one tap. The phone speaker: a plain word on why
 * there is nothing to tune.
 */
@Composable
private fun OutputCard(route: SoundRoute, output: OutputClass, onPick: (OutputClass) -> Unit) {
    val icon = when {
        !route.external -> Icons.Rounded.PhoneAndroid
        output == OutputClass.SPEAKER -> Icons.Rounded.Speaker
        output == OutputClass.CAR -> Icons.Rounded.DirectionsCar
        else -> Icons.Rounded.Headphones
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.xl)
            .clip(RoundedCornerShape(Radius.lg))
            .background(MediaColors.FillSubtle)
            .border(1.dp, MediaColors.Fill, RoundedCornerShape(Radius.lg))
            .padding(vertical = Space.lg)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.lg), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (route.external) MediaColors.Accent else MediaColors.Fill),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = if (route.external) MediaColors.OnAccent else MediaColors.CreamDim,
                    modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    if (route.external) route.name else stringResource(R.string.sound_output_phone),
                    style = Typo.Primary.copy(fontWeight = FontWeight.SemiBold), color = MediaColors.Cream,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(if (route.external) R.string.sound_output_own else R.string.sound_output_phone_sub),
                    style = Typo.Tertiary, color = MediaColors.CreamDim
                )
            }
        }
        if (route.external) {
            Spacer(Modifier.height(Space.md))
            val classes = OutputClass.entries
            // Natural widths, scrolling if a language's words run long.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                classes.forEach { c ->
                    Chip(
                        stringResource(when (c) {
                            OutputClass.HEADPHONES -> R.string.sound_class_headphones
                            OutputClass.SPEAKER -> R.string.sound_class_speaker
                            OutputClass.CAR -> R.string.sound_class_car
                        }),
                        selected = c == output
                    ) { onPick(c) }
                }
            }
        }
    }
}

/**
 * Via Pro's playback settings. Shown to everyone with the real controls: for
 * free users every switch reads off and a tap opens the Pro page, so the
 * section says exactly what Pro would change here.
 */
@Composable
private fun ProPlaybackSection() {
    val context = LocalContext.current
    val pro = isPro()
    val openPro = LocalOpenPro.current
    val p by ProPlayback.settings.collectAsState()
    fun set(change: (ProSettings) -> ProSettings) {
        if (pro) ProPlayback.update(context, change) else openPro()
    }

    Row(
        Modifier.fillMaxWidth().padding(Space.xl, Space.xl, Space.xl, Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.pro_playback).uppercase(), style = Typo.Micro, color = MediaColors.CreamDim)
        Spacer(Modifier.width(Space.sm))
        ProCrown(14.dp, lit = true)
    }
    if (!pro) ProLockedRow(stringResource(R.string.pro_playback_locked), onClick = openPro)
    Toggle(stringResource(R.string.pro_skip_silence), stringResource(R.string.pro_skip_silence_sub), pro && p.skipSilence) { v -> set { it.copy(skipSilence = v) } }
    Toggle(stringResource(R.string.pro_resume), stringResource(R.string.pro_resume_sub), pro && p.smartResume) { v -> set { it.copy(smartResume = v) } }
    Toggle(stringResource(R.string.pro_soft), stringResource(R.string.pro_soft_sub), pro && p.softPause) { v -> set { it.copy(softPause = v) } }
    Toggle(stringResource(R.string.pro_mono), stringResource(R.string.pro_mono_sub), pro && p.mono) { v -> set { it.copy(mono = v) } }
    val bal = if (pro) p.balance else 0f
    val pct = (kotlin.math.abs(bal) * 100f).roundToInt()
    SliderRow(
        label = stringResource(R.string.pro_balance), value = bal, range = -1f..1f,
        readout = when {
            pct == 0 -> stringResource(R.string.pro_balance_centre)
            bal < 0f -> stringResource(R.string.pro_balance_left, pct)
            else -> stringResource(R.string.pro_balance_right, pct)
        },
        enabled = true
    ) { v ->
        // Snaps to centre near the middle, so centre is easy to find again.
        val snapped = if (kotlin.math.abs(v) < 0.04f) 0f else (v * 20f).roundToInt() / 20f
        set { it.copy(balance = snapped) }
    }
    Column(Modifier.fillMaxWidth().padding(Space.xl, Space.md)) {
        Text(stringResource(R.string.pro_loop_repeats), style = Typo.Body, color = MediaColors.Cream)
        Text(stringResource(R.string.pro_loop_repeats_sub), style = Typo.Tertiary, color = MediaColors.CreamFaint)
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            LoopRepeatChoices.forEach { n ->
                Chip(
                    if (n == 0) stringResource(R.string.pro_loop_endless) else stringResource(R.string.pro_loop_times, n),
                    selected = pro && p.loopRepeats == n,
                    modifier = Modifier.weight(1f)
                ) { set { it.copy(loopRepeats = n) } }
            }
        }
    }
    Note(stringResource(R.string.pro_hires_note))
}

private val LoopRepeatChoices = listOf(0, 3, 5, 10)

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

// The shared chip (Chips.kt), so presets look like every other choice.
@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    ViaChip(label = label, selected = selected, modifier = modifier, onClick = onClick)

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
 * The response actually applied right now - the sliders joined by the same
 * smooth curve the engine renders, plus the shape Depth gives the low end on
 * this output - from 20Hz to 20kHz on a log scale, +/-15dB.
 */
@Composable
private fun ResponseCurve(s: SoundSettings, route: SoundRoute) {
    val accent = MediaColors.Accent
    val grid = MediaColors.Fill
    val points = remember(s, route) { SoundEngine.effectiveBands(s, route.external) }
    val steps = 160
    val lo = log10(20f)
    val hi = log10(20000f)
    // Depth's shape, worked out once per change rather than on every frame.
    val depthDb = remember(s.output, s.depth, route.external) {
        val hz = DoubleArray(steps + 1) { 10.0.pow((lo + (hi - lo) * it / steps).toDouble()) }
        if (route.external) SoundStage.depthCurveDb(s.output, s.depth / 1000f, hz, headroom = false)
        else DoubleArray(steps + 1)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.sm)) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val w = size.width
            val h = size.height
            val range = 15f
            fun yOf(db: Float) = h / 2f - (db.coerceIn(-range, range) / range) * (h / 2f)
            fun xOf(hz: Float) = (log10(hz) - lo) / (hi - lo) * w
            for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
                drawLine(grid, Offset(0f, yOf(db)), Offset(w, yOf(db)), strokeWidth = if (db == 0f) 2f else 1f)
            }
            for (f in listOf(100f, 1000f, 10000f)) drawLine(grid, Offset(xOf(f), 0f), Offset(xOf(f), h), strokeWidth = 1f)
            val path = Path()
            for (i in 0..steps) {
                val hz = 10f.pow(lo + (hi - lo) * i / steps)
                val x = w * i / steps
                val y = yOf(EqCurve.at(hz, SoundEngine.BANDS, points) + depthDb[i].toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (s.eqEnabled) for ((i, f) in SoundEngine.BANDS.withIndex()) {
                drawCircle(accent, radius = 2.5.dp.toPx(), center = Offset(xOf(f), yOf(points[i])))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.xxs), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("20 Hz", style = Typo.Micro, color = MediaColors.CreamFaint)
            Text(stringResource(R.string.sound_tuned_for, route.name),
                style = Typo.Micro, color = MediaColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = Space.sm))
            Text("20 kHz", style = Typo.Micro, color = MediaColors.CreamFaint)
        }
    }
}

/**
 * An EQ preset's name on screen. Presets are stored by their English name
 * ("Flat", "Custom"), which stays the key; this is the same name in the app's
 * language.
 */
@Composable
fun presetLabel(name: String): String = when (name) {
    "Flat" -> stringResource(R.string.preset_flat)
    "Bass" -> stringResource(R.string.preset_bass)
    "Warm" -> stringResource(R.string.preset_warm)
    "Vocal" -> stringResource(R.string.preset_vocal)
    "Bright" -> stringResource(R.string.preset_bright)
    "Loudness" -> stringResource(R.string.preset_loudness)
    "Classical" -> stringResource(R.string.preset_classical)
    "Electronic" -> stringResource(R.string.preset_electronic)
    "Custom" -> stringResource(R.string.preset_custom)
    else -> name
}
