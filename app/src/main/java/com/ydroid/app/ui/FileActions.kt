package com.ydroid.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ydroid.app.downloader.DownloadEntry
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

private fun entryUri(ctx: Context, entry: DownloadEntry): Uri? {
    entry.uri?.let { return it }
    val file = entry.localFile ?: return null
    return try {
        androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", file)
    } catch (_: Exception) {
        null
    }
}

private fun mimeForName(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus" -> "audio/opus"
        "wav", "aac" -> "audio/*"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "srt", "vtt", "ass" -> "text/plain"
        "m3u" -> "audio/x-mpegurl"
        else -> "*/*"
    }

fun openEntry(ctx: Context, entry: DownloadEntry) {
    val uri = entryUri(ctx, entry) ?: return
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeForName(entry.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Open ${entry.name}"))
    } catch (_: Exception) { }
}

fun shareEntry(ctx: Context, entry: DownloadEntry) {
    val uri = entryUri(ctx, entry)
    val fallbackPath = entry.localFile?.absolutePath ?: entry.name
    if (uri == null) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("path", fallbackPath))
        return
    }
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeForName(entry.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Share ${entry.name}"))
    } catch (_: Exception) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("path", fallbackPath))
    }
}

