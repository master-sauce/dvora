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
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The running yt-dlp download's status — written by the foreground service,
 * polled by the browser UI (toolbar badge / status row / result dialogs).
 */
object YtCtl {
    @Volatile
    var pct: Int = -1              // -1 idle · 0..99 running · >=100 done · -3 failed

    @Volatile
    var url: String = ""

    @Volatile
    var dir: String = ""

    @Volatile
    var err: String? = null

    @Volatile
    var last: File? = null         // newest merged file after a success

    fun reset() {
        pct = -1; url = ""; dir = ""; err = null; last = null
    }

    /** shared cancel path — the in-app row and the notification action both come here. */
    fun cancel(ctx: Context) {
        try {
            YoutubeDL.getInstance().destroyProcessById(YtDownloadService.PROC)
        } catch (_: Exception) {
        }
        pct = -1; err = null; last = null
        ctx.stopService(Intent(ctx, YtDownloadService::class.java))
    }
}

/**
 * The download runs in a foreground service — it keeps going when the app is
 * backgrounded or closed. The notification is swipe-proof (re-posted on task
 * removal) and closes only via its cancel action or when the file landed;
 * pressing the finished notification opens the folder the media was saved in.
 */
class YtDownloadService : Service() {

    companion object {
        const val PROC = "dvora-media"
        private const val CHANNEL = "yt_media"
        private const val ID = 4241
        const val EXTRA_URL = "yt_url"
        const val EXTRA_DIR = "yt_dir"
        const val EXTRA_NAME = "yt_name"

        fun start(ctx: Context, url: String, dir: File, name: String? = null) {
            try {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, YtDownloadService::class.java)
                        .putExtra(EXTRA_URL, url)
                        .putExtra(EXTRA_DIR, dir.path)
                        .putExtra(EXTRA_NAME, name ?: "")
                )
            } catch (_: Exception) {
            }
        }
    }

    private var job: Job? = null
    private var last: Notification? = null
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val tail = StringBuilder()     // rolling yt-dlp output — failure text

    private fun note(line: String?) {
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

    /** content of the finished notification — opens the folder the media was saved to. */
    private fun folderIntent(dir: File): PendingIntent {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(dir), "resource/folder")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (view.resolveActivity(packageManager) != null) {
            PendingIntent.getActivity(
                applicationContext,
                3,
                view,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            appIntent()
        }
    }

    private fun cancelPi(): PendingIntent = PendingIntent.getBroadcast(
        applicationContext,
        2,
        Intent(applicationContext, YtCancelReceiver::class.java),
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
            stopSelf(); return START_NOT_STICKY
        }
        val name = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }
        val dir = File(intent.getStringExtra(EXTRA_DIR) ?: Environment.getExternalStorageDirectory().path)
        if (!dir.exists() && !dir.mkdirs()) {
            stopSelf(); return START_NOT_STICKY
        }
        job?.cancel()
        tail.setLength(0)
        YtCtl.url = url
        YtCtl.dir = dir.path
        YtCtl.pct = 0
        YtCtl.err = null
        YtCtl.last = null
        val clean = url.substringBefore('#')
        job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val req = YoutubeDLRequest(clean)
                // saved file takes the caller's window title — yt-dlp's own %title% only when none came in
                req.addOption("-o", "${dir.path}/${name ?: "%(title)s"}.%(ext)s")
                // best video + separate best audio when offered, else single best — merged to one mp4 by ffmpeg
                req.addOption("-f", "bv*+ba/b")
                req.addOption("--merge-output-format", "mp4")
                // stale yt-dlp from a previously crashed / cancelled run under our fixed id
                try {
                    YoutubeDL.getInstance().destroyProcessById(PROC)
                } catch (_: Exception) {
                }
                note("yt-dlp $clean")
                val started = System.currentTimeMillis()
                var stepped = -5
                YoutubeDL.execute(req, PROC) { p, _, line ->
                    note(line)
                    val n = p.toInt()
                    if (n in 0..99) {
                        YtCtl.pct = n
                        if (n / 5 != stepped / 5) {      // refresh the notification per 5%
                            stepped = n
                            prog(n, clean)
                        }
                    }
                }
                // the process ended — did a fresh file land in the folder?
                val newest = dir.listFiles()?.filter { it.isFile }
                    ?.maxByOrNull { it.lastModified() }?.takeIf { it.lastModified() >= started }
                if (newest != null) {
                    YtCtl.pct = 100
                    YtCtl.last = newest
                    done(newest.name, dir)
                } else {
                    fail(clean)
                }
            } catch (e: Exception) {
                note("EXCEPTION: ${(e.message ?: e)}")
                // a user cancel already reset the state — no failure then
                if (YtCtl.pct >= 0) fail(clean)
            }
        }
        fg(prog(0, clean))
        return START_NOT_STICKY
    }

    private fun prog(pct: Int, url: String): Notification {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle(getString(R.string.yt_down))
            .setContentText("${pct}% · ${url.take(110)}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct.coerceIn(0, 99), false)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_revert,
                    getString(R.string.yt_cancel),
                    cancelPi()
                ).build()
            )
            .setContentIntent(appIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        last = n
        if (can()) {
            try {
                nm.notify(ID, n)
            } catch (_: Exception) {
            }
        }
        return n
    }

    private fun fg(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            } else if (Build.VERSION.SDK_INT >= 26) {
                startForeground(ID, n)
            }
        } catch (_: Exception) {
        }
    }

    private fun done(name: String, dir: File) {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle(getString(R.string.yt_done))
            .setContentText(name)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, 100, false)
            .setAutoCancel(true)
            .setContentIntent(folderIntent(dir))     // pressing it → the folder with the saved media
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        last = n
        if (can()) {
            try {
                nm.notify(ID, n)
            } catch (_: Exception) {
            }
        }
        stopSelf()     // finished — the notification stays; only cancel / done may close it
    }

    private fun fail(url: String) {
        val msg = "${tail.toString().takeLast(1500)}".take(2000)
        YtCtl.pct = -3
        YtCtl.err = msg
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle(getString(R.string.yt_err_title))
            .setContentText(url.take(140))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setAutoCancel(true)
            .setContentIntent(appIntent())
            .build()
        last = n
        if (can()) {
            try {
                nm.notify(ID, n)
            } catch (_: Exception) {
            }
        }
        stopSelf()
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
        job?.cancel()
        try {
            YoutubeDL.getInstance().destroyProcessById(PROC)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
