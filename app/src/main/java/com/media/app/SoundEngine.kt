package com.media.app

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

// ============================================================================
//  SOUND
//
//  The DSP chain, in signal order:
//      preamp + ReplayGain -> 10-band EQ + bass -> limiter   (DynamicsProcessing)
//      spatial                                                (Virtualizer)
//
//  BASS IS PART OF THE EQ, AND IT FOLLOWS THE OUTPUT. It used to be Android's
//  BassBoost effect, which on the stock effect bundle switches ITSELF off on
//  the phone speaker - so on a speaker the slider did nothing at all. And the
//  speaker cannot move air below ~150Hz anyway, so boosting 31-125Hz there
//  (which is also all the "Bass" preset did) was inaudible. Bass is now a
//  shelf folded into the EQ bands, shaped per output: the full low end on
//  headphones, the 125-500Hz punch range a phone speaker can actually play
//  on the speaker, with the sub-bass it cannot play trimmed to free headroom.
//
//  Spatial stays the Virtualizer. Android only virtualises for headphones,
//  and a phone speaker is mono, so the Sound screen says so rather than
//  offering a slider that silently does nothing.
//
//  Every stage is Android's own audio effect attached to the player's audio
//  session, so it runs in the system mixer at the mixer's precision and costs
//  the app nothing. That also works WITH the float output path the service
//  uses for 24-bit files, where media3's own audio processors are bypassed.
//
//  DynamicsProcessing is Android 9+. Below that the EQ falls back to the older
//  Equalizer effect and preamp/ReplayGain/limiter are reported as unavailable
//  rather than pretended.
//
//  Settings live in SharedPreferences, not DataStore: the playback service
//  needs them synchronously the moment a session is created, and a change
//  listener is the simplest way for it to follow the Sound screen live.
// ============================================================================

enum class ReplayGainMode(val label: String) { OFF("Off"), TRACK("Track"), ALBUM("Album") }

data class SoundSettings(
    val eqEnabled: Boolean = false,
    val bands: List<Float> = List(SoundEngine.BANDS.size) { 0f },
    val preset: String = "Flat",
    val preampDb: Float = 0f,
    val replayGain: ReplayGainMode = ReplayGainMode.OFF,
    /** Gain applied to files with no ReplayGain tags, so they are not louder than tagged ones. */
    val untaggedDb: Float = -6f,
    val limiter: Boolean = true,
    val bass: Int = 0,        // 0..1000
    val spatial: Int = 0,     // 0..1000
    /**
     * The classic Equalizer effect instead of DynamicsProcessing. For the
     * odd ROM whose DynamicsProcessing attaches without error and then
     * processes nothing: the listener has a way out instead of a dead EQ.
     */
    val classic: Boolean = false
)

/** What the chain is doing right now, for the Audio Path screen. */
data class SoundStatus(
    val sessionId: Int = 0,
    val dynamics: Boolean = false,
    val equalizer: Boolean = false,
    val bass: Boolean = false,
    val spatial: Boolean = false,
    /** Android is actually virtualising right now (it will not on a speaker). */
    val spatialActive: Boolean = false,
    /** Where the sound goes, as the chain tunes for it. */
    val speaker: Boolean = true,
    /** "Dynamics" or "Classic": which effect carries the EQ. */
    val engine: String = "",
    /** Net input gain in dB: preamp plus whatever ReplayGain applied. */
    val gainDb: Float = 0f,
    /** ReplayGain found for the current track, when enabled and tagged. */
    val replayGainDb: Float? = null,
    val limiterOn: Boolean = false
)

object SoundEngine {

    /** ISO octave centres. */
    val BANDS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    const val BAND_RANGE_DB = 12f

    /** dB at 100% bass on headphones, and on the phone speaker. */
    private const val BASS_MAX_FULL = 10f
    private const val BASS_MAX_SPEAKER = 8f

    // Per-band share of the bass shelf, on BANDS. Headphones get the real low
    // end. The speaker gets the range it can reproduce, and a trim at 31Hz:
    // pushing a tiny driver at frequencies it cannot play only costs
    // excursion and distortion.
    private val BASS_FULL = floatArrayOf(1f, 1f, 0.8f, 0.4f, 0.1f, 0f, 0f, 0f, 0f, 0f)
    private val BASS_SPEAKER = floatArrayOf(-0.3f, 0.2f, 0.65f, 1f, 0.6f, 0.15f, 0f, 0f, 0f, 0f)

    /**
     * What each band actually gets: the listener's EQ (when on) plus the bass
     * shelf for the current output.
     */
    fun effectiveBands(s: SoundSettings, speaker: Boolean): FloatArray {
        val shape = if (speaker) BASS_SPEAKER else BASS_FULL
        val max = if (speaker) BASS_MAX_SPEAKER else BASS_MAX_FULL
        val amount = s.bass.coerceIn(0, 1000) / 1000f
        return FloatArray(BANDS.size) { i ->
            val eq = if (s.eqEnabled) s.bands.getOrElse(i) { 0f } else 0f
            (eq + shape[i] * max * amount).coerceIn(-18f, 18f)
        }
    }

    val PRESETS: Map<String, List<Float>> = linkedMapOf(
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        // Reaches up to 500Hz: below ~150Hz a phone speaker plays nothing, so a
        // bass preset that stopped at 250Hz was silent on the speaker.
        "Bass" to listOf(6f, 6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f),
        "Warm" to listOf(3f, 3f, 2f, 1f, 0f, -1f, -1f, -2f, -2f, -3f),
        "Vocal" to listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f),
        "Bright" to listOf(-1f, -1f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 5f),
        "Loudness" to listOf(5f, 4f, 2f, 0f, -1f, 0f, 0f, 2f, 4f, 5f),
        "Classical" to listOf(3f, 2f, 1f, 0f, 0f, 0f, 0f, 1f, 2f, 3f),
        "Electronic" to listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f)
    )

    private const val PREFS = "sound"

    private val _status = MutableStateFlow(SoundStatus())
    val status: StateFlow<SoundStatus> = _status.asStateFlow()

    private val _settings = MutableStateFlow(SoundSettings())
    val settings: StateFlow<SoundSettings> = _settings.asStateFlow()

    private fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context): SoundSettings {
        val p = prefs(c)
        val bands = p.getString("bands", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == BANDS.size } ?: List(BANDS.size) { 0f }
        return SoundSettings(
            eqEnabled = p.getBoolean("eq", false),
            bands = bands,
            preset = p.getString("preset", "Flat") ?: "Flat",
            preampDb = p.getFloat("preamp", 0f),
            replayGain = runCatching { ReplayGainMode.valueOf(p.getString("rg", "OFF")!!) }.getOrDefault(ReplayGainMode.OFF),
            untaggedDb = p.getFloat("untagged", -6f),
            limiter = p.getBoolean("limiter", true),
            bass = p.getInt("bass", 0),
            spatial = p.getInt("spatial", 0),
            classic = p.getBoolean("classic", false)
        ).also { _settings.value = it }
    }

    fun save(c: Context, s: SoundSettings) {
        _settings.value = s
        prefs(c).edit()
            .putBoolean("eq", s.eqEnabled)
            .putString("bands", s.bands.joinToString(","))
            .putString("preset", s.preset)
            .putFloat("preamp", s.preampDb)
            .putString("rg", s.replayGain.name)
            .putFloat("untagged", s.untaggedDb)
            .putBoolean("limiter", s.limiter)
            .putInt("bass", s.bass)
            .putInt("spatial", s.spatial)
            .putBoolean("classic", s.classic)
            .apply()
    }

    internal fun publish(s: SoundStatus) { _status.value = s }

    fun listen(c: Context, onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs(c).registerOnSharedPreferenceChangeListener(l)
        return l
    }

    fun unlisten(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(c).unregisterOnSharedPreferenceChangeListener(l)
    }

    /**
     * The gain ReplayGain asks for, clamped so a quiet master is never pushed
     * above its own peak (which is the limiter's job to catch, not create).
     */
    fun replayGainFor(tags: FileTags?, s: SoundSettings): Float? {
        if (s.replayGain == ReplayGainMode.OFF) return null
        val gain = when (s.replayGain) {
            ReplayGainMode.ALBUM -> tags?.replayGainAlbum ?: tags?.replayGainTrack
            else -> tags?.replayGainTrack ?: tags?.replayGainAlbum
        } ?: return s.untaggedDb
        val peak = when (s.replayGain) {
            ReplayGainMode.ALBUM -> tags?.peakAlbum ?: tags?.peakTrack
            else -> tags?.peakTrack ?: tags?.peakAlbum
        }
        if (peak != null && peak > 0f && !s.limiter) {
            val headroom = -20f * log10(peak)
            return minOf(gain, headroom)
        }
        return gain
    }
}

/**
 * The effects themselves, owned by the playback service. One instance per
 * audio session; a session change (new ExoPlayer) rebuilds it.
 */
// Virtualizer is deprecated on Android 16 in favour of Spatializer, which is a
// system setting rather than an effect an app can attach. It still works.
@Suppress("DEPRECATION")
class SoundEffects(private val context: Context) {

    private var session = 0
    private var dynamics: Any? = null      // DynamicsProcessing on API 28+
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null    // only when no EQ effect exists at all
    private var virtualizer: android.media.audiofx.Virtualizer? = null
    private var trackGainDb: Float? = null
    private var builtClassic = false
    /** The output decides the bass shape. Set by the service on every route change. */
    var speaker: Boolean = true
        set(value) { if (field != value) { field = value; apply() } }

    fun attach(sessionId: Int) {
        val wantClassic = SoundEngine.settings.value.classic
        if (sessionId == session && sessionId != 0 && wantClassic == builtClassic) { apply(); return }
        release()
        session = sessionId
        if (sessionId == 0) return
        builtClassic = wantClassic
        if (!wantClassic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dynamics = runCatching { buildDynamics(sessionId) }.getOrNull()
        }
        if (dynamics == null) equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        // BassBoost only as a last resort: on the stock bundle it switches
        // itself off on the speaker, which is exactly where people try it.
        if (dynamics == null && equalizer == null) bass = runCatching { BassBoost(0, sessionId) }.getOrNull()
        virtualizer = runCatching { android.media.audiofx.Virtualizer(0, sessionId) }.getOrNull()
        // Another client can take an effect away (a system sound app, say)
        // and hand it back; re-assert our settings when it does.
        val regained = android.media.audiofx.AudioEffect.OnControlStatusChangeListener { _, granted ->
            if (granted) apply()
        }
        listOfNotNull(dynamics as? android.media.audiofx.AudioEffect, equalizer, bass, virtualizer).forEach {
            runCatching { it.setControlStatusListener(regained) }
        }
        apply()
    }

    /** The service calls this when the Sound screen flips the engine. */
    fun rebuildIfEngineChanged() {
        val id = session
        // attach() only reuses the chain when the engine still matches.
        if (id != 0 && SoundEngine.settings.value.classic != builtClassic) attach(id)
    }

    /** ReplayGain for the track that just started; null when it has none. */
    fun setTrackGain(db: Float?) {
        trackGainDb = db
        apply()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun buildDynamics(sessionId: Int): DynamicsProcessing {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            false, 0,
            false, 0,
            true, SoundEngine.BANDS.size,
            true
        ).build()
        return DynamicsProcessing(0, sessionId, cfg)
    }

    fun apply() {
        val s = SoundEngine.settings.value
        val rg = trackGainDb
        val gain = (s.preampDb + (rg ?: 0f)).coerceIn(-24f, 12f)
        val bands = SoundEngine.effectiveBands(s, speaker)
        val shaping = bands.any { abs(it) > 0.01f }
        var dynOk = false
        // Off entirely unless something asks for processing: with nothing
        // enabled the audio passes through untouched. The limiter only guards
        // boosts, so it never switches the chain on by itself.
        val needed = shaping || abs(gain) > 0.01f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (dynamics as? DynamicsProcessing)?.let { dp ->
                dynOk = runCatching {
                    for ((i, f) in SoundEngine.BANDS.withIndex()) {
                        // Post-EQ bands are defined by their upper edge; half an
                        // octave above the centre puts the centre in the middle.
                        val cutoff = (f * sqrt(2f)).coerceAtMost(20000f)
                        dp.setPostEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoff, bands[i]))
                    }
                    dp.setInputGainAllChannelsTo(gain)
                    dp.setLimiterAllChannelsTo(
                        DynamicsProcessing.Limiter(true, s.limiter, 0, 1f, 60f, 10f, -1f, 0f)
                    )
                    dp.setEnabled(needed)
                    true
                }.getOrDefault(false)
            }
        }
        var eqOk = false
        equalizer?.let { eq ->
            eqOk = runCatching {
                val n = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                // The classic effect has no preamp; a positive one would only
                // clip, a negative one lowers every band equally.
                val shift = if (gain < 0f) gain else 0f
                for (b in 0 until n) {
                    val centreHz = eq.getCenterFreq(b.toShort()) / 1000f
                    val g = nearestGain(centreHz, bands) + shift
                    val mb = (g * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(b.toShort(), mb.toShort())
                }
                eq.setEnabled(shaping || shift != 0f)
                true
            }.getOrDefault(false)
        }
        val bassOk = if (dynOk || eqOk) s.bass > 0 else bass?.let { b ->
            runCatching {
                if (b.strengthSupported) b.setStrength(s.bass.coerceIn(0, 1000).toShort())
                b.setEnabled(s.bass > 0)
                true
            }.getOrDefault(false)
        } ?: false
        var spatialActive = false
        val spatialOk = virtualizer?.let { v ->
            runCatching {
                if (v.strengthSupported) v.setStrength(s.spatial.coerceIn(0, 1000).toShort())
                v.setEnabled(s.spatial > 0)
                spatialActive = s.spatial > 0 &&
                    v.virtualizationMode != android.media.audiofx.Virtualizer.VIRTUALIZATION_MODE_OFF
                true
            }.getOrDefault(false)
        } ?: false
        SoundEngine.publish(
            SoundStatus(
                sessionId = session,
                dynamics = dynOk,
                equalizer = dynOk || eqOk,
                bass = bassOk,
                spatial = spatialOk,
                spatialActive = spatialActive,
                speaker = speaker,
                engine = when {
                    dynOk -> "Dynamics"
                    eqOk -> "Classic"
                    else -> ""
                },
                gainDb = if (dynOk) gain else 0f,
                replayGainDb = if (dynOk) rg else null,
                limiterOn = dynOk && s.limiter && needed
            )
        )
    }

    private fun nearestGain(hz: Float, bands: FloatArray): Float {
        var best = 0
        var bestD = Float.MAX_VALUE
        for ((i, f) in SoundEngine.BANDS.withIndex()) {
            val d = abs(log10(hz.coerceAtLeast(1f)) - log10(f))
            if (d < bestD) { bestD = d; best = i }
        }
        return bands.getOrElse(best) { 0f }
    }

    fun release() {
        runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (dynamics as? DynamicsProcessing)?.release() }
        runCatching { equalizer?.release() }
        runCatching { bass?.release() }
        runCatching { virtualizer?.release() }
        dynamics = null; equalizer = null; bass = null; virtualizer = null
        session = 0
    }
}
