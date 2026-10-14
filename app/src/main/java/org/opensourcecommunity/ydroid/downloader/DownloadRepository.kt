package org.opensourcecommunity.ydroid.downloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

sealed interface ActiveDownload {
    data object Idle : ActiveDownload
    data class Running(val url: String, val title: String, val progress: Float, val status: String) : ActiveDownload
    data class Done(val fileName: String, val status: String) : ActiveDownload
    data class Failed(val message: String) : ActiveDownload
    data class Stopped(val message: String = "Stopped — tap Download to resume") : ActiveDownload
}

/**
 * App-wide downloads. Jobs run sequentially on a private scope (not a screen
 * scope) inside a foreground service, so leaving the app doesn't cancel.
 * Partial .part files + --continue make every job resumable; the queue is
 * persisted so a killed process picks up where it left off.
 */
object DownloadRepository {
    private const val PREFS = "ydroid_download"
    private const val KEY_QUEUE = "queue_v1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<ActiveDownload>(ActiveDownload.Idle)
    val state: StateFlow<ActiveDownload> = _state

    private val _queue = MutableStateFlow(listOf<QueuedJob>())
    val queue: StateFlow<List<QueuedJob>> = _queue

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    @Volatile
    var running = false
        private set

    private var processor: Job? = null

    // ---------- public API ----------

    /** Enqueue (front if idle) and start processing. Home's Download button. */
    fun start(ctx: Context, url: String, opts: DownloadOptions, customFolder: Uri?, title: String = "") {
        val job = QueuedJob(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title.ifBlank { url },
            opts = opts,
            customFolder = customFolder?.toString()
        )
        val app = ctx.applicationContext
        _queue.value = listOf(job) + _queue.value
        persistQueue(app)
        _paused.value = false
        DownloadService.start(app, url)
        ensureProcessor(app)
    }

    fun stop(ctx: Context) {
        YdlManager.stop()
        _paused.value = true
        // current job falls back to QUEUED (resumable) in the processor loop
        try {
            ctx.applicationContext.stopService(Intent(ctx.applicationContext, DownloadService::class.java))
        } catch (_: Exception) { }
        main.post {
            running = false
            if (_state.value is ActiveDownload.Running) {
                _state.value = ActiveDownload.Stopped()
            }
        }
    }

    fun pauseAll() {
        _paused.value = true
    }

    fun resumeAll(ctx: Context) {
        if (_queue.value.none { it.status == JobStatus.QUEUED || it.status == JobStatus.ACTIVE }) return
        _paused.value = false
        DownloadService.start(ctx.applicationContext, _queue.value.firstOrNull()?.url ?: "")
        ensureProcessor(ctx.applicationContext)
    }

    fun remove(id: String, ctx: Context) {
        val job = _queue.value.find { it.id == id } ?: return
        if (job.status == JobStatus.ACTIVE) {
            YdlManager.stop()
        }
        _queue.value = _queue.value.filterNot { it.id == id }
        persistQueue(ctx.applicationContext)
    }

    fun retry(id: String, ctx: Context) {
        _queue.value = _queue.value.map {
            if (it.id == id) it.copy(status = JobStatus.QUEUED, progress = 0f, note = "") else it
        }
        persistQueue(ctx.applicationContext)
        _paused.value = false
        ensureProcessor(ctx.applicationContext)
    }

    fun clearFinished(ctx: Context) {
        _queue.value = _queue.value.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.ACTIVE }
        persistQueue(ctx.applicationContext)
    }

    fun clearAll(ctx: Context) {
        YdlManager.stop()
        processor?.cancel()
        processor = null
        running = false
        _queue.value = emptyList()
        persistQueue(ctx.applicationContext)
        _state.value = ActiveDownload.Idle
        try {
            ctx.applicationContext.stopService(Intent(ctx.applicationContext, DownloadService::class.java))
        } catch (_: Exception) { }
    }

    /** Called by the service when the process was killed and restarted. */
    fun resumeIfPersisted(ctx: Context, onNoJob: () -> Unit) {
        loadQueue(ctx.applicationContext)
        val pending = _queue.value.any { it.status == JobStatus.QUEUED || it.status == JobStatus.ACTIVE }
        if (!pending) {
            // legacy single-job spec from older builds
            if (hasLegacySpec(ctx.applicationContext)) {
                migrateLegacy(ctx.applicationContext)
            } else {
                onNoJob()
                return
            }
        }
        // ACTIVE at kill time goes back to QUEUED; --continue resumes .part
        _queue.value = _queue.value.map {
            if (it.status == JobStatus.ACTIVE) it.copy(status = JobStatus.QUEUED, progress = 0f) else it
        }
        _paused.value = false
        ensureProcessor(ctx.applicationContext)
    }

    fun hasPersistedJob(ctx: Context): Boolean {
        loadQueue(ctx.applicationContext)
        return _queue.value.any { it.status == JobStatus.QUEUED || it.status == JobStatus.ACTIVE } ||
            hasLegacySpec(ctx.applicationContext)
    }

    fun clearSpec(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) { }
    }

    // ---------- processor ----------

    private fun ensureProcessor(app: Context) {
        if (processor?.isActive == true) return
        processor = scope.launch {
            running = true
            try {
                while (true) {
                    if (_paused.value) {
                        running = false
                        return@launch
                    }
                    val next = _queue.value.firstOrNull { it.status == JobStatus.QUEUED }
                    if (next == null) {
                        running = false
                        main.post {
                            // keep last Done/Failed visible; Idle only if never ran
                            DownloadService.finish(app, "Queue complete")
                        }
                        persistQueue(app)
                        return@launch
                    }
                    runJob(app, next)
                }
            } finally {
                running = false
            }
        }
    }

    private suspend fun runJob(app: Context, job: QueuedJob) {
        setJob(job.id) { it.copy(status = JobStatus.ACTIVE, progress = 0f, note = "Starting…") }
        main.post {
            _state.value = ActiveDownload.Running(job.url, job.title, 0f, "Starting… [${positionOf(job.id)}]")
        }
        var cancelled = false
        val res = try {
            YdlManager.download(app, job.url, job.opts) { p ->
                main.post {
                    setJob(job.id) { it.copy(progress = p.progress / 100f, note = p.statusLine) }
                    _state.value = ActiveDownload.Running(
                        job.url, job.title, p.progress / 100f,
                        p.statusLine.ifBlank { "${p.progress.toInt()}%" } + " [${positionOf(job.id)}]"
                    )
                    DownloadService.updateProgress(app, p.progress, "${job.title.take(60)} — ${p.progress.toInt()}%")
                }
            }
        } catch (e: Exception) {
            cancelled = e is kotlinx.coroutines.CancellationException
            if (cancelled) Result.failure(YdlManager.CancelledException("stopped"))
            else Result.failure(e)
        }
        main.post {
            if (res.isSuccess) {
                val (newFiles, file, _) = res.getOrNull()!!
                val mediaExts = setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus", "wav", "aac")
                val media = newFiles.filter { mediaExts.contains(it.extension.lowercase()) }
                var m3uName: String? = null
                if (job.opts.playlist && media.size > 1) {
                    try {
                        val dir = file?.parentFile ?: YdlManager.downloadDir()
                        val m3u = YdlManager.writeM3u(dir, job.title.ifBlank { "playlist" }, media)
                        try {
                            android.media.MediaScannerConnection.scanFile(app, arrayOf(m3u.absolutePath), null, null)
                        } catch (_: Exception) { }
                        m3uName = m3u.name
                    } catch (_: Exception) { }
                }
                if (file != null) {
                    try {
                        android.media.MediaScannerConnection.scanFile(app, arrayOf(file.absolutePath), null, null)
                    } catch (_: Exception) { }
                    job.customFolder?.let { tree ->
                        try {
                            YdlManager.copyToTreeUri(app, file, Uri.parse(tree))
                        } catch (_: Exception) { }
                    }
                    val note = file.name + (if (m3uName != null) " + $m3uName" else "")
                    setJob(job.id) { it.copy(status = JobStatus.DONE, progress = 1f, note = note) }
                    _state.value = ActiveDownload.Done(file.name, "Saved → Downloads/Ydroid/${file.name}" + (if (m3uName != null) " + $m3uName" else "") + " [${positionOf(job.id)}]")
                } else {
                    setJob(job.id) { it.copy(status = JobStatus.DONE, progress = 1f, note = "done") }
                    _state.value = ActiveDownload.Done("", "Download complete")
                }
            } else {
                val ex = res.exceptionOrNull()
                if (ex is YdlManager.CancelledException || cancelled) {
                    // back to queue head, resumable
                    setJob(job.id) { it.copy(status = JobStatus.QUEUED, note = "paused — resumes") }
                    _state.value = ActiveDownload.Stopped()
                } else {
                    setJob(job.id) { it.copy(status = JobStatus.FAILED, note = ex?.message ?: "failed") }
                    _state.value = ActiveDownload.Failed("Download failed: ${ex?.message}")
                }
            }
            persistQueue(app)
        }
        // let the main.post above run before picking the next job
        kotlinx.coroutines.delay(300)
    }

    private fun positionOf(id: String): String {
        val pending = _queue.value.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.ACTIVE }
        val idx = pending.indexOfFirst { it.id == id }
        return if (idx < 0) "" else "${idx + 1}/${pending.size}"
    }

    private fun setJob(id: String, map: (QueuedJob) -> QueuedJob) {
        _queue.value = _queue.value.map { if (it.id == id) map(it) else it }
    }

    // ---------- persistence ----------

    private fun persistQueue(ctx: Context) {
        try {
            val arr = org.json.JSONArray()
            _queue.value.forEach { j ->
                val o = JSONObject()
                    .put("id", j.id)
                    .put("url", j.url)
                    .put("title", j.title)
                    .put("status", j.status.name)
                    .put("progress", j.progress.toDouble())
                    .put("note", j.note)
                    .put("tree", j.customFolder ?: "")
                val opts = JSONObject()
                    .put("quality", j.opts.quality.name)
                    .put("container", j.opts.container.name)
                    .put("audio", j.opts.audioFormat.name)
                    .put("subs", j.opts.subtitles.name)
                    .put("playlist", j.opts.playlist)
                    .put("playlistItems", j.opts.playlistItems)
                    .put("thumb", j.opts.embedThumbnail)
                    .put("meta", j.opts.addMetadata)
                    .put("sponsor", j.opts.sponsorBlock)
                    .put("restrict", j.opts.restrictFilenames)
                    .put("subfolder", j.opts.subfolder)
                    .put("flags", j.opts.customFlags)
                    .put("section", j.opts.sectionEnabled)
                    .put("secStart", j.opts.sectionStart)
                    .put("secEnd", j.opts.sectionEnd)
                o.put("opts", opts)
                arr.put(o)
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_QUEUE, arr.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun loadQueue(ctx: Context) {
        try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_QUEUE, null)
                ?: return
            if (_queue.value.isNotEmpty()) return // already loaded this process
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<QueuedJob>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val opts = o.optJSONObject("opts") ?: JSONObject()
                fun enumOf(name: String, fallback: String): String = opts.optString(name).ifBlank { fallback }
                list.add(
                    QueuedJob(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        url = o.optString("url"),
                        title = o.optString("title"),
                        opts = DownloadOptions(
                            quality = runCatching { VideoQuality.valueOf(enumOf("quality", "Q720")) }.getOrDefault(VideoQuality.Q720),
                            container = runCatching { VideoContainer.valueOf(enumOf("container", "MP4")) }.getOrDefault(VideoContainer.MP4),
                            audioFormat = runCatching { AudioFormat.valueOf(enumOf("audio", "MP3")) }.getOrDefault(AudioFormat.MP3),
                            subtitles = runCatching { SubtitleMode.valueOf(enumOf("subs", "OFF")) }.getOrDefault(SubtitleMode.OFF),
                            playlist = opts.optBoolean("playlist", false),
                            playlistItems = opts.optString("playlistItems"),
                            embedThumbnail = opts.optBoolean("thumb", true),
                            addMetadata = opts.optBoolean("meta", true),
                            sponsorBlock = opts.optBoolean("sponsor", false),
                            restrictFilenames = opts.optBoolean("restrict", true),
                            subfolder = opts.optString("subfolder"),
                            customFlags = opts.optString("flags"),
                            sectionEnabled = opts.optBoolean("section", false),
                            sectionStart = opts.optString("secStart"),
                            sectionEnd = opts.optString("secEnd"),
                        ),
                        customFolder = o.optString("tree").ifBlank { null },
                        status = runCatching { JobStatus.valueOf(o.optString("status")) }.getOrDefault(JobStatus.QUEUED)
                            .let { if (it == JobStatus.ACTIVE) JobStatus.QUEUED else it },
                        progress = o.optDouble("progress", 0.0).toFloat(),
                        note = o.optString("note"),
                    )
                )
            }
            _queue.value = list
        } catch (_: Exception) { }
    }

    // legacy single-job spec (pre-queue builds)
    private fun hasLegacySpec(ctx: Context): Boolean {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("url")
        } catch (_: Exception) { false }
    }

    private fun migrateLegacy(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val url = p.getString("url", null) ?: return
            val job = QueuedJob(
                id = UUID.randomUUID().toString(),
                url = url,
                title = url,
                opts = DownloadOptions(
                    quality = runCatching { VideoQuality.valueOf(p.getString("quality", null)!!) }.getOrDefault(VideoQuality.Q720),
                    container = runCatching { VideoContainer.valueOf(p.getString("container", null)!!) }.getOrDefault(VideoContainer.MP4),
                    audioFormat = runCatching { AudioFormat.valueOf(p.getString("audio", null)!!) }.getOrDefault(AudioFormat.MP3),
                    subtitles = runCatching { SubtitleMode.valueOf(p.getString("subs", null)!!) }.getOrDefault(SubtitleMode.OFF),
                    playlist = p.getBoolean("playlist", false),
                    playlistItems = p.getString("playlistItems", "") ?: "",
                    embedThumbnail = p.getBoolean("thumb", true),
                    addMetadata = p.getBoolean("meta", true),
                    sponsorBlock = p.getBoolean("sponsor", false),
                    restrictFilenames = p.getBoolean("restrict", true),
                    subfolder = p.getString("subfolder", "") ?: "",
                    customFlags = p.getString("flags", "") ?: "",
                    sectionEnabled = p.getBoolean("section", false),
                    sectionStart = p.getString("secStart", "") ?: "",
                    sectionEnd = p.getString("secEnd", "") ?: "",
                ),
                customFolder = p.getString("tree", null),
            )
            _queue.value = listOf(job)
            val ed = p.edit()
            ed.remove("url"); ed.remove("quality"); ed.remove("container"); ed.remove("audio")
            ed.remove("subs"); ed.remove("playlist"); ed.remove("playlistItems"); ed.remove("thumb")
            ed.remove("meta"); ed.remove("sponsor"); ed.remove("restrict"); ed.remove("subfolder")
            ed.remove("flags"); ed.remove("section"); ed.remove("secStart"); ed.remove("secEnd")
            ed.remove("tree")
            ed.apply()
            persistQueue(ctx)
        } catch (_: Exception) { }
    }
}
