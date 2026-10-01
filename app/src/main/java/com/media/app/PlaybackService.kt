package com.media.app

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var effects: SoundEffects? = null
    private var soundListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val main = Handler(Looper.getMainLooper())
    // Tag reads for ReplayGain are file I/O; one thread, in order, so a
    // quick skip through the queue cannot apply an older track's gain last.
    private val tagReads = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        Enrichment.init(this)
        // The saved Sound settings, BEFORE anything is attached. The service
        // used to start on the defaults - EQ off, no bass - and only picked up
        // what was saved once the Sound screen happened to be opened, so after
        // every app start the EQ silently was not there.
        SoundEngine.load(this)
        // FLOAT OUTPUT, for the files that have more than 16 bits to give.
        //
        // The default sink converts everything to 16-bit integer, so a 24-bit
        // FLAC is thrown away at the last step - decoded correctly and then
        // truncated. With float output the sink keeps 24- and 32-bit PCM and
        // float sources intact through to AudioTrack.
        //
        // The trade: when float output is actually IN USE, media3 cannot run
        // its audio processors, so playback speed does not apply. That only
        // happens for high resolution sources - 16-bit MP3, AAC and FLAC take
        // the integer path and keep speed control - which is why this is
        // acceptable: speed belongs to podcasts and audiobooks, and those are
        // not shipped in 24-bit.
        //
        // Decoder fallback: when the first decoder a device offers fails to
        // initialise, try the next one instead of failing the track. Cheap
        // insurance on the odd formats, which is exactly where it happens.
        val renderers = DefaultRenderersFactory(this)
            .setEnableAudioFloatOutput(true)
            .setEnableDecoderFallback(true)
        val player = ExoPlayer.Builder(this, renderers).build()
        // Without this the session uses media3's default loader, which has
        // no fallback: a uri that fails to resolve becomes a blank square.
        mediaSession = MediaSession.Builder(this, player)
            .setBitmapLoader(AuraBitmapLoader(this))
            .build()

        // The Sound chain rides on the player's audio session. A new session
        // id (rare: a device route change can cause one) rebuilds it.
        val fx = SoundEffects(this).also { effects = it }
        // The sound chain is an enhancement: nothing in it may stop the
        // service starting, because the service starts with the app.
        runCatching {
            fx.speaker = onSpeaker()
            fx.attach(player.audioSessionId)
        }
        // Headphones in or out, Bluetooth on or off: the bass shape follows.
        runCatching { audioManager().registerAudioDeviceCallback(routeWatch, main) }
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                runCatching { fx.attach(audioSessionId) }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Video gets the short processing frame so lip-sync holds;
                // music gets the long one for low-frequency precision.
                runCatching {
                    fx.lowLatency = mediaItem?.localConfiguration?.uri?.toString()?.contains("/video/") == true
                }
                loadGain(player, mediaItem)
            }
        })
        soundListener = SoundEngine.listen(this) {
            SoundEngine.load(this)
            runCatching { fx.rebuildIfEngineChanged(); fx.apply() }
            // Switching ReplayGain on, or between track and album, changes
            // the gain for what is already playing.
            loadGain(player, player.currentMediaItem)
        }
        loadGain(player, player.currentMediaItem)
    }

    private fun audioManager() = getSystemService(AUDIO_SERVICE) as android.media.AudioManager

    private fun onSpeaker(): Boolean =
        runCatching { AudioInfo.output(this).kind.let { it == "Phone speaker" || it == "Earpiece" } }
            .getOrDefault(true)

    // A device shows up a moment before media is actually routed to it, so
    // look again shortly after as well.
    private fun recheckRoute() {
        effects?.speaker = onSpeaker()
        main.postDelayed({ effects?.speaker = onSpeaker() }, 700)
    }

    private val routeWatch = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) = recheckRoute()
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) = recheckRoute()
    }

    private fun loadGain(player: Player, item: MediaItem?) {
        val fx = effects ?: return
        val uri = item?.localConfiguration?.uri
        if (uri == null || SoundEngine.settings.value.replayGain == ReplayGainMode.OFF) {
            fx.setTrackGain(null)
            return
        }
        val id = item?.mediaId ?: return
        // runCatching: a transition racing onDestroy must not throw
        // RejectedExecutionException from a shut-down executor.
        runCatching {
            tagReads.execute {
                val tags = readTags(this, uri)
                val gain = SoundEngine.replayGainFor(tags, SoundEngine.settings.value)
                main.post {
                    // Only if that track is still the one playing.
                    if (effects != null && player.currentMediaItem?.mediaId == id) fx.setTrackGain(gain)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        runCatching { audioManager().unregisterAudioDeviceCallback(routeWatch) }
        main.removeCallbacksAndMessages(null)
        soundListener?.let { SoundEngine.unlisten(this, it) }
        soundListener = null
        effects?.release()
        effects = null
        tagReads.shutdownNow()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
