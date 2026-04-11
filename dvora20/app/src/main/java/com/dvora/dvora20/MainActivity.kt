package com.dvora.dvora20

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ═══════════════════════════════════════════════════════════════════════════════
// BOOKMARKS — DATA MODEL & MANAGER
// ═══════════════════════════════════════════════════════════════════════════════

data class Bookmark(
    val imdbId:             String,
    val title:              String,
    val year:               String?,
    val mediaType:          String?,
    val posterUrl:          String?,
    val imdbUrl:            String,
    val addedAt:            Long    = System.currentTimeMillis(),
    val reminderDate:       String? = null,   // "YYYY-MM-DD"
    val reminderTime:       String? = null,   // "HH:mm"
    val reminderRecurrence: String? = null    // "ONCE", "DAILY", "WEEKLY", "MONTHLY"
)

object BookmarksManager {
    private const val PREFS_KEY = "dvora_bookmarks"
    private const val JSON_KEY  = "bookmarks_json"

    var bookmarks by mutableStateOf<List<Bookmark>>(emptyList())
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
        val json  = prefs.getString(JSON_KEY, null) ?: run { bookmarks = emptyList(); return }
        val type  = object : TypeToken<List<Bookmark>>() {}.type
        bookmarks = try { Gson().fromJson(json, type) ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
            .edit().putString(JSON_KEY, Gson().toJson(bookmarks)).apply()
    }

    fun isBookmarked(imdbId: String) = bookmarks.any { it.imdbId == imdbId }

    fun toggle(context: Context, item: ImdbResult) {
        if (isBookmarked(item.imdbId)) {
            ReminderHelper.cancel(context, item.imdbId)
            bookmarks = bookmarks.filter { it.imdbId != item.imdbId }
        } else {
            bookmarks = bookmarks + Bookmark(
                imdbId    = item.imdbId,
                title     = item.title,
                year      = item.year,
                mediaType = item.mediaType,
                posterUrl = item.posterUrl,
                imdbUrl   = item.imdbUrl
            )
        }
        persist(context)
    }

    fun setReminder(context: Context, imdbId: String, dateStr: String, timeStr: String, recurrence: String = "ONCE") {
        val bm = bookmarks.find { it.imdbId == imdbId } ?: return
        val updated = bm.copy(reminderDate = dateStr, reminderTime = timeStr, reminderRecurrence = recurrence)
        bookmarks = bookmarks.map { if (it.imdbId == imdbId) updated else it }
        persist(context)
        val triggerAt = ReminderHelper.datetimeToMillis(dateStr, timeStr)
        ReminderHelper.schedule(context, updated, triggerAt)
    }

    fun clearReminder(context: Context, imdbId: String) {
        bookmarks = bookmarks.map {
            if (it.imdbId == imdbId) it.copy(reminderDate = null, reminderTime = null, reminderRecurrence = null) else it
        }
        persist(context)
        ReminderHelper.cancel(context, imdbId)
    }

    fun clearReminderSilent(context: Context, imdbId: String) {
        bookmarks = bookmarks.map {
            if (it.imdbId == imdbId) it.copy(reminderDate = null, reminderTime = null, reminderRecurrence = null) else it
        }
        persist(context)
    }

    fun advanceRecurringReminder(context: Context, imdbId: String) {
        val bm = bookmarks.find { it.imdbId == imdbId } ?: return
        val currentDate = bm.reminderDate ?: return
        val timeStr     = bm.reminderTime ?: "09:00"
        val recurrence  = bm.reminderRecurrence ?: return

        var nextDate  = ReminderHelper.computeNextDate(currentDate, recurrence)
        var triggerAt = ReminderHelper.datetimeToMillis(nextDate.toString(), timeStr)
        val now       = System.currentTimeMillis()

        while (triggerAt <= now) {
            nextDate  = ReminderHelper.computeNextDate(nextDate.toString(), recurrence)
            triggerAt = ReminderHelper.datetimeToMillis(nextDate.toString(), timeStr)
        }

        val updated = bm.copy(reminderDate = nextDate.toString())
        bookmarks = bookmarks.map { if (it.imdbId == imdbId) updated else it }
        persist(context)
        ReminderHelper.schedule(context, updated, triggerAt)
    }

    fun rescheduleAllReminders(context: Context) {
        val now = System.currentTimeMillis()
        var changed = false
        bookmarks = bookmarks.map { bm ->
            val dateStr = bm.reminderDate ?: return@map bm
            val timeStr = bm.reminderTime ?: "09:00"
            var triggerAt = ReminderHelper.datetimeToMillis(dateStr, timeStr)

            if (triggerAt <= now) {
                val recurrence = bm.reminderRecurrence ?: "ONCE"
                if (recurrence == "ONCE") {
                    changed = true
                    return@map bm.copy(reminderDate = null, reminderTime = null, reminderRecurrence = null)
                }
                var nextDate = java.time.LocalDate.parse(dateStr)
                while (triggerAt <= now) {
                    nextDate  = ReminderHelper.computeNextDate(nextDate.toString(), recurrence)
                    triggerAt = ReminderHelper.datetimeToMillis(nextDate.toString(), timeStr)
                }
                changed = true
                val updated = bm.copy(reminderDate = nextDate.toString())
                ReminderHelper.schedule(context, updated, triggerAt)
                updated
            } else {
                ReminderHelper.schedule(context, bm, triggerAt)
                bm
            }
        }
        if (changed) persist(context)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// BEE THEME
// ═══════════════════════════════════════════════════════════════════════════════

object BeeColors {
    val HoneyGold       = Color(0xFFFFC107)
    val DeepAmber       = Color(0xFFFF8F00)
    val PollenOrange    = Color(0xFFFF6D00)
    val FoundGreen      = Color(0xFF558B2F)
    val FoundGreenDark  = Color(0xFF8BC34A)
    val NotFoundRed     = Color(0xFFB71C1C)
    val NotFoundAmber   = Color(0xFFFF8F00)
    val WaxWhite        = Color(0xFFFFFDE7)
    val HoneycombYellow = Color(0xFFFFECB3)
    val BeeBlack        = Color(0xFF1A1200)
    val DarkComb        = Color(0xFF1C1500)
    val DarkCell        = Color(0xFF2A1F00)
    val DarkStripe      = Color(0xFF3A2B00)
    val DarkOnSurface   = Color(0xFFFFE082)
}

private val BeeLightScheme = lightColorScheme(
    primary = BeeColors.HoneyGold, onPrimary = BeeColors.BeeBlack,
    primaryContainer = BeeColors.HoneycombYellow, onPrimaryContainer = BeeColors.BeeBlack,
    secondary = BeeColors.DeepAmber, onSecondary = BeeColors.WaxWhite,
    secondaryContainer = Color(0xFFFFD54F), onSecondaryContainer = BeeColors.BeeBlack,
    tertiary = BeeColors.PollenOrange, onTertiary = BeeColors.WaxWhite,
    background = BeeColors.WaxWhite, onBackground = BeeColors.BeeBlack,
    surface = BeeColors.WaxWhite, onSurface = BeeColors.BeeBlack,
    surfaceVariant = BeeColors.HoneycombYellow, onSurfaceVariant = Color(0xFF4E3B00),
    outline = BeeColors.DeepAmber, error = BeeColors.NotFoundRed, onError = Color.White,
)

private val BeeDarkScheme = darkColorScheme(
    primary = BeeColors.HoneyGold, onPrimary = BeeColors.BeeBlack,
    primaryContainer = Color(0xFF4A3500), onPrimaryContainer = BeeColors.HoneyGold,
    secondary = BeeColors.DeepAmber, onSecondary = BeeColors.BeeBlack,
    secondaryContainer = Color(0xFF3E2800), onSecondaryContainer = BeeColors.HoneyGold,
    tertiary = BeeColors.PollenOrange, onTertiary = BeeColors.BeeBlack,
    background = BeeColors.DarkComb, onBackground = BeeColors.DarkOnSurface,
    surface = BeeColors.DarkCell, onSurface = BeeColors.DarkOnSurface,
    surfaceVariant = BeeColors.DarkStripe, onSurfaceVariant = Color(0xFFFFCC80),
    outline = BeeColors.DeepAmber, error = Color(0xFFFF6B6B), onError = BeeColors.BeeBlack,
)

val LocalDarkMode = compositionLocalOf { mutableStateOf(false) }

@Composable
fun beeAdapt(light: Color, dark: Color): Color =
    if (LocalDarkMode.current.value) dark else light

// ═══════════════════════════════════════════════════════════════════════════════
// ACTIVITY
// ═══════════════════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BookmarksManager.load(this)
        BookmarksManager.rescheduleAllReminders(this)
        setContent {
            val prefs = getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)
            val darkModeState = remember { mutableStateOf(prefs.getBoolean("dark_mode", false)) }
            CompositionLocalProvider(
                LocalDarkMode provides darkModeState,
                LocalLayoutDirection provides LayoutDirection.Ltr
            ) {
                MaterialTheme(colorScheme = if (darkModeState.value) BeeDarkScheme else BeeLightScheme) {
                    DvoraApp(onToggleDarkMode = {
                        darkModeState.value = !darkModeState.value
                        prefs.edit().putBoolean("dark_mode", darkModeState.value).apply()
                    })
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SHARED STATE
// ═══════════════════════════════════════════════════════════════════════════════

object SearchLogs {
    var lastLogs by mutableStateOf<List<SearchResult>>(emptyList())
}

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("url", text))
}

fun openUrl(context: Context, url: String) {
    try {
        val cleanUrl = if (url.startsWith("http")) url else "https://$url"

        // Try Stremio app for Stremio web URLs
        if (cleanUrl.contains("web.stremio.com") && cleanUrl.contains("/detail/")) {
            val regex = Regex("detail/(movie|series)/([^/]+)")
            val match = regex.find(cleanUrl)
            if (match != null) {
                val type = match.groupValues[1]
                val id   = match.groupValues[2]
                val deepLink = "stremio:///detail/$type/$id"
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)))
                    return
                } catch (_: Exception) {
                    // Stremio not installed, fall through to browser
                }
            }
        }

        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)))
    } catch (_: Exception) {
        Toast.makeText(context, "Could not open URL", Toast.LENGTH_SHORT).show()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN APP
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DvoraApp(onToggleDarkMode: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val scanner = remember { DvoraScanner() }
    val isDark  = LocalDarkMode.current.value

    var searchTerm   by remember { mutableStateOf("") }
    var searchType   by remember { mutableStateOf(SourceType.SHOW) }
    var results      by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var apiResults   by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var manualLinks  by remember { mutableStateOf<List<String>>(emptyList()) }
    var isSearching  by remember { mutableStateOf(false) }
    var domainFilter by remember { mutableStateOf("") }

    var shows        by remember { mutableStateOf(loadSources(context, "shows")) }
    var movies       by remember { mutableStateOf(loadSources(context, "movies")) }
    var manualChecks by remember { mutableStateOf(loadSources(context, "manual_checks")) }
    var apiSites     by remember { mutableStateOf(loadSources(context, "api_sites")) }
    var exclusions   by remember { mutableStateOf(loadSources(context, "exclusions")) }

    var showSettings  by remember { mutableStateOf(false) }
    var showSubtitles by remember { mutableStateOf(false) }
    var showImdb      by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }

    val headerBg   = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkComb)
    val scaffoldBg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🐝", fontSize = 22.sp, modifier = Modifier.padding(end = 8.dp))
                        Text(
                            text = "DVORA",
                            fontSize = 12.sp, // Add this line
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 4.sp,
                            color = BeeColors.HoneyGold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = headerBg,
                    titleContentColor = BeeColors.HoneyGold,
                    actionIconContentColor = BeeColors.HoneyGold
                ),
                actions = {
                    IconButton(onClick = onToggleDarkMode) {
                        Icon(if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme", tint = BeeColors.HoneyGold)
                    }
                    val hasReminders = BookmarksManager.bookmarks.any { it.reminderDate != null }
                    IconButton(onClick = { showBookmarks = true; showSubtitles = false; showSettings = false; showImdb = false }) {
                        Box {
                            Icon(Icons.Default.Bookmarks, "Bookmarks", tint = BeeColors.HoneyGold)
                            if (hasReminders) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(10.dp)
                                        .background(BeeColors.DeepAmber, RoundedCornerShape(5.dp))
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showSubtitles = true; showSettings = false; showImdb = false; showBookmarks = false }) {
                        Icon(Icons.Default.Subtitles, "Subtitles", tint = BeeColors.HoneyGold)
                    }
                    IconButton(onClick = { showImdb = true; showSubtitles = false; showSettings = false; showBookmarks = false }) {
                        Box(Modifier.size(40.dp).padding(6.dp).background(Color(0xFFF5C518), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            Text("IMDb", fontSize = 7.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                        }
                    }
                    IconButton(onClick = { showSettings = true; showSubtitles = false; showImdb = false; showBookmarks = false }) {
                        Icon(Icons.Default.Settings, "Settings", tint = BeeColors.HoneyGold)
                    }
                }
            )
        },
        containerColor = scaffoldBg,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        when {
            showBookmarks -> BookmarksScreen(
                onBack = { showBookmarks = false },
                onToggleDark = onToggleDarkMode,
                modifier = Modifier.padding(innerPadding)
            )
            showSettings -> SettingsScreen(
                shows = shows, movies = movies, manualChecks = manualChecks,
                apiSites = apiSites, exclusions = exclusions,
                onUpdate = { type, newList ->
                    when (type) {
                        SourceType.SHOW      -> { shows = newList; saveSources(context, "shows", newList) }
                        SourceType.MOVIE     -> { movies = newList; saveSources(context, "movies", newList) }
                        SourceType.MANUAL    -> { manualChecks = newList; saveSources(context, "manual_checks", newList) }
                        SourceType.API       -> { apiSites = newList; saveSources(context, "api_sites", newList) }
                        SourceType.EXCLUSION -> { exclusions = newList; saveSources(context, "exclusions", newList) }
                    }
                },
                onBack = { showSettings = false },
                onToggleDark = onToggleDarkMode,
                modifier = Modifier.padding(innerPadding)
            )
            showSubtitles -> SubtitlesScreen(
                scanner = scanner,
                onBack = { showSubtitles = false },
                onToggleDark = onToggleDarkMode,
                modifier = Modifier.padding(innerPadding)
            )
            showImdb -> ImdbScreen(
                scanner = scanner,
                onBack = { showImdb = false },
                onToggleDark = onToggleDarkMode,
                modifier = Modifier.padding(innerPadding)
            )
            else -> {
                val cardBg = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell)
                Column(modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxSize()) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        elevation = CardDefaults.cardElevation(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = searchTerm, onValueChange = { searchTerm = it },
                                label = { Text("🍯 Search Movie or Show") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = beeTextFieldColors()
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                BeeRadioOption("📺 Shows", searchType == SourceType.SHOW, { searchType = SourceType.SHOW }, Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                BeeRadioOption("🎬 Movies", searchType == SourceType.MOVIE, { searchType = SourceType.MOVIE }, Modifier.weight(1f))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (searchTerm.isBlank()) return@Button
                            isSearching = true; results = emptyList(); apiResults = emptyList(); domainFilter = ""
                            scope.launch {
                                val activeSources = if (searchType == SourceType.SHOW) shows else movies
                                activeSources.forEach { source -> results = results + scanner.scanSite(source, searchTerm, exclusions) }
                                apiSites.forEach { site ->
                                    val newResults = when {
                                        site.startsWith("stremio:") -> scanner.scanStremio(site.removePrefix("stremio:"), searchTerm, searchType)
                                        site.startsWith("v1:")      -> scanner.scanV1(site.removePrefix("v1:"), searchTerm)
                                        else                        -> scanner.scanV1(site, searchTerm)
                                    }
                                    apiResults = apiResults + newResults
                                }
                                SearchLogs.lastLogs = results + apiResults
                                manualLinks = manualChecks.map { scanner.getManualCheck(it, searchTerm) }
                                isSearching = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !isSearching,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = headerBg, contentColor = BeeColors.HoneyGold,
                            disabledContainerColor = if (isDark) Color(0xFF2A2000) else Color(0xFF4A3B00),
                            disabledContentColor = BeeColors.HoneyGold.copy(alpha = 0.4f)
                        )
                    ) {
                        if (isSearching) CircularProgressIndicator(Modifier.size(24.dp), BeeColors.HoneyGold, strokeWidth = 2.dp)
                        else Text("🐝  BUZZ & SEARCH", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    val hasAnyResults = results.isNotEmpty() || apiResults.isNotEmpty() || manualLinks.isNotEmpty()
                    if (hasAnyResults) {
                        OutlinedTextField(
                            value = domainFilter, onValueChange = { domainFilter = it },
                            label = { Text("🔎 Search by Site") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, colors = beeTextFieldColors(),
                            trailingIcon = {
                                if (domainFilter.isNotEmpty()) IconButton(onClick = { domainFilter = "" }) {
                                    Icon(Icons.Default.Close, "Clear", tint = BeeColors.DeepAmber)
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        val filter = domainFilter.trim().lowercase()
                        val fr = if (filter.isEmpty()) results else results.filter { it.url.lowercase().contains(filter) }
                        val fa = if (filter.isEmpty()) apiResults else apiResults.filter { it.url.lowercase().contains(filter) }
                        val fm = if (filter.isEmpty()) manualLinks else manualLinks.filter { it.lowercase().contains(filter) }
                        if (fr.isNotEmpty()) {
                            item { BeesSectionHeader("🍯 Results") }
                            items(fr.sortedByDescending { it.found }) { ResultItem(it, true) }
                        }
                        if (fa.isNotEmpty()) {
                            item { Spacer(Modifier.height(8.dp)); BeesSectionHeader("🍯🍯 API Results") }
                            items(fa) { ResultItem(it, true) }
                        }
                        if (fm.isNotEmpty()) {
                            item { Spacer(Modifier.height(16.dp)); BeesSectionHeader("🔍 Manual Checks") }
                            items(fm) { link ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openUrl(context, link) },
                                    colors = CardDefaults.cardColors(containerColor = cardBg),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                                        Text("↗", fontSize = 16.sp, color = BeeColors.DeepAmber)
                                        Spacer(Modifier.width(8.dp))
                                        Text(link, fontSize = 12.sp, color = beeAdapt(Color(0xFF4E3B00), BeeColors.DarkOnSurface))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// REUSABLE COMPOSABLES
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun beeTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BeeColors.DeepAmber, unfocusedBorderColor = BeeColors.HoneyGold,
    focusedLabelColor = BeeColors.DeepAmber, cursorColor = BeeColors.DeepAmber,
    focusedTextColor = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface),
    unfocusedTextColor = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface),
)

@Composable
fun BeeRadioOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (selected) beeAdapt(BeeColors.BeeBlack, BeeColors.DarkStripe) else Color.Transparent
    val textColor = if (selected) BeeColors.HoneyGold else beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold)
    Surface(
        modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(8.dp), color = bg,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, if (selected) beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold) else BeeColors.DeepAmber)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(label, color = textColor, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
        }
    }
}

@Composable
fun BeesSectionHeader(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(Modifier.weight(1f).height(2.dp).background(BeeColors.HoneyGold))
        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold), letterSpacing = 1.5.sp, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(2.dp).background(BeeColors.HoneyGold))
    }
}

@Composable
fun ResultItem(result: SearchResult, showDetails: Boolean = false) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openUrl(context, result.url) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (result.found) beeAdapt(Color(0xFFF1F8E9), Color(0xFF1B2A10)) else beeAdapt(Color(0xFFFFF8E1), BeeColors.DarkCell)),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(5.dp).height(72.dp).background(if (result.found) BeeColors.FoundGreen else BeeColors.DeepAmber, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)))
            Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (result.found) "✅" else "🟡", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (result.found) "Found!" else "Not Found", fontWeight = FontWeight.Bold,
                        color = if (result.found) beeAdapt(BeeColors.FoundGreen, BeeColors.FoundGreenDark) else beeAdapt(Color(0xFF8D5A00), BeeColors.NotFoundAmber)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(result.url, fontSize = 11.sp, color = beeAdapt(Color(0xFF795548), Color(0xFFBCAAA4)))
                if (showDetails && result.foundDetails != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(result.foundDetails, fontSize = 12.sp, color = beeAdapt(Color(0xFF4E342E), BeeColors.DarkOnSurface), fontWeight = FontWeight.Medium)
                }
                if (result.errorMessage != null)
                    Text("⚠️ ${result.errorMessage}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            if (result.found) IconButton(onClick = { copyToClipboard(context, result.url) }) {
                Icon(Icons.Default.ContentCopy, "Copy", tint = BeeColors.DeepAmber)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SUBTITLES SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun SubtitlesScreen(scanner: DvoraScanner, onBack: () -> Unit, onToggleDark: () -> Unit, modifier: Modifier = Modifier) {
    val isDark     = LocalDarkMode.current.value
    val headerBg   = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkComb)
    val scaffoldBg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)

    var searchTerm  by remember { mutableStateOf("") }
    var searchType  by remember { mutableStateOf(SourceType.SHOW) }
    var results     by remember { mutableStateOf<List<SubtitleResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    LaunchedEffect(searchTerm, searchType) {
        if (searchTerm.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(400); isSearching = true
        results = scanner.scanSubtitles(searchTerm, searchType)
        isSearching = false
    }

    Column(modifier = modifier.fillMaxSize().background(scaffoldBg).padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(headerBg, RoundedCornerShape(12.dp)).padding(4.dp)
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BeeColors.HoneyGold) }
            Text("🎞️  Hebrew Subtitles", style = MaterialTheme.typography.titleLarge, color = BeeColors.HoneyGold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleDark) { Icon(if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme", tint = BeeColors.HoneyGold) }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = searchTerm, onValueChange = { searchTerm = it }, label = { Text("Movie or Show Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = beeTextFieldColors())
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BeeRadioOption("📺 Shows", searchType == SourceType.SHOW, { searchType = SourceType.SHOW }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            BeeRadioOption("🎬 Movies", searchType == SourceType.MOVIE, { searchType = SourceType.MOVIE }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        if (isSearching) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), BeeColors.DeepAmber, trackColor = BeeColors.DeepAmber.copy(alpha = 0.2f))
            Spacer(Modifier.height(12.dp))
        } else Spacer(Modifier.height(4.dp))
        if (results.isEmpty() && !isSearching && searchTerm.isNotBlank()) {
            Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                Text("No Hebrew subtitles found for \"$searchTerm\"", color = beeAdapt(Color(0xFF8D5A00), BeeColors.HoneyGold.copy(alpha = 0.7f)), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(results) { SubtitleResultCard(it) }
        }
    }
}

@Composable
fun SubtitleResultCard(item: SubtitleResult) {
    val context   = LocalContext.current
    val cardBg    = beeAdapt(Color(0xFFF1F8E9), Color(0xFF1B2A10))
    val textColor = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val subColor  = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.7f))

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { openUrl(context, item.url) },
        shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(3.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BeeColors.FoundGreen.copy(alpha = 0.35f))
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.width(70.dp).height(100.dp)
                    .background(beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe), RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (item.posterUrl != null) AsyncImage(
                    model = ImageRequest.Builder(context).data(item.posterUrl).crossfade(true).build(),
                    contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                ) else Text(if (item.type == "movie") "🎬" else "📺", fontSize = 26.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = textColor, maxLines = 2, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = BeeColors.FoundGreen.copy(alpha = 0.15f), border = androidx.compose.foundation.BorderStroke(1.dp, BeeColors.FoundGreen.copy(alpha = 0.5f))) {
                        Text("✓ SUBS", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = beeAdapt(BeeColors.FoundGreen, BeeColors.FoundGreenDark), modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                }
                if (item.titleHe != null) Text(item.titleHe, fontSize = 12.sp, color = subColor, modifier = Modifier.padding(top = 1.dp))
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (item.year != null) Text(item.year.toString(), fontSize = 12.sp, color = BeeColors.HoneyGold, fontWeight = FontWeight.Bold)
                    if (item.rating != null) Text("⭐ ${"%.1f".format(item.rating.toFloatOrNull() ?: 0f)}", fontSize = 12.sp, color = BeeColors.HoneyGold, fontWeight = FontWeight.SemiBold)
                    if (item.type != null) Surface(shape = RoundedCornerShape(4.dp), color = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe)) {
                        Text(if (item.type == "movie") "Movie" else "TV", fontSize = 10.sp, color = beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                    }
                }
                if (item.genres != null) { Spacer(Modifier.height(3.dp)); Text(item.genres, fontSize = 11.sp, color = subColor, maxLines = 1) }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) { Text("IMDb", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black) }
                    Spacer(Modifier.width(5.dp))
                    Text(item.imdbId, fontSize = 10.sp, color = subColor)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { openUrl(context, item.url) }) { Icon(Icons.Default.OpenInNew, "Open", tint = BeeColors.FoundGreenDark) }
                IconButton(onClick = { copyToClipboard(context, item.url) }) { Icon(Icons.Default.ContentCopy, "Copy", tint = BeeColors.DeepAmber) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// IMDB SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun ImdbScreen(scanner: DvoraScanner, onBack: () -> Unit, onToggleDark: () -> Unit, modifier: Modifier = Modifier) {
    val context    = LocalContext.current
    val isDark     = LocalDarkMode.current.value
    val headerBg   = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkComb)
    val scaffoldBg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)

    var searchTerm  by remember { mutableStateOf("") }
    var results     by remember { mutableStateOf<List<ImdbResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMsg    by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    LaunchedEffect(searchTerm) {
        if (searchTerm.isBlank()) { results = emptyList(); errorMsg = null; return@LaunchedEffect }
        delay(350); isSearching = true; errorMsg = null
        val found = scanner.searchImdb(searchTerm); results = found
        errorMsg = if (found.isEmpty()) "No results found for \"$searchTerm\"" else null
        isSearching = false
    }

    Column(modifier = modifier.fillMaxSize().background(scaffoldBg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(headerBg).padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BeeColors.HoneyGold) }
            Box(Modifier.padding(horizontal = 4.dp).background(Color(0xFFF5C518), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
                Text("IMDb", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
            }
            Text("  Search", style = MaterialTheme.typography.titleLarge, color = BeeColors.HoneyGold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleDark) { Icon(if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme", tint = BeeColors.HoneyGold) }
        }
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(value = searchTerm, onValueChange = { searchTerm = it }, label = { Text("Movie or Show Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = beeTextFieldColors())
            if (isSearching) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), Color(0xFFF5C518), trackColor = Color(0xFFF5C518).copy(alpha = 0.2f)) }
        }
        if (errorMsg != null) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(errorMsg!!, color = beeAdapt(Color(0xFF8D5A00), BeeColors.HoneyGold.copy(alpha = 0.7f)))
        }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(results) { ImdbResultCard(it) }
        }
    }
}

@Composable
fun ImdbResultCard(item: ImdbResult) {
    val context      = LocalContext.current
    val cardBg       = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell)
    val textColor    = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val subColor     = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.7f))
    val isBookmarked = BookmarksManager.isBookmarked(item.imdbId)

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { openUrl(context, item.imdbUrl) },
        shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            if (item.posterUrl != null) {
                Box(Modifier.width(70.dp).height(100.dp).background(beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe), RoundedCornerShape(8.dp))) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(item.posterUrl).crossfade(true).build(),
                        contentDescription = item.title, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                    )
                }
            } else {
                Box(Modifier.width(70.dp).height(100.dp).background(beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("🎬", fontSize = 26.sp) }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = textColor, maxLines = 2, modifier = Modifier.weight(1f))
                    IconButton(onClick = { copyToClipboard(context, item.title) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, "Copy title", tint = BeeColors.DeepAmber, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.year != null) Text(item.year, fontSize = 12.sp, color = BeeColors.HoneyGold, fontWeight = FontWeight.SemiBold)
                    if (item.mediaType != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFF5C518)) {
                            Text(item.mediaType, fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) { Text("IMDb", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black) }
                    Spacer(Modifier.width(5.dp))
                    Text(item.imdbId, fontSize = 11.sp, color = subColor, modifier = Modifier.weight(1f))
                    IconButton(onClick = { copyToClipboard(context, item.imdbId) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, "Copy ID", tint = Color(0xFFF5C518), modifier = Modifier.size(15.dp))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = {
                    BookmarksManager.toggle(context, item)
                    Toast.makeText(context, if (BookmarksManager.isBookmarked(item.imdbId)) "Bookmarked!" else "Removed", Toast.LENGTH_SHORT).show()
                }) { Icon(if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Bookmark", tint = if (isBookmarked) BeeColors.HoneyGold else BeeColors.DeepAmber) }
                IconButton(onClick = { copyToClipboard(context, item.imdbUrl) }) { Icon(Icons.Default.ContentCopy, "Copy link", tint = BeeColors.DeepAmber) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// BOOKMARKS SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(onBack: () -> Unit, onToggleDark: () -> Unit, modifier: Modifier = Modifier) {
    val context    = LocalContext.current
    val isDark     = LocalDarkMode.current.value
    val headerBg   = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkComb)
    val scaffoldBg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)
    val bookmarks  = BookmarksManager.bookmarks

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "Notification permission needed for reminders", Toast.LENGTH_SHORT).show()
    }

    var datePickerTargetId by remember { mutableStateOf<String?>(null) }
    var timePickerTargetId by remember { mutableStateOf<String?>(null) }
    var selectedDateStr    by remember { mutableStateOf("") }
    var selectedRecurrence by remember { mutableStateOf("ONCE") }

    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis() + 86_400_000L)
    val timePickerState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)

    // ── Date Picker Dialog ────────────────────────────────────────────────────
    if (datePickerTargetId != null) {
        DatePickerDialog(
            onDismissRequest = { datePickerTargetId = null },
            confirmButton = {
                TextButton(onClick = {
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        val selectedDate = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        val today = java.time.LocalDate.now()
                        if (selectedDate.isBefore(today)) {
                            Toast.makeText(context, "Please select today or a future date", Toast.LENGTH_SHORT).show()
                        } else {
                            selectedDateStr    = selectedDate.toString()
                            timePickerTargetId = datePickerTargetId
                            datePickerTargetId = null
                        }
                    } else {
                        datePickerTargetId = null
                    }
                }) { Text("Next: Pick Time", color = BeeColors.DeepAmber, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { datePickerTargetId = null }) { Text("Cancel", color = BeeColors.DeepAmber) }
            }
        ) { DatePicker(state = datePickerState) }
    }

    // ── Time + Recurrence Picker Dialog ───────────────────────────────────────
    if (timePickerTargetId != null) {
        var useKeyboard by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { timePickerTargetId = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("⏰ Pick Reminder Time", fontWeight = FontWeight.Bold, color = beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold), modifier = Modifier.weight(1f))
                    IconButton(onClick = { useKeyboard = !useKeyboard }) {
                        Icon(
                            if (useKeyboard) Icons.Default.Schedule else Icons.Default.Keyboard,
                            if (useKeyboard) "Switch to dial" else "Switch to keyboard",
                            tint = BeeColors.DeepAmber
                        )
                    }
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (useKeyboard) TimeInput(state = timePickerState)
                    else TimePicker(state = timePickerState)

                    Spacer(Modifier.height(16.dp))
                    Text("🔁  Repeat", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("ONCE" to "Once", "DAILY" to "Daily", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly").forEach { (value, label) ->
                            val isSelected = selectedRecurrence == value
                            Surface(
                                modifier = Modifier.weight(1f).clickable { selectedRecurrence = value },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) beeAdapt(BeeColors.BeeBlack, BeeColors.DarkStripe) else Color.Transparent,
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, if (isSelected) BeeColors.DeepAmber else BeeColors.HoneyGold.copy(alpha = 0.5f))
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                    Text(
                                        label, fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) BeeColors.HoneyGold else beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold.copy(alpha = 0.7f))
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val timeStr  = "%02d:%02d".format(timePickerState.hour, timePickerState.minute)
                    val targetId = timePickerTargetId!!

                    val now        = java.time.LocalDateTime.now()
                    val selectedDT = java.time.LocalDate.parse(selectedDateStr).atTime(timePickerState.hour, timePickerState.minute)
                    if (!selectedDT.isAfter(now)) {
                        Toast.makeText(context, "Please select a future time", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }

                    BookmarksManager.setReminder(context, targetId, selectedDateStr, timeStr, selectedRecurrence)
                    val formatted = java.time.LocalDate.parse(selectedDateStr)
                        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
                    val recLabel = when (selectedRecurrence) {
                        "DAILY"   -> " · Repeats daily"
                        "WEEKLY"  -> " · Repeats weekly"
                        "MONTHLY" -> " · Repeats monthly"
                        else      -> ""
                    }
                    Toast.makeText(context, "⏰ Reminder: $formatted at $timeStr$recLabel", Toast.LENGTH_LONG).show()
                    timePickerTargetId = null
                }) { Text("Set Reminder", color = BeeColors.DeepAmber, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { timePickerTargetId = null }) { Text("Cancel", color = BeeColors.DeepAmber) }
            }
        )
    }

    fun requestReminder(imdbId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        selectedRecurrence = "ONCE"
        datePickerTargetId = imdbId
    }

    BackHandler { onBack() }

    Column(modifier = modifier.fillMaxSize().background(scaffoldBg)) {

        // Header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(headerBg).padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BeeColors.HoneyGold) }
            Icon(Icons.Default.Bookmarks, null, tint = BeeColors.HoneyGold, modifier = Modifier.size(22.dp))
            Text("  Bookmarks", style = MaterialTheme.typography.titleLarge, color = BeeColors.HoneyGold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${bookmarks.size}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BeeColors.HoneyGold.copy(alpha = 0.7f), modifier = Modifier.padding(end = 4.dp))
            IconButton(onClick = onToggleDark) { Icon(if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme", tint = BeeColors.HoneyGold) }
        }

        if (bookmarks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔖", fontSize = 52.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("No bookmarks yet.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = beeAdapt(Color(0xFF8D5A00), BeeColors.HoneyGold.copy(alpha = 0.7f)))
                    Spacer(Modifier.height(6.dp))
                    Text("Tap ⭐ on any IMDb result to bookmark it.", fontSize = 13.sp, color = beeAdapt(Color(0xFFAA8800), BeeColors.HoneyGold.copy(alpha = 0.5f)), textAlign = TextAlign.Center)
                }
            }
            return@Column
        }

        val withReminder    = bookmarks.filter { it.reminderDate != null }.sortedBy { it.reminderDate }
        val withoutReminder = bookmarks.filter { it.reminderDate == null }

        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {

            if (withReminder.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp, top = 2.dp)) {
                        Text("⏰", fontSize = 16.sp); Spacer(Modifier.width(6.dp))
                        Text("UPCOMING REMINDERS", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, color = BeeColors.DeepAmber)
                    }
                }
                items(withReminder) { bm ->
                    BookmarkCard(bm = bm, context = context, onSetReminder = { requestReminder(bm.imdbId) }, onClearReminder = { BookmarksManager.clearReminder(context, bm.imdbId) })
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = BeeColors.HoneyGold.copy(alpha = 0.3f))
                    Spacer(Modifier.height(12.dp))
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Text("🔖", fontSize = 16.sp); Spacer(Modifier.width(6.dp))
                    Text("ALL BOOKMARKS  (${bookmarks.size})", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, color = beeAdapt(Color(0xFF8D5A00), BeeColors.HoneyGold.copy(alpha = 0.7f)))
                }
            }
            items(withoutReminder + withReminder) { bm ->
                BookmarkCard(bm = bm, context = context, onSetReminder = { requestReminder(bm.imdbId) }, onClearReminder = { BookmarksManager.clearReminder(context, bm.imdbId) })
            }
        }
    }
}

@Composable
fun BookmarkCard(bm: Bookmark, context: Context, onSetReminder: () -> Unit, onClearReminder: () -> Unit) {
    val cardBg      = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell)
    val textColor   = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val subColor    = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.7f))
    val hasReminder = bm.reminderDate != null

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { openUrl(context, bm.imdbUrl) },
        shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(2.dp),
        border = if (hasReminder) androidx.compose.foundation.BorderStroke(1.5.dp, BeeColors.DeepAmber.copy(alpha = 0.5f)) else null
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {

            // Poster
            Box(
                Modifier.width(60.dp).height(85.dp)
                    .background(beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe), RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (bm.posterUrl != null) AsyncImage(
                    model = ImageRequest.Builder(context).data(bm.posterUrl).crossfade(true).build(),
                    contentDescription = bm.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                ) else Text(if (bm.mediaType?.contains("movie", ignoreCase = true) == true) "🎬" else "📺", fontSize = 22.sp)
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Title + copy
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(bm.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = textColor, maxLines = 2, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        copyToClipboard(context, bm.title)
                        Toast.makeText(context, "Title copied", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, "Copy title", tint = BeeColors.DeepAmber, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))

                // Chips
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (bm.year != null) Text(bm.year, fontSize = 12.sp, color = BeeColors.HoneyGold, fontWeight = FontWeight.SemiBold)
                    if (bm.mediaType != null) Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFF5C518)) {
                        Text(bm.mediaType, fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                }

                // Reminder badge
                if (hasReminder) {
                    Spacer(Modifier.height(5.dp))
                    val formattedDate = try {
                        val d = java.time.LocalDate.parse(bm.reminderDate)
                        d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
                    } catch (_: Exception) { bm.reminderDate ?: "" }
                    val timeLabel = bm.reminderTime ?: "09:00"
                    val isRecurring = bm.reminderRecurrence != null && bm.reminderRecurrence != "ONCE"

                    // Line 1: date + time + cancel button
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = BeeColors.DeepAmber.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BeeColors.DeepAmber.copy(alpha = 0.5f)),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text("⏰", fontSize = 11.sp)
                                Spacer(Modifier.width(4.dp))
                                Text("$formattedDate  $timeLabel", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BeeColors.DeepAmber)
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = {
                            onClearReminder()
                            Toast.makeText(context, "Reminder cancelled", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Cancel reminder", tint = BeeColors.DeepAmber.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                        }
                    }

                    // Line 2: recurrence label (only for recurring)
                    if (isRecurring) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = BeeColors.DeepAmber.copy(alpha = 0.1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BeeColors.DeepAmber.copy(alpha = 0.35f))
                        ) {
                            val recText = when (bm.reminderRecurrence) {
                                "DAILY"   -> "🔁 Repeats Daily"
                                "WEEKLY"  -> "🔁 Repeats Weekly"
                                "MONTHLY" -> "🔁 Repeats Monthly"
                                else      -> ""
                            }
                            Text(
                                recText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = BeeColors.DeepAmber,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                // IMDb ID + copy
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                        Text("IMDb", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(bm.imdbId, fontSize = 10.sp, color = subColor, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        copyToClipboard(context, bm.imdbId)
                        Toast.makeText(context, "IMDb ID copied", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, "Copy IMDb ID", tint = Color(0xFFF5C518), modifier = Modifier.size(15.dp))
                    }
                }
            }

            // Actions
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onSetReminder, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (hasReminder) Icons.Default.NotificationsActive else Icons.Default.NotificationAdd,
                        if (hasReminder) "Change reminder" else "Set reminder",
                        tint = if (hasReminder) BeeColors.DeepAmber else BeeColors.HoneyGold,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { openUrl(context, bm.imdbUrl) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.OpenInNew, "Open IMDb", tint = BeeColors.DeepAmber, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = {
                    BookmarksManager.toggle(context, ImdbResult(bm.imdbId, bm.title, bm.year, bm.mediaType, bm.posterUrl, bm.imdbUrl))
                }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.BookmarkRemove, "Remove", tint = BeeColors.HoneyGold, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SETTINGS SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun SettingsScreen(
    shows: List<String>, movies: List<String>, manualChecks: List<String>,
    apiSites: List<String>, exclusions: List<String>,
    onUpdate: (SourceType, List<String>) -> Unit,
    onBack: () -> Unit, onToggleDark: () -> Unit, modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs     = listOf("Shows", "Movies", "APIs", "Manual", "Exclusions", "Logs")
    val isDark   = LocalDarkMode.current.value
    val headerBg = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkComb)
    val bgColor  = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)

    Column(modifier = modifier.fillMaxSize().background(bgColor)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(headerBg).padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BeeColors.HoneyGold) }
            Text("🏮 MY HIVE", style = MaterialTheme.typography.titleLarge, color = BeeColors.HoneyGold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleDark) { Icon(if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme", tint = BeeColors.HoneyGold) }
        }
        ScrollableTabRow(
            selectedTabIndex = selectedTab, edgePadding = 16.dp,
            containerColor = headerBg, contentColor = BeeColors.HoneyGold,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    height = 3.dp,
                    color = BeeColors.HoneyGold
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = {
                    Text(title, color = if (selectedTab == index) BeeColors.HoneyGold else BeeColors.HoneyGold.copy(alpha = 0.45f), fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal)
                })
            }
        }
        when (selectedTab) {
            0 -> SourceEditor(shows) { onUpdate(SourceType.SHOW, it) }
            1 -> SourceEditor(movies) { onUpdate(SourceType.MOVIE, it) }
            2 -> SourceEditor(apiSites) { onUpdate(SourceType.API, it) }
            3 -> SourceEditor(manualChecks) { onUpdate(SourceType.MANUAL, it) }
            4 -> SourceEditor(exclusions) { onUpdate(SourceType.EXCLUSION, it) }
            5 -> VerboseLogsScreen()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SOURCE EDITOR
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun SourceEditor(list: List<String>, onUpdate: (List<String>) -> Unit) {
    var newItem      by remember { mutableStateOf("") }
    var editingIndex by remember { mutableIntStateOf(-1) }
    val context      = LocalContext.current
    val bgColor      = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)
    val textColor    = beeAdapt(Color(0xFF4E3B00), BeeColors.DarkOnSurface)
    val rowAlt       = beeAdapt(BeeColors.HoneycombYellow.copy(alpha = 0.4f), BeeColors.DarkStripe.copy(alpha = 0.6f))

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val lines = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readLines() } ?: emptyList()
                val clean = lines.map { l -> l.trim() }.filter { l -> l.isNotBlank() }
                if (clean.isNotEmpty()) onUpdate((list + clean).distinct())
            } catch (_: Exception) { Toast.makeText(context, "Import failed", Toast.LENGTH_SHORT).show() }
        }
    }

    Column(modifier = Modifier.padding(16.dp).background(bgColor)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newItem, onValueChange = { newItem = it },
                label = { Text(if (editingIndex == -1) "Add Item" else "Edit Item") },
                modifier = Modifier.weight(1f), colors = beeTextFieldColors()
            )
            IconButton(onClick = {
                if (newItem.isNotBlank()) {
                    if (editingIndex == -1) onUpdate((list + newItem.trim()).distinct())
                    else { val m = list.toMutableList(); m[editingIndex] = newItem.trim(); onUpdate(m.toList()); editingIndex = -1 }
                    newItem = ""
                }
            }) { Icon(if (editingIndex == -1) Icons.Default.Add else Icons.Default.Check, null, tint = BeeColors.DeepAmber) }
            if (editingIndex == -1) IconButton(onClick = { filePickerLauncher.launch("text/plain") }) { Icon(Icons.Default.FileUpload, null, tint = BeeColors.DeepAmber) }
            else IconButton(onClick = { editingIndex = -1; newItem = "" }) { Icon(Icons.Default.Close, null, tint = BeeColors.DeepAmber) }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn {
            itemsIndexed(list) { index, item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .background(if (index % 2 == 0) rowAlt else Color.Transparent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("🔗 ", fontSize = 12.sp)
                    Text(item, modifier = Modifier.weight(1f).clickable { newItem = item; editingIndex = index }, fontSize = 13.sp, color = textColor)
                    IconButton(onClick = { onUpdate(list - item) }) { Icon(Icons.Default.Delete, null, tint = BeeColors.DeepAmber.copy(alpha = 0.7f)) }
                }
                HorizontalDivider(color = BeeColors.HoneyGold.copy(alpha = 0.25f))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// VERBOSE LOGS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun VerboseLogsScreen() {
    val logs    = SearchLogs.lastLogs
    val bgColor = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)
    if (logs.isEmpty()) {
        Box(Modifier.fillMaxSize().background(bgColor), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🐝", fontSize = 48.sp)
                Spacer(Modifier.height(8.dp))
                Text("No logs from last search.", color = beeAdapt(Color(0xFF8D5A00), BeeColors.HoneyGold.copy(alpha = 0.7f)))
            }
        }
    } else {
        LazyColumn(Modifier.padding(16.dp).background(bgColor)) { items(logs) { LogItem(it) } }
    }
}

@Composable
fun LogItem(log: SearchResult) {
    var expanded by remember { mutableStateOf(false) }
    val cardColor    = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell)
    val titleColor   = beeAdapt(Color(0xFF3E2800), BeeColors.DarkOnSurface)
    val labelColor   = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.75f))
    val monoColor    = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.7f))
    val verboseColor = beeAdapt(Color(0xFF4E342E), BeeColors.DarkOnSurface.copy(alpha = 0.6f))

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = cardColor), elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (log.found) "✅" else "🟡", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(log.url, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = titleColor, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = BeeColors.DeepAmber)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text("Status: ", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = labelColor)
                Text(
                    if (log.found) "FOUND" else "NOT FOUND",
                    color = if (log.found) beeAdapt(BeeColors.FoundGreen, BeeColors.FoundGreenDark) else BeeColors.PollenOrange,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
            Text("Details: ${log.foundDetails ?: "No details available."}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = monoColor)
            if (expanded && log.verboseLogs != null) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = BeeColors.HoneyGold.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))
                Text(log.verboseLogs, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = verboseColor)
            }
            if (log.errorMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text("⚠️ ${log.errorMessage}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// STORAGE HELPERS
// ═══════════════════════════════════════════════════════════════════════════════

fun saveSources(context: Context, key: String, sources: List<String>) {
    context.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE).edit().putStringSet(key, sources.toSet()).apply()
}

fun loadSources(context: Context, key: String): List<String> {
    val prefs = context.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)
    if (!prefs.contains(key)) {
        return when (key) {
            "shows" -> listOf(
                "+https://ww25.soap2day.day/?s=", "-https://myflixerz.to/search/", "-https://himovies.sx/search/",
                "+https://www.lookmovie2.to/shows/search/?q=", "+https://westream.to/search?keyword=",
                "+https://1movies.bz/browser?keyword=", "+https://yflix.to/browser?keyword=",
                "+https://hianime.city/?s=", "+https://gogoanime.by/?s=",
                "+https://aniwatchtv.to/search?keyword=", "+https://hianime.dk/filter?keyword="
            )
            "movies" -> listOf(
                "+https://ww25.soap2day.day/?s=", "-https://myflixerz.to/search/", "-https://himovies.sx/search/",
                "+https://www.lookmovie2.to/movies/search/?q=", "+https://westream.to/search?keyword=",
                "+https://1movies.bz/browser?keyword=", "+https://yflix.to/browser?keyword="
            )
            "manual_checks" -> listOf(
                "+https://tmovie.tv/search?query=", "+https://www.1flex.nl/search?q=",
                "+https://ww4.fmovies.co/search/?q=", "+https://ww8.123moviesfree.net/search/?q="
            )
            "api_sites" -> listOf(
                "v1:https://ww8.123moviesfree.net", "v1:https://ww4.fmovies.co", "stremio:https://v3-cinemeta.strem.io"
            )
            "exclusions" -> listOf(
                "addtoany.com", "facebook.com", "twitter.com", "reddit.com",
                "pinterest.com", "whatsapp.com", "t.me", "mailto:",
                "/login", "/register", "/signup", "/feed", "#", "/filter", "/search", "/browser", "/?s="
            )
            else -> emptyList()
        }
    }
    return prefs.getStringSet(key, null)?.toList() ?: emptyList()
}