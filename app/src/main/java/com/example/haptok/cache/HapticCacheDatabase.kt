package com.example.haptok.cache

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room entity representing a cached haptic timeline for a specific video.
 */
@Entity(tableName = "cached_haptic_tracks")
data class CachedHapticTrack(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "video_content_hash") val videoContentHash: String,
    @ColumnInfo(name = "video_uri") val videoUri: String,
    @ColumnInfo(name = "video_title") val videoTitle: String,
    @ColumnInfo(name = "haptic_timeline_json") val hapticTimelineJson: String,
    @ColumnInfo(name = "video_duration_seconds") val videoDurationSeconds: Double,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

/**
 * DAO for [CachedHapticTrack] CRUD operations.
 */
@Dao
interface HapticCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: CachedHapticTrack): Long

    @Query("SELECT * FROM cached_haptic_tracks WHERE video_content_hash = :hash LIMIT 1")
    suspend fun getByVideoHash(hash: String): CachedHapticTrack?

    @Query("SELECT * FROM cached_haptic_tracks WHERE video_uri = :uri LIMIT 1")
    suspend fun getByVideoUri(uri: String): CachedHapticTrack?

    @Query("SELECT * FROM cached_haptic_tracks ORDER BY created_at DESC")
    suspend fun getAll(): List<CachedHapticTrack>

    @Query("SELECT * FROM cached_haptic_tracks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CachedHapticTrack?

    @Delete
    suspend fun delete(track: CachedHapticTrack)

    @Query("DELETE FROM cached_haptic_tracks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM cached_haptic_tracks WHERE id IN (SELECT id FROM cached_haptic_tracks ORDER BY created_at ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("SELECT COUNT(*) FROM cached_haptic_tracks")
    suspend fun getTotalCount(): Int

    @Query("DELETE FROM cached_haptic_tracks")
    suspend fun clearAll()
}

/**
 * Room database holding the haptic cache.
 */
@Database(entities = [CachedHapticTrack::class], version = 1, exportSchema = false)
abstract class HapticCacheDatabase : RoomDatabase() {

    abstract fun hapticCacheDao(): HapticCacheDao

    companion object {
        @Volatile
        private var INSTANCE: HapticCacheDatabase? = null

        fun getInstance(context: Context): HapticCacheDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HapticCacheDatabase::class.java,
                    "haptok_cache.db",
                ).fallbackToDestructiveMigration(false)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
