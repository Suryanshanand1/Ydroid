package org.opensourcecommunity.ydroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import org.opensourcecommunity.ydroid.ui.YdroidApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sharedUrl = extractSharedUrl(intent)
        setContent {
            // Theme is applied inside YdroidApp (follows Settings → Appearance).
            YdroidApp(
                initialUrl = sharedUrl,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.let(::extractUrl)
            Intent.ACTION_VIEW -> intent.dataString?.let(::extractUrl)
            else -> null
        }
    }

    private fun extractUrl(text: String): String? {
        val regex = Regex("https?://[^\\s]+")
        return regex.find(text)?.value ?: text.takeIf { it.startsWith("http") }
    }
}

