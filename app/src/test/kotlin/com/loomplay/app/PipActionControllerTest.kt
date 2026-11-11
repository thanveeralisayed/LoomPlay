package com.loomplay.app

import android.app.Activity
import com.loomplay.core.model.EngineType
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.model.VideoSize
import com.loomplay.core.player.api.ActiveMediaMetadata
import com.loomplay.core.player.api.LoomPlaybackSessionBridge
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipActionControllerTest {

    private class TestActivity : Activity()

    private class FakeSessionBridge : LoomPlaybackSessionBridge {
        override val activeEngine = MutableStateFlow(EngineType.EXOPLAYER)
        override val playbackState = MutableStateFlow(PlaybackStatus.IDLE)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val bufferedPositionMs = MutableStateFlow(0L)
        override val playbackSpeed = MutableStateFlow(1.0f)
        override val videoSize = MutableStateFlow(VideoSize())
        override val mediaMetadata = MutableStateFlow(ActiveMediaMetadata())
        override val isBackgroundAudioEnabled = MutableStateFlow(false)
        override val activeHostActivityClassName = MutableStateFlow<String?>(null)

        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun skipToNext() = Unit
        override fun skipToPrevious() = Unit
        override fun stopSession() = Unit
    }

    @Test
    fun `resetAutoPipSuppression re-enables auto PiP for next playback session`() {
        val controller = PipActionController(TestActivity(), FakeSessionBridge()).apply {
            isPlayerActive = true
            isPlaybackPlaying = true
            pipAutoEnterPref = true
            backgroundAudioEnabled = false
        }

        assertFalse(controller.autoPipSuppressed)
        assertTrue(controller.shouldAutoEnterPip())

        controller.suppressAutoPip()
        assertTrue(controller.autoPipSuppressed)
        assertFalse(controller.shouldAutoEnterPip())

        controller.resetAutoPipSuppression()
        assertFalse(controller.autoPipSuppressed)
        assertTrue(controller.shouldAutoEnterPip())
    }

    @Test
    fun `shouldAutoEnterPip respects background audio and preference flags`() {
        val controller = PipActionController(TestActivity(), FakeSessionBridge()).apply {
            isPlayerActive = true
            isPlaybackPlaying = true
            pipAutoEnterPref = true
            backgroundAudioEnabled = true
        }

        assertFalse(controller.shouldAutoEnterPip())

        controller.backgroundAudioEnabled = false
        controller.pipAutoEnterPref = false
        assertFalse(controller.shouldAutoEnterPip())

        controller.pipAutoEnterPref = true
        controller.isPlaybackPlaying = false
        assertFalse(controller.shouldAutoEnterPip())

        controller.isPlaybackPlaying = true
        controller.isPlayerActive = false
        assertFalse(controller.shouldAutoEnterPip())
    }
}

