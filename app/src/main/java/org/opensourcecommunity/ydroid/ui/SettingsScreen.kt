package org.opensourcecommunity.ydroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.opensourcecommunity.ydroid.downloader.YdlManager
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    initVersion: String,
    onVersionChange: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf("") }
    var notifGranted by remember { mutableStateOf(isNotifGranted(ctx)) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        setNotifAsked(ctx)
        notifGranted = granted || isNotifGranted(ctx)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Text("Appearance", fontWeight = FontWeight.SemiBold)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(4.dp)) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onThemeChange(mode) }.padding(8.dp)
                    ) {
                        RadioButton(selected = themeMode == mode, onClick = { onThemeChange(mode) })
                        Text(mode.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        Text("Account", fontWeight = FontWeight.SemiBold)
        YouTubeLoginRow()

        Text("Notifications", fontWeight = FontWeight.SemiBold)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (notifGranted) "Enabled — progress shows while downloading."
                    else "Off — enable to see progress when you leave the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    if (!notifGranted) {
                        TextButton(onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }) { Text("Enable notifications") }
                    } else {
                        TextButton(onClick = { notifGranted = isNotifGranted(ctx) }) { Text("Refresh status") }
                    }
                }
            }
        }

        Text("Engine", fontWeight = FontWeight.SemiBold)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (initVersion.isBlank()) "yt-dlp starting…" else "yt-dlp: $initVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (updateMsg.isNotBlank()) {
                    Text(updateMsg, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (updating) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                updating = true
                                updateMsg = ""
                                val r = YdlManager.updateStable(ctx)
                                if (r.isSuccess) {
                                    onVersionChange(r.getOrNull() ?: initVersion)
                                    updateMsg = "Updated: ${r.getOrNull()}"
                                } else {
                                    updateMsg = "Update failed: ${r.exceptionOrNull()?.message}"
                                }
                                updating = false
                            }
                        },
                        enabled = !updating
                    ) { Text("Update yt-dlp") }
                }
            }
        }

        Text("Storage", fontWeight = FontWeight.SemiBold)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Downloads/Ydroid", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Files are saved to the shared Downloads folder — no storage permission needed on Android 14+.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

