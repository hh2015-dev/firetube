package com.palmerintech.firetube.player

import com.palmerintech.firetube.data.AudioQuality
import com.palmerintech.firetube.extractor.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SourceRoutingTest {
    private class Provider(override val sourceId: String) : AudioSource {
        val calls = mutableListOf<Pair<String, Boolean>>()
        override suspend fun resolve(trackId: String, preferLowBitrate: Boolean): ResolvedStream {
            calls += trackId to preferLowBitrate
            return ResolvedStream(trackId, "https://example.invalid/audio/${calls.size}", "audio/mp4", 128, Long.MAX_VALUE)
        }
    }

    @Test fun restartLooksUpPersistedIdentityAndCachesByInternalKeyAndQuality() = runTest {
        val youtube = Provider(TrackIdentity.YOUTUBE)
        val other = Provider("fixture")
        val identity = TrackIdentity.forSource(other.sourceId, "same-id")
        var quality = AudioQuality.HIGH
        val resolver = StreamResolver(AudioSourceRegistry(listOf(youtube, other)), { if (it == identity.id) identity else null }) { quality }
        val first = resolver.resolve(identity.id)
        assertEquals(identity.id, first.trackId)
        assertEquals(first, resolver.resolve(identity.id))
        assertEquals(listOf("same-id" to false), other.calls)
        resolver.resolve("same-id")
        assertEquals(listOf("same-id" to false), youtube.calls)
        quality = AudioQuality.DATA_SAVER
        resolver.resolve(identity.id)
        assertEquals("same-id" to true, other.calls.last())
        resolver.resolve(identity.id, forceRefresh = true)
        assertEquals(3, other.calls.size)
    }

    @Test fun queuedMetadataRoutesBeforeAnythingIsSavedToRoom() = runTest {
        val provider = Provider("fixture")
        val id = TrackIdentity.forSource("fixture", "שלום/العربية/Ελληνικά")
        val resolver = StreamResolver(AudioSourceRegistry(listOf(provider))) { AudioQuality.HIGH }
        resolver.remember(Track(id.id, "title", "artist", 1, null, sourceId = id.sourceId, externalId = id.externalId))
        resolver.resolve(id.id)
        assertEquals(id.externalId, provider.calls.single().first)
    }

    @Test fun legacyYoutubeIdBeginningWithSrcStillResolves() = runTest {
        val youtube = Provider(TrackIdentity.YOUTUBE)
        val resolver = StreamResolver(AudioSourceRegistry(listOf(youtube))) { AudioQuality.HIGH }
        resolver.resolve("src_abcdefg")
        assertEquals("src_abcdefg", youtube.calls.single().first)
    }

    @Test fun unknownGeneratedIdNeverFallsBackToYoutube() = runTest {
        val youtube = Provider(TrackIdentity.YOUTUBE)
        val resolver = StreamResolver(AudioSourceRegistry(listOf(youtube))) { AudioQuality.HIGH }
        val id = TrackIdentity.forSource("missing", "external").id
        resolver.remember(Track(id, "controller without extras", "", 0, null))
        try {
            resolver.resolve(id)
            fail("Missing identity must be reported")
        } catch (_: ExtractionException) { assertTrue(youtube.calls.isEmpty()) }
    }
}
