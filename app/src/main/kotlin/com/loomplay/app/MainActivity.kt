package com.loomplay.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.loomplay.core.data.preferences.SettingsRepository
import com.loomplay.core.data.preferences.UserPreferences
import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.ThemeMode
import com.loomplay.core.player.api.LoomPlaybackSessionBridge
import com.loomplay.core.ui.theme.LoomPlayTheme
import com.loomplay.feature.library.LibraryScreen
import com.loomplay.feature.library.PlaylistsScreen
import com.loomplay.feature.player.PlayerScreen
import com.loomplay.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

@Serializable
private data object LibraryRoute

@Serializable
private data object PlaylistsRoute

@Serializable
private data object SettingsRoute

@Serializable
private data class PlayerRoute(
    val mediaUri: String,
    val title: String,
    val mimeType: String? = null,
    val engineOverride: String? = null,
    val headersJson: String? = null,
    val playlistId: Long? = null,
)

private data class BottomNavTab(
    val label: String,
    val icon: ImageVector,
    val route: Any,
    val matches: (androidx.navigation.NavDestination?) -> Boolean,
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var sessionBridge: LoomPlaybackSessionBridge

    private lateinit var pipController: PipActionController
    private var isInPipModeState by mutableStateOf(false)

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { info ->
        isInPipModeState = info.isInPictureInPictureMode
        pipController.refreshPipParams()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null && forwardExternalIntentIfPresent(intent)) {
            finish()
            return
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        pipController = PipActionController(this, sessionBridge)
        isInPipModeState = isInPictureInPictureMode
        addOnPictureInPictureModeChangedListener(pipListener)
        pipController.register()

        val topLevelTabs = listOf(
            BottomNavTab(
                label = "Library",
                icon = Icons.Default.VideoLibrary,
                route = LibraryRoute,
                matches = { it?.hasRoute<LibraryRoute>() == true },
            ),
            BottomNavTab(
                label = "Playlists",
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                route = PlaylistsRoute,
                matches = { it?.hasRoute<PlaylistsRoute>() == true },
            ),
        )

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

            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            val isOnPlayer = currentDestination?.hasRoute<PlayerRoute>() == true
            val showBottomBar = (
                currentDestination?.hasRoute<LibraryRoute>() == true ||
                    currentDestination?.hasRoute<PlaylistsRoute>() == true
                ) && !isInPipModeState

            val systemDark = isSystemInDarkTheme()
            val isAppDark = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            LaunchedEffect(isAppDark, isOnPlayer) {
                val useLightBars = !isAppDark && !isOnPlayer
                enableEdgeToEdge(
                    statusBarStyle = if (useLightBars) {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (useLightBars) {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    },
                )
            }

            LaunchedEffect(isOnPlayer) {
                pipController.isPlayerActive = isOnPlayer
                if (isOnPlayer) {
                    pipController.resetAutoPipSuppression()
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    pipController.pipVideoRect = null
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    WindowCompat.getInsetsController(window, window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                    val lp = window.attributes
                    if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = lp
                    }
                }
                pipController.refreshPipParams()
            }

            val launchPlayer: (
                uri: String,
                title: String,
                mimeType: String?,
                engineOverride: EnginePreference?,
                headers: Map<String, String>,
                playlistId: Long?,
            ) -> Unit = { uri, title, mimeType, engineOverride, headers, playlistId ->
                if (navController.currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() != true) {
                    pipController.resetAutoPipSuppression()
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    val encodedHeaders = if (headers.isNotEmpty()) {
                        runCatching { Json.encodeToString(headers) }.getOrNull()
                    } else {
                        null
                    }
                    navController.navigate(
                        PlayerRoute(
                            mediaUri = uri,
                            title = title,
                            mimeType = mimeType,
                            engineOverride = engineOverride?.name,
                            headersJson = encodedHeaders,
                            playlistId = playlistId,
                        ),
                    ) {
                        launchSingleTop = true
                    }
                }
            }

            val navigateTopTab = { targetRoute: Any ->
                navController.navigate(targetRoute) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }

            LoomPlayTheme(
                themeMode = prefs.themeMode,
                amoledDarkMode = prefs.amoledDarkMode,
                dynamicColor = prefs.dynamicColorEnabled,
            ) {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ) {
                                topLevelTabs.forEach { tab ->
                                    val selected = tab.matches(currentDestination)
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { navigateTopTab(tab.route) },
                                        icon = {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = tab.label,
                                            )
                                        },
                                        label = { Text(tab.label) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                ) { outerPadding ->
                    val contentModifier = if (showBottomBar) {
                        Modifier
                            .padding(bottom = outerPadding.calculateBottomPadding())
                            .consumeWindowInsets(outerPadding)
                    } else {
                        Modifier
                    }

                    NavHost(
                        navController = navController,
                        startDestination = LibraryRoute,
                        modifier = contentModifier,
                    ) {
                        composable<LibraryRoute> {
                            LibraryScreen(
                                onPlayMedia = { uri, title, mimeType, engineOverride, headers ->
                                    launchPlayer(uri, title, mimeType, engineOverride, headers, null)
                                },
                                onOpenSettings = {
                                    navController.navigate(SettingsRoute) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                        composable<PlaylistsRoute> {
                            PlaylistsScreen(
                                onPlayFromPlaylist = { playlistId, uri, title, mimeType ->
                                    launchPlayer(uri, title, mimeType, null, emptyMap(), playlistId)
                                },
                                onOpenSettings = {
                                    navController.navigate(SettingsRoute) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                        composable<SettingsRoute> {
                            SettingsScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                            )
                        }
                        composable<PlayerRoute> { backStackEntry ->
                            val route = backStackEntry.toRoute<PlayerRoute>()
                            val overrideEnum = route.engineOverride?.let {
                                runCatching { EnginePreference.valueOf(it) }.getOrNull()
                            }
                            val decodedHeaders = route.headersJson?.let { raw ->
                                runCatching { Json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
                            } ?: emptyMap()
                            PlayerScreen(
                                mediaUri = route.mediaUri,
                                title = route.title,
                                mimeType = route.mimeType,
                                engineOverride = overrideEnum,
                                headers = decodedHeaders,
                                playlistId = route.playlistId,
                                hostActivityClassName = MainActivity::class.java.name,
                                isInPictureInPictureMode = isInPipModeState,
                                onEnterPip = { pipController.enterPipIfSupported() },
                                onPlaybackStateForPip = { playing, size ->
                                    if (navController.currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() == true) {
                                        pipController.isPlaybackPlaying = playing
                                        pipController.currentVideoSize = size
                                        pipController.refreshPipParams()
                                    }
                                },
                                onVideoBoundsChanged = { rect ->
                                    if (navController.currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() == true) {
                                        pipController.pipVideoRect = rect
                                        pipController.refreshPipParams()
                                    }
                                },
                                onNavigateBack = {
                                    pipController.suppressAutoPip()
                                    if (navController.currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() == true) {
                                        val popped = navController.popBackStack()
                                        if (!popped) {
                                            navController.navigate(LibraryRoute) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    inclusive = false
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardExternalIntentIfPresent(intent)
    }

    override fun onDestroy() {
        if (::pipController.isInitialized) {
            pipController.unregister()
        }
        removeOnPictureInPictureModeChangedListener(pipListener)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::pipController.isInitialized) {
            pipController.onUserLeaveHint()
        }
    }

    private fun forwardExternalIntentIfPresent(incoming: Intent?): Boolean {
        val dataUri = incoming?.data ?: return false
        if (incoming.action != Intent.ACTION_VIEW) return false
        val forwarded = Intent(this, ExternalPlayerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(dataUri, incoming.type)
            clipData = incoming.clipData
            incoming.getStringExtra(Intent.EXTRA_TITLE)?.let { putExtra(Intent.EXTRA_TITLE, it) }
            addFlags(
                incoming.flags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    ),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(forwarded)
        return true
    }
}
