package com.loomplay.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFormatQualityHelperTest {

    @Test
    fun resolveVideoQualityBadge_detectsStandardResolutions() {
        assertEquals("4K", MediaFormatQualityHelper.resolveVideoQualityBadge(3840, 2160, null))
        assertEquals("1440p", MediaFormatQualityHelper.resolveVideoQualityBadge(2560, 1440, null))
        assertEquals("1080p", MediaFormatQualityHelper.resolveVideoQualityBadge(1920, 1080, null))
        assertEquals("720p", MediaFormatQualityHelper.resolveVideoQualityBadge(1280, 720, null))
        assertEquals("480p", MediaFormatQualityHelper.resolveVideoQualityBadge(854, 480, null))
        assertNull(MediaFormatQualityHelper.resolveVideoQualityBadge(0, 0, null))
    }

    @Test
    fun resolveVideoQualityBadge_detectsHdrAndDolbyVision() {
        assertEquals("4K DV", MediaFormatQualityHelper.resolveVideoQualityBadge(3840, 2160, "Dolby Vision"))
        assertEquals("1080p DV", MediaFormatQualityHelper.resolveVideoQualityBadge(1920, 1080, "DOVI"))
        assertEquals("4K HDR", MediaFormatQualityHelper.resolveVideoQualityBadge(3840, 2160, "HDR10"))
        assertEquals("1080p HDR", MediaFormatQualityHelper.resolveVideoQualityBadge(1920, 1080, "HLG"))
        assertEquals("1440p HDR", MediaFormatQualityHelper.resolveVideoQualityBadge(2560, 1440, "ST2084"))
    }

    @Test
    fun resolveAudioTechnologyBadge_detectsDolbyAndDts() {
        assertEquals("Dolby Atmos", MediaFormatQualityHelper.resolveAudioTechnologyBadge("eac3_atmos"))
        assertEquals("Dolby Digital+", MediaFormatQualityHelper.resolveAudioTechnologyBadge("audio/eac3"))
        assertEquals("Dolby Digital+", MediaFormatQualityHelper.resolveAudioTechnologyBadge("eac3"))
        assertEquals("Dolby Digital", MediaFormatQualityHelper.resolveAudioTechnologyBadge("audio/ac3"))
        assertEquals("Dolby Digital", MediaFormatQualityHelper.resolveAudioTechnologyBadge("ac3"))
        assertEquals("Dolby TrueHD", MediaFormatQualityHelper.resolveAudioTechnologyBadge("truehd"))
        assertEquals("DTS-HD", MediaFormatQualityHelper.resolveAudioTechnologyBadge("dts-hd"))
        assertEquals("DTS", MediaFormatQualityHelper.resolveAudioTechnologyBadge("dts"))
        assertEquals("FLAC", MediaFormatQualityHelper.resolveAudioTechnologyBadge("audio/flac"))
        assertEquals("AAC", MediaFormatQualityHelper.resolveAudioTechnologyBadge("audio/mp4a-latm"))
        assertEquals("AAC", MediaFormatQualityHelper.resolveAudioTechnologyBadge("aac"))
        assertEquals("Opus", MediaFormatQualityHelper.resolveAudioTechnologyBadge("audio/opus"))
    }
}
