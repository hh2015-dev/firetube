package com.palmerintech.firetube.player

import android.app.Notification
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.palmerintech.firetube.FireTubeApp
import com.palmerintech.firetube.R
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors

/**
 * Offline downloads, stored in [MediaStack.downloadCache] under the same keys playback uses.
 * Their chapters are kept in [chapterStore], since playing a download doesn't resolve it.
 */
@UnstableApi
class Downloads(
    private val context: Context,
    stack: MediaStack,
    private val resolver: StreamResolver,
    private val chapterStore: ChapterStore,
) {

    val manager = DownloadManager(
        context,
        stack.database,
        stack.downloadCache,
        stack.networkFactory,
        Executors.newFixedThreadPool(2),
    ).apply { maxParallelDownloads = 2 }

    private val _state = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())

    /** Track id → download state, for badges and the Downloads screen. */
    val state: StateFlow<Map<String, DownloadInfo>> = _state

    // One thread: index reads publish in order.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var progressJob: Job? = null

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = refresh()
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
        })
        refresh()
    }

    /** Reads the download index off the main thread; polls while downloads are running (the manager doesn't report progress). */
    private fun refresh() {
        scope.launch {
            readIndex()
            val active = _state.value.values.any { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }
            if (active && progressJob?.isActive != true) {
                progressJob = scope.launch {
                    while (isActive) {
                        delay(1000)
                        readIndex()
                        // The index only records progress every few seconds; take live figures from the manager.
                        val live = withContext(Dispatchers.Main) { manager.currentDownloads.associate { it.request.id to it.percentDownloaded } }
                        _state.value = _state.value.mapValues { (id, info) -> live[id]?.let { info.copy(percent = it) } ?: info }
                        if (_state.value.values.none { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED }) break
                    }
                }
            }
        }
    }

    fun download(track: Track) {
        resolver.remember(track)
        val request = DownloadRequest.Builder(track.id, MediaItems.uriOf(track.id))
            .setData(json.encodeToString(StoredTrack.serializer(), StoredTrack.of(track)).encodeToByteArray())
            .build()
        DownloadService.sendAddDownload(context, DownloadsService::class.java, request, false)
    }

    fun remove(trackId: String) {
        DownloadService.sendRemoveDownload(context, DownloadsService::class.java, trackId, false)
    }

    private fun readIndex() {
        val out = mutableMapOf<String, DownloadInfo>()
        val ids = mutableSetOf<String>()
        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val d = cursor.download
                ids += d.request.id
                val track = runCatching {
                    json.decodeFromString(StoredTrack.serializer(), d.request.data.decodeToString()).toTrack()
                }.getOrNull() ?: continue
                resolver.remember(track)
                out[d.request.id] = DownloadInfo(track, d.state, d.percentDownloaded)
            }
        }
        _state.value = out
        // A download resolves its track, so its chapters are known by the time it changes state.
        chapterStore.keepFor(ids, resolver::knownChapters)
    }

    data class DownloadInfo(val track: Track, val state: Int, val percent: Float) {
        val completed get() = state == Download.STATE_COMPLETED
        val failed get() = state == Download.STATE_FAILED
    }

    @Serializable
    internal data class StoredTrack(val id: String, val title: String, val artist: String, val duration: Long, val thumb: String?, val sourceId: String = TrackIdentity.YOUTUBE, val externalId: String = id) {
        fun toTrack() = Track(id, title, artist, duration, thumb, sourceId = sourceId, externalId = externalId)

        companion object {
            fun of(t: Track) = StoredTrack(t.id, t.title, t.artist, t.durationSeconds, t.thumbnailUrl, t.sourceId, t.externalId)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@UnstableApi
class DownloadsService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.downloads_channel,
    0,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = (application as FireTubeApp).container.downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        helper.buildProgressNotification(this, R.drawable.ic_notification, null, null, downloads, notMetRequirements)

    private companion object {
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "downloads"
    }
}
