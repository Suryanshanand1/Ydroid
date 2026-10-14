package org.opensourcecommunity.ydroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.opensourcecommunity.ydroid.downloader.DownloadRepository
import org.opensourcecommunity.ydroid.downloader.JobStatus

@Composable
fun QueueScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val queue by DownloadRepository.queue.collectAsState()
    val paused by DownloadRepository.paused.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Queue (${queue.count { it.status == JobStatus.QUEUED || it.status == JobStatus.ACTIVE }})", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (paused && queue.any { it.status == JobStatus.QUEUED }) {
                IconButton(onClick = { DownloadRepository.resumeAll(ctx) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Resume all")
                }
            }
        }

        if (queue.isEmpty()) {
            Text("Empty — downloads you start on Home line up here.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { DownloadRepository.clearFinished(ctx) }) { Text("Clear finished") }
            TextButton(onClick = { DownloadRepository.clearAll(ctx) }) { Text("Clear all") }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(queue, key = { it.id }) { job ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(job.title.ifBlank { job.url }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${job.opts.quality.label} · ${if (job.opts.quality == org.opensourcecommunity.ydroid.downloader.VideoQuality.AUDIO_ONLY) job.opts.audioFormat.label else job.opts.container.label}" +
                                        (if (job.opts.playlistItems.isNotBlank()) " · items ${job.opts.playlistItems}" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusChip(job.status)
                            IconButton(onClick = { DownloadRepository.remove(job.id, ctx) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove")
                            }
                        }
                        if (job.status == JobStatus.ACTIVE) {
                            LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (job.status) {
                                    JobStatus.QUEUED -> if (job.note.isBlank()) "Waiting…" else job.note
                                    JobStatus.ACTIVE -> job.note.ifBlank { "${(job.progress * 100).toInt()}%" }
                                    JobStatus.DONE -> job.note.ifBlank { "Done" }
                                    JobStatus.FAILED -> job.note.ifBlank { "Failed" }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (job.status == JobStatus.FAILED) {
                                IconButton(onClick = { DownloadRepository.retry(job.id, ctx) }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: JobStatus) {
    val label = when (status) {
        JobStatus.QUEUED -> "Queued"
        JobStatus.ACTIVE -> "Active"
        JobStatus.DONE -> "Done"
        JobStatus.FAILED -> "Failed"
    }
    val color = when (status) {
        JobStatus.ACTIVE -> MaterialTheme.colorScheme.primary
        JobStatus.DONE -> MaterialTheme.colorScheme.tertiary
        JobStatus.FAILED -> MaterialTheme.colorScheme.error
        JobStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
}
