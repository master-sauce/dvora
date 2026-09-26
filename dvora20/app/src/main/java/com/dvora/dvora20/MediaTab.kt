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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.core.content.FileProvider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/** destination path with a "(n)" suffix appended on every clash — never overwrites. */
private fun uniqueDest(src: File, dest: File): File {
    val base = File(dest, src.name)
    if (!base.exists()) return base
    val b = src.nameWithoutExtension
    val e = src.extension
    var n = 1
    while (true) {
        val cand = File(dest, if (e.isEmpty()) "$b ($n)" else "$b ($n).$e")
        if (!cand.exists()) return cand
        n++
    }
}

/**
 * The media folder as a browsable directory: every subfolder can be opened
 * in-place — same cards / play / share / move / folder buttons at every depth,
 * folders creatable inside folders, files movable between siblings.
 */
@Composable
fun MediaTab() {
    val context = LocalContext.current
    val textColor = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val subColor = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.7f))
    val rowBg = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe)

    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    // currently browsed directory — the base media dir at startup, any subfolder afterwards
    var cur by remember { mutableStateOf<File?>(ytSaveDir(context)) }
    val root = ytSaveDir(context)
    val atRoot = (cur?.let { root != null && it.path == root.path }) ?: true
    val scope = rememberCoroutineScope()


    // watch the browsed directory — anything added there (downloads, external apps,
    // file managers) shows up within ~2s; subfolders ride along so they can be
    // opened / created / wiped right here, at every depth
    fun scan() {
        val prev = files
        val prevFolders = folders
        val d = cur?.takeIf { it.exists() } ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                val list = d
                    ?.listFiles { f -> f.isFile && f.extension.lowercase() in MEDIA_EXTS }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()
                val dirs = d
                    ?.listFiles { f -> f.isDirectory }
                    ?.sortedBy { it.name.lowercase() } ?: emptyList()
                if (list != prev) files = list
                if (dirs != prevFolders) folders = dirs
            }
        }
    }

    /** one directory up — never above the base media folder. */
    fun up() {
        val d = cur ?: return
        val base = ytSaveDir(context) ?: return
        val p = d.parentFile ?: return
        cur = if (p.absolutePath.length < base.absolutePath.length) base else p
        scan()
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
            cur = ytSaveDir(context)                       // the tree was redirected — browse from its new root
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
        val dir = cur ?: ytSaveDir(context) ?: return
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

    // ── subfolder management — hits the real directory the media folder lives in ──
    var newFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var folderToDelete by remember { mutableStateOf<File?>(null) }
    // which file awaits a destination subfolder — the "move to folder" dialog target
    var moveTarget by remember { mutableStateOf<File?>(null) }
    // which file awaits its wipe confirmation / which file OR folder awaits its new name
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var rename by remember { mutableStateOf<File?>(null) }
    var renameName by remember { mutableStateOf("") }
    val folderFocus = remember { FocusRequester() }
    val renameFocus = remember { FocusRequester() }
    // the AlertDialog opens ~before the field can hold focus — short settle, then the keyboard
    LaunchedEffect(newFolder) {
        if (newFolder) {
            delay(300)
            folderFocus.requestFocus()
        }
    }
    LaunchedEffect(rename) {
        if (rename != null) {
            delay(300)
            renameFocus.requestFocus()
        }
    }

    fun createFolder() {
        val raw = newFolderName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val base = cur ?: ytSaveDir(context) ?: return
        if (raw.isEmpty()) return
        newFolder = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { File(base, raw).mkdirs() }.getOrDefault(false) }
            Toast.makeText(
                context,
                localeStr(context, if (ok) R.string.med_folder_created else R.string.med_folder_fail),
                Toast.LENGTH_SHORT
            ).show()
            scan()
        }
    }

    fun deleteFolder() {
        val f = folderToDelete ?: return
        folderToDelete = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { f.deleteRecursively() }.getOrDefault(false) }
            Toast.makeText(
                context,
                localeStr(context, if (ok) R.string.med_folder_deleted else R.string.med_folder_fail),
                Toast.LENGTH_SHORT
            ).show()
            scan()
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

    fun deleteFile() {
        val f = fileToDelete ?: return
        fileToDelete = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { f.delete() }.getOrDefault(false) }
            Toast.makeText(
                context,
                localeStr(context, if (ok) R.string.med_file_deleted else R.string.med_action_fail),
                Toast.LENGTH_SHORT
            ).show()
            if (ok) scan()
        }
    }

    /** file and folder alike — same-directory rename, illegal chars sanitized. */
    fun doRename() {
        val src = rename ?: return
        val raw = renameName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (raw.isEmpty() || raw == src.name) return
        rename = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    src.renameTo(
                        File(
                            src.parentFile,
                            raw
                        )
                    )
                }.getOrDefault(false)
            }
            Toast.makeText(
                context,
                localeStr(context, if (ok) R.string.med_renamed else R.string.med_action_fail),
                Toast.LENGTH_SHORT
            ).show()
            if (ok) scan()
        }
    }

    /** move a listed file into one of its sibling subfolders — "(n)" suffix on a name clash. */
    fun moveFile(f: File, dest: File) {
        val target = uniqueDest(f, dest)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (!f.renameTo(target)) {          // cross-fs → copy + delete fallback
                        f.copyTo(target, overwrite = true)
                        f.delete()
                    }
                    !f.exists() && target.exists()
                }.getOrDefault(false)
            }
            Toast.makeText(
                context,
                localeStr(context, if (ok) R.string.med_moved else R.string.med_move_fail),
                Toast.LENGTH_SHORT
            ).show()
            if (ok) scan()
        }
    }

    // NOTE: plain column — the file list is the ONLY scrollable node (a LazyColumn
    // nested inside another vertical scroll is measured with infinity height → crash)
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(10.dp))

        // folder summary row — current dir + count + size + open folder + step up when nested
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(46.dp)
                .background(beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe), RoundedCornerShape(10.dp))
                .clickable { ytFolderDlg = true }
                .padding(horizontal = 12.dp)
        ) {
            if (!atRoot) {
                IconButton(onClick = { up() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "back",
                        tint = BeeColors.HoneyGold,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Icon(Icons.Default.VideoLibrary, null, tint = BeeColors.HoneyGold, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(L(R.string.med_folder_pick), fontSize = 12.sp, color = textColor)
                Text(
                    "${cur?.path ?: ""} · ${files.size} files · ${folders.size} folders · ${fmtSize(total)}",
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
            IconButton(onClick = {
                newFolderName = ""
                newFolder = true
            }) {
                Icon(
                    Icons.Default.Add,
                    L(R.string.med_folder_new),
                    tint = BeeColors.HoneyGold,
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

        if (files.isEmpty() && folders.isEmpty()) {
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
                items(folders, key = { it.path }) { f ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .background(rowBg, RoundedCornerShape(10.dp))
                            .clickable { cur = f; scan() }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Folder, null, tint = BeeColors.HoneyGold, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(f.name, fontSize = 12.sp, color = textColor, maxLines = 1, modifier = Modifier.weight(1f))
                        IconButton(onClick = { rename = f; renameName = f.name }) {
                            Icon(
                                Icons.Default.Edit,
                                L(R.string.med_rename),
                                tint = BeeColors.HoneyGold,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = { folderToDelete = f }) {
                            Icon(
                                Icons.Default.Delete,
                                L(R.string.med_folder_del),
                                tint = BeeColors.NotFoundRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
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
                        IconButton(onClick = { moveTarget = f }) {
                            Icon(Icons.Default.DriveFileMove, L(R.string.med_move), tint = BeeColors.FoundGreen)
                        }
                        IconButton(onClick = { fileToDelete = f }) {
                            Icon(Icons.Default.Delete, L(R.string.med_file_del), tint = BeeColors.NotFoundRed)
                        }
                        IconButton(onClick = { rename = f; renameName = f.name }) {
                            Icon(Icons.Default.Edit, L(R.string.med_rename), tint = BeeColors.HoneyGold)
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
                                cur = ytSaveDir(context)     // default back — leave any nested view
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

    // ── fresh subfolder inside the real media directory ───────────────────────
    if (newFolder) {
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text(L(R.string.med_folder_new), color = BeeColors.HoneyGold) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    modifier = Modifier.focusRequester(folderFocus),
                    label = { Text(L(R.string.med_folder_name)) },
                    singleLine = true,
                    colors = beeTextFieldColors()
                )
            },
            confirmButton = {
                TextButton(onClick = { createFolder() }) {
                    Text(L(R.string.ok), color = BeeColors.HoneyGold, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { newFolder = false }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── wipe a subfolder from the real media directory ────────────────────────
    folderToDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(L(R.string.med_folder_del), color = BeeColors.HoneyGold) },
            text = { Text("${f.name}\n\n" + L(R.string.med_folder_del_msg), color = textColor) },
            confirmButton = {
                TextButton(onClick = { deleteFolder() }) {
                    Text(L(R.string.ok), color = BeeColors.NotFoundRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── wipe one listed file ────────────────────────────────────────────────
    fileToDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text(L(R.string.med_file_del), color = BeeColors.HoneyGold) },
            text = { Text("${f.name}\n\n" + L(R.string.med_file_del_msg), color = textColor) },
            confirmButton = {
                TextButton(onClick = { deleteFile() }) {
                    Text(L(R.string.ok), color = BeeColors.NotFoundRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── new name for the picked file or folder ────────────────────────────────
    rename?.let { src ->
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text(L(R.string.med_rename), color = BeeColors.HoneyGold) },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    modifier = Modifier.focusRequester(renameFocus),
                    label = { Text(src.name) },
                    singleLine = true,
                    colors = beeTextFieldColors()
                )
            },
            confirmButton = {
                TextButton(onClick = { doRename() }) {
                    Text(L(R.string.ok), color = BeeColors.HoneyGold, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { rename = null }) {
                    Text(L(R.string.cancel), color = BeeColors.DeepAmber)
                }
            }
        )
    }

    // ── which subfolder of the media directory shall receive this file ────────
    moveTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text(L(R.string.med_move_title), color = BeeColors.HoneyGold) },
            text = {
                Column {
                    Text(
                        f.name,
                        fontSize = 11.sp,
                        color = textColor,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    // every directory above cur up to the base — lets a file be moved OUT of the browsed folder
                    val ups = remember(cur, root) {
                        buildList {
                            var p: File? = cur
                            val base = root ?: return@buildList
                            while (true) {
                                if (p == null || p.path == base.path) break
                                if (p.absolutePath.length < base.absolutePath.length) {
                                    add(base); break
                                }
                                val np = p.parentFile ?: break
                                p = np
                                add(np)
                            }
                        }
                    }
                    if (ups.isEmpty() && folders.isEmpty()) {
                        Text(L(R.string.med_move_empty), fontSize = 11.sp, color = subColor)
                    } else {
                        ups.forEach { d ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { moveTarget = null; moveFile(f, d) }
                                    .padding(vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    null,
                                    tint = BeeColors.HoneyGold,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(d.name, fontSize = 13.sp, color = textColor)
                            }
                        }
                        folders.forEach { d ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { moveTarget = null; moveFile(f, d) }
                                    .padding(vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    null,
                                    tint = BeeColors.HoneyGold,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(d.name, fontSize = 13.sp, color = textColor)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { moveTarget = null }) {
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
