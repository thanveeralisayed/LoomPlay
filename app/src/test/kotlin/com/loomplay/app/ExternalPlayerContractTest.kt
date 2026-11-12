package com.loomplay.app

import com.loomplay.core.model.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPlayerContractTest {

    @Test
    fun `ExternalPlaybackTracker initializes with caller provided start position`() {
        val tracker = ExternalPlaybackTracker(
            mediaUri = "https://example.com/video.mp4",
            initialPositionMs = 45_000L,
        )

        assertEquals(45_000L, tracker.latestPositionMs)
        assertEquals(0L, tracker.latestDurationMs)
        assertFalse(tracker.hasEnded)
        assertFalse(tracker.hadPlayableProgress)
    }

    @Test
    fun `ExternalPlaybackTracker updates position and duration on playback progress`() {
        val tracker = ExternalPlaybackTracker(
            mediaUri = "https://example.com/video.mp4",
            initialPositionMs = 0L,
        )

        tracker.update(positionMs = 12_000L, durationMs = 120_000L, status = PlaybackStatus.PLAYING)

        assertEquals(12_000L, tracker.latestPositionMs)
        assertEquals(120_000L, tracker.latestDurationMs)
        assertTrue(tracker.hadPlayableProgress)
        assertFalse(tracker.hasEnded)
    }

    @Test
    fun `ExternalPlaybackTracker marks completion and keeps duration on ENDED status`() {
        val tracker = ExternalPlaybackTracker(
            mediaUri = "https://example.com/video.mp4",
            initialPositionMs = 0L,
        )

        tracker.update(positionMs = 118_000L, durationMs = 120_000L, status = PlaybackStatus.PLAYING)
        tracker.update(positionMs = 120_000L, durationMs = 120_000L, status = PlaybackStatus.ENDED)

        assertEquals(120_000L, tracker.latestPositionMs)
        assertEquals(120_000L, tracker.latestDurationMs)
        assertTrue(tracker.hasEnded)

        // Does not wipe out position on IDLE or ERROR after ENDED
        tracker.update(positionMs = 0L, durationMs = 0L, status = PlaybackStatus.IDLE)
        assertEquals(120_000L, tracker.latestPositionMs)
        assertEquals(120_000L, tracker.latestDurationMs)
        assertTrue(tracker.hasEnded)
    }

    @Test
    fun `ExternalPlaybackTracker clears ended state if user seeks back and replays`() {
        val tracker = ExternalPlaybackTracker(
            mediaUri = "https://example.com/video.mp4",
            initialPositionMs = 0L,
        )

        tracker.update(positionMs = 120_000L, durationMs = 120_000L, status = PlaybackStatus.ENDED)
        assertTrue(tracker.hasEnded)

        // User scrubs back to 10s and plays
        tracker.update(positionMs = 10_000L, durationMs = 120_000L, status = PlaybackStatus.PLAYING)
        assertFalse(tracker.hasEnded)
        assertEquals(10_000L, tracker.latestPositionMs)
    }
}

