package com.dvora.dvora20

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import com.dvora.dvora20.adblock.Whitelist
import java.io.File

/** default page shown when the top-bar browser button is tapped. */
const val BROWSER_HOME = "https://duckduckgo.com/"

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
