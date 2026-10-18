package com.dvora.dvora20

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The running yt-dlp downloads — up to [MAX] concurrent jobs, each with its own
 * process id. The foreground service writes progress here; the browser UI polls
 * it for the toolbar badge / status row / done + fail dialogs. Finished files
 * and failures queue for the UI so a multi-download batch never loses one.
 */
object YtCtl {

    /** one download — registered by startYt before the service starts, keyed by its yt-dlp process id. */
    class Job(val url: String, val dir: String, val proc: String, val baseName: String?, val noteId: Int) {
        @Volatile
        var pct: Int = 0        // 0..99 running · >=100 done · -3 failed · -1 cancelled
        @Volatile
        var cancelled: Boolean = false   // checked by the native engine's loops mid-download

        /** when the job was registered — cancel only sweeps files newer than this */
        val startedAt: Long = System.currentTimeMillis()
    }

    const val MAX = 4           // concurrent yt-dlp processes

    private val lock = Any()
    private val jobs = LinkedHashMap<String, Job>()      // proc → job, insertion order
    private val doneQ = ArrayList<File>()                // saved files awaiting their done dialog
    private val errQ = ArrayList<Pair<String, String>>() // (url, failure text) awaiting their dialog
    private var seq = 0

    /**
     * Registers the next download and resolves a collision-free output base name
     * (two videos of one page share its window title → "title-2"). Null when
     * [MAX] jobs are already running.
     */
    fun add(url: String, dir: File, name: String?): Job? = synchronized(lock) {
        if (jobs.values.count { it.pct in 0..99 } >= MAX) return null
        prune()
        val proc = "dvora-media-${++seq}"
        val j = Job(url, dir.path, proc, uniqueName(dir, name), 4242 + (seq % 500))
        jobs[proc] = j
        j
    }

    /** true when this exact url is already downloading — the caller surfaces that instead of double-queueing. */
    fun contains(url: String): Boolean =
        synchronized(lock) { jobs.values.any { it.url == url && it.pct in 0..99 } }

    fun byProc(proc: String): Job? = synchronized(lock) { jobs[proc] }

    /** how many downloads run right now. */
    val count: Int get() = synchronized(lock) { jobs.values.count { it.pct in 0..99 } }

    /** every running job — the summary notification builds one line per job. */
    fun running(): List<Job> = synchronized(lock) { jobs.values.filter { it.pct in 0..99 } }

    /** progress of the first running job; -1 when nothing runs. */
    val pct: Int get() = synchronized(lock) { jobs.values.firstOrNull { it.pct in 0..99 }?.pct ?: -1 }

    /** url of the first running job. */
    val url: String get() = synchronized(lock) { jobs.values.firstOrNull { it.pct in 0..99 }?.url ?: "" }

    // ── done / fail queues — the UI shows one dialog at a time, order survives batches ──
    fun pushDone(f: File) = synchronized(lock) { doneQ.add(f) }
    fun popDone(): File? = synchronized(lock) { if (doneQ.isEmpty()) null else doneQ.removeAt(0) }
    fun pushErr(url: String, msg: String) = synchronized(lock) { errQ.add(url to msg) }
    fun popErr(): Pair<String, String>? = synchronized(lock) { if (errQ.isEmpty()) null else errQ.removeAt(0) }

    /** drop finished jobs so the registry doesn't grow across a long session. */
    private fun prune() {
        val fin = jobs.values.filter { it.pct !in 0..99 }
        if (fin.size > 8) for (j in fin.take(fin.size - 8)) jobs.remove(j.proc)
    }

    /** collision-free base name — deduped against running jobs and files already on disk. */
    private fun uniqueName(dir: File, name: String?): String? {
        if (name.isNullOrBlank()) return null
        val taken = jobs.values.mapNotNull { it.baseName }
        val onDisk = runCatching { dir.listFiles() ?: emptyArray() }.getOrDefault(emptyArray())
        var base = name
        var i = 2
        while (taken.any { it == base } || onDisk.any { it.name == base || it.name.startsWith("$base.") }) {
            base = "$name-$i"; i++
        }
        return base
    }

    /** shared cancel path — the in-app row and the notification action both come here. One job or all. */
    fun cancel(ctx: Context, job: Job? = null) {
        val targets = synchronized(lock) {
            if (job == null) jobs.values.toList().also { jobs.clear() }
            else listOfNotNull(jobs.remove(job.proc))
        }
        for (t in targets) {
            t.cancelled = true                    // native engine loops abort mid-download
            try {
                YoutubeDL.getInstance().destroyProcessById(t.proc)
            } catch (_: Exception) {
            }
            t.pct = -1
            cleanJob(t)                            // the dead process' partials leave with it
        }
        if (count == 0) ctx.stopService(Intent(ctx, YtDownloadService::class.java))
    }

    /**
     * Deletes the cancelled job's OWN partial files (.part / .ytdl / pre-merge
     * `.f###.` halves). The process is already dead so nothing races the
     * delete — the 60s-guarded stale sweep never catches these because they
     * were touched seconds ago. %title% jobs (no caller base name) are left
     * for that stale sweep — their file names are unknown here.
     */
    private fun cleanJob(t: Job) {
        val base = t.baseName ?: return
        runCatching {
            File(t.dir).listFiles()?.forEach { f ->
                val n = f.name
                val temp = n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".tmp") ||
                    n.endsWith(".dvoraPart") || Regex("""\.f\d+\.[A-Za-z0-9]+$""").containsMatchIn(n)
                if (f.isFile && temp && n.startsWith("$base.") && f.lastModified() >= t.startedAt - 5_000) {
                    runCatching { f.delete() }
                }
            }
        }
    }
}

/**
 * The downloads run in a foreground service — they keep going when the app is
 * backgrounded or closed. Every start command ADDS one more concurrent job (up
 * to [YtCtl.MAX]), each with its own coroutine + yt-dlp process id. One
 * swipe-proof summary notification (re-posted on task removal) tracks the whole
 * batch and closes only via its cancel action or after the last job landed;
 * pressing the finished notification opens the folder the media was saved in.
 */
class YtDownloadService : Service() {

    companion object {
        private const val CHANNEL = "yt_media"
        private const val ID = 4241           // the single foreground notification — the batch summary
        const val EXTRA_URL = "yt_url"
        const val EXTRA_DIR = "yt_dir"
        const val EXTRA_NAME = "yt_name"
        const val EXTRA_REFERER = "yt_referer"
        const val EXTRA_UA = "yt_ua"
        const val EXTRA_COOKIE = "yt_cookie"
        const val EXTRA_PROC = "yt_proc"
        const val EXTRA_HEADERS = "yt_headers"

        fun start(
            ctx: Context, url: String, dir: File, name: String? = null,
            referer: String? = null, userAgent: String? = null, cookie: String? = null,
            proc: String, headersCsv: String? = null
        ) {
            try {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, YtDownloadService::class.java)
                        .putExtra(EXTRA_URL, url)
                        .putExtra(EXTRA_DIR, dir.path)
                        .putExtra(EXTRA_NAME, name ?: "")
                        .putExtra(EXTRA_REFERER, referer ?: "")
                        .putExtra(EXTRA_UA, userAgent ?: "")
                        .putExtra(EXTRA_COOKIE, cookie ?: "")
                        .putExtra(EXTRA_PROC, proc)
                        .putExtra(EXTRA_HEADERS, headersCsv ?: "")
                )
            } catch (_: Exception) {
            }
        }

        /** "k: v" lines → map (the page's exact request headers, 1DM's urlHeadersCache). */
        fun parseHeaders(csv: String?): Map<String, String>? {
            if (csv.isNullOrBlank()) return null
            val m = HashMap<String, String>()
            for (line in csv.lineSequence()) {
                val i = line.indexOf(':')
                if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }
            return m.ifEmpty { null }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var last: Notification? = null
    private var lastDir: File? = null          // folder of the newest job — the finished notification's target
    private var saved = 0                      // files landed in this batch — picks the final done/fail face
    private var failed = 0
    private val finishing = AtomicBoolean(false)   // guard: only the first "last job ended" posts the final note
    private val noteIds: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()   // per-job notification ids
    private var perJobPosted = 0               // done/fail notes already posted per job — no batch-wide duplicates
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    private fun note(tail: StringBuilder, line: String?) {
        if (line.isNullOrBlank()) return
        tail.append(line).append('\n')
        if (tail.length > 6000) tail.delete(0, tail.indexOf('\n').takeIf { it > 0 } ?: 0)
        Log.i("dvora-yt", line.takeLast(400))
    }

    private fun appIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        1,
        Intent(applicationContext, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** content of the finished notification — opens the folder in the system Files app. */
    private fun folderIntent(dir: File): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        3,
        folderOpenIntent(applicationContext, dir),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** the job's own cancel action — carries its process id; empty = cancel all (the summary's button). */
    private fun cancelPi(proc: String): PendingIntent = PendingIntent.getBroadcast(
        applicationContext,
        if (proc.isEmpty()) 2 else proc.hashCode(),
        Intent(applicationContext, YtCancelReceiver::class.java).putExtra("proc", proc),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun can(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.yt_down), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: run {
            if (YtCtl.count == 0) stopSelf()
            return START_NOT_STICKY
        }
        val name = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }
        val referer = intent.getStringExtra(EXTRA_REFERER)?.takeIf { it.isNotBlank() }
        val userAgent = intent.getStringExtra(EXTRA_UA)?.takeIf { it.isNotBlank() }
        val cookie = intent.getStringExtra(EXTRA_COOKIE)?.takeIf { it.isNotBlank() }
        val dir = File(intent.getStringExtra(EXTRA_DIR) ?: Environment.getExternalStorageDirectory().path)
        if (!dir.exists() && !dir.mkdirs()) {
            if (YtCtl.count == 0) stopSelf()
            return START_NOT_STICKY
        }
        val proc = intent.getStringExtra(EXTRA_PROC)?.takeIf { it.isNotBlank() }
            ?: "dvora-media-orphan-$startId"
        val headers = parseHeaders(intent.getStringExtra(EXTRA_HEADERS))
        lastDir = dir
        finishing.set(false)                   // a fresh job joined — the batch continues
        // the job was registered by startYt; null only when the OS restarted the service — run it standalone
        launchJob(YtCtl.byProc(proc), url.substringBefore('#'), name, dir, referer, userAgent, cookie, proc, headers)
        // each download is its own notification — the job's note becomes the foreground one;
        // a service restart orphan gets a bare placeholder that finishBatch replaces
        val j = YtCtl.byProc(proc)
        if (j != null) fg(j.noteId, jobNote(j, name))
        else fg(
            ID,
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setContentTitle(getString(R.string.yt_down))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build()
        )
        return START_NOT_STICKY
    }

    /** one download — its own coroutine + its own yt-dlp process id, running alongside the others. */
    private fun launchJob(
        job: YtCtl.Job?, clean: String, name: String?, dir: File,
        referer: String?, userAgent: String?, cookie: String?, proc: String,
        headers: Map<String, String>?
    ) {
        // the caller-resolved collision-free base name wins; orphans fall back to the raw intent name
        val base = job?.baseName ?: name
        scope.launch {
            val tail = StringBuilder()
            try {
                val req = YoutubeDLRequest(clean)
                // saved file takes the caller's window title — yt-dlp's own %title% only when none came in
                req.addOption("-o", "${dir.path}/${base ?: "%(title)s"}.%(ext)s")
                // best video + separate best audio when offered, else single best — merged to one mp4 by ffmpeg
                req.addOption("-f", "bv*+ba/b")
                req.addOption("--merge-output-format", "mp4")
                // 1DM-style header replay — many CDNs 403 without the page's own referer / cookies / UA.
                // The page's cached Referer (the one the WebView actually sent and the server
                // accepted — hotlink gates check it) beats the guessed page referer.
                val effReferer = headers?.get("Referer")?.takeIf { it.isNotBlank() } ?: referer
                effReferer?.let { req.addOption("--referer", it) }
                userAgent?.let { req.addOption("--add-header", "User-Agent:$it") }
                cookie?.let { req.addOption("--add-header", "Cookie:$it") }
                // the page's EXACT request headers (urlHeadersCache) — a probe/engine must look
                // exactly like the page's own request; skip the ones set explicitly above
                headers?.forEach { (k, v) ->
                    if (!k.equals("Range", true) && !k.equals("Host", true) && v.isNotEmpty() &&
                        !k.equals("User-Agent", true) && !k.equals("Referer", true) && !k.equals("Cookie", true) &&
                        !k.equals("Content-Length", true)
                    ) req.addOption("--add-header", "$k:$v")
                }
                note(tail, "yt-dlp $clean")
                val started = System.currentTimeMillis()
                var stepped = -5
                // yt-dlp's callback % is PER STREAM — with bv*+ba the video runs 0→100
                // and the audio then RESETS to 0, so a raw pass-through renders a bar that
                // falls backwards mid-job. Parse each stream's "x% of N MiB" line and
                // weight the streams by their byte sizes → a monotone overall %.
                var doneBytes = 0.0          // streams fully landed
                var curBytes = 0.0          // the stream downloading now
                var allBytes = 0.0          // every stream this job has seen
                var lastPct = -1f
                val dlRe = Regex("""\[download\]\s+([\d.]+)%\s+of\s+~?([\d.]+)\s*(KiB|MiB|GiB|TiB)""")
                fun unit(u: String): Double = when (u) {
                    "KiB" -> 1024.0
                    "MiB" -> 1024.0 * 1024
                    "GiB" -> 1024.0 * 1024 * 1024
                    else -> 1024.0 * 1024 * 1024 * 1024
                }
                YoutubeDL.execute(req, proc) { p, _, line ->
                    note(tail, line)
                    var n = p.toInt()
                    val m = dlRe.find(line)
                    if (m != null) {
                        val iPct = m.groupValues[1].toFloatOrNull() ?: -1f
                        val iBytes = m.groupValues[2].toDoubleOrNull()?.let { it * unit(m.groupValues[3]) } ?: -1.0
                        if (iPct >= 0 && iBytes > 0) {
                            if (allBytes == 0.0) {                    // the first stream
                                curBytes = iBytes; allBytes = iBytes
                            } else if (iPct < lastPct - 2f) {          // % reset → the next stream began
                                doneBytes += curBytes; curBytes = iBytes; allBytes += iBytes
                            } else if (iBytes != curBytes) {          // mid-stream size-estimate refresh
                                allBytes += iBytes - curBytes; curBytes = iBytes
                            }
                            lastPct = iPct
                            n = ((doneBytes + iPct / 100.0 * curBytes) / allBytes * 100).toInt()
                        }
                    }
                    if (n in 0..99) {
                        job?.pct = n
                        if (n / 5 != stepped / 5) {      // refresh the job's own notification per 5%
                            stepped = n
                            job?.let { postJob(it, base) }
                        }
                    }
                }
                // the process ended — did a fresh file land? yt-dlp partial artifacts are ignored and
                // this job's own base name is preferred (concurrent jobs share the folder)
                val fresh = runCatching { dir.listFiles() ?: emptyArray() }.getOrDefault(emptyArray()).filter {
                    it.isFile && it.lastModified() >= started &&
                        !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".tmp")
                }
                val newest = base?.let { b -> fresh.firstOrNull { it.name.startsWith("$b.") } }
                    ?: fresh.maxByOrNull { it.lastModified() }
                if (newest != null) {
                    job?.pct = 100
                    saved++
                    YtCtl.pushDone(newest)
                    job?.let { jobDone(it, newest.name, dir) }
                } else {
                    // yt-dlp landed nothing — the native engine (1DM-style) gets its shot
                    note(tail, "yt-dlp empty → native engine")
                    val fb = nativeFallback(job, clean, dir, base, referer, userAgent, cookie, headers)
                    if (fb != null) {
                        job?.pct = 100
                        saved++
                        YtCtl.pushDone(fb)
                        job?.let { jobDone(it, fb.name, dir) }
                    } else fail(job, clean, tail)
                }
            } catch (e: Exception) {
                note(tail, "EXCEPTION: ${(e.message ?: e)}")
                // a user cancel already reset the state — no failure then
                if ((job?.pct ?: 0) in 0..99) {
                    note(tail, "yt-dlp failed → native engine")
                    val fb = nativeFallback(job, clean, dir, base, referer, userAgent, cookie, headers)
                    if (fb != null) {
                        job?.pct = 100
                        saved++
                        YtCtl.pushDone(fb)
                        job?.let { jobDone(it, fb.name, dir) }
                    } else fail(job, clean, tail)
                }
            }
            if (YtCtl.count == 0) finishBatch()
        }
    }

    private fun fail(job: YtCtl.Job?, url: String, tail: StringBuilder) {
        job?.pct = -3
        failed++
        YtCtl.pushErr(url, tail.toString().takeLast(1500).take(2000))
        // the job's own failure note — stays until tapped (auto-cancel)
        if (job != null) {
            perJobPosted++
            noteIds.remove(job.noteId)
            if (can()) {
                runCatching {
                    nm.notify(
                        job.noteId,
                        NotificationCompat.Builder(applicationContext, CHANNEL)
                            .setContentTitle(getString(R.string.yt_err_title))
                            .setContentText(url.take(140))
                            .setSmallIcon(android.R.drawable.stat_sys_download)
                            .setAutoCancel(true)
                            .setContentIntent(appIntent())
                            .build()
                    )
                }
            }
        }
    }

    /**
     * yt-dlp failed — the 1DM-style native engine gets its shot: multi-thread
     * ranged download for direct files, playlist walk + AES-128 segment assembly
     * for HLS. Progress flows into the job + summary notification like yt-dlp's.
     */
    private fun nativeFallback(
        job: YtCtl.Job?, url: String, dir: File, base: String?,
        referer: String?, userAgent: String?, cookie: String?, headers: Map<String, String>?
    ): File? {
        if (job?.pct == -1) return null                 // the user already cancelled
        var stepped = -5
        return runCatching {
            NativeDl.download(
                url, dir, base ?: "dvora-media", referer, userAgent, cookie, headers,
                { job?.cancelled == true }              // per-job cancel aborts the loops mid-download
            ) { n ->
                job?.pct = n
                if (n / 5 != stepped / 5) {             // refresh the job's own notification per 5%
                    stepped = n
                    job?.let { postJob(it, base) }
                }
            }
        }.getOrNull()
    }

    private fun post(n: Notification) {
        last = n
        if (can()) {
            try {
                nm.notify(ID, n)
            } catch (_: Exception) {
            }
        }
    }

    /** one notification PER download — its own progress bar + its own cancel action. */
    private fun jobNote(job: YtCtl.Job, name: String?): Notification =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle(name?.takeIf { it.isNotBlank() } ?: getString(R.string.yt_down))
            .setContentText("${job.pct.coerceIn(0, 99)}% · ${job.url.take(90)}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, job.pct.coerceIn(0, 99), false)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_revert,
                    getString(R.string.yt_cancel),
                    cancelPi(job.proc)
                ).build()
            )
            .setContentIntent(appIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun postJob(job: YtCtl.Job, name: String?) {
        noteIds.add(job.noteId)
        if (can()) {
            try {
                val n = jobNote(job, name)
                last = n
                nm.notify(job.noteId, n)
            } catch (_: Exception) {
            }
        }
    }

    /** the job's own success notification — tap it → the folder in the Files app. */
    private fun jobDone(job: YtCtl.Job, name: String, dir: File) {
        perJobPosted++
        noteIds.remove(job.noteId)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle(getString(R.string.yt_done))
            .setContentText(name)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, 100, false)
            .setAutoCancel(true)
            .setContentIntent(folderIntent(dir))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        if (can()) {
            try {
                nm.notify(job.noteId, n)
            } catch (_: Exception) {
            }
        }
    }

    private var fgId = ID          // the notification id currently bound to the foreground service

    /** the job's own notification IS the foreground one — no separate summary exists. */
    private fun fg(id: Int, n: Notification) {
        fgId = id
        last = n
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            } else if (Build.VERSION.SDK_INT >= 26) {
                startForeground(id, n)
            }
        } catch (_: Exception) {
        }
    }

    /** the batch's last job ended — sweep temp files, swap the foreground note, release. */
    private fun finishBatch() {
        if (!finishing.compareAndSet(false, true)) {
            stopSelf()
            return
        }
        val dir = lastDir ?: File(Environment.getExternalStorageDirectory(), "Download/dvora")
        // DETACH, not REMOVE — the fg-bound note is the job's own DONE note by now;
        // removing it would yank the finished notification away
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        // leftover progress notes of cancelled jobs die here; done/fail notes manage their own ids
        noteIds.forEach { runCatching { nm.cancel(it) } }
        noteIds.clear()
        // stale temp sweep FIRST (its transient note uses ID, then cancels it) —
        // .dvoraPart leftovers + yt-dlp partials; files touched <60s ago are never raced
        cleanTemp(dir)
        // every job already posted its own done/fail note — a batch-wide one would
        // just duplicate them in the shade; only orphan batches (service restart) need it
        if (saved > 0 || failed > 0) {
            if (perJobPosted == 0) {
                val n = if (saved > 0) {
                    NotificationCompat.Builder(applicationContext, CHANNEL)
                        .setContentTitle(getString(R.string.yt_done))
                        .setContentText(dir.name)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setProgress(100, 100, false)
                        .setAutoCancel(true)
                        .setContentIntent(folderIntent(dir))     // pressing it → the folder with the saved media
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .build()
                } else {
                    NotificationCompat.Builder(applicationContext, CHANNEL)
                        .setContentTitle(getString(R.string.yt_err_title))
                        .setContentText(getString(R.string.yt_down))
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setAutoCancel(true)
                        .setContentIntent(appIntent())
                        .build()
                }
                post(n)
            }
        }
        // a pure-cancel batch posts nothing — the user already saw the cancelled toast
        saved = 0
        failed = 0
        perJobPosted = 0
        stopSelf()     // finished — the notification stays; only cancel / done may close it
    }

    /**
     * Sweeps the save dir for stale temp files — `.dvoraPart` parts, yt-dlp `.part`
     * partials, `.ytdl` fragments, orphan `.f###.ext` pre-merge halves — under a
     * short "deleting temp files" note. Files touched <60s ago are skipped so an
     * active download is never raced.
     */
    private fun cleanTemp(dir: File) {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle(getString(R.string.yt_cleaning))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        if (can()) runCatching { nm.notify(ID, n) }
        try {
            val now = System.currentTimeMillis()
            runCatching { dir.listFiles() }.getOrDefault(emptyArray())
                .filter { f ->
                    val name = f.name
                    (name.endsWith(".dvoraPart") || name.endsWith(".part") || name.endsWith(".ytdl") ||
                        name.endsWith(".tmp") || Regex("""\.f\d+\.[A-Za-z0-9]+$""").containsMatchIn(name)) &&
                        f.lastModified() < now - 60_000
                }
                .forEach { runCatching { it.delete() } }
            Thread.sleep(1500)          // let the note read for a beat before it vanishes
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
        } finally {
            runCatching { nm.cancel(ID) }
        }
    }

    /** swipe-proof progress — the notification comes straight back on the swipe. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val n = last ?: return
        fun repost() {
            if (!can()) return
            runCatching { nm.notify(ID, n) }
        }
        repost()                               // immediately
        // rare race: service still processing the swipe → one short retry
        if (!runCatching { nm.activeNotifications.any { it.id == ID } }.getOrDefault(false))
            Handler(Looper.getMainLooper()).postDelayed({ repost() }, 150)
    }

    override fun onDestroy() {
        scope.cancel()
        for (j in YtCtl.running()) {
            try {
                YoutubeDL.getInstance().destroyProcessById(j.proc)
            } catch (_: Exception) {
            }
        }
        // a cancelled batch leaves the fg-bound progress note stale — kill it + the
        // leftovers; a FINISHED batch detached its notes, they manage themselves
        if (!finishing.get()) {
            runCatching { nm.cancel(fgId) }
            noteIds.forEach { runCatching { nm.cancel(it) } }
            noteIds.clear()
        }
        super.onDestroy()
    }
}
