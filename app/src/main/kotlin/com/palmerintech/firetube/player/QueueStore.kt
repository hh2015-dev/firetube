package com.palmerintech.firetube.player

import android.content.Context
import androidx.core.util.AtomicFile
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Persists the play queue so it survives the app being killed. Only track ids and metadata are
 * saved — never stream URLs, which expire — so a restored queue always re-resolves.
 * Writes are serialized and atomic, so a crash mid-write can't leave a corrupt file.
 */
class QueueStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "queue.json"))

    @Serializable
    data class Saved(val tracks: List<SavedTrack>, val index: Int, val positionMs: Long)

    @Serializable
    data class SavedTrack(val id: String, val title: String, val artist: String, val duration: Long, val thumb: String?, val sourceId: String = TrackIdentity.YOUTUBE, val externalId: String = id, val album: String? = null, val fileName: String? = null) {
        fun toTrack() = Track(id, title, artist, duration, thumb, sourceId = sourceId, externalId = externalId, album = album, fileName = fileName)
    }

    private val writer = Executors.newSingleThreadExecutor()
    private val latest = AtomicLong()

    /** Saves in the background. Rapid saves are conflated: only the newest snapshot is written. */
    fun save(tracks: List<Track>, index: Int, positionMs: Long) {
        val seq = latest.incrementAndGet()
        writer.execute { if (seq == latest.get()) synchronized(this) { write(tracks, index, positionMs) } }
    }

    /** Blocking save for shutdown paths; supersedes any pending background save. */
    fun saveNow(tracks: List<Track>, index: Int, positionMs: Long) {
        latest.incrementAndGet()
        synchronized(this) { write(tracks, index, positionMs) }
    }

    suspend fun load(): Saved? = withContext(Dispatchers.IO) {
        runCatching { if (file.baseFile.exists()) json.decodeFromString(Saved.serializer(), file.readFully().decodeToString()) else null }
            .onFailure { Timber.w(it, "Couldn't load queue") }
            .getOrNull()
    }

    private fun write(tracks: List<Track>, index: Int, positionMs: Long) {
        val saved = Saved(tracks.map { SavedTrack(it.id, it.title, it.artist, it.durationSeconds, it.thumbnailUrl, it.sourceId, it.externalId, it.album, it.fileName) }, index, positionMs)
        val bytes = json.encodeToString(Saved.serializer(), saved).encodeToByteArray()
        val out = try {
            file.startWrite()
        } catch (e: Exception) {
            Timber.w(e, "Couldn't save queue")
            return
        }
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Timber.w(e, "Couldn't save queue")
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
