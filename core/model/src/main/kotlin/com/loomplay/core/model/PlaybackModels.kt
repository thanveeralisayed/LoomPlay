package com.loomplay.core.model

enum class EngineType {
    EXOPLAYER,
    MPV,
}

enum class EnginePreference {
    AUTO,
    FORCE_EXOPLAYER,
    FORCE_MPV,
}

enum class MpvHwDecMode(val mpvValue: String, val label: String) {
    AUTO("mediacodec,mediacodec-copy", "Automatic"),
    MEDIACODEC("mediacodec", "Hardware"),
    MEDIACODEC_COPY("mediacodec-copy", "Hybrid"),
    SOFTWARE("no", "Software"),
}

enum class AspectRatioMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    STRETCH("Stretch"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class LibrarySortOrder(val label: String) {
    DATE_ADDED("Date Added"),
    NAME("Name"),
    DURATION("Duration"),
    SIZE("Size"),
}

enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    READY,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

data class VideoSize(
    val width: Int = 0,
    val height: Int = 0,
)

data class MediaTrack(
    val id: String,
    val title: String,
    val language: String?,
    val codec: String?,
    val isSelected: Boolean,
)

data class MediaTracks(
    val videoTracks: List<MediaTrack> = emptyList(),
    val audioTracks: List<MediaTrack> = emptyList(),
    val subtitleTracks: List<MediaTrack> = emptyList(),
)

data class DecoderStats(
    val activeEngine: EngineType = EngineType.EXOPLAYER,
    val decoderName: String = "Initializing",
    val isHardwareAccelerated: Boolean = false,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val droppedFrames: Long = 0L,
    val totalFrames: Long = 0L,
    val bitrateMbps: Float = 0f,
    val bufferCacheMb: Float = 0f,
    val bufferAheadSec: Float = 0f,
    val audioEngine: String = "AudioTrack",
    val renderSurface: String = "SurfaceView",
)

data class LoomMediaItem(
    val uri: String,
    val title: String,
    val folderName: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String? = null,
    val dateAddedEpochSec: Long = 0L,
    val lastPositionMs: Long = 0L,
    val lastPlayedEpochMs: Long = 0L,
    val isCompleted: Boolean = false,
    val preferredEngine: EnginePreference = EnginePreference.AUTO,
    val recommendedEngine: EngineType = EngineType.EXOPLAYER,
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (lastPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt()

    val resolutionBadge: String?
        get() = when {
            width >= 3800 || height >= 2100 -> "4K"
            width >= 1900 || height >= 1060 -> "1080p"
            width >= 1260 || height >= 700 -> "720p"
            height > 0 -> "${height}p"
            else -> null
        }

    val containerLabel: String
        get() {
            val mimeExt = mimeType?.substringAfterLast('/')?.uppercase()
            val titleExt = title.substringAfterLast('.', "").uppercase()
            return when {
                titleExt.length in 2..5 -> titleExt
                !mimeExt.isNullOrBlank() -> mimeExt.removePrefix("X-").take(6)
                else -> "VIDEO"
            }
        }
}

data class PlaylistSummary(
    val playlistId: Long,
    val name: String,
    val itemCount: Int,
    val updatedAtEpochMs: Long,
)

data class MediaChapter(
    val title: String,
    val startMs: Long,
)

data class SubtitleStyleConfig(
    val fontSizeSp: Float = 20f,
    val outlineEnabled: Boolean = true,
    val backgroundOpacity: Float = 0.45f,
    val verticalMarginPercent: Int = 8,
    val mpvAssOverride: Boolean = false,
)

data class NetworkStreamEntry(
    val url: String,
    val title: String = "",
    val headers: Map<String, String> = emptyMap(),
    val preferredEngine: EnginePreference = EnginePreference.AUTO,
)

fun List<LoomMediaItem>.sortedByOrder(order: LibrarySortOrder): List<LoomMediaItem> = when (order) {
    LibrarySortOrder.DATE_ADDED -> sortedByDescending { it.dateAddedEpochSec }
    LibrarySortOrder.NAME -> sortedBy { it.title.lowercase() }
    LibrarySortOrder.DURATION -> sortedByDescending { it.durationMs }
    LibrarySortOrder.SIZE -> sortedByDescending { it.fileSizeBytes }
}
