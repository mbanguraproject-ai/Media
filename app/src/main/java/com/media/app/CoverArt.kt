package com.media.app

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Deterministic warm-muted base tint from a title.
private fun tintFor(seed: String): Color {
    val palettes = listOf(
        Color(0xFF2D3561), Color(0xFF1F4037), Color(0xFF4A2C3D),
        Color(0xFF243B55), Color(0xFF3D3A24), Color(0xFF3A2A4D),
        Color(0xFF4D2F2A), Color(0xFF2A3D3A)
    )
    val idx = (seed.sumOf { it.code } % palettes.size)
    return palettes[if (idx < 0) idx + palettes.size else idx]
}

// A two-stop diagonal gradient derived from the base tint — a designed surface,
// not a flat block. The second stop is a lightened shift of the same hue.
private fun gradientFor(seed: String): Brush {
    val base = tintFor(seed)
    val lifted = Color(
        red = (base.red + 0.10f).coerceAtMost(1f),
        green = (base.green + 0.10f).coerceAtMost(1f),
        blue = (base.blue + 0.12f).coerceAtMost(1f),
        alpha = 1f
    )
    return Brush.linearGradient(listOf(lifted, base))
}

private fun pillarIcon(pillar: Pillar): ImageVector = when (pillar) {
    Pillar.MUSIC -> Icons.Filled.MusicNote
    Pillar.PODCAST -> Icons.Filled.Podcasts
    Pillar.AUDIOBOOK -> Icons.AutoMirrored.Filled.MenuBook
    Pillar.VIDEO -> Icons.Filled.Movie
}

private fun pillarLabel(pillar: Pillar): String = when (pillar) {
    Pillar.MUSIC -> "Music"
    Pillar.PODCAST -> "Podcast"
    Pillar.AUDIOBOOK -> "Audiobook"
    Pillar.VIDEO -> "Video"
}

@Composable
fun CoverArt(
    item: AppMediaItem,
    modifier: Modifier = Modifier,
    corner: Int = 12
) {
    val context = LocalContext.current
    var art by remember(item.uri) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(item.uri) { mutableStateOf(true) }

    LaunchedEffect(item.uri) {
        loading = true
        art = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, item.uri)
                // Audio: embedded cover art. Video: no embedded picture usually,
                // so grab a representative frame (~1s in) like a video thumbnail.
                val embedded = retriever.embeddedPicture
                val bmp = if (embedded != null) {
                    BitmapFactory.decodeByteArray(embedded, 0, embedded.size)
                } else if (item.type == MediaType.VIDEO) {
                    // 1s in avoids the black first frame; OPTION_CLOSEST_SYNC is fast.
                    retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } else {
                    null
                }
                retriever.release()
                bmp?.asImageBitmap()
            }.getOrNull()
        }
        loading = false
    }

    Box(
        modifier = modifier.clip(RoundedCornerShape(corner.dp)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = art
        if (loading) {
            // Neutral placeholder while art/frame loads — just the gradient wash,
            // no drop-cap or badge, so the swap to a real frame is seamless.
            Box(Modifier.fillMaxSize().background(gradientFor(item.title)))
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Designed fallback: gradient wash + faint pillar watermark bleeding
            // off the top-right + a bold Fraunces drop-cap anchored bottom-left.
            BoxWithConstraints(Modifier.fillMaxSize().background(gradientFor(item.title))) {
                val side = if (maxWidth < maxHeight) maxWidth else maxHeight
                val capSize = (side.value * 0.46f).sp
                val markSize = side * 0.9f

                Icon(
                    imageVector = pillarIcon(item.pillar),
                    contentDescription = null,
                    tint = MediaColors.Cream.copy(alpha = 0.10f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = (side.value * 0.06f).dp, end = (side.value * 0.02f).dp)
                        .size(markSize)
                )

                Text(
                    text = item.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "M",
                    fontFamily = Fraunces,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = capSize,
                    color = MediaColors.Cream.copy(alpha = 0.88f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = (side.value * 0.08f).dp, bottom = (side.value * 0.02f).dp)
                )

            }
        }

        // Type badge — quiet ink pill, top-left. Shown on EVERY cover (art and
        // fallback alike) so the grid stays visually consistent. The translucent
        // ink sits lightly on real artwork without fighting it.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MediaColors.Ink.copy(alpha = 0.45f))
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text(
                text = pillarLabel(item.pillar),
                fontFamily = Inter,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                color = MediaColors.Cream.copy(alpha = 0.9f)
            )
        }
    }
}
