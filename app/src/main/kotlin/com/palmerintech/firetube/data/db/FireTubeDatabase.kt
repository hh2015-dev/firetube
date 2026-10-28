package com.palmerintech.firetube.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, HistoryEntity::class, PlayEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class FireTubeDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        fun create(context: Context): FireTubeDatabase =
            Room.databaseBuilder(context, FireTubeDatabase::class.java, "firetube.db").addMigrations(*MIGRATIONS).build()

        /** 2: the plays table behind Your stats. History before it has no listened time, so stats start empty. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plays` (`trackId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                        "`msListened` INTEGER NOT NULL, PRIMARY KEY(`startedAt`, `trackId`), " +
                        "FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plays_trackId` ON `plays` (`trackId`)")
            }
        }

        /** Add provider metadata in place. Never rewrite keys or invalidate cached audio. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `sourceId` TEXT NOT NULL DEFAULT 'youtube'")
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `externalId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE `tracks` SET `externalId` = `id`")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `album` TEXT")
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `fileName` TEXT")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
