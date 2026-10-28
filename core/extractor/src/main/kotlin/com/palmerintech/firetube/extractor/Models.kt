package com.palmerintech.firetube.extractor

/** A playable item (song / video) as FireTube sees it, independent of any extractor library. */
data class Track(
    /** Opaque internal key. Legacy YouTube keys intentionally keep their original value. */
    val id: String,
    val title: String,
    /** As YouTube credits it, and as stored; show [credit] instead, it may be "X and 2 more". */
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    /** A live stream (no duration, can't be downloaded). Not stored: playback finds out again if lost. */
    val isLive: Boolean = false,
    val sourceId: String = TrackIdentity.YOUTUBE,
    val externalId: String = id,
) {
    val identity: TrackIdentity get() = TrackIdentity(id, sourceId, externalId)

    val url: String? get() = if (sourceId == TrackIdentity.YOUTUBE) "https://www.youtube.com/watch?v=$externalId" else null

    val credit: ArtistCredit get() = ArtistCredit.parse(artist)
}

/**
 * Who a track is credited to: its [primary] artist and how many [others] (a collaboration of
 * several channels). YouTube writes those as "Shakira and 2 more", in English since
 * [NewPipeStreamSource] asks for English results; that string is kept as [Track.artist] (it's
 * what has always been stored), and apps put the parts back together in the user's language.
 */
data class ArtistCredit(val primary: String, val others: Int = 0) {
    companion object {
        private val andMore = Regex("""^(.+?)\s+and\s+(\d{1,4})\s+more$""")

        /** "Shakira and 2 more" → (Shakira, 2); any other name is all [primary]. */
        fun parse(artist: String): ArtistCredit {
            val m = andMore.matchEntire(artist.trim()) ?: return ArtistCredit(artist)
            val others = m.groupValues[2].toInt()
            return if (others > 0) ArtistCredit(m.groupValues[1], others) else ArtistCredit(artist)
        }
    }
}

data class PlaylistSummary(
    val url: String,
    val title: String,
    val owner: String,
    val trackCount: Long,
    val thumbnailUrl: String?,
)

/** One page of results plus an opaque token for the next page (null when there are no more). */
data class Page<T>(val items: List<T>, val next: PageToken?)

/** Opaque continuation handle; only the [StreamSource] that produced it knows what's inside. */
class PageToken internal constructor(internal val value: Any)

/** A search hit: either something playable or a playlist to open. */
sealed interface SearchResult {
    data class TrackResult(val track: Track) : SearchResult
    data class PlaylistResult(val playlist: PlaylistSummary) : SearchResult
}

/** [ALL] is YouTube's unfiltered search: videos (live streams included) and playlists. */
enum class SearchFilter { SONGS, ALL, PLAYLISTS }

/** A resolved, directly playable audio stream. URLs expire (YouTube: ~6h), so resolve just in time. */
data class ResolvedStream(
    val trackId: String,
    val url: String,
    val mimeType: String?,
    val bitrate: Int,
    /** Epoch millis after which [url] should be considered stale. */
    val expiresAtMillis: Long,
    val related: List<Track> = emptyList(),
    /** The track's own details (useful when all we had was an id, e.g. from a shared link). */
    val track: Track? = null,
    /** Sections of a long video (a DJ mix, a full album), in order; empty when it has none. */
    val chapters: List<Chapter> = emptyList(),
    /** [url] is an HLS manifest of a live stream, not an audio file. */
    val live: Boolean = false,
)

/** A titled section of a track, starting [startMs] into it. [title] may be empty (YouTube gave none). */
data class Chapter(val title: String, val startMs: Long)

class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** True when retrying later won't help (removed, private, age-restricted, region-blocked). */
    var permanent: Boolean = false
        internal set
}
