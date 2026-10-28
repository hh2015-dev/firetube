package com.palmerintech.firetube.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.data.LibraryRepository
import com.palmerintech.firetube.data.sync.Backup
import com.palmerintech.firetube.data.sync.TrackSnapshot
import com.palmerintech.firetube.data.sync.allSnapshots
import com.palmerintech.firetube.extractor.TrackIdentity
import com.palmerintech.firetube.player.Downloads
import com.palmerintech.firetube.player.MediaItems
import com.palmerintech.firetube.player.QueueStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UserLibrarySafetyTest {
    @Test fun migratesRealisticLibraryWithoutChangingAnyExistingColumnOrDownloadReference() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "user-library-safety.db"
        val title = "שָׁלוֹם العربية Ελληνικά English e\u0301 🎵"
        val ids = (0 until 120).map { "yt" + it.toString().padStart(9, '0') }
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val snapshots = linkedMapOf<String, List<List<String?>>>()
        fun rows(db: SQLiteDatabase, table: String): List<List<String?>> = db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }
        val schema = Json.parseToJsonElement(File("schemas/com.palmerintech.firetube.data.db.FireTubeDatabase/2.json").readText()).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            schema.getValue("entities").jsonArray.forEach { value ->
                val e = value.jsonObject; val table = e.getValue("tableName").jsonPrimitive.content
                old.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { old.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.beginTransaction()
            try {
                ids.forEachIndexed { i, id ->
                    old.execSQL("INSERT INTO tracks VALUES (?, ?, ?, ?, NULL)", arrayOf<Any>(id, "$title $i", "אמן فنان Artist", 100+i))
                    repeat(3) { old.execSQL("INSERT INTO history(trackId, playedAt) VALUES (?, ?)", arrayOf<Any>(id, i*1000+it)) }
                    old.execSQL("INSERT INTO plays VALUES (?, ?, ?)", arrayOf<Any>(id, i*1000, 40000+i))
                }
                (listOf("favorites", "deleted") + (0..7).map { "list$it" }).forEachIndexed { p, list ->
                    old.execSQL("INSERT INTO playlists VALUES (?, ?, ?, ?, NULL)", arrayOf<Any>(list, "$title $p", p, if (list=="deleted") 1 else 0))
                    ids.filterIndexed { i, _ -> i % 3 == p % 3 }.reversed().forEachIndexed { i, id -> old.execSQL("INSERT INTO playlist_tracks VALUES (?, ?, ?)", arrayOf<Any>(list, i, id)) }
                }
                old.setTransactionSuccessful()
            } finally { old.endTransaction() }
            listOf("tracks", "playlists", "playlist_tracks", "history", "plays").forEach { snapshots[it]=rows(old,it) }
            old.version=2
        }
        val legacyTracks = ids.take(20).mapIndexed { i,id -> QueueStore.SavedTrack(id,"$title $i","אמן פنان Artist",100L+i,null) }
        val queue = QueueStore(context)
        val oldJson = Json.encodeToString(QueueStore.Saved.serializer(),QueueStore.Saved(legacyTracks,7,123456))
        File(context.filesDir,"queue.json").writeText(oldJson,Charsets.UTF_8)
        StandaloneDatabaseProvider(context).use { provider ->
            val index=DefaultDownloadIndex(provider)
            legacyTracks.forEach { t ->
                val request=DownloadRequest.Builder(t.id,MediaItems.uriOf(t.id)).setData(Json.encodeToString(Downloads.StoredTrack.serializer(), Downloads.StoredTrack.of(t.toTrack())).toByteArray(Charsets.UTF_8)).build()
                index.putDownload(Download(request,Download.STATE_COMPLETED,1L,2L,123L,0,0))
            }
        }
        val db=Room.databaseBuilder(context,FireTubeDatabase::class.java,name).addMigrations(*FireTubeDatabase.MIGRATIONS).build()
        val restored=Room.inMemoryDatabaseBuilder(context,FireTubeDatabase::class.java).build()
        try {
            val sql=db.openHelper.writableDatabase
            snapshots.forEach { (table, expected) ->
                sql.query("SELECT * FROM $table ORDER BY rowid").use { c ->
                    val actual=buildList { while(c.moveToNext()) add((0 until expected.first().size).map { if(c.isNull(it)) null else c.getString(it) }) }
                    assertEquals("$table lost or changed data",expected,actual)
                }
            }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            ids.forEach { assertEquals(TrackIdentity.youtube(it),db.library().track(it)!!.toTrack().identity) }
            assertEquals(QueueStore.Saved(legacyTracks,7,123456), queue.load())
            StandaloneDatabaseProvider(context).use { provider ->
                val index=DefaultDownloadIndex(provider)
                legacyTracks.forEach { t ->
                    val d=index.getDownload(t.id)!!
                    assertEquals(MediaItems.uriOf(t.id),d.request.uri)
                    assertEquals(t.toTrack(),Json.decodeFromString<Downloads.StoredTrack>(d.request.data.decodeToString()).toTrack())
                }
            }
            val library=LibraryRepository(db.library())
            val before=library.allSnapshots()
            before.flatMap { it.tracks }.forEach { assertEquals(it, TrackSnapshot.fromCloudMap(it.toCloudMap())) }
            val bytes=ByteArrayOutputStream().also { Backup(library).export(it) }.toByteArray()
            val target=LibraryRepository(restored.library())
            Backup(target).import(bytes.inputStream())
            assertEquals(before.filterNot { it.deleted }.sortedBy { it.id },target.allSnapshots().sortedBy { it.id })
        } finally {
            db.close();restored.close();context.deleteDatabase(name)
            File(context.filesDir,"queue.json").delete()
        }
    }
}
