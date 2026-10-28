package com.palmerintech.firetube.local

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.wav.WavExtractor
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.data.AudioQuality
import com.palmerintech.firetube.extractor.AudioSourceRegistry
import com.palmerintech.firetube.extractor.ExtractionException
import com.palmerintech.firetube.extractor.TrackIdentity
import com.palmerintech.firetube.player.MediaItems
import com.palmerintech.firetube.player.MediaStack
import com.palmerintech.firetube.player.StreamResolver
import com.palmerintech.firetube.player.StreamUnavailableException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@androidx.media3.common.util.UnstableApi
class LocalAudioTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = AudioProvider()
    private val audio = LocalAudio(context)

    @Before fun setup() {
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        Shadows.shadowOf(context as Application).grantPermissions(LocalAudio.permission())
    }

    @After fun cleanup() {
        File(context.cacheDir, "media").deleteRecursively()
        File(context.filesDir, "downloads").deleteRecursively()
    }

    @Test fun scanPreservesUnicodeTagsFileNamesAndVolumeIdentity() = runBlocking {
        val track = audio.scan().single()
        assertEquals(TITLE, track.title)
        assertEquals(FILE_NAME, track.fileName)
        assertEquals(ARTIST, track.artist)
        assertEquals(ALBUM, track.album)
        assertEquals(TrackIdentity.LOCAL, track.sourceId)
        assertTrue(track.externalId.startsWith("content://media/external_primary/audio/media/42?"))
        assertEquals(track.identity, audio.scan().single().identity)
        assertTrue(provider.selection!!.contains("is_pending=0"))
        assertTrue(provider.selection!!.contains("is_trashed=0"))
        assertFalse(provider.selection!!.contains("is_music"))
    }

    @Test fun emptyTitleFallsBackToOriginalFilenameWithoutRepairingText() = runBlocking {
        provider.title = ""
        assertEquals(FILE_NAME, audio.scan().single().title)
    }

    @Test fun permissionDenialIsExplicit() = runBlocking {
        Shadows.shadowOf(context as Application).denyPermissions(LocalAudio.permission())
        assertFalse(audio.hasPermission())
        try { audio.scan(); fail("Permission must be requested first") } catch (_: SecurityException) { }
    }

    @Test fun modernAndroidRequestsAudioOnlyPermission() {
        assertEquals("android.permission.READ_MEDIA_AUDIO",LocalAudio.permission())
    }

    @Test @Config(sdk = [28]) fun legacyAndroidUsesExternalVolumeAndDateAdded() = runBlocking {
        assertEquals("android.permission.READ_EXTERNAL_STORAGE",LocalAudio.permission())
        val track = audio.scan().single()
        assertTrue(track.externalId.startsWith("content://media/external/audio/media/42?added="))
        assertFalse(provider.selection?.contains("is_pending") == true)
        assertEquals(track.id,audio.resolve(track.externalId).trackId)
    }

    @Test fun removedMovedRecycledAndForeignDeviceReferencesAreRejected() = runBlocking {
        val track = audio.scan().single()
        provider.removed = true
        expectUnavailable { audio.resolve(track.externalId) }
        provider.removed = false
        provider.generation++
        expectUnavailable { audio.resolve(track.externalId) }
        provider.generation--
        val foreign = Uri.parse(track.externalId).buildUpon().clearQuery().appendQueryParameter("generation", "7").appendQueryParameter("device", "other-device").build()
        expectUnavailable { audio.resolve(foreign.toString()) }
        expectUnavailable { audio.resolve("file:///sdcard/music.mp3") }
        expectUnavailable { audio.resolve("content://other.provider/audio/42?generation=7") }
    }

    @Test fun fiveFormatsReachMedia3ExtractorsThroughContentWithoutPopulatingCaches() = runBlocking {
        val resolver = StreamResolver(AudioSourceRegistry(listOf(audio))) { AudioQuality.HIGH }
        val stack = MediaStack(context, OkHttpClient(), resolver, 64) { "HIGH" }
        try {
            for ((ext, extractor) in listOf<Pair<String, Extractor>>("mp3" to Mp3Extractor(), "m4a" to Mp4Extractor(), "flac" to FlacExtractor(), "ogg" to OggExtractor(), "wav" to WavExtractor())) {
                provider.extension = ext
                val track = audio.scan().single()
                resolver.remember(track)
                assertTrue(MediaItems.isLocalUri(MediaItems.of(track).localConfiguration!!.uri))
                val source = stack.playbackFactory.createDataSource()
                try {
                    val length = source.open(DataSpec.Builder().setUri(MediaItems.of(track).localConfiguration!!.uri).build())
                    assertTrue("Media3 must recognize $ext",extractor.sniff(DefaultExtractorInput(source,0,length)))
                } finally { source.close(); extractor.release() }
            }
            assertEquals(0L, stack.streamCache.cacheSpace)
            assertEquals(0L, stack.downloadCache.cacheSpace)
        } finally { stack.streamCache.release();stack.downloadCache.release();stack.database.close() }
    }

    @Test fun seekReadsOriginalBytesAndRevocationOnNextOpenBecomesPlaybackError() = runBlocking {
        val resolver = StreamResolver(AudioSourceRegistry(listOf(audio))) { AudioQuality.HIGH }
        val track = audio.scan().single().also(resolver::remember)
        val stack = MediaStack(context,OkHttpClient(),resolver,64) { "HIGH" }
        val source = stack.playbackFactory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(MediaItems.of(track).localConfiguration!!.uri).setPosition(10).setLength(20).build())
            val bytes=ByteArray(20)
            assertEquals(20,source.read(bytes,0,20))
            assertArrayEquals(provider.file.readBytes().copyOfRange(10,30),bytes)
            source.close()
            Shadows.shadowOf(context as Application).denyPermissions(LocalAudio.permission())
            try { source.open(DataSpec.Builder().setUri(MediaItems.of(track).localConfiguration!!.uri).build());fail("Must recheck access") }
            catch (e: StreamUnavailableException) { assertTrue(e.permanent) }
        } finally { source.close();stack.streamCache.release();stack.downloadCache.release();stack.database.close() }
    }

    @Test fun fileOrPermissionLostBetweenQueryAndOpenIsAPlaybackError() = runBlocking {
        val resolver=StreamResolver(AudioSourceRegistry(listOf(audio))) { AudioQuality.HIGH }
        val track=audio.scan().single().also(resolver::remember)
        val stack=MediaStack(context,OkHttpClient(),resolver,64) { "HIGH" }
        try {
            repeat(2) { attempt ->
                provider.failOpen = attempt == 0
                provider.denyOpen = attempt == 1
                val source=stack.playbackFactory.createDataSource()
                try {
                    source.open(DataSpec.Builder().setUri(MediaItems.of(track).localConfiguration!!.uri).build())
                    fail("An inaccessible file must be reported to Media3")
                } catch (e: StreamUnavailableException) { assertTrue(e.permanent) }
                finally { source.close() }
            }
        } finally { stack.streamCache.release();stack.downloadCache.release();stack.database.close() }
    }

    @Test fun localMetadataSurvivesLibraryFavoritesMixedPlaylistQueueAndBackup() = runBlocking {
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context, com.palmerintech.firetube.data.db.FireTubeDatabase::class.java).build()
        val targetDb=androidx.room.Room.inMemoryDatabaseBuilder(context, com.palmerintech.firetube.data.db.FireTubeDatabase::class.java).build()
        try {
            val library=com.palmerintech.firetube.data.LibraryRepository(db.library())
            val track=audio.scan().single()
            library.saveTracks(listOf(track))
            library.toggleFavorite(track)
            val youtube=com.palmerintech.firetube.extractor.Track("dQw4w9WgXcQ", "YouTube", "Artist", 10, null)
            val playlist=library.createPlaylist("Mixed עברית العربية Ελληνικά", listOf(youtube,track))
            assertEquals(listOf(youtube,track),library.playlistTracksOnce(playlist))
            assertEquals(track,library.knownTrack(track.id))
            val queue=com.palmerintech.firetube.player.QueueStore(context)
            queue.saveNow(listOf(youtube,track),1,123)
            assertEquals(listOf(youtube,track),queue.load()!!.tracks.map { it.toTrack() })
            assertEquals(track,MediaItems.trackOf(MediaItems.of(track)))
            val bytes=java.io.ByteArrayOutputStream().also { com.palmerintech.firetube.data.sync.Backup(library).export(it) }.toByteArray()
            val target=com.palmerintech.firetube.data.LibraryRepository(targetDb.library())
            com.palmerintech.firetube.data.sync.Backup(target).import(bytes.inputStream())
            assertEquals(listOf(youtube,track),target.playlistTracksOnce(playlist))
        } finally { db.close();targetDb.close();File(context.filesDir,"queue.json").delete() }
    }

    @Test fun id3v23Utf16TitleIsDecodedWithoutLosingArabicHebrewOrGreek() {
        val bytes=File("src/test/resources/local-audio/tone.mp3").readBytes()
        assertEquals(3,bytes[3].toInt())
        val metadata=Id3Decoder().decode(bytes,bytes.size)!!
        val frame=(0 until metadata.length()).map { metadata[it] }.filterIsInstance<TextInformationFrame>().first { it.id=="TIT2" }
        assertEquals("שָׁלוֹם العربية Ελληνικά English",frame.values.single())
    }

    private suspend fun expectUnavailable(block: suspend () -> Unit) {
        try { block(); fail("Expected unavailable") } catch (_: ExtractionException) { }
    }

    class AudioProvider : ContentProvider() {
        var selection: String? = null
        var title = TITLE
        var removed = false
        var failOpen = false
        var denyOpen = false
        var generation = 7L
        var extension = "wav"
        val file get() = File("src/test/resources/local-audio/tone.$extension")
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            this.selection=selection
            val columns=projection!!
            return MatrixCursor(columns).apply {
                if (!removed) addRow(columns.map { c -> when(c) {
                    MediaStore.Audio.Media._ID -> 42L
                    MediaStore.Audio.Media.TITLE -> title
                    MediaStore.Audio.Media.DISPLAY_NAME -> FILE_NAME
                    MediaStore.Audio.Media.ARTIST -> ARTIST
                    MediaStore.Audio.Media.ALBUM -> ALBUM
                    MediaStore.Audio.Media.DURATION -> 150L
                    MediaStore.Audio.Media.MIME_TYPE -> "audio/$extension"
                    MediaStore.Audio.Media.DATE_ADDED -> 5L
                    MediaStore.Audio.Media.GENERATION_ADDED -> generation
                    MediaStore.Audio.Media.VOLUME_NAME -> "external_primary"
                    else -> null
                } }.toTypedArray<Any?>())
            }
        }
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            if (failOpen) throw java.io.FileNotFoundException("File was moved")
            if (denyOpen) throw SecurityException("Permission revoked")
            return AssetFileDescriptor(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY),0,file.length())
        }
        override fun getType(uri: Uri) = "audio/$extension"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("read only")
    }

    companion object {
        const val TITLE = "שָׁלוֹם العربية Ελληνικά English e\u0301 🎵"
        const val FILE_NAME = "עברית العربية Ελληνικά English e\u0301.mp3"
        const val ARTIST = "אמן فنان Καλλιτέχνης Artist"
        const val ALBUM = "אלבום ألبوم Άλμπουμ Album"
    }
}
