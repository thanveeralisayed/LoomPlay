package com.loomplay.core.player.mpv

import com.loomplay.core.player.api.LoomPlayerError
import kotlin.math.roundToInt

/**
 * Encodes HTTP headers for libmpv's `http-header-fields` string-list property using
 * mpv's fixed-length `%bytes%string` framing so values containing commas or spaces
 * are never split into separate header lines.
 */
internal fun encodeMpvHeaderFields(headers: Map<String, String>): String {
    if (headers.isEmpty()) return ""
    val sb = StringBuilder()
    headers.forEach { (rawKey, rawValue) ->
        val key = rawKey.replace("\r", "").replace("\n", "").trim()
        val value = rawValue.replace("\r", "").replace("\n", "").trim()
        if (key.isNotEmpty() &&
            !key.equals("User-Agent", ignoreCase = true) &&
            !key.equals("Referer", ignoreCase = true)
        ) {
            val line = "$key: $value"
            val byteLen = line.toByteArray(Charsets.UTF_8).size
            sb.append('%').append(byteLen).append('%').append(line)
        }
    }
    return sb.toString()
}

internal fun parseMpvConfigLines(rawConfig: String): List<Pair<String, String>> =
    rawConfig.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('=', limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key.isNotEmpty()) key to value else null
            } else {
                line to "yes"
            }
        }
        .toList()

/**
 * Converts percentage bottom margin into libmpv virtual 720p coordinate pixels so
 * subtitle placement matches ExoPlayer's fractional bottom padding.
 */
internal fun computeMpvMarginY(verticalMarginPercent: Int): Int {
    val clamped = verticalMarginPercent.coerceIn(2, 20)
    return ((clamped / 100f) * 720f).roundToInt()
}

internal fun mapMpvError(recentErrorLog: String?, hadLoadedFile: Boolean): LoomPlayerError {
    val detail = recentErrorLog?.trim()?.takeIf { it.isNotEmpty() }
    val normalized = detail?.lowercase().orEmpty()
    return when {
        normalized.contains("unknown file format") ||
            normalized.contains("failed to recognize file format") ||
            normalized.contains("unrecognized") ||
            normalized.contains("no video or audio streams") ||
            normalized.contains("unsupported") ||
            normalized.contains("decoder") ||
            normalized.contains("codec") -> {
            LoomPlayerError.UnsupportedFormat(
                message = detail ?: "Unsupported media format",
            )
        }
        normalized.contains("http error") ||
            normalized.contains("403") ||
            normalized.contains("404") ||
            normalized.contains("tls") ||
            normalized.contains("ssl") ||
            normalized.contains("timed out") ||
            normalized.contains("timeout") ||
            normalized.contains("connection") ||
            normalized.contains("network") ||
            normalized.contains("unable to open") ||
            normalized.contains("cannot open") ||
            normalized.contains("no such file") ||
            normalized.contains("permission denied") ||
            normalized.contains("protocol not found") ||
            normalized.contains("i/o error") -> {
            LoomPlayerError.NetworkOrIo(
                message = detail ?: "Network or file I/O error",
            )
        }
        else -> {
            val fallbackMsg = if (hadLoadedFile) {
                "MPV stream ended unexpectedly"
            } else {
                "MPV failed to load media source"
            }
            LoomPlayerError.PlaybackFailure(
                message = detail ?: fallbackMsg,
            )
        }
    }
}

