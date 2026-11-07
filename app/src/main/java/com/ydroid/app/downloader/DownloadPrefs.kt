package com.ydroid.app.downloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Global download destination. When a SAF folder is set, newly finished
 * downloads are moved there (yt-dlp still stages into Downloads/Ydroid
 * because it cannot write to a content URI directly). Files downloaded
 * before the change are left where they are.
 */
object DownloadPrefs {
    private const val PREFS = "ydroid_settings"
    private const val KEY_TREE = "download_tree_uri"
    private const val KEY_LABEL = "download_tree_label"

    const val DEFAULT_LABEL = "Downloads/Ydroid"

    fun treeUri(ctx: Context): Uri? = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
    } catch (_: Exception) {
        null
    }

    fun label(ctx: Context): String = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LABEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_LABEL
    } catch (_: Exception) {
        DEFAULT_LABEL
    }

    /** Best-effort human name for the chosen folder. */
    fun displayName(ctx: Context): String {
        val uri = treeUri(ctx) ?: return DEFAULT_LABEL
        return try {
            DocumentFile.fromTreeUri(ctx, uri)?.name ?: label(ctx)
        } catch (_: Exception) {
            label(ctx)
        }
    }

    fun setTree(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) { }
        val name = try {
            DocumentFile.fromTreeUri(ctx, uri)?.name
        } catch (_: Exception) {
            null
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TREE, uri.toString())
            .putString(KEY_LABEL, name ?: uri.toString())
            .apply()
    }

    fun clear(ctx: Context) {
        val uri = treeUri(ctx)
        try {
            if (uri != null) {
                ctx.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        } catch (_: Exception) { }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TREE)
            .remove(KEY_LABEL)
            .apply()
    }
}
