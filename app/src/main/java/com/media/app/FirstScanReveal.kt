package com.media.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ============================================================================
//  FIRST-RUN REVEAL (§29, §42)
//
//  §29: "Do not simply show 'Scanning...'"
//  §42: "The first successful scan should feel like a reveal. Do not
//        immediately dump the user into a giant list."
//
//  Shown ONCE, ever. The scan itself is a MediaStore cursor and finishes in
//  well under a second on most libraries, so this is not a progress bar
//  pretending to measure work — it is the result being presented. Counts tick
//  up from zero, which is honest: they are the real totals, animated.
// ============================================================================

@Composable
fun FirstScanReveal(
    trackCount: Int,
    albumCount: Int,
    artistCount: Int,
    onDone: () -> Unit
) {
    val reduced = LocalReducedMotion.current
    // Staged so the three lines land one after another rather than together.
    var stage by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        if (reduced) { stage = 4; delay(900); onDone(); return@LaunchedEffect }
        delay(260); stage = 1
        delay(420); stage = 2
        delay(420); stage = 3
        delay(700); stage = 4
        delay(900); onDone()
    }

    Column(
        Modifier.fillMaxSize().background(moodBackground()).padding(Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Building your library",
            style = Typo.Display, color = MediaColors.Cream,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Space.xxl))

        RevealLine("tracks discovered", trackCount, visible = stage >= 1, reduced = reduced)
        Spacer(Modifier.height(Space.lg))
        RevealLine("albums organised", albumCount, visible = stage >= 2, reduced = reduced)
        Spacer(Modifier.height(Space.lg))
        RevealLine("artists identified", artistCount, visible = stage >= 3, reduced = reduced)

        Spacer(Modifier.height(Space.xxxl))
        Box(
            Modifier
                .alpha(if (stage >= 4) 1f else 0f)
                .clip(CircleShape)
                .background(MediaColors.Accent)
                .pressScale(haptic = true, onClick = onDone)
                .padding(horizontal = Space.xxl, vertical = Space.md)
        ) {
            Text("Start listening", style = Typo.Label, color = Color.White)
        }
    }
}

@Composable
private fun RevealLine(label: String, target: Int, visible: Boolean, reduced: Boolean) {
    // Counts animate from zero to the REAL total — nothing is fabricated.
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (reduced) 0 else 620, easing = Motion.Emphasized),
        label = "count"
    )
    val shown = (target * progress).toInt()

    Row(
        Modifier.fillMaxWidth().alpha(progress),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom
    ) {
        Text("$shown", style = Typo.Display, color = MediaColors.Accent)
        Spacer(Modifier.width(Space.sm))
        Text(
            label, style = Typo.Body, color = MediaColors.CreamDim,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}
