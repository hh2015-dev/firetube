package com.palmerintech.firetube.player

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.palmerintech.firetube.extractor.ExtractionException
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.IOException

/**
 * Plays `firetube://track/<videoId>` URIs: resolves the id to a fresh googlevideo URL at open
 * time (on ExoPlayer's loader thread) and streams from it. If YouTube rejects a URL that looked
 * fresh (403/410 — expired or revoked), it re-resolves once and retries.
 *
 * `firetube://live/<videoId>` works the same way, but opens the live stream's HLS manifest. When
 * an id turns out to be the other kind (a saved live stream that has since ended, or a song id
 * that's actually live), it throws [StreamKindException] for the player to swap the item.
 *
 * Because the URI is stable, cache keys are stable too: cached and downloaded audio keeps working
 * after the underlying googlevideo URL has expired.
 */
@UnstableApi
class YouTubeDataSource(
    private val upstream: HttpDataSource,
    private val resolver: StreamResolver,
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val id = MediaItems.trackIdOf(dataSpec.uri) ?: return upstream.open(dataSpec)
        val variant = dataSpec.uri.getQueryParameter("offlineQuality")
        val first = resolveOrThrow(id, forceRefresh = false, variantId = variant)
        val wantsLive = MediaItems.isLiveUri(dataSpec.uri)
        if (first.live != wantsLive) throw StreamKindException(live = first.live)
        return try {
            upstream.open(dataSpec.withUri(first.url.toUri()))
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode != 403 && e.responseCode != 410) throw e
            Timber.w("HTTP %d for %s; re-resolving", e.responseCode, id)
            upstream.close()
            val retry = resolveOrThrow(id, forceRefresh = true, variantId = variant)
            upstream.open(dataSpec.withUri(retry.url.toUri()))
        }
    }

    /** Surfaces extractor failures as IOExceptions so the player reports them as load errors. */
    private fun resolveOrThrow(id: String, forceRefresh: Boolean, variantId: String?) = try {
        runBlocking {
            if (variantId == null) resolver.resolve(id, forceRefresh)
            else resolver.resolveDownloadVariant(id, variantId, forceRefresh)
        }
    } catch (e: ExtractionException) {
        throw StreamUnavailableException(e.permanent, e)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(
        private val upstream: HttpDataSource.Factory,
        private val resolver: StreamResolver,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = YouTubeDataSource(upstream.createDataSource(), resolver)
    }
}

/**
 * ExoPlayer's default policy, except that it doesn't retry what retrying can't fix: a stream of
 * the wrong kind (the service swaps it) or a permanently unavailable one (the service skips it).
 */
@UnstableApi
class StreamErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        var e: Throwable? = info.exception
        while (e != null) {
            if (e is StreamKindException || (e is StreamUnavailableException && e.permanent)) return C.TIME_UNSET
            e = e.cause
        }
        return super.getRetryDelayMsFor(info)
    }
}

/** The id is a live stream when [live], else a regular video: it was queued as the wrong kind. */
class StreamKindException(val live: Boolean) : IOException(if (live) "Is a live stream" else "Is no longer live")

/** A track couldn't be turned into a stream. [permanent]: removed, private, blocked — don't retry. */
class StreamUnavailableException(val permanent: Boolean, cause: Throwable) : IOException(cause.message, cause)
