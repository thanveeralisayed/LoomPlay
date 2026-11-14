package com.loomplay.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoomMediaItemTest {

    @Test
    fun `progressFraction and progressPercent clamp safely`() {
        val item = LoomMediaItem(
            uri = "content://media/external/video/media/42",
            title = "Suzume.2022.2160p.mkv",
            folderName = "Movies",
            durationMs = 100_000L,
            fileSizeBytes = 2_500_000_000L,
            width = 3840,
            height = 2160,
            lastPositionMs = 70_000L,
        )
        assertEquals(0.70f, item.progressFraction, 0.001f)
        assertEquals(70, item.progressPercent)

        val overflowItem = item.copy(lastPositionMs = 150_000L)
        assertEquals(1.0f, overflowItem.progressFraction, 0.001f)
        assertEquals(100, overflowItem.progressPercent)

        val zeroDuration = item.copy(durationMs = 0L)
        assertEquals(0f, zeroDuration.progressFraction, 0.001f)
    }

    @Test
    fun `resolutionBadge and containerLabel derive accurate metadata`() {
        val uhdMkv = LoomMediaItem(
            uri = "file:///Movies/Movie.mkv",
            title = "Movie.mkv",
            folderName = "Movies",
            durationMs = 60_000L,
            fileSizeBytes = 1_000L,
            width = 3840,
            height = 2160,
            mimeType = "video/x-matroska",
        )
        assertEquals("4K", uhdMkv.resolutionBadge)
        assertEquals("MKV", uhdMkv.containerLabel)

        val fhdMp4 = uhdMkv.copy(title = "Clip", width = 1920, height = 1080, mimeType = "video/mp4")
        assertEquals("1080p", fhdMp4.resolutionBadge)
        assertEquals("MP4", fhdMp4.containerLabel)

        val unknownDim = uhdMkv.copy(width = 0, height = 0)
        assertNull(unknownDim.resolutionBadge)
    }

    @Test
    fun `theme mode hwdec and subtitle config defaults match spec`() {
        assertEquals("System", ThemeMode.SYSTEM.label)
        assertEquals("auto-safe", MpvHwDecMode.AUTO.mpvValue)
        assertEquals(listOf("Fit", "Fill", "Stretch", "16:9", "4:3"), AspectRatioMode.entries.map { it.label })
        val style = SubtitleStyleConfig()
        assertEquals(20f, style.fontSizeSp, 0.001f)
        assertEquals(true, style.outlineEnabled)
        assertEquals(false, style.mpvAssOverride)
        val stream = NetworkStreamEntry(
            url = "https://cdn.example.com/master.m3u8",
            title = "Live Feed",
            headers = mapOf("Referer" to "https://example.com"),
            preferredEngine = EnginePreference.FORCE_MPV,
        )
        assertEquals(1, stream.headers.size)
        assertEquals(EnginePreference.FORCE_MPV, stream.preferredEngine)
    }

    @Test
    fun `frame rate matching mode options and default stats are correct`() {
        assertEquals("Disabled", FrameRateMatchingMode.OFF.label)
        assertEquals("Seamless Only", FrameRateMatchingMode.SEAMLESS.label)
        assertEquals("Always", FrameRateMatchingMode.ALWAYS.label)
        val stats = DecoderStats()
        assertEquals(0f, stats.displayRefreshRate, 0.001f)
    }

    @Test
    fun `sortedByOrder sorts library items deterministically`() {
        val a = LoomMediaItem("u1", "zeta.mp4", "Movies", durationMs = 10_000L, fileSizeBytes = 500L, dateAddedEpochSec = 100L)
        val b = LoomMediaItem("u2", "Alpha.mp4", "Movies", durationMs = 50_000L, fileSizeBytes = 200L, dateAddedEpochSec = 300L)
        val items = listOf(a, b)

        assertEquals(listOf("u2", "u1"), items.sortedByOrder(LibrarySortOrder.DATE_ADDED).map { it.uri })
        assertEquals(listOf("u2", "u1"), items.sortedByOrder(LibrarySortOrder.NAME).map { it.uri })
        assertEquals(listOf("u2", "u1"), items.sortedByOrder(LibrarySortOrder.DURATION).map { it.uri })
        assertEquals(listOf("u1", "u2"), items.sortedByOrder(LibrarySortOrder.SIZE).map { it.uri })
    }
}

