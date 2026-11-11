package com.loomplay.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import com.loomplay.core.data.preferences.SettingsRepository
import com.loomplay.core.data.preferences.UserPreferences
import com.loomplay.core.player.api.LoomPlaybackSessionBridge
import com.loomplay.core.ui.theme.LoomPlayTheme
import com.loomplay.feature.player.PlayerScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private data class ExternalMediaTarget(
    val uri: String,
    val title: String,
    val mimeType: String?,
)

@AndroidEntryPoint
class ExternalPlayerActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var sessionBridge: LoomPlaybackSessionBridge

    private lateinit var pipController: PipActionController
    private var isInPipModeState by mutableStateOf(false)
    private var mediaTarget by mutableStateOf<ExternalMediaTarget?>(null)

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

        val initialTarget = parseIntent(intent)
        if (initialTarget == null) {
            finish()
            return
        }
        mediaTarget = initialTarget

        pipController = PipActionController(this, sessionBridge).apply {
            isPlayerActive = true
        }
        isInPipModeState = isInPictureInPictureMode
        addOnPictureInPictureModeChangedListener(pipListener)
        pipController.register()
        pipController.refreshPipParams()

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
                            if (isTaskRoot) {
                                val mainIntent = Intent(this@ExternalPlayerActivity, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                                startActivity(mainIntent)
                            }
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseIntent(intent)?.let { updated ->
            setIntent(intent)
            pipController.resetAutoPipSuppression()
            mediaTarget = updated
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pipController.onUserLeaveHint()
    }

    override fun onDestroy() {
        if (::pipController.isInitialized) {
            pipController.unregister()
        }
        removeOnPictureInPictureModeChangedListener(pipListener)
        super.onDestroy()
    }

    private fun parseIntent(incoming: Intent?): ExternalMediaTarget? {
        val uri = incoming?.data ?: return null
        val uriString = uri.toString().takeIf { it.isNotBlank() } ?: return null
        val resolvedMime = incoming.type?.takeIf { it.isNotBlank() && it != "*/*" }
            ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        val resolvedTitle = resolveIntentTitle(incoming, uri)
        return ExternalMediaTarget(
            uri = uriString,
            title = resolvedTitle,
            mimeType = resolvedMime,
        )
    }

    private fun resolveIntentTitle(incoming: Intent, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && cursor.moveToFirst()) {
                            cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                        }
                    }
            }
        }
        incoming.getStringExtra(Intent.EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let { return it }
        return uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let(Uri::decode) ?: "Video"
    }
}

