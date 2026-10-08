package com.media.app

import android.content.Context
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// ============================================================================
//  VIA PRO
//
//  One payment, kept for good. Pro is the Play product that used to be
//  "remove ads" (Billing.REMOVE_ADS_ID): a product id can never be renamed in
//  Play, and keeping it means everyone who already paid is Pro the moment
//  they update, with nothing to restore. Billing stays the source of truth
//  for who owns it - acknowledged, restored on every launch, revoked on a
//  refund.
//
//  What Pro adds is playback, and all of it runs inside the player in
//  PlaybackService (ProAudio.kt), so it works from the notification, the lock
//  screen and a Bluetooth button as well as from the app:
//    - an A-B loop with a repeat count, for learning a passage
//    - speed from 0.5x to 3x in fine steps, and pitch on its own
//    - skip silence
//    - smart resume: a long recording steps back a few seconds after a pause
//    - soft play and pause: a short fade instead of a cut
//    - mono audio and left-right balance
//  plus no ads and the crown.
// ============================================================================

object Pro {
    /** True while this Google account owns Via Pro. */
    val active: StateFlow<Boolean> get() = Billing.adFree
}

/** Whether Pro is active, for composables. */
@Composable
fun isPro(): Boolean {
    val pro by Pro.active.collectAsState()
    return pro
}

/** Opens the Pro page from anywhere a locked feature is tapped. */
val LocalOpenPro = staticCompositionLocalOf<() -> Unit> { {} }

// ------------------------------------------------------------------ settings

/** An A-B loop on one track. [repeats] is total plays of the section; 0 = endless. */
data class AbLoop(val mediaId: String, val aMs: Long, val bMs: Long?, val repeats: Int)

data class ProSettings(
    val skipSilence: Boolean = false,
    val softPause: Boolean = true,
    val smartResume: Boolean = true,
    val mono: Boolean = false,
    /** -1 fully left .. 0 centre .. 1 fully right. */
    val balance: Float = 0f,
    /** Semitones, -6..6. Applied with the speed, independently of it. */
    val pitch: Int = 0,
    /** Plays of a section before the loop lets go; 0 = endless. */
    val loopRepeats: Int = 0
)

object ProPlayback {
    private const val PREFS = "pro_playback"
    private val _settings = MutableStateFlow(ProSettings())
    val settings: StateFlow<ProSettings> = _settings.asStateFlow()

    private val _loop = MutableStateFlow<AbLoop?>(null)
    /** The current loop, or one waiting for its B point. Null when off. */
    val loop: StateFlow<AbLoop?> = _loop.asStateFlow()

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = ProSettings(
            skipSilence = p.getBoolean("skipSilence", false),
            softPause = p.getBoolean("softPause", true),
            smartResume = p.getBoolean("smartResume", true),
            mono = p.getBoolean("mono", false),
            balance = p.getFloat("balance", 0f).coerceIn(-1f, 1f),
            pitch = p.getInt("pitch", 0).coerceIn(-6, 6),
            loopRepeats = p.getInt("loopRepeats", 0).coerceAtLeast(0)
        )
        loaded = true
    }

    fun update(context: Context, change: (ProSettings) -> ProSettings) {
        _settings.update(change)
        val s = _settings.value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("skipSilence", s.skipSilence)
            .putBoolean("softPause", s.softPause)
            .putBoolean("smartResume", s.smartResume)
            .putBoolean("mono", s.mono)
            .putFloat("balance", s.balance)
            .putInt("pitch", s.pitch)
            .putInt("loopRepeats", s.loopRepeats)
            .apply()
    }

    /**
     * The loop button: off -> A set here -> A-B looping -> off. [positionMs]
     * is where playback is now. Returns the new state.
     */
    fun tapLoop(mediaId: String?, positionMs: Long): AbLoop? {
        val now = _loop.value
        val next = when {
            mediaId == null -> null
            now == null || now.mediaId != mediaId -> AbLoop(mediaId, positionMs.coerceAtLeast(0L), null, _settings.value.loopRepeats)
            now.bMs == null && positionMs > now.aMs + MIN_LOOP_MS -> now.copy(bMs = positionMs)
            now.bMs == null -> now   // too close to A to be a section yet
            else -> null
        }
        _loop.value = next
        return next
    }

    fun clearLoop() { _loop.value = null }

    /** The shortest section a loop may be, so a double tap is not a loop. */
    const val MIN_LOOP_MS = 500L
}

/** Pitch in semitones as the factor the player takes. */
fun pitchFactor(semitones: Int): Float = Math.pow(2.0, semitones / 12.0).toFloat()

// --------------------------------------------------------------------- crown

private val GoldLight = Color(0xFFFFE7A3)
private val Gold = Color(0xFFF6C344)
private val GoldDeep = Color(0xFFD38A12)

/**
 * The Pro crown: gold, drawn so it is crisp at any size. [lit] is the
 * owned look, full gold; unlit it is an outline in gold, the offer.
 */
@Composable
fun ProCrown(
    size: Dp,
    lit: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
    // One flat colour instead of the gold: the dark crown on the owner's
    // gold badge.
    tint: Color? = null
) {
    Canvas(
        modifier
            .size(size)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
    ) {
        val w = this.size.width
        val h = this.size.height
        // Crown on a 24-unit grid: a band, and three points rising from it.
        fun x(v: Float) = v / 24f * w
        fun y(v: Float) = v / 24f * h
        val body = Path().apply {
            moveTo(x(3f), y(8.5f))
            lineTo(x(8f), y(13f))
            lineTo(x(12f), y(5.5f))
            lineTo(x(16f), y(13f))
            lineTo(x(21f), y(8.5f))
            lineTo(x(19.2f), y(18f))
            lineTo(x(4.8f), y(18f))
            close()
        }
        val gold = if (tint != null) androidx.compose.ui.graphics.SolidColor(tint)
            else Brush.verticalGradient(listOf(GoldLight, Gold, GoldDeep), startY = y(4f), endY = y(21f))
        if (lit) {
            drawPath(body, gold)
            drawRoundRect(gold, topLeft = Offset(x(4.8f), y(19f)), size = androidx.compose.ui.geometry.Size(x(14.4f), y(2.2f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(y(1.1f)))
        } else {
            drawPath(body, gold, style = Stroke(width = y(1.7f), join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawRoundRect(gold, topLeft = Offset(x(4.8f), y(19.4f)), size = androidx.compose.ui.geometry.Size(x(14.4f), y(1.7f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(y(0.85f)))
        }
        // A jewel on each point.
        for ((jx, jy) in listOf(3f to 8.5f, 12f to 5.5f, 21f to 8.5f)) {
            drawCircle(gold, radius = x(1.6f), center = Offset(x(jx), y(jy)))
        }
    }
}

// --------------------------------------------------------------------- badge

private val OnGoldInk = Color(0xFF2A1D02)
private const val SWEEP_MS = 3600

/**
 * The Home header's Pro badge, and the one place Pro shows who has it.
 *
 * FREE   a dark pill with a gold edge, a gold crown and PRO in gold: the way
 *        to the Pro page, plainly an offer.
 * OWNED  solid gold, a dark crown and PRO, a gold glow under it, and a sweep
 *        of light across it every few seconds: a status, not a button that
 *        happens to be yellow. The sweep stops with reduced motion.
 */
@Composable
fun ProBadge(owned: Boolean, description: String, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.pill)
    val reduced = LocalReducedMotion.current
    val sweep = if (owned && !reduced) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "proSweep").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(SWEEP_MS, easing = androidx.compose.animation.core.LinearEasing)
            ),
            label = "proSweepPhase"
        )
    } else null
    androidx.compose.foundation.layout.Row(
        Modifier
            .semantics(mergeDescendants = true) { contentDescription = description }
            .pressScale(haptic = true, onClick = onClick)
            .height(30.dp)
            .then(
                if (owned) Modifier.shadow(8.dp, shape, clip = false, ambientColor = Gold, spotColor = Gold)
                else Modifier
            )
            .clip(shape)
            .background(
                if (owned) Brush.linearGradient(listOf(GoldLight, Gold, GoldDeep))
                else Brush.linearGradient(listOf(Gold.copy(alpha = 0.16f), Gold.copy(alpha = 0.06f)))
            )
            .border(1.dp, if (owned) GoldLight.copy(alpha = 0.8f) else Gold.copy(alpha = 0.7f), shape)
            .drawWithContent {
                drawContent()
                val phase = sweep?.value ?: return@drawWithContent
                // The light crosses in the first quarter of the cycle, then rests.
                val t = (phase / 0.25f).coerceAtMost(1.2f)
                if (t > 1f) return@drawWithContent
                val band = size.height * 1.6f
                val x = -band + (size.width + band * 2f) * t
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                        start = Offset(x, 0f), end = Offset(x + band, size.height)
                    )
                )
            }
            .padding(start = 9.dp, end = 11.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        ProCrown(16.dp, lit = true, tint = if (owned) OnGoldInk else null)
        androidx.compose.foundation.layout.Spacer(Modifier.width(5.dp))
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(R.string.pro_plan_pro).uppercase(),
            style = Typo.Label.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                letterSpacing = 1.4.sp
            ),
            color = if (owned) OnGoldInk else Gold
        )
    }
}
