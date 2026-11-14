package com.loomplay.feature.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loomplay.core.data.preferences.SettingsRepository
import com.loomplay.core.data.repository.MediaRepository
import com.loomplay.core.model.AspectRatioMode
import com.loomplay.core.model.DecoderStats
import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.EngineType
import com.loomplay.core.model.LoomMediaItem
import com.loomplay.core.model.MediaChapter
import com.loomplay.core.model.MediaTrack
import com.loomplay.core.model.MediaTracks
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.model.SubtitleStyleConfig
import com.loomplay.core.model.VideoSize
import com.loomplay.core.model.sortedByOrder
import com.loomplay.core.player.api.ActiveMediaMetadata
import com.loomplay.core.player.api.ExternalSubtitleSource
import com.loomplay.core.player.api.LoomMediaSessionConnector
import com.loomplay.core.player.api.LoomMediaSource
import com.loomplay.core.ui.components.formatTimecode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private const val TRACK_DISABLED_SENTINEL = "__OFF__"

enum class PlayerSheet {
    NONE,
    TRACKS,
    SYNC_DELAY,
    SPEED,
    QUEUE,
    CHAPTERS,
    MORE,
}

sealed interface GestureFeedback {
    data class Brightness(val percent: Int) : GestureFeedback
    data class Volume(val percent: Int) : GestureFeedback
    data class SeekPreview(val deltaMs: Long, val targetMs: Long, val durationMs: Long) : GestureFeedback
    data class SpeedBoost(val multiplier: Float) : GestureFeedback
}

data class PlayerUiState(
    val currentUri: String = "",
    val currentTitle: String = "",
    val isHudVisible: Boolean = true,
    val isLocked: Boolean = false,
    val showLandscapeStats: Boolean = false,
    val minimalInterface: Boolean = true,
    val activeSheet: PlayerSheet = PlayerSheet.NONE,
    val aspectRatio: AspectRatioMode = AspectRatioMode.FIT,
    val subtitleDelayMs: Long = 0L,
    val audioDelayMs: Long = 0L,
    val seekStepSeconds: Int = 10,
    val boostMultiplier: Float = 2.0f,
    val pipAutoEnter: Boolean = true,
    val pinchToZoomEnabled: Boolean = true,
    val swipeGesturesEnabled: Boolean = true,
    val horizontalSeekGestureEnabled: Boolean = true,
    val backgroundAudioEnabled: Boolean = false,
    val autoPlayNextEnabled: Boolean = true,
    val subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
    val brightnessPercent: Int = 65,
    val volumePercent: Int = 75,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val upNextCountdownSec: Int? = null,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val gestureFeedback: GestureFeedback? = null,
    val statusBanner: String? = null,
    val queueItems: List<LoomMediaItem> = emptyList(),
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val playerManager: PlayerManager,
    private val mediaSessionConnector: LoomMediaSessionConnector,
    private val settingsRepository: SettingsRepository,
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    private val ownerToken = Any()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    val activeEngine: StateFlow<EngineType> = playerManager.activeEngine

    val playbackStatus: StateFlow<PlaybackStatus> = playerManager.playbackStatus

    val positionMs: StateFlow<Long> = playerManager.positionMs

    val durationMs: StateFlow<Long> = playerManager.durationMs

    val bufferedPositionMs: StateFlow<Long> = playerManager.bufferedPositionMs

    val videoSize: StateFlow<VideoSize> = playerManager.videoSize

    val tracks: StateFlow<MediaTracks> = playerManager.activeFlow { it.tracks }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaTracks())

    val chapters: StateFlow<List<MediaChapter>> = playerManager.chapters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playbackSpeed: StateFlow<Float> = playerManager.playbackSpeed

    val stats: StateFlow<DecoderStats> = playerManager.activeFlow { it.stats }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DecoderStats())

    val sessionEvicted: StateFlow<Boolean> = combine(
        _uiState.map { it.currentUri.isNotEmpty() }.distinctUntilChanged(),
        playerManager.activeOwnerFlow,
    ) { hasLoaded, owner ->
        hasLoaded && owner != null && owner !== ownerToken
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var initialRouteKey: String? = null
    private var loadedUri: String? = null
    private var loadedMimeType: String? = null
    private var loadedOverride: EnginePreference? = null
    private var currentHeaders: Map<String, String> = emptyMap()
    private var activePlaylistId: Long? = null
    private var activeHostActivityClassName: String? = null
    private var persistedEnginePref: EnginePreference = EnginePreference.AUTO
    private var lastPrefBgAudio: Boolean? = null

    private var desiredAudioTrackPref: String? = null
    private var desiredSubtitleTrackPref: String? = null
    private val discoveredSidecars = mutableListOf<ExternalSubtitleSource>()
    private val manualSidecars = mutableListOf<ExternalSubtitleSource>()

    private var preBoostSpeed: Float = 1.0f
    private var isBoosting: Boolean = false
    private var scrubStartPosMs: Long = 0L
    private var lastLoopSeekRealtimeMs: Long = 0L

    private var hudAutoHideJob: Job? = null
    private var gestureClearJob: Job? = null
    private var bannerClearJob: Job? = null
    private var progressTickerJob: Job? = null
    private var queueObserverJob: Job? = null
    private var upNextCountdownJob: Job? = null

    init {
        playerManager.setQueueNavigationCallbacks(
            onNext = { viewModelScope.launch { playNextInQueue() } },
            onPrev = { viewModelScope.launch { playPreviousInQueue() } },
        )

        viewModelScope.launch {
            playerManager.events.collect { message ->
                showBanner(message)
            }
        }

        viewModelScope.launch {
            settingsRepository.userPreferences.collect { prefs ->
                val bgChangedFromPrefs = lastPrefBgAudio != prefs.backgroundAudioEnabled
                lastPrefBgAudio = prefs.backgroundAudioEnabled
                playerManager.setSubtitleStyle(prefs.subtitleStyleConfig)
                playerManager.setFrameRateMatchingMode(prefs.frameRateMatchingMode)
                _uiState.update { state ->
                    val nextBgAudio = if (bgChangedFromPrefs) prefs.backgroundAudioEnabled else state.backgroundAudioEnabled
                    if (playerManager.isActiveOwner(ownerToken)) {
                        playerManager.setBackgroundAudioEnabled(nextBgAudio)
                    }
                    state.copy(
                        minimalInterface = prefs.minimalInterface,
                        seekStepSeconds = prefs.seekStepSeconds,
                        boostMultiplier = prefs.longPressBoostSpeed,
                        pipAutoEnter = prefs.pipAutoEnter,
                        pinchToZoomEnabled = prefs.pinchToZoomEnabled,
                        swipeGesturesEnabled = prefs.swipeGesturesEnabled,
                        horizontalSeekGestureEnabled = prefs.horizontalSeekGestureEnabled,
                        backgroundAudioEnabled = nextBgAudio,
                        autoPlayNextEnabled = prefs.autoPlayNextEnabled,
                        subtitleStyle = prefs.subtitleStyleConfig,
                    )
                }
            }
        }

        viewModelScope.launch {
            playerManager.playbackStatus.collect { status ->
                if (status == PlaybackStatus.PLAYING) {
                    cancelUpNextAutoPlay()
                    scheduleHudAutoHide()
                } else {
                    hudAutoHideJob?.cancel()
                }
                if (status == PlaybackStatus.ENDED) {
                    persistProgressNow()
                    if (_uiState.value.autoPlayNextEnabled && _uiState.value.hasNext) {
                        startUpNextCountdown()
                    }
                } else {
                    cancelUpNextAutoPlay()
                }
            }
        }

        // Keep OS media session metadata synchronized with active item, duration & queue edges.
        viewModelScope.launch {
            combine(
                _uiState.map { Triple(it.currentUri, it.currentTitle, it.hasNext to it.hasPrevious) }.distinctUntilChanged(),
                durationMs,
                activeEngine,
            ) { (uri, title, nav), dur, engine ->
                ActiveMediaMetadata(
                    uri = uri,
                    title = title,
                    subtitle = null,
                    durationMs = dur,
                    hasNext = nav.first,
                    hasPrevious = nav.second,
                    engineType = engine,
                )
            }.collect { meta ->
                if (playerManager.isActiveOwner(ownerToken) && meta.uri.isNotBlank()) {
                    playerManager.updateMediaMetadata(meta)
                }
            }
        }

        // Keep desired audio/subtitle track selections applied across async emissions and sidecars.
        viewModelScope.launch {
            tracks.collect { mediaTracks ->
                desiredAudioTrackPref?.let { pref ->
                    if (mediaTracks.audioTracks.isNotEmpty()) {
                        findMatchingTrack(mediaTracks.audioTracks, pref)?.let { match ->
                            if (!match.isSelected) {
                                playerManager.selectAudioTrack(match.id)
                            }
                        }
                    }
                }
                when (val subPref = desiredSubtitleTrackPref) {
                    null -> Unit
                    TRACK_DISABLED_SENTINEL -> {
                        if (mediaTracks.subtitleTracks.any { it.isSelected }) {
                            playerManager.selectSubtitleTrack(null)
                        }
                    }
                    else -> {
                        if (mediaTracks.subtitleTracks.isNotEmpty()) {
                            findMatchingTrack(mediaTracks.subtitleTracks, subPref)?.let { match ->
                                if (!match.isSelected) {
                                    playerManager.selectSubtitleTrack(match.id)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Enforce A-B loop when both loopStartMs and loopEndMs are active.
        viewModelScope.launch {
            positionMs.collect { pos ->
                val state = _uiState.value
                val start = state.loopStartMs
                val end = state.loopEndMs
                if (start != null && end != null && end > start + 300L && pos >= end) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLoopSeekRealtimeMs > 450L) {
                        lastLoopSeekRealtimeMs = now
                        playerManager.seekTo(start)
                    }
                }
            }
        }
    }

    fun attachSurface(container: android.widget.FrameLayout) {
        playerManager.attachSurface(container)
    }

    fun initSystemAudioVolume(percent: Int) {
        _uiState.update { it.copy(volumePercent = percent.coerceIn(0, 100)) }
    }

    fun playFromRoute(
        uriString: String,
        title: String,
        mimeType: String? = null,
        engineOverride: EnginePreference? = null,
        headers: Map<String, String> = emptyMap(),
        playlistId: Long? = null,
        hostActivityClassName: String? = null,
        initialPositionMs: Long? = null,
        forceStartFromBeginning: Boolean = false,
        initialExternalSubtitles: List<ExternalSubtitleSource> = emptyList(),
    ) {
        playerManager.claimOwnership(ownerToken)
        activeHostActivityClassName = hostActivityClassName
        playerManager.setActiveHostActivityClassName(hostActivityClassName)
        val headersSig = headers.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        val subsSig = initialExternalSubtitles.joinToString("&") { "${it.uri}:${it.isDefault}" }
        val routeKey = "$uriString|$mimeType|${engineOverride?.name}|$playlistId|$headersSig|$initialPositionMs|$forceStartFromBeginning|$subsSig"
        if (initialRouteKey == routeKey) return
        initialRouteKey = routeKey
        activePlaylistId = playlistId
        currentHeaders = headers
        playUriInternal(
            uriString = uriString,
            title = title,
            mimeType = mimeType,
            engineOverride = engineOverride,
            headers = headers,
            initialPositionMs = initialPositionMs,
            forceStartFromBeginning = forceStartFromBeginning,
            initialExternalSubtitles = initialExternalSubtitles,
        )
    }

    private fun playUriInternal(
        uriString: String,
        title: String,
        mimeType: String? = null,
        engineOverride: EnginePreference? = null,
        headers: Map<String, String> = emptyMap(),
        initialPositionMs: Long? = null,
        forceStartFromBeginning: Boolean = false,
        initialExternalSubtitles: List<ExternalSubtitleSource> = emptyList(),
    ) {
        val headersSig = headers.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        val currentSig = currentHeaders.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        if (loadedUri == uriString && loadedMimeType == mimeType && loadedOverride == engineOverride && headersSig == currentSig && _uiState.value.currentUri == uriString && initialPositionMs == null && !forceStartFromBeginning) {
            return
        }

        val previousUri = loadedUri
        loadedUri = uriString
        loadedMimeType = mimeType
        loadedOverride = engineOverride
        currentHeaders = headers

        cancelUpNextAutoPlay()
        lastLoopSeekRealtimeMs = 0L
        discoveredSidecars.clear()
        manualSidecars.clear()

        viewModelScope.launch {
            if (previousUri != null) {
                saveProgressForUri(previousUri)
            }

            val prefs = settingsRepository.userPreferences.first()
            if (!playerManager.isActiveOwner(ownerToken)) return@launch
            lastPrefBgAudio = prefs.backgroundAudioEnabled
            val savedEntry = mediaRepository.getPlaybackEntry(uriString)
            if (!playerManager.isActiveOwner(ownerToken)) return@launch
            val effectiveOverride = engineOverride ?: savedEntry?.preferredEngine?.let {
                runCatching { EnginePreference.valueOf(it) }.getOrNull()
            } ?: EnginePreference.AUTO
            persistedEnginePref = effectiveOverride

            val startPos = when {
                forceStartFromBeginning -> 0L
                initialPositionMs != null && initialPositionMs >= 0L -> initialPositionMs
                savedEntry != null && !savedEntry.isCompleted && savedEntry.lastPositionMs > 1_500L -> savedEntry.lastPositionMs
                else -> 0L
            }

            val subDelay = savedEntry?.subtitleDelayMs ?: prefs.defaultSubtitleDelayMs
            val audioDelay = savedEntry?.audioDelayMs ?: 0L
            val initialSpeed = savedEntry?.playbackSpeed ?: prefs.defaultPlaybackSpeed

            val hasDefaultExternalSub = initialExternalSubtitles.any { it.isDefault }
            desiredAudioTrackPref = savedEntry?.selectedAudioTrack
            desiredSubtitleTrackPref = if (hasDefaultExternalSub) null else savedEntry?.selectedSubtitleTrack

            val sidecars = if (prefs.autoDetectSidecarSubtitles) {
                detectSidecarSubtitles(uriString)
            } else {
                emptyList()
            }
            if (!playerManager.isActiveOwner(ownerToken)) return@launch
            val combinedSubtitles = (initialExternalSubtitles + sidecars).distinctBy { it.uri }
            discoveredSidecars.addAll(sidecars)
            manualSidecars.addAll(initialExternalSubtitles)

            _uiState.update {
                it.copy(
                    currentUri = uriString,
                    currentTitle = title,
                    subtitleDelayMs = subDelay,
                    audioDelayMs = audioDelay,
                    minimalInterface = prefs.minimalInterface,
                    seekStepSeconds = prefs.seekStepSeconds,
                    boostMultiplier = prefs.longPressBoostSpeed,
                    pipAutoEnter = prefs.pipAutoEnter,
                    pinchToZoomEnabled = prefs.pinchToZoomEnabled,
                    swipeGesturesEnabled = prefs.swipeGesturesEnabled,
                    horizontalSeekGestureEnabled = prefs.horizontalSeekGestureEnabled,
                    backgroundAudioEnabled = prefs.backgroundAudioEnabled,
                    autoPlayNextEnabled = prefs.autoPlayNextEnabled,
                    subtitleStyle = prefs.subtitleStyleConfig,
                    aspectRatio = AspectRatioMode.FIT,
                    activeSheet = PlayerSheet.NONE,
                    loopStartMs = null,
                    loopEndMs = null,
                    upNextCountdownSec = null,
                    gestureFeedback = null,
                    isHudVisible = true,
                )
            }

            playerManager.setActiveHostActivityClassName(activeHostActivityClassName)
            playerManager.setBackgroundAudioEnabled(prefs.backgroundAudioEnabled)
            mediaSessionConnector.connectSession(ownerToken)
            playerManager.configureMpv(prefs.mpvHwDecMode, prefs.mpvCustomConfig)
            playerManager.setSubtitleStyle(prefs.subtitleStyleConfig)
            playerManager.open(
                scope = viewModelScope,
                source = LoomMediaSource(
                    uri = Uri.parse(uriString),
                    title = title,
                    mimeType = mimeType,
                    headers = headers,
                    preferredEngine = effectiveOverride,
                    initialExternalSubtitles = combinedSubtitles,
                ),
                globalPreference = prefs.defaultEngine,
                autoFallbackEnabled = prefs.autoFallbackEnabled,
                startPositionMs = startPos,
                initialSpeed = initialSpeed,
                subtitleDelayMs = subDelay,
                audioDelayMs = audioDelay,
                aspectRatio = AspectRatioMode.FIT,
                ownerToken = ownerToken,
            )

            if (initialPositionMs != null && initialPositionMs > 0L) {
                showBanner("Resumed at ${formatTimecode(startPos)}")
            } else if (startPos > 0L) {
                showBanner("Resumed from previous session")
            } else if (combinedSubtitles.isNotEmpty() && desiredSubtitleTrackPref == null) {
                val banner = if (combinedSubtitles.size == 1) {
                    "Loaded subtitle: ${combinedSubtitles.first().title ?: "External"}"
                } else {
                    "Loaded ${combinedSubtitles.size} subtitles"
                }
                showBanner(banner)
            }

            startProgressTicker()
            observeQueueForUri(uriString, activePlaylistId)
        }
    }

    fun playQueueItem(item: LoomMediaItem) {
        viewModelScope.launch {
            loadedUri?.let { saveProgressForUri(it) }
            loadedOverride = null
            playUriInternal(item.uri, item.title, item.mimeType, null, emptyMap())
        }
    }

    fun playNextInQueue() {
        cancelUpNextAutoPlay()
        val state = _uiState.value
        val idx = state.queueItems.indexOfFirst { it.uri == state.currentUri }
        if (idx >= 0 && idx + 1 < state.queueItems.size) {
            playQueueItem(state.queueItems[idx + 1])
        }
    }

    fun playPreviousInQueue() {
        cancelUpNextAutoPlay()
        val state = _uiState.value
        val idx = state.queueItems.indexOfFirst { it.uri == state.currentUri }
        if (idx > 0) {
            playQueueItem(state.queueItems[idx - 1])
        }
    }

    fun cancelUpNextAutoPlay() {
        upNextCountdownJob?.cancel()
        upNextCountdownJob = null
        _uiState.update { it.copy(upNextCountdownSec = null) }
    }

    private fun startUpNextCountdown() {
        if (upNextCountdownJob != null) return
        upNextCountdownJob = viewModelScope.launch {
            for (sec in 3 downTo 1) {
                _uiState.update { it.copy(upNextCountdownSec = sec) }
                delay(1_000L)
            }
            _uiState.update { it.copy(upNextCountdownSec = null) }
            playNextInQueue()
        }
    }

    private fun observeQueueForUri(uriString: String, playlistId: Long?) {
        queueObserverJob?.cancel()
        queueObserverJob = viewModelScope.launch {
            val sourceFlow = if (playlistId != null) {
                mediaRepository.observePlaylistItems(playlistId)
            } else {
                combine(
                    mediaRepository.observeRawLibraryVideos(),
                    settingsRepository.userPreferences.map { it.librarySortOrder }.distinctUntilChanged(),
                ) { library, sortOrder ->
                    val currentItem = library.firstOrNull { it.uri == uriString }
                    if (currentItem != null) {
                        library.filter { it.folderName == currentItem.folderName }
                            .sortedByOrder(sortOrder)
                    } else {
                        emptyList()
                    }
                }
            }
            sourceFlow.collect { queue ->
                val idx = queue.indexOfFirst { it.uri == uriString }
                val hasPrev = idx > 0
                val hasNext = idx >= 0 && idx < queue.lastIndex
                _uiState.update {
                    it.copy(
                        queueItems = queue,
                        hasPrevious = hasPrev,
                        hasNext = hasNext,
                    )
                }
            }
        }
    }

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(3_000L)
                if (playbackStatus.value == PlaybackStatus.PLAYING) {
                    loadedUri?.let { saveProgressForUri(it) }
                }
            }
        }
    }

    fun persistProgressNow() {
        val uri = loadedUri ?: return
        viewModelScope.launch {
            saveProgressForUri(uri)
        }
    }

    fun onStopLifecycle(inPip: Boolean, isChangingConfig: Boolean) {
        persistProgressNow()
        val keepPlayingInBg = _uiState.value.backgroundAudioEnabled
        if (!inPip && !isChangingConfig && !keepPlayingInBg && playbackStatus.value == PlaybackStatus.PLAYING) {
            playerManager.pause()
        }
    }

    private suspend fun saveProgressForUri(uri: String) {
        val pos = playerManager.currentPlayer.positionMs.value
        val dur = playerManager.currentPlayer.durationMs.value
        if (dur <= 0L && pos <= 0L) return
        val prefToSave = loadedOverride ?: persistedEnginePref
        val currentUi = _uiState.value
        val audioPref = desiredAudioTrackPref ?: currentAudioDescriptor()
        val subPref = desiredSubtitleTrackPref ?: currentSubtitleDescriptor()
        mediaRepository.savePlaybackProgress(
            mediaUri = uri,
            positionMs = pos,
            durationMs = dur,
            preferredEngine = prefToSave,
            selectedAudioTrack = audioPref,
            selectedSubtitleTrack = subPref,
            subtitleDelayMs = currentUi.subtitleDelayMs,
            audioDelayMs = currentUi.audioDelayMs,
            playbackSpeed = playerManager.currentPlayer.playbackSpeed.value,
        )
    }

    fun togglePlayPause() {
        cancelUpNextAutoPlay()
        playerManager.togglePlayPause()
        scheduleHudAutoHide()
    }

    fun seekTo(posMs: Long) {
        cancelUpNextAutoPlay()
        playerManager.seekTo(posMs)
        scheduleHudAutoHide()
    }

    fun seekToChapter(chapter: MediaChapter) {
        seekTo(chapter.startMs)
        closeSheet()
        showBanner("Chapter: ${chapter.title}")
    }

    fun doubleTapSeek(forward: Boolean) {
        if (_uiState.value.isLocked) return
        cancelUpNextAutoPlay()
        val delta = (_uiState.value.seekStepSeconds * 1000L) * if (forward) 1 else -1
        val current = positionMs.value
        val dur = durationMs.value
        val target = if (dur > 0L) (current + delta).coerceIn(0L, dur) else (current + delta).coerceAtLeast(0L)
        playerManager.seekTo(target)
        showTransientGesture(GestureFeedback.SeekPreview(deltaMs = delta, targetMs = target, durationMs = dur))
        if (_uiState.value.isHudVisible) {
            scheduleHudAutoHide()
        }
    }

    fun beginHorizontalScrub() {
        if (_uiState.value.isLocked) return
        scrubStartPosMs = positionMs.value
    }

    fun updateHorizontalScrub(deltaMs: Long) {
        if (_uiState.value.isLocked) return
        val dur = durationMs.value
        val rawTarget = scrubStartPosMs + deltaMs
        val clamped = if (dur > 0L) rawTarget.coerceIn(0L, dur) else rawTarget.coerceAtLeast(0L)
        _uiState.update {
            it.copy(
                gestureFeedback = GestureFeedback.SeekPreview(
                    deltaMs = clamped - scrubStartPosMs,
                    targetMs = clamped,
                    durationMs = dur,
                ),
            )
        }
    }

    fun endHorizontalScrub() {
        if (_uiState.value.isLocked) return
        val feedback = _uiState.value.gestureFeedback
        if (feedback is GestureFeedback.SeekPreview) {
            cancelUpNextAutoPlay()
            playerManager.seekTo(feedback.targetMs)
        }
        clearGestureAfterDelay(550L)
    }

    fun updateBrightnessPercent(percent: Int) {
        if (_uiState.value.isLocked) return
        val clamped = percent.coerceIn(1, 100)
        _uiState.update {
            it.copy(
                brightnessPercent = clamped,
                gestureFeedback = GestureFeedback.Brightness(clamped),
            )
        }
        clearGestureAfterDelay(900L)
    }

    fun updateVolumePercent(percent: Int) {
        if (_uiState.value.isLocked) return
        val clamped = percent.coerceIn(0, 100)
        _uiState.update {
            it.copy(
                volumePercent = clamped,
                gestureFeedback = GestureFeedback.Volume(clamped),
            )
        }
        clearGestureAfterDelay(900L)
    }

    fun startLongPressBoost() {
        if (_uiState.value.isLocked || isBoosting) return
        isBoosting = true
        preBoostSpeed = playbackSpeed.value
        val mult = _uiState.value.boostMultiplier
        playerManager.setPlaybackSpeed(mult)
        _uiState.update { it.copy(gestureFeedback = GestureFeedback.SpeedBoost(mult)) }
    }

    fun endLongPressBoost() {
        if (!isBoosting) return
        isBoosting = false
        playerManager.setPlaybackSpeed(preBoostSpeed)
        _uiState.update { it.copy(gestureFeedback = null) }
    }

    fun toggleHud() {
        _uiState.update { state ->
            val next = !state.isHudVisible
            state.copy(isHudVisible = next)
        }
        if (_uiState.value.isHudVisible) {
            scheduleHudAutoHide()
        } else {
            hudAutoHideJob?.cancel()
        }
    }

    fun keepHudVisible() {
        _uiState.update { it.copy(isHudVisible = true) }
        hudAutoHideJob?.cancel()
    }

    fun toggleLock() {
        _uiState.update { state ->
            val nextLocked = !state.isLocked
            state.copy(
                isLocked = nextLocked,
                isHudVisible = !nextLocked,
            )
        }
        showBanner(if (_uiState.value.isLocked) "Touch controls locked" else "Controls unlocked")
    }

    fun toggleBackgroundAudio() {
        _uiState.update { state ->
            val next = !state.backgroundAudioEnabled
            if (playerManager.isActiveOwner(ownerToken)) {
                playerManager.setBackgroundAudioEnabled(next)
            }
            showBanner(if (next) "Background audio enabled" else "Background audio disabled")
            state.copy(backgroundAudioEnabled = next)
        }
    }

    fun toggleAbLoop() {
        val currentPos = positionMs.value.coerceAtLeast(0L)
        val state = _uiState.value
        when {
            state.loopStartMs == null -> {
                _uiState.update { it.copy(loopStartMs = currentPos, loopEndMs = null) }
                showBanner("Loop A set (${formatTimecode(currentPos)})")
            }
            state.loopEndMs == null -> {
                val start = state.loopStartMs
                if (currentPos > start + 500L) {
                    _uiState.update { it.copy(loopEndMs = currentPos) }
                    showBanner("Loop A-B active (${formatTimecode(start)} -> ${formatTimecode(currentPos)})")
                } else {
                    showBanner("Point B must be after Point A")
                }
            }
            else -> {
                _uiState.update { it.copy(loopStartMs = null, loopEndMs = null) }
                showBanner("A-B loop cleared")
            }
        }
    }

    fun prepareForExit() {
        cancelUpNextAutoPlay()
        endLongPressBoost()
        if (!playerManager.isActiveOwner(ownerToken)) return
        persistProgressNow()
        playerManager.pause()
    }

    fun clearAbLoop() {
        _uiState.update { it.copy(loopStartMs = null, loopEndMs = null) }
    }

    fun toggleTelemetry() {
        _uiState.update { it.copy(showLandscapeStats = !it.showLandscapeStats) }
    }

    fun dismissStats() {
        _uiState.update { it.copy(showLandscapeStats = false) }
    }

    fun openSheet(sheet: PlayerSheet) {
        _uiState.update { it.copy(activeSheet = sheet, isHudVisible = true) }
        hudAutoHideJob?.cancel()
    }

    fun closeSheet() {
        _uiState.update { it.copy(activeSheet = PlayerSheet.NONE) }
        scheduleHudAutoHide()
    }

    fun toggleEngine() {
        val next = if (activeEngine.value == EngineType.EXOPLAYER) EngineType.MPV else EngineType.EXOPLAYER
        switchEngine(next)
    }

    fun switchEngine(target: EngineType) {
        viewModelScope.launch {
            desiredAudioTrackPref = desiredAudioTrackPref ?: currentAudioDescriptor()
            desiredSubtitleTrackPref = desiredSubtitleTrackPref ?: currentSubtitleDescriptor()

            loadedUri?.let { saveProgressForUri(it) }
            val explicitPref = when (target) {
                EngineType.EXOPLAYER -> EnginePreference.FORCE_EXOPLAYER
                EngineType.MPV -> EnginePreference.FORCE_MPV
            }
            loadedOverride = explicitPref
            persistedEnginePref = explicitPref
            playerManager.switchEngine(viewModelScope, target)

            if (manualSidecars.isNotEmpty()) {
                playerManager.addExternalSubtitles(manualSidecars.toList(), selectLastAdded = false)
            }
        }
    }

    fun cycleAspectRatio() {
        val entries = AspectRatioMode.entries
        val current = _uiState.value.aspectRatio
        val next = entries[(current.ordinal + 1) % entries.size]
        _uiState.update { it.copy(aspectRatio = next) }
        playerManager.setAspectRatio(next)
        showBanner("Aspect: ${next.label}")
        scheduleHudAutoHide()
    }

    fun setPlaybackSpeed(speed: Float) {
        playerManager.setPlaybackSpeed(speed)
    }

    fun selectAudioTrack(trackId: String) {
        val matched = tracks.value.audioTracks.firstOrNull { it.id == trackId }
        desiredAudioTrackPref = matched?.let(::encodeTrackPref) ?: trackId
        playerManager.selectAudioTrack(trackId)
        persistProgressNow()
    }

    fun selectSubtitleTrack(trackId: String?) {
        desiredSubtitleTrackPref = if (trackId == null) {
            TRACK_DISABLED_SENTINEL
        } else {
            val matched = tracks.value.subtitleTracks.firstOrNull { it.id == trackId }
            matched?.let(::encodeTrackPref) ?: trackId
        }
        playerManager.selectSubtitleTrack(trackId)
        persistProgressNow()
    }

    fun addExternalSubtitle(uri: Uri, mimeType: String?, title: String?) {
        val item = ExternalSubtitleSource(uri = uri, mimeType = mimeType, title = title)
        if (discoveredSidecars.none { it.uri == uri } && manualSidecars.none { it.uri == uri }) {
            manualSidecars += item
        }
        desiredSubtitleTrackPref = null
        playerManager.addExternalSubtitle(uri, mimeType, title)
    }

    fun adjustSubtitleDelay(deltaMs: Long) {
        val next = (_uiState.value.subtitleDelayMs + deltaMs).coerceIn(-10_000L, 10_000L)
        _uiState.update { it.copy(subtitleDelayMs = next) }
        playerManager.setSubtitleDelay(next)
    }

    fun adjustAudioDelay(deltaMs: Long) {
        val next = (_uiState.value.audioDelayMs + deltaMs).coerceIn(-10_000L, 10_000L)
        _uiState.update { it.copy(audioDelayMs = next) }
        playerManager.setAudioDelay(next)
    }

    fun resetDelays() {
        _uiState.update { it.copy(subtitleDelayMs = 0L, audioDelayMs = 0L) }
        playerManager.setSubtitleDelay(0L)
        playerManager.setAudioDelay(0L)
    }

    fun notifyScreenshotResult(success: Boolean, message: String) {
        showBanner(message)
    }

    private fun currentAudioDescriptor(): String? =
        playerManager.currentPlayer.tracks.value.audioTracks.firstOrNull { it.isSelected }?.let(::encodeTrackPref)

    private fun currentSubtitleDescriptor(): String? {
        val subs = playerManager.currentPlayer.tracks.value.subtitleTracks
        val selected = subs.firstOrNull { it.isSelected }
        return when {
            selected != null -> encodeTrackPref(selected)
            desiredSubtitleTrackPref == TRACK_DISABLED_SENTINEL -> TRACK_DISABLED_SENTINEL
            else -> null
        }
    }

    private fun encodeTrackPref(track: MediaTrack): String =
        "${track.id}||${track.language.orEmpty()}||${track.title}"

    private fun findMatchingTrack(list: List<MediaTrack>, pref: String): MediaTrack? {
        val parts = pref.split("||", limit = 3)
        val savedId = parts.getOrNull(0).orEmpty()
        val savedLang = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val savedTitle = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
        val savedCleanTitle = savedTitle?.substringBefore(" • ")?.trim()?.takeIf { it.isNotEmpty() }
        val normalize = { title: String -> title.substringBefore(" • ").trim() }
        return list.firstOrNull {
            it.id == savedId && (savedTitle == null || it.title.equals(savedTitle, ignoreCase = true))
        }
            ?: savedLang?.let { lang ->
                savedTitle?.let { title ->
                    list.firstOrNull {
                        it.language.equals(lang, ignoreCase = true) &&
                            (it.title.equals(title, ignoreCase = true) ||
                                (savedCleanTitle != null && normalize(it.title).equals(savedCleanTitle, ignoreCase = true)))
                    }
                }
            }
            ?: savedTitle?.let { title -> list.firstOrNull { it.title.equals(title, ignoreCase = true) } }
            ?: savedCleanTitle?.let { clean -> list.firstOrNull { normalize(it.title).equals(clean, ignoreCase = true) } }
            ?: savedLang?.let { lang -> list.firstOrNull { it.language.equals(lang, ignoreCase = true) } }
            ?: list.firstOrNull { it.id == savedId }
    }

    private suspend fun detectSidecarSubtitles(uriString: String): List<ExternalSubtitleSource> = withContext(Dispatchers.IO) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext emptyList()
        val scheme = parsed.scheme?.lowercase()
        if (scheme == "http" || scheme == "https" || scheme == "rtsp" || scheme == "rtmp") {
            return@withContext emptyList()
        }

        val supportedExts = setOf("srt", "ass", "ssa", "vtt", "sub")
        val directFiles = findDirectSidecarFiles(parsed, scheme, supportedExts)
        if (directFiles.isNotEmpty()) {
            return@withContext directFiles
        }
        if (scheme == "content") {
            return@withContext findScopedMediaStoreSidecars(parsed, supportedExts)
        }
        emptyList()
    }

    private fun findDirectSidecarFiles(
        parsed: Uri,
        scheme: String?,
        supportedExts: Set<String>,
    ): List<ExternalSubtitleSource> {
        val videoPath: String? = when (scheme) {
            "file" -> parsed.path
            "content" -> runCatching {
                appContext.contentResolver.query(
                    parsed,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
            }.getOrNull()
            else -> null
        }

        val videoFile = videoPath?.let(::File)?.takeIf { it.exists() && it.parentFile?.canRead() == true }
            ?: return emptyList()
        val parentDir = videoFile.parentFile ?: return emptyList()
        val baseStem = videoFile.nameWithoutExtension.lowercase()
        val files = runCatching { parentDir.listFiles() }.getOrNull() ?: return emptyList()

        return files.asSequence()
            .filter { candidate ->
                candidate.isFile &&
                    candidate.canRead() &&
                    candidate.extension.lowercase() in supportedExts &&
                    (
                        candidate.nameWithoutExtension.lowercase() == baseStem ||
                            candidate.nameWithoutExtension.lowercase().startsWith("$baseStem.")
                        )
            }
            .distinctBy { it.name.lowercase() }
            .take(6)
            .map { file ->
                ExternalSubtitleSource(
                    uri = Uri.fromFile(file),
                    mimeType = subtitleMimeForExtension(file.extension),
                    title = file.name,
                )
            }
            .toList()
    }

    private fun findScopedMediaStoreSidecars(
        videoUri: Uri,
        supportedExts: Set<String>,
    ): List<ExternalSubtitleSource> = runCatching {
        var displayName: String? = null
        var bucketId: Long? = null
        var relativePath: String? = null

        val metaProjection = buildList {
            add(OpenableColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.BUCKET_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()

        appContext.contentResolver.query(videoUri, metaProjection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                val bucketIdx = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                if (bucketIdx >= 0 && !cursor.isNull(bucketIdx)) bucketId = cursor.getLong(bucketIdx)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    if (relIdx >= 0) relativePath = cursor.getString(relIdx)
                }
            }
        }

        val cleanName = displayName?.takeIf { it.contains('.') } ?: return@runCatching emptyList()
        val baseStem = cleanName.substringBeforeLast('.').lowercase()
        if (baseStem.isBlank()) return@runCatching emptyList()

        val filesUri = MediaStore.Files.getContentUri("external")
        val selection: String
        val selectionArgs: Array<String>
        when {
            bucketId != null -> {
                selection = "${MediaStore.MediaColumns.BUCKET_ID} = ?"
                selectionArgs = arrayOf(bucketId.toString())
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !relativePath.isNullOrBlank() -> {
                selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                selectionArgs = arrayOf(relativePath)
            }
            else -> return@runCatching emptyList()
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val results = mutableListOf<ExternalSubtitleSource>()
        val seenNames = mutableSetOf<String>()

        appContext.contentResolver.query(filesUri, projection, selection, selectionArgs, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            if (idIdx < 0 || nameIdx < 0) return@use
            while (cursor.moveToNext() && results.size < 6) {
                val candidateName = cursor.getString(nameIdx) ?: continue
                val dotIndex = candidateName.lastIndexOf('.')
                if (dotIndex <= 0 || dotIndex >= candidateName.lastIndex) continue
                val ext = candidateName.substring(dotIndex + 1).lowercase()
                if (ext !in supportedExts) continue
                val stem = candidateName.substring(0, dotIndex).lowercase()
                if (stem != baseStem && !stem.startsWith("$baseStem.")) continue
                if (!seenNames.add(candidateName.lowercase())) continue

                val id = cursor.getLong(idIdx)
                val rowUri = ContentUris.withAppendedId(filesUri, id)
                val rowMime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                results += ExternalSubtitleSource(
                    uri = rowUri,
                    mimeType = rowMime ?: subtitleMimeForExtension(ext),
                    title = candidateName,
                )
            }
        }
        results
    }.getOrDefault(emptyList())

    private fun subtitleMimeForExtension(ext: String): String = when (ext.lowercase()) {
        "ass", "ssa" -> "text/x-ssa"
        "vtt" -> "text/vtt"
        else -> "application/x-subrip"
    }

    private fun showTransientGesture(feedback: GestureFeedback) {
        _uiState.update { it.copy(gestureFeedback = feedback) }
        clearGestureAfterDelay(850L)
    }

    private fun clearGestureAfterDelay(delayMs: Long) {
        gestureClearJob?.cancel()
        gestureClearJob = viewModelScope.launch {
            delay(delayMs)
            _uiState.update { it.copy(gestureFeedback = null) }
        }
    }

    private fun showBanner(message: String) {
        _uiState.update { it.copy(statusBanner = message) }
        bannerClearJob?.cancel()
        bannerClearJob = viewModelScope.launch {
            delay(2_800L)
            _uiState.update { it.copy(statusBanner = null) }
        }
    }

    private fun scheduleHudAutoHide() {
        hudAutoHideJob?.cancel()
        if (_uiState.value.activeSheet != PlayerSheet.NONE) return
        if (playbackStatus.value != PlaybackStatus.PLAYING) return
        hudAutoHideJob = viewModelScope.launch {
            delay(3_600L)
            if (playbackStatus.value == PlaybackStatus.PLAYING && _uiState.value.activeSheet == PlayerSheet.NONE) {
                _uiState.update { it.copy(isHudVisible = false) }
            }
        }
    }

    override fun onCleared() {
        if (playerManager.isActiveOwner(ownerToken)) {
            val uri = loadedUri
            val pos = playerManager.currentPlayer.positionMs.value
            val dur = playerManager.currentPlayer.durationMs.value
            val prefToSave = loadedOverride ?: persistedEnginePref
            val ui = _uiState.value
            val speed = playerManager.currentPlayer.playbackSpeed.value
            val audioPref = desiredAudioTrackPref ?: currentAudioDescriptor()
            val subPref = desiredSubtitleTrackPref ?: currentSubtitleDescriptor()

            if (uri != null && (dur > 0L || pos > 0L)) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching {
                        mediaRepository.savePlaybackProgress(
                            mediaUri = uri,
                            positionMs = pos,
                            durationMs = dur,
                            preferredEngine = prefToSave,
                            selectedAudioTrack = audioPref,
                            selectedSubtitleTrack = subPref,
                            subtitleDelayMs = ui.subtitleDelayMs,
                            audioDelayMs = ui.audioDelayMs,
                            playbackSpeed = speed,
                        )
                    }
                }
            }
            mediaSessionConnector.disconnectSession(ownerToken)
            playerManager.releaseIfOwner(ownerToken)
        } else {
            mediaSessionConnector.disconnectSession(ownerToken)
        }
        super.onCleared()
    }
}
