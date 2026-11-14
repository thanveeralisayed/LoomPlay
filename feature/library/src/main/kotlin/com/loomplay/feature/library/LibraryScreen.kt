package com.loomplay.feature.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.EngineType
import com.loomplay.core.model.LibrarySortOrder
import com.loomplay.core.model.LoomMediaItem
import com.loomplay.core.ui.components.EngineBadgePill
import com.loomplay.core.ui.components.LoomPlayLogoMark
import com.loomplay.core.ui.components.LoomPlayWordmark
import com.loomplay.core.ui.components.TechBadgeChip
import com.loomplay.core.ui.components.formatTimecode
import com.loomplay.core.ui.theme.LoomColors
import com.loomplay.core.ui.theme.TimecodeTextStyle
import com.loomplay.core.ui.theme.tabular

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onPlayMedia: (
        uri: String,
        title: String,
        mimeType: String?,
        engineOverride: EnginePreference?,
        headers: Map<String, String>,
    ) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val mediaPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.refreshLibrary()
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val resolvedMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val title = resolveFileDisplayName(context, uri) ?: "Local Video"
            onPlayMedia(uri.toString(), title, resolvedMime, null, emptyMap())
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mediaPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED
                if (granted != hasPermission) {
                    hasPermission = granted
                    if (granted) viewModel.refreshLibrary()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (hasPermission) viewModel.refreshLibrary()
    }

    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var showNetworkSheet by rememberSaveable { mutableStateOf(false) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }
    var optionsTarget by remember { mutableStateOf<LoomMediaItem?>(null) }
    var infoTarget by remember { mutableStateOf<LoomMediaItem?>(null) }
    var playlistTarget by remember { mutableStateOf<LoomMediaItem?>(null) }

    val selectedFolder = state.selectedFolder
    val isInsideFolder = !searchExpanded &&
        selectedFolder != null &&
        state.activeTab == LibraryTabMode.ALL_VIDEOS

    BackHandler(enabled = searchExpanded) {
        viewModel.onSearchQueryChanged("")
        searchExpanded = false
    }

    BackHandler(
        enabled = !searchExpanded &&
            selectedFolder != null &&
            state.activeTab == LibraryTabMode.ALL_VIDEOS,
    ) {
        viewModel.returnToFolders()
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
                    if (isInsideFolder) {
                        IconButton(onClick = viewModel::returnToFolders) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Folders",
                            )
                        }
                    }
                },
                title = {
                    if (searchExpanded) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (state.searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search videos or folders",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    BasicTextField(
                                        value = state.searchQuery,
                                        onValueChange = viewModel::onSearchQueryChanged,
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    } else if (isInsideFolder) {
                        Text(
                            text = selectedFolder.orEmpty(),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LoomPlayLogoMark(size = 24.dp)
                            LoomPlayWordmark(
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.4).sp,
                                ),
                            )
                        }
                    }
                },
                actions = {
                    if (searchExpanded) {
                        IconButton(
                            onClick = {
                                viewModel.onSearchQueryChanged("")
                                searchExpanded = false
                            },
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close Search")
                        }
                    } else {
                        IconButton(onClick = { searchExpanded = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { showNetworkSheet = true }) {
                            Icon(Icons.Default.Link, contentDescription = "Open Network Stream")
                        }
                        Box {
                            IconButton(onClick = { overflowMenuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More Options")
                            }
                            DropdownMenu(
                                expanded = overflowMenuExpanded,
                                onDismissRequest = { overflowMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Open File") },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        openDocumentLauncher.launch(arrayOf("video/*"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        onOpenSettings()
                                    },
                                )
                                HorizontalDivider()
                                LibrarySortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text("Sort: ${order.label}") },
                                        trailingIcon = {
                                            if (state.sortOrder == order) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            overflowMenuExpanded = false
                                        },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Refresh Library") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    onClick = {
                                        viewModel.refreshLibrary()
                                        overflowMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val showTechBadges = !state.preferences.minimalInterface

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Minimal Segmented Toggle: Videos / Folders (hidden when inside a contextual folder)
            if (selectedFolder == null || state.activeTab == LibraryTabMode.FOLDERS) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ) {
                        Row(modifier = Modifier.padding(4.dp)) {
                            SegmentedTabButton(
                                label = "Videos",
                                selected = state.activeTab == LibraryTabMode.ALL_VIDEOS,
                                onClick = { viewModel.onSelectTab(LibraryTabMode.ALL_VIDEOS) },
                                modifier = Modifier.weight(1f),
                            )
                            SegmentedTabButton(
                                label = "Folders",
                                selected = state.activeTab == LibraryTabMode.FOLDERS,
                                onClick = { viewModel.onSelectTab(LibraryTabMode.FOLDERS) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // Permission Request Card
            if (!hasPermission) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Local Video Access",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Grant storage access to scan device videos, or pick files directly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { permissionLauncher.launch(mediaPermission) }) {
                                    Text("Allow Access")
                                }
                                OutlinedButton(onClick = { openDocumentLauncher.launch(arrayOf("video/*")) }) {
                                    Text("Open File")
                                }
                            }
                        }
                    }
                }
            }

            // Continue Watching Strip
            if (state.preferences.showContinueWatching &&
                state.continueWatching.isNotEmpty() &&
                state.activeTab == LibraryTabMode.ALL_VIDEOS &&
                selectedFolder == null &&
                state.searchQuery.isBlank()
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Continue Watching",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.continueWatching, key = { "cw_${it.uri}" }) { item ->
                                MinimalContinueWatchingCard(
                                    item = item,
                                    showTechBadges = showTechBadges,
                                    onClick = { onPlayMedia(item.uri, item.title, item.mimeType, null, emptyMap()) },
                                )
                            }
                        }
                    }
                }
            }

            // Content List: Folders or Minimal Video Rows
            val canShowEmptyNotice = hasPermission || state.searchQuery.isNotBlank() || selectedFolder != null
            if (state.activeTab == LibraryTabMode.FOLDERS) {
                if (state.folders.isEmpty()) {
                    if (canShowEmptyNotice) {
                        item {
                            EmptyLibraryNotice(
                                isFiltered = state.searchQuery.isNotBlank(),
                                onOpenFile = { openDocumentLauncher.launch(arrayOf("video/*")) },
                            )
                        }
                    }
                } else {
                    items(state.folders, key = { "folder_${it.name}" }) { folder ->
                        FolderRowCard(
                            folder = folder,
                            onClick = { viewModel.onSelectFolder(folder.name) },
                        )
                    }
                }
            } else {
                if (state.filteredVideos.isEmpty()) {
                    if (canShowEmptyNotice) {
                        item {
                            EmptyLibraryNotice(
                                isFiltered = state.searchQuery.isNotBlank() || selectedFolder != null,
                                onOpenFile = { openDocumentLauncher.launch(arrayOf("video/*")) },
                            )
                        }
                    }
                } else {
                    items(state.filteredVideos, key = { it.uri }) { item ->
                        MinimalMediaListRow(
                            item = item,
                            showTechBadges = showTechBadges,
                            onClick = { onPlayMedia(item.uri, item.title, item.mimeType, null, emptyMap()) },
                            onMoreClick = { optionsTarget = item },
                        )
                    }
                }
            }
        }
    }

    if (showNetworkSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNetworkSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            NetworkStreamSheetContent(
                savedStreams = state.preferences.savedStreams,
                onPlayStream = { uri, title, engineOverride, headers ->
                    showNetworkSheet = false
                    onPlayMedia(uri, title, null, engineOverride, headers)
                },
                onRecordStream = viewModel::recordNetworkStream,
                onRemoveStream = viewModel::removeNetworkStream,
                onClearStreams = viewModel::clearNetworkStreams,
            )
        }
    }

    // 3-Dot Item Options Bottom Sheet
    optionsTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { optionsTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = target.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    onClick = {
                        optionsTarget = null
                        onPlayMedia(target.uri, target.title, target.mimeType, EnginePreference.FORCE_EXOPLAYER, emptyMap())
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = LoomColors.ExoEmerald)
                        Text("Play in Standard Mode", style = MaterialTheme.typography.titleSmall)
                    }
                }
                Surface(
                    onClick = {
                        optionsTarget = null
                        onPlayMedia(target.uri, target.title, target.mimeType, EnginePreference.FORCE_MPV, emptyMap())
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Memory, contentDescription = null, tint = LoomColors.MpvCyan)
                        Text("Play in Compatibility Mode", style = MaterialTheme.typography.titleSmall)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            infoTarget = target
                            optionsTarget = null
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Details")
                    }
                    OutlinedButton(
                        onClick = {
                            playlistTarget = target
                            optionsTarget = null
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add to Playlist")
                    }
                }
            }
        }
    }

    infoTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { infoTarget = null },
            title = { Text("Media Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Title: ${target.title}")
                    Text("Folder: ${target.folderName}")
                    if (target.width > 0 && target.height > 0) {
                        Text("Resolution: ${target.width} × ${target.height}")
                    }
                    Text("Duration: ${formatTimecode(target.durationMs)}")
                    Text("Size: ${formatBytes(target.fileSizeBytes)}")
                    Text("Format: ${target.containerLabel}")
                }
            },
            confirmButton = {
                TextButton(onClick = { infoTarget = null }) { Text("Close") }
            },
        )
    }

    playlistTarget?.let { target ->
        var newPlaylistName by remember(target.uri) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { playlistTarget = null },
            title = { Text("Add to Playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            placeholder = { Text("New playlist") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                viewModel.createPlaylistAndAdd(newPlaylistName, target.uri)
                                playlistTarget = null
                            },
                            enabled = newPlaylistName.isNotBlank(),
                        ) {
                            Text("Create")
                        }
                    }
                    if (state.playlists.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.playlists.forEach { playlist ->
                                Surface(
                                    onClick = {
                                        viewModel.addToPlaylist(playlist.playlistId, target.uri)
                                        playlistTarget = null
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(playlist.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "${playlist.itemCount}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { playlistTarget = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun SegmentedTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MinimalContinueWatchingCard(
    item: LoomMediaItem,
    showTechBadges: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(204.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(Uri.parse(item.uri))
                        .crossfade(true)
                        .build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                if (showTechBadges) {
                    EngineBadgePill(
                        engine = item.recommendedEngine,
                        label = if (item.recommendedEngine == EngineType.MPV) "Compat" else "Std",
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                    )
                }

                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Resume",
                        tint = Color.White,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(20.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(item.progressFraction)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatTimecode(item.lastPositionMs)} / ${formatTimecode(item.durationMs)}",
                    style = TimecodeTextStyle.tabular().copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MinimalMediaListRow(
    item: LoomMediaItem,
    showTechBadges: Boolean,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(104.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(Uri.parse(item.uri))
                        .crossfade(true)
                        .build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (item.durationMs > 0L) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.78f),
                    ) {
                        Text(
                            text = formatTimecode(item.durationMs),
                            style = TimecodeTextStyle.tabular().copy(fontSize = 10.sp),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                if (item.progressFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(item.progressFraction)
                                .height(2.5.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = "${formatBytes(item.fileSizeBytes)} • ${item.containerLabel} • ${item.folderName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (showTechBadges) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.resolutionBadge?.let {
                            TechBadgeChip(
                                text = it,
                                accentColor = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        TechBadgeChip(
                            text = if (item.recommendedEngine == EngineType.MPV) "Compat" else "Standard",
                            accentColor = if (item.recommendedEngine == EngineType.MPV) {
                                LoomColors.MpvCyan
                            } else {
                                LoomColors.ExoEmerald
                            },
                        )
                    }
                }
            }

            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderRowCard(
    folder: FolderGroup,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${folder.videoCount} videos • ${formatBytes(folder.totalSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryNotice(
    isFiltered: Boolean,
    onOpenFile: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isFiltered) "No matching videos found" else "No local videos found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!isFiltered) {
                Text(
                    text = "Pick a video directly from your device or open a stream link.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenFile) {
                    Text("Open Video File")
                }
            }
        }
    }
}

private fun resolveFileDisplayName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
        }
    }
    return uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let(Uri::decode)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        "%.1f GB".format(mb / 1024.0)
    } else {
        "%.0f MB".format(mb)
    }
}
