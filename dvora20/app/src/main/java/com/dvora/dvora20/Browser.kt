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
import android.util.Log
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dvora.dvora20.adblock.BlockerEngine
import com.dvora.dvora20.adblock.ListRepo
import com.dvora.dvora20.adblock.Media
import com.dvora.dvora20.adblock.ResourceProbe
import com.dvora.dvora20.adblock.Sniff
import com.dvora.dvora20.adblock.SniffName
import com.dvora.dvora20.adblock.SnifferStore
import com.dvora.dvora20.adblock.SniffRow
import com.dvora.dvora20.adblock.Whitelist
import java.io.File
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The browser's start page sentinel — the home tab renders [BrowserHomePage]
 * (the bee-themed Compose start screen) instead of loading a search engine's
 * website. The tab's WebView stays on a blank page until something is opened.
 */
const val BROWSER_HOME = "dvora://home"

/**
 * Opens a folder in the system Files app (DocumentsUI via ExternalStorageProvider).
 * `resource/folder` + file:// stopped resolving on modern Android — that's why the
 * old open-folder buttons did nothing. Falls back to the legacy view, then the
 * system Downloads screen.
 */
fun folderOpenIntent(context: Context, dir: File): Intent {
    val root = Environment.getExternalStorageDirectory().absolutePath
    val rel = dir.absolutePath.removePrefix(root).trimStart('/')
    val docId = if (rel.isEmpty()) "primary:" else "primary:$rel"
    val docUri = try {
        android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
    } catch (_: Exception) {
        null
    }
    if (docUri != null) {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(docUri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(context.packageManager) != null) return i
    }
    val legacy = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.fromFile(dir), "resource/folder")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (legacy.resolveActivity(context.packageManager) != null) return legacy
    return Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Where yt-dlp stores captured media — the picker-chosen folder from the
 * browser settings tab (prefs `yt_dir`), defaulting to `Download/dvora`.
 * Falls back to the app-private external files dir if nothing is writable.
 */
@Suppress("DEPRECATION")
fun ytSaveDir(context: Context): File? {
    val prefs = context.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)
    val custom = prefs.getString("yt_dir", null)?.takeIf { it.isNotBlank() }
    val dir = custom?.let { File(it) }
        ?: File(Environment.getExternalStorageDirectory(), "Download/dvora")
    return if (dir.canWrite() || dir.mkdirs()) dir
    else context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "media") }
        ?: context.filesDir
}

/**
 * The full-screen, in-app, bee-themed browser with the built-in
 * ad/tracker blocker: multi-tab strip (normal + 🕵 incognito tabs),
 * toolbar (history / reload / favicon+title / theme), address bar with
 * GO that follows every committed url live, load-progress indicator,
 * shield button with a per-page blocked-count badge, per-site whitelist
 * panel, SSL user dialog, `<input type="file">` picker, and
 * DownloadManager downloads.
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

    // the in-app browser UI is always rendered in the dark theme — the light
    // variants of these surfaces look broken (odd whites) against the webview

    val headerBg = BeeColors.DarkComb

    val pageBg = BeeColors.DarkComb

    val textColor = BeeColors.DarkOnSurface

    val subColor = BeeColors.DarkOnSurface.copy(alpha = 0.7f)

    // ── screen state ──────────────────────────────────────────────────────────
    // per-page state (address / title / favicon / progress / ssl / video) lives on each
    // WebTab; the screen keeps only shared chrome — tabs list, active index, url row,
    // panels, dialogs and the yt-dlp machinery
    var sheetVisible by remember { mutableStateOf(false) }
    var crossNav by remember { mutableStateOf<Triple<WebTab, String, String>?>(null) }   // (tab, url, host) awaiting confirm
    var resTick by remember { mutableIntStateOf(0) }                           // bumps live while the panel is open
    var barVisible by remember { mutableStateOf(true) }                         // hide/show the bottom toolbar
    var urlBar by remember { mutableStateOf(false) }                             // editable url/search row below the toolbar
    var urlDraft by remember { mutableStateOf("") }                               // field text while the user edits
    var urlEditing by remember { mutableStateOf(false) }                           // true once the row text is user-owned
    var historyOpen by remember { mutableStateOf(false) }                           // the 🕘 history dialog
    var histClear by remember { mutableStateOf(false) }                             // history wipe awaiting confirm
    var hitMenu by remember { mutableStateOf<Triple<String, Boolean, String?>?>(null) } // long-pressed (url, isImage, linkText)
    var previewUrl by remember { mutableStateOf<String?>(null) }                     // fullscreen preview overlay
    var previewImage by remember { mutableStateOf(false) }                            // preview target is an image
    val urlFocus = remember { FocusRequester() }
    var pendingChooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }   // active <input type=file> picker
    var hasStorage by remember { mutableStateOf(Build.VERSION.SDK_INT >= 29) }

    // the whole site's whitelist — kept live; the interceptor reads this supplier per request
    var allowed by remember { mutableStateOf(Whitelist.hosts(context)) }

    // ── yt-dlp: media download of what was captured on this page — owned by the
    // foreground service from here on; here its live state is polled for badge / row ──
    var ytPicker by remember { mutableStateOf(false) }   // dialog listing capturable media
    var ytSheet by remember { mutableStateOf(false) }    // live downloads screen (running jobs + rest)
    var yt by remember { mutableIntStateOf(YtCtl.pct) }            // -1 idle · 0..99 % (first running job)
    var ytCount by remember { mutableIntStateOf(YtCtl.count) }     // how many downloads run concurrently
    var ytUrl by remember { mutableStateOf(YtCtl.url) }            // what is downloading now
    var ytBadge by remember { mutableIntStateOf(0) }               // captured media urls → button badge
    var ytDone by remember { mutableStateOf<File?>(null) }         // saved file awaiting its done dialog (queued)
    var ytPending by remember { mutableStateOf<String?>(null) }    // url to resume once the storage prompt is done
    var ytErr by remember { mutableStateOf<Pair<String, String>?>(null) }  // (url, failure text) dialog queue
    var mgr by remember { mutableStateOf(Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) }

    // ── tabs ──────────────────────────────────────────────────────────────────
    // app-session store: regular tabs survive leaving / re-entering the browser
    // screen for as long as the app is open; incognito tabs die with the screen
    val tabs = TabCtl.tabs
    var active by TabCtl.activeState

    // the app's light/dark choice — every tab's WebView is built to follow it
    val isDark = LocalDarkMode.current.value

    // the vertical tab list — the only tab UI: a count button in the top bar
    // opens the slide-in list over the page
    var tabsPanelOpen by remember { mutableStateOf(false) }
    // close-all needs an explicit yes — it destroys incognito tabs too
    var confirmCloseAll by remember { mutableStateOf(false) }
    // panel search — filters open tabs by title / url
    var tabQuery by remember { mutableStateOf("") }
    LaunchedEffect(tabsPanelOpen) { if (!tabsPanelOpen) tabQuery = "" }

    // ── launchers ─────────────────────────────────────────────────────────────
    val dlPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasStorage = granted
        if (!granted) ytPending = null                    // denied — forget the pending media retry
    }
    // asked once at the first download — without it only the in-app status row tracks yt-dlp
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // the default folder /dvora sits outside the app sandbox — one-time "all files" grant on API 30+
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) mgr = Environment.isExternalStorageManager()
    }
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

    /** kick off a media download — the foreground service runs up to YtCtl.MAX of them concurrently. */
    fun startYt(given: String, baseName: String? = null) {
        val t = tabs.getOrNull(active) ?: return
        val url = given.substringBefore('#')     // page hash fragments are noise for yt-dlp
        // the download manager resolves media urls to the END (redirects already followed by the probe) before
        // any engine sees them — the row keeps the original, the engine gets the final url
        val cap = SnifferStore.byUrl(url)
        val target = cap?.finalUrl?.takeIf { it.startsWith("http") } ?: url
        if (YtCtl.contains(target)) {
            Toast.makeText(context, localeStr(context, R.string.yt_already), Toast.LENGTH_SHORT).show()
            return
        }
        // the picker stays open — tapping more rows queues more downloads side by side
        // old APIs need the legacy write grant; new ones the one-time "all files" grant — the default
        // folder /dvora sits outside the app sandbox
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
        if (Build.VERSION.SDK_INT >= 30 && !mgr) {
            ytPending = url
            Toast.makeText(context, localeStr(context, R.string.yt_need_store), Toast.LENGTH_LONG).show()
            grant.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)     // once; the download runs without too
        }
        val dir = ytSaveDir(context) ?: run {
            Toast.makeText(context, localeStr(context, R.string.yt_dl_fail), Toast.LENGTH_LONG).show()
            return
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(context, localeStr(context, R.string.yt_dl_fail), Toast.LENGTH_LONG).show()
            return
        }
        // the saved file takes the current window title — every capture belongs to this page.
        // A clicked download link brings its own resolved file name; a YOUTUBE row brings
        // yt-dlp's own video title — either way a KNOWN base name even when the tab title
        // is blank, so the cancel-cleanup always matches its temp files.
        // '%' must die too — yt-dlp's -o is a template and a bare % breaks it
        val rawTitle = baseName ?: if (cap?.kind == "youtube") cap.filename else null
        val title = (rawTitle ?: t.pageTitle).replace(Regex("[\\\\/:*?\"<>|%]"), "_").trim().take(120).takeIf { it.isNotBlank() }
        // registers the job (unique output name + own process id) — null = the queue is full
        val job = YtCtl.add(target, dir, title) ?: run {
            Toast.makeText(context, localeStr(context, R.string.yt_queue_full), Toast.LENGTH_SHORT).show()
            return
        }
        yt = 0
        ytCount = YtCtl.count
        ytUrl = target
        // full header replay: prefer the exact referer/cookie the page used for this
        // resource (from the sniffer capture), fall back to the page itself + cookie jar
        val referer = cap?.referer ?: t.address.takeIf { it.startsWith("http") }
        val cookie = cap?.cookie ?: CookieManager.getInstance().getCookie(url)
        // the page's EXACT request headers (urlHeadersCache) + the player's own JS-set
        // headers (auth tokens, custom X-* — the player's extra headers) — the engines
        // replay them verbatim; JS-set headers win, they are what the media host accepted.
        // Accept-Encoding stays out (raw gzip would corrupt everything that isn't yt-dlp)
        val capHeaders = cap?.extraHeaders
        val headers = (SnifferStore.headersFor(url) ?: emptyMap()) + (capHeaders ?: emptyMap())
        val headersCsv = headers.entries
            .filter {
                !it.key.equals("Range", true) && !it.key.equals("Host", true) &&
                    !it.key.equals("Accept-Encoding", true) && it.value.isNotEmpty()
            }
            .joinToString("\n") { "${it.key}: ${it.value}" }
        YtDownloadService.start(context, target, dir, job.baseName, referer, t.webView.settings.userAgentString, cookie, job.proc, headersCsv)
    }


    /** abort every running yt-dlp process — from the in-app row or the notification action */
    fun stopYt() {
        if (YtCtl.count == 0) return
        YtCtl.cancel(context)
        yt = -1
        ytCount = 0
        ytDone = null
        Toast.makeText(context, localeStr(context, R.string.yt_cancelled), Toast.LENGTH_SHORT).show()
    }

    /** queues a real file download through the system DownloadManager — its own name, its own notification. */
    fun enqueueDownload(url: String, disposition: String?, mimeType: String?) {
        if (!url.startsWith("http")) return
        if (!hasStorage) {
            if (Build.VERSION.SDK_INT < 29) {          // needs the runtime grant on old APIs
                dlPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        // filename: Content-Disposition (filename* charset-aware) first, then the url path
        val name = SniffName.fromDisposition(disposition)
            ?: url.substringAfterLast('/')?.substringBefore('?')?.trim()?.takeIf { it.isNotEmpty() }
        val safe = (name ?: "dvora-download-${System.currentTimeMillis()}")
            .replace(Regex("[^A-Za-z0-9._\\-]"), "_")
            .take(120)
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(safe)
                .setDescription(context.getString(R.string.dl_started_desc))
                .setMimeType(mimeType ?: "application/octet-stream")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // full header replay — the page's own UA / referer / cookies, not a fake one
                .addRequestHeader("User-Agent", tabs.getOrNull(active)?.webView?.settings?.userAgentString ?: "")
            tabs.getOrNull(active)?.address?.takeIf { it.startsWith("http") }?.let {
                req.addRequestHeader("Referer", it)
            }
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
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

    /** a picker/sheet row tap → the right download route — shared by both dialogs.
     *  close() always runs afterwards; what "closed" means is the caller's choice. */
    fun openRow(r: SniffRow, rows: List<SniffRow>, close: () -> Unit) {
        when {
            r.isBlob -> {
                // blob URLs can't go to yt-dlp — the JS side fetches the in-memory Blob,
                // base64-chunks it to the bridge, native writes the file
                val js = "(window.__dvoraSaveBlob && window.__dvoraSaveBlob('" + r.url + "'))"
                runCatching { tabs.getOrNull(active)?.webView?.evaluateJavascript(js, null) }
            }
            r.isSegment -> {
                // one fragment ≠ the movie — yt-dlp needs the MASTER PLAYLIST:
                // captured one → guessed sibling file of the fragment → else the page url
                val pl = rows.firstOrNull { it.isPlaylist }?.url
                    ?: if (r.url.lowercase().contains(".mp4/")) {
                        r.url.substringBeforeLast('/') + "/index.m3u8"
                    } else null
                tabs.getOrNull(active)?.let { tab -> startYt(pl ?: tab.address) }
            }
            // subtitle files go through yt-dlp now — its HTTP stack + cookies get past
            // the hotlink gates that block a direct fetch; genuine media → yt-dlp;
            // anything else is a plain file for the DownloadManager
            else -> when {
                isSubtitleRow(r) -> startYt(r.url, r.name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() })
                isMediaRow(r) -> startYt(r.url)
                else -> enqueueDownload(r.url, null, null)
            }
        }
        close()
    }

    // ── sniffer merges — rich captures (JS bridge: real headers/body) + reqLog url-only rows ──
    fun sniffRows(): List<SniffRow> {
        val fromStore = SnifferStore.all().map { c ->
            SniffRow(
                url = c.url,
                label = when {
                    c.kind == "blob" -> "BLOB"
                    c.master -> "MASTER"        // the variant list — THE entry point for the full video
                    else -> c.kind.uppercase()
                },
                size = c.size,
                name = c.filename,
                isSegment = isSegment(c.url),
                isPlaylist = c.kind == "m3u8" || c.kind == "mpd",
                isBlob = c.kind == "blob",
                duration = c.duration,
                parts = c.parts,
                isMaster = c.master,
                qualities = c.qualities
            )
        }
        val known = fromStore.mapTo(mutableSetOf()) { it.url.lowercase().substringBefore('#') }
        // ── the probe engine on the request log: candidates get probed (media-tagged
        // first, then newest-first) and the RESPONSE decides — extension-less media
        // endpoints never hide behind a weird url shape. Only a video/audio/playlist
        // answer promotes into a capture row; static-file extensions never burn a slot. ──
        val staticExts = setOf(
            "js", "mjs", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "cur", "avif",
            "woff", "woff2", "ttf", "otf", "eot", "xml", "txt", "html", "htm", "json", "map"
        )
        run {
            val pageUrl = tabs.getOrNull(active)?.address?.takeIf { it.startsWith("http") }
            val ua = tabs.getOrNull(active)?.webView?.settings?.userAgentString
            val cands = repo.engine.reqSnapshot().filter { !it.blocked }
                .filter { it.tag == "media" || it.tag == "api" || it.tag == "req" }
            (cands.filter { it.tag == "media" } + cands.filter { it.tag != "media" }.asReversed())
                .forEach { r ->
                    if (SnifferStore.byUrl(r.url) != null) return@forEach
                    val ext = r.url.substringBefore('?').substringAfterLast('.', "").lowercase()
                    if (r.tag != "media" && ext in staticExts) return@forEach    // static file — no media there
                    SnifferStore.markProbe(r.url) {
                        ResourceProbe.probeAsync(
                            r.url,
                            pageUrl,
                            CookieManager.getInstance().getCookie(r.url),
                            ua
                        )
                    }
                }
        }
        val extras = repo.engine.reqSnapshot().asSequence()
            .filter { !it.blocked }
            .filter { r ->
                // everything the page itself flagged as media (Accept: video/audio headers on
                // extension-less CDN urls), everything the loose sniffer matches, and every
                // downloadable file extension — /api/ noise only drops without a media signal
                r.tag == "media" || Media.sniff(r.url) != null ||
                    (!r.url.contains("/api/", true) && Sniff.kind(r.url, "", null, -1) != null)
            }
            .map { it.url }
            .distinctBy { Media.normalize(it) }
            .filter { it.lowercase().substringBefore('#') !in known }
            .onEach { u ->
                // probe extras once per url per page — fills size/name/duration after redirects;
                // bare fragments can't enrich (Sniff.kind rejects them) so they burn no probe slot
                if (!isSegment(u)) {
                    val pageUrl = tabs.getOrNull(active)?.address?.takeIf { it.startsWith("http") }
                    val ua = tabs.getOrNull(active)?.webView?.settings?.userAgentString
                    SnifferStore.markProbe(u) {
                        ResourceProbe.probeAsync(
                            u,
                            pageUrl,
                            CookieManager.getInstance().getCookie(u),
                            ua
                        )
                    }
                }
            }
            .map { u ->
                val k = Sniff.kind(u, "", null, -1) ?: Media.sniff(u) ?: "video"
                SniffRow(u, k.uppercase(), -1L, SniffName.resolve(u, "", null), isSegment(u), k == "m3u8" || k == "mpd", isBlob = false)
            }
            .toList()
        return (fromStore + extras)
            .distinctBy { it.url.lowercase().substringBefore('#') }
            .sortedBy { row -> when { row.isMaster -> 0; row.isPlaylist -> 1; row.isSegment -> 3; else -> 2 } }
    }

    fun fmtSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / 1e9)
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1e6)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1e3)
        else -> "$bytes B"
    }

    /** seconds → m:ss / h:mm:ss — the duration chip on every media row */
    fun fmtDur(sec: Double): String {
        if (sec <= 0) return ""
        val t = sec.toInt()
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    /** history row timestamp — short locale date + time */
    fun fmtTime(at: Long): String =
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            .format(java.util.Date(at))

    // resume the yt download that waited on the storage prompt — after startYt is declared above it
    LaunchedEffect(mgr, hasStorage, ytPending) {
        val u = ytPending ?: return@LaunchedEffect
        ytPending = null
        startYt(u)
    }

    // polls the download service — the download keeps running while the screen is disposed; row / badge / dialogs mirror it
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            val p = YtCtl.pct
            if (p != yt) yt = p
            val c = YtCtl.count
            if (c != ytCount) ytCount = c
            val u = YtCtl.url
            if (u != ytUrl) ytUrl = u
            // done/fail dialogs consume their queues one at a time — a multi-download batch loses none
            if (ytDone == null) YtCtl.popDone()?.let { ytDone = it }
            if (ytErr == null) YtCtl.popErr()?.let { ytErr = it }
            // heal the YOUTUBE row — a wipe race (another tab navigated) or a busy
            // (self-updating) yt-dlp binary must never lose it; force=… is 5s-throttled
            tabs.getOrNull(active)?.let { t ->
                t.ytWatchUrl(t.address)?.let { w ->
                    if (SnifferStore.byUrl(w) == null) t.probeYouTube(w, force = true)
                }
            }
            // badge = distinct captures (sniffer + reqLog), not raw request count
            val n = sniffRows().size
            if (n != ytBadge) ytBadge = n
        }
    }

    /**
     * submits the url/search row: url-like input navigates directly, anything else
     * becomes a search with the engine chosen in the browser settings tab. `address`
     * snaps to the target so the row reflects what is loading even if the page
     * client reports nothing yet.
     */
    fun submitUrl() {
        val t = tabs.getOrNull(active) ?: return
        // the home sentinel is not a real url — an empty row while the start page is up
        val raw = (if (urlEditing) urlDraft else t.address).trim()
            .takeUnless { it == BROWSER_HOME } ?: ""
        if (raw.isEmpty()) return
        // a url must carry a scheme or a dotted host — bare words / phrases are searches
        val host = raw.substringBefore('/').substringBefore('?').substringBefore('#')
        val looksLikeUrl = raw.contains("://") || (host.contains('.') && host.isNotEmpty())
        val target = if (looksLikeUrl) {
            if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        } else {
            searchUrl(context, raw)
        }
        urlEditing = false
        urlDraft = ""
        t.load(target)
    }

    /** opens the downloads folder — [dir] overrides (the done dialog passes the file's own folder). */
    fun openDir(dir: File? = null) {
        val d = dir ?: ytSaveDir(context) ?: return
        try {
            context.startActivity(folderOpenIntent(context, d))
        } catch (_: Exception) {
            // last resort — the system Downloads screen, then the raw path
            try {
                context.startActivity(
                    Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                Toast.makeText(context, d.path, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * A clicked download link. Genuinely MEDIA links (video / audio / stream
     * containers) run through the yt-dlp pipeline and the live downloads screen
     * opens on its own — the click IS the download plus its UI. Everything else
     * is a plain file for the DownloadManager: it keeps its own name untouched.
     */
    fun clickDownload(url: String, disposition: String?, mime: String?) {
        val mimeL = (mime ?: "").lowercase()
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        // subtitles ride the same yt-dlp pipeline — its HTTP stack + cookies get past
        // the hotlink gates that block a direct fetch; same folder, live job, menu opens
        if (isSubtitleUrl(path)) {
            val stem = (SniffName.fromDisposition(disposition) ?: SnifferStore.byUrl(url)?.filename)
                ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            startYt(url, stem)
            ytSheet = true
            return
        }
        val ext = path.substringAfterLast('.', "")
        val media = path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            mimeL.startsWith("video/") || mimeL.startsWith("audio/") ||
            mimeL.contains("mpegurl") || mimeL.contains("dash+xml") ||
            ext in MEDIA_EXTS
        if (!media) {
            enqueueDownload(url, disposition, mime)   // the file keeps its own name — no yt-dlp
            return
        }
        // the listener captured this link right before us — its resolved name is the
        // output base (stem only: yt-dlp appends %(ext)s itself)
        val stem = SnifferStore.byUrl(url)?.filename?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        startYt(url, stem)
        ytSheet = true      // the download menu opens with the live job
    }

    /**
     * Download from the long-press menu: an image goes to the yt-dlp manager (its
     * magic-byte typing fixes the extension), anything else takes the normal
     * click-download routing (media → yt-dlp, plain files → DownloadManager).
     */
    fun downloadHit(u: String, image: Boolean) {
        if (image) {
            val stem = u.substringBefore('?').substringBefore('#').substringAfterLast('/')
                .substringBeforeLast('.', "").takeIf { it.isNotBlank() } ?: "image"
            startYt(u, stem)
            ytSheet = true
        } else clickDownload(u, null, null)
    }

    // ── tab management ────────────────────────────────────────────────────────

    /**
     * While ANY incognito tab lives the app-wide cookie jar is closed — restored
     * the moment the last one dies. WebView cookies are process-global, so this
     * is the only way to guarantee an incognito tab leaves nothing behind; the
     * price is that normal tabs pause their cookie sessions while one is open.
     */
    fun syncCookies() {
        CookieManager.getInstance().setAcceptCookie(tabs.none { it.incognito })
    }

    fun newTab(url: String, incognito: Boolean, background: Boolean = false) {
        val prevActive = active
        tabs.add(
            WebTab(
                // the whitelist is read live from its cache (not this screen's state) —
                // session-restored tabs must see toggles made in a later composition
                context, repo, url.ifBlank { BROWSER_HOME }, incognito, isDark, { Whitelist.hosts(context).toSet() },
                onCrossNav = { tab, u, h -> crossNav = Triple(tab, u, h) },
                onActivity = { if (sheetVisible) resTick++ },
                onPick = { cb, params -> pendingChooser = cb; pickFile(params) },
                onEnqueue = { u, d, m -> clickDownload(u, d, m) },
                // long-press: the screen pops the action menu (download / preview /
                // new tab / background / incognito / external app / copy / share)
                onLongPress = { u, img, text -> hitMenu = Triple(u, img, text) }
            )
        )
        active = tabs.lastIndex
        if (background) active = prevActive.coerceIn(0, tabs.lastIndex)   // load it, keep the current tab on screen
        syncCookies()
    }

    fun closeTab(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        tabs.removeAt(i)
        t.destroy()
        if (tabs.isEmpty()) {                       // never tab-less — a fresh home tab
            newTab(BROWSER_HOME, false)
            return
        }
        active = when {
            i < active -> active - 1                                // same tab stays selected
            i == active -> active.coerceAtMost(tabs.lastIndex)      // its neighbor slides in
            else -> active
        }
        syncCookies()
    }

    /** closes EVERY tab (incognito included) and leaves one fresh home tab behind. */
    fun closeAllTabs() {
        if (tabs.isEmpty()) return
        tabs.toList().forEach { it.destroy() }
        tabs.clear()
        active = 0
        newTab(BROWSER_HOME, false)
        syncCookies()   // the incognito tabs died — the app-wide cookie jar reopens
    }

    // entering the browser: restore the session's tabs as they were; a tapped
    // link (initialUrl ≠ plain home open) opens as its own NEW tab instead.
    // After an activity recreation (theme switch) the snapshot taken in
    // MainActivity.onDestroy reopens the regular tabs.
    var opened by remember { mutableStateOf(false) }
    if (!opened) {
        opened = true
        val restore = TabCtl.restore
        val restoreActive = TabCtl.restoreActive
        TabCtl.restore = null
        if (tabs.isEmpty() && !restore.isNullOrEmpty()) {
            restore.forEach { u -> newTab(u, false) }
            if (tabs.isNotEmpty()) active = restoreActive.coerceIn(0, tabs.lastIndex)
        } else if (tabs.isEmpty()) newTab(initialUrl.ifBlank { BROWSER_HOME }, false)
        else if (initialUrl.isNotBlank() && initialUrl != BROWSER_HOME) newTab(initialUrl, false)
    }

    // leaving the browser screen: incognito tabs die here (never saved, never
    // recorded); regular tabs keep living in TabCtl for the whole app session
    DisposableEffect(Unit) {
        onDispose {
            TabCtl.dropIncognito()
            syncCookies()
        }
    }

    // the theme toggle flips the app; a WebView bakes its color scheme at creation,
    // so live tabs are rebuilt in place (same urls / incognito flags) to carry the
    // new light/dark into their pages
    var lastDark by remember { mutableStateOf(isDark) }
    LaunchedEffect(isDark) {
        if (isDark == lastDark) return@LaunchedEffect
        lastDark = isDark
        val snap = tabs.map { it.address to it.incognito }
        val prevActive = active
        tabs.forEach { it.destroy() }
        tabs.clear()
        snap.forEach { (addr, inc) -> newTab(addr, inc) }
        if (tabs.isNotEmpty()) active = prevActive.coerceIn(0, tabs.lastIndex)
    }

    // ── one-time wiring ───────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < 29) dlPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    BackHandler {
        if (tabsPanelOpen) {
            tabsPanelOpen = false; return@BackHandler
        }   // the vertical tab list closes first
        if (sheetVisible) {
            sheetVisible = false; return@BackHandler
        }   // resources panel closes next
        val tab = tabs.getOrNull(active)
        if (tab?.video != null) {                               // then leave fullscreen video
            tab.video?.let { it.second.onCustomViewHidden() }
            return@BackHandler
        }
        when {
            tab != null && tab.webView.canGoBack() -> tab.webView.goBack()    // then page history…
            tab != null && tabs.size > 1 -> closeTab(active)                    // …then the tab itself…
            else -> onBack()                                                   // …then exit
        }
    }

    // ── SSL dialog — allow once / go back, never silently proceed ────────────
    // ── SSL dialog — allow once / go back, never silently proceed ────────────
    tabs.getOrNull(active)?.let { tab ->
        tab.ssl?.let { (uri, handler) ->
            AlertDialog(
                onDismissRequest = { tab.ssl = null; handler.cancel() },

                containerColor = BeeColors.DarkComb,
                title = { Text("\ud83d\udd12 " + L(R.string.ssl_title), color = BeeColors.HoneyGold) },
                text = {
                    Column {
                        Text(L(R.string.ssl_body), color = textColor)
                        Spacer(Modifier.height(8.dp))
                        Text(uri?.toString() ?: "", color = subColor, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { tab.ssl = null; handler.proceed() }) {
                        Text(
                            L(R.string.ssl_proceed),
                            color = BeeColors.DeepAmber
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { tab.ssl = null; handler.cancel() }) {
                        Text(
                            L(R.string.ssl_cancel),
                            color = BeeColors.DeepAmber
                        )
                    }
                }
            )
        }
    }

    // ── cross-site confirm — asked whenever a page link would change the site ──
    crossNav?.let { (navTab, url, host) ->
        AlertDialog(
            onDismissRequest = { crossNav = null; navTab.address = navTab.webView.url ?: "" },

            containerColor = BeeColors.DarkComb,
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
                    navTab.client.docHost = Uri.parse(url).host?.lowercase() ?: ""
                    crossNav = null
                    navTab.webView.loadUrl(url)
                }) {
                    Text(L(R.string.nav_go), color = BeeColors.DeepAmber, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { crossNav = null; navTab.address = navTab.webView.url ?: "" }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── long-press action menu — download / preview / tabs / open / copy / share ──
    hitMenu?.let { (url, image, linkText) ->
        val hitHost = Uri.parse(url).host ?: url
        val incognitoTab = tabs.getOrNull(active)?.incognito == true
        // the menu pops while the finger is still down — an outside tap right after
        // (the same press being lifted) must not close it again
        val openedAt = remember(url) { android.os.SystemClock.uptimeMillis() }
        AlertDialog(
            onDismissRequest = {
                if (android.os.SystemClock.uptimeMillis() - openedAt > 450) hitMenu = null
            },
            containerColor = BeeColors.DarkComb,
            title = { Text(L(R.string.lp_title), color = BeeColors.HoneyGold) },
            text = {
                Box(Modifier.heightIn(max = 430.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            hitHost,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor,
                            maxLines = 1
                        )
                        Text(url, fontSize = 9.sp, color = subColor, maxLines = 2)
                        Spacer(Modifier.height(8.dp))

                        if (isDownloadableHit(url, image)) {
                            LinkMenuRow(
                                Icons.Default.Download, L(R.string.lp_download), BeeColors.FoundGreen
                            ) {
                                hitMenu = null
                                downloadHit(url, image)
                            }
                        }
                        LinkMenuRow(
                            Icons.Default.Visibility, L(R.string.lp_preview), BeeColors.DeepAmber
                        ) {
                            hitMenu = null
                            previewImage = image
                            previewUrl = url
                        }
                        LinkMenuRow(
                            Icons.Default.OpenInNew, L(R.string.lp_open_tab), BeeColors.HoneyGold
                        ) {
                            hitMenu = null
                            newTab(url, incognitoTab)
                        }
                        LinkMenuRow(
                            Icons.Default.Tab, L(R.string.lp_open_bg), BeeColors.HoneyGold
                        ) {
                            hitMenu = null
                            newTab(url, incognitoTab, background = true)
                            Toast.makeText(
                                context,
                                localeStr(context, R.string.lp_bg_opened),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        LinkMenuRow(
                            Icons.Default.VisibilityOff, L(R.string.lp_open_incognito), BeeColors.HoneyGold
                        ) {
                            hitMenu = null
                            newTab(url, incognito = true)
                        }
                        LinkMenuRow(
                            Icons.Default.Public, L(R.string.lp_open_app), BeeColors.HoneyGold
                        ) {
                            hitMenu = null
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        LinkMenuRow(
                            Icons.Default.ContentCopy, L(R.string.lp_copy_link), BeeColors.HoneyGold
                        ) {
                            hitMenu = null
                            copyToClipboard(context, url)
                            Toast.makeText(
                                context,
                                localeStr(context, R.string.lp_copied_link),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (!linkText.isNullOrBlank()) {
                            LinkMenuRow(
                                Icons.Default.ContentCopy, L(R.string.lp_copy_text), BeeColors.HoneyGold
                            ) {
                                hitMenu = null
                                copyToClipboard(context, linkText)
                                Toast.makeText(
                                    context,
                                    localeStr(context, R.string.lp_copied_text),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        LinkMenuRow(
                            Icons.Default.Share, L(R.string.lp_share), BeeColors.HoneyGold
                        ) {
                            hitMenu = null
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND)
                                            .setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, url),
                                        null
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { hitMenu = null }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── fullscreen preview — the raw link rendered in a throwaway WebView ──
    previewUrl?.let { url ->
        val incognitoTab = tabs.getOrNull(active)?.incognito == true
        LinkPreviewDialog(
            url = url,
            canDownload = isDownloadableHit(url, previewImage),
            onDismiss = { previewUrl = null },
            onDownload = {
                previewUrl = null
                downloadHit(url, previewImage)
            },
            onOpenTab = {
                previewUrl = null
                newTab(url, incognitoTab)
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
        // merged capture rows — sniffer captures (real headers / sizes / names) + reqLog extras.
        // playlists / masters on top, bare fragments at the bottom
        val rows = remember(ytTick) { sniffRows() }
        AlertDialog(
            onDismissRequest = { ytPicker = false },

            containerColor = BeeColors.DarkComb,
            title = { Text(L(R.string.yt_picker), color = BeeColors.HoneyGold) },
            text = {
                Box(Modifier.heightIn(max = 420.dp)) {
                    if (rows.isEmpty()) {
                        Text(L(R.string.yt_none), fontSize = 12.sp, color = textColor)
                    } else {
                        LazyColumn {
                            items(rows) { r ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .background(BeeColors.HoneyGold.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                        .border(1.dp, BeeColors.HoneyGold.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                        .clickable {
                                            // tap → hand over to the live downloads screen
                                            openRow(r, rows) { ytPicker = false; ytSheet = true }
                                        }
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        listOfNotNull(
                                            r.label,
                                            r.qualities?.takeIf { it.isNotEmpty() }?.substringBefore('·'),   // just the best — it's what gets downloaded
                                            fmtDur(r.duration).takeIf { it.isNotEmpty() },
                                            if (r.parts > 0) context.getString(R.string.yt_parts, r.parts) else null,
                                            fmtSize(r.size).takeIf { it.isNotEmpty() }
                                        ).joinToString(" · "),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            r.isSegment -> BeeColors.DeepAmber
                                            r.isPlaylist -> BeeColors.HoneyGold
                                            else -> BeeColors.HoneyGold
                                        }
                                    )
                                    Text(
                                        r.name ?: r.url,
                                        fontSize = 10.sp,
                                        color = textColor,
                                        maxLines = 2
                                    )
                                    if (r.name != null) {
                                        Text(
                                            r.url,
                                            fontSize = 9.sp,
                                            color = subColor,
                                            maxLines = 1
                                        )
                                    }
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

    // ── live downloads screen — every running job with its own progress + cancel,
    // then the rest of this page's downloadables, tap to queue another ──────────
    var dlTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ytSheet) {
        if (!ytSheet) return@LaunchedEffect
        while (true) {
            delay(400); dlTick++
        }
    }
    if (ytSheet) {
        val jobs = remember(dlTick) { YtCtl.running().map { Triple(it.url, it.pct, it) } }
        val restRows = remember(dlTick) { sniffRows() }
        val liveUrls = jobs.mapTo(mutableSetOf()) { it.first.lowercase().substringBefore('#') }
        val rest = restRows.filter { it.url.lowercase().substringBefore('#') !in liveUrls }
        AlertDialog(
            onDismissRequest = { ytSheet = false },
            containerColor = BeeColors.DarkComb,
            title = { Text("⬇ " + L(R.string.yt_sheet_title), color = BeeColors.HoneyGold) },
            text = {
                Box(Modifier.heightIn(max = 460.dp)) {
                    LazyColumn {
                        items(jobs) { t ->
                            val (jurl, jpct, job) = t
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .background(BeeColors.HoneyGold.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                        .border(1.dp, BeeColors.HoneyGold.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        jurl,
                                        fontSize = 10.sp,
                                        color = textColor,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "$jpct%",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BeeColors.FoundGreen
                                    )
                                    IconButton(
                                        onClick = {
                                            YtCtl.cancel(context, job)     // this job only — the rest keep running
                                            // and its notification dies with it, not stuck at some stale %
                                            runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(job.noteId) }
                                            Toast.makeText(context, localeStr(context, R.string.yt_cancelled), Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            L(R.string.yt_cancel),
                                            tint = BeeColors.NotFoundRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { jpct / 100f },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = BeeColors.FoundGreen,
                                    trackColor = BeeColors.FoundGreen.copy(alpha = 0.15f)
                                )
                            }
                        }
                        if (rest.isNotEmpty()) {
                            item {
                                Text(
                                    L(R.string.yt_sheet_rest),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = subColor,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                                )
                            }
                            items(rest) { r ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .background(BeeColors.HoneyGold.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                        .border(1.dp, BeeColors.HoneyGold.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                        .clickable {
                                            // the sheet stays open — the new job appears in the live list via dlTick
                                            openRow(r, restRows) { }
                                        }
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        listOfNotNull(
                                            r.label,
                                            r.qualities?.takeIf { it.isNotEmpty() }?.substringBefore('·'),   // just the best — it's what gets downloaded
                                            fmtDur(r.duration).takeIf { it.isNotEmpty() },
                                            if (r.parts > 0) context.getString(R.string.yt_parts, r.parts) else null,
                                            fmtSize(r.size).takeIf { it.isNotEmpty() }
                                        ).joinToString(" · "),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BeeColors.HoneyGold
                                    )
                                    Text(r.name ?: r.url, fontSize = 10.sp, color = textColor, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { ytSheet = false }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── yt-dlp failure reason ─────────────────────────────────────────────────
    ytErr?.let { (failedUrl, msg) ->
        AlertDialog(

            // close AND drop the source so the poll loop can't re-pop the same failure

            onDismissRequest = { ytErr = null },

            containerColor = BeeColors.DarkComb,


            title = { Text(L(R.string.yt_err_title), color = BeeColors.HoneyGold) },

            text = {

                Column {

                    Text(msg, color = textColor, fontSize = 11.sp)

                    Spacer(Modifier.height(6.dp))

                    Text(failedUrl, color = subColor, fontSize = 9.sp, maxLines = 3)

                }

            },

            confirmButton = {

                TextButton(onClick = {
                    ytErr = null
                }) {

                    Text(L(R.string.ok), color = BeeColors.DeepAmber)

                }

            },

            dismissButton = {
                TextButton(onClick = {
                    copyToClipboard(context, "$msg\n$failedUrl")
                    Toast.makeText(context, localeStr(context, R.string.rs_copied), Toast.LENGTH_SHORT).show()
                }) {
                    Text(L(R.string.yt_copy_err), color = BeeColors.HoneyGold)
                }
            }
        )
    }

    // ── saved media — one dialog per finished download, queued across a multi-download batch ──
    ytDone?.let { f ->
        AlertDialog(
            onDismissRequest = { ytDone = null },
            containerColor = BeeColors.DarkComb,
            title = { Text(L(R.string.yt_done), color = BeeColors.HoneyGold) },
            text = { Text(f.name, color = textColor, fontSize = 11.sp, maxLines = 3) },
            confirmButton = {
                TextButton(onClick = { ytDone = null; openDir(f.parentFile) }) {
                    Text(L(R.string.med_open), color = BeeColors.HoneyGold)
                }
            },
            dismissButton = {
                TextButton(onClick = { ytDone = null }) {
                    Text(L(R.string.ok), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── 🕘 history — every committed page url of normal tabs, newest on top ──
    // (incognito pages were never recorded — there is nothing of them anywhere)
    if (historyOpen) {
        AlertDialog(
            onDismissRequest = { historyOpen = false },

            containerColor = BeeColors.DarkComb,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🕘 " + L(R.string.hist_title), color = BeeColors.HoneyGold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { histClear = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = L(R.string.rs_clear),
                            tint = BeeColors.DeepAmber
                        )
                    }
                }
            },
            text = {
                Box(Modifier.heightIn(max = 420.dp)) {
                    if (HistoryStore.entries.isEmpty()) {
                        Text(L(R.string.hist_empty), fontSize = 12.sp, color = textColor)
                    } else {
                        LazyColumn {
                            items(HistoryStore.entries) { h ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            historyOpen = false
                                            tabs.getOrNull(active)?.load(h.url)   // opens in the active tab
                                        }
                                        .padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        h.title ?: h.host,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textColor,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${h.url}  ·  ${fmtTime(h.at)}",
                                        fontSize = 9.sp,
                                        color = subColor,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { historyOpen = false }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }
    if (histClear) {
        ConfirmDeleteDialog(
            title = L(R.string.hist_clear_q),
            message = L(R.string.hist_clear_msg),
            confirmText = L(R.string.ok),
            onDismiss = { histClear = false },
            onConfirm = { HistoryStore.clear() }
        )
    }

    // ── close all tabs — explicit yes (incognito tabs are destroyed too) ──
    if (confirmCloseAll) {
        ConfirmDeleteDialog(
            title = L(R.string.close_all_q),
            message = L(R.string.close_all_msg),
            confirmText = L(R.string.cd_close_all),
            onDismiss = { confirmCloseAll = false },
            onConfirm = {
                confirmCloseAll = false
                tabsPanelOpen = false
                closeAllTabs()
            }
        )
    }

    // ── UI ──────────────────────────────────────────────────────────────────────
    // edge-to-edge; the toolbar is a collapsible row at the bottom of the screen
    val tab = tabs.getOrNull(active) ?: return
    Box(modifier = modifier.fillMaxSize().background(pageBg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ── top bar ──
            // ONE tab UI now: the tabs button with the live count on the left opens
            // the vertical tab list; a 🕵 at the right end marks an incognito ACTIVE tab
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(headerBg).padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                // the tabs button — the counter on the left of the bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(BeeColors.DarkStripe, RoundedCornerShape(10.dp))
                        .clickable { tabsPanelOpen = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Default.Tab, L(R.string.cd_tabs_panel),
                        tint = BeeColors.HoneyGold, modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "${tabs.size}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                }
                // the ACTIVE tab is incognito → the eye mark sits LEFT, next to the tabs pill
                if (tab.incognito) {
                    Icon(
                        Icons.Default.VisibilityOff, L(R.string.cd_incognito),
                        tint = BeeColors.DarkOnSurface, modifier = Modifier.padding(start = 8.dp).size(22.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                // per-site ad-block toggle — shield: green = filtering this site,
                // red-crossed = the site is whitelisted, nothing is blocked here
                val siteAllowed = tab.pageHost.isNotEmpty() && Whitelist.isAllowed(tab.pageHost, allowed)
                IconButton(
                    onClick = {
                        val h = tab.pageHost
                        if (h.isNotEmpty()) {
                            if (siteAllowed) Whitelist.remove(context, h)
                            else Whitelist.add(context, BlockerEngine.registrable(h).ifBlank { h })
                            allowed = Whitelist.hosts(context)   // live — the interceptor reads it per request
                            tab.webView.reload()                  // refetch under the new rule
                            Toast.makeText(
                                context,
                                localeStr(context, if (siteAllowed) R.string.ab_site_on else R.string.ab_site_off),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        if (siteAllowed) Icons.Default.GppBad else Icons.Default.GppGood,
                        L(R.string.cd_ab_toggle),
                        tint = if (siteAllowed) BeeColors.NotFoundRed else BeeColors.FoundGreen,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // right end: close current tab → open incognito tab → open regular tab (rightmost)
                IconButton(onClick = { closeTab(active) }, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.Close, L(R.string.cd_close_tab),
                        tint = BeeColors.DeepAmber, modifier = Modifier.size(22.dp)
                    )
                }
                // OPEN a new incognito tab — the detective emoji marks the ACTION, so it
                // can't be confused with the on-state mark
                IconButton(onClick = { newTab(BROWSER_HOME, true) }, modifier = Modifier.size(44.dp)) {
                    Text("🕵", fontSize = 20.sp)
                }
                IconButton(onClick = { newTab(BROWSER_HOME, false) }, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.Add, L(R.string.cd_newtab),
                        tint = BeeColors.HoneyGold, modifier = Modifier.size(22.dp)
                    )
                }
            }

            // ── top row: live url / search — toggleable by the toolbar button ──
            val onHome = tab.address == BROWSER_HOME
            // the row text — empty on the start page, user-owned while editing.
            // Hebrew / Arabic input is right-aligned, English stays left
            val barText = if (urlEditing) urlDraft else if (onHome) "" else tab.address
            val barRtl = isRtlText(barText)
            if (urlBar) {
                LaunchedEffect(active) {
                    urlEditing = false
                    urlDraft = tab.address
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .background(headerBg)
                ) {
                    Icon(
                        Icons.Default.Share,
                        null,
                        tint = if (urlEditing) BeeColors.FoundGreen else BeeColors.HoneyGold,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                // the android share sheet for the current url
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND)
                                                .setType("text/plain")
                                                .putExtra(Intent.EXTRA_TEXT, tab.address),
                                            null
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = barText,
                        onValueChange = { urlEditing = true; urlDraft = it },
                        modifier = Modifier.weight(1f).focusRequester(urlFocus),
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 12.sp,
                            color = textColor,
                            textAlign = if (barRtl) TextAlign.End else TextAlign.Start
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor,
                            focusedBorderColor = BeeColors.HoneyGold,
                            unfocusedBorderColor = BeeColors.HoneyGold.copy(alpha = 0.25f),
                            focusedContainerColor = headerBg,
                            unfocusedContainerColor = headerBg
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submitUrl() }, onDone = { submitUrl() }),
                        placeholder = {
                            Text("https://… or search term", fontSize = 12.sp, color = textColor.copy(alpha = 0.35f))
                        }
                    )
                    IconButton(onClick = { submitUrl() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.PlayArrow, L(R.string.nav_go), tint = BeeColors.HoneyGold)
                    }
                }
            }

            // the active tab's webview is reparented in/out — every tab keeps its own
            // renderer, scroll and page state while it sits in the background.
            // On the home sentinel the blank WebView stays behind and the Compose
            // start page covers it, so navigating away/back never rebuilds the tab.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // one permanent host per tab — each WebView is attached ONCE and a tab
                // switch only flips visibility. Detaching a WebView from its window to
                // move it into another frame makes Chromium drop the drawn surface, so
                // the page came back blank after a tab switch or a screen return.
                tabs.forEach { t ->
                    key(t) {
                        AndroidView(
                            factory = { ctx ->
                                FrameLayout(ctx).apply {
                                    // a re-entering composition hands us the WebView of a
                                    // dead hierarchy — take it over before adding it here
                                    (t.webView.parent as? ViewGroup)?.removeView(t.webView)
                                    addView(
                                        t.webView,
                                        FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                }
                            },
                            update = { frame ->
                                val wv = t.webView
                                if (wv.parent !== frame) {
                                    (wv.parent as? ViewGroup)?.removeView(wv)
                                    frame.removeAllViews()
                                    frame.addView(
                                        wv,
                                        FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                    wv.requestLayout()
                                }
                                // only the active tab draws; the rest stay attached
                                // and keep their page state (background tabs)
                                val want = if (t === tab && !onHome) View.VISIBLE else View.INVISIBLE
                                if (wv.visibility != want) {
                                    wv.visibility = want
                                    // Chromium resumes drawing on show — kick one frame so
                                    // the surface comes back instead of staying blank
                                    if (want == View.VISIBLE) {
                                        wv.requestLayout()
                                        wv.invalidate()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                if (onHome) {
                    BrowserHomePage(
                        incognito = tab.incognito,
                        onSearch = { q ->
                            urlEditing = false
                            urlDraft = ""
                            tab.load(searchUrl(context, q))
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (!onHome && tab.progress < 100) {
                LinearProgressIndicator(
                    progress = { tab.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = BeeColors.DeepAmber,
                    trackColor = BeeColors.DeepAmber.copy(alpha = 0.2f)
                )
            }

            // yt-dlp download active — slim cancelable status row (shown even when the toolbar is hidden)
            if (yt in 0..99) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(headerBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Download, null, tint = BeeColors.FoundGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (ytCount > 1) "↓$ytCount · $ytUrl" else ytUrl,
                        fontSize = 10.sp,
                        color = textColor,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { yt / 100f },
                        modifier = Modifier.width(90.dp).height(8.dp),
                        color = BeeColors.FoundGreen,
                        trackColor = BeeColors.FoundGreen.copy(alpha = 0.15f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$yt%",
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
                Column(Modifier.navigationBarsPadding().fillMaxWidth()) {
                    // toolbar (bottom) — back / fwd / reload / home / page resources / url row toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 4.dp).background(headerBg)
                    ) {
                        IconButton(
                            onClick = { if (tab.canBack()) tab.webView.goBack() },
                            enabled = tab.canBack()
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack, L(R.string.cd_back_page),
                                tint = if (tab.canBack()) BeeColors.HoneyGold else BeeColors.HoneyGold.copy(alpha = 0.3f)
                            )
                        }
                        IconButton(
                            onClick = { if (tab.canFwd()) tab.webView.goForward() },
                            enabled = tab.canFwd()
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward, L(R.string.cd_fwd_page),
                                tint = if (tab.canFwd()) BeeColors.HoneyGold else BeeColors.HoneyGold.copy(
                                    alpha = 0.3f
                                )
                            )
                        }
                        IconButton(onClick = { tab.webView.reload() }) {
                            Icon(Icons.Default.Refresh, L(R.string.cd_reload), tint = BeeColors.HoneyGold)
                        }
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.Home, L(R.string.cd_home), tint = BeeColors.HoneyGold)
                        }
                        IconButton(onClick = { sheetVisible = !sheetVisible }) {
                            Icon(
                                Icons.Default.Web,
                                L(R.string.cd_network),
                                tint = BeeColors.HoneyGold,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(onClick = {
                            if (yt in 0..99) ytSheet = true      // running → the live downloads screen
                            else ytPicker = true                 // idle → list captured media
                        }) {
                            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                                if (yt in 0..99) Text(
                                    if (ytCount > 1) "↓$ytCount" else "$yt%", fontSize = 11.sp,
                                    color = BeeColors.FoundGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                else Icon(Icons.Default.Download, L(R.string.yt_cd), tint = BeeColors.FoundGreen)
                                if (yt < 0 && ytBadge > 0) {      // media urls captured on this page → count badge
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .background(BeeColors.FoundGreen, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("$ytBadge", fontSize = 9.sp, color = Color.White, maxLines = 1)
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { urlBar = !urlBar }) {
                            Icon(
                                if (urlBar) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                L(R.string.cd_bar_hide),
                                tint = BeeColors.HoneyGold
                            )
                        }
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

                    BeeColors.DarkCell,

                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

                )
            ) {
                ResourceSheet(
                    host = tab.pageHost,
                    log = remember(resTick, sheetVisible, active) { repo.engine.reqSnapshot() },
                    blocked = repo.engine.pageBlocks,
                    lifetime = repo.engine.lifetime,
                    onMedia = { ytPicker = true },
                    onDownload = { startYt(it) },
                    onClear = {
                        // wipe this page's request log + captured media list
                        repo.engine.newPage()
                        SnifferStore.newPage()
                        resTick++
                    },
                    onClose = { sheetVisible = false }
                )
            }
        }

        // vertical tabs — scrim eats the outside tap, the panel slides in from the left edge
        if (tabsPanelOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { tabsPanelOpen = false }
            )
        }
        AnimatedVisibility(
            visible = tabsPanelOpen,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(300.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(BeeColors.DarkComb)
                    .statusBarsPadding()
            ) {
                // header: close · live tab count · new tab · new INCOGNITO tab on the right
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(headerBg).padding(horizontal = 2.dp)
                ) {
                    IconButton(onClick = { tabsPanelOpen = false }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Default.Close, L(R.string.cd_close),
                            tint = BeeColors.DeepAmber, modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        "🗂 ${tabs.size}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = BeeColors.HoneyGold,
                        modifier = Modifier.weight(1f)
                    )
                    // history + close-all live here; tab actions stay on the top bar
                    IconButton(onClick = { confirmCloseAll = true }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Default.DeleteSweep, L(R.string.cd_close_all),
                            tint = BeeColors.NotFoundRed, modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = { tabsPanelOpen = false; historyOpen = true }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Default.History, L(R.string.cd_history),
                            tint = BeeColors.HoneyGold, modifier = Modifier.size(22.dp)
                        )
                    }
                }
                // search across open tabs — matches title, host and full url
                OutlinedTextField(
                    value = tabQuery,
                    onValueChange = { tabQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 12.sp,
                        color = textColor,
                        textAlign = if (isRtlText(tabQuery)) TextAlign.End else TextAlign.Start
                    ),
                    placeholder = {
                        Text(L(R.string.tab_search), fontSize = 12.sp, color = textColor.copy(alpha = 0.35f))
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedBorderColor = BeeColors.HoneyGold,
                        unfocusedBorderColor = BeeColors.HoneyGold.copy(alpha = 0.25f),
                        focusedContainerColor = BeeColors.DarkStripe,
                        unfocusedContainerColor = BeeColors.DarkStripe
                    )
                )
                val q = tabQuery.trim().lowercase()
                val shownTabs = if (q.isEmpty()) tabs.withIndex().toList()
                else tabs.withIndex().filter { (_, t) ->
                    t.pageTitle.contains(q, true) || t.address.contains(q, true) || t.pageHost.contains(q, true)
                }
                if (q.isNotEmpty() && shownTabs.isEmpty()) {
                    Text(
                        L(R.string.tab_search_none),
                        fontSize = 11.sp, color = subColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(shownTabs) { iv ->
                        val i = iv.index
                        val t = iv.value
                        val sel = i == active
                        val isHome = t.address == BROWSER_HOME
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                .background(
                                    if (sel) BeeColors.HoneyGold.copy(alpha = 0.2f) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    active = i
                                    urlEditing = false
                                    tabsPanelOpen = false
                                }
                                .padding(horizontal = 6.dp, vertical = 10.dp)
                        ) {
                            when {
                                t.incognito -> Text("🕵", fontSize = 14.sp)
                                isHome -> Text("🐝", fontSize = 14.sp)
                                t.favicon != null -> Image(
                                    t.favicon!!.asImageBitmap(), null,
                                    modifier = Modifier.size(18.dp)
                                )
                                else -> Text("🌐", fontSize = 14.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.pageTitle.ifBlank {
                                        if (isHome) L(R.string.home_title) else t.pageHost.ifBlank { "…" }
                                    },
                                    fontSize = 12.sp,
                                    color = textColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!isHome) {
                                    Text(
                                        t.address,
                                        fontSize = 9.sp,
                                        color = subColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = L(R.string.cd_close_tab),
                                tint = subColor,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { closeTab(i) }
                            )
                        }
                    }
                }
            }
        }

        // fullscreen video overlay (onShowCustomView) of the ACTIVE tab, with its own close button
        tab.video?.let { (v, cb) ->
            key(v) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { v }, modifier = Modifier.fillMaxSize())
                    IconButton(
                        onClick = { tab.video = null; cb.onCustomViewHidden() },
                        modifier = Modifier.statusBarsPadding().align(Alignment.TopEnd).padding(8.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = L(R.string.cd_close), tint = Color.White)
                    }
                }
            }
        }
    }
}

/** one tappable row of the long-press action menu. */
@Composable
private fun LinkMenuRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 11.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 13.sp, color = BeeColors.DarkOnSurface)
    }
}

/**
 * Fullscreen preview of a long-pressed link / media url: the raw target inside a
 * throwaway WebView — images, video, audio and plain pages all render natively —
 * with its own Download / New-tab / Close bar. Nothing here touches the tab
 * session, the history or the ad-block accounting.
 */
@Composable
private fun LinkPreviewDialog(
    url: String,
    canDownload: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpenTab: () -> Unit
) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }

    val preview = remember(url) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                // everything stays inside the preview — never hand off to another app
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress
                }
            }
            loadUrl(url)
        }
    }
    DisposableEffect(url) {
        onDispose { runCatching { preview.destroy() } }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BeeColors.DarkComb.copy(alpha = 0.94f))
                        .statusBarsPadding()
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.Close, L(R.string.cd_close),
                            tint = BeeColors.HoneyGold, modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        url,
                        fontSize = 10.sp,
                        color = BeeColors.DarkOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (progress < 100) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = BeeColors.DeepAmber,
                        trackColor = BeeColors.DeepAmber.copy(alpha = 0.2f)
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BeeColors.DarkComb.copy(alpha = 0.94f))
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (canDownload) {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BeeColors.FoundGreen, contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(L(R.string.lp_download), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = onOpenTab,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BeeColors.DarkStripe, contentColor = BeeColors.HoneyGold
                        )
                    ) {
                        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(L(R.string.lp_open_tab), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResourceSheet(
    host: String,
    log: List<BlockerEngine.ReqRecord>,
    blocked: Int,
    lifetime: Long,
    onMedia: (String) -> Unit,
    onDownload: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    val textColor = BeeColors.DarkOnSurface

    val subColor = BeeColors.DarkOnSurface.copy(alpha = 0.7f)

    val rowBg = BeeColors.DarkStripe

    val context = LocalContext.current

    val copied = L(R.string.rs_copied)
    var filter by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }              // free-text search across every request
    var confirm by remember { mutableStateOf<String?>(null) } // long-pressed url awaiting download confirm
    var confirmClear by remember { mutableStateOf(false) }     // the trash button's wipe confirm

    val shown = remember(log, filter, query) {
        val base = when (filter) {
            "blocked" -> log.filter { it.blocked }
            "api" -> log.filter { it.tag == "api" }
            "media" -> log.filter { it.tag == "media" }
            else -> log
        }
        val q = query.trim()
        if (q.isEmpty()) base
        else base.filter { it.url.contains(q, ignoreCase = true) || it.tag.contains(q, ignoreCase = true) }
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
                    "${log.size} ${L(R.string.rs_req)} · $blocked ${L(R.string.rs_blocked)}",
                    fontSize = 10.sp,
                    color = subColor,
                    maxLines = 1
                )
            }
            IconButton(onClick = { confirmClear = true }) {
                Icon(Icons.Default.Delete, contentDescription = L(R.string.rs_clear), tint = BeeColors.DeepAmber)
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
                            if (active) BeeColors.HoneyGold else BeeColors.DarkStripe,
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
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, color = textColor),
            placeholder = {
                Text(L(R.string.rs_search), fontSize = 12.sp, color = textColor.copy(alpha = 0.35f))
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = textColor,
                unfocusedTextColor = textColor,
                focusedBorderColor = BeeColors.HoneyGold,
                unfocusedBorderColor = BeeColors.HoneyGold.copy(alpha = 0.25f),
                focusedContainerColor = rowBg,
                unfocusedContainerColor = rowBg
            )
        )
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
                            .combinedClickable(
                                onClick = {
                                    copyToClipboard(context, rec.url)
                                    if (rec.tag == "media") onMedia(rec.url)   // media rows forward to the downloads dialog
                                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                                },
                                onLongClick = { confirm = rec.url }            // ask before queueing a download
                            )
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

    // the trash button — wiping the page's request log + captured media needs an explicit yes
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = BeeColors.DarkComb,
            title = { Text(L(R.string.rs_clear_q), color = BeeColors.HoneyGold) },
            text = { Text(L(R.string.rs_clear), color = textColor, fontSize = 11.sp) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) {
                    Text(L(R.string.ok), color = BeeColors.FoundGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // long-press → explicit confirm before the url joins the download queue
    confirm?.let { u ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = BeeColors.DarkComb,
            title = { Text(L(R.string.dl_confirm_title), color = BeeColors.HoneyGold) },
            text = { Text(u, color = textColor, fontSize = 10.sp, maxLines = 4) },
            confirmButton = {
                TextButton(onClick = { confirm = null; onDownload(u) }) {
                    Text(L(R.string.yt_down), color = BeeColors.FoundGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }
}


/** bare .ts/.m4s fragment file — yt-dlp can't turn one segment back into the full video/show; the master playlist row is the entry point. */
private fun isSegment(u: String): Boolean {
    val p = u.substringBefore('?').lowercase()
    return p.endsWith(".ts") || p.endsWith(".m4s")
}

/** the MEDIA whitelist — only these ever reach yt-dlp. Everything else is a plain
 *  file for the DownloadManager, which keeps its own name (no ".unknown_video"). */
private val MEDIA_EXTS = setOf(
    "mp4", "m3u8", "mpd", "mkv", "webm", "m4v", "mov", "avi", "flv", "wmv", "mpg", "mpeg",
    "3gp", "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma"
)

/** a picker row holding genuine media — everything else is a plain file. */
private fun isMediaRow(r: SniffRow): Boolean {
    val label = r.label.lowercase()
    // "YOUTUBE" rows carry a watch url (no media extension) — yt-dlp's own entry point;
    // "VIDEO"/"AUDIO" are the loose sniffer's extension-less media hits
    if (label == "youtube" || label == "video" || label == "audio") return true
    if (label in MEDIA_EXTS) return true
    val ext = r.url.substringBefore('?').substringBefore('#').lowercase().substringAfterLast('.', "")
    return ext in MEDIA_EXTS
}

/** subtitle files — saved straight into the dvora folder with the media. */
private val SUBTITLE_EXTS = setOf("srt", "vtt", "sub", "ttml", "sbv", "ssa", "ass")

/** a picker row holding a subtitle file. */
private fun isSubtitleRow(r: SniffRow): Boolean =
    r.label.lowercase() in SUBTITLE_EXTS ||
        r.url.substringBefore('?').substringBefore('#').lowercase().substringAfterLast('.', "") in SUBTITLE_EXTS

/** a subtitle url (query/hash already stripped, lowercased by the caller or not — handled here). */
private fun isSubtitleUrl(url: String): Boolean =
    url.substringBefore('?').substringBefore('#').lowercase().substringAfterLast('.', "") in SUBTITLE_EXTS


/** a long-pressed target the download pipeline can actually take: an image, or a url
 *  whose path names real media / a playlist / a subtitle file. A page link is not. */
private fun isDownloadableHit(url: String, image: Boolean): Boolean {
    if (image) return true
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return true
    if (isSubtitleUrl(path)) return true
    val ext = path.substringAfterLast('.', "")
    return ext in MEDIA_EXTS || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "svg", "ico")
}

/** notification cancel action — kills the yt-dlp process + stops the download service. */
class YtCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val proc = intent?.getStringExtra("proc")
        if (proc.isNullOrBlank()) YtCtl.cancel(context)               // cancel-all (in-app stop button)
        else YtCtl.byProc(proc)?.let {
            YtCtl.cancel(context, it)                                  // this download only…
            runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(it.noteId) }   // …and its note
        }
        Toast.makeText(context, "cancelled", Toast.LENGTH_SHORT).show()
    }
}
