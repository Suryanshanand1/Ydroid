package org.opensourcecommunity.ydroid.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.opensourcecommunity.ydroid.downloader.YdlManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    files: List<File>,
    onRefresh: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var selection by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteSingle by remember { mutableStateOf<File?>(null) }
    var m3uMsg by remember { mutableStateOf("") }

    fun toggle(path: String) {
        selection = if (selection.contains(path)) selection - path else selection + path
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Downloads (${files.size})", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { selection = emptySet(); onRefresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        if (files.isEmpty()) {
            Text("No files yet. Downloads go to Downloads/Ydroid.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val allSelected = selection.size == files.size && files.isNotEmpty()
            Checkbox(checked = allSelected, onCheckedChange = {
                selection = if (it) files.map { f -> f.absolutePath }.toSet() else emptySet()
            })
            Text(if (allSelected) "Deselect all" else "Select all", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (selection.isNotEmpty()) {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete (${selection.size})") }
            }
        }

        if (selection.size >= 2) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val picked = files.filter { selection.contains(it.absolutePath) }
                    val mediaExts = setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus", "wav", "aac")
                    val media = picked.filter { mediaExts.contains(it.extension.lowercase()) }.ifEmpty { picked }
                    try {
                        val name = "Ydroid-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.getDefault()).format(java.util.Date())
                        val m3u = YdlManager.writeM3u(YdlManager.downloadDir(), name, media)
                        scanFiles(ctx, listOf(m3u))
                        m3uMsg = "Saved ${m3u.name} (${media.size} entries)"
                        selection = emptySet()
                        onRefresh()
                    } catch (e: Exception) {
                        m3uMsg = "Export failed: ${e.message}"
                    }
                }) { Text("Export .m3u (${selection.size})") }
            }
        }
        if (m3uMsg.isNotBlank()) {
            Text(m3uMsg, style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(files, key = { it.absolutePath }) { f ->
                val selected = selection.contains(f.absolutePath)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(8.dp).clickable { toggle(f.absolutePath) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = selected, onCheckedChange = { toggle(f.absolutePath) })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${Formatter.formatFileSize(ctx, f.length())} • ${formatDate(f.lastModified())}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row {
                                TextButton(onClick = { openFile(ctx, f) }) { Text("Open") }
                                IconButton(onClick = { shareFile(ctx, f) }) {
                                    Icon(Icons.Filled.Share, contentDescription = "Share")
                                }
                                IconButton(onClick = { confirmDeleteSingle = f }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selection.size} file(s)?") },
            text = { Text("Files will be removed from Downloads/Ydroid.") },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = files.filter { selection.contains(it.absolutePath) }
                    YdlManager.deleteFiles(toDelete)
                    selection = emptySet()
                    confirmDelete = false
                    onDeleted()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    confirmDeleteSingle?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDeleteSingle = null },
            title = { Text("Delete file?") },
            text = { Text(f.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    YdlManager.deleteFiles(listOf(f))
                    selection = selection - f.absolutePath
                    confirmDeleteSingle = null
                    onDeleted()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSingle = null }) { Text("Cancel") } }
        )
    }
}

private fun formatDate(ms: Long): String {
    return try {
        SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(ms))
    } catch (_: Exception) { "" }
}

