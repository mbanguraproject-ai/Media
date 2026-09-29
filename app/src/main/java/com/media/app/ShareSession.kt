package com.media.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ============================================================================
//  AURA SHARE - the session
//
//  A singleton, not something a screen owns. Playback on a TV outlives the
//  sheet that started it, outlives Home, and has to outlive the Activity: the
//  server serving the file and the loop watching the renderer both belong to
//  the app, and a foreground service keeps the process alive around them.
//
//  One StateFlow for everything. The sheet collects it, the notification
//  collects it, and there is exactly one description of what is happening -
//  which is how the pill, the header icon and the notification stay honest
//  with each other without any of them talking to the others.
//
//  Every public method returns immediately and does its work on the session's
//  own scope. Nothing here may run on the UI thread: each of these is a
//  blocking round trip to a device that may have been unplugged.
// ============================================================================

data class ShareState(
    val devices: List<Renderer> = emptyList(),
    val scanning: Boolean = false,
    val active: Renderer? = null,
    val queue: List<AppMediaItem> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int = -1,            // -1: this renderer has no volume service
    val host: String? = null,
    val note: String? = null
) {
    val item: AppMediaItem? get() = queue.getOrNull(index)
    val sharing: Boolean get() = active != null
    val hasNext: Boolean get() = index + 1 in queue.indices
    val hasPrevious: Boolean get() = index - 1 in queue.indices
}

object ShareSession {

    // IO, not Default: every call in here is a blocking socket round trip
    // to a device that may be unplugged, and Default's thread count is
    // sized for CPU work.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state.asStateFlow()

    private var app: Context? = null
    private var server: ShareServer? = null
    private var poller: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var watcher: ConnectivityManager.NetworkCallback? = null

    fun attach(context: Context) {
        if (app == null) {
            app = context.applicationContext
            server = ShareServer(context.applicationContext)
        }
    }

    // ------------------------------------------------------------ DISCOVERY
    fun scan() {
        val ctx = app ?: return
        scope.launch {
            _state.update { it.copy(scanning = true, note = null) }
            // Someone on mobile data has no local network for a TV to be on.
            // Saying "nothing answered" to them is a lie of omission.
            if (AuraShare.lanNetwork(ctx) == null) {
                _state.update {
                    it.copy(
                        scanning = false, devices = emptyList(), host = null,
                        note = "Aura Share needs Wi-Fi. This phone is on mobile data, " +
                            "which has no local network for a TV to be on."
                    )
                }
                return@launch
            }
            val found = AuraShare.discover(ctx)
            val host = AuraShare.localAddress(ctx)
            _state.update {
                it.copy(
                    scanning = false, devices = found, host = host,
                    note = if (found.isNotEmpty()) null else
                        "Nothing answered on this network" +
                            (host?.let { h -> " (this phone is $h)" } ?: "") +
                            ". The TV has to be on the same Wi-Fi with its media sharing " +
                            "turned on - it is called DLNA, Screen Share or AllShare " +
                            "depending on the brand."
                )
            }
        }
    }

    // -------------------------------------------------------------- CONTROL
    fun start(renderer: Renderer, items: List<AppMediaItem>, at: Int) {
        val ctx = app ?: return
        scope.launch {
            val host = AuraShare.localAddress(ctx)
            if (host == null) {
                _state.update { it.copy(note = "This phone has no address on the network right now.") }
                return@launch
            }
            val start = at.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            val media = items.getOrNull(start) ?: return@launch
            if (!load(renderer, media, host)) {
                server?.stop()
                _state.update {
                    it.copy(
                        note = "${renderer.name} would not take that file. Some devices " +
                            "refuse formats they cannot decode - MKV and HEVC are the two " +
                            "that usually fail."
                    )
                }
                return@launch
            }
            hold(ctx)
            watch(ctx)
            _state.update {
                it.copy(
                    active = renderer, queue = items, index = start,
                    playing = true, positionMs = 0L,
                    durationMs = media.durationMs, note = null,
                    volume = AuraShare.volume(renderer)
                )
            }
            ShareService.start(ctx)
            poll()
        }
    }

    fun toggle() = scope.launch {
        val s = _state.value
        val r = s.active ?: return@launch
        val ok = if (s.playing) AuraShare.pause(r) else AuraShare.resume(r)
        if (ok) _state.update { it.copy(playing = !s.playing) }
    }

    fun next() = scope.launch { advance(1, auto = false) }
    fun previous() = scope.launch { advance(-1, auto = false) }

    fun seek(fraction: Float) = scope.launch {
        val s = _state.value
        val r = s.active ?: return@launch
        val total = if (s.durationMs > 0) s.durationMs else s.item?.durationMs ?: 0L
        if (total <= 0L) return@launch
        val ms = (fraction.coerceIn(0f, 1f) * total).toLong()
        _state.update { it.copy(positionMs = ms) }
        AuraShare.seek(r, ms)
    }

    fun setVolume(value: Int) = scope.launch {
        val r = _state.value.active ?: return@launch
        if (AuraShare.setVolume(r, value)) _state.update { it.copy(volume = value.coerceIn(0, 100)) }
    }

    fun stop(reason: String? = null) {
        val ctx = app
        val r = _state.value.active
        poller?.cancel()
        poller = null
        _state.update {
            it.copy(
                active = null, queue = emptyList(), index = 0, playing = false,
                positionMs = 0L, durationMs = 0L, volume = -1, note = reason
            )
        }
        release()
        unwatch(ctx)
        if (ctx != null) ShareService.stop(ctx)
        scope.launch {
            if (r != null) AuraShare.stop(r)
            server?.stop()
        }
    }

    // --------------------------------------------------------------- GUTS
    private suspend fun load(r: Renderer, media: AppMediaItem, host: String): Boolean {
        val url = server?.serve(media, host) ?: return false
        if (AuraShare.play(r, url, media.title, media.mimeType, media.durationMs)) return true
        // Some renderers accept the URI and are not ready to be told Play in
        // the same breath. One retry, then it is a real refusal.
        delay(400)
        return AuraShare.play(r, url, media.title, media.mimeType, media.durationMs)
    }

    private suspend fun advance(delta: Int, auto: Boolean) {
        val s = _state.value
        val r = s.active ?: return
        val ctx = app ?: return
        val target = s.index + delta
        if (target !in s.queue.indices) {
            // Falling off the end of the queue is the end of the session; a
            // manual Next at the end is simply nothing.
            if (auto) stop()
            return
        }
        val media = s.queue[target]
        val host = AuraShare.localAddress(ctx) ?: return
        _state.update {
            it.copy(index = target, positionMs = 0L, durationMs = media.durationMs, playing = true)
        }
        if (!load(r, media, host)) stop("${r.name} could not play ${media.title}.")
    }

    /**
     * The renderer is the source of truth, asked once a second. It is the only
     * feedback UPnP gives and it is reported in whole seconds, which is plenty
     * for a progress line and is exactly why picture and sound cannot be split
     * across two devices.
     */
    private fun poll() {
        poller?.cancel()
        poller = scope.launch {
            var everPlayed = false
            while (isActive) {
                val r = _state.value.active ?: break
                val pos = AuraShare.position(r)
                val dur = AuraShare.duration(r)
                val transport = AuraShare.transportState(r)
                if (transport == "PLAYING") everPlayed = true
                _state.update {
                    it.copy(
                        positionMs = if (pos >= 0) pos else it.positionMs,
                        durationMs = if (dur > 0) dur else it.durationMs,
                        playing = when (transport) {
                            "PLAYING" -> true
                            "PAUSED_PLAYBACK" -> false
                            else -> it.playing
                        }
                    )
                }
                // STOPPED after having played is how a renderer says "finished".
                // There is no end-of-item event in AVTransport worth trusting.
                if (everPlayed && transport == "STOPPED") {
                    everPlayed = false
                    advance(1, auto = true)
                }
                delay(1000)
            }
        }
    }

    // ---------------------------------------------------------------- LOCKS
    //
    // A foreground service keeps the PROCESS alive. It does not keep the CPU
    // awake with the screen off, and it does not stop Wi-Fi dropping into a
    // power-saving mode that turns a film into a slideshow. Both locks are
    // taken for the duration and released in one place.
    @Suppress("DEPRECATION")   // WIFI_MODE_FULL_HIGH_PERF, for API 24-28 only
    private fun hold(ctx: Context) {
        release()
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm?.createWifiLock(mode, "aura-share")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aura:share")?.apply {
            setReferenceCounted(false)
            // Timed, because a wake lock with no timeout is a battery bug
            // waiting for one crash to happen. Four hours outlasts any film.
            runCatching { acquire(4 * 60 * 60 * 1000L) }
        }
    }

    private fun release() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
    }

    // Wi-Fi going away mid-film should say so, not leave a frozen progress bar
    // and a TV showing a spinner.
    private fun watch(ctx: Context) {
        unwatch(ctx)
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (_state.value.sharing) stop("Wi-Fi dropped, so sharing stopped.")
            }
        }
        watcher = cb
        runCatching { cm.registerNetworkCallback(request, cb) }
    }

    private fun unwatch(ctx: Context?) {
        val cm = ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        watcher?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        watcher = null
    }
}
