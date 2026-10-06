package com.coxtv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChannelEntity::class, FavoriteEntity::class, ProgramEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun programs(): ProgramDao

    companion object {
        /** Adds favorite ordering, starting from the order favorites were shown in (channel order). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE favorites SET position = " +
                        "COALESCE((SELECT sortOrder FROM channels WHERE id = favorites.channelId), 1000000)",
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "coxtv.db")
                // WAL lets the UI keep reading the old guide while a refresh transaction runs.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
