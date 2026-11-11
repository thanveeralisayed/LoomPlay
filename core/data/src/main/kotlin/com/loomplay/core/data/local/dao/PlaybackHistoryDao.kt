package com.loomplay.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.loomplay.core.data.local.entity.PlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY lastPlayedEpochMs DESC")
    fun observeHistory(): Flow<List<PlaybackHistoryEntity>>

    @Query(
        "SELECT * FROM playback_history " +
            "WHERE isCompleted = 0 AND lastPositionMs > 0 " +
            "ORDER BY lastPlayedEpochMs DESC LIMIT 20",
    )
    fun observeContinueWatching(): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE mediaUri = :mediaUri LIMIT 1")
    suspend fun getByUri(mediaUri: String): PlaybackHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history WHERE mediaUri = :mediaUri")
    suspend fun deleteByUri(mediaUri: String)

    @Query("DELETE FROM playback_history")
    suspend fun clearAll()
}
