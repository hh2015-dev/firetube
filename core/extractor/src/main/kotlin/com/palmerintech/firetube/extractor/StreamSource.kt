package com.palmerintech.firetube.extractor

/**
 * Everything FireTube needs from a music source. The app only depends on this interface, so when
 * YouTube breaks the current extractor, the fix is contained to one implementation.
 */
interface StreamSource : AudioSource {
    override val sourceId: String get() = TrackIdentity.YOUTUBE
    suspend fun search(query: String, filter: SearchFilter = SearchFilter.SONGS): Page<SearchResult>
    suspend fun searchMore(query: String, filter: SearchFilter, token: PageToken): Page<SearchResult>
    suspend fun suggestions(query: String): List<String>

    /**
     * Resolve [trackId] to a playable audio URL, plus related tracks for autoplay/radio.
     * [preferLowBitrate] picks the smallest stream (data saver).
     */
    override suspend fun resolve(trackId: String, preferLowBitrate: Boolean): ResolvedStream

    suspend fun playlist(url: String): Pair<PlaylistSummary, Page<Track>>
    suspend fun playlistMore(url: String, token: PageToken): Page<Track>

    /** Popular music right now (for the home screen). May be empty if the source has none. */
    suspend fun trending(): List<Track>
}
