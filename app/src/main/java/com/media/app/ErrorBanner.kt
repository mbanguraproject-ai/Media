package com.media.app

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ============================================================================
//  ERROR BANNER (§22)
//
//  A banner, deliberately not a dialog. §22: "Errors should not destroy
//  current context" — the queue, the scroll position and the rest of the UI
//  all stay exactly where they were, and the message can simply be dismissed.
//
//  Actions are chosen per error kind, because "Try again" is useless advice
//  when the file has been deleted.
// ============================================================================

@Composable
fun ErrorBanner(
    error: PlaybackError?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = error != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        val e = error ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.md)
                .clip(RoundedCornerShape(Radius.lg))
                .background(MediaColors.Modal)
                .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f), RoundedCornerShape(Radius.lg))
                .padding(Space.lg)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.ErrorOutline, null, tint = Color(0xFFEF4444),
                    modifier = Modifier.size(IconSize.md)
                )
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(e.headline, style = Typo.Primary, color = MediaColors.Cream)
                    Spacer(Modifier.height(Space.xxs))
                    Text(e.detail, style = Typo.Secondary, color = MediaColors.CreamDim)
                    Spacer(Modifier.height(Space.xxs))
                    Text(
                        e.trackTitle, style = Typo.Tertiary, color = MediaColors.CreamFaint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.Filled.Close, "Dismiss", tint = MediaColors.CreamFaint,
                    modifier = Modifier.size(IconSize.md).pressScale(onClick = onDismiss)
                )
            }

            Spacer(Modifier.height(Space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                when (e.kind) {
                    ErrorKind.MISSING -> {
                        BannerAction("Skip track", primary = true, onClick = onSkip)
                        BannerAction("Rescan library", primary = false, onClick = onRescan)
                    }
                    ErrorKind.FORMAT -> {
                        BannerAction("Skip track", primary = true, onClick = onSkip)
                        if (e.uri != null) {
                            BannerAction("Open file", primary = false) {
                                // Hand it to something that can play it. Wrapped:
                                // plenty of devices have no handler for the type.
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(android.net.Uri.parse(e.uri), "*/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    )
                                }
                                onDismiss()
                            }
                        }
                    }
                    ErrorKind.PERMISSION -> {
                        BannerAction("Try again", primary = true, onClick = onRetry)
                        BannerAction("Rescan library", primary = false, onClick = onRescan)
                    }
                    ErrorKind.GENERIC -> {
                        BannerAction("Try again", primary = true, onClick = onRetry)
                        BannerAction("Skip track", primary = false, onClick = onSkip)
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerAction(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .then(
                if (primary) Modifier.background(MediaColors.Accent)
                else Modifier.border(
                    1.dp, MediaColors.CreamFaint.copy(alpha = 0.4f),
                    RoundedCornerShape(Radius.pill)
                )
            )
            .pressScale(haptic = true, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.sm)
    ) {
        Text(
            label, style = Typo.Label,
            color = if (primary) Color.White else MediaColors.CreamDim
        )
    }
}
