package com.media.app

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
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
//  The chain, in signal order:
//      Depth + Space                       (SoundStage, in the player)
//      preamp + ReplayGain -> 10-band EQ -> limiter   (DynamicsProcessing)
//
//  SOUND IS FOR WHAT YOU LISTEN THROUGH. Headphones, Bluetooth, wired and
//  USB audio, speakers and cars each get their own tuning - EQ, Depth, Space
//  and what kind of device it is - remembered by the device's own name, so
//  the over-ears and the car never share a curve. The phone's own speaker
//  plays the music as it is: no EQ, no Depth, no Space. Loudness (preamp,
//  ReplayGain, the limiter) is about the files, not the output, and stays
//  the same everywhere.
//
//  Depth and Space are Via's own processing (SoundStage.kt), run on the
//  samples inside the player (SoundSink.kt), so they work on every phone,
//  every Android version and every format, hi-res included. They replace
//  Android's BassBoost, which switched itself off on most outputs, and its
//  Virtualizer, which most phones never actually run for stereo.
//
//  The EQ, preamp, ReplayGain and limiter are Android's own effect attached
//  to the player's audio session: it runs in the system mixer at the mixer's
//  precision, and with the float output path the service uses for 24-bit
//  files. DynamicsProcessing is Android 9+. Below that the EQ falls back to
//  the older Equalizer effect and preamp/ReplayGain/limiter are reported as
//  unavailable rather than pretended.
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
    // ---- This output device's own tuning ----
    val eqEnabled: Boolean = false,
    val bands: List<Float> = List(SoundEngine.BANDS.size) { 0f },
    val preset: String = "Flat",
    /** 0..1000 */
    val depth: Int = 0,
    /** 0..1000 */
    val space: Int = 0,
    /** What the device is; Depth and Space are shaped for it. */
    val output: OutputClass = OutputClass.HEADPHONES,
    // ---- The same on every output ----
    val preampDb: Float = 0f,
    val replayGain: ReplayGainMode = ReplayGainMode.OFF,
    /** Gain applied to files with no ReplayGain tags, so they are not louder than tagged ones. */
    val untaggedDb: Float = -6f,
    val limiter: Boolean = true,
    /**
     * The classic Equalizer effect instead of DynamicsProcessing. For the
     * odd ROM whose DynamicsProcessing attaches without error and then
     * processes nothing: the listener has a way out instead of a dead EQ.
     */
    val classic: Boolean = false
)

/** Where the sound is going, as Sound sees it. */
data class SoundRoute(
    /** Stable across languages and restarts: what the device is plus its own name. */
    val key: String = "",
    /** For the screen: the device's name, or what it is. */
    val name: String = "",
    /** False for the phone's own speaker (and earpiece): nothing is tuned there. */
    val external: Boolean = false,
    /** What it most likely is, until the listener says otherwise. */
    val guess: OutputClass = OutputClass.HEADPHONES
)

/** What the chain is doing right now, for the Audio Path screen. */
data class SoundStatus(
    val sessionId: Int = 0,
    val dynamics: Boolean = false,
    val equalizer: Boolean = false,
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

    private val FLAT = FloatArray(BANDS.size)

    /** What each band actually gets: the listener's EQ, on an output that is tuned. */
    fun effectiveBands(s: SoundSettings, external: Boolean): FloatArray =
        if (!external || !s.eqEnabled) FLAT
        else FloatArray(BANDS.size) { i -> s.bands.getOrElse(i) { 0f }.coerceIn(-18f, 18f) }

    val PRESETS: Map<String, List<Float>> = linkedMapOf(
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
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

    private val _route = MutableStateFlow(SoundRoute())
    val route: StateFlow<SoundRoute> = _route.asStateFlow()

    private fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** This device's keys. */
    private fun dk(route: SoundRoute, key: String) = "d|${route.key}|$key"

    private fun parseBands(v: String?): List<Float>? =
        v?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == BANDS.size }

    /**
     * Where the sound is going now, and that output's settings. The service
     * calls it on every route change, the Sound screen when it opens.
     */
    fun load(c: Context): SoundSettings = load(c, routeOf(AudioInfo.output(c)))

    private fun load(c: Context, r: SoundRoute): SoundSettings {
        _route.value = r
        val p = prefs(c)
        // A device seen for the first time starts from what the single,
        // pre-5.5 Sound settings were, so nobody's EQ disappears.
        val own = p.contains(dk(r, "eq"))
        fun key(k: String, legacy: String) = if (own) dk(r, k) else legacy
        return SoundSettings(
            eqEnabled = p.getBoolean(key("eq", "eq"), false),
            bands = parseBands(p.getString(key("bands", "bands"), null)) ?: List(BANDS.size) { 0f },
            preset = p.getString(key("preset", "preset"), "Flat") ?: "Flat",
            depth = p.getInt(key("depth", "bass"), 0).coerceIn(0, 1000),
            space = p.getInt(key("space", "spatial"), 0).coerceIn(0, 1000),
            output = p.getString(dk(r, "class"), null)
                ?.let { n -> OutputClass.entries.firstOrNull { it.name == n } } ?: r.guess,
            preampDb = p.getFloat("preamp", 0f),
            replayGain = runCatching { ReplayGainMode.valueOf(p.getString("rg", "OFF")!!) }.getOrDefault(ReplayGainMode.OFF),
            untaggedDb = p.getFloat("untagged", -6f),
            limiter = p.getBoolean("limiter", true),
            classic = p.getBoolean("classic", false)
        ).also { _settings.value = it }
    }

    /** The saved settings again, for the output already known: no route lookup. */
    fun reload(c: Context): SoundSettings = load(c, _route.value)

    fun save(c: Context, s: SoundSettings) {
        _settings.value = s
        val r = _route.value
        val e = prefs(c).edit()
            .putFloat("preamp", s.preampDb)
            .putString("rg", s.replayGain.name)
            .putFloat("untagged", s.untaggedDb)
            .putBoolean("limiter", s.limiter)
            .putBoolean("classic", s.classic)
        // The phone speaker has no tuning to keep.
        if (r.external) {
            e.putBoolean(dk(r, "eq"), s.eqEnabled)
                .putString(dk(r, "bands"), s.bands.joinToString(","))
                .putString(dk(r, "preset"), s.preset)
                .putInt(dk(r, "depth"), s.depth)
                .putInt(dk(r, "space"), s.space)
                .putString(dk(r, "class"), s.output.name)
        }
        e.apply()
    }

    /** What the stage in the player should do for this output. */
    fun stageParams(): StageParams {
        val s = _settings.value
        return StageParams(
            on = _route.value.external,
            depth = s.depth / 1000f,
            space = s.space / 1000f,
            output = s.output
        )
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

    // Outputs that are not tuned: the phone itself.
    private val PHONE_TYPES = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_TELEPHONY, 24 /* TYPE_BUILTIN_SPEAKER_SAFE */,
        25 /* TYPE_REMOTE_SUBMIX */
    )
    private val SPEAKER_TYPES = setOf(
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_AUX_LINE,
        27 /* BLE_SPEAKER */, 29 /* HDMI_EARC */, 30 /* BLE_BROADCAST */, 31 /* DOCK_ANALOG */
    )

    // Words in a Bluetooth device's name that say what it is. Matched as
    // whole words, so "Seattle" is not a SEAT.
    private val CAR_WORDS = setOf(
        "car", "auto", "sync", "uconnect", "mylink", "entune", "mbux", "idrive", "toyota", "honda", "ford",
        "chevrolet", "chevy", "nissan", "hyundai", "kia", "mazda", "subaru", "volkswagen", "vw", "bmw", "audi",
        "mercedes", "lexus", "jeep", "dodge", "tesla", "volvo", "peugeot", "renault", "skoda", "fiat", "opel",
        "suzuki", "mitsubishi", "infiniti", "acura", "buick", "gmc", "cadillac", "lincoln", "porsche",
        "citroen", "byd", "pioneer", "kenwood", "alpine"
    )
    private val SPEAKER_WORDS = setOf(
        "speaker", "soundlink", "flip", "charge", "xtreme", "boombox", "partybox", "pulse", "clip", "boom",
        "megaboom", "wonderboom", "everboom", "sonos", "roam", "soundbar", "bar", "echo", "homepod", "nest",
        "tv", "dock", "stanmore", "acton", "woburn", "emberton", "srs", "beosound", "beolit", "party"
    )

    /** What an output most likely is, from its type and its own name. */
    fun guessClass(type: Int?, product: String?): OutputClass {
        if (type == AudioDeviceInfo.TYPE_BUS) return OutputClass.CAR
        if (type != null && type in SPEAKER_TYPES) return OutputClass.SPEAKER
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && product != null) {
            val words = product.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
            if (words.any { it in CAR_WORDS }) return OutputClass.CAR
            if (words.any { it in SPEAKER_WORDS } || ("jbl" in words && "go" in words)) return OutputClass.SPEAKER
        }
        return OutputClass.HEADPHONES
    }

    fun routeOf(o: OutputRoute): SoundRoute {
        val type = o.type
        val external = type != null && type !in PHONE_TYPES
        return SoundRoute(
            key = "${o.kind}|${o.product.orEmpty()}",
            name = o.name,
            external = external,
            guess = guessClass(type, o.product)
        )
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
class SoundEffects(private val context: Context) {

    private var session = 0
    private var dynamics: Any? = null      // DynamicsProcessing on API 28+
    private var dpCentres: FloatArray = FloatArray(0)
    private var dpFrameMs = 0f
    private val sent = HashMap<Int, Float>()
    private var equalizer: Equalizer? = null
    private var trackGainDb: Float? = null
    private var builtClassic = false
    private var builtLowLatency = false

    /** Headphones, a speaker, a car: the EQ applies. The phone's own speaker: it does not. */
    var external: Boolean = false
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
        // Another client can take an effect away (a system sound app, say)
        // and hand it back; re-assert every setting when it does.
        val regained = android.media.audiofx.AudioEffect.OnControlStatusChangeListener { _, granted ->
            if (granted) { sent.clear(); apply() }
        }
        listOfNotNull(dynamics as? android.media.audiofx.AudioEffect, equalizer).forEach {
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
        // The ten control points: the listener's EQ for this output (flat on
        // the phone speaker). Everything below samples the smooth curve
        // through them.
        val points = SoundEngine.effectiveBands(s, external)
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
        SoundEngine.publish(
            SoundStatus(
                sessionId = session,
                dynamics = dynOk,
                equalizer = dynOk || eqOk,
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
        dynamics = null; equalizer = null
        dpCentres = FloatArray(0)
        sent.clear()
        session = 0
    }
}
