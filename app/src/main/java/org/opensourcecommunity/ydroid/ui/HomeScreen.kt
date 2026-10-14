package org.opensourcecommunity.ydroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import org.opensourcecommunity.ydroid.downloader.ActiveDownload
import org.opensourcecommunity.ydroid.downloader.AudioFormat
import org.opensourcecommunity.ydroid.downloader.DlUiState
import org.opensourcecommunity.ydroid.downloader.DownloadOptions
import org.opensourcecommunity.ydroid.downloader.DownloadRepository
import org.opensourcecommunity.ydroid.downloader.FetchedInfo
import org.opensourcecommunity.ydroid.downloader.JobStatus
import org.opensourcecommunity.ydroid.downloader.SubtitleMode
import org.opensourcecommunity.ydroid.downloader.VideoContainer
import org.opensourcecommunity.ydroid.downloader.VideoQuality
import org.opensourcecommunity.ydroid.downloader.YdlManager
import org.opensourcecommunity.ydroid.downloader.playlistSpec
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    initialUrl: String?,
    engineState: DlUiState,
    initVersion: String,
    onDownloaded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember(initialUrl) { mutableStateOf(initialUrl ?: "") }
    var opts by remember { mutableStateOf(DownloadOptions()) }
    var showOptions by remember { mutableStateOf(false) }

    var state: DlUiState by remember(engineState) { mutableStateOf(engineState) }
    var info: FetchedInfo? by remember { mutableStateOf(null) }
    var statusLine by remember { mutableStateOf("") }
    var customFolderUri by remember { mutableStateOf<Uri?>(null) }
    var selectedPlaylist by remember { mutableStateOf(setOf<Int>()) }
    var playlistCache by remember { mutableStateOf(listOf<org.opensourcecommunity.ydroid.downloader.PlaylistItem>()) }
    val job by DownloadRepository.state.collectAsState()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
            customFolderUri = uri
        }
    }

    if (state is DlUiState.Initializing && engineState !is DlUiState.Initializing) {
        state = engineState
    }

    fun doDownload() {
        val u = url.trim()
        if (u.isBlank()) {
            state = DlUiState.Error("Paste a video link first")
            return
        }
        if (DownloadRepository.running) return
        ensureNotifPermission()
        statusLine = ""
        state = DlUiState.Ready
        DownloadRepository.start(ctx, u, opts, customFolderUri, info?.title ?: "")
    }

    // mirror background job into local UI state
    when (val j = job) {
        is ActiveDownload.Running -> {
            state = DlUiState.Downloading(
                org.opensourcecommunity.ydroid.downloader.DownloadProgress(j.progress * 100f, 0, j.status),
                info
            )
            statusLine = j.status
        }
        is ActiveDownload.Done -> {
            if (state is DlUiState.Downloading) {
                onDownloaded()
                state = DlUiState.Done(j.fileName, "")
                statusLine = j.status
            }
        }
        is ActiveDownload.Failed -> {
            if (state is DlUiState.Downloading) {
                state = DlUiState.Error(j.message)
            }
        }
        is ActiveDownload.Stopped -> {
            if (state is DlUiState.Downloading) {
                state = DlUiState.Error(j.message)
                statusLine = j.message
            }
        }
        else -> {}
    }
    val isRunning = job is ActiveDownload.Running
    val progress = (job as? ActiveDownload.Running)?.progress ?: 0f
    val queue by DownloadRepository.queue.collectAsState()
    val pendingCount = queue.count { it.status == JobStatus.QUEUED || it.status == JobStatus.ACTIVE }
    var showQueue by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Download", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            androidx.compose.material3.BadgedBox(badge = { if (pendingCount > 0) androidx.compose.material3.Badge { Text("$pendingCount") } }) {
                androidx.compose.material3.IconButton(onClick = { showQueue = true }) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.PlayArrow,
                        contentDescription = "Queue"
                    )
                }
            }
        }
        if (showQueue) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showQueue = false }) {
                androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth().height(540.dp)) {
                    QueueScreen()
                }
            }
        }
        Text(
            if (initVersion.isBlank()) "yt-dlp starting…" else "yt-dlp: $initVersion",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Video / playlist link") },
            placeholder = { Text("https://…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                if (!clip.isNullOrBlank()) {
                    url = clip.trim()
                    info = null
                    scope.launch {
                        val r = YdlManager.fetchInfo(ctx, clip.trim())
                        if (r.isSuccess) info = r.getOrNull()
                    }
                }
            }) { Text("Paste") }
            TextButton(onClick = { url = ""; info = null }) { Text("Clear") }
        }

        // --- core picks (always visible, still few) ---
        DropdownRow(
            label = "Quality",
            value = opts.quality.label,
            items = VideoQuality.entries.map { it.label },
            onPick = { i -> opts = opts.copy(quality = VideoQuality.entries[i]) }
        )
        if (opts.quality != VideoQuality.AUDIO_ONLY) {
            DropdownRow(
                label = "Video format",
                value = opts.container.label,
                items = VideoContainer.entries.map { it.label },
                onPick = { i -> opts = opts.copy(container = VideoContainer.entries[i]) }
            )
        } else {
            DropdownRow(
                label = "Audio format",
                value = opts.audioFormat.label,
                items = AudioFormat.entries.map { it.label },
                onPick = { i -> opts = opts.copy(audioFormat = AudioFormat.entries[i]) }
            )
        }

        TextButton(onClick = { showOptions = !showOptions }) {
            Text(if (showOptions) "Hide more options ▲" else "More options ▼")
        }

        if (showOptions) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DropdownRow(
                        label = "Subtitles",
                        value = opts.subtitles.label,
                        items = SubtitleMode.entries.map { it.label },
                        onPick = { i -> opts = opts.copy(subtitles = SubtitleMode.entries[i]) }
                    )
                    SwitchRow("Playlist (full)", "Off = single video", opts.playlist) {
                        opts = opts.copy(playlist = it, playlistItems = "")
                        selectedPlaylist = emptySet()
                        playlistCache = emptyList()
                    }
                    if (opts.playlist) {
                        PlaylistPicker(
                            url = url,
                            selected = selectedPlaylist,
                            onSelectionChange = { sel ->
                                selectedPlaylist = sel
                                opts = opts.copy(playlistItems = playlistSpec(sel))
                            }
                        )
                    }
                    SwitchRow("Embed thumbnail", "", opts.embedThumbnail) {
                        opts = opts.copy(embedThumbnail = it)
                    }
                    SwitchRow("Add metadata", "", opts.addMetadata) {
                        opts = opts.copy(addMetadata = it)
                    }
                    SwitchRow("Remove sponsors", "SponsorBlock", opts.sponsorBlock) {
                        opts = opts.copy(sponsorBlock = it)
                    }
                    SwitchRow("Safe filenames", "Recommended", opts.restrictFilenames) {
                        opts = opts.copy(restrictFilenames = it)
                    }
                    SwitchRow("Section only", "Cut start–end", opts.sectionEnabled) {
                        opts = opts.copy(sectionEnabled = it)
                    }
                    if (opts.sectionEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = opts.sectionStart,
                                onValueChange = { opts = opts.copy(sectionStart = it.take(12)) },
                                label = { Text("Start (mm:ss)") },
                                placeholder = { Text("01:30") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = opts.sectionEnd,
                                onValueChange = { opts = opts.copy(sectionEnd = it.take(12)) },
                                label = { Text("End") },
                                placeholder = { Text("02:00") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = opts.subfolder,
                        onValueChange = { opts = opts.copy(subfolder = it.filter { c -> c.isLetterOrDigit() || c in "_- " }.take(40)) },
                        label = { Text("Subfolder in Ydroid (optional)") },
                        placeholder = { Text("Music") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // custom SAF folder
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Custom folder", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                customFolderUri?.let { DocumentFile.fromTreeUri(ctx, it)?.name ?: it.toString() } ?: "Downloads/Ydroid",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { folderPicker.launch(null) }) { Text("Pick") }
                        if (customFolderUri != null) {
                            TextButton(onClick = { customFolderUri = null }) { Text("Reset") }
                        }
                    }
                    OutlinedTextField(
                        value = opts.customFlags,
                        onValueChange = { opts = opts.copy(customFlags = it) },
                        label = { Text("Extra yt-dlp flags (advanced)") },
                        placeholder = { Text("--retries 5 --concurrent-fragments 8") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Button(
            onClick = ::doDownload,
            enabled = state !is DlUiState.Initializing && !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (opts.playlist) "Download playlist" else "Download") }

        if (isRunning) {
            OutlinedButton(onClick = { DownloadRepository.stop(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(statusLine, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } else if (statusLine.isNotBlank()) {
            Text(statusLine, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (!isRunning && state is DlUiState.Error && (statusLine.contains("resume", ignoreCase = true))) {
            OutlinedButton(onClick = { DownloadRepository.resumeAll(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Resume download") }
        }

        info?.let { fetched ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (fetched.thumbnailUrl.isNotBlank()) {
                        AsyncImage(
                            model = fetched.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(fetched.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (fetched.uploader.isNotBlank()) Text(fetched.uploader, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        when (val s = state) {
            is DlUiState.Error -> Card { Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            is DlUiState.Done -> Card {
                Text("Saved: ${s.fileName.ifBlank { "Downloads/Ydroid" }}", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
            }
            is DlUiState.InitError -> Card { Text("Engine failed: ${s.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            is DlUiState.Initializing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Loading yt-dlp…", style = MaterialTheme.typography.bodySmall)
            }
            else -> {}
        }

        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownRow(
    label: String,
    value: String,
    items: List<String>,
    onPick: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEachIndexed { i, name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(i); expanded = false })
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

