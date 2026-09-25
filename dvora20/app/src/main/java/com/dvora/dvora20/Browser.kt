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
import android.util.Patterns.WEB_URL
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
const val BROWSER_HOME = "https://www.globes.co.il/"

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
    var hasStorage by remember { mutableStateOf(Build.VERSION.SDK_INT >= 29) }

    // the whole site's whitelist — kept live; the interceptor reads this supplier per request
    var allowed by remember { mutableStateOf(Whitelist.hosts(context)) }
    val hostAllowed = pageHost.isNotEmpty() && allowed.any { pageHost == it || pageHost.endsWith(".$it") }

    val faviconBmp = remember(favicon) { favicon?.asImageBitmap() }

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
        chrome.onTitle = { pageTitle = it }
        chrome.onProgress = { progress = it }
        chrome.onIcon = { favicon = it }
        client.onUrl = { address = it }
        client.onPageStart = { host ->
            pageHost = host
            pageTitle = ""
            favicon = null
            blocked = 0
            blockLog = emptyList()
        }
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
        if (webView.canGoBack()) webView.goBack() else onBack()   // page history first, then exit
    }

    fun go() {
        var u = address.trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http")) {
            if (WEB_URL.matcher(u).matches()) u = "https://$u"
            else u = "https://www.google.com/search?q=${Uri.encode(u)}"
        }
        webView.loadUrl(u)
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

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(modifier = modifier.fillMaxSize().background(pageBg)) {
        Column(Modifier.fillMaxSize()) {
            // header — back / favicon + title / theme
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(headerBg).padding(4.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = L(R.string.cd_back),
                        tint = BeeColors.HoneyGold
                    )
                }
                if (faviconBmp != null) {
                    Image(
                        bitmap = faviconBmp!!,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Text(
                    pageTitle.ifEmpty { pageHost },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BeeColors.HoneyGold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                IconButton(onClick = onToggleDark) {
                    Icon(
                        if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = L(R.string.cd_theme),
                        tint = BeeColors.HoneyGold
                    )
                }
            }

            // toolbar — history / address bar / GO / shield
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
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
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = beeTextFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    trailingIcon = {
                        IconButton(onClick = { go() }) {
                            Icon(
                                Icons.Default.PlayArrow,
                                L(R.string.cd_go),
                                tint = BeeColors.HoneyGold
                            )
                        }
                    }
                )
                IconButton(onClick = { sheetVisible = !sheetVisible }) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Shield, L(R.string.cd_shield),
                            tint = if (blocked > 0) BeeColors.FoundGreenDark else BeeColors.HoneyGold
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
            }

            if (progress < 100) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = BeeColors.DeepAmber,
                    trackColor = BeeColors.DeepAmber.copy(alpha = 0.2f)
                )
            }

            AndroidView(
                factory = { webView },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }

        // the slide-up per-site shield / whitelist panel
        AnimatedVisibility(
            visible = sheetVisible,
            enter = slideInVertically { 0 },
            exit = slideOutVertically { 0 },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            Box(
                Modifier.fillMaxWidth().background(
                    beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell),
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
            ) {
                ShieldSheet(
                    host = pageHost,
                    blocking = !hostAllowed,
                    blocked = blocked,
                    log = blockLog,
                    lifetime = repo.engine.lifetime,
                    onToggle = { on ->
                        val host = BlockerEngine.registrable(pageHost)
                        if (host.isNotEmpty()) {
                            if (on) {
                                if (allowed.any { host == it || host.endsWith(".$it") }) {
                                    allowed = allowed.filter { !(host == it || host.endsWith(".$it")) }
                                    Whitelist.remove(context, host)
                                }
                            } else {
                                if (!allowed.any { host == it || host.endsWith(".$it") }) {
                                    allowed = allowed + host
                                    Whitelist.add(context, host)
                                }
                            }
                        }
                    },
                    onClose = { sheetVisible = false }
                )
            }
        }
    }
}

/**
 * The panel opened by the toolbar shield button: per-site allow /
 * block toggle (persisted), this page's block list (url + matched rule + list),
 * and the lifetime block counter.
 */
@Composable
private fun ShieldSheet(
    host: String,
    blocking: Boolean,
    blocked: Int,
    log: List<BlockerEngine.BlockRecord>,
    lifetime: Long,
    onToggle: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val textColor = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val subColor = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.7f))
    val rowBg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkStripe)

    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "🛡 " + host,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (blocking) {
                        "${blocked} ${L(R.string.sheet_blocked_page)} · ${lifetime} ${L(R.string.sheet_lifetime)}"
                    } else L(R.string.sheet_allowed),
                    fontSize = 11.sp,
                    color = subColor
                )
            }
            Switch(
                checked = blocking,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = BeeColors.FoundGreen,
                    uncheckedTrackColor = BeeColors.DeepAmber.copy(alpha = 0.4f),
                    checkedThumbColor = Color.White,
                    uncheckedThumbColor = BeeColors.HoneyGold
                )
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = L(R.string.cd_close), tint = BeeColors.DeepAmber)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(L(R.string.sheet_log), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textColor)
        Spacer(Modifier.height(4.dp))
        if (log.isEmpty()) {
            Text(L(R.string.sheet_log_empty), fontSize = 11.sp, color = subColor)
        } else {
            LazyColumn(modifier = Modifier.height(320.dp)) {
                items(log) { rec ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = rowBg),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(rec.url, fontSize = 10.sp, color = textColor, maxLines = 2)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${rec.pattern} — ${rec.list}",
                                fontSize = 9.sp,
                                color = BeeColors.DeepAmber,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
