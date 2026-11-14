package com.loomplay.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loomplay.core.data.preferences.SettingsRepository
import com.loomplay.core.data.preferences.UserPreferences
import com.loomplay.core.data.repository.MediaRepository
import com.loomplay.core.data.updater.AppUpdateManager
import com.loomplay.core.model.AppUpdateInfo
import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.FrameRateMatchingMode
import com.loomplay.core.model.MpvHwDecMode
import com.loomplay.core.model.ThemeMode
import com.loomplay.core.model.UpdateDownloadState
import com.loomplay.core.ui.components.LoomPlayBrandLockup
import com.loomplay.core.ui.theme.LoomColors
import com.loomplay.core.ui.theme.TimecodeTextStyle
import com.loomplay.core.ui.theme.tabular
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val mediaRepository: MediaRepository,
    private val appUpdateManager: AppUpdateManager,
) : ViewModel() {

    val updateState = appUpdateManager.downloadState

    fun checkForUpdates(currentVersionName: String) {
        viewModelScope.launch {
            appUpdateManager.checkForUpdates(currentVersionName)
        }
    }

    fun downloadAndInstallUpdate(updateInfo: AppUpdateInfo, onReady: (android.net.Uri) -> Unit) {
        viewModelScope.launch {
            val result = appUpdateManager.downloadAndPrepareApk(updateInfo)
            result.onSuccess(onReady)
        }
    }

    fun resetUpdateState() {
        appUpdateManager.resetState()
    }

    val preferences = settingsRepository.userPreferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserPreferences(),
    )

    fun setEnginePreference(engine: EnginePreference) {
        viewModelScope.launch { settingsRepository.setDefaultEngine(engine) }
    }

    fun setAutoFallbackEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoFallbackEnabled(enabled) }
    }

    fun setMpvHwDecMode(mode: MpvHwDecMode) {
        viewModelScope.launch { settingsRepository.setMpvHwDecMode(mode) }
    }

    fun setFrameRateMatchingMode(mode: FrameRateMatchingMode) {
        viewModelScope.launch { settingsRepository.setFrameRateMatchingMode(mode) }
    }

    fun setMpvCustomConfig(config: String) {
        viewModelScope.launch { settingsRepository.setMpvCustomConfig(config) }
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        viewModelScope.launch { settingsRepository.setDefaultPlaybackSpeed(speed) }
    }

    fun setDefaultSubtitleDelayMs(delayMs: Long) {
        viewModelScope.launch { settingsRepository.setDefaultSubtitleDelayMs(delayMs) }
    }

    fun setAutoDetectSidecarSubtitles(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoDetectSidecarSubtitles(enabled) }
    }

    fun setAutoPlayNextEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoPlayNextEnabled(enabled) }
    }

    fun setBackgroundAudioEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBackgroundAudioEnabled(enabled) }
    }

    fun setSeekStepSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepository.setSeekStepSeconds(seconds) }
    }

    fun setLongPressBoostSpeed(speed: Float) {
        viewModelScope.launch { settingsRepository.setLongPressBoostSpeed(speed) }
    }

    fun setPinchToZoomEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPinchToZoomEnabled(enabled) }
    }

    fun setSwipeGesturesEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSwipeGesturesEnabled(enabled) }
    }

    fun setHorizontalSeekGestureEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHorizontalSeekGestureEnabled(enabled) }
    }

    fun setPipAutoEnter(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPipAutoEnter(enabled) }
    }

    fun setSubtitleFontSizeSp(sizeSp: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleFontSizeSp(sizeSp) }
    }

    fun setSubtitleOutlineEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSubtitleOutlineEnabled(enabled) }
    }

    fun setSubtitleBgOpacity(opacity: Float) {
        viewModelScope.launch { settingsRepository.setSubtitleBgOpacity(opacity) }
    }

    fun setSubtitleMarginPercent(percent: Int) {
        viewModelScope.launch { settingsRepository.setSubtitleMarginPercent(percent) }
    }

    fun setMpvAssOverrideEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMpvAssOverrideEnabled(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setAmoledDarkMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAmoledDarkMode(enabled) }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColorEnabled(enabled) }
    }

    fun setShowContinueWatching(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowContinueWatching(enabled) }
    }

    fun setMinimalInterface(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMinimalInterface(enabled) }
    }

    fun clearPlaybackHistory() {
        viewModelScope.launch { mediaRepository.clearPlaybackHistory() }
    }

    fun clearThumbnailCache() {
        mediaRepository.clearThumbnailCache()
    }

    fun clearNetworkStreams() {
        viewModelScope.launch { settingsRepository.clearNetworkStreams() }
    }
}

private const val DEVELOPER_WEBSITE_URL = "https://thanveer.vercel.app/"

private enum class SettingsCategory(
    val title: String,
    val icon: ImageVector,
) {
    APPEARANCE("Appearance", Icons.Default.Palette),
    PLAYBACK("Playback & Gestures", Icons.Default.Speed),
    SUBTITLES("Subtitles", Icons.Default.ClosedCaption),
    STORAGE("Storage & Data", Icons.Default.DeleteOutline),
    COMPATIBILITY("Compatibility", Icons.Default.Tune),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }

    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearStreams by remember { mutableStateOf(false) }
    var storageFeedback by remember { mutableStateOf<String?>(null) }
    var pendingUpdateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }

    BackHandler(enabled = selectedCategory != null) {
        selectedCategory = null
        storageFeedback = null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    val backAction = when {
                        selectedCategory != null -> {
                            {
                                selectedCategory = null
                                storageFeedback = null
                            }
                        }
                        onNavigateBack != null -> onNavigateBack
                        else -> null
                    }
                    if (backAction != null) {
                        IconButton(onClick = backAction) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
                title = {
                    Text(
                        text = selectedCategory?.title ?: "Settings",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = selectedCategory,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 6 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 6 } + fadeOut())
                }
            },
            label = "settings_category_transition",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { category ->
            if (category == null) {
                val currentVersion = remember(context) {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "0.1.7"
                }
                SettingsCategoryHub(
                    prefs = prefs,
                    onSelectCategory = {
                        storageFeedback = null
                        selectedCategory = it
                    },
                    onCheckForUpdates = {
                        viewModel.checkForUpdates(currentVersion)
                    },
                )
            } else {
                SettingsCategoryDetail(
                    category = category,
                    prefs = prefs,
                    viewModel = viewModel,
                    storageFeedback = storageFeedback,
                    onClearThumbnailCache = {
                        viewModel.clearThumbnailCache()
                        storageFeedback = "Thumbnail cache cleared"
                    },
                    onRequestClearHistory = { confirmClearHistory = true },
                    onRequestClearStreams = { confirmClearStreams = true },
                )
            }
        }
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear Watch History?") },
            text = {
                Text("Resets resume times, saved audio and subtitle choices, and Continue Watching items.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearPlaybackHistory()
                        confirmClearHistory = false
                        storageFeedback = "Watch history cleared"
                    },
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") }
            },
        )
    }

    val launchInstaller: (Uri) -> Unit = { uri ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Please allow LoomPlay to install apps", Toast.LENGTH_LONG).show()
            val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(permissionIntent)
            }.onFailure {
                // Fallback to generic unknown app sources settings if package-specific fails
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(fallbackIntent) }
            }
        } else {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(intent)
            }.onFailure {
                Toast.makeText(context, "Unable to launch installer: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    when (val state = updateState) {
        is UpdateDownloadState.Available -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetUpdateState() },
                title = { Text("Update Available: v${state.updateInfo.latestVersionName}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (state.updateInfo.releaseNotes.isNotBlank()) {
                                state.updateInfo.releaseNotes
                            } else {
                                "A newer version of LoomPlay is available on GitHub."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Package: ${state.updateInfo.apkFileName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.downloadAndInstallUpdate(state.updateInfo) { uri ->
                                launchInstaller(uri)
                            }
                        },
                    ) {
                        Text("Download & Update")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.resetUpdateState() }) {
                        Text("Later")
                    }
                },
            )
        }
        is UpdateDownloadState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Downloading Update...") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(
                            progress = { state.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${state.progressPercent}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (state.totalBytes > 0) {
                                val mbDownloaded = state.bytesDownloaded / (1024f * 1024f)
                                val mbTotal = state.totalBytes / (1024f * 1024f)
                                Text(
                                    text = "%.1f / %.1f MB".format(mbDownloaded, mbTotal),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
            )
        }
        is UpdateDownloadState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetUpdateState() },
                title = { Text("Update Downloaded") },
                text = {
                    Text("LoomPlay v${state.updateInfo.latestVersionName} is ready to install.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val uri = runCatching { android.net.Uri.parse(state.apkUriString) }.getOrNull()
                            if (uri != null) {
                                launchInstaller(uri)
                            }
                        },
                    ) {
                        Text("Install Now")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.resetUpdateState() }) {
                        Text("Close")
                    }
                },
            )
        }
        is UpdateDownloadState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetUpdateState() },
                title = { Text("Update Check") },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.resetUpdateState() }) {
                        Text("OK")
                    }
                },
            )
        }
        UpdateDownloadState.Checking -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Checking for Updates") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LinearProgressIndicator(modifier = Modifier.weight(1f))
                    }
                },
                confirmButton = {},
            )
        }
        UpdateDownloadState.Idle -> Unit
    }
}

@Composable
private fun SettingsCategoryHub(
    prefs: UserPreferences,
    onSelectCategory: (SettingsCategory) -> Unit,
    onCheckForUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val versionLabel = remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName?.takeIf { it.isNotBlank() }?.let { "Version $it" }
        }.getOrNull() ?: "Video & Stream Player"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            ) {
                LoomPlayBrandLockup(
                    iconSize = 42.dp,
                    subtitle = versionLabel,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
        item(key = "check_updates") {
            Surface(
                onClick = onCheckForUpdates,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = LoomColors.PrimaryScrub.copy(alpha = 0.14f),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = LoomColors.PrimaryScrub,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "Check for Updates",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Look for new releases from GitHub",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        items(SettingsCategory.entries, key = { it.name }) { category ->
            val accent = categoryAccent(category)
            val summary = categorySummary(category, prefs)
            Surface(
                onClick = { onSelectCategory(category) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = accent.copy(alpha = 0.14f),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = category.icon,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = category.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        item(key = "about_developer") {
            val uriHandler = LocalUriHandler.current
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "ABOUT DEVELOPER",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Surface(
                    onClick = {
                        runCatching {
                            uriHandler.openUri(DEVELOPER_WEBSITE_URL)
                        }.onFailure {
                            Toast.makeText(context, "Unable to open browser", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = LoomColors.Primary.copy(alpha = 0.14f),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = null,
                                    tint = LoomColors.Primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "Thanveer Ali",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = DEVELOPER_WEBSITE_URL,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open developer website",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCategoryDetail(
    category: SettingsCategory,
    prefs: UserPreferences,
    viewModel: SettingsViewModel,
    storageFeedback: String?,
    onClearThumbnailCache: () -> Unit,
    onRequestClearHistory: () -> Unit,
    onRequestClearStreams: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (category) {
            SettingsCategory.APPEARANCE -> {
                item {
                    SettingsGroupCard(title = "THEME") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = prefs.themeMode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    label = { Text(mode.label) },
                                    shape = CircleShape,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                )
                            }
                        }
                        SettingsToggleRow(
                            title = "Pure Black Dark Mode",
                            description = "Use true black backgrounds in dark mode",
                            checked = prefs.amoledDarkMode,
                            enabled = prefs.themeMode != ThemeMode.LIGHT,
                            onCheckedChange = viewModel::setAmoledDarkMode,
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                            SettingsToggleRow(
                                title = "Wallpaper Colors",
                                description = "Match app accent colors to your wallpaper",
                                checked = prefs.dynamicColorEnabled,
                                onCheckedChange = viewModel::setDynamicColorEnabled,
                            )
                        }
                    }
                }
                item {
                    SettingsGroupCard(title = "INTERFACE") {
                        SettingsToggleRow(
                            title = "Clean Interface",
                            description = "Hide extra video format tags and on-screen playback details",
                            checked = prefs.minimalInterface,
                            onCheckedChange = viewModel::setMinimalInterface,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        SettingsToggleRow(
                            title = "Continue Watching",
                            description = "Show partially watched videos above your library",
                            checked = prefs.showContinueWatching,
                            onCheckedChange = viewModel::setShowContinueWatching,
                        )
                    }
                }
            }

            SettingsCategory.PLAYBACK -> {
                item {
                    SettingsGroupCard(title = "PLAYBACK") {
                        Text(
                            text = "Default Speed",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                FilterChip(
                                    selected = abs(prefs.defaultPlaybackSpeed - speed) < 0.03f,
                                    onClick = { viewModel.setDefaultPlaybackSpeed(speed) },
                                    label = { Text(formatSpeed(speed)) },
                                    shape = CircleShape,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        SettingsToggleRow(
                            title = "Auto-Play Next Video",
                            description = "Automatically play the next video in your queue",
                            checked = prefs.autoPlayNextEnabled,
                            onCheckedChange = viewModel::setAutoPlayNextEnabled,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        SettingsToggleRow(
                            title = "Background Audio",
                            description = "Keep audio playing when leaving the player",
                            checked = prefs.backgroundAudioEnabled,
                            onCheckedChange = viewModel::setBackgroundAudioEnabled,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        SettingsToggleRow(
                            title = "Auto-Enter Picture-in-Picture",
                            description = if (prefs.backgroundAudioEnabled) {
                                "Turn off Background Audio to use Picture-in-Picture"
                            } else {
                                "Shrink video into a floating window when leaving the app"
                            },
                            checked = prefs.pipAutoEnter,
                            enabled = !prefs.backgroundAudioEnabled,
                            onCheckedChange = viewModel::setPipAutoEnter,
                        )
                    }
                }

                item {
                    SettingsGroupCard(title = "MATCH FRAME RATE (JUDDER-FREE)") {
                        Text(
                            text = "Matches the display refresh rate to the video's frame rate to eliminate 3:2 pulldown judder during movies. 'Seamless' only changes refresh rate if your screen supports flicker-free VRR.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FrameRateMatchingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = prefs.frameRateMatchingMode == mode,
                                    onClick = { viewModel.setFrameRateMatchingMode(mode) },
                                    label = { Text(mode.label) },
                                    shape = CircleShape,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                )
                            }
                        }
                    }
                }

                item {
                    SettingsGroupCard(title = "GESTURES & SKIP") {
                        Text(
                            text = "Double-Tap Skip",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 10, 15, 30).forEach { seconds ->
                                FilterChip(
                                    selected = prefs.seekStepSeconds == seconds,
                                    onClick = { viewModel.setSeekStepSeconds(seconds) },
                                    label = { Text("${seconds}s") },
                                    shape = CircleShape,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        Text(
                            text = "Hold-to-Speed Boost",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1.5f, 2.0f, 2.5f, 3.0f).forEach { speed ->
                                FilterChip(
                                    selected = abs(prefs.longPressBoostSpeed - speed) < 0.03f,
                                    onClick = { viewModel.setLongPressBoostSpeed(speed) },
                                    label = { Text(formatSpeed(speed)) },
                                    shape = CircleShape,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        SettingsToggleRow(
                            title = "Brightness & Volume Swipes",
                            description = "Swipe up or down on the left for brightness, right for volume",
                            checked = prefs.swipeGesturesEnabled,
                            onCheckedChange = viewModel::setSwipeGesturesEnabled,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        SettingsToggleRow(
                            title = "Horizontal Seek Swipe",
                            description = "Slide left or right across the video to jump ahead or back",
                            checked = prefs.horizontalSeekGestureEnabled,
                            onCheckedChange = viewModel::setHorizontalSeekGestureEnabled,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        SettingsToggleRow(
                            title = "Pinch to Zoom",
                            description = "Use two fingers to zoom in and pan around the video",
                            checked = prefs.pinchToZoomEnabled,
                            onCheckedChange = viewModel::setPinchToZoomEnabled,
                        )
                    }
                }
            }

            SettingsCategory.SUBTITLES -> {
                item {
                    SettingsGroupCard(title = "STYLE") {
                        val sliderColors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Text Size",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${prefs.subtitleFontSizeSp.roundToInt()}",
                                style = TimecodeTextStyle.tabular(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = prefs.subtitleFontSizeSp,
                            onValueChange = viewModel::setSubtitleFontSizeSp,
                            valueRange = 12f..36f,
                            colors = sliderColors,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Background Opacity",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${(prefs.subtitleBgOpacity * 100f).roundToInt()}%",
                                style = TimecodeTextStyle.tabular(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = prefs.subtitleBgOpacity,
                            onValueChange = viewModel::setSubtitleBgOpacity,
                            valueRange = 0f..1f,
                            colors = sliderColors,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Bottom Margin",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${prefs.subtitleMarginPercent}%",
                                style = TimecodeTextStyle.tabular(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = prefs.subtitleMarginPercent.toFloat(),
                            onValueChange = { viewModel.setSubtitleMarginPercent(it.roundToInt()) },
                            valueRange = 2f..20f,
                            colors = sliderColors,
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        SettingsToggleRow(
                            title = "Text Outline & Shadow",
                            description = "Improve readability over bright scenes",
                            checked = prefs.subtitleOutlineEnabled,
                            onCheckedChange = viewModel::setSubtitleOutlineEnabled,
                        )
                    }
                }

                item {
                    SettingsGroupCard(title = "FILES & TIMING") {
                        SettingsToggleRow(
                            title = "Auto-Load Local Subtitles",
                            description = "Automatically load matching subtitle files in the same folder",
                            checked = prefs.autoDetectSidecarSubtitles,
                            onCheckedChange = viewModel::setAutoDetectSidecarSubtitles,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        SettingsToggleRow(
                            title = "Override Custom Subtitle Styles",
                            description = "Use your font size and appearance settings for formatted subtitles",
                            checked = prefs.mpvAssOverrideEnabled,
                            onCheckedChange = viewModel::setMpvAssOverrideEnabled,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Default Timing Offset",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (prefs.defaultSubtitleDelayMs == 0L) {
                                    "On time"
                                } else {
                                    "%+d ms".format(prefs.defaultSubtitleDelayMs)
                                },
                                style = TimecodeTextStyle.tabular(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.setDefaultSubtitleDelayMs(prefs.defaultSubtitleDelayMs - 250L) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) { Text("-250", maxLines = 1) }
                            OutlinedButton(
                                onClick = { viewModel.setDefaultSubtitleDelayMs(prefs.defaultSubtitleDelayMs - 50L) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) { Text("-50", maxLines = 1) }
                            OutlinedButton(
                                onClick = { viewModel.setDefaultSubtitleDelayMs(0L) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) { Text("Reset", maxLines = 1) }
                            OutlinedButton(
                                onClick = { viewModel.setDefaultSubtitleDelayMs(prefs.defaultSubtitleDelayMs + 50L) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) { Text("+50", maxLines = 1) }
                            OutlinedButton(
                                onClick = { viewModel.setDefaultSubtitleDelayMs(prefs.defaultSubtitleDelayMs + 250L) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) { Text("+250", maxLines = 1) }
                        }
                    }
                }
            }

            SettingsCategory.STORAGE -> {
                item {
                    SettingsGroupCard(title = "CLEAR DATA") {
                        if (storageFeedback != null) {
                            Text(
                                text = storageFeedback,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        SettingsActionRow(
                            title = "Thumbnail Cache",
                            description = "Remove saved video preview thumbnails to free space",
                            actionLabel = "Clear",
                            onClick = onClearThumbnailCache,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        SettingsActionRow(
                            title = "Watch History",
                            description = "Reset saved resume positions and Continue Watching items",
                            actionLabel = "Clear",
                            onClick = onRequestClearHistory,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        SettingsActionRow(
                            title = "Saved Stream Links",
                            description = "Remove recently opened network stream links",
                            actionLabel = "Clear",
                            onClick = onRequestClearStreams,
                        )
                    }
                }
            }

            SettingsCategory.COMPATIBILITY -> {
                item {
                    SettingsGroupCard(title = "DEFAULT PLAYBACK MODE") {
                        Text(
                            text = "Automatic picks the best playback mode for each video format.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            EnginePreference.entries.forEach { option ->
                                FilterChip(
                                    selected = prefs.defaultEngine == option,
                                    onClick = { viewModel.setEnginePreference(option) },
                                    label = { Text(playerModeLabel(option)) },
                                    shape = CircleShape,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                )
                            }
                        }
                    }
                }

                item {
                    SettingsGroupCard(title = "HARDWARE ACCELERATION") {
                        Text(
                            text = "Uses device hardware for smoother playback and lower battery usage when supported.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MpvHwDecMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = prefs.mpvHwDecMode == mode,
                                    onClick = { viewModel.setMpvHwDecMode(mode) },
                                    label = { Text(mode.label) },
                                    shape = CircleShape,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = LoomColors.MpvCyan,
                                        selectedLabelColor = LoomColors.OnMpv,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun categoryAccent(category: SettingsCategory): Color = when (category) {
    SettingsCategory.APPEARANCE -> LoomColors.Primary
    SettingsCategory.PLAYBACK -> LoomColors.ExoEmerald
    SettingsCategory.SUBTITLES -> LoomColors.MpvCyan
    SettingsCategory.STORAGE -> LoomColors.Primary
    SettingsCategory.COMPATIBILITY -> LoomColors.ExoEmerald
}

private fun categorySummary(category: SettingsCategory, prefs: UserPreferences): String = when (category) {
    SettingsCategory.APPEARANCE -> buildList {
        add(prefs.themeMode.label)
        if (prefs.amoledDarkMode && prefs.themeMode != ThemeMode.LIGHT) add("Pure Black")
        if (!prefs.minimalInterface) add("Extra Details")
    }.joinToString(" • ")

    SettingsCategory.PLAYBACK -> {
        val leaveTag = when {
            prefs.backgroundAudioEnabled -> "Background audio"
            prefs.pipAutoEnter -> "Picture-in-Picture"
            else -> "Pause on exit"
        }
        "${formatSpeed(prefs.defaultPlaybackSpeed)} speed • ${prefs.seekStepSeconds}s skip • $leaveTag"
    }

    SettingsCategory.SUBTITLES -> buildList {
        add("Size ${prefs.subtitleFontSizeSp.roundToInt()}")
        add("${prefs.subtitleMarginPercent}% margin")
        if (prefs.defaultSubtitleDelayMs != 0L) {
            add("%+d ms".format(prefs.defaultSubtitleDelayMs))
        }
    }.joinToString(" • ")

    SettingsCategory.STORAGE -> "Cache, watch history & saved links"

    SettingsCategory.COMPATIBILITY -> {
        if (prefs.defaultEngine == EnginePreference.AUTO && prefs.mpvHwDecMode == MpvHwDecMode.AUTO) {
            "Automatic (Recommended)"
        } else {
            "${playerModeLabel(prefs.defaultEngine)} mode • ${prefs.mpvHwDecMode.label} decoding"
        }
    }
}

private fun formatSpeed(speed: Float): String = when {
    abs(speed - speed.roundToInt()) < 0.01f -> "${speed.roundToInt()}x"
    abs((speed * 10f) - (speed * 10f).roundToInt()) < 0.05f -> "%.1fx".format(speed)
    else -> "%.2fx".format(speed)
}

private fun playerModeLabel(pref: EnginePreference): String = when (pref) {
    EnginePreference.AUTO -> "Automatic"
    EnginePreference.FORCE_EXOPLAYER -> "Standard"
    EnginePreference.FORCE_MPV -> "Compatibility"
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    description: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onClick,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(actionLabel)
        }
    }
}
