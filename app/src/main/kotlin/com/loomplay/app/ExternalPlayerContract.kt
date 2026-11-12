package com.loomplay.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.OpenableColumns
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.player.api.ExternalSubtitleSource

data class ExternalPlayerParams(
    val uri: String,
    val title: String,
    val mimeType: String?,
    val initialPositionMs: Long?,
    val forceStartFromBeginning: Boolean,
    val returnResult: Boolean,
    val headers: Map<String, String>,
    val subtitles: List<ExternalSubtitleSource>,
)

@Suppress("DEPRECATION")
object ExternalPlayerContract {
    const val ACTION_MX_RESULT = "com.mxtech.intent.result.VIEW"
    const val EXTRA_POSITION = "position"
    const val EXTRA_DURATION = "duration"
    const val EXTRA_END_BY = "end_by"
    const val END_BY_USER = "user"
    const val END_BY_COMPLETION = "playback_completion"

    const val EXTRA_VLC_POSITION = "extra_position"
    const val EXTRA_VLC_DURATION = "extra_duration"
    const val EXTRA_VLC_URI = "extra_uri"

    fun parseIntent(
        incoming: Intent?,
        contentResolver: ContentResolver? = null,
        hasCallingActivity: Boolean = false,
    ): ExternalPlayerParams? {
        val uri = incoming?.data ?: return null
        val uriString = uri.toString().takeIf { it.isNotBlank() } ?: return null

        val resolvedMime = incoming.type?.takeIf { it.isNotBlank() && it != "*/*" }
            ?: runCatching { contentResolver?.getType(uri) }.getOrNull()

        val extras = incoming.extras
        val returnResult = hasCallingActivity || extractBooleanExtra(extras, "return_result") ||
            extractBooleanExtra(extras, "android.intent.extra.RETURN_RESULT")

        val fromStart = extractBooleanExtra(extras, "from_start")
        val initialPositionMs = if (fromStart) 0L else extractPositionMs(extras)

        val resolvedTitle = resolveTitle(incoming, extras, uri, contentResolver)
        val headers = extractHeaders(extras)
        val subtitles = extractSubtitles(extras)

        return ExternalPlayerParams(
            uri = uriString,
            title = resolvedTitle,
            mimeType = resolvedMime,
            initialPositionMs = initialPositionMs,
            forceStartFromBeginning = fromStart,
            returnResult = returnResult,
            headers = headers,
            subtitles = subtitles,
        )
    }

    fun buildResultIntent(
        mediaUri: String,
        positionMs: Long,
        durationMs: Long,
        endedNormally: Boolean,
    ): Intent {
        val safePos = positionMs.coerceAtLeast(0L)
        val safeDur = durationMs.coerceAtLeast(0L)
        val endBy = if (endedNormally) END_BY_COMPLETION else END_BY_USER

        return Intent(ACTION_MX_RESULT).apply {
            data = runCatching { Uri.parse(mediaUri) }.getOrNull()
            putExtra(EXTRA_END_BY, endBy)
            putExtra(EXTRA_POSITION, safePos.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            putExtra(EXTRA_DURATION, safeDur.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            putExtra(EXTRA_VLC_POSITION, safePos)
            putExtra(EXTRA_VLC_DURATION, safeDur)
            putExtra(EXTRA_VLC_URI, mediaUri)
        }
    }

    internal fun extractPositionMs(extras: Bundle?): Long? {
        if (extras == null) return null
        val keys = listOf("position", "extra_position", "startfrom", "start_from", "offset")
        for (key in keys) {
            val raw = extras.get(key) ?: continue
            val coerced = when (raw) {
                is Number -> raw.toLong()
                is String -> raw.trim().toLongOrNull() ?: raw.trim().toDoubleOrNull()?.toLong()
                else -> null
            }
            if (coerced != null && coerced >= 0L) {
                return coerced
            }
        }
        return null
    }

    internal fun extractBooleanExtra(extras: Bundle?, key: String): Boolean {
        if (extras == null) return false
        val raw = extras.get(key) ?: return false
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.trim().equals("true", ignoreCase = true) || raw.trim() == "1"
            else -> false
        }
    }

    internal fun resolveTitle(
        incoming: Intent,
        extras: Bundle?,
        uri: Uri,
        contentResolver: ContentResolver?,
    ): String {
        val titleKeys = listOf("title", Intent.EXTRA_TITLE, "name", "video_title")
        for (key in titleKeys) {
            val candidate = extras?.getString(key)?.takeIf { it.isNotBlank() }
            if (candidate != null) return candidate
        }

        if (uri.scheme == "content" && contentResolver != null) {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && cursor.moveToFirst()) {
                            cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                        }
                    }
            }
        }

        return uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let(Uri::decode) ?: "Video"
    }

    internal fun extractHeaders(extras: Bundle?): Map<String, String> {
        if (extras == null) return emptyMap()
        val result = LinkedHashMap<String, String>()

        val headerKeys = listOf("headers", "http_headers", "http-headers")
        for (key in headerKeys) {
            val raw = extras.get(key) ?: continue
            when (raw) {
                is Array<*> -> {
                    val strings = raw.mapNotNull { it?.toString() }
                    for (i in 0 until strings.size - 1 step 2) {
                        val headerKey = strings[i].trim()
                        val headerValue = strings[i + 1]
                        if (headerKey.isNotEmpty()) {
                            result[headerKey] = headerValue
                        }
                    }
                }
                is List<*> -> {
                    val strings = raw.mapNotNull { it?.toString() }
                    for (i in 0 until strings.size - 1 step 2) {
                        val headerKey = strings[i].trim()
                        val headerValue = strings[i + 1]
                        if (headerKey.isNotEmpty()) {
                            result[headerKey] = headerValue
                        }
                    }
                }
                is Bundle -> {
                    for (k in raw.keySet()) {
                        val v = raw.getString(k) ?: raw.get(k)?.toString() ?: continue
                        if (k.isNotBlank()) {
                            result[k] = v
                        }
                    }
                }
                is Map<*, *> -> {
                    for ((k, v) in raw) {
                        val headerKey = k?.toString()?.trim() ?: continue
                        val headerValue = v?.toString() ?: continue
                        if (headerKey.isNotEmpty()) {
                            result[headerKey] = headerValue
                        }
                    }
                }
            }
        }

        val individualHeaders = mapOf(
            "user_agent" to "User-Agent",
            "User-Agent" to "User-Agent",
            "referer" to "Referer",
            "Referer" to "Referer",
            "cookie" to "Cookie",
            "Cookie" to "Cookie",
            "authorization" to "Authorization",
            "Authorization" to "Authorization",
        )
        for ((extraKey, canonicalHeader) in individualHeaders) {
            if (!result.containsKey(canonicalHeader)) {
                val value = extras.getString(extraKey)?.takeIf { it.isNotBlank() }
                if (value != null) {
                    result[canonicalHeader] = value
                }
            }
        }

        return result
    }

    internal fun extractSubtitles(extras: Bundle?): List<ExternalSubtitleSource> {
        if (extras == null) return emptyList()
        val subsRaw = extras.get("subs") ?: return emptyList()

        val subUris: List<Uri> = when (subsRaw) {
            is Array<*> -> subsRaw.mapNotNull { toUri(it) }
            is List<*> -> subsRaw.mapNotNull { toUri(it) }
            else -> listOfNotNull(toUri(subsRaw))
        }
        if (subUris.isEmpty()) return emptyList()

        val names: List<String?> = when (val raw = extras.get("subs.name")) {
            is Array<*> -> raw.map { it?.toString() }
            is List<*> -> raw.map { it?.toString() }
            else -> listOfNotNull(raw?.toString())
        }
        val filenames: List<String?> = when (val raw = extras.get("subs.filename")) {
            is Array<*> -> raw.map { it?.toString() }
            is List<*> -> raw.map { it?.toString() }
            else -> listOfNotNull(raw?.toString())
        }

        val enabledUris: Set<String> = when (val raw = extras.get("subs.enable")) {
            is Array<*> -> raw.mapNotNull { toUri(it)?.toString() }.toSet()
            is List<*> -> raw.mapNotNull { toUri(it)?.toString() }.toSet()
            else -> listOfNotNull(toUri(raw)?.toString()).toSet()
        }

        return subUris.mapIndexed { index, uri ->
            val name = names.getOrNull(index)?.takeIf { it.isNotBlank() }
            val filename = filenames.getOrNull(index)?.takeIf { it.isNotBlank() }
            val title = name ?: filename ?: uri.lastPathSegment ?: "External Subtitle ${index + 1}"
            val mime = resolveSubtitleMime(filename ?: name ?: uri.toString())
            val isDefault = enabledUris.contains(uri.toString()) ||
                (enabledUris.isEmpty() && subUris.size == 1 && extras.containsKey("subs.enable"))

            ExternalSubtitleSource(
                uri = uri,
                mimeType = mime,
                title = title,
                isDefault = isDefault,
            )
        }
    }

    private fun toUri(item: Any?): Uri? = when (item) {
        is Uri -> item
        is Parcelable -> (item as? Uri) ?: runCatching { Uri.parse(item.toString()) }.getOrNull()
        is CharSequence -> item.toString().takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        else -> null
    }

    private fun resolveSubtitleMime(pathOrName: String): String {
        val lower = pathOrName.lowercase()
        return when {
            lower.endsWith(".vtt") -> "text/vtt"
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> "text/x-ssa"
            lower.endsWith(".ttml") || lower.endsWith(".dfxp") -> "application/ttml+xml"
            else -> "application/x-subrip"
        }
    }
}

class ExternalPlaybackTracker(
    val mediaUri: String,
    val initialPositionMs: Long? = null,
) {
    var latestPositionMs: Long = initialPositionMs?.coerceAtLeast(0L) ?: 0L
        private set
    var latestDurationMs: Long = 0L
        private set
    var hasEnded: Boolean = false
        private set
    var hadPlayableProgress: Boolean = false
        private set

    fun update(positionMs: Long, durationMs: Long, status: PlaybackStatus) {
        if (durationMs > 0L) {
            latestDurationMs = durationMs
        }

        when (status) {
            PlaybackStatus.ENDED -> {
                hasEnded = true
                hadPlayableProgress = true
                latestPositionMs = if (latestDurationMs > 0L) maxOf(positionMs, latestDurationMs) else positionMs
            }
            PlaybackStatus.PLAYING, PlaybackStatus.PAUSED, PlaybackStatus.READY, PlaybackStatus.BUFFERING -> {
                if (positionMs > 0L) {
                    hadPlayableProgress = true
                    latestPositionMs = positionMs
                } else if (positionMs == 0L && hadPlayableProgress) {
                    latestPositionMs = 0L
                }
                if (status == PlaybackStatus.PLAYING && latestDurationMs > 0L && positionMs < latestDurationMs - 3_000L) {
                    hasEnded = false
                }
            }
            PlaybackStatus.IDLE, PlaybackStatus.ERROR -> {
                // Do not clobber latest valid position on teardown or transient idle
            }
        }
    }

    fun buildResultIntent(): Intent = ExternalPlayerContract.buildResultIntent(
        mediaUri = mediaUri,
        positionMs = latestPositionMs,
        durationMs = latestDurationMs,
        endedNormally = hasEnded,
    )
}

