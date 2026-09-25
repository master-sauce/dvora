package com.dvora.dvora20

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dvora.dvora20.adblock.BlockerChromeClient
import com.dvora.dvora20.adblock.BlockerEngine
import com.dvora.dvora20.adblock.BlockerWebViewClient
import com.dvora.dvora20.adblock.ListRepo
import com.dvora.dvora20.adblock.Media
import com.dvora.dvora20.adblock.Whitelist
import java.io.File
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** default page shown when the top-bar browser button is tapped. */
const val BROWSER_HOME = "https://duckduckgo.com/"

/**
 * Where yt-dlp stores captured media — the folder chosen in the browser tab
 * of the settings (download default, movies / dcim / music), each under a
 * `dvora` subfolder. Falls back to the app-private external files dir if
 * none of the public ones is writable.
 */
@Suppress("DEPRECATION")
fun ytSaveDir(context: Context): File? {
    val pick = context.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)
        .getString("yt_folder", "download") ?: "download"
    val env = when (pick) {
        "movies" -> Environment.DIRECTORY_MOVIES
        "dcim" -> Environment.DIRECTORY_DCIM
        "music" -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_DOWNLOADS
    }
    val dir = File(Environment.getExternalStoragePublicDirectory(env), "dvora")
    return dir.takeIf { d -> d.canWrite() || d.mkdirs() }
        ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
}

/**
 * The full-screen, in-app, bee-themed browser with the built-in
 * ad/tracker blocker: toolbar (history / reload / favicon+title / theme),
 * address bar with GO, load-progress indicator, shield button with a
 * per-page blocked-count badge, per-site whitelist panel, SSL user dialog,
 * `<input type="file">` picker, and DownloadManager downloads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    initialUrl: String,
    repo: ListRepo,
    onBack: () -> Unit,
    onToggleDark: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = LocalDarkMode.current.value
    val headerBg = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkComb)
    val pageBg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)
    val textColor = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val subColor = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.7f))

    // ── screen state ──────────────────────────────────────────────────────────
    var address by remember { mutableStateOf(initialUrl.ifBlank { BROWSER_HOME }) }
    var pageTitle by remember { mutableStateOf("") }
    var favicon by remember { mutableStateOf<Bitmap?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageHost by remember { mutableStateOf("") }
    var blocked by remember { mutableIntStateOf(0) }
    var blockLog by remember { mutableStateOf<List<BlockerEngine.BlockRecord>>(emptyList()) }
    var ssl by remember { mutableStateOf<Pair<Uri?, SslErrorHandler>?>(null) }
    var pendingChooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var sheetVisible by remember { mutableStateOf(false) }
    var crossNav by remember { mutableStateOf<Pair<String, String>?>(null) }   // (url, host) awaiting confirm
    var resTick by remember { mutableIntStateOf(0) }                           // bumps live while the panel is open
    var barVisible by remember { mutableStateOf(true) }                         // hide/show the bottom toolbar
    var hasStorage by remember { mutableStateOf(Build.VERSION.SDK_INT >= 29) }
    var video by remember { mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null) }   // fullscreen video overlay

    // the whole site's whitelist — kept live; the interceptor reads this supplier per request
    var allowed by remember { mutableStateOf(Whitelist.hosts(context)) }

    // ── yt-dlp: downloading of media captured on this page ────────────────────────
    var ytPicker by remember { mutableStateOf(false) }   // dialog listing capturable media
    var ytUrl by remember { mutableStateOf("") }         // what is downloading now — for the status row/notification
    var ytProg by remember { mutableIntStateOf(-1) }     // -1 = idle, else % progress
    val ytScope = rememberCoroutineScope()               // for the background yt-dlp download
    var ytPending by remember { mutableStateOf<String?>(null) }   // url to resume once the storage prompt is done
    var ytErr by remember { mutableStateOf<String?>(null) }      // surfaced yt-dlp failure reason (dialog)

    // ── one-time objects ──────────────────────────────────────────────────────
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setGeolocationEnabled(false)
            setBackgroundColor(if (isDark) 0xFF1C1500.toInt() else 0xFFFFFDE7.toInt())
        }
    }
    val client = remember { BlockerWebViewClient(context, repo) { allowed.toSet() } }
    val chrome = remember { BlockerChromeClient() }

    // ── launchers ─────────────────────────────────────────────────────────────
    val dlPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasStorage = granted
        if (!granted) ytPending = null                    // denied — forget the pending media retry
    }
    // asked once at the first download — without it only the in-app status row tracks yt-dlp
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = pendingChooser ?: return@rememberLauncherForActivityResult
        pendingChooser = null
        val data = if (result.resultCode == Activity.RESULT_OK) result.data else null
        if (data == null) {
            cb.onReceiveValue(null)
        } else {
            val picked = mutableListOf<Uri>()
            data.data?.let { picked.add(it) }
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { picked.add(it) }
            }
            cb.onReceiveValue(if (picked.isEmpty()) null else picked.toTypedArray())
        }
    }

    fun pickFile(params: WebChromeClient.FileChooserParams) {
        filePicker.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            val types = params.acceptTypes
            type = if (!types.isNullOrEmpty()) types.first() else "image/*"
            if (!types.isNullOrEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, types)
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        })
    }

    // ── yt-dlp ────────────────────────────────────────────────────────────────────

    /** run yt-dlp on [url], saving into the downloads dir — tracked by the in-app status row and a notification. */
    fun startYt(given: String) {
        if (ytProg >= 0) return
        val url = given.substringBefore('#')     // page hash fragments are noise for yt-dlp
        ytPicker = false
        ytErr = null
        // old APIs need the write grant on the chosen public folder first — ask, then retry
        if (Build.VERSION.SDK_INT < 30 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ytPending = url
            dlPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ytUrl = url
        ytProg = 0
        YtNotify.progress(context, 0, url)
        // blocking download — must not run on the UI thread
        ytScope.launch(Dispatchers.IO) {
            // rolling tail of yt-dlp's own stdout/stderr — shown in the failure dialog, logged under tag dvora-yt
            val out = StringBuilder()
            fun note(line: String?) {
                if (line.isNullOrBlank()) return
                out.append(line).append('\n')
                if (out.length > 6000) out.delete(0, out.indexOf('\n').takeIf { it > 0 } ?: 0)
                Log.i("dvora-yt", line.takeLast(400))
            }

            val started = System.currentTimeMillis()
            try {
                val dir = ytSaveDir(context) ?: error("no writable media folder")
                if (!dir.exists() && !dir.mkdirs()) error("cannot create folder ${dir.path}")
                val req = YoutubeDLRequest(url)
                req.addOption("-o", "${dir.path}/%(title)s.%(ext)s")
                // best video + separate best audio when offered, else single best — merged to one mp4 by ffmpeg
                req.addOption("-f", "bv*+ba/b")
                req.addOption("--merge-output-format", "mp4")
                // stale yt-dlp from a previously crashed / cancelled run under our fixed id
                try {
                    YoutubeDL.getInstance().destroyProcessById("dvora-media")
                } catch (_: Exception) {
                }
                note("yt-dlp $url")
                YoutubeDL.execute(req, "dvora-media") { p, _, line ->
                    note(line)
                    val pct = p.toInt()
                    if (pct >= 100) {
                        ytProg = -1
                        val newest = dir.listFiles()?.filter { f -> f.isFile }?.maxByOrNull { f -> f.lastModified() }
                        YtNotify.done(context, newest?.name ?: "")
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "⬇ ${newest?.name ?: "saved"} → ${dir.path}", Toast.LENGTH_LONG)
                                .show()
                        }
                    } else if (pct >= 0) {
                        ytProg = pct
                        if (pct % 5 == 0) YtNotify.progress(context, pct, url)
                    }
                }
                // the process ended without ever reporting ≥100 — did it still land a fresh file?
                if (ytProg in 0..99) {
                    val newest = dir.listFiles()?.filter { f -> f.isFile }?.maxByOrNull { f -> f.lastModified() }
                    if (newest != null && newest.lastModified() >= started) {
                        ytProg = -1
                        YtNotify.done(context, newest.name)
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "⬇ ${newest.name} → ${dir.path}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // nothing fresh on disk — a silent failure; surface what yt-dlp said
                        YtNotify.dismiss(context)
                        ytErr = out.toString().takeLast(2000)
                        ytProg = -1
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, localeStr(context, R.string.yt_dl_fail), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                note("EXCEPTION: ${e.stackTraceToString().takeLast(1500)}")
                YtNotify.dismiss(context)
                // a user cancel (stopYt / notification action) already reset ytProg — no failure toast then
                if (ytProg >= 0) {
                    ytErr = "${e.message ?: e}\n${out.toString().takeLast(1200)}".take(2000)
                    ytProg = -1
                    // toast is main-thread only — the worker coroutine crashed the app before this was fixed
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, localeStr(context, R.string.yt_dl_fail), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** abort a running yt-dlp process — from the in-app row or the notification action */
    fun stopYt() {
        if (ytProg < 0) return
        try {
            YoutubeDL.getInstance().destroyProcessById("dvora-media")
        } catch (_: Exception) {
        }
        YtNotify.dismiss(context)
        Toast.makeText(context, localeStr(context, R.string.yt_cancelled), Toast.LENGTH_SHORT).show()
        ytProg = -1
    }

    // resume the yt download that waited on the storage prompt — after startYt is declared above it
    LaunchedEffect(hasStorage, ytPending) {
        val u = ytPending ?: return@LaunchedEffect
        if (!hasStorage || ytProg >= 0) return@LaunchedEffect
        ytPending = null
        startYt(u)
    }

    fun enqueueDownload(url: String, disposition: String?, mimeType: String?) {
        if (!url.startsWith("http")) return
        if (!hasStorage) {
            if (Build.VERSION.SDK_INT < 29) {          // needs the runtime grant on old APIs
                dlPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        val name = disposition?.let {
            Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)", RegexOption.IGNORE_CASE).find(it)
                ?.groupValues?.get(1)?.trim()?.takeIf { n -> n.isNotEmpty() }
        } ?: url.substringAfterLast('/')?.substringBefore('?')?.trim()?.takeIf { it.isNotEmpty() }
        val safe = (name ?: "dvora-download-${System.currentTimeMillis()}")
            .replace(Regex("[^A-Za-z0-9._\\-]"), "_")
            .take(120)
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(safe)
                .setDescription(context.getString(R.string.dl_started_desc))
                .setMimeType(mimeType ?: "application/octet-stream")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android) Dvora/1.0")
            if (Build.VERSION.SDK_INT >= 29) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safe)
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                req.setDestinationUri(Uri.fromFile(File(dir, safe)))
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(context, localeStr(context, R.string.dl_started), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, localeStr(context, R.string.dl_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ── one-time wiring ───────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        if (Build.VERSION.SDK_INT < 29) dlPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        webView.webViewClient = client
        webView.webChromeClient = chrome
        webView.setDownloadListener(object : DownloadListener {
            override fun onDownloadStart(
                url: String, userAgent: String, contentDisposition: String, mimeType: String, contentLength: Long
            ) {
                enqueueDownload(url, contentDisposition, mimeType)
            }
        })

        chrome.onFileChooser = { cb, params -> pendingChooser = cb; pickFile(params) }
        chrome.onVideo = { v, cb -> video = (v to cb) }
        chrome.onVideoExited = { video = null }
        chrome.onTitle = { pageTitle = it }
        chrome.onProgress = { progress = it }
        chrome.onIcon = { favicon = it }
        client.onUrl = { address = it }
        client.onPageStart = { host, url ->
            if (video != null) {                           // page changed → leave fullscreen video
                video?.let { it.second.onCustomViewHidden() }
                video = null
            }
            pageHost = host
            pageTitle = ""
            favicon = null
            blocked = 0
            blockLog = emptyList()
            address =
                url                              // bar always tracks the page actually loaded (redirects / history)
        }
        client.onActivity = { if (sheetVisible) resTick++ }
        client.onCrossNav = { url, host -> crossNav = url to host }
        client.onPageEnd = {
            blockLog = repo.engine.pageLog.toList()
            blocked = repo.engine.pageBlocks
        }
        client.onBlocked = { blocked = repo.engine.pageBlocks }
        client.onSsl = { uri, handler -> ssl = (uri to handler) }

        webView.loadUrl(address)
    }

    LaunchedEffect(isDark) {
        webView.setBackgroundColor(if (isDark) 0xFF1C1500.toInt() else 0xFFFFFDE7.toInt())
    }

    BackHandler {
        if (sheetVisible) {
            sheetVisible = false; return@BackHandler
        }   // resources panel closes first
        if (video != null) {                                 // then leave fullscreen video
            video?.let { it.second.onCustomViewHidden() }
            video = null
            return@BackHandler
        }
        if (webView.canGoBack()) webView.goBack() else onBack()          // then page history, then exit
    }

    // ── SSL dialog — allow once / go back, never silently proceed ────────────
    ssl?.let { (uri, handler) ->
        AlertDialog(
            onDismissRequest = { ssl = null; handler.cancel() },
            title = { Text("🔒 " + L(R.string.ssl_title), color = BeeColors.HoneyGold) },
            text = {
                Column {
                    Text(L(R.string.ssl_body), color = textColor)
                    Spacer(Modifier.height(8.dp))
                    Text(uri?.toString() ?: "", color = subColor, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { ssl = null; handler.proceed() }) {
                    Text(
                        L(R.string.ssl_proceed),
                        color = BeeColors.DeepAmber
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { ssl = null; handler.cancel() }) {
                    Text(
                        L(R.string.ssl_cancel),
                        color = BeeColors.DeepAmber
                    )
                }
            }
        )
    }

    // ── cross-site confirm — asked whenever a page link would change the site ──
    crossNav?.let { (url, host) ->
        AlertDialog(
            onDismissRequest = { crossNav = null; address = webView.url ?: "" },
            title = { Text(L(R.string.nav_title), color = BeeColors.HoneyGold) },
            text = {
                Column {
                    Text(
                        host,
                        color = textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(url, color = subColor, fontSize = 10.sp, maxLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // follow-up redirects of the approved host must not re-prompt
                    client.docHost = Uri.parse(url).host?.lowercase() ?: ""
                    crossNav = null
                    webView.loadUrl(url)
                }) {
                    Text(L(R.string.nav_go), color = BeeColors.DeepAmber, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { crossNav = null; address = webView.url ?: "" }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── yt-dlp picker: only the media captured for this page ────────────────
    // live while open — streams captured after the dialog is up must appear without reopening
    var ytTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ytPicker) {
        if (!ytPicker) return@LaunchedEffect
        while (true) {
            delay(400); ytTick++
        }
    }
    if (ytPicker) {
        // the same rows the panel's "media" filter shows — nothing captured may be missing here.
        // playlists / direct videos on top, bare fragments at the bottom
        val cands = remember(ytTick) {
            repo.engine.reqLog
                .filter { !it.blocked }
                .map { it.url }
                .filter { !it.contains("/api/", true) }   // json api endpoints are not yt-dlp inputs
                .filter { Media.sniff(it) != null }       // playlists / manifests / direct files / fragments only
                .distinctBy { Media.normalize(it) }       // query-stripped dedup — one row per stream, not per segment
                .sortedBy { mediaRank(it) }               // playlists / masters on top — one fragment at the bottom is not the movie
        }
        AlertDialog(
            onDismissRequest = { ytPicker = false },
            title = { Text(L(R.string.yt_picker), color = BeeColors.HoneyGold) },
            text = {
                Box(Modifier.heightIn(max = 420.dp)) {
                    if (cands.isEmpty()) {
                        Text(L(R.string.yt_none), fontSize = 12.sp, color = textColor)
                    } else {
                        LazyColumn {
                            items(cands) { u ->
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        if (isSegment(u)) {
                                            // one fragment ≠ the movie — yt-dlp needs the stream's MASTER PLAYLIST:
                                            // captured one → guessed sibling file of the fragment → else the page url
                                            val pl = cands.firstOrNull { isPlaylistCandidate(it) }
                                                ?: if (u.lowercase().contains(".mp4/")) {
                                                    u.substringBeforeLast('/') + "/index.m3u8"
                                                } else null
                                            startYt(pl ?: address)
                                        } else {
                                            startYt(u)
                                        }
                                    }.padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        if (isSegment(u)) "SEGMENT" else (Media.sniff(u) ?: "media").uppercase(),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSegment(u)) BeeColors.DeepAmber else BeeColors.HoneyGold
                                    )
                                    Text(
                                        u,
                                        fontSize = 10.sp,
                                        color = textColor,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { ytPicker = false }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── yt-dlp failure reason ─────────────────────────────────────────────────
    ytErr?.let { msg ->
        AlertDialog(
            onDismissRequest = { ytErr = null },
            title = { Text(L(R.string.yt_err_title), color = BeeColors.HoneyGold) },
            text = {
                Column {
                    Text(msg, color = textColor, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(ytUrl, color = subColor, fontSize = 9.sp, maxLines = 3)
                }
            },
            confirmButton = {
                TextButton(onClick = { ytErr = null }) {
                    Text(L(R.string.ok), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── UI ──────────────────────────────────────────────────────────────────────
    // edge-to-edge; the toolbar is a collapsible row at the bottom of the screen
    Box(modifier = modifier.fillMaxSize().background(pageBg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )

            if (progress < 100) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = BeeColors.DeepAmber,
                    trackColor = BeeColors.DeepAmber.copy(alpha = 0.2f)
                )
            }

            // yt-dlp download active — slim cancelable status row (shown even when the toolbar is hidden)
            if (ytProg >= 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(headerBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Download, null, tint = BeeColors.FoundGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        ytUrl,
                        fontSize = 10.sp,
                        color = textColor,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { ytProg.coerceAtLeast(0) / 100f },
                        modifier = Modifier.width(90.dp).height(8.dp),
                        color = BeeColors.FoundGreen,
                        trackColor = BeeColors.FoundGreen.copy(alpha = 0.15f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$ytProg%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = BeeColors.FoundGreen
                    )
                    IconButton(onClick = { stopYt() }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Default.Close,
                            L(R.string.yt_cancel),
                            tint = BeeColors.HoneyGold,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (barVisible) {
                // toolbar (bottom) — back / fwd / reload / home / page resources / hide bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.navigationBarsPadding().fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 4.dp).background(headerBg)
                ) {
                    IconButton(onClick = { if (webView.canGoBack()) webView.goBack() }, enabled = webView.canGoBack()) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, L(R.string.cd_back_page),
                            tint = if (webView.canGoBack()) BeeColors.HoneyGold else BeeColors.HoneyGold.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(
                        onClick = { if (webView.canGoForward()) webView.goForward() },
                        enabled = webView.canGoForward()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward, L(R.string.cd_fwd_page),
                            tint = if (webView.canGoForward()) BeeColors.HoneyGold else BeeColors.HoneyGold.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(onClick = { webView.reload() }) {
                        Icon(Icons.Default.Refresh, L(R.string.cd_reload), tint = BeeColors.HoneyGold)
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Home, L(R.string.cd_home), tint = BeeColors.HoneyGold)
                    }
                    IconButton(onClick = { sheetVisible = !sheetVisible }) {
                        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Web,
                                L(R.string.cd_network),
                                tint = if (blocked > 0) BeeColors.FoundGreenDark else BeeColors.HoneyGold,
                                modifier = Modifier.size(24.dp)
                            )
                            if (blocked > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .background(BeeColors.NotFoundRed, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("$blocked", fontSize = 9.sp, color = Color.White, maxLines = 1)
                                }
                            }
                        }
                    }
                    IconButton(onClick = {
                        when {
                            ytProg >= 100 -> ytProg = -1     // finished → reset
                            ytProg >= 0 -> stopYt()          // running → tap cancels
                            else -> ytPicker = true          // idle → list captured media
                        }
                    }) {
                        if (ytProg >= 0) {
                            Text(
                                if (ytProg >= 100) "✓" else "$ytProg%",
                                fontSize = 11.sp,
                                color = BeeColors.FoundGreen,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(Icons.Default.Download, L(R.string.yt_cd), tint = BeeColors.FoundGreen)
                        }
                    }
                    IconButton(onClick = { barVisible = false }) {
                        Icon(Icons.Default.ExpandLess, L(R.string.cd_bar_hide), tint = BeeColors.HoneyGold)
                    }
                }
            } else {
                // bar hidden — slim strip with one button that brings it back
                Box(
                    Modifier.navigationBarsPadding().fillMaxWidth().background(headerBg),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = { barVisible = true }) {
                        Icon(
                            Icons.Default.ExpandMore,
                            L(R.string.cd_bar_show),
                            tint = BeeColors.HoneyGold,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // the slide-up page-resources panel — every request of this page, api / media first-class
        AnimatedVisibility(
            visible = sheetVisible,
            enter = slideInVertically { 0 },
            exit = slideOutVertically { 0 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = if (barVisible) 62.dp else 0.dp)
        ) {
            Box(
                Modifier.navigationBarsPadding().fillMaxWidth().background(
                    beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell),
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
            ) {
                ResourceSheet(
                    host = pageHost,
                    log = remember(resTick, sheetVisible) { repo.engine.reqLog.toList() },
                    blocked = repo.engine.pageBlocks,
                    lifetime = repo.engine.lifetime,
                    onMedia = { ytPicker = true },
                    onClose = { sheetVisible = false }
                )
            }
        }

        // fullscreen video overlay (onShowCustomView) with its own close button
        video?.let { (v, cb) ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { v }, modifier = Modifier.fillMaxSize())
                IconButton(
                    onClick = { video = null; cb.onCustomViewHidden() },
                    modifier = Modifier.statusBarsPadding().align(Alignment.TopEnd).padding(8.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(Icons.Default.Close, contentDescription = L(R.string.cd_close), tint = Color.White)
                }
            }
        }
    }
}

/**
 * The panel opened by the toolbar resources button: EVERY request of this
 * page — page / js / css / img / frame / api / media — with live filters,
 * rule + list for each blocked one, and the lifetime counter.
 */
@Composable
private fun ResourceSheet(
    host: String,
    log: List<BlockerEngine.ReqRecord>,
    blocked: Int,
    lifetime: Long,
    onMedia: (String) -> Unit,
    onClose: () -> Unit
) {
    val textColor = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val subColor = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.7f))
    val rowBg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkStripe)
    val context = LocalContext.current
    val copied = L(R.string.rs_copied)
    var filter by remember { mutableStateOf("all") }

    val shown = remember(log, filter) {
        when (filter) {
            "blocked" -> log.filter { it.blocked }
            "api" -> log.filter { it.tag == "api" }
            "media" -> log.filter { it.tag == "media" }
            else -> log
        }
    }

    Column(Modifier.fillMaxWidth().height(560.dp).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "📡 $host",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${log.size} ${L(R.string.rs_req)} · $blocked ${L(R.string.rs_blocked)} · ${lifetime} ${L(R.string.sheet_lifetime)}",
                    fontSize = 10.sp,
                    color = subColor,
                    maxLines = 1
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = L(R.string.cd_close), tint = BeeColors.DeepAmber)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all", "blocked", "api", "media").forEach { key ->
                val active = filter == key
                val label = when (key) {
                    "blocked" -> L(R.string.chip_blocked)
                    "api" -> L(R.string.chip_api)
                    "media" -> L(R.string.chip_media)
                    else -> L(R.string.chip_all)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .background(
                            if (active) BeeColors.HoneyGold else beeAdapt(BeeColors.WaxWhite, BeeColors.DarkStripe),
                            RoundedCornerShape(9.dp)
                        )
                        .clickable { filter = key },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) Color.Black else textColor
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Text(L(R.string.rs_empty), fontSize = 11.sp, color = subColor)
        } else {
            LazyColumn {
                items(shown) { rec ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(
                                if (rec.blocked) BeeColors.NotFoundRed.copy(alpha = 0.18f) else rowBg,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                copyToClipboard(context, rec.url)
                                if (rec.tag == "media") onMedia(rec.url)   // media rows forward to the downloads dialog
                                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                            }
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                rec.tag,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    rec.blocked -> BeeColors.NotFoundRed
                                    rec.tag == "media" -> BeeColors.HoneyGold
                                    rec.tag == "api" -> BeeColors.DeepAmber
                                    rec.tag == "page" -> BeeColors.FoundGreen
                                    else -> textColor
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                rec.url,
                                fontSize = 10.sp,
                                color = textColor,
                                maxLines = 2,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rec.blocked && rec.rule.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(rec.rule, fontSize = 9.sp, color = BeeColors.DeepAmber, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}


/** bare .ts/.m4s fragment file — yt-dlp can't turn one segment back into the full video/show; the master playlist row is the entry point. */
private fun isSegment(u: String): Boolean {
    val p = u.substringBefore('?').lowercase()
    return p.endsWith(".ts") || p.endsWith(".m4s")
}

/**
 * a genuinely playable playlist/master URL — JSON api endpoints such as
 * /api/v2/download/episode/manifest?id=… sniff as MPD by url but hand yt-dlp
 * a plain JSON blob instead of the stream.
 */
private fun isPlaylistCandidate(u: String): Boolean {
    val ul = u.lowercase()
    if (ul.contains("/api/", true) || ul.contains("manifest?id=", true)) return false
    return Media.isM3U8(u) || Media.isMPD(u)
}

/** picker row rank — playlists/masters first, full container files next, fragments last. */
private fun mediaRank(u: String): Int = when {
    isPlaylistCandidate(u) -> 0
    isSegment(u) -> 2
    else -> 1
}

/** yt-dlp download notification — progress + cancel action, mirrors the in-app status row. */
object YtNotify {
    const val PROC = "dvora-media"
    private const val CHANNEL = "yt_media"
    private const val ID = 4241

    private fun nm(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun can(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val n = nm(ctx)
            if (n.getNotificationChannel(CHANNEL) == null) {
                n.createNotificationChannel(
                    NotificationChannel(CHANNEL, ctx.getString(R.string.yt_down), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private val appIntent: (Context) -> PendingIntent
        get() = { ctx ->
            PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

    private val cancelPi: (Context) -> PendingIntent
        get() = { ctx ->
            PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, YtCancelReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

    fun progress(ctx: Context, pct: Int, url: String) {
        if (!can(ctx)) return
        try {
            ensureChannel(ctx)
            nm(ctx).notify(
                ID, NotificationCompat.Builder(ctx, CHANNEL)
                    .setContentTitle(ctx.getString(R.string.yt_down))
                    .setContentText(url.take(120))
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, pct.coerceIn(0, 99), false)
                    .addAction(
                        NotificationCompat.Action.Builder(
                            android.R.drawable.ic_menu_revert,
                            ctx.getString(R.string.yt_cancel),
                            cancelPi(ctx)
                        ).build()
                    )
                    .setContentIntent(appIntent(ctx))
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    fun done(ctx: Context, name: String) {
        if (!can(ctx)) return
        try {
            ensureChannel(ctx)
            val n = nm(ctx)
            n.notify(
                ID, NotificationCompat.Builder(ctx, CHANNEL)
                    .setContentTitle(ctx.getString(R.string.yt_done))
                    .setContentText(name)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress(100, 100, false)
                    .setAutoCancel(true)
                    .setContentIntent(appIntent(ctx))
                    .build()
            )
            // tidy up after a few seconds — the file is already on disk, no need to keep the note
            Handler(Looper.getMainLooper()).postDelayed({ n.cancel(ID) }, 8000)
        } catch (_: Exception) {
        }
    }

    fun dismiss(ctx: Context) {
        if (!can(ctx)) return
        try {
            nm(ctx).cancel(ID)
        } catch (_: Exception) {
        }
    }
}

/** notification cancel action — kills the yt-dlp process by its process id. */
class YtCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            YoutubeDL.getInstance().destroyProcessById(YtNotify.PROC)
        } catch (_: Exception) {
        }
        YtNotify.dismiss(context)
        Toast.makeText(context, "cancelled", Toast.LENGTH_SHORT).show()
    }
}
