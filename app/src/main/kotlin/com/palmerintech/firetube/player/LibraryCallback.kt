package com.palmerintech.firetube.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.palmerintech.firetube.AppContainer
import com.palmerintech.firetube.R
import com.palmerintech.firetube.extractor.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future

/**
 * The browse tree for Android Auto and other media browsers, rebuilding play URIs for items
 * that arrive from other processes, and resuming the saved queue.
 */
@UnstableApi
class LibraryCallback(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        mediaItems.mapNotNull { item ->
            val query = item.requestMetadata.searchQuery
            when {
                // Voice search ("play … on FireTube"): play the top song; autoplay carries on from it.
                !query.isNullOrBlank() -> runCatching { container.source.search(query).items }.getOrDefault(emptyList())
                    .filterIsInstance<SearchResult.TrackResult>().firstOrNull()?.let { MediaItems.of(it.track) }
                item.mediaId.isBlank() -> null
                // Our own UI sends full metadata; only the URI needs rebuilding.
                item.mediaMetadata.title != null -> MediaItems.withUri(item)
                // Android Auto / system controls send just an id: fill in the details.
                else -> (container.library.knownTrack(item.mediaId)
                    ?: runCatching { container.resolver.resolve(item.mediaId).track }.getOrNull())
                    ?.let(MediaItems::of) ?: MediaItems.withUri(item)
            }
        }.toMutableList().also { items ->
            items.forEach { container.resolver.remember(MediaItems.trackOf(it)) }
            // An empty result would replace the queue with nothing (e.g. a misheard voice search).
            if (items.isEmpty()) throw UnsupportedOperationException("Nothing to play")
        }
    }

    /**
     * Set once play was pressed on an empty player and the saved queue is being resumed here;
     * PlaybackService's own restore then stands down, so the queue is only applied once.
     */
    var resuming = false
        private set

    /**
     * Play pressed while the player is empty — e.g. on the home-screen widget after FireTube was
     * closed, before the service's own restore has finished: pick up the saved queue.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (isForPlayback) resuming = true
        return scope.future {
            val saved = container.queueStore.load()
            saved?.tracks?.forEach { container.resolver.remember(it.toTrack()) }
            resumptionOf(saved)
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future { LibraryResult.ofItem(folder(ROOT, container.app.getString(R.string.app_name)), params) }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val track = container.library.recentOnce(200).firstOrNull { it.id == mediaId }
        if (track != null) LibraryResult.ofItem(MediaItems.of(track), null)
        else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val lib = container.library
        val items: List<MediaItem> = when {
            parentId == ROOT -> listOf(
                folder(RECENT, container.app.getString(R.string.library_recently_played)),
                folder(FAVORITES, container.app.getString(R.string.library_favorites)),
                folder(PLAYLISTS, container.app.getString(R.string.library_playlists)),
                folder(TRENDING, container.app.getString(R.string.auto_trending)),
            )
            parentId == RECENT -> lib.recentOnce(50).map(MediaItems::of)
            parentId == FAVORITES -> lib.favorites.first().map(MediaItems::of)
            parentId == PLAYLISTS -> lib.playlists.first().map { folder(PLAYLIST_PREFIX + it.id, it.name) }
            parentId.startsWith(PLAYLIST_PREFIX) -> lib.playlistTracksOnce(parentId.removePrefix(PLAYLIST_PREFIX)).map(MediaItems::of)
            parentId == TRENDING -> runCatching { container.source.trending() }.getOrDefault(emptyList()).take(50).map(MediaItems::of)
            else -> emptyList()
        }
        val from = (page * pageSize).coerceAtMost(items.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, (from + pageSize).coerceAtMost(items.size))), params)
    }

    private var lastSearch: Pair<String, List<MediaItem>>? = null

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        val results = runCatching { container.source.search(query).items }.getOrDefault(emptyList())
            .filterIsInstance<SearchResult.TrackResult>().map { MediaItems.of(it.track) }
        lastSearch = query to results
        session.notifySearchResultChanged(browser, query, results.size, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val items = lastSearch?.takeIf { it.first == query }?.second ?: emptyList()
        val from = (page * pageSize).coerceAtMost(items.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, (from + pageSize).coerceAtMost(items.size))), params)
    }

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(Bundle.EMPTY)
                .build(),
        )
        .build()

    companion object {
        /** The saved queue as Media3 resumes it; throws (Media3 then just plays) when there's none. */
        fun resumptionOf(saved: QueueStore.Saved?): MediaSession.MediaItemsWithStartPosition {
            if (saved == null || saved.tracks.isEmpty()) throw UnsupportedOperationException("No saved queue")
            return MediaSession.MediaItemsWithStartPosition(
                saved.tracks.map { MediaItems.of(it.toTrack()) },
                saved.index.coerceIn(0, saved.tracks.lastIndex),
                saved.positionMs,
            )
        }

        private const val ROOT = "root"
        private const val RECENT = "recent"
        private const val FAVORITES = "favorites"
        private const val PLAYLISTS = "playlists"
        private const val TRENDING = "trending"
        private const val PLAYLIST_PREFIX = "playlist:"
    }
}
