package com.loomplay.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.loomplay.core.data.local.dao.PlaybackHistoryDao
import com.loomplay.core.data.local.dao.PlaylistDao
import com.loomplay.core.data.local.entity.PlaybackHistoryEntity
import com.loomplay.core.data.local.entity.PlaylistEntity
import com.loomplay.core.data.local.entity.PlaylistItemEntity

@Database(
    entities = [
        PlaybackHistoryEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LoomDatabase : RoomDatabase() {
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun playlistDao(): PlaylistDao
}
