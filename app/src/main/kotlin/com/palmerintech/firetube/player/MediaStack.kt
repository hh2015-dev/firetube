package com.palmerintech.firetube.player

import android.content.Context
import com.palmerintech.firetube.drive.DriveLibrary
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.io.File

/**
 * Shared Media3 plumbing: the streaming cache (LRU, recently played songs replay without the
 * network), the downloads cache (never evicted) and the data-source chain that reads through both.
 */
@UnstableApi
class MediaStack(
    context: Context,
    httpClient: OkHttpClient,
    resolver: StreamResolver,
    streamCacheMb: Int,
    private val driveLibrary: DriveLibrary? = null,
    /** Current audio quality tag; part of the song-cache key so different bitrates never mix. */
    private val qualityTag: () -> String,
) {
    private val databaseProvider = StandaloneDatabaseProvider(context)

    val streamCache = SimpleCache(
        File(context.cacheDir, "media"),
        LeastRecentlyUsedCacheEvictor(streamCacheMb.coerceAtLeast(64) * 1024L * 1024L),
        databaseProvider,
    )

    val downloadCache = SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), databaseProvider)

    val database get() = databaseProvider

    /** Network: firetube:// ids → fresh googlevideo URLs over OkHttp. */
    val networkFactory: DataSource.Factory = YouTubeDataSource.Factory(OkHttpDataSource.Factory(httpClient), resolver)

    /**
     * Default downloads reuse already streamed bytes instead of fetching them a second time.
     * Explicitly chosen encodings never consult the stream cache: a same-track cache entry may
     * hold a different bitrate, which would otherwise corrupt the downloaded audio.
     */
    val downloadUpstreamFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(streamCache)
        .setCacheWriteDataSinkFactory(null)
        .setUpstreamDataSourceFactory(networkFactory)
        .setCacheKeyFactory { spec ->
            if (spec.uri.getQueryParameter("offlineQuality") != null) {
                "offline-uncached:${spec.key ?: spec.uri}"
            } else "${spec.key ?: spec.uri}@${qualityTag()}"
        }
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Playback: downloads (read-only, plain keys) → stream cache (keyed by id + quality) → network. */
    private val cachedFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(downloadCache)
        .setCacheWriteDataSinkFactory(null)
        .setUpstreamDataSourceFactory(
            CacheDataSource.Factory()
                .setCache(streamCache)
                .setUpstreamDataSourceFactory(networkFactory)
                .setCacheKeyFactory { spec -> "${spec.key ?: spec.uri}@${qualityTag()}" }
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        )
    val playbackFactory: DataSource.Factory = DataSource.Factory {
        PlaybackDataSource(cachedFactory.createDataSource(), LocalPlaybackDataSource(context, resolver),
            DocumentPlaybackDataSource(context, resolver),
            driveLibrary?.let { DrivePlaybackDataSource(it, httpClient, resolver) })
    }
}
