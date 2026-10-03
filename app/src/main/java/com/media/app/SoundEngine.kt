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
import kotlin.math.roundToInt
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

// Stored by name; the label is a string resource in the app's language.
enum class ReplayGainMode(@androidx.annotation.StringRes val label: Int) {
    OFF(R.string.rg_off), TRACK(R.string.rg_mode_track), ALBUM(R.string.rg_mode_album)
}

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
    /** How many bands the curve is rendered at right now. */
    val eqBands: Int = 0,
    /** Dynamics Processing frame length; longer = finer low-frequency resolution. */
    val frameMs: Float = 0f,
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
 *
 * PRECISION. Dynamics Processing equalises in the frequency domain, one FFT
 * frame at a time, and its frequency resolution is the sample rate divided by
 * the frame size. At the default 10ms frame that is roughly 94Hz per bin at
 * 48kHz, so 31Hz and 62Hz landed in the same one or two bins: the two lowest
 * sliders moved the same sound, and the bass shelf smeared. Music now runs
 * 40ms frames (~23Hz per bin), which separates the low bands properly.
 * Longer frames add latency, which audio alone does not notice but a video's
 * lip-sync does, so a video gets the short frame and the engine is rebuilt at
 * the boundary. The curve is rendered at 31 bands (1/3 octave) from the
 * smooth EqCurve, and only bands that changed are sent to the audio server,
 * so dragging a slider stays light.
 *
 * If this phone's Dynamics Processing refuses 31 bands or a 40ms frame, the
 * engine falls back to 10 bands at the default frame, then to the classic
 * Equalizer - never to nothing.
 */
// Virtualizer is deprecated on Android 16 in favour of Spatializer, which is a
// system setting rather than an effect an app can attach. It still works.
@Suppress("DEPRECATION")
class SoundEffects(private val context: Context) {

    private var session = 0
    private var dynamics: Any? = null      // DynamicsProcessing on API 28+
    private var dpCentres: FloatArray = FloatArray(0)
    private var dpFrameMs = 0f
    private val sent = HashMap<Int, Float>()
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null    // only when no EQ effect exists at all
    private var virtualizer: android.media.audiofx.Virtualizer? = null
    private var trackGainDb: Float? = null
    private var builtClassic = false
    private var builtLowLatency = false

    /** The output decides the bass shape. Set by the service on every route change. */
    var speaker: Boolean = true
        set(value) { if (field != value) { field = value; apply() } }

    /** True while a video plays: short frames, so the picture and sound stay in sync. */
    var lowLatency: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            val id = session
            if (id != 0 && dynamics != null && value != builtLowLatency) attach(id, force = true)
        }

    fun attach(sessionId: Int, force: Boolean = false) {
        val wantClassic = SoundEngine.settings.value.classic
        if (!force && sessionId == session && sessionId != 0 && wantClassic == builtClassic) { apply(); return }
        release()
        session = sessionId
        if (sessionId == 0) return
        builtClassic = wantClassic
        builtLowLatency = lowLatency
        if (!wantClassic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val frame = if (lowLatency) 10f else 40f
            dynamics = runCatching { buildDynamics(sessionId, EqCurve.THIRD_OCTAVE, frame) }
                .onSuccess { dpCentres = EqCurve.THIRD_OCTAVE; dpFrameMs = frame }
                .recoverCatching {
                    buildDynamics(sessionId, SoundEngine.BANDS, 10f)
                        .also { dpCentres = SoundEngine.BANDS; dpFrameMs = 10f }
                }
                .getOrNull()
        }
        if (dynamics == null) equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        // BassBoost only as a last resort: on the stock bundle it switches
        // itself off on the speaker, which is exactly where people try it.
        if (dynamics == null && equalizer == null) bass = runCatching { BassBoost(0, sessionId) }.getOrNull()
        virtualizer = runCatching { android.media.audiofx.Virtualizer(0, sessionId) }.getOrNull()
        // Another client can take an effect away (a system sound app, say)
        // and hand it back; re-assert every setting when it does.
        val regained = android.media.audiofx.AudioEffect.OnControlStatusChangeListener { _, granted ->
            if (granted) { sent.clear(); apply() }
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
    private fun buildDynamics(sessionId: Int, centres: FloatArray, frameMs: Float): DynamicsProcessing {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            false, 0,
            false, 0,
            true, centres.size,
            true
        ).setPreferredFrameDuration(frameMs).build()
        return DynamicsProcessing(0, sessionId, cfg)
    }

    fun apply() {
        val s = SoundEngine.settings.value
        val rg = trackGainDb
        val gain = (s.preampDb + (rg ?: 0f)).coerceIn(-24f, 12f)
        // The ten control points: the listener's EQ plus the bass shelf for
        // this output. Everything below samples the smooth curve through them.
        val points = SoundEngine.effectiveBands(s, speaker)
        val shaping = points.any { abs(it) > 0.01f }
        var dynOk = false
        // Off entirely unless something asks for processing: with nothing
        // enabled the audio passes through untouched. The limiter only guards
        // boosts, so it never switches the chain on by itself.
        val needed = shaping || abs(gain) > 0.01f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (dynamics as? DynamicsProcessing)?.let { dp ->
                dynOk = runCatching {
                    for ((i, f) in dpCentres.withIndex()) {
                        val g = (EqCurve.at(f, SoundEngine.BANDS, points) * 10f).roundToInt() / 10f
                        if (sent[i] == g) continue
                        // Each band is defined by its upper edge, a sixth of
                        // an octave (or, on the 10-band fallback, half an
                        // octave) above its centre.
                        val edge = if (dpCentres.size == SoundEngine.BANDS.size)
                            (f * sqrt(2f)).coerceAtMost(20000f) else EqCurve.upperEdge(f)
                        dp.setPostEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, edge, g))
                        sent[i] = g
                    }
                    dp.setInputGainAllChannelsTo(gain)
                    // A near-brickwall ceiling at -1dBFS: 20:1 holds even a
                    // +12dB boost under full scale, with a 1ms attack so the
                    // first transient of a hit is caught, not just the tail.
                    dp.setLimiterAllChannelsTo(
                        DynamicsProcessing.Limiter(true, s.limiter, 0, 1f, 60f, 20f, -1f, 0f)
                    )
                    dp.setEnabled(needed)
                    true
                }.getOrElse { sent.clear(); false }
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
                    // The curve sampled at this phone's own band centre, not
                    // the nearest slider: a 230Hz band gets what the curve says
                    // at 230Hz.
                    val centreHz = eq.getCenterFreq(b.toShort()) / 1000f
                    val g = EqCurve.at(centreHz, SoundEngine.BANDS, points) + shift
                    val mb = (g * 100).roundToInt().coerceIn(range[0].toInt(), range[1].toInt())
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
                eqBands = when {
                    dynOk -> dpCentres.size
                    eqOk -> runCatching { equalizer?.numberOfBands?.toInt() ?: 0 }.getOrDefault(0)
                    else -> 0
                },
                frameMs = if (dynOk) dpFrameMs else 0f,
                gainDb = if (dynOk) gain else 0f,
                replayGainDb = if (dynOk) rg else null,
                limiterOn = dynOk && s.limiter && needed
            )
        )
    }

    fun release() {
        runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (dynamics as? DynamicsProcessing)?.release() }
        runCatching { equalizer?.release() }
        runCatching { bass?.release() }
        runCatching { virtualizer?.release() }
        dynamics = null; equalizer = null; bass = null; virtualizer = null
        dpCentres = FloatArray(0)
        sent.clear()
        session = 0
    }
}
