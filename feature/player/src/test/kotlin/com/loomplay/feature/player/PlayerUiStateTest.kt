package com.loomplay.feature.player

import com.loomplay.core.model.AspectRatioMode
import com.loomplay.core.model.SubtitleStyleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiStateTest {

    @Test
    fun `default player state is minimal with unlocked HUD and telemetry hidden`() {
        val state = PlayerUiState()
        assertTrue(state.isHudVisible)
        assertFalse(state.isLocked)
        assertFalse(state.showLandscapeStats)
        assertTrue(state.minimalInterface)
        assertTrue(state.pinchToZoomEnabled)
        assertTrue(state.swipeGesturesEnabled)
        assertTrue(state.horizontalSeekGestureEnabled)
        assertTrue(state.autoPlayNextEnabled)
        assertFalse(state.backgroundAudioEnabled)
        assertEquals(SubtitleStyleConfig(), state.subtitleStyle)
        assertNull(state.loopStartMs)
        assertNull(state.loopEndMs)
        assertNull(state.upNextCountdownSec)
        assertFalse(state.hasNext)
        assertFalse(state.hasPrevious)
        assertEquals(AspectRatioMode.FIT, state.aspectRatio)
        assertEquals(PlayerSheet.NONE, state.activeSheet)
        assertNull(state.gestureFeedback)
    }

    @Test
    fun `gesture feedback classes hold expected telemetry ranges`() {
        val brightness = GestureFeedback.Brightness(65)
        val volume = GestureFeedback.Volume(80)
        val scrub = GestureFeedback.SeekPreview(deltaMs = 14_000L, targetMs = 872_000L, durationMs = 1_450_000L)
        val boost = GestureFeedback.SpeedBoost(2.0f)

        assertEquals(65, brightness.percent)
        assertEquals(80, volume.percent)
        assertEquals(14_000L, scrub.deltaMs)
        assertEquals(2.0f, boost.multiplier, 0.001f)
    }
}

