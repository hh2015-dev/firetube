package com.palmerintech.firetube.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** Further behind than this, a live stream offers to jump back to the live broadcast. ExoPlayer itself aims for ~15s. */
private const val BEHIND_LIVE_MS = 30_000L

/** UI-facing player state. */
data class PlayerUiState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    /** 0 for a live stream, which has no end to count down to. */
    val durationMs: Long = 0,
    val isLive: Boolean = false,
    /** Playing a live stream behind its live edge (it was paused, or seeked back). */
    val behindLive: Boolean = false,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    /** Playback speed (1 = normal); positions already advance at this rate. */
    val speed: Float = 1f,
    /** Connected to the playback service; until then [current] is null even if a queue will be restored. */
    val connected: Boolean = false,
    val localPlaybackError: Boolean = false,
    val localCastBlocked: Boolean = false,
)

/**
 * Connects the UI to [PlaybackService] through a [MediaController] and exposes its state as a
 * flow. Commands issued before the connection is ready are queued and run once it is.
 */
@UnstableApi
class PlayerConnection(private val context: Context, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var ticker: Job? = null

    private var connecting = false

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        scope.launch {
            try {
                val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                val c = MediaController.Builder(context, token).buildAsync().await()
                controller = c
                c.addListener(listener)
                publish(c)
                pending.forEach { it(c) }
                pending.clear()
            } catch (e: Exception) {
                // Drop queued commands (they'd replay stale later); the next command reconnects.
                pending.clear()
                Timber.w(e, "Couldn't connect to the player")
            } finally {
                connecting = false
            }
        }
    }

    fun release() {
        ticker?.cancel()
        controller?.release()
        controller = null
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: run {
            pending += block
            connect()
        }
    }

    // --- commands ---

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) = withController { c ->
        if (tracks.isEmpty()) return@withController
        if (blocksLocalCast(c, tracks)) return@withController
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(tracks.indices)
        c.setMediaItems(tracks.map(MediaItems::of), start, 0)
        c.prepare()
        c.play()
    }

    fun playNext(track: Track) = withController { c ->
        if (c.mediaItemCount == 0) return@withController play(listOf(track))
        if (blocksLocalCast(c, listOf(track))) return@withController
        c.addMediaItem(c.currentMediaItemIndex + 1, MediaItems.of(track))
    }

    fun enqueue(tracks: List<Track>) = withController { c ->
        if (c.mediaItemCount == 0) return@withController play(tracks)
        if (blocksLocalCast(c, tracks)) return@withController
        c.addMediaItems(tracks.map(MediaItems::of))
    }

    private fun blocksLocalCast(controller: MediaController, tracks: List<Track>): Boolean {
        val blocked = controller.deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE &&
            tracks.any { it.sourceId == TrackIdentity.LOCAL }
        if (blocked) _state.value = _state.value.copy(localCastBlocked = true)
        return blocked
    }

    fun togglePlay() = withController { c -> if (c.isPlaying) c.pause() else c.resume() }

    /** Plays, picking a stopped or finished queue back up. */
    fun resume() = withController { it.resume() }
    fun pause() = withController { it.pause() }

    private fun Player.resume() {
        if (playbackState == Player.STATE_IDLE) prepare()
        if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
        play()
    }

    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }

    /** Always changes track (unlike [previous], which restarts the song when it's past the start). */
    fun previousTrack() = withController { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun goLive() = withController { it.seekToDefaultPosition() }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index); it.play() }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun moveQueueItem(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun removeQueueItem(index: Int) = withController { it.removeMediaItem(index) }

    // --- state ---

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }
    }

    private fun publish(p: Player) {
        val queue = if (p.currentTimeline == Timeline.EMPTY) emptyList()
        else (0 until p.mediaItemCount).map { MediaItems.trackOf(p.getMediaItemAt(it)) }
        val current = p.currentMediaItem?.let(MediaItems::trackOf)
        val live = p.isCurrentMediaItemLive || current?.isLive == true
        _state.value = PlayerUiState(
            current = current,
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            positionMs = p.currentPosition,
            durationMs = if (live) 0 else p.duration.takeIf { it > 0 } ?: ((p.currentMediaItem?.mediaMetadata?.durationMs) ?: 0),
            isLive = live,
            behindLive = live && p.currentLiveOffset.let { it != C.TIME_UNSET && it > BEHIND_LIVE_MS },
            queue = queue,
            currentIndex = p.currentMediaItemIndex,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            hasNext = p.hasNextMediaItem(),
            hasPrevious = p.hasPreviousMediaItem(),
            speed = p.playbackParameters.speed,
            connected = true,
            localPlaybackError = p.playerError != null && current?.sourceId == TrackIdentity.LOCAL,
        )
        if (p.isPlaying) startTicker(p) else ticker?.cancel()
    }

    /** Keeps the progress bar moving while playing (a little faster when the song plays faster). */
    private fun startTicker(p: Player) {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                _state.value = _state.value.copy(positionMs = p.currentPosition)
                delay((500 / p.playbackParameters.speed.coerceAtLeast(1f)).toLong())
            }
        }
    }
}
