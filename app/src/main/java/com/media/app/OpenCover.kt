package com.media.app

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

// ============================================================================
//  THE SPLASH, ON A RETURN TO VIA
//
//  Android draws its splash only when the activity is created. Coming back
//  to a Via that is still running - from the home screen, recents, another
//  app - there is none, so the app-open ad would land on top of the app
//  itself. This is the same splash, drawn by Via: the same ground
//  (splash_ink), the same iris at the size Android draws it (288dp, the
//  size for an icon without a background), and on Android 12 and up the
//  same wordmark 60dp above the bottom edge, where Android puts it. The ad
//  goes up over this; it lifts behind the ad, so closing the ad shows the
//  app (FullScreenAds).
//
//  It appears at once - a fade-in would show the app underneath for its
//  first frames - and fades out. Touches stop here: nothing behind it is
//  reachable while it is up.
// ============================================================================

private val SplashGround = Color(0xFF000000)   // @color/splash_ink

@Composable
fun OpenCover(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = EnterTransition.None,
        exit = fadeOut(tween(Motion.Standard))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(SplashGround)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
        ) {
            Image(
                painterResource(R.drawable.splash_icon), null,
                modifier = Modifier.align(Alignment.Center).size(288.dp)
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Image(
                    painterResource(R.drawable.splash_branding), null,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 60.dp)
                        .size(200.dp, 80.dp)
                )
            }
        }
    }
}
