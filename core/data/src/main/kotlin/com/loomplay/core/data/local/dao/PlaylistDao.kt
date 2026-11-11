package com.loomplay.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.loomplay.core.data.local.entity.PlaylistEntity
import com.loomplay.core.data.local.entity.PlaylistItemEntity
import kotlinx.coroutines.flow.Flow

data class PlaylistWithCountRow(
    val playlistId: Long,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val itemCount: Int,
)

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.playlistId, p.name, p.createdAtEpochMs, p.updatedAtEpochMs, " +
            "COUNT(pi.mediaUri) AS itemCount " +
            "FROM playlists p LEFT JOIN playlist_items pi ON p.playlistId = pi.playlistId " +
            "GROUP BY p.playlistId ORDER BY p.updatedAtEpochMs DESC",
    )
    fun observePlaylistsWithCounts(): Flow<List<PlaylistWithCountRow>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    fun observePlaylistItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    suspend fun getPlaylistItemsOnce(playlistId: Long): List<PlaylistItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistItem(item: PlaylistItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistItems(items: List<PlaylistItemEntity>)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun nextSortOrder(playlistId: Long): Int

    @Query("UPDATE playlists SET name = :newName, updatedAtEpochMs = :updatedAt WHERE playlistId = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, newName: String, updatedAt: Long)

    @Query("UPDATE playlists SET updatedAtEpochMs = :updatedAt WHERE playlistId = :playlistId")
    suspend fun touchPlaylist(playlistId: Long, updatedAt: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND mediaUri = :mediaUri")
    suspend fun removePlaylistItem(playlistId: Long, mediaUri: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteItemsForPlaylist(playlistId: Long)

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Transaction
    suspend fun movePlaylistItem(playlistId: Long, fromIndex: Int, toIndex: Int, updatedAt: Long) {
        val current = getPlaylistItemsOnce(playlistId).toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val moved = current.removeAt(fromIndex)
        current.add(toIndex, moved)
        upsertPlaylistItems(current.mapIndexed { idx, item -> item.copy(sortOrder = idx) })
        touchPlaylist(playlistId, updatedAt)
    }
}

