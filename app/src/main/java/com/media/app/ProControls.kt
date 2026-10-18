package com.media.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ============================================================================
//  PRO CONTROLS ON NOW PLAYING
//
//  The A-B loop button and the speed sheet. Both are in the transport row for
//  everyone: free users see what Pro adds where they would use it, and a tap
//  on anything locked opens the Pro page rather than doing nothing.
// ============================================================================

/** Speed as the transport row and the sheet show it: 1x, 1.25x, 0.8x. */
fun speedLabel(speed: Float): String {
    val r = (speed * 100f).roundToInt() / 100f
    return "${r}x".replace(".0x", "x")
}

/**
 * The loop button. Off: "A-B". After the first tap: "A-" lit, waiting for B.
 * Looping: "A-B" on an accent pill. Free: a small crown beside it.
 */
@Composable
fun LoopButton(vm: PlayerViewModel) {
    val pro = isPro()
    val openPro = LocalOpenPro.current
    val loop by ProPlayback.loop.collectAsState()
    val live = if (pro) loop else null
    val looping = live?.bMs != null
    val label = when {
        live == null -> "A-B"
        !looping -> "A-"
        else -> "A-B"
    }
    val spoken = stringResource(
        when {
            !pro -> R.string.pro_loop_locked
            live == null -> R.string.pro_loop_set_a
            !looping -> R.string.pro_loop_set_b
            else -> R.string.pro_loop_off
        }
    )
    Box(
        Modifier
            .semantics { contentDescription = spoken }
            .pressScale(haptic = true) { if (pro) vm.tapLoop() else openPro() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = Typo.Label.copy(fontWeight = FontWeight.Bold),
            color = when {
                looping -> MediaColors.OnAccent
                live != null -> MediaColors.Accent
                else -> MediaColors.CreamDim
            },
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(if (looping) MediaColors.Accent else MediaColors.FillSubtle)
                .border(1.dp, if (live != null) MediaColors.Accent else MediaColors.Fill, RoundedCornerShape(Radius.pill))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
        if (!pro) ProCrown(12.dp, lit = true, modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-8).dp))
    }
}

private val SpeedPresets = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Speed, and for Pro pitch. Presets for everyone; Pro adds a slider from
 * 0.5x to 3x in 0.05 steps and pitch in semitones, independent of speed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(state: PlayerState, vm: PlayerViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MediaColors.Modal,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MediaColors.CreamFaint) }
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Space.xl)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.xl), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.speed_title), style = Typo.Section, color = MediaColors.Cream, modifier = Modifier.weight(1f))
                Text(speedLabel(state.speed), style = Typo.Primary.copy(fontWeight = FontWeight.Bold), color = MediaColors.Accent)
            }
            Spacer(Modifier.height(Space.md))
            ChipRow(
                labels = SpeedPresets.map(::speedLabel),
                selected = SpeedPresets.indexOfFirst { kotlin.math.abs(it - state.speed) < 0.01f }
            ) { vm.setSpeed(SpeedPresets[it]) }
            Spacer(Modifier.height(Space.lg))
            SpeedProControls(state, vm, onDismiss)
        }
    }
}

@Composable
private fun SpeedProControls(state: PlayerState, vm: PlayerViewModel, onDismiss: () -> Unit) {
    val pro = isPro()
    val openPro = LocalOpenPro.current
    val context = LocalContext.current
    val settings by ProPlayback.settings.collectAsState()
    if (!pro) {
        // The sheet is its own window, above everything: close it first or
        // the Pro page opens behind it.
        ProLockedRow(stringResource(R.string.pro_speed_locked)) { onDismiss(); openPro() }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl)) {
        // The slider moves freely and commits on release, so dragging does
        // not write the speed to the database sixty times a second.
        var speed by remember(state.speed) { mutableFloatStateOf(state.speed) }
        SliderLabel(stringResource(R.string.speed_fine), speedLabel(speed))
        Slider(
            value = speed,
            onValueChange = { speed = (it * 20f).roundToInt() / 20f },
            onValueChangeFinished = { vm.setSpeed(speed) },
            valueRange = SPEED_MIN..SPEED_MAX,
            colors = proSliderColors()
        )
        Spacer(Modifier.height(Space.sm))
        val pitch = settings.pitch
        SliderLabel(
            stringResource(R.string.pitch_title),
            if (pitch == 0) stringResource(R.string.pitch_natural) else (if (pitch > 0) "+$pitch" else "$pitch")
        )
        Slider(
            value = pitch.toFloat(),
            onValueChange = { v ->
                val p = v.roundToInt().coerceIn(-6, 6)
                if (p != pitch) ProPlayback.update(context) { it.copy(pitch = p) }
            },
            valueRange = -6f..6f,
            steps = 11,
            colors = proSliderColors()
        )
        Text(stringResource(R.string.pitch_sub), style = Typo.Tertiary, color = MediaColors.CreamFaint)
    }
}

@Composable
fun SliderLabel(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Typo.Secondary, color = MediaColors.Cream, modifier = Modifier.weight(1f))
        Text(value, style = Typo.Secondary.copy(fontWeight = FontWeight.SemiBold), color = MediaColors.Accent)
    }
}

@Composable
fun proSliderColors() = SliderDefaults.colors(
    thumbColor = MediaColors.Cream,
    activeTrackColor = MediaColors.Accent,
    inactiveTrackColor = MediaColors.Fill,
    activeTickColor = MediaColors.OnAccent.copy(alpha = 0.5f),
    inactiveTickColor = MediaColors.CreamFaint
)

/** A locked Pro feature: the crown, what it is, and the way to the Pro page. */
@Composable
fun ProLockedRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.xl)
            .clip(RoundedCornerShape(Radius.md))
            .background(MediaColors.FillSubtle)
            .border(1.dp, MediaColors.Fill, RoundedCornerShape(Radius.md))
            .pressScale(haptic = true, scaleDown = 0.98f, onClick = onClick)
            .padding(Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        ProCrown(22.dp, lit = true)
        Text(text, style = Typo.Secondary, color = MediaColors.Cream, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.pro_unlock), style = Typo.Label.copy(fontWeight = FontWeight.Bold), color = MediaColors.Accent)
    }
}
