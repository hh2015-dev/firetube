package com.palmerintech.firetube.extractor

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TrackIdentityTest {
    @Test fun youtubeRetainsLegacyKeysAndShareUrl() {
        val old = "src_abcdefg" // A valid 11-character YouTube id, not our new-key namespace.
        val identity = TrackIdentity.forSource(TrackIdentity.YOUTUBE, old)
        assertEquals(old, identity.id)
        assertEquals(old, identity.externalId)
        assertEquals("https://www.youtube.com/watch?v=$old", Track(old, "", "", 0, null).url)
    }

    @Test fun otherSourcesHaveStableDistinctInternalKeysWithoutChangingUnicode() {
        val external = "תיקייה/موسيقى/Ελληνικά e\u0301 🎵.mp3"
        val a = TrackIdentity.forSource("fixture-a", external)
        assertEquals(a, TrackIdentity.forSource("fixture-a", external))
        assertNotEquals(external, a.id)
        assertNotEquals(a.id, TrackIdentity.forSource("fixture-b", external).id)
        assertNotEquals(a.id, TrackIdentity.forSource("fixture-a", external.replace("e\u0301", "é")).id)
        assertEquals(external, a.externalId)
        assertTrue(a.id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    }

    @Test fun registryOnlyPassesExternalIdAndReturnsInternalIdentity() = runTest {
        val identity = TrackIdentity("opaque-key", "fixture", "provider/123")
        var low = false
        val source = object : AudioSource {
            override val sourceId = "fixture"
            override suspend fun resolve(trackId: String, preferLowBitrate: Boolean): ResolvedStream {
                assertEquals(identity.externalId, trackId)
                low = preferLowBitrate
                return ResolvedStream(trackId, "https://example.invalid/audio", "audio/mp4", 128, Long.MAX_VALUE,
                    track = Track(trackId, "שלום العربية Ελληνικά", "e\u0301", 1, null))
            }
        }
        val resolved = AudioSourceRegistry(listOf(source)).resolve(identity, true)
        assertTrue(low)
        assertEquals(identity.id, resolved.trackId)
        assertEquals(identity, resolved.track!!.identity)
        assertEquals("שלום العربية Ελληνικά", resolved.track.title)
        assertNull(resolved.track.url)
    }

    @Test fun unavailableSourceDoesNotFallBackToYoutube() = runTest {
        try {
            AudioSourceRegistry(emptyList()).resolve(TrackIdentity.forSource("missing", "same-id"))
            fail("An unknown provider must not be sent to YouTube")
        } catch (e: ExtractionException) { assertTrue(e.permanent) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateProvidersAreRejected() {
        val source = object : AudioSource {
            override val sourceId = "fixture"
            override suspend fun resolve(trackId: String, preferLowBitrate: Boolean): ResolvedStream = error("unused")
        }
        AudioSourceRegistry(listOf(source, source))
    }
}
