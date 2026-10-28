package com.palmerintech.firetube.data.sync

import com.palmerintech.firetube.data.LibraryRepository
import com.palmerintech.firetube.data.db.PlaylistEntity
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import kotlinx.serialization.Serializable

/** A playlist as it's stored off-device (Firestore documents and backup files). */
@Serializable
data class PlaylistSnapshot(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val sourceUrl: String? = null,
    val tracks: List<TrackSnapshot> = emptyList(),
) {
    fun entity() = PlaylistEntity(id, name, updatedAt, deleted, sourceUrl)
    fun trackList() = tracks.map { it.toTrack() }
}

@Serializable
data class TrackSnapshot(
    val id: String,
    val title: String,
    val artist: String = "",
    val duration: Long = 0,
    val thumb: String? = null,
    val sourceId: String = TrackIdentity.YOUTUBE,
    val externalId: String = id,
) {
    fun toTrack() = Track(id, title, artist, duration, thumb, sourceId = sourceId, externalId = externalId)

    internal fun toCloudMap(): Map<String, Any?> = mapOf(
        "id" to id, "title" to title, "artist" to artist, "duration" to duration,
        "thumb" to thumb, "sourceId" to sourceId, "externalId" to externalId,
    )

    companion object {
        internal fun fromCloudMap(t: Map<String, Any?>) = TrackSnapshot(
            id = t["id"] as String,
            title = t["title"] as? String ?: "",
            artist = t["artist"] as? String ?: "",
            duration = (t["duration"] as? Number)?.toLong() ?: 0,
            thumb = t["thumb"] as? String,
            sourceId = t["sourceId"] as? String ?: TrackIdentity.YOUTUBE,
            externalId = t["externalId"] as? String ?: t["id"] as String,
        )

        fun of(t: Track) = TrackSnapshot(t.id, t.title, t.artist, t.durationSeconds, t.thumbnailUrl, t.sourceId, t.externalId)
    }
}

suspend fun LibraryRepository.snapshot(id: String): PlaylistSnapshot? {
    val p = playlistEntity(id) ?: return null
    return PlaylistSnapshot(p.id, p.name, p.updatedAt, p.deleted, p.sourceUrl, playlistTracksOnce(id).map(TrackSnapshot::of))
}

suspend fun LibraryRepository.allSnapshots(): List<PlaylistSnapshot> =
    allPlaylistsIncludingDeleted().mapNotNull { snapshot(it.id) }

/**
 * Merges [incoming] into the local library and returns what should be sent back.
 *
 * Normally last-writer-wins per playlist. Playlists in [unionIds] are instead combined (incoming
 * order first, then local-only songs) — used for Favorites on an account's first sync on a device,
 * so hearting one song before signing in doesn't wipe the favorites already in the cloud.
 */
suspend fun LibraryRepository.mergeSnapshots(
    incoming: List<PlaylistSnapshot>,
    unionIds: Set<String> = emptySet(),
): List<PlaylistSnapshot> = withEditLock {
    val local = allSnapshots().associateBy { it.id }
    val remote = incoming.associateBy { it.id }
    val toSend = mutableListOf<PlaylistSnapshot>()
    for ((id, r) in remote) {
        val l = local[id]
        when {
            l == null -> applyRemote(r.entity(), r.trackList())
            id in unionIds && !l.deleted && !r.deleted -> {
                val merged = r.copy(
                    updatedAt = maxOf(l.updatedAt, r.updatedAt) + 1,
                    tracks = r.tracks + l.tracks.filter { t -> r.tracks.none { it.id == t.id } },
                )
                applyRemote(merged.entity(), merged.trackList())
                toSend += merged
            }
            r.updatedAt > l.updatedAt -> applyRemote(r.entity(), r.trackList())
            l.updatedAt > r.updatedAt -> toSend += l
        }
    }
    toSend += local.values.filter { it.id !in remote }
    toSend
}
