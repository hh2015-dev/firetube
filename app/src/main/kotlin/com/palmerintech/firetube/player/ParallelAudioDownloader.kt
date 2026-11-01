package com.palmerintech.firetube.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import timber.log.Timber
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

/**
 * Downloads separate non-overlapping byte ranges into Media3's *existing* offline cache.
 *
 * Parallel mode is enabled only after a real HTTP 206 Content-Range probe supplies an exact
 * total length. Unknown sizes, ignored Range requests, and legacy downloads use Media3's
 * unchanged single-stream downloader. No fragment is ever exposed as a completed download.
 *
 * This is an experiment: multiple HTTP requests can improve a rate-limited connection, but
 * cannot overcome server-side per-account throttling or the device's actual network speed.
 */
@UnstableApi
internal class FastAudioDownloaderFactory(
    private val cache: Cache,
    private val cacheFactory: CacheDataSource.Factory,
    private val probeFactory: DataSource.Factory,
    executor: Executor,
) : DownloaderFactory {
    private val delegate = DefaultDownloaderFactory(cacheFactory, executor)

    override fun createDownloader(request: DownloadRequest): Downloader {
        val standard = delegate.createDownloader(request)
        if (request.uri.getQueryParameter("offlineQuality") == null || request.byteRange != null) {
            return standard
        }
        return ParallelAudioDownloader(request, cache, cacheFactory, probeFactory, standard)
    }
}

@UnstableApi
internal class ParallelAudioDownloader(
    private val request: DownloadRequest,
    private val cache: Cache,
    private val cacheFactory: CacheDataSource.Factory,
    private val probeFactory: DataSource.Factory,
    private val standard: Downloader,
) : Downloader {
    @Volatile private var cancelled = false
    private val active = CopyOnWriteArrayList<Downloader>()
    private val threads = CopyOnWriteArrayList<Thread>()

    override fun download(progressListener: Downloader.ProgressListener?) {
        if (cancelled) throw InterruptedIOException("Download cancelled")

        val total = probeExactRangeLength() ?: return standard.download(progressListener)
        val ranges = ParallelAudioRanges.split(total)
        if (ranges.size == 1) return standard.download(progressListener)

        val mediaItem = request.toMediaItem()
        val cacheKey = requireNotNull(request.customCacheKey) {
            "Parallel download must use the same explicit key as the playback download cache"
        }
        val progress = LongArray(ranges.size)
        val progressLock = Any()
        val failures = ConcurrentLinkedQueue<Throwable>()

        for ((index, range) in ranges.withIndex()) {
            // A synchronous executor makes each ProgressiveDownloader run on this worker,
            // rather than waiting on a nested executor that could deadlock.
            val downloader = ProgressiveDownloader(mediaItem, cacheFactory, Executor { it.run() },
                range.position, range.length)
            active += downloader
            val worker = Thread({
                try {
                    downloader.download(Downloader.ProgressListener { _, bytesDownloaded, _ ->
                        synchronized(progressLock) {
                            progress[index] = bytesDownloaded.coerceIn(0, range.length)
                            val bytes = progress.sum().coerceAtMost(total)
                            progressListener?.onProgress(total, bytes, 100f * bytes / total)
                        }
                    })
                } catch (e: Throwable) {
                    failures.add(e)
                    // Stop the other slices quickly; downloaded, valid cache spans remain resumable.
                    active.forEach { it.cancel() }
                }
            }, "FireTube-range-${index + 1}")
            threads += worker
        }
        Timber.i("Offline range test: %d HTTP parts for %d bytes", ranges.size, total)
        threads.forEach { it.start() }
        try {
            threads.forEach { it.join() }
        } catch (e: InterruptedException) {
            cancel()
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Parallel download interrupted").apply { initCause(e) }
        } finally {
            active.clear()
            threads.clear()
        }

        failures.peek()?.let { throw IOException("Parallel audio download failed", it) }
        if (cancelled) throw InterruptedIOException("Download cancelled")
        // A successful worker call alone is insufficient: insist all byte ranges were cached.
        if (!cache.isCached(cacheKey, 0, total)) {
            throw IOException("Incomplete audio cache: refusing to mark download completed")
        }
        val metadata = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(metadata, total)
        cache.applyContentMetadataMutations(cacheKey, metadata)
        progressListener?.onProgress(total, total, 100f)
    }

    /**
     * Probe *over the real upstream* (not CacheDataSource): a byte-range GET must include
     * `Content-Range: bytes 0-0/<length>`. A 200 response never authorizes parallel mode.
     */
    private fun probeExactRangeLength(): Long? {
        val source = probeFactory.createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(request.uri).setPosition(0).setLength(1).build())
            val header = source.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()
            ParallelAudioRanges.totalFromProbe(header)
        } catch (e: IOException) {
            Timber.w(e, "Range preflight unavailable; falling back to sequential download")
            null
        } finally {
            runCatching { source.close() }
        }
    }

    override fun cancel() {
        cancelled = true
        standard.cancel()
        active.forEach { it.cancel() }
        threads.forEach { it.interrupt() }
    }

    override fun remove() = standard.remove()
}

/** Pure, independently testable range calculations. */
internal object ParallelAudioRanges {
    data class Part(val position: Long, val length: Long)

    private const val MIN_PARALLEL_BYTES = 2L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
    private val RANGE_HEADER = Regex("""bytes 0-0/(\d+)""", RegexOption.IGNORE_CASE)

    fun totalFromProbe(contentRange: String?): Long? {
        val total = contentRange?.trim()?.let { RANGE_HEADER.matchEntire(it)?.groupValues?.get(1) }
            ?.toLongOrNull() ?: return null
        return total.takeIf { it in 1..MAX_TOTAL_BYTES }
    }

    fun split(totalBytes: Long): List<Part> {
        require(totalBytes > 0)
        val count = if (totalBytes >= MIN_PARALLEL_BYTES) 3 else 1
        val baseSize = totalBytes / count
        val remainder = totalBytes % count
        var position = 0L
        return List(count) { index ->
            val size = baseSize + if (index < remainder) 1 else 0
            Part(position, size).also { position += size }
        }
    }
}
