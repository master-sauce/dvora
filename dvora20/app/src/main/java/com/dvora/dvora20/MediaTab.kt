package com.dvora.dvora20

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.core.content.FileProvider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MEDIA_EXTS = setOf(
    "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v",
    "ts", "m2ts", "mpg", "mpeg", "3gp"
)

private fun guessMime(f: File): String = when (f.extension.lowercase()) {
    "mp4", "m4v", "3gp" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    "avi" -> "video/x-msvideo"
    "flv" -> "video/x-flv"
    "wmv" -> "video/x-ms-wmv"
    "ts", "m2ts" -> "video/mp2t"
    "mpg", "mpeg" -> "video/mpeg"
    else -> "video/*"
}

private const val PROVIDER_AUTH = "com.dvora.dvora20.media"

/**
 * A content:// URI other apps can actually open — a raw file:// URI is not
 * dependable for cross-process IPC on modern Android. Falls back to file://
 * only when the file lies outside every provider root.
 */
private fun mediaUri(ctx: Context, f: File): Uri =
    try {
        FileProvider.getUriForFile(ctx, PROVIDER_AUTH, f)
    } catch (_: Exception) {
        Uri.fromFile(f)
    }

private fun fmtSize(b: Long): String {
    if (b < 1024) return "$b B"
    var v = b.toDouble()
    var u = "B"
    for (n in arrayOf("KB", "MB", "GB", "TB")) {
        if (v < 1024.0) break
        v /= 1024.0
        u = n
    }
    return String.format(Locale.getDefault(), "%.1f %s", v, u)
}

/**
 * Settings tab listing everything yt-dlp saved into the media folder:
 * play-in-app-of-choice (ACTION_VIEW chooser) and share (ACTION_SEND),
 * no need to wander into the folder.
 */
@Composable
fun MediaTab() {
    val context = LocalContext.current
    val textColor = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val subColor = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.7f))
    val rowBg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkStripe)

    var files by remember { mutableStateOf<List<File>>(emptyList()) }

    // watch the folder — anything added there (downloads, external apps, file managers)
    // shows up within ~2s
    suspend fun scan() {
        val prev = files
        withContext(Dispatchers.IO) {
            val list = ytSaveDir(context)?.takeIf { it.exists() }
                ?.listFiles { f -> f.isFile && f.extension.lowercase() in MEDIA_EXTS }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (list != prev) files = list
        }
    }
    LaunchedEffect(Unit) {
        scan()
        while (true) {
            delay(2000)
            scan()
        }
    }
    // where yt-dlp saves captured media — wizard dialog (folder picker / reset / grant)
    var ytFolderDlg by remember { mutableStateOf(false) }
    var ytPick by remember { mutableStateOf(false) }
    var ytGrant by remember { mutableStateOf(false) }
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val p = uri.path ?: ""
            context.getSharedPreferences("dvora_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "yt_dir", if (p.startsWith("/storage/")) p
                    else p.substringAfter("primary:", "").let { if (it.startsWith("/")) it else "/$it" }).apply()
            // a picked folder is only reachable via direct File IO once the
            // one-time "all files" grant exists — ask for it if not yet
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
                grant.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        ytPick = false
    }
    // fire the picker exactly once per flip — a plain `if (ytPick) launch()` would
    // re-launch on every recomposition while the picker is open
    LaunchedEffect(ytPick) {
        if (ytPick) {
            ytPick = false
            tree.launch(null)
        }
    }
    val total = remember(files) { files.sumOf { it.length() } }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }

    fun openFolder() {
        val dir = ytSaveDir(context) ?: return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.fromFile(dir), "resource/folder")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            Toast.makeText(context, dir.path, Toast.LENGTH_LONG).show()
        }
    }

    /** ACTION_VIEW video — chooser over every installed player. */
    fun view(f: File) {
        val uri = mediaUri(context, f)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, guessMime(f))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, view)
                    putExtra(Intent.EXTRA_TITLE, f.name)
                }
            )
        }.onFailure { chooser ->
            runCatching { context.startActivity(view) }.onFailure { e ->
                Log.e("dvora-yt", "view failed: ${chooser.message} | ${e.message}", e)
                Toast.makeText(context, localeStr(context, R.string.med_no_player), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** ACTION_SEND video — chooser over every installed sharer. */
    fun share(f: File) {
        val uri = mediaUri(context, f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = guessMime(f)
            setData(uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, send)
                    putExtra(Intent.EXTRA_TITLE, f.name)
                }
            )
        }.onFailure { chooser ->
            runCatching { context.startActivity(send) }.onFailure { e ->
                Log.e("dvora-yt", "share failed: ${chooser.message} | ${e.message}", e)
                Toast.makeText(context, localeStr(context, R.string.med_no_sharer), Toast.LENGTH_LONG).show()
            }
        }
    }

    // NOTE: plain column — the file list is the ONLY scrollable node (a LazyColumn
    // nested inside another vertical scroll is measured with infinity height → crash)
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(10.dp))

        // folder summary row — count + size + open folder
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(46.dp)
                .background(beeAdapt(BeeColors.WaxWhite, BeeColors.DarkStripe), RoundedCornerShape(10.dp))
                .clickable { ytFolderDlg = true }
                .padding(horizontal = 12.dp)
        ) {
            Icon(Icons.Default.VideoLibrary, null, tint = BeeColors.HoneyGold, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(L(R.string.med_folder_pick), fontSize = 12.sp, color = textColor)
                Text(
                    "${ytSaveDir(context)?.path ?: ""} · ${files.size} · ${fmtSize(total)}",
                    fontSize = 10.sp,
                    color = subColor,
                    maxLines = 1
                )
            }
            IconButton(onClick = { openFolder() }) {
                Icon(
                    Icons.Default.Folder,
                    L(R.string.med_open),
                    tint = BeeColors.DeepAmber,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ask for the one-time "all files" grant right from here while it's missing
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(42.dp)
                    .background(BeeColors.HoneyGold.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .clickable { ytGrant = true }
                    .padding(horizontal = 12.dp)
            ) {
                Icon(Icons.Default.Folder, null, tint = BeeColors.HoneyGold, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(L(R.string.med_grant), fontSize = 12.sp, color = textColor, modifier = Modifier.weight(1f))
                Text("›", fontSize = 20.sp, color = BeeColors.DeepAmber)
            }
            Spacer(Modifier.height(10.dp))
        }

        if (files.isEmpty()) {
            Text(
                L(R.string.med_empty),
                fontSize = 12.sp,
                color = subColor,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files, key = { "${it.path}-${it.lastModified()}" }) { f ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()

                            .background(rowBg, RoundedCornerShape(10.dp))

                            .clickable { view(f) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)

                    ) {

                        IconButton(onClick = { view(f) }) {
                            Icon(Icons.Default.PlayArrow, L(R.string.med_play), tint = BeeColors.FoundGreen)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 12.sp, color = textColor, maxLines = 2)
                            Text(
                                "${fmtSize(f.length())} · ${dateFmt.format(Date(f.lastModified()))}",
                                fontSize = 10.sp,
                                color = subColor
                            )
                        }
                        IconButton(onClick = { share(f) }) {
                            Icon(Icons.Default.Share, L(R.string.med_share), tint = BeeColors.DeepAmber)
                        }
                    }
                }
            }
        }
    }

    // ── pick the media download folder ────────────────────────────────────────
    if (ytFolderDlg) {
        AlertDialog(
            onDismissRequest = { ytFolderDlg = false },
            title = { Text(L(R.string.yt_folder_title), color = BeeColors.HoneyGold) },
            text = {
                Column {
                    Text(
                        ytSaveDir(context)?.path ?: "",
                        fontSize = 10.sp,
                        color = subColor,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { ytPick = true }
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            null,
                            tint = BeeColors.HoneyGold,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(L(R.string.yt_dir_change), fontSize = 13.sp, color = textColor)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                context.getSharedPreferences("dvora_prefs", android.content.Context.MODE_PRIVATE)
                                    .edit().putString("yt_dir", "").apply()
                                ytFolderDlg = false
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = BeeColors.HoneyGold, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(L(R.string.yt_dir_reset), fontSize = 13.sp, color = textColor)
                    }
                    if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                        Text(
                            L(R.string.yt_dir_grant_msg),
                            fontSize = 11.sp,
                            color = subColor,
                            modifier = Modifier.padding(top = 10.dp, start = 4.dp)
                        )
                        TextButton(onClick = {
                            ytFolderDlg = false
                            grant.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }) {
                            Text(L(R.string.yt_dir_grant), color = BeeColors.HoneyGold)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { ytFolderDlg = false }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── confirm → the one-time "all files" system prompt ─────────────────────
    if (ytGrant) {
        AlertDialog(
            onDismissRequest = { ytGrant = false },
            title = { Text(L(R.string.med_grant_q), color = BeeColors.HoneyGold) },
            text = { Text(L(R.string.med_grant_msg), color = textColor) },
            confirmButton = {
                TextButton(onClick = {
                    ytGrant = false
                    grant.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }) {
                    Text(L(R.string.med_grant), color = BeeColors.HoneyGold, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { ytGrant = false }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }
}
