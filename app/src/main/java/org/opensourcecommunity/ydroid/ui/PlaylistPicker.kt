package org.opensourcecommunity.ydroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.opensourcecommunity.ydroid.downloader.PlaylistItem
import org.opensourcecommunity.ydroid.downloader.YdlManager
import org.opensourcecommunity.ydroid.downloader.playlistSpec
import kotlinx.coroutines.launch

@Composable
fun PlaylistPicker(
    url: String,
    selected: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(listOf<PlaylistItem>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var loadedFor by remember { mutableStateOf("") }

    val u = url.trim()
    val summary = if (selected.isEmpty()) "All videos" else "${selected.size} selected (${playlistSpec(selected)})"

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Videos: $summary", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    if (u.isBlank()) {
                        error = "Paste a playlist link first"
                        return@TextButton
                    }
                    if (loadedFor != u || items.isEmpty()) {
                        loading = true
                        error = ""
                        scope.launch {
                            val r = YdlManager.fetchPlaylist(ctx, u)
                            loading = false
                            if (r.isSuccess) {
                                items = r.getOrNull() ?: emptyList()
                                loadedFor = u
                                if (items.isEmpty()) {
                                    error = "No playlist found — single video link?"
                                } else {
                                    showDialog = true
                                }
                            } else {
                                error = "List failed: ${r.exceptionOrNull()?.message}"
                            }
                        }
                    } else {
                        showDialog = true
                    }
                }
            ) { Text("Pick videos") }
        }
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text("  Loading list…", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (error.isNotBlank()) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (showDialog) {
        var draft by remember(items) { mutableStateOf(selected.ifEmpty { items.map { it.index }.toSet() }) }
        Dialog(onDismissRequest = { showDialog = false }) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("${items.size} videos", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { draft = items.map { it.index }.toSet() }) { Text("All") }
                        TextButton(onClick = { draft = emptySet() }) { Text("None") }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(items, key = { it.index }) { item ->
                            val checked = draft.contains(item.index)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    draft = if (checked) draft - item.index else draft + item.index
                                }
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        draft = if (it) draft + item.index else draft - item.index
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${item.index}. ${item.title}",
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (item.duration.isNotBlank()) {
                                        Text(item.duration, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showDialog = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            // empty draft = user explicitly wants none? treat as all (yt-dlp default)
                            onSelectionChange(draft)
                            showDialog = false
                        }) { Text("Done (${draft.size})") }
                    }
                    Text(
                        "Empty = whole playlist. Tip: playlist order can shift — indices refer to current order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

