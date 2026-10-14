package org.opensourcecommunity.ydroid.downloader

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object YdlManager {
    private const val TAG = "YdlManager"
    const val PROCESS_ID = "ydroid-dl"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var initialized = false
        private set

    fun downloadDir(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, "Ydroid")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun init(ctx: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            YoutubeDL.getInstance().init(ctx.applicationContext)
            try {
                FFmpeg.getInstance().init(ctx.applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "ffmpeg init failed (non-fatal): ${e.message}")
            }
            initialized = true
            val ver = try {
                YoutubeDL.getInstance().version(ctx.applicationContext) ?: "yt-dlp ready"
            } catch (_: Exception) {
                "yt-dlp ready"
            }
            Result.success(ver.toString())
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            Result.failure(e)
        }
    }

    suspend fun updateStable(ctx: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            Result.success(status.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchInfo(url: String): Result<FetchedInfo> = fetchInfo(null, url)

    suspend fun fetchInfo(ctx: Context?, url: String): Result<FetchedInfo> = withContext(Dispatchers.IO) {
        try {
            val info = if (ctx != null && Cookies.hasCookies(ctx)) {
                val request = YoutubeDLRequest(url)
                request.addOption("--cookies", Cookies.file(ctx).absolutePath)
                YoutubeDL.getInstance().getInfo(request)
            } else {
                YoutubeDL.getInstance().getInfo(url)
            }
            Result.success(mapInfo(info, url))
        } catch (e: Exception) {
            Log.e(TAG, "getInfo failed", e)
            Result.failure(e)
        }
    }

    private fun mapInfo(v: VideoInfo, fallbackUrl: String): FetchedInfo {
        return try {
            val title = v.title ?: fallbackUrl
            val uploader = v.uploader ?: ""
            val duration = try {
                v.duration.toLong()
            } catch (_: Exception) {
                0L
            }
            val thumb = v.thumbnail ?: ""
            val pageUrl = v.webpageUrl ?: fallbackUrl
            FetchedInfo(
                title = title,
                uploader = uploader,
                durationSec = duration,
                thumbnailUrl = thumb,
                webpageUrl = pageUrl,
                formats = presetFormats()
            )
        } catch (e: Exception) {
            FetchedInfo(title = fallbackUrl, formats = presetFormats())
        }
    }

    /** Flat playlist index: fast, no per-video fetch. Empty list = not a playlist. */
    suspend fun fetchPlaylist(ctx: Context?, url: String): Result<List<PlaylistItem>> = withContext(Dispatchers.IO) {
        try {
            val request = YoutubeDLRequest(url)
            request.addOption("--flat-playlist")
            request.addOption("--dump-single-json")
            if (ctx != null && Cookies.hasCookies(ctx)) {
                request.addOption("--cookies", Cookies.file(ctx).absolutePath)
            }
            val response = YoutubeDL.getInstance().execute(request, null, null)
            val out = response.out.trim()
            if (out.isEmpty()) return@withContext Result.success(emptyList())
            val root = org.json.JSONObject(out)
            val entries = root.optJSONArray("entries") ?: return@withContext Result.success(emptyList())
            if (entries.length() == 0) return@withContext Result.success(emptyList())
            val items = mutableListOf<PlaylistItem>()
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val id = e.optString("id").ifBlank { continue }
                val title = e.optString("title").ifBlank { id }
                val dur = e.optString("duration_string").ifBlank { e.optString("duration") }
                var page = e.optString("webpage_url").ifBlank { e.optString("url") }
                if (page.isNotBlank() && !page.startsWith("http")) {
                    page = "https://www.youtube.com/watch?v=$id"
                }
                items.add(PlaylistItem(i + 1, id, title, dur, page))
            }
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "fetchPlaylist failed", e)
            Result.failure(e)
        }
    }

    suspend fun download(
        url: String,
        format: String,
        audioOnlyMp3: Boolean = false,
        onProgress: (DownloadProgress) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val opts = DownloadOptions(
            quality = if (audioOnlyMp3) VideoQuality.AUDIO_ONLY else VideoQuality.BEST,
            audioFormat = AudioFormat.MP3,
        )
        download(url, opts, onProgress).map { it.third }
    }

    data class DownloadResult(val file: File?, val log: String)

    suspend fun download(
        url: String,
        opts: DownloadOptions,
        onProgress: (DownloadProgress) -> Unit
    ): Result<Triple<List<File>, File?, String>> = download(null, url, opts, onProgress)

    suspend fun download(
        ctx: Context?,
        url: String,
        opts: DownloadOptions,
        onProgress: (DownloadProgress) -> Unit
    ): Result<Triple<List<File>, File?, String>> = withContext(Dispatchers.IO) {
        try {
            val baseDir = downloadDir()
            val dir = if (opts.subfolder.isBlank()) baseDir
            else File(baseDir, opts.subfolder.trim().trim('/')).also { it.mkdirs() }
            val before = dir.listFiles()?.map { it.absolutePath to it.lastModified() }?.toMap() ?: emptyMap()

            val request = YoutubeDLRequest(url)
            request.addOption("--no-mtime")
            request.addOption("--continue")
            request.addOption("--retries", "10")
            request.addOption("--fragment-retries", "10")
            request.addOption("--concurrent-fragments", "4")
            if (ctx != null && Cookies.hasCookies(ctx)) {
                request.addOption("--cookies", Cookies.file(ctx).absolutePath)
            }

            // playlist vs single
            if (opts.playlist) {
                request.addOption("--yes-playlist")
                if (opts.playlistItems.isNotBlank()) {
                    request.addOption("--playlist-items", opts.playlistItems)
                }
            } else request.addOption("--no-playlist")

            // format / audio
            if (opts.quality == VideoQuality.AUDIO_ONLY) {
                request.addOption("-x")
                request.addOption("--audio-format", opts.audioFormat.flag)
                request.addOption("-f", "bestaudio/best")
            } else {
                request.addOption("-f", opts.formatSelector())
                if (opts.container != VideoContainer.AUTO) {
                    request.addOption("--merge-output-format", opts.container.flag)
                }
            }

            // subtitles
            when (opts.subtitles) {
                SubtitleMode.OFF -> {}
                SubtitleMode.EMBED_EN -> {
                    request.addOption("--sub-langs", "en.*")
                    request.addOption("--write-auto-subs")
                    request.addOption("--embed-subs")
                }
                SubtitleMode.WRITE_EN -> {
                    request.addOption("--sub-langs", "en.*")
                    request.addOption("--write-subs")
                    request.addOption("--write-auto-subs")
                    request.addOption("--sub-format", "srt")
                }
                SubtitleMode.WRITE_ALL -> {
                    request.addOption("--sub-langs", "all")
                    request.addOption("--write-subs")
                    request.addOption("--write-auto-subs")
                    request.addOption("--sub-format", "srt")
                }
            }

            if (opts.embedThumbnail) request.addOption("--embed-thumbnail")
            if (opts.addMetadata) request.addOption("--add-metadata")
            if (opts.sponsorBlock) {
                request.addOption("--sponsorblock-remove", "sponsor")
            }
            if (opts.restrictFilenames) request.addOption("--restrict-filenames")
            if (opts.sectionEnabled) {
                val s = opts.sectionStart.trim().ifBlank { "00:00" }
                val e = opts.sectionEnd.trim().ifBlank { "99:99:99" }
                request.addOption("--download-sections", "*$s-$e")
                request.addOption("--force-keyframes-at-cuts")
            }

            // free-form extra flags, split respecting quotes
            splitFlags(opts.customFlags).forEach { flag ->
                if (flag.isNotBlank()) request.addOption(flag)
            }

            request.addOption("-o", dir.absolutePath + "/%(title)s.%(ext)s")

            val callback = { progress: Float, eta: Long, line: String ->
                val p = DownloadProgress(progress, eta, line)
                mainHandler.post { onProgress(p) }
                kotlin.Unit
            }

            val response = YoutubeDL.getInstance().execute(request, PROCESS_ID, callback)
            val media = dir.listFiles()
                ?.filter { it.isFile && !tempExtensions.contains(it.extension.lowercase()) }
                ?: emptyList()
            val newFiles = media.filter { (before[it.absolutePath] ?: -1) != it.lastModified() }
            val newest = newFiles.maxByOrNull { it.lastModified() }
                ?: media.maxByOrNull { it.lastModified() }
            Result.success(Triple(newFiles, newest, response.out))
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            if (e is YoutubeDL.CanceledException ||
                e.message?.contains("destroyed", ignoreCase = true) == true ||
                e.message?.contains("canceled", ignoreCase = true) == true ||
                e.message?.contains("cancelled", ignoreCase = true) == true
            ) {
                Result.failure(CancelledException(e.message ?: "cancelled"))
            } else {
                Result.failure(e)
            }
        }
    }

    private fun splitFlags(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        // split on spaces unless inside single/double quotes
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (c in raw) {
            when {
                quote != null && c == quote -> { quote = null }
                quote == null && (c == '"' || c == '\'') -> { quote = c }
                quote == null && c.isWhitespace() -> {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** Copy a downloaded file into a SAF tree URI picked by the user. Returns true on success. */
    fun copyToTreeUri(ctx: Context, src: File, treeUri: android.net.Uri): Boolean {
        return try {
            val docTree = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, treeUri) ?: return false
            val mime = when (src.extension.lowercase()) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "opus" -> "audio/opus"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "srt" -> "text/plain"
                "vtt" -> "text/vtt"
                else -> "*/*"
            }
            // delete same-name file if present, then create
            docTree.findFile(src.name)?.delete()
            val dest = docTree.createFile(mime, src.name) ?: return false
            ctx.contentResolver.openInputStream(android.net.Uri.fromFile(src))?.use { input ->
                ctx.contentResolver.openOutputStream(dest.uri)?.use { output ->
                    input.copyTo(output)
                } ?: return false
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyToTreeUri failed", e)
            false
        }
    }

    fun stop() {
        try {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        } catch (e: Exception) {
            Log.w(TAG, "stop failed: ${e.message}")
        }
    }

    private val tempExtensions = setOf("part", "ytdl", "temp", "tmp")

    fun listDownloads(): List<File> {
        val dir = downloadDir()
        return dir.listFiles()
            ?.filter { it.isFile && !tempExtensions.contains(it.extension.lowercase()) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun deleteFiles(files: List<File>): Int {
        var count = 0
        files.forEach {
            try {
                if (it.exists() && it.delete()) count++
            } catch (_: Exception) { }
        }
        return count
    }

    fun safeName(s: String): String {
        val cleaned = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "playlist" }.take(80)
    }

    /** Write an EXTENDED .m3u playlist. Returns the .m3u file. */
    fun writeM3u(dir: File, baseName: String, files: List<File>): File {
        val m3u = File(dir, safeName(baseName) + ".m3u")
        val sb = StringBuilder("#EXTM3U\n")
        files.sortedBy { it.name }.forEach { f ->
            sb.append("#EXTINF:-1,${f.nameWithoutExtension}\n")
            sb.append(f.absolutePath).append("\n")
        }
        m3u.writeText(sb.toString())
        return m3u
    }

    class CancelledException(msg: String) : Exception(msg)
}

