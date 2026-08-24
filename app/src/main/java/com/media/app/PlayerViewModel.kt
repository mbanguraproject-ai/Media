package com.media.app

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerState(
    val currentTitle: String = "",
    val currentArtist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasItem: Boolean = false,
    val currentUri: String? = null,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,   // Media3: 0=OFF, 1=ONE, 2=ALL
    val speed: Float = 1.0f,
    val isVideo: Boolean = false,
    val sleepActive: Boolean = false,
    val sleepRemainingMs: Long = 0L,
    val sleepEndOfTrack: Boolean = false,
    val queueIndex: Int = 0,
    val queueSize: Int = 0
)

// ============================================================================
//  PLAYBACK ERRORS (§22)
//  Errors are translated into something a person can act on. Never
//  "ERROR_CODE_IO_FILE_NOT_FOUND" — that is the shape §22 explicitly rejects.
// ============================================================================
enum class ErrorKind { MISSING, FORMAT, PERMISSION, GENERIC }

data class PlaybackError(
    val kind: ErrorKind,
    val headline: String,
    val detail: String,
    val trackTitle: String,
    val uri: String?
)

internal fun playbackErrorFrom(e: PlaybackException, title: String, uri: String?): PlaybackError =
    when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            PlaybackError(
                ErrorKind.MISSING, "This file is no longer available",
                "It may have been moved, renamed, or deleted since your library was scanned.",
                title, uri
            )
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            PlaybackError(
                ErrorKind.PERMISSION, "Media couldn't open this file",
                "Access to this file was denied. Check the app's media permission.",
                title, uri
            )
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            PlaybackError(
                ErrorKind.FORMAT, "Couldn't play this track",
                "This device can't decode the file, or it's damaged.",
                title, uri
            )
        else ->
            PlaybackError(
                ErrorKind.GENERIC, "Couldn't play this track",
                "Something went wrong starting playback.",
                title, uri
            )
    }

/** One row of the Media3 timeline, flattened for the queue sheet (§25). */
data class QueueEntry(
    val mediaId: Long,
    val title: String,
    val artist: String,
    val uri: String
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private var controller: MediaController? = null
    // Sleep timer: countdown = absolute deadline (robust to tick jitter);
    // end-of-track = flag watched in the position loop. Fade runs once.
    private var sleepDeadlineMs: Long? = null
    private var sleepEndOfTrack = false
    private var sleepFiring = false
    // Resume-position support
    private val db = OverrideDatabase.get(app)
    private var pillarById: Map<Long, Pillar> = emptyMap()
    private var lastPositionSaveMs = 0L

    // Set by the UI: called with the mediaId once it has played 5s (dedup handled by caller/DB).
    var onQualifyingPlay: ((Long) -> Unit)? = null
    private var recordedForCurrent = false

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveCurrentPosition()   // capture position on pause
            refresh()
        }
        override fun onMediaItemTransition(mediaItem: ExoMediaItem?, reason: Int) {
            saveCurrentPosition()                    // capture position before the item changes
            recordedForCurrent = false
            refresh()
        }
        override fun onPlaybackStateChanged(playbackState: Int) = refresh()

        // §22: nothing used to handle this — a deleted file failed silently.
        override fun onPlayerError(error: PlaybackException) {
            val c = controller
            _error.value = playbackErrorFrom(
                error,
                c?.mediaMetadata?.title?.toString() ?: "this track",
                c?.currentMediaItem?.localConfiguration?.uri?.toString()
            )
            refresh()
        }

        // Timeline edits (add / move / remove) land here.
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            refreshQueue(); refresh()
        }
    }

    init {
        val token = SessionToken(
            app,
            ComponentName(app, PlaybackService::class.java)
        )
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            controller = future.get().also { it.addListener(listener) }
            refreshQueue()
            refresh()
            startPositionUpdates()
        }, MoreExecutors.directExecutor())
    }

    private fun startPositionUpdates() {
        viewModelScope.launch {
            while (true) {
                controller?.let {
                    if (it.isPlaying) {
                        _state.value = _state.value.copy(
                            positionMs = it.currentPosition,
                            durationMs = it.duration.coerceAtLeast(0L)
                        )
                        if (!recordedForCurrent && it.currentPosition >= 5000L) {
                            val id = it.currentMediaItem?.localConfiguration?.uri?.lastPathSegment?.toLongOrNull()
                            if (id != null) {
                                recordedForCurrent = true
                                onQualifyingPlay?.invoke(id)
                            }
                        }
                    }
                }
                tickSleepTimer()
                // Throttled resume-position save (long-form only, ~every 5s).
                controller?.let {
                    if (it.isPlaying) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastPositionSaveMs >= 5000L) {
                            lastPositionSaveMs = now
                            saveCurrentPosition()
                        }
                    }
                }
                delay(500)
            }
        }
    }

    // ---- Sleep timer ----

    fun startSleepTimer(minutes: Int) {
        sleepEndOfTrack = false
        sleepDeadlineMs = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
        sleepFiring = false
        _state.value = _state.value.copy(sleepActive = true, sleepEndOfTrack = false, sleepRemainingMs = minutes * 60_000L)
    }

    fun startSleepEndOfTrack() {
        sleepDeadlineMs = null
        sleepEndOfTrack = true
        sleepFiring = false
        _state.value = _state.value.copy(sleepActive = true, sleepEndOfTrack = true, sleepRemainingMs = 0L)
    }

    fun cancelSleepTimer() {
        sleepDeadlineMs = null
        sleepEndOfTrack = false
        sleepFiring = false
        controller?.volume = 1f
        _state.value = _state.value.copy(sleepActive = false, sleepEndOfTrack = false, sleepRemainingMs = 0L)
    }

    private fun tickSleepTimer() {
        if (sleepFiring) return
        val c = controller ?: return
        val deadline = sleepDeadlineMs
        if (deadline != null) {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0L) { fireSleepTimer(); return }
            _state.value = _state.value.copy(sleepRemainingMs = remaining)
        } else if (sleepEndOfTrack) {
            val dur = c.duration
            if (dur > 0 && c.currentPosition >= dur - 10_000L) fireSleepTimer()
        }
    }

    private fun fireSleepTimer() {
        if (sleepFiring) return
        sleepFiring = true
        sleepDeadlineMs = null
        sleepEndOfTrack = false
        viewModelScope.launch {
            val c = controller
            if (c != null) {
                val steps = 20
                for (i in 1..steps) {
                    if (!sleepFiring) return@launch
                    c.volume = (1f - i.toFloat() / steps).coerceAtLeast(0f)
                    delay(500)
                }
                c.pause()
                c.volume = 1f
            }
            sleepFiring = false
            _state.value = _state.value.copy(sleepActive = false, sleepEndOfTrack = false, sleepRemainingMs = 0L)
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val md = c.mediaMetadata
        val prev = _state.value   // preserve sleep-timer state across rebuilds
        _state.value = PlayerState(
            currentTitle = md.title?.toString() ?: "",
            currentArtist = md.artist?.toString() ?: "",
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.coerceAtLeast(0L),
            hasItem = c.currentMediaItem != null,
            currentUri = c.currentMediaItem?.localConfiguration?.uri?.toString(),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            speed = c.playbackParameters.speed,
            isVideo = c.currentMediaItem?.localConfiguration?.uri?.toString()?.contains("/video/") == true,
            sleepActive = prev.sleepActive,
            sleepRemainingMs = prev.sleepRemainingMs,
            sleepEndOfTrack = prev.sleepEndOfTrack,
            queueIndex = c.currentMediaItemIndex,
            queueSize = c.mediaItemCount
        )
    }

    // ---- QUEUE (§25) ----
    // Kept in its own flow rather than inside PlayerState: the position ticker
    // rebuilds PlayerState twice a second and the timeline changes rarely, so
    // folding the queue in would rebuild the whole list 120 times a minute.
    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue

    private val _error = MutableStateFlow<PlaybackError?>(null)
    val error: StateFlow<PlaybackError?> = _error

    fun clearError() { _error.value = null }

    /** §22 "Try again" — re-prepare without losing the queue or position. */
    fun retryPlayback() {
        val c = controller ?: return
        c.prepare(); c.play()
        _error.value = null
    }

    /**
     * §22 "Errors should not destroy current context": drop only the offending
     * item and carry on with the rest of the queue.
     */
    fun skipFailedItem() {
        val c = controller ?: return
        if (c.mediaItemCount > 1) {
            c.removeMediaItem(c.currentMediaItemIndex)
            c.prepare(); c.play()
        } else {
            c.clearMediaItems()
        }
        _error.value = null
        refreshQueue(); refresh()
    }

    private fun refreshQueue() {
        val c = controller ?: return
        _queue.value = (0 until c.mediaItemCount).map { i ->
            val mi = c.getMediaItemAt(i)
            QueueEntry(
                mediaId = mi.mediaId.toLongOrNull() ?: 0L,
                title = mi.mediaMetadata.title?.toString() ?: "Untitled",
                artist = mi.mediaMetadata.artist?.toString() ?: "",
                uri = mi.localConfiguration?.uri?.toString() ?: ""
            )
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to || from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount) return
        c.moveMediaItem(from, to)
        refreshQueue(); refresh()
    }

    fun removeQueueItem(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.removeMediaItem(index)
        refreshQueue(); refresh()
    }

    fun playQueueIndex(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0L); c.play()
        refresh()
    }

    /** Clears everything AFTER the current track — never stops what's playing. */
    fun clearUpNext() {
        val c = controller ?: return
        val next = c.currentMediaItemIndex + 1
        if (next < c.mediaItemCount) c.removeMediaItems(next, c.mediaItemCount)
        refreshQueue(); refresh()
    }

    fun play(items: List<AppMediaItem>, startIndex: Int) {
        val c = controller ?: return
        val exoItems = items.map { item ->
            ExoMediaItem.Builder()
                .setUri(item.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .apply { item.artworkUri?.let { setArtworkUri(it) } }
                        .build()
                )
                .build()
        }
        pillarById = items.associate { it.id to it.pillar }
        val startItem = items[startIndex]
        val longForm = startItem.pillar == Pillar.AUDIOBOOK || startItem.pillar == Pillar.PODCAST
        if (longForm) {
            // Read saved position off the main thread, then start at that offset.
            viewModelScope.launch {
                val saved = withContext(Dispatchers.IO) { db.positionDao().getOne(startItem.id) }
                val resumeMs = resumeOffsetFor(saved)
                c.setMediaItems(exoItems, startIndex, resumeMs)
                c.prepare()
                c.setPlaybackSpeed(saved?.speed ?: 1.0f)   // restore this item's speed
                c.play()
            }
        } else {
            c.setMediaItems(exoItems, startIndex, 0L)
            c.prepare()
            c.setPlaybackSpeed(1.0f)   // music always starts at 1x
            c.play()
        }
    }

    // A saved position is worth resuming only if it's past the intro (>30s) and
    // not within the last 30s of the item (so a nearly-finished book doesn't
    // resume at the very end). Otherwise start from 0.
    private fun resumeOffsetFor(p: PlaybackPosition?): Long {
        if (p == null) return 0L
        if (p.positionMs < 30_000L) return 0L
        if (p.durationMs > 0 && p.positionMs > p.durationMs - 30_000L) return 0L
        return p.positionMs
    }

    private fun currentIsLongForm(): Boolean {
        val id = controller?.currentMediaItem?.localConfiguration?.uri?.lastPathSegment?.toLongOrNull()
            ?: return false
        val pillar = pillarById[id] ?: return false
        return pillar == Pillar.AUDIOBOOK || pillar == Pillar.PODCAST
    }

    private fun saveCurrentPosition() {
        val c = controller ?: return
        if (!currentIsLongForm()) return
        val id = c.currentMediaItem?.localConfiguration?.uri?.lastPathSegment?.toLongOrNull() ?: return
        val pos = c.currentPosition
        val dur = c.duration.coerceAtLeast(0L)
        val spd = c.playbackParameters.speed
        if (pos <= 0L) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.positionDao().save(PlaybackPosition(id, pos, dur, System.currentTimeMillis(), spd))
            }
        }
    }

    fun playOrToggle(items: List<AppMediaItem>, startIndex: Int) {
        val c = controller ?: return
        val target = items[startIndex].uri.toString()
        if (c.currentMediaItem?.localConfiguration?.uri?.toString() == target) {
            if (c.isPlaying) c.pause() else c.play()
        } else {
            play(items, startIndex)
        }
    }

    // Update the live session metadata for the current item (after a user edit),
    // so notification / lock screen / mini-player refresh instantly.
    fun updateCurrentMetadata(mediaId: Long, title: String, artist: String) {
        val c = controller ?: return
        val current = c.currentMediaItem ?: return
        // Match by the id embedded in the uri (last path segment)
        val currentId = current.localConfiguration?.uri?.lastPathSegment?.toLongOrNull()
        if (currentId != mediaId) return

        val newMeta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .build()
        val updated = current.buildUpon().setMediaMetadata(newMeta).build()
        val idx = c.currentMediaItemIndex
        val pos = c.currentPosition
        val wasPlaying = c.isPlaying
        c.replaceMediaItem(idx, updated)
        c.seekTo(idx, pos)
        if (wasPlaying) c.play()
        refresh()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun next() { controller?.seekToNext() }
    fun previous() { controller?.seekToPrevious() }
    fun dismiss() {
        // Save any resume position first, then stop and clear so the mini-player
        // (shown only when hasItem) disappears.
        saveCurrentPosition()
        controller?.stop()
        controller?.clearMediaItems()
        refresh()
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        refresh()
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
            androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
        refresh()
    }

    fun cycleSpeed() {
        val c = controller ?: return
        val next = when {
            c.playbackParameters.speed < 1.1f -> 1.25f
            c.playbackParameters.speed < 1.4f -> 1.5f
            c.playbackParameters.speed < 1.9f -> 2.0f
            else -> 1.0f
        }
        c.setPlaybackSpeed(next)
        refresh()
        saveCurrentPosition()   // persist the new speed for this item right away
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onCleared()
    }
}
