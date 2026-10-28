package com.palmerintech.firetube.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.data.LibraryRepository
import com.palmerintech.firetube.data.sync.Backup
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class IdentityBackupTest {
    @Test fun importsOldBackupThenExportsAndRestoresAMixedIdentityPlaylist() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FireTubeDatabase::class.java).build()
        val restored = Room.inMemoryDatabaseBuilder(context, FireTubeDatabase::class.java).build()
        try {
            val library = LibraryRepository(db.library())
            val old = """{"format":"firetube-backup","version":1,"playlists":[{"id":"favorites","name":"Favorites","updatedAt":1,"tracks":[{"id":"dQw4w9WgXcQ","title":"שָׁלוֹם العربية Ελληνικά é","artist":"artist","duration":123}]}]}"""
            assertEquals(1, Backup(library).import(old.byteInputStream(Charsets.UTF_8)))
            val youtube = library.favorites.first().single()
            assertEquals(TrackIdentity.youtube("dQw4w9WgXcQ"), youtube.identity)
            val identity = TrackIdentity.forSource("fixture", youtube.id)
            val track = Track(identity.id, youtube.title, "אמן فنان Καλλιτέχνης", 123, null,
                sourceId = identity.sourceId, externalId = identity.externalId)
            library.addToPlaylist("favorites", listOf(track))
            val bytes = ByteArrayOutputStream().also { Backup(library).export(it) }.toByteArray()
            val target = LibraryRepository(restored.library())
            assertEquals(1, Backup(target).import(bytes.inputStream()))
            assertEquals(listOf(youtube, track), target.favorites.first())
        } finally { db.close(); restored.close() }
    }
}
