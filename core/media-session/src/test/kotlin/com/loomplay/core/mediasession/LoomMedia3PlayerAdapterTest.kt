package com.loomplay.core.mediasession

import androidx.media3.common.C
import androidx.media3.common.Player
import com.loomplay.core.model.EngineType
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.model.VideoSize
import com.loomplay.core.player.api.ActiveMediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoomMedia3PlayerAdapterTest {

    @Test
    fun `blank metadata forces STATE_IDLE and empty playlist across every PlaybackStatus`() {
        PlaybackStatus.entries.forEach { status ->
            val state = buildLoomMedia3State(
                status = status,
                meta = ActiveMediaMetadata(uri = "", title = ""),
                durationMs = 120_000L,
                positionMs = 45_000L,
                bufferedPositionMs = 60_000L,
                playbackSpeed = 1.5f,
                videoSize = VideoSize(1920, 1080),
                playWhenReadyRequested = true,
            )

            assertEquals("Expected STATE_IDLE for $status with blank URI", Player.STATE_IDLE, state.playbackState)
            assertFalse("Expected playWhenReady=false for $status with blank URI", state.playWhenReady)
            assertTrue("Expected empty playlist for $status with blank URI", state.playlist.isEmpty())
            assertEquals("Expected INDEX_UNSET for $status with blank URI", C.INDEX_UNSET, state.currentMediaItemIndex)
        }
    }

    @Test
    fun `non-blank metadata builds valid playlist and playback states`() {
        val meta = ActiveMediaMetadata(
            uri = "content://media/external/video/media/42",
            title = "Sample Video",
            durationMs = 90_000L,
            hasPrevious = true,
            hasNext = true,
            engineType = EngineType.MPV,
        )

        val playingState = buildLoomMedia3State(
            status = PlaybackStatus.PLAYING,
            meta = meta,
            durationMs = 90_000L,
            positionMs = 15_000L,
            bufferedPositionMs = 30_000L,
            playbackSpeed = 1.25f,
            videoSize = VideoSize(1920, 1080),
            playWhenReadyRequested = true,
        )

        assertEquals(Player.STATE_READY, playingState.playbackState)
        assertTrue(playingState.playWhenReady)
        assertEquals(3, playingState.playlist.size)
        assertEquals(1, playingState.currentMediaItemIndex)
        assertEquals("__prev__", playingState.playlist[0].mediaItem.mediaId)
        assertEquals(meta.uri, playingState.playlist[1].mediaItem.mediaId)
        assertEquals("__next__", playingState.playlist[2].mediaItem.mediaId)

        val bufferingState = buildLoomMedia3State(
            status = PlaybackStatus.BUFFERING,
            meta = meta.copy(hasPrevious = false, hasNext = false),
            durationMs = 0L,
            positionMs = 0L,
            bufferedPositionMs = 0L,
            playbackSpeed = 1.0f,
            videoSize = VideoSize(),
            playWhenReadyRequested = true,
        )

        assertEquals(Player.STATE_BUFFERING, bufferingState.playbackState)
        assertTrue(bufferingState.playWhenReady)
        assertEquals(1, bufferingState.playlist.size)
        assertEquals(0, bufferingState.currentMediaItemIndex)
        assertEquals(meta.uri, bufferingState.playlist[0].mediaItem.mediaId)
    }
}

