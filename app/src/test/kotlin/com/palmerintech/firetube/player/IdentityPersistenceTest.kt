package com.palmerintech.firetube.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.data.sync.TrackSnapshot
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class IdentityPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val text = "שָׁלוֹם العربية Ελληνικά e\u0301 🎵"
    private val identity = TrackIdentity.forSource("fixture", "תיקייה/موسيقى/Ελληνικά.mp3")
    private val track = Track(identity.id, text, text, 123, null, sourceId = identity.sourceId, externalId = identity.externalId, album = text, fileName = "שירים موسيقى Ελληνικά e\u0301.mp3")
    private val json = Json { ignoreUnknownKeys = true }
    private val oldJson = """{"id":"dQw4w9WgXcQ","title":"שלום العربية Ελληνικά","artist":"artist","duration":123,"thumb":null}"""

    @Test fun oldQueueLoadsAndNewQueuePreservesIdentityUnicodeAndPosition() = runBlocking {
        val file = File(context.filesDir, "queue.json")
        file.writeText("""{"tracks":[$oldJson],"index":0,"positionMs":4567}""", Charsets.UTF_8)
        val store = QueueStore(context)
        assertEquals(TrackIdentity.youtube("dQw4w9WgXcQ"), store.load()!!.tracks.single().toTrack().identity)
        store.saveNow(listOf(track), 0, 9876)
        val saved = QueueStore(context).load()!!
        assertEquals(9876L, saved.positionMs)
        assertEquals(track, saved.tracks.single().toTrack())
        file.delete()
        Unit
    }

    @Test fun downloadMetadataReadsLegacyAndRoundTripsNewIdentity() {
        val old = json.decodeFromString<Downloads.StoredTrack>(oldJson).toTrack()
        assertEquals(TrackIdentity.youtube("dQw4w9WgXcQ"), old.identity)
        assertEquals("firetube://track/dQw4w9WgXcQ", MediaItems.uriOf(old.id).toString())
        val encoded = json.encodeToString(Downloads.StoredTrack.serializer(), Downloads.StoredTrack.of(track))
        assertEquals(track, json.decodeFromString<Downloads.StoredTrack>(encoded).toTrack())
    }

    @Test fun backupTrackMetadataReadsLegacyAndPreservesNewIdentity() {
        assertEquals(TrackIdentity.youtube("dQw4w9WgXcQ"), json.decodeFromString<TrackSnapshot>(oldJson).toTrack().identity)
        val encoded = json.encodeToString(TrackSnapshot.serializer(), TrackSnapshot.of(track))
        assertEquals(track, json.decodeFromString<TrackSnapshot>(encoded).toTrack())
    }

    @Test fun realtimeDatabaseMapsReadOldFieldsAndPreserveNewOnes() {
        val old = mapOf<String, Any?>("id" to "dQw4w9WgXcQ", "title" to text)
        assertEquals(TrackIdentity.youtube("dQw4w9WgXcQ"), TrackSnapshot.fromCloudMap(old).toTrack().identity)
        val snapshot = TrackSnapshot.of(track)
        assertEquals(snapshot, TrackSnapshot.fromCloudMap(snapshot.toCloudMap()))
    }

    @Test fun mediaControllerSerializationKeepsIdentityAndUnicode() {
        val item = MediaItem.fromBundle(MediaItems.of(track).toBundle())
        assertEquals(track, MediaItems.trackOf(MediaItems.withUri(item)))
        assertEquals(identity.id, MediaItems.trackIdOf(MediaItems.withUri(item).localConfiguration!!.uri))
    }

    @Test fun uriRoundTripDoesNotSplitOrNormalizeAnOpaqueKey() {
        val id = "שלום/العربية?Ελληνικά#e\u0301"
        assertEquals(id, MediaItems.trackIdOf(MediaItems.uriOf(id)))
    }
}
