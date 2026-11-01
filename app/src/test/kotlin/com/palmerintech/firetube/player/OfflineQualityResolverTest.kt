package com.palmerintech.firetube.player

import com.palmerintech.firetube.data.AudioQuality
import com.palmerintech.firetube.extractor.AudioSource
import com.palmerintech.firetube.extractor.AudioSourceRegistry
import com.palmerintech.firetube.extractor.DownloadVariant
import com.palmerintech.firetube.extractor.ResolvedStream
import com.palmerintech.firetube.extractor.TrackIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Download choices are bound to a provider stream; they do not change playback quality. */
class OfflineQualityResolverTest {
    private class Provider : AudioSource {
        override val sourceId = TrackIdentity.YOUTUBE
        var normalCalls = 0
        var offlineCalls = 0
        override suspend fun resolve(trackId: String, preferLowBitrate: Boolean): ResolvedStream {
            normalCalls++
            return ResolvedStream(trackId, "https://example.invalid/high", "audio/mp4", 192, Long.MAX_VALUE)
        }
        override suspend fun downloadVariants(trackId: String) =
            listOf(DownloadVariant("itag:140", 128, "audio/mp4", 3_840_000, false),
                DownloadVariant("itag:249", 50, "audio/webm", 1_500_000, true))
        override suspend fun resolveDownloadVariant(trackId: String, variantId: String): ResolvedStream {
            offlineCalls++
            return ResolvedStream(trackId, "https://example.invalid/$variantId", "audio/webm", 50, Long.MAX_VALUE)
        }
    }

    @Test fun offlineQualityHasItsOwnCacheAndNeverChangesPlayback() = runTest {
        val provider = Provider()
        val resolver = StreamResolver(AudioSourceRegistry(listOf(provider))) { AudioQuality.HIGH }
        val playback = resolver.resolve("test")
        val choices = resolver.downloadVariants("test")
        assertEquals(2, choices.size)
        assertEquals(1, provider.normalCalls)
        val selected = resolver.resolveDownloadVariant("test", "itag:249")
        assertNotEquals(playback.url, selected.url)
        assertEquals(selected, resolver.resolveDownloadVariant("test", "itag:249"))
        assertEquals(playback, resolver.resolve("test"))
        assertEquals(1, provider.offlineCalls)
        assertEquals(1, provider.normalCalls)
        resolver.resolveDownloadVariant("test", "itag:249", forceRefresh = true)
        assertEquals(2, provider.offlineCalls)
    }
}
