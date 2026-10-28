package com.palmerintech.firetube.player

import com.palmerintech.firetube.data.AudioQuality
import com.palmerintech.firetube.extractor.Chapter
import com.palmerintech.firetube.extractor.ExtractionException
import com.palmerintech.firetube.extractor.ResolvedStream
import com.palmerintech.firetube.extractor.StreamSource
import com.palmerintech.firetube.extractor.AudioSourceRegistry
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns track ids into playable URLs just before they're needed, caching results until they
 * expire. Concurrent requests for the same id share one network call.
 */
class StreamResolver(
    private val sources: AudioSourceRegistry,
    private val lookupIdentity: suspend (String) -> TrackIdentity? = { null },
    /** Chapters of downloaded songs, for those played without being resolved. */
    private val stored: ChapterStore? = null,
    private val quality: suspend () -> AudioQuality,
) {
    constructor(source: StreamSource, stored: ChapterStore? = null, quality: suspend () -> AudioQuality) :
        this(AudioSourceRegistry(listOf(source)), stored = stored, quality = quality)

    private val identities = ConcurrentHashMap<String, TrackIdentity>()

    fun remember(track: Track) {
        // A controller may lose extras. Do not turn an unknown internal key into a YouTube id;
        // let the persistent lookup recover it, or report missing identity at resolve time.
        if (TrackIdentity.isGeneratedId(track.id) && track.sourceId == TrackIdentity.YOUTUBE) return
        identities[track.id] = track.identity
    }

    private suspend fun identityOf(id: String): TrackIdentity = identities[id] ?: lookupIdentity(id)?.also {
        identities[id] = it
    } ?: if (TrackIdentity.isGeneratedId(id)) {
        throw ExtractionException("Unknown track identity: $id")
    } else TrackIdentity.youtube(id)

    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Chapters by track id, kept after the URL expires (they don't change); only tracks that have some. */
    private val chapters = MutableStateFlow<Map<String, List<Chapter>>>(emptyMap())

    suspend fun resolve(trackId: String, forceRefresh: Boolean = false): ResolvedStream {
        // Keyed by quality too, so a URL resolved at the old quality is never served after a switch.
        val q = quality()
        val key = "$trackId@$q"
        if (!forceRefresh) fresh(key)?.let { return it }
        return locks.getOrPut(key) { Mutex() }.withLock {
            if (!forceRefresh) fresh(key)?.let { return@withLock it }
            sources.resolve(identityOf(trackId), preferLowBitrate = q == AudioQuality.DATA_SAVER)
                .also { cache[key] = it; keepChapters(trackId, it.chapters) }
        }
    }

    /** Related tracks from the last resolution, if any (used for autoplay without another request). */
    fun cachedRelated(trackId: String) = AudioQuality.entries.firstNotNullOfOrNull { cache["$trackId@$it"] }?.related

    /**
     * [trackId]'s chapters from when it was last resolved; empty until then, or when it has none.
     * A download that hasn't been resolved since the app started gets those saved with it; a song
     * played entirely from the cache otherwise isn't resolved, so shows none.
     */
    fun chapters(trackId: String): Flow<List<Chapter>> = flow {
        if (stored != null && trackId !in chapters.value) {
            val saved = withContext(Dispatchers.IO) { stored.load(trackId) }.orEmpty()
            // Unless a resolve got there first: those are newer.
            if (saved.isNotEmpty()) chapters.update { all -> if (trackId in all) all else withChapters(all, trackId, saved) }
        }
        emitAll(chapters.map { it[trackId].orEmpty() })
    }.distinctUntilChanged()

    /** The chapters known for [trackId] right now, without reading the store; null when none. */
    fun knownChapters(trackId: String): List<Chapter>? = chapters.value[trackId]

    private fun keepChapters(trackId: String, list: List<Chapter>) = chapters.update { all ->
        when {
            list.isNotEmpty() -> withChapters(all, trackId, list)
            trackId in all -> all - trackId
            else -> all
        }
    }

    // Re-added at the end, so the least recently resolved is the first dropped.
    private fun withChapters(all: Map<String, List<Chapter>>, trackId: String, list: List<Chapter>) =
        (all - trackId + (trackId to list)).let { if (it.size > MAX_CHAPTERED) it - it.keys.first() else it }

    fun invalidate(trackId: String) {
        AudioQuality.entries.forEach { cache.remove("$trackId@$it") }
    }

    /** Drops every resolved URL, e.g. after the audio quality setting changes. */
    fun clear() = cache.clear()

    private fun fresh(key: String) =
        cache[key]?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }

    private companion object {
        /** Tracks whose chapters are kept in memory. */
        const val MAX_CHAPTERED = 50
    }
}
