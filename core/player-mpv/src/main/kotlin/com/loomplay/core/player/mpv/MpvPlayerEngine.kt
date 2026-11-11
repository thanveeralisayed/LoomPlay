package com.loomplay.core.player.mpv

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import com.loomplay.core.model.AspectRatioMode
import com.loomplay.core.model.DecoderStats
import com.loomplay.core.model.EngineType
import com.loomplay.core.model.MediaChapter
import com.loomplay.core.model.MediaTrack
import com.loomplay.core.model.MediaTracks
import com.loomplay.core.model.MpvHwDecMode
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.model.SubtitleStyleConfig
import com.loomplay.core.model.VideoSize
import com.loomplay.core.player.api.ExternalSubtitleSource
import com.loomplay.core.player.api.ILoomPlayer
import com.loomplay.core.player.api.LoomMediaSource
import com.loomplay.core.player.api.LoomPlayerError
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class MpvPlayerEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ILoomPlayer {

    override val engineType: EngineType = EngineType.MPV

    // Dedicated single-thread dispatcher serializing libmpv lifecycle, commands, and property reads.
    private val mpvDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val mpvScope = CoroutineScope(SupervisorJob() + mpvDispatcher)

    // Guards mpv instance pointer + active surface attachment. NEVER held during destroy()
    // and NEVER acquired inside mpvObserver/mpvLogObserver native callbacks.
    private val mpvLock = Any()
    private val engineGeneration = AtomicLong(0L)

    private val _playbackState = MutableStateFlow(PlaybackStatus.IDLE)
    override val playbackState: StateFlow<PlaybackStatus> = _playbackState.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _bufferedPositionMs = MutableStateFlow(0L)
    override val bufferedPositionMs: StateFlow<Long> = _bufferedPositionMs.asStateFlow()

    private val _videoSize = MutableStateFlow(VideoSize())
    override val videoSize: StateFlow<VideoSize> = _videoSize.asStateFlow()

    private val _tracks = MutableStateFlow(MediaTracks())
    override val tracks: StateFlow<MediaTracks> = _tracks.asStateFlow()

    private val _chapters = MutableStateFlow<List<MediaChapter>>(emptyList())
    override val chapters: StateFlow<List<MediaChapter>> = _chapters.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _stats = MutableStateFlow(defaultStats())
    override val stats: StateFlow<DecoderStats> = _stats.asStateFlow()

    private val _errors = MutableSharedFlow<LoomPlayerError>(extraBufferCapacity = 8)
    override val errors: SharedFlow<LoomPlayerError> = _errors.asSharedFlow()

    private var mpv: MPVLib? = null
    private var surfaceView: SurfaceView? = null
    private var activeHolder: SurfaceHolder? = null
    private var isSurfaceReady = false

    private data class PendingExternalSub(
        val uri: Uri,
        val title: String?,
        val autoSelect: Boolean,
    )

    private var activePfd: ParcelFileDescriptor? = null
    private val extraSubPfds = mutableListOf<ParcelFileDescriptor>()
    private val pendingExternalSubs = mutableListOf<PendingExternalSub>()
    private val addedSubUris = mutableSetOf<Uri>()

    private var hwDecMode: MpvHwDecMode = MpvHwDecMode.AUTO
    private var customConfigText: String = ""
    private var appliedHwDec: MpvHwDecMode? = null
    private var appliedCustomConfig: String? = null
    private var subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig()
    private var activeAspectMode: AspectRatioMode = AspectRatioMode.FIT
    private var activeVolumeFraction: Float = 1.0f

    @Volatile
    private var isPausedFlag = false

    @Volatile
    private var isPausedForCache = false

    @Volatile
    private var isEofReached = false

    @Volatile
    private var hadLoadedCurrentFile = false

    @Volatile
    private var hasActiveNativeLoad = false

    @Volatile
    private var expectingReplaceEndFile = false

    @Volatile
    private var lastErrorLogLine: String? = null

    @Volatile
    private var pendingSeekTargetMs: Long = -1L

    @Volatile
    private var pendingSeekSuppressUntilMs: Long = 0L

    @Volatile
    private var latestHwDecCurrent = ""

    @Volatile
    private var latestVideoCodec: String? = null

    @Volatile
    private var latestVideoFormat: String? = null

    @Volatile
    private var latestAudioCodec: String? = null

    fun configureMpvOptions(hwDec: MpvHwDecMode, customConfig: String) {
        hwDecMode = hwDec
        customConfigText = customConfig
    }

    private fun ensureMpvInitialized(forceRecreate: Boolean = false): MPVLib? {
        var oldToDestroy: MPVLib? = null
        val result: MPVLib?
        synchronized(mpvLock) {
            val configChanged = appliedHwDec != hwDecMode || appliedCustomConfig != customConfigText
            if (mpv != null && !forceRecreate && !configChanged) {
                return mpv
            }
            if (mpv != null) {
                oldToDestroy = mpv
                mpv = null
                hasActiveNativeLoad = false
                expectingReplaceEndFile = false
                runCatching {
                    oldToDestroy?.removeObserver(mpvObserver)
                    oldToDestroy?.removeLogObserver(mpvLogObserver)
                    if (isSurfaceReady) {
                        oldToDestroy?.setPropertyString("vo", "null")
                        oldToDestroy?.setPropertyString("force-window", "no")
                        oldToDestroy?.detachSurface()
                    }
                }
            }
            result = createAndInitInstanceLocked()
        }
        oldToDestroy?.let { old ->
            runCatching { old.destroy() }
        }
        return result
    }

    private fun createAndInitInstanceLocked(): MPVLib? {
        val instance = runCatching { MPVLib.create(context) }.getOrNull() ?: return null
        return try {
            val validSurface = if (isSurfaceReady) activeHolder?.surface?.takeIf { it.isValid } else null
            instance.setOptionString("vo", if (validSurface != null) "gpu" else "null")
            instance.setOptionString("gpu-context", "android")
            instance.setOptionString("opengl-es", "yes")
            instance.setOptionString("hwdec", hwDecMode.mpvValue)
            instance.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            instance.setOptionString("ao", "audiotrack,opensles")
            // Disabled fuzzy sidecar scan because LoomPlay manages sidecar subtitles explicitly.
            instance.setOptionString("sub-auto", "no")
            instance.setOptionString("keep-open", "yes")
            instance.setOptionString("save-position-on-quit", "no")
            instance.setOptionString("ytdl", "no")
            instance.setOptionString("volume", (activeVolumeFraction * 100f).roundToInt().toString())

            applySubtitleStyleOptions(instance::setOptionString)
            applyCustomConfig(instance, customConfigText)

            instance.init()
            instance.addLogObserver(mpvLogObserver)
            instance.addObserver(mpvObserver)
            observeMpvProperties(instance)

            if (validSurface != null) {
                instance.attachSurface(validSurface)
                instance.setPropertyString("force-window", "yes")
                instance.setPropertyString("vo", "gpu")
                applyAspectRatioLocked(instance, activeAspectMode)
            }

            appliedHwDec = hwDecMode
            appliedCustomConfig = customConfigText
            mpv = instance
            instance
        } catch (t: Throwable) {
            runCatching { instance.removeObserver(mpvObserver) }
            runCatching { instance.removeLogObserver(mpvLogObserver) }
            runCatching { instance.destroy() }
            lastErrorLogLine = t.message
            null
        }
    }

    private fun applyCustomConfig(instance: MPVLib, rawConfig: String) {
        parseMpvConfigLines(rawConfig).forEach { (key, value) ->
            runCatching { instance.setOptionString(key, value) }
        }
    }

    private fun observeMpvProperties(instance: MPVLib) {
        instance.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        instance.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        instance.observeProperty("demuxer-cache-duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        instance.observeProperty("estimated-vf-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        instance.observeProperty("video-bitrate", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        instance.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        instance.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        instance.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        instance.observeProperty("video-params/w", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        instance.observeProperty("video-params/h", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        instance.observeProperty("frame-drop-count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        instance.observeProperty("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        instance.observeProperty("chapter-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        instance.observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        instance.observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        instance.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        instance.observeProperty("video-codec", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        instance.observeProperty("video-format", MPVLib.MpvFormat.MPV_FORMAT_STRING)
        instance.observeProperty("audio-codec-name", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            synchronized(mpvLock) {
                activeHolder = holder
                isSurfaceReady = true
                mpv?.let { instance ->
                    instance.attachSurface(holder.surface)
                    instance.setPropertyString("force-window", "yes")
                    instance.setPropertyString("vo", "gpu")
                    applyAspectRatioLocked(instance, activeAspectMode)
                }
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            synchronized(mpvLock) {
                if (activeHolder === holder) {
                    mpv?.setPropertyString("android-surface-size", "${width}x${height}")
                }
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            synchronized(mpvLock) {
                if (activeHolder === holder) {
                    activeHolder = null
                    if (isSurfaceReady) {
                        isSurfaceReady = false
                        mpv?.let { instance ->
                            instance.setPropertyString("vo", "null")
                            instance.setPropertyString("force-window", "no")
                            instance.detachSurface()
                        }
                    }
                }
            }
        }
    }

    override fun attachSurfaceView(container: FrameLayout) {
        val view = surfaceView ?: SurfaceView(container.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            holder.addCallback(surfaceCallback)
            surfaceView = this
        }
        if (view.parent !== container) {
            (view.parent as? ViewGroup)?.removeView(view)
            container.removeAllViews()
            container.addView(view)
        }
    }

    override fun detachSurfaceView() {
        synchronized(mpvLock) {
            if (isSurfaceReady) {
                runCatching {
                    mpv?.setPropertyString("vo", "null")
                    mpv?.setPropertyString("force-window", "no")
                    mpv?.detachSurface()
                }
                isSurfaceReady = false
            }
            activeHolder = null
        }
        (surfaceView?.parent as? ViewGroup)?.removeView(surfaceView)
    }

    override suspend fun prepare(
        source: LoomMediaSource,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) = withContext(mpvDispatcher) {
        val instance = ensureMpvInitialized()
        if (instance == null) {
            _playbackState.value = PlaybackStatus.ERROR
            _errors.tryEmit(
                LoomPlayerError.PlaybackFailure(
                    lastErrorLogLine ?: "Unable to initialize libmpv native engine",
                ),
            )
            return@withContext
        }

        val pfdToOpen = if (source.uri.scheme == "content") {
            runCatching { context.contentResolver.openFileDescriptor(source.uri, "r") }.getOrNull()
        } else {
            null
        }

        if (source.uri.scheme == "content" && pfdToOpen == null) {
            synchronized(mpvLock) {
                closeMediaPfdsLocked()
            }
            _playbackState.value = PlaybackStatus.ERROR
            _errors.tryEmit(
                LoomPlayerError.NetworkOrIo("Unable to open media URI (permission denied or file missing)"),
            )
            return@withContext
        }

        synchronized(mpvLock) {
            closeMediaPfdsLocked()

            val targetUri = if (pfdToOpen != null) {
                activePfd = pfdToOpen
                "fd://${pfdToOpen.fd}"
            } else {
                source.uri.toString()
            }

            val userAgent = source.headers.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            val referrer = source.headers.entries
                .firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
            val headerFields = encodeMpvHeaderFields(source.headers)

            instance.setPropertyString("user-agent", userAgent ?: "libmpv")
            instance.setPropertyString("referrer", referrer.orEmpty())
            instance.setPropertyString("http-header-fields", headerFields)

            if (startPositionMs > 0L) {
                instance.setOptionString("start", (startPositionMs / 1000.0).toString())
            } else {
                instance.setOptionString("start", "none")
            }

            instance.setPropertyString("aid", "auto")
            instance.setPropertyString("sid", "auto")
            instance.setPropertyDouble("sub-delay", 0.0)
            instance.setPropertyDouble("audio-delay", 0.0)
            instance.setPropertyDouble("speed", 1.0)
            applyAspectRatioLocked(instance, activeAspectMode)

            _tracks.value = MediaTracks()
            _chapters.value = emptyList()
            _durationMs.value = 0L
            _bufferedPositionMs.value = 0L
            _videoSize.value = VideoSize()
            _playbackSpeed.value = 1.0f
            latestHwDecCurrent = ""
            latestVideoCodec = null
            latestVideoFormat = null
            latestAudioCodec = null
            _stats.value = defaultStats()
            _positionMs.value = startPositionMs.coerceAtLeast(0L)

            pendingExternalSubs.clear()
            addedSubUris.clear()
            source.initialExternalSubtitles.forEach { sub ->
                if (addedSubUris.add(sub.uri)) {
                    pendingExternalSubs += PendingExternalSub(
                        uri = sub.uri,
                        title = sub.title,
                        autoSelect = false,
                    )
                }
            }

            hadLoadedCurrentFile = false
            isEofReached = false
            isPausedForCache = false
            lastErrorLogLine = null
            pendingSeekTargetMs = -1L
            expectingReplaceEndFile = hasActiveNativeLoad
            hasActiveNativeLoad = true
            isPausedFlag = !autoPlay
            instance.setPropertyBoolean("pause", !autoPlay)
            _playbackState.value = PlaybackStatus.BUFFERING
            instance.command(arrayOf("loadfile", targetUri, "replace"))
        }
    }

    override fun play() {
        val gen = engineGeneration.get()
        mpvScope.launch {
            if (gen != engineGeneration.get()) return@launch
            synchronized(mpvLock) {
                mpv?.setPropertyBoolean("pause", false)
            }
        }
    }

    override fun pause() {
        val gen = engineGeneration.get()
        mpvScope.launch {
            if (gen != engineGeneration.get()) return@launch
            synchronized(mpvLock) {
                mpv?.setPropertyBoolean("pause", true)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        pendingSeekTargetMs = target
        pendingSeekSuppressUntilMs = (System.nanoTime() / 1_000_000L) + 800L
        _positionMs.value = target
        val gen = engineGeneration.get()
        mpvScope.launch {
            if (gen != engineGeneration.get()) return@launch
            synchronized(mpvLock) {
                mpv?.command(arrayOf("seek", (target / 1000.0).toString(), "absolute", "exact"))
            }
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        val gen = engineGeneration.get()
        mpvScope.launch {
            if (gen != engineGeneration.get()) return@launch
            synchronized(mpvLock) {
                mpv?.setPropertyDouble("speed", speed.toDouble())
            }
        }
    }

    override fun selectAudioTrack(trackId: String) {
        val gen = engineGeneration.get()
        mpvScope.launch {
            if (gen != engineGeneration.get()) return@launch
            synchronized(mpvLock) {
                mpv?.setPropertyString("aid", trackId)
            }
            refreshTracksSafe()
        }
    }

    override fun selectSubtitleTrack(trackId: String?) {
        val gen = engineGeneration.get()
        mpvScope.launch {
            if (gen != engineGeneration.get()) return@launch
            synchronized(mpvLock) {
                mpv?.setPropertyString("sid", trackId ?: "no")
            }
            refreshTracksSafe()
        }
    }

    override fun addExternalSubtitle(uri: Uri, mimeType: String?, title: String?) {
        addExternalSubtitles(
            subtitles = listOf(ExternalSubtitleSource(uri = uri, mimeType = mimeType, title = title)),
            selectLastAdded = true,
        )
    }

    override fun addExternalSubtitles(
        subtitles: List<ExternalSubtitleSource>,
        selectLastAdded: Boolean,
    ) {
        if (subtitles.isEmpty()) return
        val gen = engineGeneration.get()
        mpvScope.launch {
            if (gen != engineGeneration.get()) return@launch
            var addedAny = false
            subtitles.forEachIndexed { index, sub ->
                val autoSelect = selectLastAdded && index == subtitles.lastIndex
                val shouldQueue: Boolean
                synchronized(mpvLock) {
                    if (!addedSubUris.add(sub.uri)) return@forEachIndexed
                    if (!hadLoadedCurrentFile || mpv == null) {
                        pendingExternalSubs += PendingExternalSub(
                            uri = sub.uri,
                            title = sub.title,
                            autoSelect = autoSelect,
                        )
                        shouldQueue = true
                    } else {
                        shouldQueue = false
                    }
                }
                if (!shouldQueue && executeSubAddSafe(sub.uri, sub.title, autoSelect)) {
                    addedAny = true
                }
            }
            if (addedAny) {
                refreshTracksSafe()
            }
        }
    }

    private fun executeSubAddSafe(uri: Uri, title: String?, autoSelect: Boolean): Boolean {
        val subPfd = if (uri.scheme == "content") {
            runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        } else {
            null
        }
        if (uri.scheme == "content" && subPfd == null) {
            return false
        }
        return synchronized(mpvLock) {
            val instance = mpv
            if (instance == null || !hadLoadedCurrentFile) {
                runCatching { subPfd?.close() }
                return@synchronized false
            }
            val path = if (subPfd != null) {
                extraSubPfds += subPfd
                "fd://${subPfd.fd}"
            } else {
                uri.toString()
            }
            val selectFlag = if (autoSelect) "select" else "auto"
            val cmd = if (!title.isNullOrBlank()) {
                arrayOf("sub-add", path, selectFlag, title)
            } else {
                arrayOf("sub-add", path, selectFlag)
            }
            instance.command(cmd)
            true
        }
    }

    private fun flushPendingExternalSubtitlesSafe() {
        val queued = synchronized(mpvLock) {
            if (pendingExternalSubs.isEmpty()) return
            pendingExternalSubs.toList().also { pendingExternalSubs.clear() }
        }
        queued.forEach { sub ->
            executeSubAddSafe(sub.uri, sub.title, sub.autoSelect)
        }
    }

    override fun setSubtitleDelay(delayMs: Long) {
        val gen = engineGeneration.get()
        mpvScope.launch {
            if (gen != engineGeneration.get()) return@launch
            synchronized(mpvLock) {
                mpv?.setPropertyDouble("sub-delay", delayMs / 1000.0)
            }
        }
    }

    override fun setAudioDelay(delayMs: Long) {
        val gen = engineGeneration.get()
        mpvScope.launch {
            if (gen != engineGeneration.get()) return@launch
            synchronized(mpvLock) {
                mpv?.setPropertyDouble("audio-delay", delayMs / 1000.0)
            }
        }
    }

    override fun setAspectRatio(mode: AspectRatioMode) {
        activeAspectMode = mode
        val gen = engineGeneration.get()
        mpvScope.launch {
            if (gen != engineGeneration.get()) return@launch
            synchronized(mpvLock) {
                val instance = mpv ?: return@launch
                applyAspectRatioLocked(instance, mode)
            }
        }
    }

    private fun applyAspectRatioLocked(instance: MPVLib, mode: AspectRatioMode) {
        when (mode) {
            AspectRatioMode.FIT -> {
                instance.setPropertyString("keepaspect", "yes")
                instance.setPropertyString("video-aspect-override", "-1")
                instance.setPropertyDouble("panscan", 0.0)
            }
            AspectRatioMode.FILL -> {
                instance.setPropertyString("keepaspect", "yes")
                instance.setPropertyString("video-aspect-override", "-1")
                instance.setPropertyDouble("panscan", 1.0)
            }
            AspectRatioMode.STRETCH -> {
                instance.setPropertyString("keepaspect", "no")
                instance.setPropertyString("video-aspect-override", "-1")
                instance.setPropertyDouble("panscan", 0.0)
            }
            AspectRatioMode.RATIO_16_9 -> {
                instance.setPropertyString("keepaspect", "yes")
                instance.setPropertyString("video-aspect-override", "16:9")
                instance.setPropertyDouble("panscan", 0.0)
            }
            AspectRatioMode.RATIO_4_3 -> {
                instance.setPropertyString("keepaspect", "yes")
                instance.setPropertyString("video-aspect-override", "4:3")
                instance.setPropertyDouble("panscan", 0.0)
            }
        }
    }

    override fun setSubtitleStyle(style: SubtitleStyleConfig) {
        subtitleStyle = style
        val gen = engineGeneration.get()
        mpvScope.launch {
            if (gen != engineGeneration.get()) return@launch
            synchronized(mpvLock) {
                val instance = mpv ?: return@launch
                instance.setPropertyDouble("sub-scale", (style.fontSizeSp / 20.0).coerceIn(0.5, 2.2))
                instance.setPropertyDouble("sub-border-size", if (style.outlineEnabled) 2.5 else 0.0)
                instance.setPropertyString(
                    "sub-back-color",
                    String.format(Locale.US, "0/0/0/%.2f", style.backgroundOpacity.coerceIn(0f, 1f)),
                )
                instance.setPropertyInt("sub-margin-y", computeMpvMarginY(style.verticalMarginPercent))
                instance.setPropertyString("sub-ass-override", if (style.mpvAssOverride) "force" else "no")
            }
        }
    }

    override fun setVolumeFraction(fraction: Float) {
        activeVolumeFraction = fraction.coerceIn(0f, 1f)
        val gen = engineGeneration.get()
        mpvScope.launch {
            if (gen != engineGeneration.get()) return@launch
            synchronized(mpvLock) {
                mpv?.setPropertyDouble("volume", activeVolumeFraction * 100.0)
            }
        }
    }

    private fun applySubtitleStyleOptions(applyOption: (String, String) -> Unit) {
        val style = subtitleStyle
        applyOption("sub-scale", String.format(Locale.US, "%.2f", (style.fontSizeSp / 20.0).coerceIn(0.5, 2.2)))
        applyOption("sub-border-size", if (style.outlineEnabled) "2.5" else "0.0")
        applyOption("sub-back-color", String.format(Locale.US, "0/0/0/%.2f", style.backgroundOpacity.coerceIn(0f, 1f)))
        applyOption("sub-margin-y", computeMpvMarginY(style.verticalMarginPercent).toString())
        applyOption("sub-ass-override", if (style.mpvAssOverride) "force" else "no")
    }

    override fun release() {
        engineGeneration.incrementAndGet()
        detachSurfaceView()
        surfaceView?.holder?.removeCallback(surfaceCallback)
        surfaceView = null

        val instanceToDestroy: MPVLib?
        synchronized(mpvLock) {
            instanceToDestroy = mpv
            mpv = null
            appliedHwDec = null
            appliedCustomConfig = null
            pendingExternalSubs.clear()
            addedSubUris.clear()
            hadLoadedCurrentFile = false
            hasActiveNativeLoad = false
            expectingReplaceEndFile = false
            isEofReached = false
            isPausedForCache = false
            lastErrorLogLine = null
            pendingSeekTargetMs = -1L
            instanceToDestroy?.removeObserver(mpvObserver)
            instanceToDestroy?.removeLogObserver(mpvLogObserver)
            closeMediaPfdsLocked()
        }

        // Destroy off Main thread on the serialized mpvDispatcher so a subsequent prepare()
        // executes strictly after native teardown without blocking UI or racing lock order.
        instanceToDestroy?.let { old ->
            mpvScope.launch {
                runCatching { old.destroy() }
            }
        }

        latestHwDecCurrent = ""
        latestVideoCodec = null
        latestVideoFormat = null
        latestAudioCodec = null
        _playbackState.value = PlaybackStatus.IDLE
        _positionMs.value = 0L
        _durationMs.value = 0L
        _bufferedPositionMs.value = 0L
        _videoSize.value = VideoSize()
        _tracks.value = MediaTracks()
        _chapters.value = emptyList()
        _playbackSpeed.value = 1.0f
        _stats.value = defaultStats()
    }

    private fun closeMediaPfdsLocked() {
        runCatching { activePfd?.close() }
        activePfd = null
        extraSubPfds.forEach { runCatching { it.close() } }
        extraSubPfds.clear()
    }

    private val mpvLogObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            if (level == MPVLib.MpvLogLevel.MPV_LOG_LEVEL_FATAL ||
                level == MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR
            ) {
                val cleaned = text.trim()
                if (cleaned.isNotEmpty()) {
                    lastErrorLogLine = "[$prefix] $cleaned"
                }
            }
        }
    }

    // Native event-thread callback: lock-free StateFlow updates & serialized async dispatches.
    // Never acquires mpvLock or performs blocking JNI queries here.
    private val mpvObserver = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit

        override fun eventProperty(property: String, value: Long) {
            when (property) {
                "video-params/w" -> {
                    val w = value.toInt().coerceAtLeast(0)
                    _videoSize.update { it.copy(width = w) }
                    _stats.update { it.copy(width = w) }
                }
                "video-params/h" -> {
                    val h = value.toInt().coerceAtLeast(0)
                    _videoSize.update { it.copy(height = h) }
                    _stats.update { it.copy(height = h) }
                }
                "frame-drop-count" -> {
                    _stats.update { it.copy(droppedFrames = value.coerceAtLeast(0L)) }
                }
                "track-list/count" -> {
                    val gen = engineGeneration.get()
                    mpvScope.launch {
                        if (gen == engineGeneration.get()) refreshTracksSafe()
                    }
                }
                "chapter-list/count" -> {
                    val gen = engineGeneration.get()
                    mpvScope.launch {
                        if (gen == engineGeneration.get()) refreshChaptersSafe()
                    }
                }
            }
        }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> {
                    val posMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                    val nowMs = System.nanoTime() / 1_000_000L
                    val pendingTarget = pendingSeekTargetMs
                    if (pendingTarget >= 0L && nowMs < pendingSeekSuppressUntilMs) {
                        if (abs(posMs - pendingTarget) <= 1_200L) {
                            pendingSeekTargetMs = -1L
                            _positionMs.value = posMs
                        }
                    } else {
                        pendingSeekTargetMs = -1L
                        _positionMs.value = posMs
                    }
                }
                "duration" -> {
                    _durationMs.value = (value * 1000.0).toLong().coerceAtLeast(0L)
                }
                "demuxer-cache-duration" -> {
                    val cacheSec = value.toFloat().coerceAtLeast(0f)
                    val pos = _positionMs.value
                    _bufferedPositionMs.value = pos + (value * 1000.0).toLong().coerceAtLeast(0L)
                    _stats.update { it.copy(bufferAheadSec = cacheSec) }
                }
                "estimated-vf-fps" -> {
                    _stats.update { it.copy(fps = value.toFloat().coerceAtLeast(0f)) }
                }
                "video-bitrate" -> {
                    val mbps = (value / 1_000_000.0).toFloat().coerceAtLeast(0f)
                    _stats.update { it.copy(bitrateMbps = mbps) }
                }
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> {
                    isPausedFlag = value
                    if (hadLoadedCurrentFile && !isPausedForCache) {
                        if (!isEofReached) {
                            _playbackState.value = if (value) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING
                        } else if (!value && _playbackState.value == PlaybackStatus.ENDED) {
                            isEofReached = false
                            _playbackState.value = PlaybackStatus.PLAYING
                        }
                    }
                }
                "paused-for-cache" -> {
                    isPausedForCache = value
                    if (value) {
                        _playbackState.value = PlaybackStatus.BUFFERING
                    } else if (hadLoadedCurrentFile && _playbackState.value == PlaybackStatus.BUFFERING) {
                        _playbackState.value = when {
                            isEofReached -> PlaybackStatus.ENDED
                            isPausedFlag -> PlaybackStatus.PAUSED
                            else -> PlaybackStatus.PLAYING
                        }
                    }
                }
                "eof-reached" -> {
                    isEofReached = value
                    if (value) {
                        _playbackState.value = PlaybackStatus.ENDED
                    } else if (hadLoadedCurrentFile && _playbackState.value == PlaybackStatus.ENDED && !isPausedForCache) {
                        _playbackState.value = if (isPausedFlag) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING
                    }
                }
            }
        }

        override fun eventProperty(property: String, value: String) {
            when (property) {
                "aid", "sid" -> {
                    val gen = engineGeneration.get()
                    mpvScope.launch {
                        if (gen == engineGeneration.get()) refreshTracksSafe()
                    }
                }
                "hwdec-current" -> {
                    latestHwDecCurrent = value
                    updateDecoderDescriptor()
                }
                "video-codec" -> {
                    latestVideoCodec = value.ifBlank { null }
                    updateDecoderDescriptor()
                }
                "video-format" -> {
                    latestVideoFormat = value.ifBlank { null }
                    updateDecoderDescriptor()
                }
                "audio-codec-name" -> {
                    latestAudioCodec = value.ifBlank { null }
                    updateDecoderDescriptor()
                }
            }
        }

        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                    hasActiveNativeLoad = true
                    expectingReplaceEndFile = false
                    isEofReached = false
                }
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    expectingReplaceEndFile = false
                    isEofReached = false
                    hadLoadedCurrentFile = true
                    _playbackState.value = when {
                        isPausedForCache -> PlaybackStatus.BUFFERING
                        isPausedFlag -> PlaybackStatus.READY
                        else -> PlaybackStatus.PLAYING
                    }
                    val gen = engineGeneration.get()
                    mpvScope.launch {
                        if (gen == engineGeneration.get()) {
                            flushPendingExternalSubtitlesSafe()
                            refreshTracksSafe()
                            refreshChaptersSafe()
                        }
                    }
                }
                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    pendingSeekTargetMs = -1L
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    if (expectingReplaceEndFile) {
                        expectingReplaceEndFile = false
                        return
                    }
                    hasActiveNativeLoad = false
                    if (isEofReached) {
                        _playbackState.value = PlaybackStatus.ENDED
                        return
                    }
                    val current = _playbackState.value
                    if (current != PlaybackStatus.ENDED && current != PlaybackStatus.IDLE) {
                        _playbackState.value = PlaybackStatus.ERROR
                        _errors.tryEmit(mapMpvError(lastErrorLogLine, hadLoadedCurrentFile))
                    }
                }
            }
        }
    }

    private fun updateDecoderDescriptor() {
        val hwdec = latestHwDecCurrent
        val isHw = hwdec.isNotBlank() && hwdec != "no"
        _stats.update { current ->
            current.copy(
                decoderName = if (isHw) "libmpv • $hwdec" else "libmpv • SW",
                isHardwareAccelerated = isHw,
                videoCodec = latestVideoCodec ?: latestVideoFormat,
                audioCodec = latestAudioCodec,
                audioEngine = "AudioTrack / OpenSL",
                renderSurface = "OpenGL ES (gpu)",
            )
        }
    }

    private fun refreshTracksSafe() {
        synchronized(mpvLock) {
            val instance = mpv ?: return
            val count = instance.getPropertyInt("track-list/count") ?: 0
            val videoList = mutableListOf<MediaTrack>()
            val audioList = mutableListOf<MediaTrack>()
            val subList = mutableListOf<MediaTrack>()

            for (i in 0 until count) {
                val id = instance.getPropertyInt("track-list/$i/id")?.toString() ?: continue
                val type = instance.getPropertyString("track-list/$i/type") ?: continue
                val title = instance.getPropertyString("track-list/$i/title")
                val lang = instance.getPropertyString("track-list/$i/lang")
                val codec = instance.getPropertyString("track-list/$i/codec")
                val selected = instance.getPropertyBoolean("track-list/$i/selected") ?: false

                val display = listOfNotNull(title?.takeIf { it.isNotBlank() }, lang?.uppercase())
                    .joinToString(" • ")
                    .ifBlank { "Track $id" }

                val track = MediaTrack(
                    id = id,
                    title = display,
                    language = lang,
                    codec = codec,
                    isSelected = selected,
                )
                when (type) {
                    "video" -> videoList += track
                    "audio" -> audioList += track
                    "sub" -> subList += track
                }
            }
            _tracks.value = MediaTracks(videoList, audioList, subList)
        }
    }

    private fun refreshChaptersSafe() {
        synchronized(mpvLock) {
            val instance = mpv ?: return
            val count = instance.getPropertyInt("chapter-list/count") ?: 0
            if (count <= 0) {
                _chapters.value = emptyList()
                return
            }
            val list = ArrayList<MediaChapter>(count)
            for (i in 0 until count) {
                val timeSec = instance.getPropertyDouble("chapter-list/$i/time") ?: continue
                val rawTitle = instance.getPropertyString("chapter-list/$i/title")
                val title = rawTitle?.takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}"
                list += MediaChapter(
                    title = title,
                    startMs = (timeSec * 1000.0).toLong().coerceAtLeast(0L),
                )
            }
            _chapters.value = list.sortedBy { it.startMs }
        }
    }

    private fun defaultStats() = DecoderStats(
        activeEngine = EngineType.MPV,
        decoderName = "libmpv",
        isHardwareAccelerated = true,
        audioEngine = "AudioTrack",
        renderSurface = "OpenGL ES (gpu)",
    )
}
