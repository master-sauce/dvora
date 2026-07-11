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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.app.AlarmManager
import android.provider.Settings
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll


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

// IDM (Internet Download Manager) packages — tried in order before the default browser.
private val IDM_PACKAGES = listOf(
    "idm.internet.download.manager.plus",
    "idm.internet.download.manager"
)

// Brave browser package - tried after IDM, before the default browser.
private const val BRAVE_PACKAGE = "com.brave.browser"

/**
 * Attempts to open [url] in one of the installed IDM apps by pinning the
 * ACTION_VIEW intent to each package in turn. Returns true if any IDM app
 * accepted the intent, false otherwise (caller should fall back to browser).
 */
private fun tryOpenInIdm(context: Context, url: String): Boolean {
    val cleanUrl = if (url.startsWith("http")) url else "https://$url"
    for (pkg in IDM_PACKAGES) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // resolveActivity returns null if the package isn't installed or
            // can't handle the intent — avoids an exception on Android 11+.
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        } catch (_: Exception) {
            // This IDM variant isn't installed or refused — try the next one.
        }
    }
    return false
}

/**
 * Attempts to open [url] in the Brave browser by pinning the ACTION_VIEW
 * intent to its package. Returns true if Brave accepted the intent, false
 * otherwise (caller should fall back to the default browser).
 */
private fun tryOpenInBrave(context: Context, url: String): Boolean {
    val cleanUrl = if (url.startsWith("http")) url else "https://$url"
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
            setPackage(BRAVE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // resolveActivity returns null if Brave isn't installed or can't
        // handle the intent - avoids an exception on Android 11+.
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return true
        }
    } catch (_: Exception) {
        // Brave isn't installed or refused - fall back to default browser.
    }
    return false
}

fun openUrl(context: Context, url: String) {
    try {
        val cleanUrl = if (url.startsWith("http")) url else "https://$url"

        // 1) Stremio web URLs go to the Stremio app (NOT a download manager).
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
                    // Stremio not installed, fall through to IDM / browser
                }
            }
        }

        // 2) Try IDM apps (download manager) first.
        if (tryOpenInIdm(context, cleanUrl)) return

        // 3) Try Brave browser next.
        if (tryOpenInBrave(context, cleanUrl)) return

        // 4) Fall back to the default browser
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
    var imdbResults  by remember { mutableStateOf<List<ImdbResult>>(emptyList()) }
    var manualLinks  by remember { mutableStateOf<List<String>>(emptyList()) }
    var isSearching  by remember { mutableStateOf(false) }
    var domainFilter by remember { mutableStateOf("") }
    var imdbSuggestions by remember { mutableStateOf<List<ImdbResult>>(emptyList()) }
    var showImdbDropdown by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val searchFieldFocusRequester = remember { FocusRequester() }
    var justSelectedSuggestion by remember { mutableStateOf(false) }
    var dismissedForTerm by remember { mutableStateOf("") }

    // Add this LaunchedEffect to trigger IMDb search as the user types
    LaunchedEffect(searchTerm) {
        if (searchTerm.isBlank()) {
            imdbSuggestions = emptyList()
            showImdbDropdown = false
            return@LaunchedEffect
        }

        // Don't show dropdown if we just selected a suggestion
        if (justSelectedSuggestion) {
            justSelectedSuggestion = false
            return@LaunchedEffect
        }

        delay(500) // Debounce to avoid too many requests
        val suggestions = scanner.searchImdb(searchTerm)
        imdbSuggestions = suggestions
        showImdbDropdown = suggestions.isNotEmpty()
    }

    var shows        by remember { mutableStateOf(loadSources(context, "shows")) }
    var movies       by remember { mutableStateOf(loadSources(context, "movies")) }
    var manualChecks by remember { mutableStateOf(loadSources(context, "manual_checks")) }
    var apiSites     by remember { mutableStateOf(loadSources(context, "api_sites")) }
    var exclusions   by remember { mutableStateOf(loadSources(context, "exclusions")) }

    // Load user-defined custom API types (v2, v3, etc.)
    LaunchedEffect(Unit) { CustomApiTypeManager.load(context) }

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
                            Column {
                                OutlinedTextField(
                                    value = searchTerm,
                                    onValueChange = { searchTerm = it },
                                    label = { Text("🍯 Search Movie or Show") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(searchFieldFocusRequester),
                                    singleLine = true
                                    , colors = beeTextFieldColors()
                                )

                                // IMDb suggestions dropdown
                                if (showImdbDropdown && imdbSuggestions.isNotEmpty() && searchTerm != dismissedForTerm) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = cardBg),
                                        elevation = CardDefaults.cardElevation(4.dp)
                                    ) {
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Suggestions", fontSize = 11.sp, color = beeAdapt(Color(0xFF4E3B00), BeeColors.DarkOnSurface), modifier = Modifier.weight(1f))
                                                IconButton(onClick = { dismissedForTerm = searchTerm }, modifier = Modifier.size(28.dp)) {
                                                    Icon(Icons.Default.Close, "Dismiss", tint = BeeColors.DeepAmber, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                            LazyColumn(
                                                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                                            ) {
                                                items(imdbSuggestions.take(5)) { suggestion ->
                                                    ImdbSuggestionItem(
                                                        suggestion = suggestion,
                                                        onSelect = {
                                                            searchTerm = suggestion.title
                                                            showImdbDropdown = false
                                                            justSelectedSuggestion = true
                                                            focusManager.clearFocus()
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
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
                            isSearching = true; results = emptyList(); apiResults = emptyList(); imdbResults = emptyList(); domainFilter = ""
                            scope.launch {
                                val activeSources = if (searchType == SourceType.SHOW) shows else movies
                                activeSources.forEach { source -> results = results + scanner.scanSite(source, searchTerm, exclusions) }
                                apiSites.forEach { site ->
                                    val entry = parseApiEntry(site)
                                    val newResults = when (entry.type) {
                                        "stremio" -> scanner.scanStremio(entry.apiUrl, searchTerm, searchType)
                                        else      -> scanner.scanV1(entry.apiUrl, searchTerm, entry.landingUrl, entry.matchKeys)
                                    }
                                    apiResults = apiResults + newResults
                                }
                                imdbResults = scanner.searchImdb(searchTerm)
                                SearchLogs.lastLogs = results + apiResults
                                manualLinks = manualChecks.map { scanner.getManualCheck(it) }
                                isSearching = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !isSearching,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) BeeColors.HoneyGold else headerBg,
                            contentColor = if (isDark) Color.Black else BeeColors.HoneyGold,
                            disabledContainerColor = if (isDark) BeeColors.HoneyGold.copy(alpha = 0.3f) else Color(0xFF4A3B00),
                            disabledContentColor = if (isDark) Color.Black.copy(alpha = 0.4f) else BeeColors.HoneyGold.copy(alpha = 0.4f)
                        )
                    ) {
                        if (isSearching) CircularProgressIndicator(Modifier.size(24.dp), BeeColors.HoneyGold, strokeWidth = 2.dp)
                        else Text("🐝  BUZZ & SEARCH", fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = if (isDark) Color.Black else BeeColors.HoneyGold)
                    }
                    Spacer(Modifier.height(16.dp))
                    val hasAnyResults = results.isNotEmpty() || apiResults.isNotEmpty() || manualLinks.isNotEmpty() || imdbResults.isNotEmpty()
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
                        val fi = if (filter.isEmpty()) imdbResults else imdbResults.filter {
                            it.title.lowercase().contains(filter) ||
                                    it.imdbId.lowercase().contains(filter)
                        }
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

@Composable
fun ImdbResultItem(item: ImdbResult) {
    val context = LocalContext.current
    val isBookmarked = BookmarksManager.isBookmarked(item.imdbId)
    val cardBg = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell)

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openUrl(context, item.imdbUrl) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.width(5.dp).height(72.dp)
                    .background(Color(0xFFF5C518), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
            )

            // Poster if available
            if (item.posterUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(item.posterUrl).crossfade(true).build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                )
            } else {
                Box(
                    modifier = Modifier.size(72.dp).background(
                        beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe),
                        RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (item.mediaType?.contains("movie", true) == true) "🎬" else "📺", fontSize = 26.sp)
                }
            }

            Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎬", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.title,
                        fontWeight = FontWeight.Bold,
                        color = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface),
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(4.dp))

                Row {
                    if (item.year != null) {
                        Text(
                            item.year,
                            fontSize = 12.sp,
                            color = BeeColors.HoneyGold,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                    }

                    if (item.mediaType != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFF5C518)
                        ) {
                            Text(
                                item.mediaType,
                                fontSize = 10.sp,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    item.imdbId,
                    fontSize = 11.sp,
                    color = beeAdapt(Color(0xFF795548), Color(0xFFBCAAA4))
                )
            }

            Column {
                IconButton(onClick = {
                    copyToClipboard(context, item.imdbUrl)
                }) {
                    Icon(Icons.Default.ContentCopy, "Copy URL", tint = BeeColors.DeepAmber)
                }

                IconButton(onClick = {
                    BookmarksManager.toggle(context, item)
                    val message = if (BookmarksManager.isBookmarked(item.imdbId)) "Bookmarked!" else "Removed"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        "Bookmark",
                        tint = if (isBookmarked) BeeColors.HoneyGold else BeeColors.DeepAmber
                    )
                }
            }
        }
    }
}

@Composable
fun ImdbSuggestionItem(
    suggestion: ImdbResult,
    onSelect: () -> Unit
) {
    val context = LocalContext.current
    val isBookmarked = BookmarksManager.isBookmarked(suggestion.imdbId)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Poster or placeholder
        if (suggestion.posterUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(suggestion.posterUrl).crossfade(true).build(),
                contentDescription = suggestion.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
            )
        } else {
            Box(
                modifier = Modifier.size(48.dp)
                    .background(beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(if (suggestion.mediaType?.contains("movie", true) == true) "🎬" else "📺", fontSize = 20.sp)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                suggestion.title,
                fontWeight = FontWeight.Bold,
                color = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface),
                maxLines = 1
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (suggestion.year != null) {
                    Text(
                        suggestion.year,
                        fontSize = 12.sp,
                        color = BeeColors.HoneyGold,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                }

                if (suggestion.mediaType != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFF5C518)
                    ) {
                        Text(
                            suggestion.mediaType,
                            fontSize = 10.sp,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Text(
                suggestion.imdbId,
                fontSize = 11.sp,
                color = beeAdapt(Color(0xFF795548), Color(0xFFBCAAA4))
            )
        }

        IconButton(
            onClick = {
                BookmarksManager.toggle(context, suggestion)
                val message = if (BookmarksManager.isBookmarked(suggestion.imdbId)) "Bookmarked!" else "Removed"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        ) {
            Icon(
                if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                "Bookmark",
                tint = if (isBookmarked) BeeColors.HoneyGold else BeeColors.DeepAmber
            )
        }
    }

    HorizontalDivider(color = BeeColors.HoneyGold.copy(alpha = 0.2f))
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
                    Toast.makeText(context, "⏰ Reminder: $formatted at $timeStr $recLabel", Toast.LENGTH_LONG).show()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
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
            2 -> ApiSourcesEditor(apiSites) { onUpdate(SourceType.API, it) }
            3 -> SourceEditor(manualChecks) { onUpdate(SourceType.MANUAL, it) }
            4 -> SourceEditor(exclusions) { onUpdate(SourceType.EXCLUSION, it) }
            5 -> VerboseLogsScreen()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// API ENTRY MODEL & WIZARD
// ═══════════════════════════════════════════════════════════════════════════════

data class ApiEntry(
    val type:       String,          // "v1", "stremio", or "custom"
    val apiUrl:     String,          // base URL used for the search API call
    val landingUrl: String? = null,  // different base URL for the result link
    val matchKeys:  List<String> = emptyList(), // JSON key paths to match against (e.g. "data.title")
    val customName: String? = null   // name of the custom API type (for "custom" type)
) {
    fun toRawString(): String {
        val base = "$type:$apiUrl"
        val withLanding = if (!landingUrl.isNullOrBlank() && landingUrl != apiUrl) "$base|$landingUrl" else base
        val withKeys = if (matchKeys.isNotEmpty()) "$withLanding#${matchKeys.joinToString(",")}" else withLanding
        // Append custom name with '@' separator if present
        return if (!customName.isNullOrBlank() && type == "custom") "$withKeys@${customName}" else withKeys
    }
    fun displayType(): String = when (type) {
        "stremio" -> "Stremio"
        "custom"  -> customName ?: "Custom"
        else      -> "V1 JSON API"
    }
}

fun parseApiEntry(raw: String): ApiEntry {
    val type = when {
        raw.startsWith("stremio:") -> "stremio"
        raw.startsWith("v1:")      -> "v1"
        raw.startsWith("custom:")  -> "custom"
        else                       -> "v1"
    }
    val rest  = raw.removePrefix("$type:")
    // Split off the custom name section (after '@') if present
    val (withoutName, namePart) = if (rest.contains("@")) {
        val idx = rest.indexOf("@")
        rest.substring(0, idx) to rest.substring(idx + 1)
    } else {
        rest to ""
    }
    // Split off the match-keys section (after '#') if present
    val (mainPart, keysPart) = if (withoutName.contains("#")) {
        val idx = withoutName.indexOf("#")
        withoutName.substring(0, idx) to withoutName.substring(idx + 1)
    } else {
        withoutName to ""
    }
    val parts = mainPart.split("|", limit = 2)
    val keys = if (keysPart.isNotBlank()) keysPart.split(",").map { it.trim() }.filter { it.isNotBlank() } else emptyList()
    val cName = namePart.trim().ifBlank { null }
    return ApiEntry(
        type       = type,
        apiUrl     = parts[0].trim(),
        landingUrl = parts.getOrNull(1)?.trim()?.ifBlank { null },
        matchKeys  = keys,
        customName = cName
    )
}

fun extractDomain(url: String): String {
    val noScheme = url.substringAfter("://")
    val host = noScheme.substringBefore("/")
    return host.ifBlank { url.trim() }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CUSTOM API TYPES — user-defined API search profiles (v2, v3, etc.)
// ═══════════════════════════════════════════════════════════════════════════════

data class CustomApiType(
    val id:          String,          // unique id, e.g. "custom_1"
    val name:        String,          // user-facing name, e.g. "My V2 API"
    val apiUrl:      String,          // search API URL template (with DVORA placeholder)
    val landingUrl:  String,          // landing URL template (with DVORA placeholder)
    val matchKeys:   List<String>     // JSON key paths to match against
) {
    fun toRawString(): String {
        // Format: CUSTOM|id|name|apiUrl|landingUrl|key1,key2,key3
        return listOf(
            "CUSTOM", id, name, apiUrl, landingUrl, matchKeys.joinToString(",")
        ).joinToString("|") { it.replace("|", "\\|") }
    }
}

fun parseCustomApiType(raw: String): CustomApiType? {
    if (!raw.startsWith("CUSTOM|")) return null
    val parts = raw.split("|")
    if (parts.size < 6) return null
    val keys = if (parts[5].isNotBlank()) parts[5].split(",").map { it.trim() }.filter { it.isNotBlank() } else emptyList()
    return CustomApiType(
        id         = parts[1],
        name       = parts[2],
        apiUrl     = parts[3],
        landingUrl = parts[4],
        matchKeys  = keys
    )
}

object CustomApiTypeManager {
    private const val PREFS_KEY = "dvora_custom_api_types"
    private const val JSON_KEY  = "custom_api_types_json"

    var customTypes by mutableStateOf<List<CustomApiType>>(emptyList())
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
        val json  = prefs.getString(JSON_KEY, null) ?: run { customTypes = emptyList(); return }
        val type  = object : TypeToken<List<CustomApiType>>() {}.type
        customTypes = try { Gson().fromJson(json, type) ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    fun save(context: Context, types: List<CustomApiType>) {
        customTypes = types
        context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
            .edit().putString(JSON_KEY, Gson().toJson(types)).apply()
    }

    fun add(context: Context, type: CustomApiType) {
        save(context, (customTypes + type).distinctBy { it.id })
    }

    fun remove(context: Context, id: String) {
        save(context, customTypes.filter { it.id != id })
    }
}

@Composable
fun ApiSourcesEditor(apiSites: List<String>, onUpdate: (List<String>) -> Unit) {
    var showWizard    by remember { mutableStateOf(false) }
    var showTester    by remember { mutableStateOf(false) }
    var editingIndex  by remember { mutableIntStateOf(-1) }
    var editingEntry  by remember { mutableStateOf<ApiEntry?>(null) }

    val bgColor   = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)
    val textColor = beeAdapt(Color(0xFF4E3B00), BeeColors.DarkOnSurface)
    val cardBg    = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell)

    Column(modifier = Modifier.padding(16.dp).background(bgColor)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { editingIndex = -1; editingEntry = null; showWizard = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = BeeColors.DeepAmber),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("🧙 Add API Source", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { showTester = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = BeeColors.HoneyGold),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Search, null, tint = BeeColors.BeeBlack)
                Spacer(Modifier.width(6.dp))
                Text("🧪 Add API Endpoint", color = BeeColors.BeeBlack, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (apiSites.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🐝", fontSize = 36.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("No API sources yet", color = textColor.copy(alpha = 0.6f), fontSize = 14.sp)
                }
            }
        }

        LazyColumn {
            itemsIndexed(apiSites) { index, raw ->
                val entry = remember(raw) { parseApiEntry(raw) }
                val hasSplitUrl = !entry.landingUrl.isNullOrBlank() && entry.landingUrl != entry.apiUrl
                Card(
                    modifier  = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    shape     = RoundedCornerShape(10.dp),
                    colors    = CardDefaults.cardColors(containerColor = cardBg),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (entry.type == "stremio") Color(0xFF6C3FC4) else BeeColors.DeepAmber
                                ) {
                                    Text(
                                        entry.displayType(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                                if (hasSplitUrl) {
                                    Surface(shape = RoundedCornerShape(4.dp), color = BeeColors.HoneyGold.copy(alpha = 0.2f)) {
                                        Text(
                                            "SPLIT", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = BeeColors.DeepAmber,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (entry.type == "custom") {
                                    Surface(shape = RoundedCornerShape(4.dp), color = beeAdapt(BeeColors.FoundGreen, BeeColors.FoundGreenDark).copy(alpha = 0.25f)) {
                                        Text(
                                            "CUSTOM", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = beeAdapt(BeeColors.FoundGreen, BeeColors.FoundGreenDark),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            // Show only the domain name of the search API
                            val apiDomain    = extractDomain(entry.apiUrl)
                            val landDomain   = if (hasSplitUrl) extractDomain(entry.landingUrl!!) else apiDomain
                            val domainsDiffer = hasSplitUrl && landDomain != apiDomain
                            Text(
                                "🔍 $apiDomain",
                                fontSize = 12.sp,
                                color = textColor,
                                maxLines = 1,
                                fontWeight = FontWeight.Medium
                            )
                            if (domainsDiffer) {
                                Text(
                                    "🔗 $landDomain",
                                    fontSize = 12.sp,
                                    color = textColor.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        IconButton(onClick = { editingIndex = index; editingEntry = entry; showWizard = true }) {
                            Icon(Icons.Default.Edit, null, tint = BeeColors.HoneyGold)
                        }
                        IconButton(onClick = {
                            onUpdate(apiSites.toMutableList().also { it.removeAt(index) })
                        }) {
                            Icon(Icons.Default.Delete, null, tint = BeeColors.DeepAmber.copy(alpha = 0.7f))
                        }
                    }
                }
                HorizontalDivider(color = BeeColors.HoneyGold.copy(alpha = 0.15f))
            }
        }
    }

    if (showWizard) {
        ApiSourceWizardDialog(
            initial   = editingEntry,
            onDismiss = { showWizard = false; editingIndex = -1; editingEntry = null },
            onSave    = { newEntry ->
                val updated = apiSites.toMutableList()
                if (editingIndex >= 0) updated[editingIndex] = newEntry.toRawString()
                else updated.add(newEntry.toRawString())
                onUpdate(updated)
                showWizard = false; editingIndex = -1; editingEntry = null
            }
        )
    }

    if (showTester) {
        ApiEndpointTesterDialog(
            onDismiss = { showTester = false },
            onSaveCustom = { customType ->
                // Custom API types are persisted by the manager; they will appear
                // as options in the Add API Source wizard.
            }
        )
    }
}

@Composable
fun ApiSourceWizardDialog(
    initial:   ApiEntry?,
    onDismiss: () -> Unit,
    onSave:    (ApiEntry) -> Unit
) {
    val context = LocalContext.current
    var step               by remember { mutableIntStateOf(0) }
    var apiType            by remember { mutableStateOf(initial?.type ?: "v1") }
    var apiUrl             by remember { mutableStateOf(initial?.apiUrl ?: "") }
    var landingUrl         by remember { mutableStateOf(initial?.landingUrl ?: "") }
    var selectedCustomTypeId by remember { mutableStateOf<String?>(null) }

    val bgColor   = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)
    val textColor = beeAdapt(Color(0xFF4E3B00), BeeColors.DarkOnSurface)
    val cardBg    = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell)
    val isEditing = initial != null
    val totalSteps = 3

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = bgColor,
        title = null,
        text  = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // ── Header ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🧙 ", fontSize = 20.sp)
                    Text(
                        if (isEditing) "Edit API Source" else "New API Source",
                        fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                        color = beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold)
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${step + 1} / $totalSteps", fontSize = 11.sp, color = BeeColors.DeepAmber, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                // Progress bar
                androidx.compose.material3.LinearProgressIndicator(
                    progress            = { (step + 1f) / totalSteps },
                    modifier            = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color               = BeeColors.HoneyGold,
                    trackColor          = BeeColors.HoneyGold.copy(alpha = 0.2f)
                )
                Spacer(Modifier.height(18.dp))

                when (step) {
                    // ── Step 1: API type ──────────────────────────────────────
                    0 -> {
                        Text("What type of API does this site use?", fontWeight = FontWeight.SemiBold, color = textColor, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        // Built-in types
                        listOf(
                            "v1"      to ("V1 JSON API"    to "Sites with /searching?q= endpoint\ne.g. 123moviesfree, fmovies, yesmovies"),
                            "stremio" to ("Stremio Addon"  to "Stremio catalog addons\ne.g. Cinemeta")
                        ).forEach { (type, info) ->
                            val (label, desc) = info
                            val selected = apiType == type
                            Card(
                                modifier  = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { apiType = type; selectedCustomTypeId = null },
                                shape     = RoundedCornerShape(10.dp),
                                colors    = CardDefaults.cardColors(
                                    containerColor = if (selected) BeeColors.DeepAmber.copy(alpha = 0.15f) else cardBg
                                ),
                                border    = androidx.compose.foundation.BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) BeeColors.DeepAmber else BeeColors.HoneyGold.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                                    RadioButton(
                                        selected = selected, onClick = { apiType = type; selectedCustomTypeId = null },
                                        colors = RadioButtonDefaults.colors(selectedColor = BeeColors.DeepAmber)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(label, fontWeight = FontWeight.Bold, color = textColor, fontSize = 13.sp)
                                        Text(desc, fontSize = 11.sp, color = textColor.copy(alpha = 0.65f))
                                    }
                                }
                            }
                        }
                        // User-defined custom API types
                        if (CustomApiTypeManager.customTypes.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Your custom API types:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BeeColors.HoneyGold)
                            Spacer(Modifier.height(4.dp))
                        }
                        CustomApiTypeManager.customTypes.forEach { custom ->
                            val selected = selectedCustomTypeId == custom.id
                            Card(
                                modifier  = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                    apiType = "custom"; selectedCustomTypeId = custom.id
                                },
                                shape     = RoundedCornerShape(10.dp),
                                colors    = CardDefaults.cardColors(
                                    containerColor = if (selected) beeAdapt(BeeColors.FoundGreen, BeeColors.FoundGreenDark).copy(alpha = 0.15f) else cardBg
                                ),
                                border    = androidx.compose.foundation.BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) beeAdapt(BeeColors.FoundGreen, BeeColors.FoundGreenDark) else BeeColors.HoneyGold.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                                    RadioButton(
                                        selected = selected, onClick = { apiType = "custom"; selectedCustomTypeId = custom.id },
                                        colors = RadioButtonDefaults.colors(selectedColor = beeAdapt(BeeColors.FoundGreen, BeeColors.FoundGreenDark))
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(custom.name, fontWeight = FontWeight.Bold, color = textColor, fontSize = 13.sp)
                                        Text("Custom API • ${custom.matchKeys.size} match key(s) • ${extractDomain(custom.apiUrl)}",
                                            fontSize = 11.sp, color = textColor.copy(alpha = 0.65f), maxLines = 1)
                                    }
                                    IconButton(onClick = {
                                        context.getSharedPreferences("dvora_custom_api_types", Context.MODE_PRIVATE)
                                        CustomApiTypeManager.remove(context, custom.id)
                                    }) {
                                        Icon(Icons.Default.Delete, "Delete custom type", tint = BeeColors.DeepAmber.copy(alpha = 0.7f))
                                    }
                                }
                            }
                        }
                    }

                    // ── Step 2: Search API URL ────────────────────────────────
                    1 -> {
                        // Pre-fill from custom type if selected
                        LaunchedEffect(selectedCustomTypeId) {
                            if (apiType == "custom" && selectedCustomTypeId != null) {
                                val custom = CustomApiTypeManager.customTypes.find { it.id == selectedCustomTypeId }
                                if (custom != null) {
                                    if (apiUrl.isBlank()) apiUrl = custom.apiUrl
                                    if (landingUrl.isBlank()) landingUrl = custom.landingUrl
                                }
                            }
                        }
                        Text(
                            if (apiType == "stremio") "Stremio Addon Base URL"
                            else if (apiType == "custom") "Search API URL (from custom type)"
                            else "Search API URL",
                            fontWeight = FontWeight.Bold, color = textColor, fontSize = 14.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (apiType == "stremio")
                                "The base URL of the Stremio addon — no placeholder needed.\ne.g. https://v3-cinemeta.strem.io"
                            else if (apiType == "custom")
                                "Pre-filled from your custom API type. You can edit it if needed. DVORA marks where the title goes."
                            else
                                "Type the full search URL and put DVORA exactly where the movie/show title should go.",
                            fontSize = 11.sp, color = textColor.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value         = apiUrl,
                            onValueChange = { apiUrl = it },
                            label         = { Text(if (apiType == "stremio") "Addon Base URL" else "API URL  (use DVORA as placeholder)") },
                            placeholder   = {
                                Text(
                                    if (apiType == "stremio") "https://v3-cinemeta.strem.io"
                                    else "https://ww1.yesmovies.ag/searching?q=DVORA&limit=40",
                                    color = textColor.copy(alpha = 0.35f)
                                )
                            },
                            modifier      = Modifier.fillMaxWidth(),
                            singleLine    = true,
                            colors        = beeTextFieldColors()
                        )
                        Spacer(Modifier.height(10.dp))
                        // Live preview
                        val previewQuery = "the+matrix"
                        val previewApiUrl = if (apiUrl.isBlank())
                            if (apiType == "stremio") "https://addon.strem.io" else "https://your-site.com/searching?q=DVORA&limit=40"
                        else apiUrl
                        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = cardBg)) {
                            Column(Modifier.padding(10.dp)) {
                                Text("📡 Dvora will call:", fontSize = 11.sp, color = BeeColors.DeepAmber, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    if (apiType == "stremio")
                                        "$previewApiUrl/catalog/movie/top/search=$previewQuery.json"
                                    else
                                        previewApiUrl.replace("DVORA", previewQuery),
                                    fontSize = 10.sp, color = textColor.copy(alpha = 0.65f), fontFamily = FontFamily.Monospace
                                )
                                if (apiType == "v1" && apiUrl.isNotBlank() && !apiUrl.contains("DVORA")) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("⚠️ Add DVORA somewhere in the URL", fontSize = 10.sp, color = BeeColors.PollenOrange, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    // ── Step 3: Landing URL ───────────────────────────────────
                    2 -> {
                        Text("Landing URL", fontWeight = FontWeight.Bold, color = textColor, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The link Dvora opens when a match is found.\nPut DVORA where the title goes — this URL is completely independent from the search API above.\nThis field is required.",
                            fontSize = 11.sp, color = textColor.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value         = landingUrl,
                            onValueChange = { landingUrl = it },
                            label         = { Text("Landing URL  (use DVORA as placeholder, required)") },
                            placeholder   = { Text("https://yesmovies.ag/search/?q=DVORA", color = textColor.copy(alpha = 0.35f)) },
                            modifier      = Modifier.fillMaxWidth(),
                            singleLine    = true,
                            colors        = beeTextFieldColors()
                        )
                        Spacer(Modifier.height(10.dp))
                        val previewQuery2 = "the+matrix"
                        val effectiveLanding = landingUrl.ifBlank { "https://your-site.com/search/?q=DVORA" }
                        val isSplit = landingUrl.isNotBlank() && landingUrl.trimEnd('/') != apiUrl.trimEnd('/')
                        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = cardBg)) {
                            Column(Modifier.padding(10.dp)) {
                                Text("🔗 Match will open:", fontSize = 11.sp, color = BeeColors.HoneyGold, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    effectiveLanding.replace("DVORA", previewQuery2),
                                    fontSize = 10.sp, color = textColor.copy(alpha = 0.65f), fontFamily = FontFamily.Monospace
                                )
                                Spacer(Modifier.height(6.dp))
                                if (isSplit) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("✅", fontSize = 12.sp)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "Split mode — search API and landing site are different",
                                            fontSize = 10.sp, color = beeAdapt(BeeColors.FoundGreen, BeeColors.FoundGreenDark),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                if (landingUrl.isBlank()) {
                                    Text(
                                        "⚠️ Landing URL is required",
                                        fontSize = 10.sp, color = BeeColors.PollenOrange, fontWeight = FontWeight.SemiBold
                                    )
                                }
                                if (landingUrl.isNotBlank() && !landingUrl.contains("DVORA")) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("⚠️ Add DVORA somewhere in the URL", fontSize = 10.sp, color = BeeColors.PollenOrange, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) {
                    TextButton(onClick = { step-- }) {
                        Text("← Back", color = BeeColors.DeepAmber)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (step < totalSteps - 1) {
                    Button(
                        onClick  = { step++ },
                        enabled  = step == 0 || apiUrl.isNotBlank(),
                        colors   = ButtonDefaults.buttonColors(containerColor = BeeColors.DeepAmber),
                        shape    = RoundedCornerShape(8.dp)
                    ) {
                        Text("Next →", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick  = {
                            if (apiUrl.isNotBlank() && landingUrl.isNotBlank()) {
                                // If a custom API type is selected, carry its match keys and name
                                val custom = if (apiType == "custom") {
                                    CustomApiTypeManager.customTypes.find { it.id == selectedCustomTypeId }
                                } else null
                                onSave(ApiEntry(
                                    type       = apiType,
                                    apiUrl     = apiUrl.trimEnd('/'),
                                    landingUrl = landingUrl.trimEnd('/'),
                                    matchKeys  = custom?.matchKeys ?: emptyList(),
                                    customName = custom?.name
                                ))
                            }
                        },
                        enabled  = apiUrl.isNotBlank() && landingUrl.isNotBlank(),
                        colors   = ButtonDefaults.buttonColors(containerColor = BeeColors.HoneyGold),
                        shape    = RoundedCornerShape(8.dp)
                    ) {
                        Text("💾 Save", color = BeeColors.BeeBlack, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = textColor.copy(alpha = 0.6f))
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// API ENDPOINT TESTER
// ═══════════════════════════════════════════════════════════════════════════════

data class JsonKeyPath(
    val path: String,   // e.g. "data.title" or "data[0].name"
    val sampleValue: String,
    val selected: Boolean = false
)

fun flattenJson(element: com.google.gson.JsonElement, prefix: String = ""): List<JsonKeyPath> {
    val result = mutableListOf<JsonKeyPath>()
    when {
        element.isJsonObject -> {
            element.asJsonObject.entrySet().forEach { (key, value) ->
                val newPrefix = if (prefix.isEmpty()) key else "$prefix.$key"
                result.addAll(flattenJson(value, newPrefix))
            }
        }
        element.isJsonArray -> {
            val arr = element.asJsonArray
            if (arr.size() > 0) {
                // Show first item's keys with array index notation
                result.addAll(flattenJson(arr[0], "$prefix[0]"))
            } else {
                result.add(JsonKeyPath(prefix, "[] (empty array)"))
            }
        }
        else -> {
            val valueStr = element.toString().trim('"')
            result.add(JsonKeyPath(prefix, valueStr.take(80)))
        }
    }
    return result
}

@Composable
fun ApiEndpointTesterDialog(
    onDismiss: () -> Unit,
    onSaveCustom: (CustomApiType) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var endpointUrl by remember { mutableStateOf("") }
    var landingUrl  by remember { mutableStateOf("") }
    var customName  by remember { mutableStateOf("") }
    var isLoading   by remember { mutableStateOf(false) }
    var rawJson     by remember { mutableStateOf("") }
    var errorMsg    by remember { mutableStateOf<String?>(null) }
    var keyPaths    by remember { mutableStateOf<List<JsonKeyPath>>(emptyList()) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saveMsg     by remember { mutableStateOf<String?>(null) }

    val bgColor   = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)
    val textColor = beeAdapt(Color(0xFF4E3B00), BeeColors.DarkOnSurface)
    val cardBg    = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = bgColor,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧪 ", fontSize = 20.sp)
                Text("Create Custom API Type", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                    color = beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold))
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Create your own custom API type (like v1). Paste an API endpoint URL (use DVORA as the search placeholder). Dvora will fetch it with a real movie title, show the JSON, and let you pick which key-value pairs to match on. Then name your API type, add a landing URL, and save it. It will appear as an option when adding API sources.",
                    fontSize = 11.sp, color = textColor.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value         = endpointUrl,
                    onValueChange = { endpointUrl = it },
                    label         = { Text("API Endpoint URL  (use DVORA as placeholder)") },
                    placeholder   = { Text("https://example.com/search?q=DVORA", color = textColor.copy(alpha = 0.35f)) },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    colors        = beeTextFieldColors()
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (endpointUrl.isBlank()) {
                            errorMsg = "Please enter a URL"
                            return@Button
                        }
                        // Replace DVORA with a real movie title so the API returns real results
                        val testUrl = endpointUrl.replace("DVORA", "interstellar")
                        isLoading = true
                        errorMsg = null
                        rawJson = ""
                        keyPaths = emptyList()
                        selectedKeys = emptySet()
                        saveMsg = null
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val client = okhttp3.OkHttpClient.Builder()
                                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                        .build()
                                    val request = okhttp3.Request.Builder()
                                        .url(testUrl)
                                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) Dvora/2.0")
                                        .header("Accept", "application/json")
                                        .build()
                                    client.newCall(request).execute().use { response ->
                                        if (!response.isSuccessful) {
                                            return@withContext "HTTP_ERROR:${response.code}"
                                        }
                                        val body = response.body?.string() ?: return@withContext "EMPTY_BODY"
                                        body
                                    }
                                }
                                if (result.startsWith("HTTP_ERROR:")) {
                                    errorMsg = result
                                } else if (result == "EMPTY_BODY") {
                                    errorMsg = "Empty response body"
                                } else {
                                    rawJson = result
                                    try {
                                        val parsed = com.google.gson.JsonParser.parseString(result)
                                        keyPaths = flattenJson(parsed)
                                    } catch (_: Exception) {
                                        errorMsg = "Response is not valid JSON"
                                    }
                                }
                            } catch (e: Exception) {
                                errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = !isLoading && endpointUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BeeColors.DeepAmber),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isLoading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Fetching...", color = Color.White, fontWeight = FontWeight.Bold)
                    } else {
                        Text("🚀 Fetch JSON", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                errorMsg?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text("⚠️ $err", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }

                if (rawJson.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("📄 Raw JSON Response:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BeeColors.DeepAmber)
                    Spacer(Modifier.height(4.dp))
                    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = cardBg)) {
                        Text(
                            rawJson.take(2000) + if (rawJson.length > 2000) "\n... (truncated)" else "",
                            modifier = Modifier.padding(8.dp),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            color = textColor.copy(alpha = 0.75f),
                            maxLines = 12
                        )
                    }
                }

                if (keyPaths.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("🔑 Pick key-value pairs to match on:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BeeColors.DeepAmber)
                    Spacer(Modifier.height(4.dp))
                    Text("Tap to select/deselect. Selected keys will be used when scanning for matches.",
                        fontSize = 10.sp, color = textColor.copy(alpha = 0.55f))
                    Spacer(Modifier.height(6.dp))
                    keyPaths.forEach { kp ->
                        val isSelected = kp.path in selectedKeys
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .clickable {
                                    selectedKeys = if (isSelected) selectedKeys - kp.path else selectedKeys + kp.path
                                },
                            shape = RoundedCornerShape(6.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) BeeColors.HoneyGold.copy(alpha = 0.25f) else cardBg
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                if (isSelected) 2.dp else 1.dp,
                                if (isSelected) BeeColors.DeepAmber else BeeColors.HoneyGold.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        selectedKeys = if (it) selectedKeys + kp.path else selectedKeys - kp.path
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = BeeColors.DeepAmber)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(kp.path, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textColor, fontFamily = FontFamily.Monospace)
                                    Text("e.g. ${kp.sampleValue}", fontSize = 10.sp, color = textColor.copy(alpha = 0.6f), maxLines = 1)
                                }
                            }
                        }
                    }

                    if (selectedKeys.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = BeeColors.HoneyGold.copy(alpha = 0.15f))) {
                            Column(Modifier.padding(10.dp)) {
                                Text("✅ Selected keys for matching:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BeeColors.DeepAmber)
                                Spacer(Modifier.height(4.dp))
                                selectedKeys.forEach { key ->
                                    Text("• $key", fontSize = 11.sp, color = textColor, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }

                    // ── Name + Landing URL + Save section ──
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = BeeColors.HoneyGold.copy(alpha = 0.3f))
                    Spacer(Modifier.height(10.dp))
                    Text("🏷️ Name your API type", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BeeColors.HoneyGold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Give this API type a name (e.g. 'My V2 API'). It will appear as an option when adding API sources.",
                        fontSize = 11.sp, color = textColor.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value         = customName,
                        onValueChange = { customName = it },
                        label         = { Text("API Type Name") },
                        placeholder   = { Text("e.g. My V2 API", color = textColor.copy(alpha = 0.35f)) },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        colors        = beeTextFieldColors()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("🔗 Landing URL (required)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BeeColors.HoneyGold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "The link Dvora opens when a match is found. Put DVORA where the title goes.",
                        fontSize = 11.sp, color = textColor.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value         = landingUrl,
                        onValueChange = { landingUrl = it },
                        label         = { Text("Landing URL  (use DVORA as placeholder)") },
                        placeholder   = { Text("https://example.com/watch?q=DVORA", color = textColor.copy(alpha = 0.35f)) },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        colors        = beeTextFieldColors()
                    )
                    Spacer(Modifier.height(10.dp))

                    val canSave = endpointUrl.isNotBlank() && landingUrl.isNotBlank() && selectedKeys.isNotEmpty() && customName.isNotBlank()
                    Button(
                        onClick = {
                            val customType = CustomApiType(
                                id         = "custom_${System.currentTimeMillis()}",
                                name       = customName.trim(),
                                apiUrl     = endpointUrl.trimEnd('/'),
                                landingUrl = landingUrl.trimEnd('/'),
                                matchKeys  = selectedKeys.toList()
                            )
                            CustomApiTypeManager.add(context, customType)
                            onSaveCustom(customType)
                            saveMsg = "✅ Saved! '${customType.name}' is now available as an API type."
                            Toast.makeText(context, "Custom API type '${customType.name}' created", Toast.LENGTH_SHORT).show()
                        },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = BeeColors.HoneyGold),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("💾 Save Custom API Type", color = BeeColors.BeeBlack, fontWeight = FontWeight.Bold)
                    }
                    if (!canSave) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Fill in the name, endpoint, landing URL, and pick at least one match key to save.",
                            fontSize = 10.sp, color = textColor.copy(alpha = 0.5f)
                        )
                    }
                    saveMsg?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = 11.sp, color = beeAdapt(BeeColors.FoundGreen, BeeColors.FoundGreenDark), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = BeeColors.DeepAmber, fontWeight = FontWeight.Bold)
            }
        }
    )
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
                "+https://ww25.soap2day.day/?s=", "+https://dorawatch.one/?s=",
                "+https://www.lookmovie2.to/shows/search/?q=", "+https://hydrahd.ru/index.php?menu=search&query=",
                "+https://1movies.bz/browser?keyword=", "+https://yflix.to/browser?keyword=",
                "+https://hianime.city/?s=", "+https://gogoanime.by/?s=",
                "+https://hianime.dk/filter?keyword="
            )
            "movies" -> listOf(
                "+https://ww25.soap2day.day/?s=", "+https://hydrahd.ru/index.php?menu=search&query=",
                "+https://dorawatch.one/?s=", "+https://1movies.bz/browser?keyword=", "+https://yflix.to/browser?keyword=",
                "+https://www.lookmovie2.to/movies/search/?q=", "+https://1movies.bz/browser?keyword=", "+https://yflix.to/browser?keyword="
            )
            "manual_checks" -> listOf(
                "https://67movies.net/", "https://popcornmovies.org/"

            )
            "api_sites" -> listOf(
                "v1:https://ww8.123moviesfree.net/searching?q=DVORA&limit=40&offset=0|https://ww8.123moviesfree.net/search/?q=DVORA",
                "v1:https://ww4.fmovies.co/searching?q=DVORA&limit=40&offset=0|https://ww4.fmovies.co/search/?q=DVORA",
                "v1:https://ww1.yesmovies.ag/searching?q=DVORA&limit=40&offset=0|https://ww1.yesmovies.ag/search.html?q=DVORA",
                "stremio:https://v3-cinemeta.strem.io"
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