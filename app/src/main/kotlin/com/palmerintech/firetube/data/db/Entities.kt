package com.palmerintech.firetube.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity

/** Track metadata, shared by playlists, history and downloads. */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    @ColumnInfo(defaultValue = "'youtube'") val sourceId: String = TrackIdentity.YOUTUBE,
    @ColumnInfo(defaultValue = "''") val externalId: String = id,
    val album: String? = null,
    val fileName: String? = null,
) {
    fun toTrack() = Track(id, title, artist, durationSeconds, thumbnailUrl, sourceId = sourceId, externalId = externalId, album = album, fileName = fileName)

    companion object {
        fun of(t: Track) = TrackEntity(t.id, t.title, t.artist, t.durationSeconds, t.thumbnailUrl, t.sourceId, t.externalId, t.album, t.fileName)
    }
}

/**
 * A playlist. Ids are UUID strings so they're stable across devices for Firestore sync.
 * Favorites is just the playlist with id [FAVORITES_ID].
 * Deletions are soft ([deleted]) so they propagate through sync.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** Set when imported from a YouTube playlist. */
    val sourceUrl: String? = null,
) {
    companion object {
        const val FAVORITES_ID = "favorites"
    }
}

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"]),
    ],
    indices = [Index("trackId")],
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val position: Int,
    val trackId: String,
)

@Entity(
    tableName = "history",
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"])],
    indices = [Index("trackId"), Index("playedAt")],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val trackId: String,
    val playedAt: Long,
)

/**
 * One listen, for Your stats: how long a song actually played, not counting time paused,
 * buffering or skipped (SponsorBlock jumps). Written only once it counts as a play (see
 * [com.palmerintech.firetube.player.ListenCounter]). Never synced or put in backup files, but
 * Android's device backup includes it with the rest of the database.
 * Unlike [HistoryEntity], which is written as soon as a song starts.
 */
@Entity(
    tableName = "plays",
    primaryKeys = ["startedAt", "trackId"],
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"])],
    indices = [Index("trackId")],
)
data class PlayEntity(
    val trackId: String,
    /** Wall-clock time the song first started making sound. */
    val startedAt: Long,
    val msListened: Long,
)

/** A track with its plays in some period, for stats. */
data class TrackPlays(
    @Embedded val track: TrackEntity,
    val plays: Int,
    val msListened: Long,
)

/** When a play happened and how long it lasted, for the listening-by-hour chart. */
data class PlayTime(val startedAt: Long, val msListened: Long)

/** Playlist row for list screens. */
data class PlaylistWithCount(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val trackCount: Int,
    val thumbnailUrl: String?,
)
