package com.media.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

// ============================================================================
//  PICTURE-IN-PICTURE
//
//  PiP shrinks the WHOLE activity into the corner window, so the UI has to
//  collapse to just the video surface while it is active - otherwise the user
//  gets a postage stamp of the entire home screen. LocalInPip drives that.
//
//  Aspect ratio comes from the decoded video, clamped to the range Android
//  accepts (0.418..2.39). Outside that range enterPictureInPictureMode throws.
// ============================================================================

object Pip {

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun ratioFor(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        // Android rejects anything outside roughly 1:2.39 .. 2.39:1.
        val r = width.toFloat() / height
        return when {
            r < 0.42f -> Rational(10, 23)
            r > 2.39f -> Rational(239, 100)
            else -> Rational(width, height)
        }
    }

    fun params(width: Int, height: Int, autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(ratioFor(width, height))
            .apply {
                // Android 12+ enters PiP on its own when the user swipes home,
                // which is what people expect FOR VIDEO. It must be turned off
                // again for audio: the flag persists on the activity, so once
                // any video had played, swiping home during a song opened a
                // black PiP window with nothing in it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(autoEnter)
                }
            }
            .build()

    fun enter(activity: Activity, width: Int, height: Int) {
        if (!isSupported(activity)) return
        runCatching { activity.enterPictureInPictureMode(params(width, height, true)) }
    }

    /**
     * Keeps the auto-enter parameters current as the video changes, so a swipe
     * home always uses the right aspect ratio.
     */
    fun update(activity: Activity, width: Int, height: Int, autoEnter: Boolean) {
        if (!isSupported(activity)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching { activity.setPictureInPictureParams(params(width, height, autoEnter)) }
    }
}

/**
 * Holds the screen awake while a video is actually playing.
 *
 * Nothing did this before, so the display would time out mid-video - the one
 * thing a video player must never do. Audio is unaffected: it plays with the
 * screen off by design.
 */
@Composable
fun KeepScreenOnWhileVideo(isVideo: Boolean, isPlaying: Boolean) {
    val view = LocalView.current
    DisposableEffect(isVideo, isPlaying) {
        view.keepScreenOn = isVideo && isPlaying
        onDispose { view.keepScreenOn = false }
    }
}
