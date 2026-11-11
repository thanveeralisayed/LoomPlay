package com.loomplay.feature.library

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loomplay.core.model.EnginePreference
import com.loomplay.core.model.NetworkStreamEntry

private data class CustomHeaderField(
    val id: Long,
    val name: String = "",
    val value: String = "",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NetworkStreamSheetContent(
    savedStreams: List<NetworkStreamEntry>,
    onPlayStream: (uri: String, title: String, engineOverride: EnginePreference?, headers: Map<String, String>) -> Unit,
    onRecordStream: (url: String, title: String, headers: Map<String, String>, preferredEngine: EnginePreference) -> Unit,
    onRemoveStream: (url: String) -> Unit,
    onClearStreams: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var urlInput by remember { mutableStateOf("") }
    var titleInput by remember { mutableStateOf("") }
    var selectedEngine by remember { mutableStateOf<EnginePreference?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }

    var userAgentInput by remember { mutableStateOf("") }
    var refererInput by remember { mutableStateOf("") }
    var cookieInput by remember { mutableStateOf("") }
    var nextHeaderId by remember { mutableStateOf(1L) }
    val customHeaders = remember { mutableStateListOf<CustomHeaderField>() }

    fun collectHeaders(): Map<String, String> = buildMap {
        userAgentInput.trim().takeIf { it.isNotEmpty() }?.let { put("User-Agent", it) }
        refererInput.trim().takeIf { it.isNotEmpty() }?.let { put("Referer", it) }
        cookieInput.trim().takeIf { it.isNotEmpty() }?.let { put("Cookie", it) }
        customHeaders.forEach { field ->
            val k = field.name.trim()
            val v = field.value.trim()
            if (k.isNotEmpty() && v.isNotEmpty()) {
                put(k, v)
            }
        }
    }

    fun launchStream() {
        val cleanUrl = urlInput.trim()
        if (cleanUrl.isEmpty()) return
        val headers = collectHeaders()
        val displayTitle = titleInput.trim().ifBlank { "Network Stream" }
        onRecordStream(
            cleanUrl,
            titleInput.trim(),
            headers,
            selectedEngine ?: EnginePreference.AUTO,
        )
        onPlayStream(cleanUrl, displayTitle, selectedEngine, headers)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 580.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 4.dp,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Network Stream",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Stream URL") },
                    placeholder = { Text("https://, .m3u8, .mpd, rtsp://") },
                    singleLine = true,
                    trailingIcon = {
                        if (urlInput.isNotEmpty()) {
                            IconButton(onClick = { urlInput = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear URL")
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    readClipboardText(context)?.let { urlInput = it }
                                },
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste URL")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Surface(
                    onClick = { showAdvanced = !showAdvanced },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val headerCount = collectHeaders().size
                        val suffix = buildList {
                            if (selectedEngine != null) {
                                add(if (selectedEngine == EnginePreference.FORCE_MPV) "Compatibility" else "Standard")
                            }
                            if (headerCount > 0) add("$headerCount headers")
                        }.joinToString(" • ")
                        Text(
                            text = if (suffix.isNotEmpty()) "Options ($suffix)" else "Title, Headers & Playback Mode",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (showAdvanced) "Collapse options" else "Expand options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AnimatedVisibility(visible = showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = titleInput,
                            onValueChange = { titleInput = it },
                            label = { Text("Title (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = selectedEngine == null,
                                onClick = { selectedEngine = null },
                                label = { Text("Auto") },
                                shape = CircleShape,
                            )
                            FilterChip(
                                selected = selectedEngine == EnginePreference.FORCE_EXOPLAYER,
                                onClick = { selectedEngine = EnginePreference.FORCE_EXOPLAYER },
                                label = { Text("Standard") },
                                shape = CircleShape,
                            )
                            FilterChip(
                                selected = selectedEngine == EnginePreference.FORCE_MPV,
                                onClick = { selectedEngine = EnginePreference.FORCE_MPV },
                                label = { Text("Compatibility") },
                                shape = CircleShape,
                            )
                        }

                        OutlinedTextField(
                            value = userAgentInput,
                            onValueChange = { userAgentInput = it },
                            label = { Text("User-Agent") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = refererInput,
                            onValueChange = { refererInput = it },
                            label = { Text("Referer") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = cookieInput,
                            onValueChange = { cookieInput = it },
                            label = { Text("Cookie") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        customHeaders.forEachIndexed { idx, field ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = field.name,
                                    onValueChange = { customHeaders[idx] = field.copy(name = it) },
                                    label = { Text("Header") },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.9f),
                                )
                                OutlinedTextField(
                                    value = field.value,
                                    onValueChange = { customHeaders[idx] = field.copy(value = it) },
                                    label = { Text("Value") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.2f),
                                )
                                IconButton(onClick = { customHeaders.removeAt(idx) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove header")
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(
                                onClick = { customHeaders += CustomHeaderField(id = nextHeaderId++) },
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Header")
                            }
                            if (collectHeaders().isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        userAgentInput = ""
                                        refererInput = ""
                                        cookieInput = ""
                                        customHeaders.clear()
                                    },
                                ) {
                                    Text("Clear Headers")
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = ::launchStream,
                    enabled = urlInput.isNotBlank(),
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play Stream")
                }
            }
        }

        if (savedStreams.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Recent",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onClearStreams) {
                        Text("Clear")
                    }
                }
            }
            items(savedStreams, key = { it.url }) { stream ->
                var menuOpen by remember { mutableStateOf(false) }
                Surface(
                    onClick = {
                        val override = stream.preferredEngine.takeIf { it != EnginePreference.AUTO }
                        onPlayStream(
                            stream.url,
                            stream.title.ifBlank { "Network Stream" },
                            override,
                            stream.headers,
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stream.title.ifBlank { stream.url },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitleText = buildString {
                                if (stream.title.isNotBlank()) append(stream.url)
                                if (stream.preferredEngine != EnginePreference.AUTO) {
                                    if (isNotEmpty()) append(" • ")
                                    append(if (stream.preferredEngine == EnginePreference.FORCE_MPV) "Compatibility" else "Standard")
                                }
                                if (stream.headers.isNotEmpty()) {
                                    if (isNotEmpty()) append(" • ")
                                    append("${stream.headers.size} headers")
                                }
                            }
                            if (subtitleText.isNotEmpty()) {
                                Text(
                                    text = subtitleText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Stream options",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Load into Editor") },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    onClick = {
                                        urlInput = stream.url
                                        titleInput = stream.title
                                        selectedEngine = stream.preferredEngine.takeIf { it != EnginePreference.AUTO }
                                        userAgentInput = stream.headers["User-Agent"].orEmpty()
                                        refererInput = stream.headers["Referer"].orEmpty()
                                        cookieInput = stream.headers["Cookie"].orEmpty()
                                        customHeaders.clear()
                                        stream.headers
                                            .filterKeys { it !in setOf("User-Agent", "Referer", "Cookie") }
                                            .forEach { (k, v) ->
                                                customHeaders += CustomHeaderField(id = nextHeaderId++, name = k, value = v)
                                            }
                                        if (stream.headers.isNotEmpty() || stream.title.isNotBlank() || selectedEngine != null) {
                                            showAdvanced = true
                                        }
                                        menuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Remove") },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                                    onClick = {
                                        onRemoveStream(stream.url)
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun readClipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    if (!cm.hasPrimaryClip()) return null
    return cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
