package com.media.app

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
