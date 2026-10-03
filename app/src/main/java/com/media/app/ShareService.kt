package com.media.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ============================================================================
//  AURA SHARE - staying alive
//
//  The phone is the server. Once the TV is playing, the file it is reading
//  comes out of this process, so the process has to still be here when the
//  user leaves the app - otherwise the film stops thirty seconds after they
//  switch to WhatsApp, and there is no way to explain that to them.
//
//  mediaPlayback is the right foreground type: Android names casting in its
//  definition, and the permission is already declared for the local player.
//
//  This service owns nothing. It reads ShareSession's state, draws it, and
//  goes away when the session does; the notification's buttons call straight
//  back into the session. A service that kept its own copy of what was
//  playing would be a second source of truth and would eventually disagree.
// ============================================================================

class ShareService : Service() {

    // The language picked in Settings, on Android 12 and below (Language.kt).
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    companion object {
        private const val CHANNEL = "aura_share"
        private const val ID = 0xA124

        const val ACTION_TOGGLE = "com.media.app.share.TOGGLE"
        const val ACTION_NEXT = "com.media.app.share.NEXT"
        const val ACTION_STOP = "com.media.app.share.STOP"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, ShareService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ShareService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // The state collector must not stop the service before startForeground
    // has run: a foreground service that is torn down before it posts its
    // notification is the classic ForegroundServiceDidNotStartInTime crash.
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel()
        scope.launch {
            ShareSession.state.collectLatest { state ->
                if (!started) return@collectLatest
                if (!state.sharing) {
                    ServiceCompat.stopForeground(this@ShareService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notifier()?.notify(ID, build(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> ShareSession.toggle()
            ACTION_NEXT -> ShareSession.next()
            ACTION_STOP -> ShareSession.stop()
        }
        // Must happen on every start, and quickly: a foreground service that
        // does not post its notification in time is killed with an ANR.
        started = true
        ServiceCompat.startForeground(
            this, ID, build(ShareSession.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        if (!ShareSession.state.value.sharing) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // A sharing session that is killed with the task would leave a TV playing
    // a file from a server that no longer exists.
    override fun onTaskRemoved(rootIntent: Intent?) {
        ShareSession.stop()
        super.onTaskRemoved(rootIntent)
    }

    private fun notifier(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notifier()?.getNotificationChannel(CHANNEL)
        if (existing != null) return
        val ch = NotificationChannel(
            CHANNEL, getString(R.string.share_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.share_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        notifier()?.createNotificationChannel(ch)
    }

    private fun action(name: String, icon: Int, label: String): NotificationCompat.Action {
        val intent = Intent(this, ShareService::class.java).setAction(name)
        val pending = PendingIntent.getService(
            this, name.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(icon, label, pending).build()
    }

    private fun build(state: ShareState): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = state.item?.title ?: getString(R.string.share_name)
        val where = state.active?.name ?: ""
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(if (where.isEmpty()) "" else getString(R.string.share_playing_on, where))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                action(
                    ACTION_TOGGLE,
                    if (state.playing) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play,
                    getString(if (state.playing) R.string.action_pause else R.string.action_play)
                )
            )
        if (state.hasNext) {
            builder.addAction(action(ACTION_NEXT, android.R.drawable.ic_media_next, getString(R.string.action_next)))
        }
        builder.addAction(
            action(ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop))
        )
        return builder.build()
    }
}
