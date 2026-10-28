package com.palmerintech.firetube.player

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity

/** Conversions between FireTube [Track]s and Media3 [MediaItem]s. */
object MediaItems {
    private const val SCHEME = "firetube"
    private const val HOST = "track"

    /** Live streams: an HLS manifest, played straight from the network rather than through the song caches. */
    private const val LIVE_HOST = "live"

    /** In the metadata extras, which (unlike the URI) reach controllers and survive Android Auto. */
    private const val EXTRA_LIVE = "firetube.live"
    private const val EXTRA_SOURCE = "firetube.source"
    private const val EXTRA_EXTERNAL_ID = "firetube.externalId"

    fun uriOf(trackId: String, live: Boolean = false): Uri = Uri.Builder().scheme(SCHEME).authority(if (live) LIVE_HOST else HOST).appendPath(trackId).build()

    fun trackIdOf(uri: Uri): String? =
        if (uri.scheme == SCHEME && (uri.host == HOST || uri.host == LIVE_HOST)) uri.lastPathSegment else null

    fun isLiveUri(uri: Uri): Boolean = uri.scheme == SCHEME && uri.host == LIVE_HOST

    fun isLive(item: MediaItem): Boolean = item.mediaMetadata.extras?.getBoolean(EXTRA_LIVE) == true

    fun of(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(uriOf(track.id, track.isLive))
        .setMimeType(if (track.isLive) MimeTypes.APPLICATION_M3U8 else null)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setArtworkUri(track.thumbnailUrl?.toUri())
                .setDurationMs(track.durationSeconds.takeIf { it > 0 }?.times(1000))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply {
                    if (track.isLive) putBoolean(EXTRA_LIVE, true)
                    putString(EXTRA_SOURCE, track.sourceId)
                    putString(EXTRA_EXTERNAL_ID, track.externalId)
                })
                .build(),
        )
        .build()

    /**
     * Items arriving from other processes (Android Auto, system media controls) lose their URI;
     * rebuild it from the media id.
     */
    fun withUri(item: MediaItem): MediaItem =
        if (item.localConfiguration != null) item else of(trackOf(item)).let { rebuilt ->
            item.buildUpon().setUri(rebuilt.localConfiguration!!.uri).setMimeType(rebuilt.localConfiguration!!.mimeType).build()
        }

    fun trackOf(item: MediaItem): Track {
        val m = item.mediaMetadata
        return Track(
            id = item.mediaId,
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            durationSeconds = (m.durationMs ?: 0L) / 1000,
            thumbnailUrl = m.artworkUri?.toString(),
            isLive = isLive(item),
            sourceId = m.extras?.getString(EXTRA_SOURCE) ?: TrackIdentity.YOUTUBE,
            externalId = m.extras?.getString(EXTRA_EXTERNAL_ID) ?: item.mediaId,
        )
    }
}
