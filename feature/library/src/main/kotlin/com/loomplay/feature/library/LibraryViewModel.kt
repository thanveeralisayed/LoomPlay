package com.loomplay.feature.library

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loomplay.core.data.media.MediaStoreVideoScanner
import com.loomplay.core.data.preferences.SettingsRepository
import com.loomplay.core.data.preferences.UserPreferences
import com.loomplay.core.data.repository.MediaRepository
import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.LibrarySortOrder
import com.loomplay.core.model.LoomMediaItem
import com.loomplay.core.model.PlaylistSummary
import com.loomplay.core.model.sortedByOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTabMode {
    ALL_VIDEOS,
    FOLDERS,
}

data class FolderGroup(
    val name: String,
    val videoCount: Int,
    val totalSizeBytes: Long,
)

data class LibraryUiState(
    val allVideos: List<LoomMediaItem> = emptyList(),
    val filteredVideos: List<LoomMediaItem> = emptyList(),
    val continueWatching: List<LoomMediaItem> = emptyList(),
    val folders: List<FolderGroup> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val searchQuery: String = "",
    val sortOrder: LibrarySortOrder = LibrarySortOrder.DATE_ADDED,
    val activeTab: LibraryTabMode = LibraryTabMode.ALL_VIDEOS,
    val selectedFolder: String? = null,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val videoScanner: MediaStoreVideoScanner,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val activeTab = MutableStateFlow(LibraryTabMode.ALL_VIDEOS)
    private val selectedFolder = MutableStateFlow<String?>(null)

    private val filtersFlow = combine(
        searchQuery,
        activeTab,
        selectedFolder,
    ) { query, tab, folder ->
        FilterParams(query, tab, folder)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        mediaRepository.observeLibraryVideos(),
        mediaRepository.observeContinueWatching(),
        mediaRepository.observePlaylists(),
        settingsRepository.userPreferences,
        filtersFlow,
    ) { videos, continueList, playlists, prefs, filters ->
        val queryLower = filters.query.trim().lowercase()
        val folderGroups = videos
            .groupBy { it.folderName }
            .map { (name, items) ->
                FolderGroup(
                    name = name,
                    videoCount = items.size,
                    totalSizeBytes = items.sumOf { it.fileSizeBytes },
                )
            }
            .filter { group -> queryLower.isEmpty() || group.name.lowercase().contains(queryLower) }
            .sortedByDescending { it.videoCount }

        val filtered = videos
            .filter { item ->
                val matchesFolder = filters.selectedFolder == null || item.folderName == filters.selectedFolder
                val matchesQuery = queryLower.isEmpty() ||
                    item.title.lowercase().contains(queryLower) ||
                    item.folderName.lowercase().contains(queryLower)
                matchesFolder && matchesQuery
            }
            .sortedByOrder(prefs.librarySortOrder)

        LibraryUiState(
            allVideos = videos,
            filteredVideos = filtered,
            continueWatching = continueList,
            folders = folderGroups,
            playlists = playlists,
            preferences = prefs,
            searchQuery = filters.query,
            sortOrder = prefs.librarySortOrder,
            activeTab = filters.activeTab,
            selectedFolder = filters.selectedFolder,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun onSelectTab(tab: LibraryTabMode) {
        activeTab.value = tab
        selectedFolder.value = null
    }

    fun onSelectFolder(folderName: String?) {
        selectedFolder.value = folderName
        if (folderName != null) {
            activeTab.value = LibraryTabMode.ALL_VIDEOS
        }
    }

    fun returnToFolders() {
        selectedFolder.value = null
        activeTab.value = LibraryTabMode.FOLDERS
    }

    fun setSortOrder(order: LibrarySortOrder) {
        viewModelScope.launch {
            settingsRepository.setLibrarySortOrder(order)
        }
    }

    fun refreshLibrary() {
        mediaRepository.triggerRefresh()
    }

    suspend fun loadThumbnail(uri: String): Bitmap? = videoScanner.loadThumbnail(uri)

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { mediaRepository.createPlaylist(name) }
    }

    fun createPlaylistAndAdd(name: String, mediaUri: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = mediaRepository.createPlaylist(name)
            mediaRepository.addToPlaylist(id, mediaUri)
        }
    }

    fun addToPlaylist(playlistId: Long, mediaUri: String) {
        viewModelScope.launch { mediaRepository.addToPlaylist(playlistId, mediaUri) }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { mediaRepository.deletePlaylist(playlistId) }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { mediaRepository.renamePlaylist(playlistId, newName) }
    }

    fun removeFromPlaylist(playlistId: Long, mediaUri: String) {
        viewModelScope.launch { mediaRepository.removeFromPlaylist(playlistId, mediaUri) }
    }

    fun movePlaylistItem(playlistId: Long, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch { mediaRepository.movePlaylistItem(playlistId, fromIndex, toIndex) }
    }

    fun observePlaylistItems(playlistId: Long): Flow<List<LoomMediaItem>> =
        mediaRepository.observePlaylistItems(playlistId)

    fun recordNetworkStream(
        url: String,
        title: String = "",
        headers: Map<String, String> = emptyMap(),
        preferredEngine: EnginePreference = EnginePreference.AUTO,
    ) {
        viewModelScope.launch {
            settingsRepository.saveNetworkStream(url, title, headers, preferredEngine)
        }
    }

    fun removeNetworkStream(url: String) {
        viewModelScope.launch { settingsRepository.removeNetworkStream(url) }
    }

    fun clearNetworkStreams() {
        viewModelScope.launch { settingsRepository.clearNetworkStreams() }
    }

    private data class FilterParams(
        val query: String,
        val activeTab: LibraryTabMode,
        val selectedFolder: String?,
    )
}

