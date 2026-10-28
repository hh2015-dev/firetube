package com.palmerintech.firetube.data

import com.palmerintech.firetube.data.db.HistoryEntity
import com.palmerintech.firetube.data.db.LibraryDao
import com.palmerintech.firetube.data.db.PlayEntity
import com.palmerintech.firetube.data.db.PlaylistEntity
import com.palmerintech.firetube.data.db.PlaylistEntity.Companion.FAVORITES_ID
import com.palmerintech.firetube.data.db.PlaylistWithCount
import com.palmerintech.firetube.data.db.TrackEntity
import com.palmerintech.firetube.data.db.TrackPlays
import com.palmerintech.firetube.extractor.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The user's library on this device: playlists (incl. Favorites) and play history.
 * This is the source of truth; cloud sync mirrors it when signed in.
 */
class LibraryRepository(private val dao: LibraryDao) {

    /** Serializes read-modify-write playlist edits so quick taps can't lose an update. */
    private val edits = Mutex()

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Emits a playlist id whenever it changes locally, so sync can push it. */
    val localChanges: SharedFlow<String> = _changes

    val playlists: Flow<List<PlaylistWithCount>> = dao.observePlaylists()
    val favorites: Flow<List<Track>> = dao.observePlaylistTracks(FAVORITES_ID).map { it.map(TrackEntity::toTrack) }

    fun recent(limit: Int = 30): Flow<List<Track>> = dao.observeRecent(limit).map { it.map(TrackEntity::toTrack) }
    suspend fun recentOnce(limit: Int = 30): List<Track> = dao.recent(limit).map(TrackEntity::toTrack)

    fun playlist(id: String): Flow<PlaylistEntity?> = dao.observePlaylist(id)
    fun playlistTracks(id: String): Flow<List<Track>> = dao.observePlaylistTracks(id).map { it.map(TrackEntity::toTrack) }
    suspend fun playlistTracksOnce(id: String): List<Track> = dao.playlistTracks(id).map(TrackEntity::toTrack)

    fun isFavorite(trackId: String): Flow<Boolean> = dao.observeContains(FAVORITES_ID, trackId)

    suspend fun toggleFavorite(track: Track): Unit = edits.withLock {
        val current = dao.playlistTracks(FAVORITES_ID).map(TrackEntity::toTrack)
        val updated = if (current.any { it.id == track.id }) current.filterNot { it.id == track.id } else listOf(track) + current
        ensureFavorites()
        write(FAVORITES_ID, updated)
    }

    suspend fun createPlaylist(
        name: String,
        tracks: List<Track> = emptyList(),
        sourceUrl: String? = null,
        id: String = UUID.randomUUID().toString(),
    ): String {
        dao.writePlaylist(PlaylistEntity(id, name.trim(), now(), sourceUrl = sourceUrl), tracks.map(TrackEntity::of))
        _changes.tryEmit(id)
        return id
    }

    suspend fun renamePlaylist(id: String, name: String): Unit = edits.withLock {
        val p = dao.playlist(id) ?: return
        dao.upsertPlaylist(p.copy(name = name.trim(), updatedAt = now()))
        _changes.tryEmit(id)
    }

    suspend fun deletePlaylist(id: String): Unit = edits.withLock {
        val p = dao.playlist(id) ?: return
        dao.writePlaylist(p.copy(deleted = true, updatedAt = now()), emptyList())
        _changes.tryEmit(id)
    }

    suspend fun addToPlaylist(id: String, tracks: List<Track>): Unit = edits.withLock {
        val current = dao.playlistTracks(id).map(TrackEntity::toTrack)
        write(id, current + tracks.filter { t -> current.none { it.id == t.id } })
    }

    suspend fun removeFromPlaylist(id: String, index: Int): Unit = edits.withLock {
        val current = dao.playlistTracks(id).map(TrackEntity::toTrack).toMutableList()
        if (index in current.indices) current.removeAt(index)
        write(id, current)
    }

    suspend fun movePlaylistTrack(id: String, from: Int, to: Int): Unit = edits.withLock {
        val current = dao.playlistTracks(id).map(TrackEntity::toTrack).toMutableList()
        if (from !in current.indices || to !in current.indices) return
        current.add(to, current.removeAt(from))
        write(id, current)
    }

    /** What we already know about a track, if it's been saved or played before. */
    suspend fun saveTracks(tracks: List<Track>) = dao.upsertTracks(tracks.map(TrackEntity::of))

    suspend fun knownTrack(id: String): Track? = dao.track(id)?.toTrack()

    suspend fun recordPlay(track: Track, at: Long = now()) {
        // Never overwrite real metadata with a blank item (e.g. one that arrived without details).
        if (track.title.isNotBlank()) dao.upsertTracks(listOf(TrackEntity.of(track)))
        else if (dao.track(track.id) == null) return
        dao.insertHistory(HistoryEntity(trackId = track.id, playedAt = at))
    }

    /** Clears stats too: they're the same listening history, just counted. */
    suspend fun clearHistory() {
        dao.clearHistory()
        dao.clearPlays()
    }

    // --- Stats (plays aren't synced or put in backup files; Android's device backup has them) ---

    private val prunedPlays = AtomicBoolean(false)

    /**
     * Saves (or updates) one listen once it counts as a play. Called again as the same listen
     * goes on, so it's an upsert keyed by when it started.
     */
    suspend fun recordListen(track: Track, startedAt: Long, msListened: Long) {
        if (track.title.isNotBlank()) dao.upsertTracks(listOf(TrackEntity.of(track)))
        else if (dao.track(track.id) == null) return
        dao.savePlay(PlayEntity(track.id, startedAt, msListened))
        // Keep the table bounded; once per run is plenty.
        if (prunedPlays.compareAndSet(false, true)) dao.prunePlays(now() - PLAYS_KEPT_MS)
    }

    fun trackPlays(from: Long): Flow<List<TrackPlays>> = dao.observeTrackPlays(from)

    /**
     * Stats for plays since [from]. One source: the hour chart is read with each emission of the
     * per-song totals, so the two never disagree for a frame.
     */
    fun listeningStats(from: Long, zone: ZoneId): Flow<ListeningStats> =
        dao.observeTrackPlays(from).map { tracks -> ListeningStats.of(tracks, dao.playTimes(from), zone) }

    val playCount: Flow<Int> = dao.observePlayCount()

    // --- used by sync / backup ---

    /** Runs a sync/backup merge under the same lock as local edits, so neither overwrites the other. */
    suspend fun <T> withEditLock(block: suspend () -> T): T = edits.withLock { block() }

    suspend fun allPlaylistsIncludingDeleted(): List<PlaylistEntity> = dao.allPlaylistsIncludingDeleted()

    /** Overwrite a playlist with a version from sync or a backup file. Does not emit a local change. */
    suspend fun applyRemote(playlist: PlaylistEntity, tracks: List<Track>) {
        dao.writePlaylist(playlist, if (playlist.deleted) emptyList() else tracks.map(TrackEntity::of))
    }

    suspend fun playlistEntity(id: String): PlaylistEntity? = dao.playlist(id)

    private suspend fun write(id: String, tracks: List<Track>) {
        val p = dao.playlist(id) ?: return
        dao.writePlaylist(p.copy(updatedAt = now(), deleted = false), tracks.map(TrackEntity::of))
        _changes.tryEmit(id)
    }

    private suspend fun ensureFavorites() {
        if (dao.playlist(FAVORITES_ID) == null) {
            dao.upsertPlaylist(PlaylistEntity(FAVORITES_ID, "Favorites", now()))
        }
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        /** Stats go back two years at most. */
        const val PLAYS_KEPT_MS = 2 * 366 * 24 * 60 * 60 * 1000L
    }
}
