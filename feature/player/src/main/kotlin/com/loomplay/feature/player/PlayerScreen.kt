package com.loomplay.feature.player

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loomplay.core.model.AspectRatioMode
import com.loomplay.core.model.DecoderStats
import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.EngineType
import com.loomplay.core.model.LoomMediaItem
import com.loomplay.core.model.MediaChapter
import com.loomplay.core.model.MediaTracks
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.model.VideoSize
import com.loomplay.core.player.api.ExternalSubtitleSource
import com.loomplay.core.ui.components.EngineBadgePill
import com.loomplay.core.ui.components.GestureCapsuleIndicator
import com.loomplay.core.ui.components.GlassIconButton
import com.loomplay.core.ui.components.LoomScrubberBar
import com.loomplay.core.ui.components.formatTimecode
import com.loomplay.core.ui.theme.LoomColors
import com.loomplay.core.ui.theme.TimecodeTextStyle
import com.loomplay.core.ui.theme.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class DragAxis {
    HORIZONTAL,
    VERTICAL_LEFT,
    VERTICAL_RIGHT,
    IGNORED,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    mediaUri: String,
    title: String,
    mimeType: String? = null,
    engineOverride: EnginePreference? = null,
    headers: Map<String, String> = emptyMap(),
    playlistId: Long? = null,
    hostActivityClassName: String? = null,
    initialPositionMs: Long? = null,
    forceStartFromBeginning: Boolean = false,
    initialExternalSubtitles: List<ExternalSubtitleSource> = emptyList(),
    isInPictureInPictureMode: Boolean = false,
    onEnterPip: () -> Unit = {},
    onPlaybackStateForPip: (isPlaying: Boolean, videoSize: VideoSize) -> Unit = { _, _ -> },
    onVideoBoundsChanged: (Rect) -> Unit = {},
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val resolvedHostClassName = remember(hostActivityClassName, activity) {
        hostActivityClassName ?: activity?.javaClass?.name
    }
    val coroutineScope = rememberCoroutineScope()
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeEngine by viewModel.activeEngine.collectAsStateWithLifecycle()
    val status by viewModel.playbackStatus.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.durationMs.collectAsStateWithLifecycle()
    val bufferedMs by viewModel.bufferedPositionMs.collectAsStateWithLifecycle()
    val videoSize by viewModel.videoSize.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val sessionEvicted by viewModel.sessionEvicted.collectAsStateWithLifecycle()

    var videoHostView by remember { mutableStateOf<FrameLayout?>(null) }
    var zoomScale by remember(mediaUri) { mutableFloatStateOf(1.0f) }
    var panX by remember(mediaUri) { mutableFloatStateOf(0f) }
    var panY by remember(mediaUri) { mutableFloatStateOf(0f) }

    val chapterFractions by remember(chapters) {
        derivedStateOf {
            if (durationMs > 0L && chapters.isNotEmpty()) {
                chapters.mapNotNull { ch ->
                    if (ch.startMs in 1L until durationMs) ch.startMs.toFloat() / durationMs.toFloat() else null
                }
            } else {
                emptyList()
            }
        }
    }
    val currentChapterTitle by remember(chapters) {
        derivedStateOf {
            chapters.lastOrNull { it.startMs <= positionMs }?.title
        }
    }

    var brightnessFraction by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it > 0f } ?: 0.65f,
        )
    }
    var hasAdjustedBrightness by remember { mutableStateOf(false) }
    var volumeFraction by remember {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        mutableFloatStateOf(current.toFloat() / max.toFloat())
    }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val resolvedMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val resolvedName = resolveUriDisplayName(context, uri)
            viewModel.addExternalSubtitle(uri, resolvedMime, resolvedName)
        }
    }

    BackHandler(enabled = !isInPictureInPictureMode && uiState.activeSheet == PlayerSheet.NONE) {
        viewModel.prepareForExit()
        onNavigateBack()
    }

    LaunchedEffect(Unit) {
        viewModel.initSystemAudioVolume((volumeFraction * 100f).roundToInt())
    }

    LaunchedEffect(sessionEvicted) {
        if (sessionEvicted) {
            onNavigateBack()
        }
    }

    LaunchedEffect(
        mediaUri,
        title,
        mimeType,
        engineOverride,
        headers,
        playlistId,
        resolvedHostClassName,
        initialPositionMs,
        forceStartFromBeginning,
        initialExternalSubtitles,
    ) {
        viewModel.playFromRoute(
            uriString = mediaUri,
            title = title,
            mimeType = mimeType,
            engineOverride = engineOverride,
            headers = headers,
            playlistId = playlistId,
            hostActivityClassName = resolvedHostClassName,
            initialPositionMs = initialPositionMs,
            forceStartFromBeginning = forceStartFromBeginning,
            initialExternalSubtitles = initialExternalSubtitles,
        )
    }

    val captureScreenshot: () -> Unit = {
        captureVideoFrame(
            context = context,
            host = videoHostView,
            videoSize = videoSize,
            aspectRatio = uiState.aspectRatio,
            scope = coroutineScope,
            title = uiState.currentTitle.ifBlank { title },
            onResult = viewModel::notifyScreenshotResult,
        )
    }

    LaunchedEffect(status, videoSize) {
        val isPlaying = status == PlaybackStatus.PLAYING || status == PlaybackStatus.BUFFERING
        onPlaybackStateForPip(isPlaying, videoSize)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.onStopLifecycle(
                    inPip = activity?.isInPictureInPictureMode == true,
                    isChangingConfig = activity?.isChangingConfigurations == true,
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(activity, isInPictureInPictureMode, status) {
        val act = activity ?: return@LaunchedEffect
        val isPlaying = status == PlaybackStatus.PLAYING || status == PlaybackStatus.BUFFERING
        if (!isInPictureInPictureMode) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        act.window?.let { window ->
            if (isPlaying && !isInPictureInPictureMode) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (!isInPictureInPictureMode) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
                if (hasAdjustedBrightness) {
                    val lp = window.attributes
                    lp.screenBrightness = brightnessFraction
                    window.attributes = lp
                }
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
                val lp = window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }

    DisposableEffect(activity) {
        onDispose {
            viewModel.persistProgressNow()
            activity?.window?.let { window ->
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                val lp = window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }

    val applyBrightnessDelta = { deltaFraction: Float ->
        hasAdjustedBrightness = true
        brightnessFraction = (brightnessFraction + deltaFraction).coerceIn(0.02f, 1.0f)
        activity?.window?.let { window ->
            val lp = window.attributes
            lp.screenBrightness = brightnessFraction
            window.attributes = lp
        }
        viewModel.updateBrightnessPercent((brightnessFraction * 100f).roundToInt())
    }

    val applyVolumeDelta = { deltaFraction: Float ->
        volumeFraction = (volumeFraction + deltaFraction).coerceIn(0f, 1f)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetIndex = (volumeFraction * maxVol).roundToInt().coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
        viewModel.updateVolumePercent((volumeFraction * 100f).roundToInt())
    }

    val displayTitle = uiState.currentTitle.ifBlank { title }
    val latestDurationMs by rememberUpdatedState(durationMs)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 1. PERSISTENT FULLSCREEN LANDSCAPE VIDEO VIEWPORT
        var canvasWidthPx by remember { mutableFloatStateOf(1f) }
        var canvasHeightPx by remember { mutableFloatStateOf(1f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .background(Color.Black)
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInWindow()
                    val r = Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
                    if (r.width() > 0 && r.height() > 0) {
                        onVideoBoundsChanged(r)
                    }
                }
                .onSizeChanged {
                    canvasWidthPx = it.width.toFloat().coerceAtLeast(1f)
                    canvasHeightPx = it.height.toFloat().coerceAtLeast(1f)
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        clipChildren = true
                        clipToPadding = true
                        keepScreenOn = true
                        videoHostView = this
                        viewModel.attachSurface(this)
                    }
                },
                update = { host ->
                    val (targetScale, targetPanX, targetPanY) = if (isInPictureInPictureMode) {
                        Triple(1f, 0f, 0f)
                    } else {
                        Triple(zoomScale, panX, panY)
                    }
                    if (host.scaleX != targetScale) {
                        host.scaleX = targetScale
                        host.scaleY = targetScale
                    }
                    if (host.translationX != targetPanX) {
                        host.translationX = targetPanX
                    }
                    if (host.translationY != targetPanY) {
                        host.translationY = targetPanY
                    }
                },
                onRelease = { host ->
                    host.removeAllViews()
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Gesture surface sits behind interactive HUD buttons
            if (!isInPictureInPictureMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(uiState.isLocked, uiState.seekStepSeconds) {
                            detectTapGestures(
                                onPress = {
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        viewModel.endLongPressBoost()
                                    }
                                },
                                onTap = {
                                    viewModel.toggleHud()
                                },
                                onDoubleTap = { offset ->
                                    if (!uiState.isLocked) {
                                        val isRightHalf = offset.x >= canvasWidthPx * 0.5f
                                        viewModel.doubleTapSeek(forward = isRightHalf)
                                    }
                                },
                                onLongPress = {
                                    if (!uiState.isLocked) {
                                        viewModel.startLongPressBoost()
                                    }
                                },
                            )
                        }
                        .pointerInput(
                            uiState.isLocked,
                            uiState.pinchToZoomEnabled,
                            uiState.swipeGesturesEnabled,
                            uiState.horizontalSeekGestureEnabled,
                        ) {
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                if (uiState.isLocked) return@awaitEachGesture

                                val startPos = firstDown.position
                                var axis: DragAxis? = null
                                var totalHorizPx = 0f
                                var isMultiTouch = false
                                val touchSlop = viewConfiguration.touchSlop

                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break

                                    if (pressed.size >= 2) {
                                        if (uiState.pinchToZoomEnabled) {
                                            if (axis == DragAxis.HORIZONTAL) {
                                                axis = null
                                            }
                                            isMultiTouch = true
                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()
                                            if (zoomChange != 1f || panChange.x != 0f || panChange.y != 0f) {
                                                val nextScale = (zoomScale * zoomChange).coerceIn(1.0f, 3.0f)
                                                if (nextScale <= 1.05f) {
                                                    zoomScale = 1.0f
                                                    panX = 0f
                                                    panY = 0f
                                                } else {
                                                    zoomScale = nextScale
                                                    val maxPanX = (nextScale - 1f) * canvasWidthPx * 0.5f
                                                    val maxPanY = (nextScale - 1f) * canvasHeightPx * 0.5f
                                                    panX = (panX + panChange.x).coerceIn(-maxPanX, maxPanX)
                                                    panY = (panY + panChange.y).coerceIn(-maxPanY, maxPanY)
                                                }
                                                pressed.forEach { it.consume() }
                                            }
                                        }
                                        continue
                                    }

                                    if (isMultiTouch) continue

                                    val primary = pressed.firstOrNull { it.id == firstDown.id } ?: pressed.first()
                                    val delta = primary.position - primary.previousPosition
                                    if (axis == null) {
                                        val offset = primary.position - startPos
                                        if (offset.getDistance() > touchSlop) {
                                            if (abs(offset.x) > abs(offset.y)) {
                                                if (uiState.horizontalSeekGestureEnabled) {
                                                    axis = DragAxis.HORIZONTAL
                                                    viewModel.beginHorizontalScrub()
                                                    primary.consume()
                                                } else {
                                                    axis = DragAxis.IGNORED
                                                }
                                            } else {
                                                if (uiState.swipeGesturesEnabled) {
                                                    axis = if (startPos.x < canvasWidthPx * 0.5f) {
                                                        DragAxis.VERTICAL_LEFT
                                                    } else {
                                                        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                                        volumeFraction = current.toFloat() / max.toFloat()
                                                        DragAxis.VERTICAL_RIGHT
                                                    }
                                                    primary.consume()
                                                } else {
                                                    axis = DragAxis.IGNORED
                                                }
                                            }
                                        }
                                    } else {
                                        when (axis) {
                                            DragAxis.HORIZONTAL -> {
                                                totalHorizPx += delta.x
                                                val activeDuration = latestDurationMs
                                                val msPerScreen = if (activeDuration > 0L) {
                                                    (activeDuration * 0.35f).coerceIn(30_000f, 180_000f)
                                                } else {
                                                    90_000f
                                                }
                                                val deltaMs = ((totalHorizPx / canvasWidthPx) * msPerScreen).toLong()
                                                viewModel.updateHorizontalScrub(deltaMs)
                                                primary.consume()
                                            }
                                            DragAxis.VERTICAL_LEFT -> {
                                                applyBrightnessDelta(-delta.y / (canvasHeightPx * 0.75f))
                                                primary.consume()
                                            }
                                            DragAxis.VERTICAL_RIGHT -> {
                                                applyVolumeDelta(-delta.y / (canvasHeightPx * 0.75f))
                                                primary.consume()
                                            }
                                            DragAxis.IGNORED -> Unit
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                if (!isMultiTouch && axis == DragAxis.HORIZONTAL) {
                                    viewModel.endHorizontalScrub()
                                }
                            }
                        },
                )
            }

            // Video Overlays (Hidden during PiP)
            if (!isInPictureInPictureMode) {
                LandscapePlayerHudOverlay(
                    title = displayTitle,
                    activeEngine = activeEngine,
                    status = status,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    chapterFractions = chapterFractions,
                    currentChapterTitle = currentChapterTitle,
                    hasChapters = chapters.isNotEmpty(),
                    videoSize = videoSize,
                    playbackSpeed = playbackSpeed,
                    stats = stats,
                    uiState = uiState,
                    onBack = {
                        viewModel.prepareForExit()
                        onNavigateBack()
                    },
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onPlayPrevious = viewModel::playPreviousInQueue,
                    onPlayNext = viewModel::playNextInQueue,
                    onSeekTo = viewModel::seekTo,
                    onToggleEngine = viewModel::toggleEngine,
                    onToggleTelemetry = viewModel::toggleTelemetry,
                    onDismissStats = viewModel::dismissStats,
                    onOpenTracks = { viewModel.openSheet(PlayerSheet.TRACKS) },
                    onOpenSync = { viewModel.openSheet(PlayerSheet.SYNC_DELAY) },
                    onOpenSpeed = { viewModel.openSheet(PlayerSheet.SPEED) },
                    onOpenQueue = { viewModel.openSheet(PlayerSheet.QUEUE) },
                    onOpenChapters = { viewModel.openSheet(PlayerSheet.CHAPTERS) },
                    onOpenMore = { viewModel.openSheet(PlayerSheet.MORE) },
                    onToggleAbLoop = viewModel::toggleAbLoop,
                    onCycleAspectRatio = viewModel::cycleAspectRatio,
                    onToggleLock = viewModel::toggleLock,
                    onEnterPip = onEnterPip,
                )

                // Pinch-Zoom Reset Chip
                AnimatedVisibility(
                    visible = zoomScale > 1.05f && uiState.isHudVisible && !uiState.isLocked,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .displayCutoutPadding()
                        .padding(top = 64.dp, end = 16.dp)
                        .zIndex(5f),
                ) {
                    Surface(
                        onClick = {
                            zoomScale = 1.0f
                            panX = 0f
                            panY = 0f
                        },
                        shape = CircleShape,
                        color = LoomColors.SurfaceLowest.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, LoomColors.MpvCyan.copy(alpha = 0.45f)),
                    ) {
                        Text(
                            text = "%.1fx • Reset".format(zoomScale),
                            style = MaterialTheme.typography.labelSmall,
                            color = LoomColors.MpvCyan,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                // Up-Next Auto-Advance Countdown Floating Card
                val countdown = uiState.upNextCountdownSec
                if (countdown != null) {
                    val idx = uiState.queueItems.indexOfFirst { it.uri == uiState.currentUri }
                    val nextItem = uiState.queueItems.getOrNull(idx + 1)
                    if (nextItem != null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .displayCutoutPadding()
                                .padding(16.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = LoomColors.SurfaceLowest.copy(alpha = 0.94f),
                            border = BorderStroke(1.dp, LoomColors.Primary.copy(alpha = 0.45f)),
                        ) {
                            Column(
                                modifier = Modifier
                                    .width(240.dp)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Up Next in ${countdown}s",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = LoomColors.Primary,
                                )
                                Text(
                                    text = nextItem.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = viewModel::cancelUpNextAutoPlay,
                                        modifier = Modifier.weight(1f),
                                        shape = CircleShape,
                                    ) {
                                        Text("Cancel")
                                    }
                                    Button(
                                        onClick = viewModel::playNextInQueue,
                                        modifier = Modifier.weight(1f),
                                        shape = CircleShape,
                                    ) {
                                        Text("Play Now")
                                    }
                                }
                            }
                        }
                    }
                }

                // Gesture Feedback Overlay (Volume / Brightness / Seek Preview / 2x Speed Boost)
                GestureOverlayCallouts(
                    gesture = uiState.gestureFeedback,
                    statusBanner = uiState.statusBanner,
                    modifier = Modifier
                        .fillMaxSize()
                        .displayCutoutPadding(),
                )

                // Touch-Lock Unlock Pill (when locked and user taps)
                AnimatedVisibility(
                    visible = uiState.isLocked && uiState.isHudVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                ) {
                    Surface(
                        onClick = viewModel::toggleLock,
                        shape = CircleShape,
                        color = LoomColors.SurfaceLowest.copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, LoomColors.Error.copy(alpha = 0.55f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = LoomColors.Error, modifier = Modifier.size(18.dp))
                            Text("Tap to Unlock", style = MaterialTheme.typography.labelLarge, color = LoomColors.OnSurface)
                        }
                    }
                }
            }
        }

        // 2. MODAL BOTTOM SHEETS (Tracks, Sync Delay, Speed, Queue, Chapters, More Tools)
        if (!isInPictureInPictureMode && uiState.activeSheet != PlayerSheet.NONE) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = viewModel::closeSheet,
                sheetState = sheetState,
                containerColor = LoomColors.SurfaceContainer,
            ) {
                when (uiState.activeSheet) {
                    PlayerSheet.TRACKS -> TrackSelectionSheetContent(
                        tracks = tracks,
                        onSelectAudio = { id ->
                            viewModel.selectAudioTrack(id)
                            viewModel.closeSheet()
                        },
                        onSelectSubtitle = { id ->
                            viewModel.selectSubtitleTrack(id)
                            viewModel.closeSheet()
                        },
                        onPickExternalSubtitle = {
                            subtitlePickerLauncher.launch(arrayOf("*/*"))
                        },
                    )
                    PlayerSheet.SYNC_DELAY -> SyncOffsetSheetContent(
                        subtitleDelayMs = uiState.subtitleDelayMs,
                        audioDelayMs = uiState.audioDelayMs,
                        activeEngine = activeEngine,
                        onAdjustSubtitle = viewModel::adjustSubtitleDelay,
                        onAdjustAudio = viewModel::adjustAudioDelay,
                        onReset = viewModel::resetDelays,
                        onSwitchToMpv = { viewModel.switchEngine(EngineType.MPV) },
                    )
                    PlayerSheet.SPEED -> PlaybackSpeedSheetContent(
                        currentSpeed = playbackSpeed,
                        onSelectSpeed = { speed ->
                            viewModel.setPlaybackSpeed(speed)
                        },
                    )
                    PlayerSheet.QUEUE -> LandscapeQueueSheetContent(
                        currentUri = uiState.currentUri,
                        queueItems = uiState.queueItems,
                        onSelectItem = { item ->
                            viewModel.closeSheet()
                            viewModel.playQueueItem(item)
                        },
                    )
                    PlayerSheet.CHAPTERS -> ChaptersSheetContent(
                        chapters = chapters,
                        currentPositionMs = positionMs,
                        onSelectChapter = viewModel::seekToChapter,
                    )
                    PlayerSheet.MORE -> MorePlayerToolsSheetContent(
                        activeEngine = activeEngine,
                        isStatsVisible = uiState.showLandscapeStats,
                        uiState = uiState,
                        onSwitchEngine = {
                            viewModel.toggleEngine()
                            viewModel.closeSheet()
                        },
                        onOpenSync = { viewModel.openSheet(PlayerSheet.SYNC_DELAY) },
                        onToggleAbLoop = {
                            viewModel.toggleAbLoop()
                            viewModel.closeSheet()
                        },
                        onCaptureScreenshot = {
                            viewModel.closeSheet()
                            captureScreenshot()
                        },
                        onToggleBackgroundAudio = viewModel::toggleBackgroundAudio,
                        onToggleTelemetry = {
                            viewModel.toggleTelemetry()
                            viewModel.closeSheet()
                        },
                    )
                    PlayerSheet.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun LandscapePlayerHudOverlay(
    title: String,
    activeEngine: EngineType,
    status: PlaybackStatus,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chapterFractions: List<Float>,
    currentChapterTitle: String?,
    hasChapters: Boolean,
    videoSize: VideoSize,
    playbackSpeed: Float,
    stats: DecoderStats,
    uiState: PlayerUiState,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleEngine: () -> Unit,
    onToggleTelemetry: () -> Unit,
    onDismissStats: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenMore: () -> Unit,
    onToggleAbLoop: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleLock: () -> Unit,
    onEnterPip: () -> Unit,
) {
    AnimatedVisibility(
        visible = uiState.isHudVisible && !uiState.isLocked,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Top & Bottom Scrim Gradients
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(LoomColors.SurfaceLowest.copy(alpha = 0.88f), Color.Transparent),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, LoomColors.SurfaceLowest.copy(alpha = 0.92f)),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .displayCutoutPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // TOP SYSTEM & MEDIA BAR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlassIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            onClick = onBack,
                            size = 44.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!uiState.minimalInterface) {
                                Text(
                                    text = buildTechnicalSubtitle(videoSize, stats),
                                    style = TimecodeTextStyle.tabular().copy(fontSize = 12.sp),
                                    color = LoomColors.OnSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (!uiState.minimalInterface) {
                            EngineBadgePill(
                                engine = activeEngine,
                                label = if (activeEngine == EngineType.MPV) "Compat" else "Std",
                                onClick = onToggleEngine,
                            )
                        }

                        if (uiState.subtitleDelayMs != 0L || uiState.audioDelayMs != 0L) {
                            Surface(
                                onClick = onOpenSync,
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.14f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sync,
                                        contentDescription = "Subtitle Sync",
                                        tint = LoomColors.SyncAmber,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        text = if (uiState.subtitleDelayMs != 0L) {
                                            "%+dms".format(uiState.subtitleDelayMs)
                                        } else {
                                            "%+dms".format(uiState.audioDelayMs)
                                        },
                                        style = TimecodeTextStyle.tabular().copy(fontSize = 12.sp),
                                        color = LoomColors.OnSurface,
                                    )
                                }
                            }
                        }

                        if (!uiState.minimalInterface || uiState.showLandscapeStats) {
                            GlassIconButton(
                                icon = Icons.Default.Analytics,
                                contentDescription = "Playback Stats",
                                onClick = onToggleTelemetry,
                                size = 44.dp,
                                tint = if (uiState.showLandscapeStats) LoomColors.Primary else Color.White,
                            )
                        }

                        GlassIconButton(
                            icon = Icons.Default.LockOpen,
                            contentDescription = "Lock Controls",
                            onClick = onToggleLock,
                            size = 44.dp,
                        )
                        GlassIconButton(
                            icon = Icons.Default.MoreVert,
                            contentDescription = "More Player Tools",
                            onClick = onOpenMore,
                            size = 44.dp,
                        )
                    }
                }

                // CENTER TRANSPORT HERO
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        if (uiState.hasPrevious || uiState.hasNext) {
                            GlassIconButton(
                                icon = Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                onClick = { if (uiState.hasPrevious) onPlayPrevious() },
                                size = 48.dp,
                                tint = if (uiState.hasPrevious) Color.White else Color.White.copy(alpha = 0.3f),
                            )
                        }

                        Surface(
                            onClick = onTogglePlayPause,
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            color = LoomColors.PrimaryContainer,
                            shadowElevation = 10.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (status == PlaybackStatus.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play or Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(38.dp),
                                )
                            }
                        }

                        if (uiState.hasPrevious || uiState.hasNext) {
                            GlassIconButton(
                                icon = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                onClick = { if (uiState.hasNext) onPlayNext() },
                                size = 48.dp,
                                tint = if (uiState.hasNext) Color.White else Color.White.copy(alpha = 0.3f),
                            )
                        }
                    }
                }

                // BOTTOM SCRUBBER & MINIMAL ACTION TOOLBAR
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = formatTimecode(positionMs),
                            style = TimecodeTextStyle.tabular().copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                        )
                        LoomScrubberBar(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            bufferedMs = bufferedMs,
                            onSeekTo = onSeekTo,
                            chapterFractions = chapterFractions,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatTimecode(durationMs),
                            style = TimecodeTextStyle.tabular(),
                            color = LoomColors.OnSurfaceVariant,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                onClick = onOpenTracks,
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.12f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(Icons.Default.Subtitles, contentDescription = null, tint = LoomColors.Primary, modifier = Modifier.size(16.dp))
                                    Text("Audio & Subs", style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
                                }
                            }

                            Surface(
                                onClick = onOpenSpeed,
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.12f),
                            ) {
                                Text(
                                    text = "%.2gx".format(playbackSpeed),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = LoomColors.Primary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }

                            if (hasChapters) {
                                Surface(
                                    onClick = onOpenChapters,
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.12f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.FormatListBulleted,
                                            contentDescription = "Chapters",
                                            tint = LoomColors.MpvCyan,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Text(
                                            text = currentChapterTitle ?: "Chapters",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 160.dp),
                                        )
                                    }
                                }
                            }

                            if (uiState.loopStartMs != null) {
                                Surface(
                                    onClick = onToggleAbLoop,
                                    shape = CircleShape,
                                    color = LoomColors.Primary.copy(alpha = 0.28f),
                                ) {
                                    Text(
                                        text = if (uiState.loopEndMs == null) "Loop: Set B" else "Loop Active",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = LoomColors.Primary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.padding(start = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Surface(
                                onClick = onCycleAspectRatio,
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.12f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(Icons.Default.AspectRatio, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Text(uiState.aspectRatio.label, style = MaterialTheme.typography.labelMedium, color = Color.White)
                                }
                            }

                            if (uiState.queueItems.size > 1) {
                                GlassIconButton(
                                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = "Up Next Queue",
                                    onClick = onOpenQueue,
                                    size = 38.dp,
                                )
                            }
                            GlassIconButton(
                                icon = Icons.Default.PictureInPictureAlt,
                                contentDescription = "Picture in Picture",
                                onClick = onEnterPip,
                                size = 38.dp,
                            )
                        }
                    }
                }
            }

            // Floating Stats for Nerds Panel (Top Right)
            if (uiState.showLandscapeStats) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .displayCutoutPadding()
                        .padding(top = 74.dp, end = 24.dp)
                        .width(300.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = LoomColors.SurfaceLowest.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, tint = LoomColors.Primary, modifier = Modifier.size(16.dp))
                                Text("Playback Stats", style = MaterialTheme.typography.labelLarge, color = Color.White)
                            }
                            IconButton(onClick = onDismissStats, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = LoomColors.OnSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                        }

                        StatsKeyValueRow("Playback Mode:", if (stats.activeEngine == EngineType.MPV) "Compatibility" else "Standard", LoomColors.MpvCyan)
                        StatsKeyValueRow("Decoder:", stats.decoderName, LoomColors.ExoEmerald)
                        StatsKeyValueRow("Resolution:", if (stats.width > 0) "${stats.width}×${stats.height}" else "Detecting", LoomColors.OnSurface)
                        StatsKeyValueRow("Frame Rate:", if (stats.fps > 0f) "%.3f fps".format(stats.fps) else "--", LoomColors.OnSurface)
                        if (stats.displayRefreshRate > 0f) {
                            val cadenceSuffix = if (stats.fps > 0f) {
                                val ratio = stats.displayRefreshRate / stats.fps
                                val rounded = kotlin.math.round(ratio).toInt()
                                if (kotlin.math.abs(ratio - rounded) < 0.05f && rounded >= 1) {
                                    " • %d:%d Match".format(rounded, rounded)
                                } else {
                                    ""
                                }
                            } else ""
                            StatsKeyValueRow(
                                "Display:",
                                "%.0f Hz%s".format(stats.displayRefreshRate, cadenceSuffix),
                                LoomColors.ExoEmeraldBright,
                            )
                        }
                        StatsKeyValueRow("Bitrate:", if (stats.bitrateMbps > 0f) "%.1f Mbps".format(stats.bitrateMbps) else "Variable", LoomColors.OnSurface)
                        StatsKeyValueRow("Audio Engine:", stats.audioEngine, LoomColors.OnSurface)
                        StatsKeyValueRow("Buffer Ahead:", "%.1fs".format(stats.bufferAheadSec), LoomColors.Primary)
                        StatsKeyValueRow("Dropped Frames:", "${stats.droppedFrames}", if (stats.droppedFrames == 0L) LoomColors.ExoEmeraldBright else LoomColors.SyncAmber)
                        StatsKeyValueRow("Surface:", stats.renderSurface, LoomColors.OnSurface)

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(if (stats.droppedFrames == 0L) LoomColors.ExoEmerald else LoomColors.SyncAmber),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsKeyValueRow(
    key: String,
    value: String,
    valueColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = TimecodeTextStyle.copy(fontSize = 11.5.sp), color = LoomColors.OnSurfaceVariant)
        Text(
            text = value,
            style = TimecodeTextStyle.tabular().copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GestureOverlayCallouts(
    gesture: GestureFeedback?,
    statusBanner: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // Status Banner (Engine Switch, Resume, External Subtitle Loaded)
        AnimatedVisibility(
            visible = statusBanner != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = LoomColors.SurfaceLowest.copy(alpha = 0.88f),
                border = BorderStroke(1.dp, LoomColors.Primary.copy(alpha = 0.35f)),
            ) {
                Text(
                    text = statusBanner.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = LoomColors.OnSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

        // Floating Gesture Feedback
        when (gesture) {
            is GestureFeedback.SpeedBoost -> {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 58.dp),
                    shape = CircleShape,
                    color = LoomColors.SurfaceHighest.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, LoomColors.MpvCyan.copy(alpha = 0.45f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Default.FastForward, contentDescription = null, tint = LoomColors.MpvCyan, modifier = Modifier.size(16.dp))
                        Text(
                            text = "%.1fX BOOST".format(gesture.multiplier),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }
                }
            }
            is GestureFeedback.SeekPreview -> {
                val deltaSec = gesture.deltaMs / 1000L
                val sign = if (deltaSec >= 0) "+" else "-"
                val absSec = abs(deltaSec)
                val deltaLabel = "%s%02d:%02d".format(sign, absSec / 60L, absSec % 60L)

                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 90.dp),
                    shape = CircleShape,
                    color = LoomColors.SurfaceLowest.copy(alpha = 0.90f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = deltaLabel,
                            style = TimecodeTextStyle.tabular().copy(fontWeight = FontWeight.Bold),
                            color = LoomColors.ExoEmerald,
                        )
                        Text("•", color = LoomColors.OnSurfaceVariant)
                        Text(
                            text = "${formatTimecode(gesture.targetMs)} / ${formatTimecode(gesture.durationMs)}",
                            style = TimecodeTextStyle.tabular(),
                            color = Color.White,
                        )
                    }
                }
            }
            is GestureFeedback.Brightness -> {
                GestureCapsuleIndicator(
                    icon = Icons.Default.Brightness6,
                    percent = gesture.percent,
                    accentColor = LoomColors.MpvCyan,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 14.dp),
                )
            }
            is GestureFeedback.Volume -> {
                GestureCapsuleIndicator(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    percent = gesture.percent,
                    accentColor = LoomColors.PrimaryContainer,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 14.dp),
                )
            }
            null -> Unit
        }
    }
}

@Composable
private fun TrackSelectionSheetContent(
    tracks: MediaTracks,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onPickExternalSubtitle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Audio & Subtitles", style = MaterialTheme.typography.headlineSmall, color = Color.White)

        Text("SUBTITLES", style = MaterialTheme.typography.labelSmall, color = LoomColors.OnSurfaceVariant)
        TrackOptionRow(
            title = "Subtitles Off",
            subtitle = "Disable closed captions",
            selected = tracks.subtitleTracks.none { it.isSelected },
            onClick = { onSelectSubtitle(null) },
        )
        tracks.subtitleTracks.forEach { track ->
            TrackOptionRow(
                title = track.title,
                subtitle = listOfNotNull(track.language, track.codec).joinToString(" • ").ifBlank { "Subtitle" },
                selected = track.isSelected,
                onClick = { onSelectSubtitle(track.id) },
            )
        }

        OutlinedButton(
            onClick = onPickExternalSubtitle,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Load External Subtitle")
        }

        Text("AUDIO", style = MaterialTheme.typography.labelSmall, color = LoomColors.OnSurfaceVariant)
        if (tracks.audioTracks.isEmpty()) {
            Text("No selectable audio streams reported yet.", style = MaterialTheme.typography.bodySmall, color = LoomColors.OnSurfaceVariant)
        } else {
            tracks.audioTracks.forEach { track ->
                TrackOptionRow(
                    title = track.title,
                    subtitle = listOfNotNull(track.language, track.codec).joinToString(" • ").ifBlank { "Audio Stream" },
                    selected = track.isSelected,
                    onClick = { onSelectAudio(track.id) },
                )
            }
        }
    }
}

@Composable
private fun TrackOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) LoomColors.Primary.copy(alpha = 0.16f) else LoomColors.SurfaceLow,
        border = BorderStroke(1.dp, if (selected) LoomColors.Primary.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.06f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LoomColors.OnSurfaceVariant, maxLines = 1)
            }
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = LoomColors.Primary)
            }
        }
    }
}

@Composable
private fun SyncOffsetSheetContent(
    subtitleDelayMs: Long,
    audioDelayMs: Long,
    activeEngine: EngineType,
    onAdjustSubtitle: (Long) -> Unit,
    onAdjustAudio: (Long) -> Unit,
    onReset: () -> Unit,
    onSwitchToMpv: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Audio & Subtitle Sync", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            TextButton(onClick = onReset) { Text("Reset") }
        }

        if (activeEngine != EngineType.MPV) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = LoomColors.SyncAmber.copy(alpha = 0.14f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Switch to Compatibility Mode to adjust audio and subtitle timing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LoomColors.SyncAmber,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onSwitchToMpv, shape = CircleShape) {
                        Text("Switch", maxLines = 1)
                    }
                }
            }
        }

        SyncStepperRow(
            label = "Subtitle Delay",
            delayMs = subtitleDelayMs,
            accent = LoomColors.MpvCyan,
            enabled = activeEngine == EngineType.MPV,
            onDelta = onAdjustSubtitle,
        )

        SyncStepperRow(
            label = "Audio Delay",
            delayMs = audioDelayMs,
            accent = LoomColors.ExoEmerald,
            enabled = activeEngine == EngineType.MPV,
            onDelta = onAdjustAudio,
        )
    }
}

@Composable
private fun SyncStepperRow(
    label: String,
    delayMs: Long,
    accent: Color,
    enabled: Boolean = true,
    onDelta: (Long) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = LoomColors.SurfaceLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = if (enabled) Color.White else LoomColors.OnSurfaceVariant)
                Text(
                    text = "%+d ms".format(delayMs),
                    style = TimecodeTextStyle.tabular().copy(fontWeight = FontWeight.Bold),
                    color = if (!enabled || delayMs == 0L) LoomColors.OnSurfaceVariant else accent,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { onDelta(-250L) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("-250") }
                OutlinedButton(onClick = { onDelta(-50L) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("-50") }
                OutlinedButton(onClick = { onDelta(50L) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("+50") }
                OutlinedButton(onClick = { onDelta(250L) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("+250") }
            }
        }
    }
}

@Composable
private fun PlaybackSpeedSheetContent(
    currentSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
) {
    val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Playback Speed", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                text = "%.2gx".format(currentSpeed),
                style = TimecodeTextStyle.tabular().copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                color = LoomColors.Primary,
            )
        }

        Slider(
            value = currentSpeed,
            onValueChange = onSelectSpeed,
            valueRange = 0.25f..3.0f,
            colors = SliderDefaults.colors(
                thumbColor = LoomColors.Primary,
                activeTrackColor = LoomColors.PrimaryContainer,
                inactiveTrackColor = Color.White.copy(alpha = 0.22f),
            ),
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets) { speed ->
                FilterChip(
                    selected = abs(currentSpeed - speed) < 0.03f,
                    onClick = { onSelectSpeed(speed) },
                    label = { Text("${speed}x") },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = LoomColors.Primary,
                        selectedLabelColor = LoomColors.OnPrimary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun LandscapeQueueSheetContent(
    currentUri: String,
    queueItems: List<LoomMediaItem>,
    onSelectItem: (LoomMediaItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Up Next Queue", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        LazyColumn(
            modifier = Modifier.heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(queueItems.take(20), key = { it.uri }) { item ->
                val isCurrent = item.uri == currentUri
                Surface(
                    onClick = { onSelectItem(item) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isCurrent) LoomColors.Primary.copy(alpha = 0.16f) else LoomColors.SurfaceLow,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) LoomColors.Primary else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatTimecode(item.durationMs),
                            style = TimecodeTextStyle.tabular(),
                            color = LoomColors.OnSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun buildTechnicalSubtitle(videoSize: VideoSize, stats: DecoderStats): String {
    val res = if (videoSize.width > 0 && videoSize.height > 0) "${videoSize.width}×${videoSize.height}" else null
    return listOfNotNull(
        res,
        stats.videoCodec?.uppercase(),
        stats.audioCodec?.uppercase(),
    ).joinToString(" • ").ifBlank { "Video Stream" }
}

private fun resolveUriDisplayName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
        }
    }
    return uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let(Uri::decode)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ChaptersSheetContent(
    chapters: List<MediaChapter>,
    currentPositionMs: Long,
    onSelectChapter: (MediaChapter) -> Unit,
) {
    val activeChapter = chapters.lastOrNull { it.startMs <= currentPositionMs }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Chapters", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        if (chapters.isEmpty()) {
            Text("No embedded chapters found in this media.", style = MaterialTheme.typography.bodyMedium, color = LoomColors.OnSurfaceVariant)
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chapters, key = { "${it.startMs}_${it.title}" }) { chapter ->
                    val isCurrent = chapter == activeChapter
                    Surface(
                        onClick = { onSelectChapter(chapter) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isCurrent) LoomColors.Primary.copy(alpha = 0.16f) else LoomColors.SurfaceLow,
                        border = BorderStroke(1.dp, if (isCurrent) LoomColors.Primary.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.06f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) LoomColors.Primary else Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatTimecode(chapter.startMs),
                                style = TimecodeTextStyle.tabular(),
                                color = LoomColors.OnSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MorePlayerToolsSheetContent(
    activeEngine: EngineType,
    isStatsVisible: Boolean,
    uiState: PlayerUiState,
    onSwitchEngine: () -> Unit,
    onOpenSync: () -> Unit,
    onToggleAbLoop: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onToggleBackgroundAudio: () -> Unit,
    onToggleTelemetry: () -> Unit,
) {
    val isMpv = activeEngine == EngineType.MPV
    val accent = if (isMpv) LoomColors.MpvCyan else LoomColors.ExoEmerald
    val loopStatus = when {
        uiState.loopStartMs == null -> "Off"
        uiState.loopEndMs == null -> "Set B"
        else -> "Active"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Tools",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )

        Surface(
            onClick = onSwitchEngine,
            shape = RoundedCornerShape(16.dp),
            color = LoomColors.SurfaceLow,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = if (isMpv) Icons.Default.Memory else Icons.Default.Bolt,
                        contentDescription = null,
                        tint = accent,
                    )
                    Column {
                        Text(
                            text = if (isMpv) "Compatibility Player" else "Standard Player",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        Text(
                            text = if (isMpv) {
                                "Tap to switch to Standard mode"
                            } else {
                                "Tap if video or sync needs compatibility mode"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = LoomColors.OnSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = "Switch playback mode",
                    tint = accent,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlayerToolTile(
                icon = Icons.Default.Sync,
                title = "Audio & Sub Sync",
                subtitle = if (uiState.subtitleDelayMs == 0L && uiState.audioDelayMs == 0L) {
                    "On time"
                } else {
                    "Sub %+d ms".format(uiState.subtitleDelayMs)
                },
                tint = if (uiState.subtitleDelayMs != 0L || uiState.audioDelayMs != 0L) LoomColors.SyncAmber else LoomColors.MpvCyan,
                onClick = onOpenSync,
                modifier = Modifier.weight(1f),
            )
            PlayerToolTile(
                icon = Icons.Default.Repeat,
                title = "A-B Loop",
                subtitle = loopStatus,
                tint = if (uiState.loopStartMs != null) LoomColors.Primary else LoomColors.OnSurfaceVariant,
                onClick = onToggleAbLoop,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlayerToolTile(
                icon = Icons.Default.PhotoCamera,
                title = "Screenshot",
                subtitle = "Save frame",
                tint = LoomColors.ExoEmerald,
                onClick = onCaptureScreenshot,
                modifier = Modifier.weight(1f),
            )
            PlayerToolTile(
                icon = Icons.Default.Headphones,
                title = "Background Audio",
                subtitle = if (uiState.backgroundAudioEnabled) "On" else "Off",
                tint = if (uiState.backgroundAudioEnabled) LoomColors.MpvCyan else LoomColors.OnSurfaceVariant,
                onClick = onToggleBackgroundAudio,
                modifier = Modifier.weight(1f),
            )
        }

        PlayerToolTile(
            icon = Icons.Default.Terminal,
            title = "Playback Stats",
            subtitle = if (isStatsVisible) "Visible" else "Hidden",
            tint = if (isStatsVisible) LoomColors.Primary else LoomColors.OnSurfaceVariant,
            onClick = onToggleTelemetry,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PlayerToolTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = LoomColors.SurfaceLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LoomColors.OnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun findSurfaceView(root: View?): SurfaceView? {
    if (root == null) return null
    if (root is SurfaceView) return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            findSurfaceView(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}

private fun captureVideoFrame(
    context: Context,
    host: FrameLayout?,
    videoSize: VideoSize,
    aspectRatio: AspectRatioMode,
    scope: kotlinx.coroutines.CoroutineScope,
    title: String,
    onResult: (Boolean, String) -> Unit,
) {
    val surfaceView = findSurfaceView(host)
    if (surfaceView == null || !surfaceView.holder.surface.isValid || surfaceView.width <= 0 || surfaceView.height <= 0) {
        onResult(false, "Video surface not ready for capture")
        return
    }
    val rawBitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
    PixelCopy.request(
        surfaceView,
        rawBitmap,
        { copyResult ->
            if (copyResult != PixelCopy.SUCCESS) {
                rawBitmap.recycle()
                onResult(false, "Frame capture failed ($copyResult)")
                return@request
            }
            scope.launch(Dispatchers.IO) {
                val bitmapToSave = cropLetterboxIfNeeded(rawBitmap, videoSize, aspectRatio)
                if (bitmapToSave !== rawBitmap) {
                    rawBitmap.recycle()
                }
                val safeTitle = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(32).ifBlank { "frame" }
                val fileName = "LoomPlay_${safeTitle}_${System.currentTimeMillis()}.png"
                val saved = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LoomPlay")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val resolver = context.contentResolver
                        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            ?: error("Unable to create MediaStore image entry")
                        resolver.openOutputStream(uri)?.use { out ->
                            bitmapToSave.compress(Bitmap.CompressFormat.PNG, 100, out)
                        } ?: error("Unable to open image output stream")
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        "Saved to Pictures/LoomPlay"
                    } else {
                        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "LoomPlay").apply { mkdirs() }
                        val outFile = File(dir, fileName)
                        FileOutputStream(outFile).use { out ->
                            bitmapToSave.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        "Saved screenshot: $fileName"
                    }
                }
                bitmapToSave.recycle()
                withContext(Dispatchers.Main) {
                    saved.onSuccess { msg -> onResult(true, msg) }
                        .onFailure { err -> onResult(false, err.message ?: "Screenshot save failed") }
                }
            }
        },
        Handler(Looper.getMainLooper()),
    )
}

private fun cropLetterboxIfNeeded(
    source: Bitmap,
    videoSize: VideoSize,
    aspectRatio: AspectRatioMode,
): Bitmap {
    if (aspectRatio == AspectRatioMode.FILL || aspectRatio == AspectRatioMode.STRETCH) {
        return source
    }
    val targetRatio = when (aspectRatio) {
        AspectRatioMode.RATIO_16_9 -> 16f / 9f
        AspectRatioMode.RATIO_4_3 -> 4f / 3f
        AspectRatioMode.FIT -> if (videoSize.width > 0 && videoSize.height > 0) {
            videoSize.width.toFloat() / videoSize.height.toFloat()
        } else {
            return source
        }
        AspectRatioMode.FILL,
        AspectRatioMode.STRETCH -> return source
    }
    if (targetRatio <= 0f || source.width <= 4 || source.height <= 4) return source

    val surfaceRatio = source.width.toFloat() / source.height.toFloat()
    var cropX = 0
    var cropY = 0
    var cropW = source.width
    var cropH = source.height

    if (surfaceRatio > targetRatio + 0.01f) {
        cropW = (source.height * targetRatio).roundToInt().coerceIn(1, source.width)
        cropX = ((source.width - cropW) / 2).coerceAtLeast(0)
    } else if (targetRatio > surfaceRatio + 0.01f) {
        cropH = (source.width / targetRatio).roundToInt().coerceIn(1, source.height)
        cropY = ((source.height - cropH) / 2).coerceAtLeast(0)
    }

    return if (cropW < source.width - 4 || cropH < source.height - 4) {
        Bitmap.createBitmap(source, cropX, cropY, cropW, cropH)
    } else {
        source
    }
}
