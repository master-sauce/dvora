package com.dvora.dvora20

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dvora.dvora20.adblock.ListInfo
import com.dvora.dvora20.adblock.ListRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The settings tab for the in-app browser + the whole-app extras that hang
 * off it: master ad-block switch, every filter list on/off with its live
 * status and manual refresh, lifetime stats, the result-link handler mode,
 * the EN/HE app language, and browsing-data clearing.
 */
@Composable
fun BrowserTab(repo: ListRepo, onToggleLang: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE) }
    val textColor = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val cardBg = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell)
    val subColor = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.7f))

    var tick by remember { mutableIntStateOf(0) }     // refreshes async list statuses live
    LaunchedEffect(Unit) {
        while (true) {
            delay(900); tick++
        }
    }
    var confirmClear by remember { mutableStateOf(false) }

    // live UI state — flipped instantly on tap, prefs written straight behind it
    var master by remember { mutableStateOf(repo.isMaster()) }
    var listOn by remember { mutableStateOf(ListInfo.all.map { repo.isEnabled(it) }) }
    var useDvora by remember { mutableStateOf(prefs.getBoolean("use_dvora_browser", false)) }
    val lang = LocalUiLang.current.value

    fun statusLine(l: ListInfo): String {
        val msg = repo.statuses[l] ?: ""
        val ts = repo.lastUpdated(l)
        val whenStr = if (ts > 0) SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH).format(Date(ts))
        else context.getString(R.string.btab_never)
        return "$msg · $whenStr"
    }

    fun clearAll() {
        confirmClear = false
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val cm = CookieManager.getInstance()
                    cm.flush()
                    cm.removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                    File(context.cacheDir, "webview_cache").deleteRecursively()
                    File(context.cacheDir, "webview").deleteRecursively()
                } catch (_: Exception) {
                }
            }
            Toast.makeText(context, localeStr(context, R.string.btab_cleared), Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // ── master switch ─────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🛡 " + L(R.string.btab_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textColor)
                Spacer(Modifier.height(2.dp))
                Text(L(R.string.btab_hint), fontSize = 10.sp, color = subColor)
            }
            Switch(
                checked = master,
                onCheckedChange = { master = it; repo.setMaster(it) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = BeeColors.FoundGreen,
                    uncheckedTrackColor = BeeColors.DeepAmber.copy(alpha = 0.4f),
                    checkedThumbColor = Color.White,
                    uncheckedThumbColor = BeeColors.HoneyGold
                )
            )
        }
        Spacer(Modifier.height(14.dp))

        // ── the four lists ────────────────────────────────────────────────────
        Text(L(R.string.btab_lists), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textColor)
        Spacer(Modifier.height(4.dp))
        for (l in ListInfo.all) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(l.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = textColor)
                    Text(
                        statusLine(l),
                        fontSize = 10.sp,
                        color = if ((repo.statuses[l] ?: "").startsWith("bundled") || (repo.statuses[l]
                                ?: "").startsWith("empty")
                        ) BeeColors.DeepAmber else subColor
                    )
                }
                IconButton(onClick = { repo.refresh(scope, l) }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = L(R.string.cd_refresh),
                        tint = BeeColors.DeepAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }
                val idx = ListInfo.all.indexOf(l)
                Switch(
                    checked = listOn[idx],
                    onCheckedChange = {
                        listOn = listOn.toMutableList().apply { set(idx, it) }
                        repo.setEnabled(l, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BeeColors.HoneyGold,
                        uncheckedTrackColor = BeeColors.DeepAmber.copy(alpha = 0.3f),
                        checkedThumbColor = Color.Black,
                        uncheckedThumbColor = BeeColors.HoneyGold
                    )
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── engine + block stats ──────────────────────────────────────────────
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    "${L(R.string.btab_rules)}: ${repo.engine.totalRules + repo.engine.totalException}",
                    fontSize = 11.sp,
                    color = textColor
                )
                Text(
                    "${L(R.string.btab_lifetime)}: ${repo.engine.lifetime}",
                    fontSize = 11.sp,
                    color = BeeColors.DeepAmber,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── where do result cards open ────────────────────────────────────────
        Text(L(R.string.btab_linkmode), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textColor)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BeeRadioOption(
                L(R.string.btab_link_ext),
                !useDvora,
                {
                    useDvora = false
                    prefs.edit().putBoolean("use_dvora_browser", false).apply()
                },
                Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            BeeRadioOption(
                L(R.string.btab_link_dvora),
                useDvora,
                {
                    useDvora = true
                    prefs.edit().putBoolean("use_dvora_browser", true).apply()
                },
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(14.dp))

        // ── UI language ───────────────────────────────────────────────────────
        Text(L(R.string.btab_lang), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textColor)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BeeRadioOption(
                "EN",
                lang == "en",
                {
                    prefs.edit().putString("ui_lang", "en").apply()
                    onToggleLang()
                },
                Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            BeeRadioOption(
                "עברית",
                lang == "he",
                {
                    prefs.edit().putString("ui_lang", "he").apply()
                    onToggleLang()
                },
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(14.dp))

        // ── browsing data ─────────────────────────────────────────────────────
        Button(
            onClick = { confirmClear = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BeeColors.NotFoundRed, contentColor = Color.White)
        ) {
            Text(L(R.string.btab_clear), fontWeight = FontWeight.Bold)
        }
    }

    // ── confirm the destructive clear ─────────────────────────────────────────
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(L(R.string.btab_clear_q), color = BeeColors.HoneyGold) },
            text = { Text(L(R.string.btab_clear_msg), color = textColor) },
            confirmButton = {
                TextButton(onClick = { clearAll() }) {
                    Text(
                        L(R.string.ok),
                        color = BeeColors.NotFoundRed,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(
                        L(R.string.cancel),
                        color = BeeColors.DeepAmber
                    )
                }
            }
        )
    }
}
