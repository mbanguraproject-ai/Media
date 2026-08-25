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
    val lastPlayed: Long,      // epoch millis
    val playCount: Int = 0     // §9 "Most played" / §8 "keep coming back to"
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

// One row per favorited track. Presence = favorited. Keyed by MediaStore id.
@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey val mediaId: Long,
    val addedAt: Long   // epoch millis
)

// Generalized mood membership: one row = a track belongs to a mood list.
// moodKey is the Mood enum name lowercased (late_night, workout, focus, favorites).
// 'all' is never stored here — it always means the whole library.
@Entity(tableName = "mood_members", primaryKeys = ["moodKey", "mediaId"])
data class MoodMember(
    val moodKey: String,
    val mediaId: Long,
    val addedAt: Long
)

// User-created playlists (My Playlists tab).
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long
)

@Entity(tableName = "playlist_members", primaryKeys = ["playlistId", "mediaId"])
data class PlaylistMember(
    val playlistId: Long,
    val mediaId: Long,
    val addedAt: Long
)

// Folders (Folders tab).
@Entity(tableName = "folders")
data class MediaFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(tableName = "folder_members", primaryKeys = ["folderId", "mediaId"])
data class FolderMember(
    val folderId: Long,
    val mediaId: Long
)

/**
 * Cached bass-energy curve for one track. Stored as a blob: a 4-minute track
 * at 20Hz is 4,800 floats = ~19KB, and only tracks you actually play get one.
 *
 * [dateModified] is copied from MediaStore so a re-encoded or replaced file
 * invalidates its own envelope instead of pulsing to the wrong audio.
 */
@Entity(tableName = "audio_envelopes")
class AudioEnvelope(
    @PrimaryKey val mediaId: Long,
    val hz: Int,
    val dateModified: Long,
    val data: ByteArray
)

@Dao
interface EnvelopeDao {
    @Query("SELECT * FROM audio_envelopes WHERE mediaId = :id")
    suspend fun get(id: Long): AudioEnvelope?

    @Upsert
    suspend fun put(entry: AudioEnvelope)

    @Query("DELETE FROM audio_envelopes WHERE mediaId = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM audio_envelopes")
    suspend fun clear()

    @Query("SELECT COALESCE(SUM(LENGTH(data)), 0) FROM audio_envelopes")
    suspend fun totalBytes(): Long
}

@Dao
interface HistoryDao {
    // Increment-or-insert. Deliberately NOT an upsert: @Upsert replaces the row
    // and would reset playCount to 0 every play. Deliberately NOT
    // "ON CONFLICT DO UPDATE" either — that needs SQLite 3.24 (API 30+) and
    // minSdk here is 24.
    @Query("UPDATE play_history SET lastPlayed = :at, playCount = playCount + 1 WHERE mediaId = :id")
    suspend fun touch(id: Long, at: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(entry: PlayHistory)

    @Transaction
    suspend fun record(id: Long, at: Long) {
        if (touch(id, at) == 0) insertNew(PlayHistory(id, at, 1))
    }

    @Query("SELECT * FROM play_history ORDER BY playCount DESC, lastPlayed DESC LIMIT :limit")
    fun observeMostPlayed(limit: Int): Flow<List<PlayHistory>>

    // Full table — feeds the RECENTLY_PLAYED / MOST_PLAYED sort keys (§9).
    @Query("SELECT * FROM play_history")
    fun observeAllHistory(): Flow<List<PlayHistory>>

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
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Favorite>>

    @Upsert
    suspend fun add(fav: Favorite)

    @Query("DELETE FROM favorites WHERE mediaId = :id")
    suspend fun remove(id: Long)
}

@Dao
interface MoodDao {
    @Query("SELECT * FROM mood_members WHERE moodKey = :key ORDER BY addedAt DESC")
    fun observeMood(key: String): Flow<List<MoodMember>>

    @Query("SELECT * FROM mood_members")
    fun observeAll(): Flow<List<MoodMember>>

    @Upsert
    suspend fun add(m: MoodMember)

    @Query("DELETE FROM mood_members WHERE moodKey = :key AND mediaId = :id")
    suspend fun remove(key: String, id: Long)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observePlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlist_members WHERE playlistId = :pid ORDER BY addedAt DESC")
    fun observeMembers(pid: Long): Flow<List<PlaylistMember>>

    @Query("SELECT * FROM playlist_members")
    fun observeAllMembers(): Flow<List<PlaylistMember>>

    @Insert
    suspend fun create(p: Playlist): Long

    @Query("DELETE FROM playlists WHERE id = :pid")
    suspend fun deletePlaylist(pid: Long)

    @Query("DELETE FROM playlist_members WHERE playlistId = :pid")
    suspend fun clearMembers(pid: Long)

    @Upsert
    suspend fun addMember(m: PlaylistMember)

    @Query("DELETE FROM playlist_members WHERE playlistId = :pid AND mediaId = :id")
    suspend fun removeMember(pid: Long, id: Long)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name")
    fun observeFolders(): Flow<List<MediaFolder>>

    @Query("SELECT * FROM folder_members WHERE folderId = :fid")
    fun observeMembers(fid: Long): Flow<List<FolderMember>>

    @Insert
    suspend fun create(f: MediaFolder): Long

    @Query("DELETE FROM folders WHERE id = :fid")
    suspend fun deleteFolder(fid: Long)

    @Upsert
    suspend fun addMember(m: FolderMember)

    @Query("DELETE FROM folder_members WHERE folderId = :fid AND mediaId = :id")
    suspend fun removeMember(fid: Long, id: Long)
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

@Database(entities = [MediaOverride::class, PlayHistory::class, PlaybackPosition::class, Favorite::class, MoodMember::class, Playlist::class, PlaylistMember::class, MediaFolder::class, FolderMember::class, AudioEnvelope::class], version = 8, exportSchema = false)
abstract class OverrideDatabase : RoomDatabase() {
    abstract fun dao(): OverrideDao
    abstract fun historyDao(): HistoryDao
    abstract fun envelopeDao(): EnvelopeDao
    abstract fun positionDao(): PositionDao
    abstract fun moodDao(): MoodDao
    abstract fun playlistDao(): PlaylistDao

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

        // v4 -> v5: add favorites table, all existing data preserved.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorites` " +
                    "(`mediaId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`mediaId`))"
                )
            }
        }

        // v5 -> v6: mood membership, playlists, folders. Existing favorites are
        // copied into mood_members(moodKey='favorites') so no hearts are lost.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `mood_members` (`moodKey` TEXT NOT NULL, `mediaId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`moodKey`, `mediaId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_members` (`playlistId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `mediaId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `folders` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `name` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `folder_members` (`folderId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, PRIMARY KEY(`folderId`, `mediaId`))")
                // Preserve existing favorites.
                db.execSQL("INSERT OR IGNORE INTO `mood_members` (`moodKey`, `mediaId`, `addedAt`) SELECT 'favorites', `mediaId`, `addedAt` FROM `favorites`")
            }
        }

        // v6 -> v7: play_history gains playCount. Existing rows have been played
        // at least once, so they seed at 1 rather than 0.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `play_history` ADD COLUMN `playCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `play_history` SET `playCount` = 1 WHERE `playCount` = 0")
            }
        }

        // v7 -> v8: cached bass envelopes. Pure addition, nothing to backfill.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audio_envelopes` (" +
                        "`mediaId` INTEGER NOT NULL, " +
                        "`hz` INTEGER NOT NULL, " +
                        "`dateModified` INTEGER NOT NULL, " +
                        "`data` BLOB NOT NULL, " +
                        "PRIMARY KEY(`mediaId`))"
                )
            }
        }

        fun get(context: Context): OverrideDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OverrideDatabase::class.java,
                    "media_overrides.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { INSTANCE = it }
            }
    }
}
