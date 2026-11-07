package com.ydroid.app.downloader

import java.util.Locale

data class FormatOption(
    val id: String,
    val label: String,
    val ext: String = "",
    val note: String = ""
)

/**
 * One entry from yt-dlp's `formats` array, trimmed to what we need to estimate
 * the download size for a chosen quality.
 */
data class MediaFormat(
    val formatId: String = "",
    val ext: String = "",
    val height: Int = 0,
    val vcodec: String = "",
    val acodec: String = "",
    val fileSize: Long = 0L,
    /** Total bitrate in kbps. */
    val tbr: Int = 0,
    /** Audio bitrate in kbps. */
    val abr: Int = 0,
) {
    val isVideo: Boolean get() = vcodec.isNotBlank() && !vcodec.equals("none", true)
    val isAudio: Boolean get() = acodec.isNotBlank() && !acodec.equals("none", true)
    val isVideoOnly: Boolean get() = isVideo && !isAudio
    val isAudioOnly: Boolean get() = isAudio && !isVideo
}

data class FetchedInfo(
    val title: String,
    val uploader: String = "",
    val durationSec: Long = 0,
    val thumbnailUrl: String = "",
    val webpageUrl: String = "",
    val formats: List<FormatOption> = emptyList(),
    /** Raw yt-dlp formats, used for per-quality size estimates. */
    val mediaFormats: List<MediaFormat> = emptyList(),
) {
    val durationLabel: String
        get() {
            if (durationSec <= 0) return ""
            val h = durationSec / 3600
            val m = (durationSec % 3600) / 60
            val s = durationSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
}

/**
 * Approximate download size in bytes for the selected quality/container.
 * Uses yt-dlp's reported filesize/filesize_approx when available, otherwise
 * estimates from bitrate × duration. Returns null when nothing is known.
 */
fun FetchedInfo.estimatedBytes(
    quality: VideoQuality,
    container: VideoContainer = VideoContainer.AUTO,
): Long? {
    if (mediaFormats.isEmpty()) return null
    val dur = durationSec

    fun sizeOf(f: MediaFormat, bitrateKbps: Int): Long {
        if (f.fileSize > 0) return f.fileSize
        if (dur > 0 && bitrateKbps > 0) return bitrateKbps.toLong() * 1000L / 8L * dur
        return 0L
    }

    fun extBonus(f: MediaFormat): Int = when {
        container == VideoContainer.AUTO -> 0
        f.ext.equals(container.flag, true) -> 2
        container == VideoContainer.MP4 && f.ext.equals("m4a", true) -> 1
        else -> 0
    }

    val audioOnly = mediaFormats.filter { it.isAudioOnly }
    if (quality == VideoQuality.AUDIO_ONLY) {
        val audio = audioOnly.maxWithOrNull(
            compareBy<MediaFormat> { it.abr }.thenBy { it.tbr }
        ) ?: return null
        return sizeOf(audio, if (audio.abr > 0) audio.abr else audio.tbr).takeIf { it > 0 }
    }

    val cap = if (quality == VideoQuality.BEST) Int.MAX_VALUE else quality.maxHeight
    val videos = mediaFormats.filter { it.isVideo && it.height in 1..cap }
    if (videos.isEmpty()) return null

    // Match yt-dlp's bestvideo+bestaudio: tallest stream first, prefer
    // video-only at the same height, then the requested container.
    val video = videos.maxWithOrNull(
        compareBy<MediaFormat> { it.height }
            .thenBy { if (it.isVideoOnly) 1 else 0 }
            .thenBy { it.tbr }
            .thenBy { extBonus(it) }
    ) ?: return null

    var total = sizeOf(video, video.tbr)
    if (total <= 0) return null
    if (video.isVideoOnly) {
        val audio = audioOnly.maxWithOrNull(
            compareBy<MediaFormat> { it.abr }.thenBy { it.tbr }
        ) ?: return total
        val audioSize = sizeOf(audio, if (audio.abr > 0) audio.abr else audio.tbr)
        if (audioSize > 0) total += audioSize
    }
    return total
}

fun humanSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (i == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", value, units[i])
}

data class DownloadProgress(
    val progress: Float = 0f,
    val etaSec: Long = 0,
    val statusLine: String = ""
)

sealed interface DlUiState {
    data object Idle : DlUiState
    data object Initializing : DlUiState
    data class InitError(val message: String) : DlUiState
    data object Ready : DlUiState
    data object Fetching : DlUiState
    data class Fetched(val info: FetchedInfo) : DlUiState
    data class Downloading(val progress: DownloadProgress, val info: FetchedInfo? = null) : DlUiState
    data class Done(val fileName: String, val output: String) : DlUiState
    data class Error(val message: String) : DlUiState
}

enum class VideoQuality(val label: String, val maxHeight: Int) {
    BEST("Best", 0),
    Q2160("4K (2160p)", 2160),
    Q1440("1440p", 1440),
    Q1080("1080p", 1080),
    Q720("720p", 720),
    Q480("480p", 480),
    Q360("360p", 360),
    AUDIO_ONLY("Audio only", -1),
}

enum class VideoContainer(val label: String, val flag: String) {
    MP4("MP4", "mp4"),
    MKV("MKV", "mkv"),
    WEBM("WebM", "webm"),
    AUTO("Auto", ""),
}

enum class AudioFormat(val label: String, val flag: String) {
    MP3("MP3", "mp3"),
    M4A("M4A", "m4a"),
    OPUS("Opus", "opus"),
}

enum class SubtitleMode(val label: String) {
    OFF("Off"),
    EMBED_EN("Embed English"),
    WRITE_EN("English .srt file"),
    WRITE_ALL("All languages"),
}

data class DownloadOptions(
    val quality: VideoQuality = VideoQuality.BEST,
    val container: VideoContainer = VideoContainer.MP4,
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val subtitles: SubtitleMode = SubtitleMode.OFF,
    val playlist: Boolean = false,
    /** yt-dlp --playlist-items spec, e.g. "1-3,5". Blank = whole playlist. */
    val playlistItems: String = "",
    val embedThumbnail: Boolean = true,
    val addMetadata: Boolean = true,
    val sponsorBlock: Boolean = false,
    val restrictFilenames: Boolean = true,
    val subfolder: String = "",
    val customFlags: String = "",
    val sectionEnabled: Boolean = false,
    val sectionStart: String = "",
    val sectionEnd: String = "",
)

data class PlaylistItem(
    val index: Int,
    val id: String,
    val title: String,
    val duration: String = "",
    val url: String = "",
)

/** Compress sorted indices to yt-dlp --playlist-items spec, e.g. [1,2,3,5] -> "1-3,5". */
fun playlistSpec(indices: Collection<Int>): String {
    val sorted = indices.sorted()
    if (sorted.isEmpty()) return ""
    val parts = mutableListOf<String>()
    var start = sorted[0]
    var prev = sorted[0]
    for (i in 1..sorted.size) {
        val cur = sorted.getOrNull(i)
        if (cur == prev + 1) {
            prev = cur
        } else {
            parts.add(if (start == prev) "$start" else "$start-$prev")
            if (cur != null) { start = cur; prev = cur }
        }
    }
    return parts.joinToString(",")
}

enum class JobStatus { QUEUED, ACTIVE, DONE, FAILED }

data class QueuedJob(
    val id: String,
    val url: String,
    val title: String,
    val opts: DownloadOptions,
    val customFolder: String?,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Float = 0f,
    val note: String = "",
)

fun presetFormats(): List<FormatOption> = listOf(
    FormatOption("best", "Best (auto merge mp4)", "mp4"),
    FormatOption("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best", "Best mp4 ≤1080p", "mp4"),
    FormatOption("bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[height<=720]", "720p mp4", "mp4"),
    FormatOption("bestvideo[height<=480]+bestaudio/best[height<=480]/best", "480p", "mp4"),
    FormatOption("bestaudio/best", "Audio only (m4a/opus)", "m4a"),
    FormatOption("bestaudio --extract-audio --audio-format mp3", "MP3 audio", "mp3"),
)

/** Build a yt-dlp -f selector from quality + container. */
fun DownloadOptions.formatSelector(): String {
    if (quality == VideoQuality.AUDIO_ONLY) return "bestaudio/best"
    if (quality == VideoQuality.BEST) {
        return if (container == VideoContainer.AUTO) "bestvideo+bestaudio/best"
        else "bestvideo+bestaudio/best"
    }
    val h = quality.maxHeight
    val ext = container.flag
    return if (ext.isBlank()) {
        "bestvideo[height<=$h]+bestaudio/best[height<=$h]/best"
    } else {
        "bestvideo[height<=$h][ext=$ext]+bestaudio/best[height<=$h][ext=$ext]/best[height<=$h]/best"
    }
}

