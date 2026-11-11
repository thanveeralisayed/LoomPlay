package com.loomplay.core.mediasession

import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize as Media3VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.loomplay.core.model.EngineType
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.model.VideoSize
import com.loomplay.core.player.api.ActiveMediaMetadata
import com.loomplay.core.player.api.LoomPlaybackSessionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(UnstableApi::class)
internal class LoomMedia3PlayerAdapter(
    private val bridge: LoomPlaybackSessionBridge,
    private val onRequestAudioFocus: () -> Boolean,
    private val onStopRequested: () -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var playWhenReadyFlag = false
    private var anchorPosMs = 0L
    private var anchorElapsedMs = SystemClock.elapsedRealtime()
    private var lastIsPlaying = false
    private var lastSpeed = 1.0f

    init {
        scope.launch {
            bridge.playbackState.collect { status ->
                val hasMedia = bridge.mediaMetadata.value.uri.isNotBlank()
                val isPlaying = hasMedia && (status == PlaybackStatus.PLAYING || status == PlaybackStatus.BUFFERING)
                playWhenReadyFlag = isPlaying
                lastIsPlaying = hasMedia && status == PlaybackStatus.PLAYING
                anchorPosMs = bridge.positionMs.value.coerceAtLeast(0L)
                anchorElapsedMs = SystemClock.elapsedRealtime()
                invalidateState()
            }
        }
        scope.launch {
            bridge.durationMs.collect {
                invalidateState()
            }
        }
        scope.launch {
            bridge.playbackSpeed.collect { speed ->
                lastSpeed = speed.coerceIn(0.25f, 4.0f)
                anchorPosMs = bridge.positionMs.value.coerceAtLeast(0L)
                anchorElapsedMs = SystemClock.elapsedRealtime()
                invalidateState()
            }
        }
        scope.launch {
            bridge.videoSize.collect {
                invalidateState()
            }
        }
        scope.launch {
            bridge.mediaMetadata.collect { meta ->
                if (meta.uri.isBlank()) {
                    playWhenReadyFlag = false
                    lastIsPlaying = false
                }
                invalidateState()
            }
        }
        scope.launch {
            bridge.positionMs.collect { pos ->
                val safePos = pos.coerceAtLeast(0L)
                val now = SystemClock.elapsedRealtime()
                val expected = if (lastIsPlaying) {
                    anchorPosMs + ((now - anchorElapsedMs) * lastSpeed).toLong()
                } else {
                    anchorPosMs
                }
                if (!lastIsPlaying || abs(safePos - expected) > 1_200L) {
                    anchorPosMs = safePos
                    anchorElapsedMs = now
                    invalidateState()
                }
            }
        }
    }

    override fun getState(): State = buildLoomMedia3State(
        status = bridge.playbackState.value,
        meta = bridge.mediaMetadata.value,
        durationMs = bridge.durationMs.value,
        positionMs = bridge.positionMs.value,
        bufferedPositionMs = bridge.bufferedPositionMs.value,
        playbackSpeed = bridge.playbackSpeed.value,
        videoSize = bridge.videoSize.value,
        playWhenReadyRequested = playWhenReadyFlag,
    )

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (bridge.mediaMetadata.value.uri.isBlank()) {
            playWhenReadyFlag = false
            invalidateState()
            return Futures.immediateVoidFuture()
        }
        playWhenReadyFlag = playWhenReady
        if (playWhenReady) {
            if (onRequestAudioFocus()) {
                bridge.play()
            } else {
                playWhenReadyFlag = false
                bridge.pause()
            }
        } else {
            bridge.pause()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playWhenReadyFlag = false
        bridge.pause()
        onStopRequested()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val currentIdx = if (bridge.mediaMetadata.value.hasPrevious) 1 else 0
        when {
            seekCommand == Player.COMMAND_SEEK_TO_NEXT ||
                seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex > currentIdx) -> {
                bridge.skipToNext()
            }
            seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS ||
                seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                (mediaItemIndex in 0 until currentIdx) -> {
                bridge.skipToPrevious()
            }
            seekCommand == Player.COMMAND_SEEK_BACK -> {
                bridge.seekTo((bridge.positionMs.value - 10_000L).coerceAtLeast(0L))
            }
            seekCommand == Player.COMMAND_SEEK_FORWARD -> {
                bridge.seekTo(bridge.positionMs.value + 10_000L)
            }
            positionMs != C.TIME_UNSET -> {
                bridge.seekTo(positionMs.coerceAtLeast(0L))
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        bridge.setPlaybackSpeed(playbackParameters.speed)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }
}

@OptIn(UnstableApi::class)
internal fun buildLoomMedia3State(
    status: PlaybackStatus,
    meta: ActiveMediaMetadata,
    durationMs: Long,
    positionMs: Long,
    bufferedPositionMs: Long,
    playbackSpeed: Float,
    videoSize: VideoSize,
    playWhenReadyRequested: Boolean,
): SimpleBasePlayer.State {
    val durMs = (durationMs.takeIf { it > 0L } ?: meta.durationMs).coerceAtLeast(0L)
    val posMs = positionMs.coerceAtLeast(0L)
    val bufMs = bufferedPositionMs.coerceAtLeast(posMs)
    val speed = playbackSpeed.coerceIn(0.25f, 4.0f)

    val hasMedia = meta.uri.isNotBlank()
    // Empty playlists are only legal in STATE_IDLE or STATE_ENDED; clamp blank metadata to IDLE
    // so transient flow updates during release or startup can never violate SimpleBasePlayer invariants.
    val playerState = if (!hasMedia) {
        Player.STATE_IDLE
    } else {
        when (status) {
            PlaybackStatus.IDLE, PlaybackStatus.ERROR -> Player.STATE_IDLE
            PlaybackStatus.BUFFERING -> Player.STATE_BUFFERING
            PlaybackStatus.READY, PlaybackStatus.PAUSED, PlaybackStatus.PLAYING -> Player.STATE_READY
            PlaybackStatus.ENDED -> Player.STATE_ENDED
        }
    }

    val commandsBuilder = Player.Commands.Builder()
    if (hasMedia) {
        commandsBuilder.addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE,
        )
        if (meta.hasNext) {
            commandsBuilder.addAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            )
        }
        if (meta.hasPrevious) {
            commandsBuilder.addAll(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
        }
    }

    val isPlayingNow = hasMedia && status == PlaybackStatus.PLAYING
    val effectivePlayWhenReady = hasMedia && playWhenReadyRequested
    val posSupplier = if (isPlayingNow) {
        SimpleBasePlayer.PositionSupplier.getExtrapolating(posMs, speed)
    } else {
        SimpleBasePlayer.PositionSupplier.getConstant(posMs)
    }

    val builder = SimpleBasePlayer.State.Builder()
        .setAvailableCommands(commandsBuilder.build())
        .setPlayWhenReady(effectivePlayWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackState(playerState)
        .setPlaybackParameters(PlaybackParameters(speed))
        .setSeekBackIncrementMs(10_000L)
        .setSeekForwardIncrementMs(10_000L)
        .setMaxSeekToPreviousPositionMs(4_000L)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setVideoSize(
            if (videoSize.width > 0 && videoSize.height > 0) {
                Media3VideoSize(videoSize.width, videoSize.height)
            } else {
                Media3VideoSize.UNKNOWN
            },
        )
        .setContentPositionMs(posSupplier)
        .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier.getConstant(bufMs))

    if (hasMedia) {
        val playlist = mutableListOf<SimpleBasePlayer.MediaItemData>()
        if (meta.hasPrevious) {
            playlist += SimpleBasePlayer.MediaItemData.Builder("prev:${meta.uri}")
                .setIsPlaceholder(true)
                .setMediaItem(MediaItem.Builder().setMediaId("__prev__").build())
                .build()
        }

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(meta.title.ifBlank { "LoomPlay Video" })
            .apply {
                meta.subtitle?.takeIf { it.isNotBlank() }?.let(::setSubtitle)
            }
            .setArtist("LoomPlay")
            .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
            .build()

        val currentItem = MediaItem.Builder()
            .setMediaId(meta.uri)
            .setUri(meta.uri)
            .setMediaMetadata(mediaMetadata)
            .build()

        playlist += SimpleBasePlayer.MediaItemData.Builder("current:${meta.uri}")
            .setMediaItem(currentItem)
            .setMediaMetadata(mediaMetadata)
            .setIsSeekable(durMs > 0L)
            .setDurationUs(if (durMs > 0L) durMs * 1000L else C.TIME_UNSET)
            .build()

        if (meta.hasNext) {
            playlist += SimpleBasePlayer.MediaItemData.Builder("next:${meta.uri}")
                .setIsPlaceholder(true)
                .setMediaItem(MediaItem.Builder().setMediaId("__next__").build())
                .build()
        }

        val currentIdx = if (meta.hasPrevious) 1 else 0
        builder
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(currentIdx)
    } else {
        builder
            .setPlaylist(emptyList())
            .setCurrentMediaItemIndex(C.INDEX_UNSET)
    }

    return builder.build()
}

