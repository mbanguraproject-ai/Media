package com.media.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// ============================================================================
//  UPDATE BANNER
//
//  A new version has downloaded (InAppUpdate.kt). Same place and shape as the
//  playback error banner, and like it a banner, not a dialog: nothing on
//  screen is interrupted, and the music plays on until Restart is tapped. It
//  gives way to a playback error, which is about the music right now.
// ============================================================================

@Composable
fun UpdateBanner(visible: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val stage by InAppUpdate.stage.collectAsState()
    AnimatedVisibility(
        visible = visible && stage == UpdateStage.READY,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.md)
                .clip(RoundedCornerShape(Radius.lg))
                .background(MediaColors.Modal)
                .border(1.dp, MediaColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(Radius.lg))
                .padding(Space.lg)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.SystemUpdate, null, tint = MediaColors.Accent,
                    modifier = Modifier.size(IconSize.md)
                )
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_ready_title), style = Typo.Primary, color = MediaColors.Cream)
                    Spacer(Modifier.height(Space.xxs))
                    Text(stringResource(R.string.update_ready_body), style = Typo.Secondary, color = MediaColors.CreamDim)
                }
            }
            Spacer(Modifier.height(Space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                BannerAction(stringResource(R.string.action_restart), primary = true) { InAppUpdate.restart(context) }
                BannerAction(stringResource(R.string.action_not_now), primary = false) { InAppUpdate.later() }
            }
        }
    }
}
