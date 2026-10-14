package org.opensourcecommunity.ydroid.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.opensourcecommunity.ydroid.downloader.Cookies
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun YouTubeLoginRow(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLogin by remember { mutableStateOf(false) }
    var loggedIn by remember { mutableStateOf(Cookies.hasCookies(ctx)) }
    var message by remember { mutableStateOf("") }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("YouTube login", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (loggedIn) {
                    val d = Cookies.lastSaved(ctx)
                    val whenStr = if (d > 0) SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(d)) else ""
                    "Logged in $whenStr — downloads use your account, fewer bot-blocks."
                } else {
                    "Not logged in — age-restricted / bot-flagged videos may fail."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showLogin = true }) {
                    Text(if (loggedIn) "Re-login" else "Log in")
                }
                if (loggedIn) {
                    OutlinedButton(onClick = {
                        Cookies.clear(ctx)
                        loggedIn = false
                        message = "Logged out — cookies cleared."
                    }) { Text("Log out") }
                }
            }
        }
    }

    if (showLogin) {
        Dialog(
            onDismissRequest = { showLogin = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(modifier = Modifier.padding(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sign in to YouTube, then tap Save cookies")
                    YouTubeWebView(Modifier.fillMaxWidth().height(420.dp))
                    if (message.isNotBlank()) {
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val r = Cookies.saveFromWebView(ctx)
                                if (r.isSuccess) {
                                    loggedIn = true
                                    message = "Saved ${r.getOrNull()} cookies."
                                    showLogin = false
                                } else {
                                    message = r.exceptionOrNull()?.message ?: "No cookies found"
                                }
                            }
                        }) { Text("Save cookies") }
                        TextButton(onClick = { showLogin = false }) { Text("Close") }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeWebView(modifier: Modifier = Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                try {
                    cm.setAcceptThirdPartyCookies(this, true)
                } catch (_: Exception) { }
                webViewClient = WebViewClient()
                loadUrl("https://m.youtube.com")
            }
        }
    )
}

