package org.opensourcecommunity.ydroid.downloader

data class FormatOption(
    val id: String,
    val label: String,
    val ext: String = "",
    val note: String = ""
)

data class FetchedInfo(
    val title: String,
    val uploader: String = "",
    val durationSec: Long = 0,
    val thumbnailUrl: String = "",
    val webpageUrl: String = "",
    val formats: List<FormatOption> = emptyList()
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
    val quality: VideoQuality = VideoQuality.Q720,
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

