package com.media.app

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ============================================================================
//  IN-APP UPDATES
//
//  Google Play's In-App Updates. When the app opens and Play has a newer
//  version of Via, Play's own sheet offers it. Accepted, it downloads in the
//  background while the music keeps playing, and a banner says when it is
//  ready; Restart installs it. The restart is always the listener's tap:
//  installing stops playback for a moment, so it never happens by itself in
//  the middle of a song.
//
//  FLEXIBLE, NOT BLOCKING. A music player that covers the screen with "you
//  must update" for an ordinary release stops your music to talk about
//  itself. Every update is offered the flexible way. Only one published with
//  priority 4 or 5 takes the immediate, full-screen flow - that is for a fix
//  that must not wait. (Priority is set through the Play Developer API when
//  a release is published; Play Console has no field for it, so an ordinary
//  release is priority 0 and flexible.)
//
//  NOT A NAG. Offered once per app open. "No thanks" holds that version back
//  for three days; a newer version is offered straight away.
//
//  ONLY FROM PLAY. Play answers only for an install it made. A debug build or
//  a sideloaded APK gets an error back, and nothing is shown.
// ============================================================================

/** What the update banner shows. */
enum class UpdateStage { NONE, READY }

internal enum class UpdateFlow { NONE, FLEXIBLE, IMMEDIATE }

/** Days a declined version waits before it is offered again. */
internal const val UPDATE_DECLINE_DAYS = 3L
/** Priority from which an update is urgent and takes the full-screen flow. */
internal const val UPDATE_URGENT_PRIORITY = 4
private const val DAY_MS = 86_400_000L

/**
 * Which flow, if any, to offer for an update Play says is available. Pure,
 * so the rules can be checked off the device.
 */
internal fun chooseUpdateFlow(
    availableVersion: Int,
    priority: Int,
    flexibleAllowed: Boolean,
    immediateAllowed: Boolean,
    declinedVersion: Int,
    declinedAt: Long,
    now: Long
): UpdateFlow = when {
    // An urgent fix: full screen, whatever was declined before.
    priority >= UPDATE_URGENT_PRIORITY && immediateAllowed -> UpdateFlow.IMMEDIATE
    // "No thanks" to this very version, recently. A clock set back counts
    // as long ago, so it cannot hide an update for good.
    availableVersion == declinedVersion && now - declinedAt in 0 until UPDATE_DECLINE_DAYS * DAY_MS -> UpdateFlow.NONE
    flexibleAllowed -> UpdateFlow.FLEXIBLE
    // Play allows only the full-screen flow for an ordinary update: not
    // offered. The next app open asks again.
    else -> UpdateFlow.NONE
}

object InAppUpdate {
    private const val PREFS = "update"
    private const val KEY_DECLINED_VERSION = "declinedVersion"
    private const val KEY_DECLINED_AT = "declinedAt"
    private const val KEY_IMMEDIATE_VERSION = "immediateVersion"

    private val _stage = MutableStateFlow(UpdateStage.NONE)
    /** READY once a downloaded update waits for Restart. */
    val stage: StateFlow<UpdateStage> = _stage

    private var manager: AppUpdateManager? = null
    private var listening = false
    /** An update has been offered in this run of the app: once per open. */
    private var offered = false
    /** "Not now" on the banner: hidden until the app next opens. */
    private var dismissed = false
    /** The version Play's sheet is open for, so a "No thanks" can be remembered. */
    private var pendingVersion = 0

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // The application context's manager: it outlives every screen, so the
    // listener below can stay registered for the life of the app without
    // holding on to an activity.
    private fun manager(c: Context): AppUpdateManager? = manager
        ?: runCatching { AppUpdateManagerFactory.create(c.applicationContext) }.getOrNull()?.also { manager = it }

    private val listener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> if (!dismissed) _stage.value = UpdateStage.READY
            InstallStatus.INSTALLED, InstallStatus.FAILED, InstallStatus.CANCELED -> _stage.value = UpdateStage.NONE
            else -> Unit
        }
    }

    /**
     * Every time the app comes to the front. Shows the banner for an update
     * that finished downloading while the app was away, resumes an urgent
     * update Play is still installing, and - once per app open - offers a
     * new version.
     */
    fun check(activity: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val m = manager(activity) ?: return
        if (!listening) {
            runCatching { m.registerListener(listener) }.onSuccess { listening = true }
        }
        val task = runCatching { m.appUpdateInfo }.getOrNull() ?: return
        task.addOnSuccessListener { info ->
            if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
            when {
                info.installStatus() == InstallStatus.DOWNLOADED -> {
                    if (!dismissed) _stage.value = UpdateStage.READY
                }
                // Only an urgent update this app started is resumed full
                // screen; a flexible download in progress reports the same
                // availability and must stay in the background.
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
                    info.availableVersionCode() == prefs(activity).getInt(KEY_IMMEDIATE_VERSION, -1) ->
                    start(m, info, AppUpdateType.IMMEDIATE, launcher)
                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && !offered -> {
                    offered = true
                    offer(activity, m, info, launcher)
                }
            }
        }
        // A debug or sideloaded build, no Play, no network: nothing to say.
        task.addOnFailureListener { }
    }

    private fun offer(
        activity: Activity,
        m: AppUpdateManager,
        info: AppUpdateInfo,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        val p = prefs(activity)
        val flow = chooseUpdateFlow(
            availableVersion = info.availableVersionCode(),
            priority = info.updatePriority(),
            flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
            immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
            declinedVersion = p.getInt(KEY_DECLINED_VERSION, 0),
            declinedAt = p.getLong(KEY_DECLINED_AT, 0L),
            now = System.currentTimeMillis()
        )
        when (flow) {
            UpdateFlow.FLEXIBLE -> start(m, info, AppUpdateType.FLEXIBLE, launcher)
            UpdateFlow.IMMEDIATE -> {
                p.edit().putInt(KEY_IMMEDIATE_VERSION, info.availableVersionCode()).apply()
                start(m, info, AppUpdateType.IMMEDIATE, launcher)
            }
            UpdateFlow.NONE -> Unit
        }
    }

    private fun start(
        m: AppUpdateManager,
        info: AppUpdateInfo,
        type: Int,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        pendingVersion = info.availableVersionCode()
        runCatching { m.startUpdateFlowForResult(info, launcher, AppUpdateOptions.defaultOptions(type)) }
    }

    /** What the listener chose in Play's sheet. */
    fun onFlowResult(context: Context, resultCode: Int) {
        if (resultCode == Activity.RESULT_CANCELED && pendingVersion != 0) {
            prefs(context).edit()
                .putInt(KEY_DECLINED_VERSION, pendingVersion)
                .putLong(KEY_DECLINED_AT, System.currentTimeMillis())
                .apply()
        }
        pendingVersion = 0
    }

    /** Installs the downloaded update; Play restarts the app into it. */
    fun restart(context: Context) {
        val m = manager(context) ?: return
        runCatching { m.completeUpdate() }
    }

    /** "Not now": the banner goes until the app next opens. */
    fun later() {
        dismissed = true
        _stage.value = UpdateStage.NONE
    }
}
