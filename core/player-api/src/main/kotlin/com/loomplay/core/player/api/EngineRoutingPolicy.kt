package com.loomplay.core.player.api

import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.EngineType

object EngineRoutingPolicy {
    private val exoAdaptiveExtensions = setOf("m3u8", "mpd")
    private val mpvPreferredExtensions = setOf("mkv", "avi", "rmvb", "flv", "ts", "m2ts", "vob")
    private val mpvPreferredSchemes = setOf("rtsp", "rtsps", "rtmp", "rtmps", "mms", "mmsh", "udp", "srt")
    private val exoAdaptiveMimeTypes = setOf(
        "application/x-mpegurl",
        "application/vnd.apple.mpegurl",
        "application/dash+xml",
    )
    private val mpvPreferredMimeTypes = setOf(
        "video/x-matroska",
        "application/x-matroska",
        "video/x-msvideo",
        "video/avi",
        "video/x-flv",
        "video/mp2t",
        "application/vnd.rn-realmedia",
        "application/vnd.rn-realmedia-vbr",
    )

    fun selectInitialEngine(
        source: LoomMediaSource,
        globalPreference: EnginePreference,
    ): EngineType = selectInitialEngine(
        uriString = source.uri.toString(),
        mimeType = source.mimeType,
        sourcePreference = source.preferredEngine,
        globalPreference = globalPreference,
    )

    fun selectInitialEngine(
        uriString: String,
        sourcePreference: EnginePreference,
        globalPreference: EnginePreference,
    ): EngineType = selectInitialEngine(
        uriString = uriString,
        mimeType = null,
        sourcePreference = sourcePreference,
        globalPreference = globalPreference,
    )

    fun selectInitialEngine(
        uriString: String,
        mimeType: String?,
        sourcePreference: EnginePreference,
        globalPreference: EnginePreference,
    ): EngineType {
        val effectivePreference = when {
            sourcePreference != EnginePreference.AUTO -> sourcePreference
            else -> globalPreference
        }

        return when (effectivePreference) {
            EnginePreference.FORCE_EXOPLAYER -> EngineType.EXOPLAYER
            EnginePreference.FORCE_MPV -> EngineType.MPV
            EnginePreference.AUTO -> {
                val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
                if (normalizedMime in exoAdaptiveMimeTypes) return EngineType.EXOPLAYER
                if (normalizedMime in mpvPreferredMimeTypes) return EngineType.MPV

                val scheme = uriString.substringBefore("://", missingDelimiterValue = "").lowercase()
                if (scheme in mpvPreferredSchemes) return EngineType.MPV

                val pathOnly = uriString.substringBefore('?').substringBefore('#').lowercase()
                val fileName = pathOnly.substringAfterLast('/', missingDelimiterValue = pathOnly)
                val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
                when {
                    ext in exoAdaptiveExtensions -> EngineType.EXOPLAYER
                    ext in mpvPreferredExtensions -> EngineType.MPV
                    else -> EngineType.EXOPLAYER
                }
            }
        }
    }

    fun canFallback(
        currentEngine: EngineType,
        error: LoomPlayerError,
        attemptedEngines: Set<EngineType>,
    ): Boolean {
        if (currentEngine != EngineType.EXOPLAYER) return false
        if (EngineType.MPV in attemptedEngines) return false
        return error is LoomPlayerError.UnsupportedFormat
    }
}
