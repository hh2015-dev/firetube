package com.palmerintech.firetube.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MediaItemsTest {
    private val song = Track("song", "Song", "Artist", 200, null)
    private val radio = Track("radio", "Radio", "Channel", 0, null, isLive = true)

    @Test
    fun songsPlayThroughTheTrackUri() {
        val item = MediaItems.of(song)
        assertEquals(MediaItems.uriOf("song"), item.localConfiguration?.uri)
        assertNull(item.localConfiguration?.mimeType)
        assertFalse(MediaItems.isLive(item))
        assertFalse(MediaItems.isLiveUri(item.localConfiguration!!.uri))
    }

    @Test
    fun liveStreamsPlayAsHls() {
        val item = MediaItems.of(radio)
        val uri = item.localConfiguration!!.uri
        assertTrue(MediaItems.isLiveUri(uri))
        assertEquals("radio", MediaItems.trackIdOf(uri))
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration?.mimeType)
        assertTrue(MediaItems.trackOf(item).isLive)
    }

    @Test
    fun liveSurvivesLosingTheUri() {
        // As from Android Auto or another controller: metadata only, no URI.
        val bare = MediaItem.fromBundle(MediaItems.of(radio).toBundle())
        assertNull(bare.localConfiguration)
        val rebuilt = MediaItems.withUri(bare)
        assertTrue(MediaItems.isLiveUri(rebuilt.localConfiguration!!.uri))
        assertEquals(MimeTypes.APPLICATION_M3U8, rebuilt.localConfiguration?.mimeType)
        assertEquals("Radio", rebuilt.mediaMetadata.title)
    }

    @Test
    fun songsSurviveLosingTheUri() {
        val bare = MediaItem.fromBundle(MediaItems.of(song).toBundle())
        assertEquals(MediaItems.uriOf("song"), MediaItems.withUri(bare).localConfiguration?.uri)
    }

    @Test
    fun documentAudioKeepsIdentityThroughMedia3ControllerRoundTrip() {
        val identity = TrackIdentity.forSource(TrackIdentity.DOCUMENT, "content://com.example.documents/document/audio%3A1")
        val track = Track(identity.id, "صوت • Ελληνικά", "", 0, null,
            sourceId = TrackIdentity.DOCUMENT, externalId = identity.externalId)
        val item = MediaItems.of(track)
        val uri = item.localConfiguration!!.uri
        assertTrue(MediaItems.isDocumentUri(uri))
        assertFalse(MediaItems.isLocalUri(uri))
        assertEquals(identity.id, MediaItems.trackIdOf(uri))
        val bare = MediaItem.fromBundle(item.toBundle())
        val restored = MediaItems.withUri(bare)
        assertEquals(uri, restored.localConfiguration?.uri)
        assertEquals(TrackIdentity.DOCUMENT, MediaItems.trackOf(restored).sourceId)
        assertEquals(identity.externalId, MediaItems.trackOf(restored).externalId)
    }

    @Test
    fun otherExtrasDontMakeALiveStream() {
        val item = MediaItems.of(song).let {
            it.buildUpon().setMediaMetadata(it.mediaMetadata.buildUpon().setExtras(Bundle().apply { putString("x", "y") }).build()).build()
        }
        assertFalse(MediaItems.isLive(item))
    }
}
