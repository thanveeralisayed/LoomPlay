package com.loomplay.feature.player

import android.net.Uri
import android.widget.FrameLayout
import com.loomplay.core.model.AspectRatioMode
import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.EngineType
import com.loomplay.core.model.MediaChapter
import com.loomplay.core.model.MpvHwDecMode
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.model.SubtitleStyleConfig
import com.loomplay.core.model.VideoSize
import com.loomplay.core.player.api.ActiveMediaMetadata
import com.loomplay.core.player.api.EngineRoutingPolicy
import com.loomplay.core.player.api.ExternalSubtitleSource
import com.loomplay.core.player.api.ILoomPlayer
import com.loomplay.core.player.api.LoomMediaSource
import com.loomplay.core.player.api.LoomPlaybackSessionBridge
import com.loomplay.core.player.exo.ExoPlayerEngine
import com.loomplay.core.player.mpv.MpvPlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlayerManager internal constructor(
    private val exoEngine: ILoomPlayer,
    private val mpvEngine: ILoomPlayer,
    private val mpvConfigurator: (MpvHwDecMode, String) -> Unit,
) : LoomPlaybackSessionBridge {
    @Inject
    constructor(
        exoEngine: ExoPlayerEngine,
        mpvEngine: MpvPlayerEngine,
    ) : this(
        exoEngine = exoEngine,
        mpvEngine = mpvEngine,
        mpvConfigurator = mpvEngine::configureMpvOptions,
    )

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transitionMutex = Mutex()
    private val ownerLock = Any()
    private var activeOwnerToken: Any? = null
    private var generation: Long = 0L

    private val _activeEngine = MutableStateFlow(EngineType.EXOPLAYER)
    override val activeEngine: StateFlow<EngineType> = _activeEngine.asStateFlow()

    private val _playbackStatus = MutableStateFlow(PlaybackStatus.IDLE)
    val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()
    override val playbackState: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()

    private val _mediaMetadata = MutableStateFlow(ActiveMediaMetadata())
    override val mediaMetadata: StateFlow<ActiveMediaMetadata> = _mediaMetadata.asStateFlow()

    private val _isBackgroundAudioEnabled = MutableStateFlow(false)
    override val isBackgroundAudioEnabled: StateFlow<Boolean> = _isBackgroundAudioEnabled.asStateFlow()

    private val _activeHostActivityClassName = MutableStateFlow<String?>(null)
    override val activeHostActivityClassName: StateFlow<String?> = _activeHostActivityClassName.asStateFlow()

    private val _activeOwnerFlow = MutableStateFlow<Any?>(null)
    val activeOwnerFlow: StateFlow<Any?> = _activeOwnerFlow.asStateFlow()

    override val positionMs: StateFlow<Long> = activeFlow { it.positionMs }
        .stateIn(bridgeScope, SharingStarted.WhileSubscribed(5_000), 0L)

    override val durationMs: StateFlow<Long> = activeFlow { it.durationMs }
        .stateIn(bridgeScope, SharingStarted.WhileSubscribed(5_000), 0L)

    override fun currentPositionMs(): Long = currentPlayer.positionMs.value
    override fun currentDurationMs(): Long = currentPlayer.durationMs.value

    override val bufferedPositionMs: StateFlow<Long> = activeFlow { it.bufferedPositionMs }
        .stateIn(bridgeScope, SharingStarted.WhileSubscribed(5_000), 0L)

    override val playbackSpeed: StateFlow<Float> = activeFlow { it.playbackSpeed }
        .stateIn(bridgeScope, SharingStarted.WhileSubscribed(5_000), 1.0f)

    override val videoSize: StateFlow<VideoSize> = activeFlow { it.videoSize }
        .stateIn(bridgeScope, SharingStarted.WhileSubscribed(5_000), VideoSize())

    val chapters: Flow<List<MediaChapter>> = activeFlow { it.chapters }

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var surfaceContainer: FrameLayout? = null
    private var currentSource: LoomMediaSource? = null
    private val attemptedEngines: MutableSet<EngineType> = mutableSetOf()
    private var observerJob: Job? = null
    private var allowAutoFallback: Boolean = true
    private var lastAspectRatio: AspectRatioMode = AspectRatioMode.FIT
    private var lastSubtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig()
    private var pendingSpeed: Float = 1.0f
    private var pendingSubtitleDelayMs: Long = 0L
    private var pendingAudioDelayMs: Long = 0L
    private var pendingVolumeFraction: Float = 1.0f

    private var onSkipToNextCallback: (() -> Unit)? = null
    private var onSkipToPreviousCallback: (() -> Unit)? = null
    private var onStopSessionCallback: (() -> Unit)? = null

    val currentPlayer: ILoomPlayer
        get() = playerFor(_activeEngine.value)

    fun playerFor(engineType: EngineType): ILoomPlayer = when (engineType) {
        EngineType.EXOPLAYER -> exoEngine
        EngineType.MPV -> mpvEngine
    }

    fun <T> activeFlow(selector: (ILoomPlayer) -> Flow<T>): Flow<T> =
        activeEngine.flatMapLatest { engine -> selector(playerFor(engine)) }

    fun configureMpv(hwDecMode: MpvHwDecMode, customConfig: String) {
        mpvConfigurator(hwDecMode, customConfig)
    }

    fun attachSurface(container: FrameLayout) {
        surfaceContainer = container
        currentPlayer.attachSurfaceView(container)
    }

    fun claimOwnership(ownerToken: Any) {
        synchronized(ownerLock) {
            activeOwnerToken = ownerToken
        }
        _activeOwnerFlow.value = ownerToken
    }

    fun isActiveOwner(ownerToken: Any): Boolean = synchronized(ownerLock) {
        activeOwnerToken === ownerToken
    }

    fun releaseIfOwner(ownerToken: Any): Boolean {
        val shouldRelease = synchronized(ownerLock) {
            if (activeOwnerToken === ownerToken) {
                activeOwnerToken = null
                true
            } else {
                false
            }
        }
        if (shouldRelease) {
            _activeOwnerFlow.value = null
            release()
        }
        return shouldRelease
    }

    fun setBackgroundAudioEnabled(enabled: Boolean) {
        _isBackgroundAudioEnabled.value = enabled
    }

    fun setActiveHostActivityClassName(className: String?) {
        _activeHostActivityClassName.value = className
    }

    suspend fun open(
        scope: CoroutineScope,
        source: LoomMediaSource,
        globalPreference: EnginePreference,
        autoFallbackEnabled: Boolean = true,
        startPositionMs: Long = 0L,
        initialSpeed: Float = 1.0f,
        subtitleDelayMs: Long = 0L,
        audioDelayMs: Long = 0L,
        aspectRatio: AspectRatioMode = AspectRatioMode.FIT,
        ownerToken: Any? = null,
    ) {
        transitionMutex.withLock {
            if (ownerToken != null) {
                claimOwnership(ownerToken)
            }
            generation++
            currentSource = source
            allowAutoFallback = autoFallbackEnabled
            attemptedEngines.clear()
            pendingSpeed = initialSpeed.coerceIn(0.25f, 4.0f)
            pendingSubtitleDelayMs = subtitleDelayMs
            pendingAudioDelayMs = audioDelayMs
            lastAspectRatio = aspectRatio

            val chosen = EngineRoutingPolicy.selectInitialEngine(source, globalPreference)
            if (_activeEngine.value != chosen) {
                observerJob?.cancel()
                observerJob = null
                val previous = currentPlayer
                previous.detachSurfaceView()
                _playbackStatus.value = PlaybackStatus.IDLE
                previous.release()
            }
            startEngineLocked(
                scope = scope,
                expectedGeneration = generation,
                engineType = chosen,
                source = source,
                startPositionMs = startPositionMs,
                autoPlay = true,
            )
        }
    }

    suspend fun switchEngine(scope: CoroutineScope, target: EngineType) {
        transitionMutex.withLock {
            val source = currentSource ?: return
            if (_activeEngine.value == target) return

            generation++
            val previous = currentPlayer
            val resumePos = previous.positionMs.value
            val currentSpeed = previous.playbackSpeed.value
            val wasPlaying = _playbackStatus.value == PlaybackStatus.PLAYING ||
                _playbackStatus.value == PlaybackStatus.BUFFERING
            pendingSpeed = currentSpeed.coerceIn(0.25f, 4.0f)

            previous.detachSurfaceView()
            observerJob?.cancel()
            observerJob = null
            previous.release()

            startEngineLocked(
                scope = scope,
                expectedGeneration = generation,
                engineType = target,
                source = source,
                startPositionMs = resumePos,
                autoPlay = wasPlaying,
            )
            val label = if (target == EngineType.MPV) "Compatibility player" else "Standard player"
            _events.tryEmit("Switched to $label")
        }
    }

    private suspend fun performFallback(
        scope: CoroutineScope,
        expectedGeneration: Long,
        source: LoomMediaSource,
        resumePosMs: Long,
        speed: Float,
    ) {
        transitionMutex.withLock {
            if (expectedGeneration != generation || currentSource != source) return
            if (_activeEngine.value != EngineType.EXOPLAYER) return

            pendingSpeed = speed.coerceIn(0.25f, 4.0f)
            observerJob?.cancel()
            observerJob = null
            exoEngine.detachSurfaceView()
            exoEngine.release()
            _events.tryEmit("Format fallback: switched to compatibility mode")
            startEngineLocked(
                scope = scope,
                expectedGeneration = generation,
                engineType = EngineType.MPV,
                source = source,
                startPositionMs = resumePosMs,
                autoPlay = true,
            )
        }
    }

    private suspend fun startEngineLocked(
        scope: CoroutineScope,
        expectedGeneration: Long,
        engineType: EngineType,
        source: LoomMediaSource,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) {
        observerJob?.cancel()
        attemptedEngines.add(engineType)
        _activeEngine.value = engineType
        val sourceUri = source.uri.toString()
        _mediaMetadata.value = if (_mediaMetadata.value.uri == sourceUri) {
            _mediaMetadata.value.copy(engineType = engineType)
        } else {
            ActiveMediaMetadata(
                uri = sourceUri,
                title = source.title,
                engineType = engineType,
            )
        }
        val engine = playerFor(engineType)
        surfaceContainer?.let(engine::attachSurfaceView)

        observerJob = scope.launch {
            launch {
                engine.playbackState.collect { _playbackStatus.value = it }
            }
            launch {
                engine.errors.collect { error ->
                    if (allowAutoFallback && EngineRoutingPolicy.canFallback(engineType, error, attemptedEngines)) {
                        val pos = engine.positionMs.value
                        val speed = engine.playbackSpeed.value
                        // Dispatch onto parent scope so observerJob cancellation inside startEngineLocked
                        // does not self-cancel the fallback execution.
                        scope.launch {
                            performFallback(
                                scope = scope,
                                expectedGeneration = expectedGeneration,
                                source = source,
                                resumePosMs = pos,
                                speed = speed,
                            )
                        }
                    } else {
                        _events.tryEmit(error.message)
                    }
                }
            }
        }

        engine.prepare(source = source, startPositionMs = startPositionMs, autoPlay = autoPlay)
        engine.setPlaybackSpeed(pendingSpeed)
        engine.setAspectRatio(lastAspectRatio)
        engine.setSubtitleStyle(lastSubtitleStyle)
        engine.setVolumeFraction(pendingVolumeFraction)
        engine.setSubtitleDelay(pendingSubtitleDelayMs)
        engine.setAudioDelay(pendingAudioDelayMs)
    }

    fun togglePlayPause() {
        when (_playbackStatus.value) {
            PlaybackStatus.PLAYING -> currentPlayer.pause()
            PlaybackStatus.ENDED -> {
                currentPlayer.seekTo(0L)
                currentPlayer.play()
            }
            else -> currentPlayer.play()
        }
    }

    override fun play() {
        if (_playbackStatus.value == PlaybackStatus.ENDED) {
            currentPlayer.seekTo(0L)
        }
        currentPlayer.play()
    }

    override fun pause() = currentPlayer.pause()

    override fun seekTo(positionMs: Long) {
        val duration = currentPlayer.durationMs.value
        val clamped = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        currentPlayer.seekTo(clamped)
    }

    fun seekBy(deltaMs: Long) {
        seekTo(currentPlayer.positionMs.value + deltaMs)
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        pendingSpeed = clamped
        currentPlayer.setPlaybackSpeed(clamped)
    }

    fun selectAudioTrack(trackId: String) = currentPlayer.selectAudioTrack(trackId)

    fun selectSubtitleTrack(trackId: String?) = currentPlayer.selectSubtitleTrack(trackId)

    fun addExternalSubtitle(uri: Uri, mimeType: String?, title: String?, announce: Boolean = true) {
        currentPlayer.addExternalSubtitle(uri, mimeType, title)
        if (announce) {
            _events.tryEmit("Loaded subtitle: ${title ?: uri.lastPathSegment ?: "External"}")
        }
    }

    fun addExternalSubtitles(
        subtitles: List<ExternalSubtitleSource>,
        selectLastAdded: Boolean = true,
    ) {
        currentPlayer.addExternalSubtitles(subtitles, selectLastAdded)
    }

    fun setSubtitleDelay(delayMs: Long) {
        pendingSubtitleDelayMs = delayMs
        currentPlayer.setSubtitleDelay(delayMs)
    }

    fun setAudioDelay(delayMs: Long) {
        pendingAudioDelayMs = delayMs
        currentPlayer.setAudioDelay(delayMs)
    }

    fun setAspectRatio(mode: AspectRatioMode) {
        lastAspectRatio = mode
        currentPlayer.setAspectRatio(mode)
    }

    fun setSubtitleStyle(style: SubtitleStyleConfig) {
        lastSubtitleStyle = style
        exoEngine.setSubtitleStyle(style)
        mpvEngine.setSubtitleStyle(style)
    }

    override fun setAudioFocusDucked(ducked: Boolean) {
        pendingVolumeFraction = if (ducked) 0.25f else 1.0f
        currentPlayer.setVolumeFraction(pendingVolumeFraction)
    }

    fun updateMediaMetadata(metadata: ActiveMediaMetadata) {
        _mediaMetadata.value = metadata.copy(engineType = _activeEngine.value)
    }

    fun setQueueNavigationCallbacks(
        onNext: () -> Unit,
        onPrev: () -> Unit,
        onStop: (() -> Unit)? = null,
    ) {
        onSkipToNextCallback = onNext
        onSkipToPreviousCallback = onPrev
        onStopSessionCallback = onStop
    }

    override fun skipToNext() {
        onSkipToNextCallback?.invoke()
    }

    override fun skipToPrevious() {
        onSkipToPreviousCallback?.invoke()
    }

    override fun stopSession() {
        onStopSessionCallback?.invoke()
        release()
    }

    fun release() {
        synchronized(ownerLock) {
            activeOwnerToken = null
        }
        _activeOwnerFlow.value = null
        _isBackgroundAudioEnabled.value = false
        _activeHostActivityClassName.value = null
        generation++
        observerJob?.cancel()
        observerJob = null
        currentSource = null
        pendingVolumeFraction = 1.0f
        onSkipToNextCallback = null
        onSkipToPreviousCallback = null
        onStopSessionCallback = null
        // Reset status to IDLE before clearing metadata or releasing engines so Media3 adapters
        // collecting these flows synchronously never observe a non-IDLE state with an empty playlist.
        _playbackStatus.value = PlaybackStatus.IDLE
        _mediaMetadata.value = ActiveMediaMetadata()
        exoEngine.release()
        mpvEngine.release()
        surfaceContainer = null
    }
}
