package com.loomplay.feature.player

import android.net.TestUri
import android.net.Uri
import android.widget.FrameLayout
import com.loomplay.core.model.AspectRatioMode
import com.loomplay.core.model.DecoderStats
import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.EngineType
import com.loomplay.core.model.MediaChapter
import com.loomplay.core.model.MediaTracks
import com.loomplay.core.model.PlaybackStatus
import com.loomplay.core.model.SubtitleStyleConfig
import com.loomplay.core.model.VideoSize
import com.loomplay.core.player.api.ActiveMediaMetadata
import com.loomplay.core.player.api.ILoomPlayer
import com.loomplay.core.player.api.LoomMediaSource
import com.loomplay.core.player.api.LoomPlayerError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerManagerTest {

    private class FakeLoomPlayer(
        override val engineType: EngineType,
    ) : ILoomPlayer {
        override val playbackState = MutableStateFlow(PlaybackStatus.IDLE)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(120_000L)
        override val bufferedPositionMs = MutableStateFlow(0L)
        override val videoSize = MutableStateFlow(VideoSize(1920, 1080))
        override val tracks = MutableStateFlow(MediaTracks())
        override val chapters = MutableStateFlow<List<MediaChapter>>(emptyList())
        override val playbackSpeed = MutableStateFlow(1.0f)
        override val stats = MutableStateFlow(DecoderStats(activeEngine = engineType))
        override val errors = MutableSharedFlow<LoomPlayerError>(extraBufferCapacity = 8)

        var prepareCount = 0
        var lastSource: LoomMediaSource? = null
        var lastStartPositionMs = 0L
        var lastAutoPlay = false
        var detachCount = 0
        var releaseCount = 0
        var lastSubtitleDelayMs: Long? = null
        var lastAudioDelayMs: Long? = null
        var lastAspectRatio: AspectRatioMode? = null
        var lastSubtitleStyle: SubtitleStyleConfig? = null
        var lastVolumeFraction: Float? = null

        override fun attachSurfaceView(container: FrameLayout) = Unit

        override fun detachSurfaceView() {
            detachCount++
        }

        override suspend fun prepare(
            source: LoomMediaSource,
            startPositionMs: Long,
            autoPlay: Boolean,
        ) {
            prepareCount++
            lastSource = source
            lastStartPositionMs = startPositionMs
            lastAutoPlay = autoPlay
            positionMs.value = startPositionMs
            playbackState.value = if (autoPlay) PlaybackStatus.PLAYING else PlaybackStatus.READY
        }

        override fun play() {
            playbackState.value = PlaybackStatus.PLAYING
        }

        override fun pause() {
            playbackState.value = PlaybackStatus.PAUSED
        }

        override fun seekTo(positionMs: Long) {
            this.positionMs.value = positionMs
        }

        override fun setPlaybackSpeed(speed: Float) {
            playbackSpeed.value = speed
        }

        override fun selectAudioTrack(trackId: String) = Unit

        override fun selectSubtitleTrack(trackId: String?) = Unit

        override fun addExternalSubtitle(uri: Uri, mimeType: String?, title: String?) = Unit

        override fun setSubtitleDelay(delayMs: Long) {
            lastSubtitleDelayMs = delayMs
        }

        override fun setAudioDelay(delayMs: Long) {
            lastAudioDelayMs = delayMs
        }

        override fun setAspectRatio(mode: AspectRatioMode) {
            lastAspectRatio = mode
        }

        override fun setSubtitleStyle(style: SubtitleStyleConfig) {
            lastSubtitleStyle = style
        }

        override fun setVolumeFraction(fraction: Float) {
            lastVolumeFraction = fraction
        }

        override fun release() {
            releaseCount++
            playbackState.value = PlaybackStatus.IDLE
        }
    }

    @Test
    fun `auto fallback from ExoPlayer to MPV transfers position and settings and releases Exo`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val exo = FakeLoomPlayer(EngineType.EXOPLAYER)
        val mpv = FakeLoomPlayer(EngineType.MPV)
        val manager = PlayerManager(exo, mpv) { _, _ -> }
        val events = mutableListOf<String>()
        val collectJob = managerScope.launch { manager.events.collect { events += it } }

        val source = LoomMediaSource(
            uri = TestUri("https://example.com/movie.mp4"),
            title = "Movie",
            preferredEngine = EnginePreference.AUTO,
        )

        manager.open(
            scope = managerScope,
            source = source,
            globalPreference = EnginePreference.AUTO,
            autoFallbackEnabled = true,
            startPositionMs = 5_000L,
            initialSpeed = 1.5f,
            subtitleDelayMs = 250L,
            audioDelayMs = -100L,
            aspectRatio = AspectRatioMode.RATIO_16_9,
        )
        advanceUntilIdle()

        assertEquals(EngineType.EXOPLAYER, manager.activeEngine.value)
        assertEquals(1, exo.prepareCount)

        exo.seekTo(42_000L)
        exo.errors.tryEmit(LoomPlayerError.UnsupportedFormat("UnrecognizedInputFormatException"))
        advanceUntilIdle()

        assertEquals(EngineType.MPV, manager.activeEngine.value)
        assertEquals(1, exo.detachCount)
        assertEquals(1, exo.releaseCount)
        assertEquals(1, mpv.prepareCount)
        assertEquals(42_000L, mpv.lastStartPositionMs)
        assertEquals(true, mpv.lastAutoPlay)
        assertEquals(1.5f, mpv.playbackSpeed.value)
        assertEquals(250L, mpv.lastSubtitleDelayMs)
        assertEquals(-100L, mpv.lastAudioDelayMs)
        assertEquals(AspectRatioMode.RATIO_16_9, mpv.lastAspectRatio)
        assertTrue(events.any { it.contains("Format fallback") })

        collectJob.cancel()
        manager.release()
        managerScope.cancel()
    }

    @Test
    fun `does not fallback to MPV on NetworkOrIo error`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val exo = FakeLoomPlayer(EngineType.EXOPLAYER)
        val mpv = FakeLoomPlayer(EngineType.MPV)
        val manager = PlayerManager(exo, mpv) { _, _ -> }
        val events = mutableListOf<String>()
        val collectJob = managerScope.launch { manager.events.collect { events += it } }

        val source = LoomMediaSource(
            uri = TestUri("https://example.com/stream.mp4"),
            title = "Stream",
        )

        manager.open(
            scope = managerScope,
            source = source,
            globalPreference = EnginePreference.AUTO,
            autoFallbackEnabled = true,
        )
        advanceUntilIdle()

        exo.errors.tryEmit(LoomPlayerError.NetworkOrIo("HTTP 404"))
        advanceUntilIdle()

        assertEquals(EngineType.EXOPLAYER, manager.activeEngine.value)
        assertEquals(0, mpv.prepareCount)
        assertTrue(events.contains("HTTP 404"))

        collectJob.cancel()
        manager.release()
        managerScope.cancel()
    }

    @Test
    fun `does not fallback when autoFallbackEnabled is false or MPV already attempted`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val exo = FakeLoomPlayer(EngineType.EXOPLAYER)
        val mpv = FakeLoomPlayer(EngineType.MPV)
        val manager = PlayerManager(exo, mpv) { _, _ -> }
        val events = mutableListOf<String>()
        val collectJob = managerScope.launch { manager.events.collect { events += it } }

        val source = LoomMediaSource(
            uri = TestUri("https://example.com/movie.mp4"),
            title = "Movie",
        )

        manager.open(
            scope = managerScope,
            source = source,
            globalPreference = EnginePreference.AUTO,
            autoFallbackEnabled = false,
        )
        advanceUntilIdle()

        exo.errors.tryEmit(LoomPlayerError.UnsupportedFormat("Unsupported codec"))
        advanceUntilIdle()

        assertEquals(EngineType.EXOPLAYER, manager.activeEngine.value)
        assertEquals(0, mpv.prepareCount)
        assertTrue(events.contains("Unsupported codec"))

        // Enable fallback and force fallback to MPV, then ensure MPV error does not loop back
        manager.open(
            scope = managerScope,
            source = source,
            globalPreference = EnginePreference.AUTO,
            autoFallbackEnabled = true,
        )
        advanceUntilIdle()
        exo.errors.tryEmit(LoomPlayerError.UnsupportedFormat("Fallback trigger"))
        advanceUntilIdle()
        assertEquals(EngineType.MPV, manager.activeEngine.value)

        mpv.errors.tryEmit(LoomPlayerError.UnsupportedFormat("MPV decode fail"))
        advanceUntilIdle()
        assertEquals(EngineType.MPV, manager.activeEngine.value)
        assertTrue(events.contains("MPV decode fail"))

        collectJob.cancel()
        manager.release()
        managerScope.cancel()
    }

    @Test
    fun `manual switchEngine releases previous engine and transfers full playback state`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val exo = FakeLoomPlayer(EngineType.EXOPLAYER)
        val mpv = FakeLoomPlayer(EngineType.MPV)
        val manager = PlayerManager(exo, mpv) { _, _ -> }

        val source = LoomMediaSource(
            uri = TestUri("file:///storage/emulated/0/Movies/clip.mp4"),
            title = "Clip",
        )

        manager.open(
            scope = managerScope,
            source = source,
            globalPreference = EnginePreference.AUTO,
        )
        advanceUntilIdle()

        exo.seekTo(18_500L)
        manager.setPlaybackSpeed(1.25f)
        manager.setSubtitleDelay(-300L)
        manager.setAudioDelay(150L)
        manager.setAspectRatio(AspectRatioMode.FILL)

        manager.switchEngine(managerScope, EngineType.MPV)
        advanceUntilIdle()

        assertEquals(EngineType.MPV, manager.activeEngine.value)
        assertEquals(1, exo.detachCount)
        assertEquals(1, exo.releaseCount)
        assertEquals(1, mpv.prepareCount)
        assertEquals(18_500L, mpv.lastStartPositionMs)
        assertEquals(true, mpv.lastAutoPlay)
        assertEquals(1.25f, mpv.playbackSpeed.value)
        assertEquals(-300L, mpv.lastSubtitleDelayMs)
        assertEquals(150L, mpv.lastAudioDelayMs)
        assertEquals(AspectRatioMode.FILL, mpv.lastAspectRatio)

        manager.release()
        managerScope.cancel()
    }

    @Test
    fun `open switching engines releases previously active engine`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val exo = FakeLoomPlayer(EngineType.EXOPLAYER)
        val mpv = FakeLoomPlayer(EngineType.MPV)
        val manager = PlayerManager(exo, mpv) { _, _ -> }

        manager.open(
            scope = managerScope,
            source = LoomMediaSource(uri = TestUri("file:///Movies/one.mp4"), title = "One"),
            globalPreference = EnginePreference.AUTO,
        )
        advanceUntilIdle()
        assertEquals(EngineType.EXOPLAYER, manager.activeEngine.value)

        manager.open(
            scope = managerScope,
            source = LoomMediaSource(uri = TestUri("file:///Movies/two.mkv"), title = "Two"),
            globalPreference = EnginePreference.AUTO,
        )
        advanceUntilIdle()

        assertEquals(EngineType.MPV, manager.activeEngine.value)
        assertEquals(1, exo.detachCount)
        assertEquals(1, exo.releaseCount)
        assertEquals(1, mpv.prepareCount)

        manager.release()
        managerScope.cancel()
    }

    @Test
    fun `subtitle style and audio focus duck state persist across engine switch and fallback`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val exo = FakeLoomPlayer(EngineType.EXOPLAYER)
        val mpv = FakeLoomPlayer(EngineType.MPV)
        val manager = PlayerManager(exo, mpv) { _, _ -> }

        val customStyle = SubtitleStyleConfig(
            fontSizeSp = 26f,
            outlineEnabled = false,
            backgroundOpacity = 0.75f,
            mpvAssOverride = true,
        )
        manager.setSubtitleStyle(customStyle)
        assertEquals(customStyle, exo.lastSubtitleStyle)
        assertEquals(customStyle, mpv.lastSubtitleStyle)

        manager.open(
            scope = managerScope,
            source = LoomMediaSource(uri = TestUri("https://example.com/movie.mp4"), title = "Movie"),
            globalPreference = EnginePreference.AUTO,
            autoFallbackEnabled = true,
        )
        advanceUntilIdle()

        manager.setAudioFocusDucked(true)
        assertEquals(0.25f, exo.lastVolumeFraction!!, 0.001f)

        exo.errors.tryEmit(LoomPlayerError.UnsupportedFormat("Fallback to MPV"))
        advanceUntilIdle()

        assertEquals(EngineType.MPV, manager.activeEngine.value)
        assertEquals(customStyle, mpv.lastSubtitleStyle)
        assertEquals(0.25f, mpv.lastVolumeFraction!!, 0.001f)

        manager.setAudioFocusDucked(false)
        assertEquals(1.0f, mpv.lastVolumeFraction!!, 0.001f)

        manager.release()
        managerScope.cancel()
    }

    @Test
    fun `session bridge forwards metadata, queue callbacks, silent sidecar add, and stopSession`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val exo = FakeLoomPlayer(EngineType.EXOPLAYER)
        val mpv = FakeLoomPlayer(EngineType.MPV)
        val manager = PlayerManager(exo, mpv) { _, _ -> }
        val events = mutableListOf<String>()
        val collectJob = managerScope.launch { manager.events.collect { events += it } }

        var nextCalled = 0
        var prevCalled = 0
        var stopCalled = 0
        manager.setQueueNavigationCallbacks(
            onNext = { nextCalled++ },
            onPrev = { prevCalled++ },
            onStop = { stopCalled++ },
        )
        manager.updateMediaMetadata(
            ActiveMediaMetadata(
                uri = "file:///Movies/ep1.mp4",
                title = "Episode 1",
                hasNext = true,
                hasPrevious = false,
            ),
        )
        manager.open(
            scope = managerScope,
            source = LoomMediaSource(uri = TestUri("file:///Movies/ep1.mp4"), title = "Episode 1"),
            globalPreference = EnginePreference.AUTO,
        )
        advanceUntilIdle()

        assertEquals(EngineType.EXOPLAYER, manager.mediaMetadata.value.engineType)
        assertEquals("Episode 1", manager.mediaMetadata.value.title)

        manager.skipToNext()
        manager.skipToPrevious()
        assertEquals(1, nextCalled)
        assertEquals(1, prevCalled)

        manager.addExternalSubtitle(TestUri("file:///Movies/ep1.srt"), "application/x-subrip", "ep1.srt", announce = false)
        advanceUntilIdle()
        assertTrue(events.none { it.contains("Loaded subtitle") })

        manager.addExternalSubtitle(TestUri("file:///Movies/manual.srt"), "application/x-subrip", "manual.srt", announce = true)
        advanceUntilIdle()
        assertTrue(events.any { it == "Loaded subtitle: manual.srt" })

        manager.stopSession()
        assertEquals(1, stopCalled)
        assertEquals("", manager.mediaMetadata.value.uri)
        assertEquals(PlaybackStatus.IDLE, manager.playbackState.value)

        collectJob.cancel()
        managerScope.cancel()
    }

    @Test
    fun `opening next video resets playback speed to target engine value even at 1x`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val exo = FakeLoomPlayer(EngineType.EXOPLAYER)
        val mpv = FakeLoomPlayer(EngineType.MPV)
        val manager = PlayerManager(exo, mpv) { _, _ -> }

        manager.open(
            scope = managerScope,
            source = LoomMediaSource(uri = TestUri("file:///Movies/fast.mp4"), title = "Fast"),
            globalPreference = EnginePreference.AUTO,
            initialSpeed = 2.0f,
        )
        advanceUntilIdle()
        assertEquals(2.0f, exo.playbackSpeed.value, 0.001f)

        manager.open(
            scope = managerScope,
            source = LoomMediaSource(uri = TestUri("file:///Movies/normal.mp4"), title = "Normal"),
            globalPreference = EnginePreference.AUTO,
            initialSpeed = 1.0f,
        )
        advanceUntilIdle()
        assertEquals(1.0f, exo.playbackSpeed.value, 0.001f)

        manager.release()
        managerScope.cancel()
    }

    @Test
    fun `stale owner token cannot release newer active playback session`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val exo = FakeLoomPlayer(EngineType.EXOPLAYER)
        val mpv = FakeLoomPlayer(EngineType.MPV)
        val manager = PlayerManager(exo, mpv) { _, _ -> }

        val token1 = Any()
        val token2 = Any()

        manager.open(
            scope = managerScope,
            source = LoomMediaSource(uri = TestUri("file:///Movies/one.mp4"), title = "One"),
            globalPreference = EnginePreference.AUTO,
            ownerToken = token1,
        )
        advanceUntilIdle()
        assertTrue(manager.isActiveOwner(token1))

        manager.open(
            scope = managerScope,
            source = LoomMediaSource(uri = TestUri("file:///Movies/two.mp4"), title = "Two"),
            globalPreference = EnginePreference.AUTO,
            ownerToken = token2,
        )
        advanceUntilIdle()
        assertTrue(!manager.isActiveOwner(token1))
        assertTrue(manager.isActiveOwner(token2))

        val releasedByStale = manager.releaseIfOwner(token1)
        assertTrue(!releasedByStale)
        assertEquals(0, exo.releaseCount)

        val releasedByActive = manager.releaseIfOwner(token2)
        assertTrue(releasedByActive)
        assertEquals(1, exo.releaseCount)

        managerScope.cancel()
    }

    @Test
    fun `background audio and active host activity class sync and reset on release`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val exo = FakeLoomPlayer(EngineType.EXOPLAYER)
        val mpv = FakeLoomPlayer(EngineType.MPV)
        val manager = PlayerManager(exo, mpv) { _, _ -> }

        manager.setBackgroundAudioEnabled(true)
        manager.setActiveHostActivityClassName("com.loomplay.app.ExternalPlayerActivity")

        assertEquals(true, manager.isBackgroundAudioEnabled.value)
        assertEquals("com.loomplay.app.ExternalPlayerActivity", manager.activeHostActivityClassName.value)

        manager.release()

        assertEquals(false, manager.isBackgroundAudioEnabled.value)
        assertEquals(null, manager.activeHostActivityClassName.value)
        managerScope.cancel()
    }

    @Test
    fun `release resets playbackState to IDLE before clearing mediaMetadata across active states`() = runTest {
        val activeStatuses = listOf(
            PlaybackStatus.PLAYING,
            PlaybackStatus.PAUSED,
            PlaybackStatus.BUFFERING,
            PlaybackStatus.READY,
        )

        activeStatuses.forEach { activeStatus ->
            val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
            val exo = FakeLoomPlayer(EngineType.EXOPLAYER)
            val mpv = FakeLoomPlayer(EngineType.MPV)
            val manager = PlayerManager(exo, mpv) { _, _ -> }
            val observedPairs = mutableListOf<Pair<PlaybackStatus, String>>()

            val statusJob = managerScope.launch(Dispatchers.Unconfined) {
                manager.playbackState.collect { status ->
                    observedPairs += status to manager.mediaMetadata.value.uri
                }
            }
            val metaJob = managerScope.launch(Dispatchers.Unconfined) {
                manager.mediaMetadata.collect { meta ->
                    observedPairs += manager.playbackState.value to meta.uri
                }
            }

            manager.open(
                scope = managerScope,
                source = LoomMediaSource(uri = TestUri("file:///Movies/sample.mp4"), title = "Sample"),
                globalPreference = EnginePreference.AUTO,
            )
            advanceUntilIdle()
            exo.playbackState.value = activeStatus
            advanceUntilIdle()
            observedPairs.clear()

            manager.release()
            advanceUntilIdle()

            val illegal = observedPairs.filter { (status, uri) ->
                status != PlaybackStatus.IDLE && status != PlaybackStatus.ENDED && uri.isBlank()
            }
            assertTrue("Observed non-IDLE status with blank URI during release from $activeStatus: $observedPairs", illegal.isEmpty())
            val idleWithMediaIndex = observedPairs.indexOfFirst { (status, uri) ->
                status == PlaybackStatus.IDLE && uri.isNotBlank()
            }
            val idleBlankIndex = observedPairs.indexOfLast { (status, uri) ->
                status == PlaybackStatus.IDLE && uri.isBlank()
            }
            assertTrue("Expected IDLE with metadata before blank metadata for $activeStatus: $observedPairs", idleWithMediaIndex in 0 until idleBlankIndex)

            statusJob.cancel()
            metaJob.cancel()
            managerScope.cancel()
        }
    }

    @Test
    fun `open seeds non-blank metadata before playbackStatus becomes non-IDLE`() = runTest {
        val managerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val exo = FakeLoomPlayer(EngineType.EXOPLAYER)
        val mpv = FakeLoomPlayer(EngineType.MPV)
        val manager = PlayerManager(exo, mpv) { _, _ -> }
        val observedPairs = mutableListOf<Pair<PlaybackStatus, String>>()

        val statusJob = managerScope.launch(Dispatchers.Unconfined) {
            manager.playbackState.collect { status ->
                observedPairs += status to manager.mediaMetadata.value.uri
            }
        }
        val metaJob = managerScope.launch(Dispatchers.Unconfined) {
            manager.mediaMetadata.collect { meta ->
                observedPairs += manager.playbackState.value to meta.uri
            }
        }

        manager.open(
            scope = managerScope,
            source = LoomMediaSource(uri = TestUri("file:///Movies/first.mp4"), title = "First"),
            globalPreference = EnginePreference.AUTO,
        )
        advanceUntilIdle()

        // Switch engines cleanly while previous engine was actively playing
        manager.open(
            scope = managerScope,
            source = LoomMediaSource(uri = TestUri("file:///Movies/second.mkv"), title = "Second"),
            globalPreference = EnginePreference.AUTO,
        )
        advanceUntilIdle()

        val illegal = observedPairs.filter { (status, uri) ->
            status != PlaybackStatus.IDLE && status != PlaybackStatus.ENDED && uri.isBlank()
        }
        assertTrue("Observed non-IDLE status with blank URI during open/switch: $observedPairs", illegal.isEmpty())

        statusJob.cancel()
        metaJob.cancel()
        manager.release()
        managerScope.cancel()
    }
}
