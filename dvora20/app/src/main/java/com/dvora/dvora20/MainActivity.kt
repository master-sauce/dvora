package com.dvora.dvora20

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════════
// BEE THEME — COLORS & SCHEMES
// ═══════════════════════════════════════════════════════════════════════════════

/** Immutable palette used by both light and dark schemes. */
object BeeColors {
    // Shared warm tones
    val HoneyGold       = Color(0xFFFFC107)
    val DeepAmber       = Color(0xFFFF8F00)
    val PollenOrange    = Color(0xFFFF6D00)
    val FoundGreen      = Color(0xFF558B2F)
    val FoundGreenDark  = Color(0xFF8BC34A)
    val NotFoundRed     = Color(0xFFB71C1C)
    val NotFoundAmber   = Color(0xFFFF8F00)

    // Light-mode surfaces
    val WaxWhite        = Color(0xFFFFFDE7)
    val HoneycombYellow = Color(0xFFFFECB3)
    val BeeBlack        = Color(0xFF1A1200)

    // Dark-mode surfaces
    val DarkComb        = Color(0xFF1C1500)  // deepest dark bg
    val DarkCell        = Color(0xFF2A1F00)  // card/surface dark
    val DarkStripe      = Color(0xFF3A2B00)  // elevated surface
    val DarkOnSurface   = Color(0xFFFFE082)  // readable warm text on dark
}

/** Light bee color scheme — cream and amber */
private val BeeLightScheme = lightColorScheme(
    primary              = BeeColors.HoneyGold,
    onPrimary            = BeeColors.BeeBlack,
    primaryContainer     = BeeColors.HoneycombYellow,
    onPrimaryContainer   = BeeColors.BeeBlack,
    secondary            = BeeColors.DeepAmber,
    onSecondary          = BeeColors.WaxWhite,
    secondaryContainer   = Color(0xFFFFD54F),
    onSecondaryContainer = BeeColors.BeeBlack,
    tertiary             = BeeColors.PollenOrange,
    onTertiary           = BeeColors.WaxWhite,
    background           = BeeColors.WaxWhite,
    onBackground         = BeeColors.BeeBlack,
    surface              = BeeColors.WaxWhite,
    onSurface            = BeeColors.BeeBlack,
    surfaceVariant       = BeeColors.HoneycombYellow,
    onSurfaceVariant     = Color(0xFF4E3B00),
    outline              = BeeColors.DeepAmber,
    error                = BeeColors.NotFoundRed,
    onError              = Color.White,
)

/** Dark bee color scheme — deep amber on near-black */
private val BeeDarkScheme = darkColorScheme(
    primary              = BeeColors.HoneyGold,
    onPrimary            = BeeColors.BeeBlack,
    primaryContainer     = Color(0xFF4A3500),
    onPrimaryContainer   = BeeColors.HoneyGold,
    secondary            = BeeColors.DeepAmber,
    onSecondary          = BeeColors.BeeBlack,
    secondaryContainer   = Color(0xFF3E2800),
    onSecondaryContainer = BeeColors.HoneyGold,
    tertiary             = BeeColors.PollenOrange,
    onTertiary           = BeeColors.BeeBlack,
    background           = BeeColors.DarkComb,
    onBackground         = BeeColors.DarkOnSurface,
    surface              = BeeColors.DarkCell,
    onSurface            = BeeColors.DarkOnSurface,
    surfaceVariant       = BeeColors.DarkStripe,
    onSurfaceVariant     = Color(0xFFFFCC80),
    outline              = BeeColors.DeepAmber,
    error                = Color(0xFFFF6B6B),
    onError              = BeeColors.BeeBlack,
)

// ── CompositionLocal for dark-mode toggle ────────────────────────────────────
val LocalDarkMode = compositionLocalOf { mutableStateOf(false) }

/** Returns a color that adapts to the current bee theme mode. */
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
        setContent {
            val prefs = getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)
            val darkModeState = remember { mutableStateOf(prefs.getBoolean("dark_mode", false)) }

            CompositionLocalProvider(
                LocalDarkMode provides darkModeState,
                LocalLayoutDirection provides LayoutDirection.Ltr
            ) {
                MaterialTheme(colorScheme = if (darkModeState.value) BeeDarkScheme else BeeLightScheme) {
                    DvoraApp(
                        onToggleDarkMode = {
                            darkModeState.value = !darkModeState.value
                            prefs.edit().putBoolean("dark_mode", darkModeState.value).apply()
                        }
                    )
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
//    Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
}

fun openUrl(context: Context, url: String) {
    try {
        val cleanUrl = if (url.startsWith("http")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl))
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open URL", Toast.LENGTH_SHORT).show()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN APP
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DvoraApp(onToggleDarkMode: () -> Unit) {
    val context    = LocalContext.current
    val scope      = rememberCoroutineScope()
    val scanner    = remember { DvoraScanner() }
    val isDark     = LocalDarkMode.current.value

    var searchTerm  by remember { mutableStateOf("") }
    var searchType  by remember { mutableStateOf(SourceType.SHOW) }
    var results     by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var manualLinks by remember { mutableStateOf<List<String>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    var shows        by remember { mutableStateOf(loadSources(context, "shows")) }
    var movies       by remember { mutableStateOf(loadSources(context, "movies")) }
    var manualChecks by remember { mutableStateOf(loadSources(context, "manual_checks")) }
    var apiSites     by remember { mutableStateOf(loadSources(context, "api_sites")) }

    var showSettings  by remember { mutableStateOf(false) }
    var showSubtitles by remember { mutableStateOf(false) }
    var showImdb      by remember { mutableStateOf(false) }

    val headerBg   = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkComb)
    val scaffoldBg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🐝", fontSize = 22.sp, modifier = Modifier.padding(end = 8.dp))
                        Text(
                            "DVORA",
                            fontWeight    = FontWeight.ExtraBold,
                            letterSpacing = 4.sp,
                            color         = BeeColors.HoneyGold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor         = headerBg,
                    titleContentColor      = BeeColors.HoneyGold,
                    actionIconContentColor = BeeColors.HoneyGold
                ),
                actions = {
                    // Dark / Light toggle button
                    IconButton(onClick = onToggleDarkMode) {
                        Icon(
                            imageVector        = if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (isDark) "Switch to Light" else "Switch to Dark",
                            tint               = BeeColors.HoneyGold
                        )
                    }
                    IconButton(onClick = { showSubtitles = true; showSettings = false; showImdb = false }) {
                        Icon(Icons.Default.Subtitles, "Subtitles", tint = BeeColors.HoneyGold)
                    }
                    IconButton(onClick = { showImdb = true; showSubtitles = false; showSettings = false }) {
                        // IMDb logo badge — gold "IMDb" text in a rounded rectangle
                        Box(
                            modifier         = Modifier
                                .size(40.dp)
                                .padding(6.dp)
                                .background(Color(0xFFF5C518), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text       = "IMDb",
                                fontSize   = 7.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color      = Color.Black,
                                letterSpacing = 0.sp
                            )
                        }
                    }
                    IconButton(onClick = { showSettings = true; showSubtitles = false; showImdb = false }) {
                        Icon(Icons.Default.Settings, "Settings", tint = BeeColors.HoneyGold)
                    }
                }
            )
        },
        containerColor = scaffoldBg,
        modifier       = Modifier.fillMaxSize()
    ) { innerPadding ->
        when {
            showSettings -> SettingsScreen(
                shows        = shows,
                movies       = movies,
                manualChecks = manualChecks,
                apiSites     = apiSites,
                onUpdate     = { type, newList ->
                    when (type) {
                        SourceType.SHOW   -> { shows = newList;        saveSources(context, "shows", newList) }
                        SourceType.MOVIE  -> { movies = newList;       saveSources(context, "movies", newList) }
                        SourceType.MANUAL -> { manualChecks = newList; saveSources(context, "manual_checks", newList) }
                        SourceType.API    -> { apiSites = newList;     saveSources(context, "api_sites", newList) }
                    }
                },
                onBack       = { showSettings = false },
                onToggleDark = onToggleDarkMode,
                modifier     = Modifier.padding(innerPadding)
            )
            showSubtitles -> SubtitlesScreen(
                scanner      = scanner,
                onBack       = { showSubtitles = false },
                onToggleDark = onToggleDarkMode,
                modifier     = Modifier.padding(innerPadding)
            )
            showImdb -> ImdbScreen(
                scanner      = scanner,
                onBack       = { showImdb = false },
                onToggleDark = onToggleDarkMode,
                modifier     = Modifier.padding(innerPadding)
            )
            else -> {
                val cardBg = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell)

                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .padding(16.dp)
                        .fillMaxSize()
                ) {
                    // ── Search card ────────────────────────────────────────
                    Card(
                        shape     = RoundedCornerShape(16.dp),
                        colors    = CardDefaults.cardColors(containerColor = cardBg),
                        elevation = CardDefaults.cardElevation(4.dp),
                        modifier  = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value         = searchTerm,
                                onValueChange = { searchTerm = it },
                                label         = { Text("🍯 Search Movie or Show") },
                                modifier      = Modifier.fillMaxWidth(),
                                singleLine    = true,
                                colors        = beeTextFieldColors()
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier          = Modifier.fillMaxWidth()
                            ) {
                                BeeRadioOption(
                                    label    = "📺 Shows",
                                    selected = searchType == SourceType.SHOW,
                                    onClick  = { searchType = SourceType.SHOW },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                BeeRadioOption(
                                    label    = "🎬 Movies",
                                    selected = searchType == SourceType.MOVIE,
                                    onClick  = { searchType = SourceType.MOVIE },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Search button ──────────────────────────────────────
                    Button(
                        onClick = {
                            if (searchTerm.isBlank()) return@Button
                            isSearching = true
                            results     = emptyList()
                            scope.launch {
                                val activeSources = if (searchType == SourceType.SHOW) shows else movies

                                // Each site is awaited individually so the UI updates
                                // the moment a result arrives instead of waiting for all.
                                activeSources.forEach { source ->
                                    val result = scanner.scanSite(source, searchTerm)
                                    results = results + result
                                }
                                apiSites.forEach { site ->
                                    val newResults = when {
                                        site.startsWith("stremio:") -> scanner.scanStremio(site.removePrefix("stremio:"), searchTerm, searchType)
                                        site.startsWith("v1:")      -> scanner.scanV1(site.removePrefix("v1:"), searchTerm)
                                        else                        -> scanner.scanV1(site, searchTerm)
                                    }
                                    results = results + newResults
                                }
                                SearchLogs.lastLogs = results
                                manualLinks         = manualChecks.map { scanner.getManualCheck(it, searchTerm) }
                                isSearching         = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled  = !isSearching,
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor        = headerBg,
                            contentColor          = BeeColors.HoneyGold,
                            disabledContainerColor = if (isDark) Color(0xFF2A2000) else Color(0xFF4A3B00),
                            disabledContentColor   = BeeColors.HoneyGold.copy(alpha = 0.4f)
                        )
                    ) {
                        if (isSearching)
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = BeeColors.HoneyGold, strokeWidth = 2.dp)
                        else
                            Text("🐝  BUZZ & SEARCH", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }

                    Spacer(Modifier.height(16.dp))

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (results.isNotEmpty()) {
                            item { BeesSectionHeader("🍯 Results") }
                            items(results) { ResultItem(it, showDetails = true) }
                        }
                        if (manualLinks.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(16.dp))
                                BeesSectionHeader("🔍 Manual Checks")
                            }
                            items(manualLinks) { link ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { openUrl(context, link) },
                                    colors = CardDefaults.cardColors(containerColor = cardBg),
                                    shape  = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier          = Modifier.padding(10.dp)
                                    ) {
                                        Text("↗", fontSize = 16.sp, color = BeeColors.DeepAmber)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            link,
                                            fontSize = 12.sp,
                                            color    = beeAdapt(Color(0xFF4E3B00), BeeColors.DarkOnSurface)
                                        )
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
    focusedBorderColor   = BeeColors.DeepAmber,
    unfocusedBorderColor = BeeColors.HoneyGold,
    focusedLabelColor    = BeeColors.DeepAmber,
    cursorColor          = BeeColors.DeepAmber,
    focusedTextColor     = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface),
    unfocusedTextColor   = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface),
)

@Composable
fun BeeRadioOption(
    label:    String,
    selected: Boolean,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg        = if (selected) beeAdapt(BeeColors.BeeBlack, BeeColors.DarkStripe) else Color.Transparent
    val textColor = if (selected) BeeColors.HoneyGold else beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold)

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape    = RoundedCornerShape(8.dp),
        color    = bg,
        border   = androidx.compose.foundation.BorderStroke(
            width = 1.5.dp,
            color = if (selected) beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold) else BeeColors.DeepAmber
        )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(label, color = textColor, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
        }
    }
}

@Composable
fun BeesSectionHeader(title: String) {
    val lineColor  = BeeColors.HoneyGold
    val labelColor = beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Box(modifier = Modifier.weight(1f).height(2.dp).background(lineColor))
        Text(
            text          = title,
            fontWeight    = FontWeight.ExtraBold,
            fontSize      = 14.sp,
            color         = labelColor,
            letterSpacing = 1.5.sp,
            modifier      = Modifier.padding(horizontal = 10.dp)
        )
        Box(modifier = Modifier.weight(1f).height(2.dp).background(lineColor))
    }
}

@Composable
fun ResultItem(result: SearchResult, showDetails: Boolean = false) {
    val context     = LocalContext.current
    val foundBg     = beeAdapt(Color(0xFFF1F8E9), Color(0xFF1B2A10))
    val notFoundBg  = beeAdapt(Color(0xFFFFF8E1), BeeColors.DarkCell)
    val urlColor    = beeAdapt(Color(0xFF795548), Color(0xFFBCAAA4))
    val detailColor = beeAdapt(Color(0xFF4E342E), BeeColors.DarkOnSurface)

    Card(
        modifier  = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openUrl(context, result.url) },
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = if (result.found) foundBg else notFoundBg),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(72.dp)
                    .background(
                        color = if (result.found) BeeColors.FoundGreen else BeeColors.DeepAmber,
                        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                    )
            )
            Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (result.found) "✅" else "🟡", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (result.found) "Found!" else "Not Found",
                        fontWeight = FontWeight.Bold,
                        color      = if (result.found)
                            beeAdapt(BeeColors.FoundGreen, BeeColors.FoundGreenDark)
                        else
                            beeAdapt(Color(0xFF8D5A00), BeeColors.NotFoundAmber)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(result.url, fontSize = 11.sp, color = urlColor)
                if (showDetails && result.foundDetails != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(result.foundDetails, fontSize = 12.sp, color = detailColor, fontWeight = FontWeight.Medium)
                }
                if (result.errorMessage != null)
                    Text("⚠️ ${result.errorMessage}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            if (result.found) {
                IconButton(onClick = { copyToClipboard(context, result.url) }) {
                    Icon(Icons.Default.ContentCopy, "Copy link", tint = BeeColors.DeepAmber)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SUBTITLES SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun SubtitlesScreen(
    scanner:      DvoraScanner,
    onBack:       () -> Unit,
    onToggleDark: () -> Unit,
    modifier:     Modifier = Modifier
) {
    val context     = LocalContext.current
    val isDark      = LocalDarkMode.current.value
    val headerBg    = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkComb)
    val scaffoldBg  = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)

    var searchTerm  by remember { mutableStateOf("") }
    var searchType  by remember { mutableStateOf(SourceType.SHOW) }
    var results     by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    // Live search: re-runs whenever searchTerm or searchType changes.
    // 400ms debounce on text changes; type toggle fires immediately.
    LaunchedEffect(searchTerm, searchType) {
        if (searchTerm.isBlank()) { results = emptyList(); SearchLogs.lastLogs = emptyList(); return@LaunchedEffect }
        delay(400)
        isSearching = true
        val found           = scanner.scanSubtitles(searchTerm, searchType)
        results             = found
        SearchLogs.lastLogs = found
        isSearching         = false
    }

    Column(modifier = modifier.fillMaxSize().background(scaffoldBg).padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg, shape = RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BeeColors.HoneyGold)
            }
            Text(
                "🎞️  Hebrew Subtitles",
                style      = MaterialTheme.typography.titleLarge,
                color      = BeeColors.HoneyGold,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.weight(1f)
            )
            IconButton(onClick = onToggleDark) {
                Icon(
                    if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                    "Toggle theme",
                    tint = BeeColors.HoneyGold
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = searchTerm, onValueChange = { searchTerm = it },
            label = { Text("Movie or Show Name") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            colors = beeTextFieldColors()
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        ) {
            BeeRadioOption("📺 Shows",  searchType == SourceType.SHOW,  { searchType = SourceType.SHOW },  Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            BeeRadioOption("🎬 Movies", searchType == SourceType.MOVIE, { searchType = SourceType.MOVIE }, Modifier.weight(1f))
        }

        // Progress bar replaces the button
        if (isSearching) {
            LinearProgressIndicator(
                modifier   = Modifier.fillMaxWidth(),
                color      = BeeColors.DeepAmber,
                trackColor = BeeColors.DeepAmber.copy(alpha = 0.2f)
            )
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(4.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(results) { ResultItem(it, showDetails = true) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// IMDB SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun ImdbScreen(
    scanner:      DvoraScanner,
    onBack:       () -> Unit,
    onToggleDark: () -> Unit,
    modifier:     Modifier = Modifier
) {
    val context    = LocalContext.current
    val scope      = rememberCoroutineScope()
    val isDark     = LocalDarkMode.current.value
    val headerBg   = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkComb)
    val scaffoldBg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)

    var searchTerm  by remember { mutableStateOf("") }
    var results     by remember { mutableStateOf<List<ImdbResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMsg    by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    // Live search: debounce 350ms so we don't hammer the API on every keystroke
    LaunchedEffect(searchTerm) {
        if (searchTerm.isBlank()) { results = emptyList(); errorMsg = null; return@LaunchedEffect }
        delay(350)
        isSearching = true
        errorMsg    = null
        val found   = scanner.searchImdb(searchTerm)
        results     = found
        errorMsg    = if (found.isEmpty()) "No results found for \"$searchTerm\"" else null
        isSearching = false
    }

    Column(modifier = modifier.fillMaxSize().background(scaffoldBg)) {

        // ── Header ────────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BeeColors.HoneyGold)
            }
            // IMDb logo
            Box(
                modifier         = Modifier
                    .padding(horizontal = 4.dp)
                    .background(Color(0xFFF5C518), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = "IMDb",
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color      = Color.Black,
                    letterSpacing = 0.sp
                )
            }
            Text(
                "  Search",
                style      = MaterialTheme.typography.titleLarge,
                color      = BeeColors.HoneyGold,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.weight(1f)
            )
            IconButton(onClick = onToggleDark) {
                Icon(
                    if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                    "Toggle theme",
                    tint = BeeColors.HoneyGold
                )
            }
        }

        // ── Search field + button ─────────────────────────────────────────────
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value         = searchTerm,
                onValueChange = { searchTerm = it },
                label         = { Text("Movie or Show Name") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
                colors        = beeTextFieldColors()
            )
            // Loading indicator shown inline while live search is running
            if (isSearching) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color    = Color(0xFFF5C518),
                    trackColor = Color(0xFFF5C518).copy(alpha = 0.2f)
                )
            }
        }

        // ── Results ───────────────────────────────────────────────────────────
        if (errorMsg != null) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(errorMsg!!, color = beeAdapt(Color(0xFF8D5A00), BeeColors.HoneyGold.copy(alpha = 0.7f)))
            }
        }

        LazyColumn(
            modifier      = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
        ) {
            items(results) { ImdbResultCard(it) }
        }
    }
}

@Composable
fun ImdbResultCard(item: ImdbResult) {
    val context   = LocalContext.current
    val cardBg    = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell)
    val textColor = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val subColor  = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.7f))

    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable { openUrl(context, item.imdbUrl) },
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {

            // Poster
            if (item.posterUrl != null) {
                Box(
                    modifier = Modifier
                        .width(70.dp)
                        .height(100.dp)
                        .background(
                            beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe),
                            RoundedCornerShape(8.dp)
                        )
                ) {
                    AsyncImage(
                        model             = ImageRequest.Builder(context)
                            .data(item.posterUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.title,
                        contentScale      = ContentScale.Crop,
                        modifier          = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
            } else {
                Box(
                    modifier         = Modifier
                        .width(70.dp)
                        .height(100.dp)
                        .background(
                            beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) { Text("🎬", fontSize = 26.sp) }
            }

            Spacer(Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = textColor, maxLines = 2, modifier = Modifier.weight(1f))
                    IconButton(onClick = { copyToClipboard(context, item.title) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, "Copy title", tint = BeeColors.DeepAmber, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.year != null)
                        Text(item.year, fontSize = 12.sp, color = BeeColors.HoneyGold, fontWeight = FontWeight.SemiBold)
                    if (item.mediaType != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFF5C518)
                        ) {
                            Text(
                                item.mediaType,
                                fontSize = 10.sp,
                                color    = Color.Black,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // IMDb ID row with copy button
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier         = Modifier
                            .background(Color(0xFFF5C518), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text("IMDb", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(item.imdbId, fontSize = 11.sp, color = subColor, modifier = Modifier.weight(1f))
                    // Copy IMDb ID
                    IconButton(
                        onClick  = { copyToClipboard(context, item.imdbId) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Tag, "Copy IMDb ID", tint = Color(0xFFF5C518), modifier = Modifier.size(15.dp))
                    }
                }
            }
            // Copy full IMDb URL button
            IconButton(onClick = { copyToClipboard(context, item.imdbUrl) }) {
                Icon(Icons.Default.ContentCopy, "Copy link", tint = BeeColors.DeepAmber)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SETTINGS SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun SettingsScreen(
    shows:        List<String>,
    movies:       List<String>,
    manualChecks: List<String>,
    apiSites:     List<String>,
    onUpdate:     (SourceType, List<String>) -> Unit,
    onBack:       () -> Unit,
    onToggleDark: () -> Unit,
    modifier:     Modifier = Modifier
) {
    BackHandler { onBack() }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Shows", "Movies", "APIs", "Manual", "Logs")
    val isDark   = LocalDarkMode.current.value
    val headerBg = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkComb)
    val bgColor  = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)

    Column(modifier = modifier.fillMaxSize().background(bgColor)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(headerBg).padding(4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BeeColors.HoneyGold)
            }
            Text(
                "⚙️  Settings",
                style      = MaterialTheme.typography.titleLarge,
                color      = BeeColors.HoneyGold,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.weight(1f)
            )
            IconButton(onClick = onToggleDark) {
                Icon(
                    if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                    "Toggle theme",
                    tint = BeeColors.HoneyGold
                )
            }
        }

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding      = 16.dp,
            containerColor   = headerBg,
            contentColor     = BeeColors.HoneyGold,
            indicator        = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color    = BeeColors.HoneyGold,
                    height   = 3.dp
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick  = { selectedTab = index },
                    text     = {
                        Text(
                            title,
                            color      = if (selectedTab == index) BeeColors.HoneyGold else BeeColors.HoneyGold.copy(alpha = 0.45f),
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> SourceEditor(shows)        { onUpdate(SourceType.SHOW, it) }
            1 -> SourceEditor(movies)       { onUpdate(SourceType.MOVIE, it) }
            2 -> SourceEditor(apiSites)     { onUpdate(SourceType.API, it) }
            3 -> SourceEditor(manualChecks) { onUpdate(SourceType.MANUAL, it) }
            4 -> VerboseLogsScreen()
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
                val lines = context.contentResolver.openInputStream(it)
                    ?.bufferedReader()?.use { r -> r.readLines() } ?: emptyList()
                val clean = lines.map { l -> l.trim() }.filter { l -> l.isNotBlank() }
                if (clean.isNotEmpty()) onUpdate((list + clean).distinct())
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp).background(bgColor)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value         = newItem,
                onValueChange = { newItem = it },
                label         = { Text(if (editingIndex == -1) "Add Item" else "Edit Item") },
                modifier      = Modifier.weight(1f),
                colors        = beeTextFieldColors()
            )
            IconButton(onClick = {
                if (newItem.isNotBlank()) {
                    if (editingIndex == -1) onUpdate((list + newItem.trim()).distinct())
                    else {
                        val mutable = list.toMutableList()
                        mutable[editingIndex] = newItem.trim()
                        onUpdate(mutable.toList())
                        editingIndex = -1
                    }
                    newItem = ""
                }
            }) { Icon(if (editingIndex == -1) Icons.Default.Add else Icons.Default.Check, null, tint = BeeColors.DeepAmber) }

            if (editingIndex == -1) {
                IconButton(onClick = { filePickerLauncher.launch("text/plain") }) {
                    Icon(Icons.Default.FileUpload, null, tint = BeeColors.DeepAmber)
                }
            } else {
                IconButton(onClick = { editingIndex = -1; newItem = "" }) {
                    Icon(Icons.Default.Close, null, tint = BeeColors.DeepAmber)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn {
            itemsIndexed(list) { index, item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(
                            if (index % 2 == 0) rowAlt else Color.Transparent,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("🔗 ", fontSize = 12.sp)
                    Text(
                        item,
                        modifier = Modifier.weight(1f).clickable { newItem = item; editingIndex = index },
                        fontSize = 13.sp,
                        color    = textColor
                    )
                    IconButton(onClick = { onUpdate(list - item) }) {
                        Icon(Icons.Default.Delete, null, tint = BeeColors.DeepAmber.copy(alpha = 0.7f))
                    }
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
                Text(
                    "No logs from last search.",
                    color = beeAdapt(Color(0xFF8D5A00), BeeColors.HoneyGold.copy(alpha = 0.7f))
                )
            }
        }
    } else {
        LazyColumn(Modifier.padding(16.dp).background(bgColor)) {
            items(logs) { LogItem(it) }
        }
    }
}

@Composable
fun LogItem(log: SearchResult) {
    var expanded     by remember { mutableStateOf(false) }
    val cardColor    = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkCell)
    val titleColor   = beeAdapt(Color(0xFF3E2800), BeeColors.DarkOnSurface)
    val labelColor   = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.75f))
    val monoColor    = beeAdapt(Color(0xFF5D4037), BeeColors.DarkOnSurface.copy(alpha = 0.7f))
    val verboseColor = beeAdapt(Color(0xFF4E342E), BeeColors.DarkOnSurface.copy(alpha = 0.6f))

    Card(
        modifier  = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { expanded = !expanded },
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(2.dp)
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
                    color      = if (log.found) beeAdapt(BeeColors.FoundGreen, BeeColors.FoundGreenDark) else BeeColors.PollenOrange,
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "Details: ${log.foundDetails ?: "No details available."}",
                fontSize   = 12.sp,
                fontFamily = FontFamily.Monospace,
                color      = monoColor
            )
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
    context.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)
        .edit().putStringSet(key, sources.toSet()).apply()
}

fun loadSources(context: Context, key: String): List<String> {
    val prefs = context.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)
    if (!prefs.contains(key)) {
        return when (key) {
            "shows"         -> listOf(
                "+https://ww25.soap2day.day/?s=",
                "-https://myflixerz.to/search/",
                "-https://himovies.sx/search/",
                "+https://www.lookmovie2.to/shows/search/?q=",
                "+https://westream.to/search?keyword=",
                "+https://1movies.bz/browser?keyword=",
                "+https://yflix.to/browser?keyword=",
                "+https://hianime.city/?s=",
                "+https://gogoanime.by/?s=",
                "+https://aniwatchtv.to/search?keyword="
            )
            "movies"        -> listOf(
                "+https://ww25.soap2day.day/?s=",
                "-https://myflixerz.to/search/",
                "-https://himovies.sx/search/",
                "+https://www.lookmovie2.to/movies/search/?q=",
                "+https://westream.to/search?keyword=",
                "+https://1movies.bz/browser?keyword=",
                "+https://yflix.to/browser?keyword="
            )
            "manual_checks" -> listOf(
                "+https://tmovie.tv/search?query=",
                "+https://www.1flex.nl/search?q=",
                "+https://ww4.fmovies.co/search/?q=",
                "+https://ww8.123moviesfree.net/search/?q="
            )
            "api_sites"     -> listOf(
                "v1:https://ww8.123moviesfree.net",
                "v1:https://ww4.fmovies.co",
                "stremio:https://v3-cinemeta.strem.io"
            )
            else            -> emptyList()
        }
    }
    return prefs.getStringSet(key, null)?.toList() ?: emptyList()
}