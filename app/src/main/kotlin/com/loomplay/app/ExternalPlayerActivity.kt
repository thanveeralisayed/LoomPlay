package com.loomplay.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.loomplay.core.data.preferences.SettingsRepository
import com.loomplay.core.data.preferences.UserPreferences
import com.loomplay.core.player.api.LoomPlaybackSessionBridge
import com.loomplay.core.ui.theme.LoomPlayTheme
import com.loomplay.feature.player.PlayerScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ExternalPlayerActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var sessionBridge: LoomPlaybackSessionBridge

    private lateinit var pipController: PipActionController
    private var isInPipModeState by mutableStateOf(false)
    private var mediaTarget by mutableStateOf<ExternalPlayerParams?>(null)
    private var playbackTracker: ExternalPlaybackTracker? = null

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { info ->
        isInPipModeState = info.isInPictureInPictureMode
        pipController.refreshPipParams()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val initialTarget = ExternalPlayerContract.parseIntent(
            incoming = intent,
            contentResolver = contentResolver,
            hasCallingActivity = callingActivity != null,
        )
        if (initialTarget == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        mediaTarget = initialTarget
        val tracker = ExternalPlaybackTracker(
            mediaUri = initialTarget.uri,
            initialPositionMs = initialTarget.initialPositionMs,
        )
        playbackTracker = tracker
        setResult(Activity.RESULT_OK, tracker.buildResultIntent())

        pipController = PipActionController(this, sessionBridge).apply {
            isPlayerActive = true
        }
        isInPipModeState = isInPictureInPictureMode
        addOnPictureInPictureModeChangedListener(pipListener)
        pipController.register()
        pipController.refreshPipParams()

        lifecycleScope.launch {
            combine(
                sessionBridge.positionMs,
                sessionBridge.durationMs,
                sessionBridge.playbackState,
            ) { pos, dur, status ->
                Triple(pos, dur, status)
            }.collect { (pos, dur, status) ->
                tracker.update(pos, dur, status)
                setResult(Activity.RESULT_OK, tracker.buildResultIntent())
            }
        }

        setContent {
            val prefs by settingsRepository.userPreferences.collectAsStateWithLifecycle(
                initialValue = UserPreferences(),
            )
            val bgAudioEnabled by sessionBridge.isBackgroundAudioEnabled.collectAsStateWithLifecycle()

            LaunchedEffect(prefs.pipAutoEnter, bgAudioEnabled) {
                pipController.pipAutoEnterPref = prefs.pipAutoEnter
                pipController.backgroundAudioEnabled = bgAudioEnabled
                pipController.refreshPipParams()
            }

            val activeTarget = mediaTarget
            if (activeTarget != null) {
                LoomPlayTheme(
                    themeMode = prefs.themeMode,
                    amoledDarkMode = prefs.amoledDarkMode,
                    dynamicColor = prefs.dynamicColorEnabled,
                ) {
                    PlayerScreen(
                        mediaUri = activeTarget.uri,
                        title = activeTarget.title,
                        mimeType = activeTarget.mimeType,
                        headers = activeTarget.headers,
                        initialPositionMs = activeTarget.initialPositionMs,
                        forceStartFromBeginning = activeTarget.forceStartFromBeginning,
                        initialExternalSubtitles = activeTarget.subtitles,
                        hostActivityClassName = ExternalPlayerActivity::class.java.name,
                        isInPictureInPictureMode = isInPipModeState,
                        onEnterPip = { pipController.enterPipIfSupported() },
                        onPlaybackStateForPip = { playing, size ->
                            pipController.isPlaybackPlaying = playing
                            pipController.currentVideoSize = size
                            pipController.refreshPipParams()
                        },
                        onVideoBoundsChanged = { rect ->
                            pipController.pipVideoRect = rect
                            pipController.refreshPipParams()
                        },
                        onNavigateBack = {
                            pipController.suppressAutoPip()
                            syncLatestResult()
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val updated = ExternalPlayerContract.parseIntent(
            incoming = intent,
            contentResolver = contentResolver,
            hasCallingActivity = callingActivity != null,
        ) ?: return

        syncLatestResult()
        setIntent(intent)
        pipController.resetAutoPipSuppression()
        val tracker = ExternalPlaybackTracker(
            mediaUri = updated.uri,
            initialPositionMs = updated.initialPositionMs,
        )
        playbackTracker = tracker
        setResult(Activity.RESULT_OK, tracker.buildResultIntent())
        mediaTarget = updated
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pipController.onUserLeaveHint()
    }

    override fun onPause() {
        super.onPause()
        syncLatestResult()
    }

    override fun onStop() {
        super.onStop()
        syncLatestResult()
    }

    override fun finish() {
        syncLatestResult()
        super.finish()
    }

    override fun onDestroy() {
        if (::pipController.isInitialized) {
            pipController.unregister()
        }
        removeOnPictureInPictureModeChangedListener(pipListener)
        super.onDestroy()
    }

    private fun syncLatestResult() {
        val tracker = playbackTracker ?: return
        val pos = sessionBridge.currentPositionMs()
        val dur = sessionBridge.currentDurationMs()
        val status = sessionBridge.playbackState.value
        tracker.update(pos, dur, status)
        setResult(Activity.RESULT_OK, tracker.buildResultIntent())
    }
}
