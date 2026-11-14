package com.loomplay.core.model

object MediaFormatQualityHelper {

    /**
     * Resolves human-readable video quality badge such as:
     * "4K DV", "4K HDR", "4K", "1440p", "1080p DV", "1080p HDR", "1080p", "720p", "480p", "SD"
     */
    fun resolveVideoQualityBadge(width: Int, height: Int, colorProfile: String?): String? {
        val maxDim = maxOf(width, height)
        val minDim = minOf(width, height)
        if (maxDim <= 0 && minDim <= 0) return null

        val baseResolution = when {
            // 4K UHD / DCI
            maxDim >= 3600 || minDim >= 2000 -> "4K"
            // 1440p QHD / 2K
            maxDim >= 2400 || minDim >= 1400 -> "1440p"
            // 1080p FHD
            maxDim >= 1800 || minDim >= 1000 -> "1080p"
            // 720p HD
            maxDim >= 1200 || minDim >= 700 -> "720p"
            // 576p / 480p SD
            maxDim >= 700 || minDim >= 450 -> "480p"
            minDim in 1..449 -> "SD"
            else -> null
        } ?: return null

        val normProfile = colorProfile?.uppercase().orEmpty()
        val hdrTag = when {
            normProfile.contains("DV") || normProfile.contains("DOLBY VISION") || normProfile.contains("DOVI") -> "DV"
            normProfile.contains("HDR10+") || normProfile.contains("HDR10PLUS") -> "HDR10+"
            normProfile.contains("HDR") || normProfile.contains("HLG") || normProfile.contains("PQ") || normProfile.contains("ST2084") -> "HDR"
            else -> null
        }

        return if (hdrTag != null) {
            "$baseResolution $hdrTag"
        } else {
            baseResolution
        }
    }

    /**
     * Resolves human-readable audio technology badge such as:
     * "Dolby Atmos", "Dolby Digital+", "Dolby Digital", "Dolby TrueHD", "DTS:X", "DTS-HD", "DTS", "FLAC", "AAC", "Opus", etc.
     */
    fun resolveAudioTechnologyBadge(audioCodec: String?): String? {
        val raw = audioCodec?.trim() ?: return null
        if (raw.isBlank()) return null
        val lower = raw.lowercase()

        return when {
            lower.contains("atmos") -> "Dolby Atmos"
            lower.contains("truehd") -> "Dolby TrueHD"
            lower.contains("eac3") || lower.contains("e-ac-3") || lower.contains("eac-3") || lower.contains("ddp") || lower.contains("dolby digital plus") || lower.contains("ec-3") -> "Dolby Digital+"
            lower.contains("ac3") || lower.contains("ac-3") || lower.contains("dolby digital") -> "Dolby Digital"
            lower.contains("dts-hd") || lower.contains("dtshd") || lower.contains("dts-ma") || lower.contains("dts_ma") -> "DTS-HD"
            lower.contains("dtsx") || lower.contains("dts:x") -> "DTS:X"
            lower.contains("dts") -> "DTS"
            lower.contains("flac") -> "FLAC"
            lower.contains("alac") -> "ALAC"
            lower.contains("opus") -> "Opus"
            lower.contains("vorbis") -> "Vorbis"
            lower.contains("mp3") || lower.contains("mpeg") && lower.contains("layer") -> "MP3"
            lower.contains("aac") || lower.contains("mp4a") -> "AAC"
            lower.contains("pcm") || lower.contains("wav") -> "PCM"
            else -> raw.substringAfterLast('/').substringAfterLast('.').uppercase().take(8)
        }
    }
}
