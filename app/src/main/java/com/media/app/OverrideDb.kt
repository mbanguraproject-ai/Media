package com.media.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// One row per user-edited file. Keyed by the MediaStore id.
@Entity(tableName = "overrides")
data class MediaOverride(
    @PrimaryKey val mediaId: Long,
    val customTitle: String? = null,
    val customArtist: String? = null,   // artist / host / author depending on pillar
    val details: String? = null,
    val pillar: String? = null          // "MUSIC" | "PODCAST" | "AUDIOBOOK" | null
)

@Entity(tableName = "play_history")
data class PlayHistory(
    @PrimaryKey val mediaId: Long,
    val lastPlayed: Long   // epoch millis
)

// Resume position for long-form items (audiobooks/podcasts). One row per file.
@Entity(tableName = "playback_positions")
data class PlaybackPosition(
    @PrimaryKey val mediaId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,   // epoch millis
    val speed: Float = 1.0f // remembered playback speed for this item
)

@Dao
interface HistoryDao {
    @Upsert
    suspend fun record(entry: PlayHistory)

    @Query("SELECT * FROM play_history ORDER BY lastPlayed DESC LIMIT :limit")
    fun observeRecent(limit: Int): kotlinx.coroutines.flow.Flow<List<PlayHistory>>

    @Query("DELETE FROM play_history")
    suspend fun clear()
}

@Dao
interface PositionDao {
    @Upsert
    suspend fun save(pos: PlaybackPosition)

    @Query("SELECT * FROM playback_positions WHERE mediaId = :id LIMIT 1")
    suspend fun getOne(id: Long): PlaybackPosition?

    @Query("DELETE FROM playback_positions WHERE mediaId = :id")
    suspend fun clearOne(id: Long)

    @Query("SELECT * FROM playback_positions")
    fun observeAll(): Flow<List<PlaybackPosition>>
}

@Dao
interface OverrideDao {
    @Query("SELECT * FROM overrides")
    fun observeAll(): Flow<List<MediaOverride>>

    @Query("SELECT * FROM overrides")
    suspend fun getAll(): List<MediaOverride>

    @Upsert
    suspend fun upsert(override: MediaOverride)

    @Query("DELETE FROM overrides WHERE mediaId = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [MediaOverride::class, PlayHistory::class, PlaybackPosition::class], version = 4, exportSchema = false)
abstract class OverrideDatabase : RoomDatabase() {
    abstract fun dao(): OverrideDao
    abstract fun historyDao(): HistoryDao
    abstract fun positionDao(): PositionDao

    companion object {
        @Volatile private var INSTANCE: OverrideDatabase? = null

        // v1 -> v2: add play_history table, preserve all existing overrides.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `play_history` " +
                    "(`mediaId` INTEGER NOT NULL, `lastPlayed` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`mediaId`))"
                )
            }
        }

        // v2 -> v3: add playback_positions table, preserve overrides + history.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playback_positions` " +
                    "(`mediaId` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, " +
                    "`durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`mediaId`))"
                )
            }
        }

        // v3 -> v4: add speed column to playback_positions (default 1.0), data preserved.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `playback_positions` ADD COLUMN `speed` REAL NOT NULL DEFAULT 1.0"
                )
            }
        }

        fun get(context: Context): OverrideDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OverrideDatabase::class.java,
                    "media_overrides.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
    }
}
