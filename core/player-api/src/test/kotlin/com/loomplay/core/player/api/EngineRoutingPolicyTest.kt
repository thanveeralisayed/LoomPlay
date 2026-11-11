package com.loomplay.core.player.api

import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRoutingPolicyTest {

    @Test
    fun `routes adaptive manifests to ExoPlayer in auto mode`() {
        assertEquals(
            EngineType.EXOPLAYER,
            EngineRoutingPolicy.selectInitialEngine(
                uriString = "https://cdn.example.com/live/stream.m3u8?token=abc",
                sourcePreference = EnginePreference.AUTO,
                globalPreference = EnginePreference.AUTO,
            ),
        )
        assertEquals(
            EngineType.EXOPLAYER,
            EngineRoutingPolicy.selectInitialEngine(
                uriString = "https://cdn.example.com/manifest.mpd#t=10",
                sourcePreference = EnginePreference.AUTO,
                globalPreference = EnginePreference.AUTO,
            ),
        )
    }

    @Test
    fun `routes mkv and avi files to MPV in auto mode`() {
        assertEquals(
            EngineType.MPV,
            EngineRoutingPolicy.selectInitialEngine(
                uriString = "file:///storage/emulated/0/Movies/sample.mkv",
                sourcePreference = EnginePreference.AUTO,
                globalPreference = EnginePreference.AUTO,
            ),
        )
        assertEquals(
            EngineType.MPV,
            EngineRoutingPolicy.selectInitialEngine(
                uriString = "file:///storage/emulated/0/Movies/archive.AVI",
                sourcePreference = EnginePreference.AUTO,
                globalPreference = EnginePreference.AUTO,
            ),
        )
    }

    @Test
    fun `per-source override takes precedence over global engine preference`() {
        assertEquals(
            EngineType.MPV,
            EngineRoutingPolicy.selectInitialEngine(
                uriString = "https://cdn.example.com/video.mp4",
                sourcePreference = EnginePreference.FORCE_MPV,
                globalPreference = EnginePreference.FORCE_EXOPLAYER,
            ),
        )
        assertEquals(
            EngineType.EXOPLAYER,
            EngineRoutingPolicy.selectInitialEngine(
                uriString = "file:///Movies/anime.mkv",
                sourcePreference = EnginePreference.FORCE_EXOPLAYER,
                globalPreference = EnginePreference.FORCE_MPV,
            ),
        )
    }

    @Test
    fun `allows fallback to MPV only on unsupported format from ExoPlayer`() {
        val error = LoomPlayerError.UnsupportedFormat("UnrecognizedInputFormatException")
        assertTrue(EngineRoutingPolicy.canFallback(EngineType.EXOPLAYER, error, setOf(EngineType.EXOPLAYER)))
        assertFalse(EngineRoutingPolicy.canFallback(EngineType.EXOPLAYER, error, setOf(EngineType.EXOPLAYER, EngineType.MPV)))
        assertFalse(EngineRoutingPolicy.canFallback(EngineType.MPV, error, setOf(EngineType.MPV)))
        assertFalse(
            EngineRoutingPolicy.canFallback(
                EngineType.EXOPLAYER,
                LoomPlayerError.NetworkOrIo("404 Not Found"),
                setOf(EngineType.EXOPLAYER),
            ),
        )
    }

    @Test
    fun `routes rtsp and rtmp streams and extensionless mkv mime uris to MPV in auto mode`() {
        assertEquals(
            EngineType.MPV,
            EngineRoutingPolicy.selectInitialEngine(
                uriString = "rtsp://camera.local:554/stream1",
                sourcePreference = EnginePreference.AUTO,
                globalPreference = EnginePreference.AUTO,
            ),
        )
        assertEquals(
            EngineType.MPV,
            EngineRoutingPolicy.selectInitialEngine(
                uriString = "rtmp://live.example.com/app/key",
                sourcePreference = EnginePreference.AUTO,
                globalPreference = EnginePreference.AUTO,
            ),
        )
        assertEquals(
            EngineType.MPV,
            EngineRoutingPolicy.selectInitialEngine(
                uriString = "content://media/external/video/media/42",
                mimeType = "video/x-matroska",
                sourcePreference = EnginePreference.AUTO,
                globalPreference = EnginePreference.AUTO,
            ),
        )
        assertEquals(
            EngineType.EXOPLAYER,
            EngineRoutingPolicy.selectInitialEngine(
                uriString = "content://media/external/video/media/43",
                mimeType = "application/vnd.apple.mpegurl",
                sourcePreference = EnginePreference.AUTO,
                globalPreference = EnginePreference.AUTO,
            ),
        )
    }
}
