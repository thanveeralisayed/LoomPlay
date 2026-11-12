package com.loomplay.core.player.api

import android.net.Uri
import android.widget.FrameLayout
import com.loomplay.core.model.AspectRatioMode
import com.loomplay.core.model.DecoderStats
import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.EngineType
import com.loomplay.core.model.MediaChapter
import com.loomplay.core.model.MediaTracks
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.model.SubtitleStyleConfig
import com.loomplay.core.model.VideoSize
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class ExternalSubtitleSource(
    val uri: Uri,
    val mimeType: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
)

data class LoomMediaSource(
    val uri: Uri,
    val title: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val preferredEngine: EnginePreference = EnginePreference.AUTO,
    val initialExternalSubtitles: List<ExternalSubtitleSource> = emptyList(),
)

sealed interface LoomPlayerError {
    val message: String
    val cause: Throwable?

    data class UnsupportedFormat(
        override val message: String,
        override val cause: Throwable? = null,
    ) : LoomPlayerError

    data class NetworkOrIo(
        override val message: String,
        override val cause: Throwable? = null,
    ) : LoomPlayerError

    data class PlaybackFailure(
        override val message: String,
        override val cause: Throwable? = null,
    ) : LoomPlayerError
}

interface ILoomPlayer {
    val engineType: EngineType
    val playbackState: StateFlow<PlaybackStatus>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val bufferedPositionMs: StateFlow<Long>
    val videoSize: StateFlow<VideoSize>
    val tracks: StateFlow<MediaTracks>
    val chapters: StateFlow<List<MediaChapter>>
    val playbackSpeed: StateFlow<Float>
    val stats: StateFlow<DecoderStats>
    val errors: SharedFlow<LoomPlayerError>

    fun attachSurfaceView(container: FrameLayout)
    fun detachSurfaceView()

    suspend fun prepare(
        source: LoomMediaSource,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
    )

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun selectAudioTrack(trackId: String)
    fun selectSubtitleTrack(trackId: String?)
    fun addExternalSubtitle(uri: Uri, mimeType: String?, title: String?)
    fun addExternalSubtitles(
        subtitles: List<ExternalSubtitleSource>,
        selectLastAdded: Boolean = true,
    ) {
        subtitles.forEach { sub ->
            addExternalSubtitle(sub.uri, sub.mimeType, sub.title)
        }
    }
    fun setSubtitleDelay(delayMs: Long)
    fun setAudioDelay(delayMs: Long)
    fun setAspectRatio(mode: AspectRatioMode)
    fun setSubtitleStyle(style: SubtitleStyleConfig) {}
    fun setVolumeFraction(fraction: Float) {}
    fun release()
}

data class ActiveMediaMetadata(
    val uri: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val durationMs: Long = 0L,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val engineType: EngineType = EngineType.EXOPLAYER,
)

interface LoomPlaybackSessionBridge {
    val activeEngine: StateFlow<EngineType>
    val playbackState: StateFlow<PlaybackStatus>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val bufferedPositionMs: StateFlow<Long>
    val playbackSpeed: StateFlow<Float>
    val videoSize: StateFlow<VideoSize>
    val mediaMetadata: StateFlow<ActiveMediaMetadata>
    val isBackgroundAudioEnabled: StateFlow<Boolean>
    val activeHostActivityClassName: StateFlow<String?>

    fun currentPositionMs(): Long = positionMs.value
    fun currentDurationMs(): Long = durationMs.value

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun skipToNext()
    fun skipToPrevious()
    fun setAudioFocusDucked(ducked: Boolean) {}
    fun stopSession()
}

interface LoomMediaSessionConnector {
    fun connectSession(ownerToken: Any = Unit)
    fun disconnectSession(ownerToken: Any = Unit)
}

