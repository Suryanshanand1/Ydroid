package org.opensourcecommunity.ydroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.opensourcecommunity.ydroid.downloader.DlUiState
import org.opensourcecommunity.ydroid.downloader.YdlManager
import org.opensourcecommunity.ydroid.ui.theme.YdoidTheme
import java.io.File

@Composable
fun YdroidApp(initialUrl: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current

    var tab by remember { mutableStateOf(0) }
    var engineState: DlUiState by remember { mutableStateOf(DlUiState.Initializing) }
    var initVersion by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(listOf<File>()) }
    var themeMode by remember { mutableStateOf(loadThemeMode(ctx)) }
    var showNotifAsk by remember { mutableStateOf(!notifAsked(ctx) && android.os.Build.VERSION.SDK_INT >= 33) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        setNotifAsked(ctx)
        showNotifAsk = false
    }

    fun refreshFiles() {
        files = try { YdlManager.listDownloads() } catch (_: Exception) { emptyList() }
    }

    LaunchedEffect(Unit) {
        val res = YdlManager.init(ctx)
        engineState = if (res.isSuccess) {
            initVersion = res.getOrNull() ?: ""
            DlUiState.Ready
        } else {
            DlUiState.InitError(res.exceptionOrNull()?.message ?: "init failed")
        }
        refreshFiles()
    }

    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    YdoidTheme(darkTheme = darkTheme) {
        if (showNotifAsk) {
            AlertDialog(
                onDismissRequest = { setNotifAsked(ctx); showNotifAsk = false },
                title = { Text("Download notifications?") },
                text = { Text("Ydroid shows download progress in a notification so downloads keep running when you leave the app. Allow notifications?") },
                confirmButton = {
                    TextButton(onClick = {
                        notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text("Allow") }
                },
                dismissButton = {
                    TextButton(onClick = { setNotifAsked(ctx); showNotifAsk = false }) { Text("Skip") }
                }
            )
        }
        Scaffold(
            modifier = modifier,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        label = { Text("Home") },
                        icon = { Icon(Icons.Filled.Home, contentDescription = "Home") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1; refreshFiles() },
                        label = { Text("Downloads") },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Downloads") }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        label = { Text("Settings") },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    )
                }
            }
        ) { inner ->
            val m = Modifier.padding(inner)
            when (tab) {
                0 -> HomeScreen(
                    initialUrl = initialUrl,
                    engineState = engineState,
                    initVersion = initVersion,
                    onDownloaded = { refreshFiles() },
                    modifier = m
                )
                1 -> DownloadsScreen(
                    files = files,
                    onRefresh = { refreshFiles() },
                    onDeleted = { refreshFiles() },
                    modifier = m
                )
                else -> SettingsScreen(
                    initVersion = initVersion,
                    onVersionChange = { initVersion = it },
                    themeMode = themeMode,
                    onThemeChange = {
                        themeMode = it
                        saveThemeMode(ctx, it)
                    },
                    modifier = m
                )
            }
        }
    }
}

