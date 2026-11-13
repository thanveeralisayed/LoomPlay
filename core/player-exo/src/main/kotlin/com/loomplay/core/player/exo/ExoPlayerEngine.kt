package com.loomplay.core.player.exo

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize as Media3VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.loomplay.core.model.AspectRatioMode
import com.loomplay.core.model.DecoderStats
import com.loomplay.core.model.EngineType
import com.loomplay.core.model.MediaChapter
import com.loomplay.core.model.MediaTrack
import com.loomplay.core.model.MediaTracks
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.model.SubtitleStyleConfig
import com.loomplay.core.model.VideoSize
import com.loomplay.core.player.api.ExternalSubtitleSource
import com.loomplay.core.player.api.ILoomPlayer
import com.loomplay.core.player.api.LoomMediaSource
import com.loomplay.core.player.api.LoomPlayerError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

class ExoPlayerEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ILoomPlayer {

    override val engineType: EngineType = EngineType.EXOPLAYER

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var positionTickerJob: Job? = null

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

    private var exoPlayer: ExoPlayer? = null
    private var configuredHeaders: Map<String, String>? = null
    private var playerView: LoomPlayerView? = null
    private var currentSource: LoomMediaSource? = null
    private var latestTracks: Tracks = Tracks.EMPTY
    private var activeAspectMode: AspectRatioMode = AspectRatioMode.FIT
    private var activeSubtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig()
    private var activeVolumeFraction: Float = 1.0f
    private val extraSubtitles = mutableListOf<MediaItem.SubtitleConfiguration>()
    private var activeVideoDecoderName: String? = null
    private var isVideoHwAccelerated: Boolean = true

    @OptIn(UnstableApi::class)
    private fun ensurePlayer(headers: Map<String, String> = emptyMap()): ExoPlayer {
        val current = exoPlayer
        if (current != null && configuredHeaders == headers) {
            return current
        }
        val preservedTrackParams = current?.trackSelectionParameters
        if (current != null) {
            updateTicker(false)
            current.release()
            exoPlayer = null
        }

        val httpFactory = DefaultHttpDataSource.Factory().apply {
            setAllowCrossProtocolRedirects(true)
            setConnectTimeoutMs(15_000)
            setReadTimeoutMs(15_000)
            if (headers.isNotEmpty()) {
                setDefaultRequestProperties(headers)
            }
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setMediaCodecSelector(MediaCodecSelector.DEFAULT)
            setEnableDecoderFallback(true)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val extractorsFactory = DefaultExtractorsFactory().apply {
            setConstantBitrateSeekingEnabled(true)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .build()
            .apply {
                volume = activeVolumeFraction
                if (preservedTrackParams != null) {
                    trackSelectionParameters = preservedTrackParams
                }
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long,
                    ) {
                        activeVideoDecoderName = decoderName
                        val lower = decoderName.lowercase()
                        isVideoHwAccelerated = !(lower.startsWith("omx.google.") ||
                            lower.startsWith("c2.android.") ||
                            lower.startsWith("omx.ffmpeg.") ||
                            lower.contains("sw") ||
                            lower.contains("software"))
                        syncMetrics(this@apply)
                    }
                })
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _playbackState.value = when (playbackState) {
                            Player.STATE_IDLE -> PlaybackStatus.IDLE
                            Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
                            Player.STATE_READY -> if (isPlaying) PlaybackStatus.PLAYING else PlaybackStatus.READY
                            Player.STATE_ENDED -> PlaybackStatus.ENDED
                            else -> PlaybackStatus.IDLE
                        }
                        syncMetrics(this@apply)
                        updateTicker(isPlaying || playbackState == Player.STATE_BUFFERING)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (playbackState == Player.STATE_READY) {
                            _playbackState.value = if (isPlaying) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED
                        }
                        syncMetrics(this@apply)
                        updateTicker(isPlaying || playbackState == Player.STATE_BUFFERING)
                    }

                    override fun onVideoSizeChanged(videoSize: Media3VideoSize) {
                        _videoSize.value = VideoSize(videoSize.width, videoSize.height)
                        playerView?.applyAspectMode(activeAspectMode)
                        syncMetrics(this@apply)
                    }

                    override fun onMetadata(metadata: Metadata) {
                        val extracted = mutableListOf<MediaChapter>()
                        for (i in 0 until metadata.length()) {
                            val entry = metadata.get(i)
                            if (entry is Chapter && !entry.isHidden && entry.startTimeMs >= 0L) {
                                val title = entry.title?.value?.takeIf { it.isNotBlank() }
                                    ?: "Chapter ${_chapters.value.size + extracted.size + 1}"
                                extracted += MediaChapter(
                                    title = title,
                                    startMs = entry.startTimeMs,
                                )
                            }
                        }
                        if (extracted.isNotEmpty()) {
                            _chapters.value = (_chapters.value + extracted)
                                .distinctBy { it.startMs }
                                .sortedBy { it.startMs }
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        latestTracks = tracks
                        val videoList = mutableListOf<MediaTrack>()
                        val audioList = mutableListOf<MediaTrack>()
                        val subList = mutableListOf<MediaTrack>()

                        tracks.groups.forEachIndexed { groupIdx, group ->
                            for (trackIdx in 0 until group.length) {
                                val fmt = group.getTrackFormat(trackIdx)
                                val id = "$groupIdx:$trackIdx"
                                val label = fmt.label ?: fmt.language ?: "Track ${trackIdx + 1}"
                                val track = MediaTrack(
                                    id = id,
                                    title = label,
                                    language = fmt.language,
                                    codec = fmt.codecs ?: fmt.sampleMimeType,
                                    isSelected = group.isTrackSelected(trackIdx),
                                )
                                when (group.type) {
                                    C.TRACK_TYPE_VIDEO -> videoList += track
                                    C.TRACK_TYPE_AUDIO -> audioList += track
                                    C.TRACK_TYPE_TEXT -> subList += track
                                }
                            }
                        }
                        _tracks.value = MediaTracks(
                            videoTracks = videoList,
                            audioTracks = audioList,
                            subtitleTracks = subList,
                        )
                        syncMetrics(this@apply)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        _playbackState.value = PlaybackStatus.ERROR
                        updateTicker(false)
                        val rootCause = error.cause
                        val mapped = if (rootCause is UnrecognizedInputFormatException ||
                            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                        ) {
                            LoomPlayerError.UnsupportedFormat(
                                message = error.message ?: "Unsupported media format",
                                cause = error,
                            )
                        } else if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                        ) {
                            LoomPlayerError.NetworkOrIo(
                                message = error.message ?: "Network or file I/O error",
                                cause = error,
                            )
                        } else {
                            LoomPlayerError.PlaybackFailure(
                                message = error.message ?: "ExoPlayer playback error",
                                cause = error,
                            )
                        }
                        _errors.tryEmit(mapped)
                    }
                })
                configuredHeaders = headers
                _playbackSpeed.value = 1.0f
                playerView?.player = this
                exoPlayer = this
            }
    }

    @OptIn(UnstableApi::class)
    private fun syncMetrics(player: ExoPlayer) {
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.coerceAtLeast(0L)
        val buf = player.bufferedPosition.coerceAtLeast(0L)
        _positionMs.value = pos
        _durationMs.value = dur
        _bufferedPositionMs.value = buf

        val videoFmt = player.videoFormat
        val audioFmt = player.audioFormat
        val counters = player.videoDecoderCounters
        val totalFrames = counters?.let { it.renderedOutputBufferCount.toLong() + it.droppedBufferCount.toLong() } ?: 0L

        val decoderName = activeVideoDecoderName ?: videoFmt?.codecs ?: "MediaCodec (ExoPlayer)"
        val isHw = if (activeVideoDecoderName != null) {
            isVideoHwAccelerated
        } else {
            true
        }

        _stats.value = DecoderStats(
            activeEngine = EngineType.EXOPLAYER,
            decoderName = decoderName,
            isHardwareAccelerated = isHw,
            videoCodec = videoFmt?.sampleMimeType ?: videoFmt?.codecs,
            audioCodec = audioFmt?.sampleMimeType ?: audioFmt?.codecs,
            width = videoFmt?.width?.coerceAtLeast(0) ?: _videoSize.value.width,
            height = videoFmt?.height?.coerceAtLeast(0) ?: _videoSize.value.height,
            fps = videoFmt?.frameRate?.takeIf { it > 0f } ?: 0f,
            droppedFrames = counters?.droppedBufferCount?.toLong() ?: 0L,
            totalFrames = totalFrames,
            bitrateMbps = ((videoFmt?.bitrate ?: 0).coerceAtLeast(0) / 1_000_000f),
            bufferAheadSec = ((buf - pos).coerceAtLeast(0L)) / 1000f,
            audioEngine = "AudioTrack",
            renderSurface = "SurfaceView",
        )
    }

    private fun updateTicker(active: Boolean) {
        positionTickerJob?.cancel()
        if (!active) return
        positionTickerJob = scope.launch {
            while (isActive) {
                exoPlayer?.let(::syncMetrics)
                delay(250L)
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun attachSurfaceView(container: FrameLayout) {
        val player = ensurePlayer(currentSource?.headers ?: emptyMap())
        val view = playerView ?: LoomPlayerView(container.context).apply {
            useController = false
            setEnableComposeSurfaceSyncWorkaround(true)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            applyAspectMode(activeAspectMode)
            playerView = this
        }
        view.player = player
        applySubtitleStyleToView(view)
        if (view.parent !== container) {
            (view.parent as? ViewGroup)?.removeView(view)
            container.removeAllViews()
            container.addView(view)
        }
    }

    override fun detachSurfaceView() {
        playerView?.player = null
        (playerView?.parent as? ViewGroup)?.removeView(playerView)
        playerView = null
    }

    override suspend fun prepare(
        source: LoomMediaSource,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) {
        currentSource = source
        extraSubtitles.clear()
        _chapters.value = emptyList()
        _tracks.value = MediaTracks()
        _positionMs.value = startPositionMs.coerceAtLeast(0L)
        _durationMs.value = 0L
        _bufferedPositionMs.value = 0L
        _videoSize.value = VideoSize()
        _playbackSpeed.value = 1.0f
        activeVideoDecoderName = null
        isVideoHwAccelerated = true
        _stats.value = defaultStats()
        val player = ensurePlayer(source.headers)
        player.setPlaybackSpeed(1.0f)
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        source.initialExternalSubtitles.forEach { sub ->
            if (extraSubtitles.none { it.uri == sub.uri }) {
                val resolvedMime = resolveSubtitleMimeType(sub.uri, sub.mimeType, sub.title)
                val builder = MediaItem.SubtitleConfiguration.Builder(sub.uri)
                    .setMimeType(resolvedMime)
                    .setLabel(sub.title ?: sub.uri.lastPathSegment ?: "External Subtitle")
                if (sub.isDefault) {
                    builder.setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                }
                extraSubtitles += builder.build()
            }
        }
        val mediaItem = MediaItem.Builder()
            .setUri(source.uri)
            .apply { source.mimeType?.let(::setMimeType) }
            .apply {
                if (extraSubtitles.isNotEmpty()) {
                    setSubtitleConfigurations(extraSubtitles.toList())
                }
            }
            .build()
        player.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
        player.playWhenReady = autoPlay
        player.prepare()
    }

    override fun play() {
        exoPlayer?.play()
    }

    override fun pause() {
        exoPlayer?.pause()
    }

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        exoPlayer?.seekTo(target)
        _positionMs.value = target
    }

    override fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
        _playbackSpeed.value = speed
    }

    override fun selectAudioTrack(trackId: String) {
        val player = exoPlayer ?: return
        val groupIndex = trackId.substringBefore(':').toIntOrNull() ?: return
        val trackIndex = trackId.substringAfter(':').toIntOrNull() ?: return
        val group = latestTracks.groups.getOrNull(groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    override fun selectSubtitleTrack(trackId: String?) {
        val player = exoPlayer ?: return
        if (trackId == null) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }
        val groupIndex = trackId.substringBefore(':').toIntOrNull() ?: return
        val trackIndex = trackId.substringAfter(':').toIntOrNull() ?: return
        val group = latestTracks.groups.getOrNull(groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
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
        val player = exoPlayer ?: return
        val source = currentSource ?: return
        val incoming = subtitles.filter { sub -> extraSubtitles.none { it.uri == sub.uri } }
        if (incoming.isEmpty()) return

        incoming.forEachIndexed { index, sub ->
            val resolvedMime = resolveSubtitleMimeType(sub.uri, sub.mimeType, sub.title)
            val builder = MediaItem.SubtitleConfiguration.Builder(sub.uri)
                .setMimeType(resolvedMime)
                .setLabel(sub.title ?: sub.uri.lastPathSegment ?: "External Subtitle")
            if (selectLastAdded && index == incoming.lastIndex) {
                builder.setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            }
            extraSubtitles += builder.build()
        }

        val pos = maxOf(player.currentPosition.coerceAtLeast(0L), _positionMs.value)
        val resumePlayWhenReady = player.playWhenReady
        if (selectLastAdded) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
        }
        val mediaItem = MediaItem.Builder()
            .setUri(source.uri)
            .apply { source.mimeType?.let(::setMimeType) }
            .setSubtitleConfigurations(extraSubtitles.toList())
            .build()
        player.setMediaItem(mediaItem, pos)
        player.playWhenReady = resumePlayWhenReady
        player.prepare()
    }

    private fun resolveSubtitleMimeType(uri: Uri, explicitMime: String?, title: String?): String {
        val candidateName = (title ?: uri.lastPathSegment ?: uri.toString()).lowercase()
        val ext = candidateName.substringAfterLast('.', "")
        val resolvedFromExt = when (ext) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> null
        }
        if (resolvedFromExt != null) return resolvedFromExt

        val queried = explicitMime ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return when (queried?.lowercase()) {
            MimeTypes.TEXT_VTT, "text/vtt" -> MimeTypes.TEXT_VTT
            MimeTypes.TEXT_SSA, "text/x-ssa", "text/x-ass", "application/x-ass" -> MimeTypes.TEXT_SSA
            MimeTypes.APPLICATION_TTML, "application/ttml+xml" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    override fun setSubtitleDelay(delayMs: Long) = Unit

    override fun setAudioDelay(delayMs: Long) = Unit

    override fun setAspectRatio(mode: AspectRatioMode) {
        activeAspectMode = mode
        playerView?.applyAspectMode(mode)
    }

    override fun setSubtitleStyle(style: SubtitleStyleConfig) {
        activeSubtitleStyle = style
        applySubtitleStyleToView(playerView)
    }

    override fun setVolumeFraction(fraction: Float) {
        activeVolumeFraction = fraction.coerceIn(0f, 1f)
        exoPlayer?.volume = activeVolumeFraction
    }

    @OptIn(UnstableApi::class)
    private fun applySubtitleStyleToView(view: LoomPlayerView?) {
        val subView = view?.subtitleView ?: return
        val style = activeSubtitleStyle
        subView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp)
        val bgAlpha = (style.backgroundOpacity.coerceIn(0f, 1f) * 255f).roundToInt()
        val bgColor = AndroidColor.argb(bgAlpha, 0, 0, 0)
        val edgeType = if (style.outlineEnabled) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE
        subView.setStyle(
            CaptionStyleCompat(
                AndroidColor.WHITE,
                bgColor,
                AndroidColor.TRANSPARENT,
                edgeType,
                AndroidColor.BLACK,
                null,
            ),
        )
        subView.setBottomPaddingFraction(style.verticalMarginPercent.coerceIn(2, 25) / 100f)
        subView.setApplyEmbeddedStyles(!style.mpvAssOverride)
        subView.setApplyEmbeddedFontSizes(!style.mpvAssOverride)
    }

    override fun release() {
        updateTicker(false)
        detachSurfaceView()
        playerView = null
        exoPlayer?.release()
        exoPlayer = null
        configuredHeaders = null
        activeVideoDecoderName = null
        isVideoHwAccelerated = true
        currentSource = null
        extraSubtitles.clear()
        latestTracks = Tracks.EMPTY
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

    private fun defaultStats() = DecoderStats(
        activeEngine = EngineType.EXOPLAYER,
        decoderName = "Media3 ExoPlayer",
        isHardwareAccelerated = true,
        audioEngine = "AudioTrack",
        renderSurface = "SurfaceView",
    )
}

@OptIn(UnstableApi::class)
private class LoomPlayerView(context: Context) : PlayerView(context) {
    private var contentFrameRef: AspectRatioFrameLayout? = null
    private var naturalAspectRatio: Float = 0f
    private var mode: AspectRatioMode = AspectRatioMode.FIT

    fun applyAspectMode(aspectMode: AspectRatioMode) {
        mode = aspectMode
        resizeMode = when (aspectMode) {
            AspectRatioMode.FIT,
            AspectRatioMode.RATIO_16_9,
            AspectRatioMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectRatioMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        val frame = contentFrameRef ?: return
        val targetRatio = computeTargetAspectRatio()
        if (targetRatio > 0f) {
            frame.setAspectRatio(targetRatio)
        }
    }

    override fun onContentAspectRatioChanged(contentFrame: AspectRatioFrameLayout?, aspectRatio: Float) {
        contentFrameRef = contentFrame
        if (aspectRatio > 0f) {
            naturalAspectRatio = aspectRatio
        }
        super.onContentAspectRatioChanged(contentFrame, computeTargetAspectRatio().takeIf { it > 0f } ?: aspectRatio)
    }

    private fun computeTargetAspectRatio(): Float = when (mode) {
        AspectRatioMode.RATIO_16_9 -> 16f / 9f
        AspectRatioMode.RATIO_4_3 -> 4f / 3f
        AspectRatioMode.FIT,
        AspectRatioMode.FILL,
        AspectRatioMode.STRETCH -> naturalAspectRatio
    }
}
