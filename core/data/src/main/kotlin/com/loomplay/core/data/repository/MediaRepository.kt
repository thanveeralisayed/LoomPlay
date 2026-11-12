package com.loomplay.core.data.repository

import com.loomplay.core.data.local.dao.PlaybackHistoryDao
import com.loomplay.core.data.local.dao.PlaylistDao
import com.loomplay.core.data.local.entity.PlaybackHistoryEntity
import com.loomplay.core.data.local.entity.PlaylistEntity
import com.loomplay.core.data.local.entity.PlaylistItemEntity
import com.loomplay.core.data.media.MediaStoreVideoScanner
import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.LoomMediaItem
import com.loomplay.core.model.PlaylistSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    private val scanner: MediaStoreVideoScanner,
    private val historyDao: PlaybackHistoryDao,
    private val playlistDao: PlaylistDao,
) {
    private val refreshTick = MutableStateFlow(0)

    fun observeRawLibraryVideos(): Flow<List<LoomMediaItem>> = scanner.observeDeviceVideos()

    fun observeLibraryVideos(): Flow<List<LoomMediaItem>> = combine(
        scanner.observeDeviceVideos(),
        historyDao.observeHistory(),
        refreshTick,
    ) { videos, historyEntries, _ ->
        val historyMap = historyEntries.associateBy { it.mediaUri }
        videos.map { video ->
            val h = historyMap[video.uri]
            if (h != null) {
                video.copy(
                    lastPositionMs = h.lastPositionMs,
                    lastPlayedEpochMs = h.lastPlayedEpochMs,
                    isCompleted = h.isCompleted,
                    preferredEngine = h.preferredEngine?.let {
                        runCatching { EnginePreference.valueOf(it) }.getOrNull()
                    } ?: EnginePreference.AUTO,
                )
            } else {
                video
            }
        }
    }

    fun observeContinueWatching(): Flow<List<LoomMediaItem>> = combine(
        observeLibraryVideos(),
        historyDao.observeContinueWatching(),
    ) { library, continueEntries ->
        val libraryMap = library.associateBy { it.uri }
        continueEntries.map { entry ->
            libraryMap[entry.mediaUri] ?: LoomMediaItem(
                uri = entry.mediaUri,
                title = decodeFallbackTitle(entry.mediaUri, "Stream"),
                folderName = "Network / External",
                durationMs = entry.durationMs,
                fileSizeBytes = 0L,
                lastPositionMs = entry.lastPositionMs,
                lastPlayedEpochMs = entry.lastPlayedEpochMs,
                isCompleted = entry.isCompleted,
            )
        }
    }

    suspend fun getPlaybackEntry(mediaUri: String): PlaybackHistoryEntity? =
        historyDao.getByUri(mediaUri)

    suspend fun savePlaybackProgress(
        mediaUri: String,
        positionMs: Long,
        durationMs: Long,
        preferredEngine: EnginePreference,
        selectedAudioTrack: String? = null,
        selectedSubtitleTrack: String? = null,
        subtitleDelayMs: Long,
        audioDelayMs: Long,
        playbackSpeed: Float,
        completionThresholdPercent: Int = 90,
    ) {
        val ratioThreshold = (completionThresholdPercent.coerceIn(70, 99) / 100f)
        val completed = durationMs > 0L && (positionMs.toFloat() / durationMs.toFloat()) >= ratioThreshold
        historyDao.upsert(
            PlaybackHistoryEntity(
                mediaUri = mediaUri,
                lastPositionMs = if (completed) 0L else positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                lastPlayedEpochMs = System.currentTimeMillis(),
                preferredEngine = preferredEngine.name,
                selectedAudioTrack = selectedAudioTrack,
                selectedSubtitleTrack = selectedSubtitleTrack,
                subtitleDelayMs = subtitleDelayMs,
                audioDelayMs = audioDelayMs,
                playbackSpeed = playbackSpeed,
                isCompleted = completed,
            ),
        )
    }

    suspend fun clearPlaybackHistory() {
        historyDao.clearAll()
    }

    fun clearThumbnailCache() {
        scanner.clearThumbnailCache()
    }

    fun triggerRefresh() {
        scanner.triggerRescan()
        refreshTick.value++
    }

    fun observePlaylists(): Flow<List<PlaylistSummary>> =
        playlistDao.observePlaylistsWithCounts().map { rows ->
            rows.map {
                PlaylistSummary(
                    playlistId = it.playlistId,
                    name = it.name,
                    itemCount = it.itemCount,
                    updatedAtEpochMs = it.updatedAtEpochMs,
                )
            }
        }

    fun observePlaylistItems(playlistId: Long): Flow<List<LoomMediaItem>> = combine(
        playlistDao.observePlaylistItems(playlistId),
        observeLibraryVideos(),
    ) { items, library ->
        val map = library.associateBy { it.uri }
        items.map { item ->
            map[item.mediaUri] ?: LoomMediaItem(
                uri = item.mediaUri,
                title = decodeFallbackTitle(item.mediaUri, "Media"),
                folderName = "Playlist",
                durationMs = 0L,
                fileSizeBytes = 0L,
            )
        }
    }

    suspend fun createPlaylist(name: String): Long {
        val now = System.currentTimeMillis()
        return playlistDao.insertPlaylist(
            PlaylistEntity(
                name = name.trim(),
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
    }

    suspend fun addToPlaylist(playlistId: Long, mediaUri: String) {
        val now = System.currentTimeMillis()
        val nextOrder = playlistDao.nextSortOrder(playlistId)
        playlistDao.upsertPlaylistItem(
            PlaylistItemEntity(
                playlistId = playlistId,
                mediaUri = mediaUri,
                sortOrder = nextOrder,
                addedAtEpochMs = now,
            ),
        )
        playlistDao.touchPlaylist(playlistId, now)
    }

    suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deleteItemsForPlaylist(playlistId)
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) {
        val cleaned = newName.trim()
        if (cleaned.isEmpty()) return
        playlistDao.renamePlaylist(playlistId, cleaned, System.currentTimeMillis())
    }

    suspend fun removeFromPlaylist(playlistId: Long, mediaUri: String) {
        playlistDao.removePlaylistItem(playlistId, mediaUri)
        playlistDao.touchPlaylist(playlistId, System.currentTimeMillis())
    }

    suspend fun movePlaylistItem(playlistId: Long, fromIndex: Int, toIndex: Int) {
        playlistDao.movePlaylistItem(playlistId, fromIndex, toIndex, System.currentTimeMillis())
    }

    private fun decodeFallbackTitle(mediaUri: String, defaultTitle: String): String {
        val raw = mediaUri.substringAfterLast('/').substringBefore('?').substringBefore('#')
        if (raw.isBlank()) return defaultTitle
        val decoded = runCatching {
            java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(raw)
        return decoded.takeIf { it.isNotBlank() } ?: defaultTitle
    }
}

