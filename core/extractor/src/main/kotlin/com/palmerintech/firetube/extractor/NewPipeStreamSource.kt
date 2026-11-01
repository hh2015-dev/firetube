package com.palmerintech.firetube.extractor

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeTrendingMusicLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.Page as NpPage

/** [StreamSource] backed by NewPipeExtractor's YouTube service. */
class NewPipeStreamSource(
    client: OkHttpClient = OkHttpDownloader.defaultClient(),
    localization: Localization = Localization.DEFAULT,
    country: ContentCountry = ContentCountry.DEFAULT,
    /** The user's country in English ("United States"), for its chart; null for the global one only. */
    private val chartCountry: String? = null,
    /** Today, for the daily rotation of the charts (which YouTube only updates weekly). */
    private val today: () -> Long = { System.currentTimeMillis() / 86_400_000L },
) : StreamSource {

    init {
        NewPipe.init(OkHttpDownloader(client), localization, country)
    }

    private val yt = ServiceList.YouTube
    /** Reuse the already extracted URL immediately after the user chooses a quality. */
    private val preparedDownloads = ConcurrentHashMap<String, Map<String, ResolvedStream>>()

    override suspend fun search(query: String, filter: SearchFilter): Page<SearchResult> = io {
        val handler = yt.searchQHFactory.fromQuery(query, filter.contentFilters(), "")
        val info = SearchInfo.getInfo(yt, handler)
        Page(info.relatedItems.mapNotNull { it.toSearchResult() }, info.nextPage?.let(::PageToken))
    }

    override suspend fun searchMore(query: String, filter: SearchFilter, token: PageToken): Page<SearchResult> = io {
        val handler = yt.searchQHFactory.fromQuery(query, filter.contentFilters(), "")
        val page = SearchInfo.getMoreItems(yt, handler, token.value as NpPage)
        Page(page.items.mapNotNull { it.toSearchResult() }, page.nextPage?.let(::PageToken))
    }

    override suspend fun suggestions(query: String): List<String> = io {
        yt.suggestionExtractor.suggestionList(query)
    }

    override suspend fun resolve(trackId: String, preferLowBitrate: Boolean): ResolvedStream = io {
        val info = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$trackId")
        val related = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack() }.distinctBy { it.id }
        val live = info.streamType.isLive()
        val track = Track(trackId, info.name, info.uploaderName.orEmpty().removeSuffix(" - Topic"), if (live) 0 else info.duration, info.thumbnails.best(), live)
        if (live) {
            // YouTube only serves live streams as video+audio manifests; the player keeps the audio.
            val hls = info.hlsUrl?.takeIf { it.isNotEmpty() }
                ?: throw ExtractionException("No playable live stream for $trackId")
            return@io ResolvedStream(
                trackId = trackId,
                url = hls,
                mimeType = HLS_MIME_TYPE,
                bitrate = 0,
                expiresAtMillis = expiryOf(hls),
                related = related,
                track = track,
                live = true,
            )
        }
        val audio = pickAudio(info.audioStreams, preferLowBitrate)
            ?: throw ExtractionException("No playable audio stream for $trackId")
        ResolvedStream(
            trackId = trackId,
            url = audio.content,
            mimeType = audio.format?.mimeType,
            bitrate = audio.averageBitrate,
            expiresAtMillis = expiryOf(audio.content),
            related = related,
            track = track,
            // Same request: YouTube's chapters, or a track list in the description when it has none.
            chapters = Chapters.fromSegments(info.streamSegments.orEmpty(), info.duration)
                .ifEmpty { Chapters.fromDescription(Chapters.plainText(info.description), info.duration) },
        )
    }

    override suspend fun downloadVariants(trackId: String): List<DownloadVariant> = io {
        val info = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$trackId")
        if (info.streamType.isLive()) return@io emptyList()
        val selected = eligibleAudio(info.audioStreams).distinctBy(::variantId)
        val chapters = Chapters.fromSegments(info.streamSegments.orEmpty(), info.duration)
            .ifEmpty { Chapters.fromDescription(Chapters.plainText(info.description), info.duration) }
        if (preparedDownloads.size > 24) preparedDownloads.clear()
        preparedDownloads[trackId] = selected.associate { audio ->
            variantId(audio) to ResolvedStream(
                trackId, audio.content, audio.format?.mimeType, audio.averageBitrate,
                expiryOf(audio.content), chapters = chapters,
            )
        }
        selected.map { audio ->
                val exact = audio.itagItem?.contentLength?.takeIf { it > 0 }
                val bitrate = audio.averageBitrate
                val estimate = if (exact == null && bitrate > 0 && info.duration > 0) {
                    (info.duration * bitrate.toLong() * 1000L) / 8L
                } else null
                DownloadVariant(variantId(audio), bitrate, audio.format?.mimeType, exact ?: estimate, exact == null)
            }
            .sortedWith(compareBy<DownloadVariant> { it.bitrateKbps }.thenBy { it.mimeType })
    }

    override suspend fun resolveDownloadVariant(trackId: String, variantId: String, forceRefresh: Boolean): ResolvedStream = io {
        if (!forceRefresh) preparedDownloads[trackId]?.get(variantId)
            ?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }?.let { return@io it }
        val info = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$trackId")
        if (info.streamType.isLive()) throw ExtractionException("Cannot download live audio")
        val audio = eligibleAudio(info.audioStreams).firstOrNull { variantId(it) == variantId }
            ?: throw ExtractionException("Selected audio quality is no longer available")
        ResolvedStream(
            trackId = trackId,
            url = audio.content,
            mimeType = audio.format?.mimeType,
            bitrate = audio.averageBitrate,
            expiresAtMillis = expiryOf(audio.content),
            chapters = Chapters.fromSegments(info.streamSegments.orEmpty(), info.duration)
                .ifEmpty { Chapters.fromDescription(Chapters.plainText(info.description), info.duration) },
        )
    }

    override suspend fun playlist(url: String): Pair<PlaylistSummary, Page<Track>> = io {
        val info = PlaylistInfo.getInfo(yt, url)
        val summary = PlaylistSummary(
            url = info.url,
            title = info.name,
            owner = info.uploaderName.orEmpty(),
            trackCount = info.streamCount,
            thumbnailUrl = info.thumbnails.best(),
        )
        summary to Page(info.relatedItems.mapNotNull { it.toTrack() }, info.nextPage?.let(::PageToken))
    }

    override suspend fun playlistMore(url: String, token: PageToken): Page<Track> = io {
        val page = PlaylistInfo.getMoreItems(yt, url, token.value as NpPage)
        Page(page.items.mapNotNull { it.toTrack() }, page.nextPage?.let(::PageToken))
    }

    override suspend fun trending(): List<Track> {
        val kiosk = runCatching {
            io {
                val extractor = yt.kioskList.getExtractorById(YoutubeTrendingMusicLinkHandlerFactory.KIOSK_ID, null)
                KioskInfo.getInfo(extractor).relatedItems.mapNotNull { it.toTrack() }
            }
        }.getOrDefault(emptyList())
        if (kiosk.isNotEmpty()) return kiosk.distinctBy { it.id }
        // Charts change weekly: look one up once a day (Home and Android Auto both ask).
        val day = today()
        chartCache?.takeIf { it.first == day }?.let { return it.second }
        // The trending-music kiosk is usually empty. YouTube's charts stand in: the user's country's
        // first, else the global one. They change weekly, so the order rotates daily to keep Home fresh.
        val chart = chartCountry?.let { runCatching { countryChart(it) }.getOrNull() }
            ?: playlist(TOP_SONGS_GLOBAL).second.items
        return dailyMix(chart.distinctBy { it.id }, day).also { chartCache = day to it }
    }

    /** Today's trending list from the charts, and the day it was made. */
    @Volatile private var chartCache: Pair<Long, List<Track>>? = null

    /** The country's chart from YouTube's own chart channel (songs, else music videos); null when it has none. */
    private suspend fun countryChart(country: String): List<Track>? {
        for (title in listOf("Top 100 Songs $country", "Top 100 Music Videos $country")) {
            val hit = search(title, SearchFilter.PLAYLISTS).items
                .filterIsInstance<SearchResult.PlaylistResult>()
                .firstOrNull { it.playlist.owner == CHARTS_CHANNEL && it.playlist.title.equals(title, ignoreCase = true) }
                ?: continue
            playlist(hit.playlist.url).second.items.ifEmpty { null }?.let { return it }
        }
        return null
    }

    /** Runs blocking NewPipe calls off the main thread and maps its exceptions to ours. */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: ExtractionException) {
            throw e
        } catch (e: Exception) {
            throw ExtractionException(e.message ?: e.javaClass.simpleName, e).apply {
                permanent = e is AgeRestrictedContentException || e is GeographicRestrictionException ||
                    e is PrivateContentException || e is PaidContentException ||
                    e.javaClass == ContentNotAvailableException::class.java
            }
        }
    }

    private fun SearchFilter.contentFilters() = when (this) {
        SearchFilter.SONGS -> listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
        SearchFilter.ALL -> emptyList() // channels in the results are dropped by toSearchResult
        SearchFilter.PLAYLISTS -> listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS)
    }

    private fun InfoItem.toSearchResult(): SearchResult? = when (this) {
        is StreamInfoItem -> toTrack()?.let(SearchResult::TrackResult)
        is PlaylistInfoItem -> SearchResult.PlaylistResult(
            PlaylistSummary(url, name, uploaderName.orEmpty(), streamCount, thumbnails.best()),
        )
        else -> null
    }

    private fun StreamInfoItem.toTrack(): Track? {
        val id = videoId(url) ?: return null
        val live = streamType.isLive()
        // A live stream's "duration" is meaningless (-1, or how long it's been on).
        return Track(id, name, uploaderName.orEmpty().removeSuffix(" - Topic"), if (live) 0 else duration, thumbnails.best(), live)
    }

    private fun StreamType?.isLive() = this == StreamType.LIVE_STREAM || this == StreamType.AUDIO_LIVE_STREAM

    companion object {
        /** YouTube Music's "Top 100 Songs Global" chart. */
        const val HLS_MIME_TYPE = "application/x-mpegURL"

        /** The channel that publishes YouTube Music's official charts. */
        private const val CHARTS_CHANNEL = "YouTube Music Global Charts"

        /**
         * [chart] reordered for [day]: the top 10 stay on top (shuffled among themselves), the rest
         * are shuffled below them. Stable within a day, different the next.
         */
        internal fun dailyMix(chart: List<Track>, day: Long): List<Track> {
            val random = java.util.Random(day)
            val (top, rest) = chart.take(10) to chart.drop(10)
            return top.shuffled(random) + rest.shuffled(random)
        }

        const val TOP_SONGS_GLOBAL = "https://www.youtube.com/playlist?list=PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i"

        private val VIDEO_ID = Regex("(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})")

        fun videoId(url: String): String? = VIDEO_ID.find(url)?.groupValues?.get(1)

        /**
         * Prefer progressive (single-file) streams — ExoPlayer plays them directly and they cache
         * cleanly. Among those prefer AAC/M4A (plays everywhere, incl. Cast), then by bitrate.
         */
        internal fun pickAudio(streams: List<AudioStream>, preferLowBitrate: Boolean = false): AudioStream? {
            val byPreference = compareBy<AudioStream> { if (it.format == MediaFormat.M4A) 1 else 0 }
                .thenBy { if (preferLowBitrate) -it.averageBitrate else it.averageBitrate }
            return eligibleAudio(streams).maxWithOrNull(byPreference)
        }

        /** Stable across URL expiry; 0/-1 itags use format + bitrate as a fallback. */
        internal fun variantId(stream: AudioStream): String =
            if (stream.itag > 0) "itag:${stream.itag}" else "format:${stream.format?.mimeType}:${stream.averageBitrate}"

        private fun eligibleAudio(streams: List<AudioStream>): List<AudioStream> {
            val progressive = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            val original = progressive.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }
            return original.ifEmpty { progressive }
        }

        internal fun expiryOf(url: String): Long {
            val parsed = url.toHttpUrlOrNull()
            // Audio URLs carry ?expire=…; HLS manifests carry it as a path segment, /expire/…/.
            val expireSeconds = (
                parsed?.queryParameter("expire")
                    ?: parsed?.pathSegments?.zipWithNext()?.firstOrNull { it.first == "expire" }?.second
                )?.toLongOrNull()
            // Refresh a bit early; default to 1h if the URL doesn't say.
            return expireSeconds?.let { it * 1000 - 10 * 60_000 } ?: (System.currentTimeMillis() + 60 * 60_000)
        }

        internal fun List<Image>.best(): String? =
            filter { it.height <= 720 || it.height == Image.HEIGHT_UNKNOWN }.maxByOrNull { it.height }?.url
                ?: firstOrNull()?.url
    }
}
