package org.opensourcecommunity.ydroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.io.File

fun shareFile(ctx: Context, file: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    } catch (_: Exception) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("path", file.absolutePath))
    }
}

fun openFile(ctx: Context, file: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", file)
        val mime = when (file.extension.lowercase()) {
            "mp3", "m4a", "opus", "wav", "aac" -> "audio/*"
            "mp4", "mkv", "webm" -> "video/*"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Open ${file.name}"))
    } catch (_: Exception) {
    }
}

fun scanFiles(ctx: Context, files: List<File>) {
    try {
        android.media.MediaScannerConnection.scanFile(
            ctx,
            files.map { it.absolutePath }.toTypedArray(),
            null,
            null
        )
    } catch (_: Exception) { }
}

