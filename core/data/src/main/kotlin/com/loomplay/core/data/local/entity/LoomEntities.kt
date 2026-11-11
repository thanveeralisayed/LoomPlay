package com.loomplay.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_history")
data class PlaybackHistoryEntity(
    @PrimaryKey val mediaUri: String,
    val lastPositionMs: Long,
    val durationMs: Long,
    val lastPlayedEpochMs: Long,
    val preferredEngine: String?,
    val selectedAudioTrack: String?,
    val selectedSubtitleTrack: String?,
    val subtitleDelayMs: Long,
    val audioDelayMs: Long,
    val playbackSpeed: Float,
    val isCompleted: Boolean,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0L,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "mediaUri"],
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val mediaUri: String,
    val sortOrder: Int,
    val addedAtEpochMs: Long,
)

