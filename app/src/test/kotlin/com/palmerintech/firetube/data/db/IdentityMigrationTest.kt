package com.palmerintech.firetube.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.data.LibraryRepository
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.extractor.TrackIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class IdentityMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var db: FireTubeDatabase? = null
    private val title = "שָׁלוֹם — العربية — Ελληνικά e\u0301 🎵"
    private val id = "dQw4w9WgXcQ"

    @After fun close() { db?.close(); context.deleteDatabase(NAME) }

    private fun migrate(version: Int) = runBlocking {
        val schema = Json.parseToJsonElement(File("schemas/com.palmerintech.firetube.data.db.FireTubeDatabase/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME).apply { parentFile?.mkdirs() }, null).use { old ->
            schema.getValue("entities").jsonArray.forEach { entity ->
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                old.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { old.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            if (version == 3) old.execSQL("INSERT INTO tracks VALUES (?, ?, ?, 213, NULL, 'youtube', ?)", arrayOf(id, title, "אמן / فنان / Καλλιτέχνης", id))
            else old.execSQL("INSERT INTO tracks VALUES (?, ?, ?, 213, NULL)", arrayOf(id, title, "אמן / فنان / Καλλιτέχνης"))
            old.execSQL("INSERT INTO playlists VALUES ('favorites', 'Favorites', 10, 0, NULL)")
            old.execSQL("INSERT INTO playlists VALUES ('list', 'قائمة שירים', 11, 0, 'https://www.youtube.com/playlist?list=old')")
            old.execSQL("INSERT INTO playlists VALUES ('deleted', 'old', 12, 1, NULL)")
            old.execSQL("INSERT INTO playlist_tracks VALUES ('favorites', 0, ?)", arrayOf(id))
            old.execSQL("INSERT INTO playlist_tracks VALUES ('list', 0, ?)", arrayOf(id))
            old.execSQL("INSERT INTO history (trackId, playedAt) VALUES (?, 1000)", arrayOf(id))
            if (version >= 2) old.execSQL("INSERT INTO plays VALUES (?, 2000, 65000)", arrayOf(id))
            old.version = version
        }
        // Opening through Room validates the entire migrated schema, including defaults and FKs.
        val current = Room.databaseBuilder(context, FireTubeDatabase::class.java, NAME)
            .addMigrations(*FireTubeDatabase.MIGRATIONS).build().also { db = it }
        val dao = current.library()
        val track = dao.track(id)!!.toTrack()
        assertEquals(TrackIdentity.youtube(id), track.identity)
        assertEquals(title, track.title)
        assertEquals(listOf(id), dao.playlistTracks("list").map { it.id })
        assertEquals(listOf(id), dao.playlistTracks("favorites").map { it.id })
        assertTrue(dao.playlist("deleted")!!.deleted)
        assertEquals(listOf(id), dao.recent(10).map { it.id })
        assertEquals(if (version >= 2) 65000L else 0L, dao.observeTrackPlays(0).first().sumOf { it.msListened })
        current.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        val other = TrackIdentity.forSource("fixture", id)
        val another = Track(other.id, title, "artist", 213, null, sourceId = other.sourceId, externalId = other.externalId)
        LibraryRepository(dao).addToPlaylist("list", listOf(another))
        val tracks = dao.playlistTracks("list").map { it.toTrack() }
        assertEquals(listOf(id, other.id), tracks.map { it.id })
        assertEquals(other, tracks.last().identity)
    }

    @Test fun version1PreservesLibraryFavoritesHistoryAndUnicode() = migrate(1)
    @Test fun version2AlsoPreservesListeningStats() = migrate(2)
    @Test fun version3PreservesIdentitiesWhenAddingLocalMetadataColumns() = migrate(3)

    private companion object { const val NAME = "identity-migration.db" }
}
