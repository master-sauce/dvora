package com.dvora.dvora20

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dvora.dvora20.ui.theme.Dvora20Theme
import kotlinx.coroutines.launch

// ── Bee Theme Colors ──────────────────────────────────────────────────────────
object BeeColors {
    val HoneyGold       = Color(0xFFFFC107) // primary amber
    val DeepAmber       = Color(0xFFFF8F00) // darker amber for accents
    val HoneycombYellow = Color(0xFFFFECB3) // soft background tint
    val BeeBlack        = Color(0xFF1A1200) // near-black with warm tint
    val BeeStripe       = Color(0xFF212121) // dark grey stripe
    val WaxWhite        = Color(0xFFFFFDE7) // cream white
    val PollenOrange    = Color(0xFFFF6D00) // vivid accent
    val ComбBlue        = Color(0xFF424242) // muted dark for surfaces
    val FoundGreen      = Color(0xFF558B2F) // earthy green for "found"
    val NotFoundRed     = Color(0xFFB71C1C) // deep red for "not found"
}

// Bee-themed Material3 ColorScheme
private val BeeColorScheme = lightColorScheme(
    primary            = BeeColors.HoneyGold,
    onPrimary          = BeeColors.BeeBlack,
    primaryContainer   = BeeColors.HoneycombYellow,
    onPrimaryContainer = BeeColors.BeeBlack,
    secondary          = BeeColors.DeepAmber,
    onSecondary        = BeeColors.WaxWhite,
    secondaryContainer = Color(0xFFFFD54F),
    onSecondaryContainer = BeeColors.BeeBlack,
    tertiary           = BeeColors.PollenOrange,
    onTertiary         = BeeColors.WaxWhite,
    background         = BeeColors.WaxWhite,
    onBackground       = BeeColors.BeeBlack,
    surface            = BeeColors.WaxWhite,
    onSurface          = BeeColors.BeeBlack,
    surfaceVariant     = BeeColors.HoneycombYellow,
    onSurfaceVariant   = Color(0xFF4E3B00),
    outline            = BeeColors.DeepAmber,
    error              = BeeColors.NotFoundRed,
    onError            = Color.White,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Wrap in bee theme
            MaterialTheme(colorScheme = BeeColorScheme) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    DvoraApp()
                }
            }
        }
    }
}

object SearchLogs {
    var lastLogs by mutableStateOf<List<SearchResult>>(emptyList())
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

// ── Honeycomb stripe background modifier ─────────────────────────────────────
private val HoneycombHeaderBrush = Brush.horizontalGradient(
    colors = listOf(BeeColors.BeeBlack, Color(0xFF3E2800), BeeColors.BeeBlack)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DvoraApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember { DvoraScanner() }

    var searchTerm by remember { mutableStateOf("") }
    var searchType by remember { mutableStateOf(SourceType.SHOW) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var manualLinks by remember { mutableStateOf<List<String>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    var shows by remember { mutableStateOf(loadSources(context, "shows")) }
    var movies by remember { mutableStateOf(loadSources(context, "movies")) }
    var manualChecks by remember { mutableStateOf(loadSources(context, "manual_checks")) }
    var apiSites by remember { mutableStateOf(loadSources(context, "api_sites")) }

    var showSettings by remember { mutableStateOf(false) }
    var showSubtitles by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🐝",
                            fontSize = 22.sp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "DVORA",
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 4.sp,
                            color = BeeColors.HoneyGold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BeeColors.BeeBlack,
                    titleContentColor = BeeColors.HoneyGold,
                    actionIconContentColor = BeeColors.HoneyGold
                ),
                actions = {
                    IconButton(onClick = {
                        showSubtitles = true
                        showSettings = false
                    }) {
                        Icon(
                            Icons.Default.Subtitles,
                            contentDescription = "Subtitles",
                            tint = BeeColors.HoneyGold
                        )
                    }
                    IconButton(onClick = {
                        showSettings = true
                        showSubtitles = false
                    }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = BeeColors.HoneyGold
                        )
                    }
                }
            )
        },
        containerColor = BeeColors.WaxWhite,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        when {
            showSettings -> {
                SettingsScreen(
                    shows = shows,
                    movies = movies,
                    manualChecks = manualChecks,
                    apiSites = apiSites,
                    onUpdate = { type, newList ->
                        when (type) {
                            SourceType.SHOW   -> { shows = newList;        saveSources(context, "shows", newList) }
                            SourceType.MOVIE  -> { movies = newList;       saveSources(context, "movies", newList) }
                            SourceType.MANUAL -> { manualChecks = newList; saveSources(context, "manual_checks", newList) }
                            SourceType.API    -> { apiSites = newList;     saveSources(context, "api_sites", newList) }
                        }
                    },
                    onBack = { showSettings = false },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            showSubtitles -> {
                SubtitlesScreen(
                    scanner = scanner,
                    onBack = { showSubtitles = false },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .padding(16.dp)
                        .fillMaxSize()
                ) {
                    // ── Honeycomb search card ───────────────────────────────
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = BeeColors.HoneycombYellow),
                        elevation = CardDefaults.cardElevation(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = searchTerm,
                                onValueChange = { searchTerm = it },
                                label = { Text("🍯 Search Movie or Show") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = BeeColors.DeepAmber,
                                    unfocusedBorderColor = BeeColors.HoneyGold,
                                    focusedLabelColor    = BeeColors.DeepAmber,
                                    cursorColor          = BeeColors.DeepAmber
                                )
                            )

                            Spacer(Modifier.height(12.dp))

                            // ── Type selector with bee stripe pill ─────────
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                BeeRadioOption(
                                    label = "📺 Shows",
                                    selected = searchType == SourceType.SHOW,
                                    onClick = { searchType = SourceType.SHOW },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                BeeRadioOption(
                                    label = "🎬 Movies",
                                    selected = searchType == SourceType.MOVIE,
                                    onClick = { searchType = SourceType.MOVIE },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Search button ───────────────────────────────────────
                    Button(
                        onClick = {
                            if (searchTerm.isBlank()) return@Button
                            isSearching = true
                            results = emptyList()

                            scope.launch {
                                val currentResults = mutableListOf<SearchResult>()

                                val activeSources = if (searchType == SourceType.SHOW) shows else movies
                                activeSources.forEach { url ->
                                    currentResults.add(scanner.scanSite(url, searchTerm))
                                }

                                apiSites.forEach { site ->
                                    when {
                                        site.startsWith("stremio:") -> {
                                            val base = site.removePrefix("stremio:")
                                            currentResults.addAll(scanner.scanStremio(base, searchTerm, searchType))
                                        }
                                        site.startsWith("v1:") -> {
                                            val base = site.removePrefix("v1:")
                                            currentResults.addAll(scanner.scanV1(base, searchTerm))
                                        }
                                        else -> {
                                            currentResults.addAll(scanner.scanV1(site, searchTerm))
                                        }
                                    }
                                }

                                results = currentResults
                                SearchLogs.lastLogs = currentResults
                                manualLinks = manualChecks.map { scanner.getManualCheck(it, searchTerm) }
                                isSearching = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = !isSearching,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BeeColors.BeeBlack,
                            contentColor   = BeeColors.HoneyGold,
                            disabledContainerColor = Color(0xFF4A3B00),
                            disabledContentColor   = BeeColors.HoneyGold.copy(alpha = 0.5f)
                        )
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = BeeColors.HoneyGold,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "🐝  BUZZ & SEARCH",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (results.isNotEmpty()) {
                            item {
                                BeesSectionHeader(title = "🍯 Results")
                            }
                            items(results) { result -> ResultItem(result, showDetails = true) }
                        }
                        if (manualLinks.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(16.dp))
                                BeesSectionHeader(title = "🔍 Manual Checks")
                            }
                            items(manualLinks) { link ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { openUrl(context, link) },
                                    colors = CardDefaults.cardColors(containerColor = BeeColors.HoneycombYellow),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(10.dp)
                                    ) {
                                        Text("↗", fontSize = 16.sp, color = BeeColors.DeepAmber)
                                        Spacer(Modifier.width(8.dp))
                                        Text(link, fontSize = 12.sp, color = Color(0xFF4E3B00))
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

// ── Reusable bee-styled radio option ─────────────────────────────────────────
@Composable
fun BeeRadioOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (selected) BeeColors.BeeBlack else Color.Transparent
    val textColor = if (selected) BeeColors.HoneyGold else BeeColors.BeeBlack

    Surface(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = bg,
        tonalElevation = if (selected) 0.dp else 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.5.dp,
            color = if (selected) BeeColors.BeeBlack else BeeColors.DeepAmber
        )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(label, color = textColor, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
        }
    }
}

// ── Section header with honey-stripe decoration ───────────────────────────────
@Composable
fun BeesSectionHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
                .background(BeeColors.HoneyGold)
        )
        Text(
            text = title,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 14.sp,
            color = BeeColors.BeeBlack,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
                .background(BeeColors.HoneyGold)
        )
    }
}

@Composable
fun SubtitlesScreen(
    scanner: DvoraScanner,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchTerm by remember { mutableStateOf("") }
    var searchType by remember { mutableStateOf(SourceType.SHOW) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(BeeColors.WaxWhite).padding(16.dp)) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(BeeColors.BeeBlack, shape = RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BeeColors.HoneyGold)
            }
            Text(
                "🎞️  Subtitles",
                style = MaterialTheme.typography.titleLarge,
                color = BeeColors.HoneyGold,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = searchTerm,
            onValueChange = { searchTerm = it },
            label = { Text("Movie or Show Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = BeeColors.DeepAmber,
                unfocusedBorderColor = BeeColors.HoneyGold,
                focusedLabelColor    = BeeColors.DeepAmber
            )
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            BeeRadioOption("📺 Shows",  searchType == SourceType.SHOW,  { searchType = SourceType.SHOW },  Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            BeeRadioOption("🎬 Movies", searchType == SourceType.MOVIE, { searchType = SourceType.MOVIE }, Modifier.weight(1f))
        }

        Button(
            onClick = {
                if (searchTerm.isBlank()) return@Button
                isSearching = true
                results = emptyList()
                scope.launch {
                    val searchResults = scanner.scanSubtitles(searchTerm, searchType)
                    results = searchResults
                    SearchLogs.lastLogs = searchResults
                    isSearching = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !isSearching,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BeeColors.DeepAmber,
                contentColor   = BeeColors.WaxWhite
            )
        ) {
            if (isSearching) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = BeeColors.WaxWhite, strokeWidth = 2.dp)
            else Text("🐝  Search Subtitles", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(results) { result -> ResultItem(result, showDetails = true) }
        }
    }
}

@Composable
fun ResultItem(result: SearchResult, showDetails: Boolean = false) {
    val context = LocalContext.current
    val foundBg    = Color(0xFFF1F8E9) // light earthy green tint
    val notFoundBg = Color(0xFFFFF8E1) // pale amber tint (not harsh red)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { openUrl(context, result.url) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.found) foundBg else notFoundBg
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        // Colored left accent stripe
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(
                        color = if (result.found) BeeColors.FoundGreen else BeeColors.DeepAmber,
                        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                    )
            )
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (result.found) "✅" else "🟡",
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (result.found) "Found!" else "Not Found",
                        fontWeight = FontWeight.Bold,
                        color = if (result.found) BeeColors.FoundGreen else Color(0xFF8D5A00)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(result.url, fontSize = 11.sp, color = Color(0xFF795548))
                if (showDetails && result.foundDetails != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        result.foundDetails,
                        fontSize = 12.sp,
                        color = Color(0xFF4E342E),
                        fontWeight = FontWeight.Medium
                    )
                }
                if (result.errorMessage != null) {
                    Text("⚠️ ${result.errorMessage}", color = BeeColors.NotFoundRed, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    shows: List<String>,
    movies: List<String>,
    manualChecks: List<String>,
    apiSites: List<String>,
    onUpdate: (SourceType, List<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Shows", "Movies", "APIs", "Manual", "Logs")

    Column(modifier = modifier.fillMaxSize().background(BeeColors.WaxWhite)) {
        // Settings header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(BeeColors.BeeBlack)
                .padding(4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BeeColors.HoneyGold)
            }
            Text(
                "⚙️  Settings",
                style = MaterialTheme.typography.titleLarge,
                color = BeeColors.HoneyGold,
                fontWeight = FontWeight.Bold
            )
        }

        // Bee-striped tab row
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 16.dp,
            containerColor = BeeColors.BeeBlack,
            contentColor = BeeColors.HoneyGold,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier
                        .1tabIndicatorOffset(tabPositions[selectedTab]),
                    color = BeeColors.HoneyGold,
                    height = 3.dp
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            title,
                            color = if (selectedTab == index) BeeColors.HoneyGold else BeeColors.HoneyGold.copy(alpha = 0.5f),
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

@Composable
fun SourceEditor(list: List<String>, onUpdate: (List<String>) -> Unit) {
    var newItem by remember { mutableStateOf("") }
    var editingIndex by remember { mutableIntStateOf(-1) }
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val lines = inputStream?.bufferedReader()?.use { r -> r.readLines() } ?: emptyList()
                val clean = lines.map { l -> l.trim() }.filter { l -> l.isNotBlank() }
                if (clean.isNotEmpty()) onUpdate((list + clean).distinct())
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp).background(BeeColors.WaxWhite)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newItem,
                onValueChange = { newItem = it },
                label = { Text(if (editingIndex == -1) "Add Item" else "Edit Item") },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = BeeColors.DeepAmber,
                    unfocusedBorderColor = BeeColors.HoneyGold,
                    focusedLabelColor    = BeeColors.DeepAmber
                )
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
            }) {
                Icon(
                    if (editingIndex == -1) Icons.Default.Add else Icons.Default.Check,
                    null,
                    tint = BeeColors.DeepAmber
                )
            }

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
                            if (index % 2 == 0) BeeColors.HoneycombYellow.copy(alpha = 0.4f) else Color.Transparent,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "🔗 ",
                        fontSize = 12.sp
                    )
                    Text(
                        item,
                        modifier = Modifier.weight(1f).clickable {
                            newItem = item
                            editingIndex = index
                        },
                        fontSize = 13.sp,
                        color = Color(0xFF4E3B00)
                    )
                    IconButton(onClick = { onUpdate(list - item) }) {
                        Icon(Icons.Default.Delete, null, tint = BeeColors.DeepAmber.copy(alpha = 0.7f))
                    }
                }
                HorizontalDivider(color = BeeColors.HoneyGold.copy(alpha = 0.3f))
            }
        }
    }
}

@Composable
fun VerboseLogsScreen() {
    val logs = SearchLogs.lastLogs
    if (logs.isEmpty()) {
        Box(
            Modifier.fillMaxSize().background(BeeColors.WaxWhite),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🐝", fontSize = 48.sp)
                Spacer(Modifier.height(8.dp))
                Text("No logs from last search.", color = Color(0xFF8D5A00))
            }
        }
    } else {
        LazyColumn(Modifier.padding(16.dp).background(BeeColors.WaxWhite)) {
            items(logs) { log -> LogItem(log) }
        }
    }
}

@Composable
fun LogItem(log: SearchResult) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BeeColors.HoneycombYellow),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (log.found) "✅" else "🟡",
                    fontSize = 16.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    log.url,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFF3E2800),
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = BeeColors.DeepAmber
                )
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text("Status: ", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF5D4037))
                Text(
                    if (log.found) "FOUND" else "NOT FOUND",
                    color = if (log.found) BeeColors.FoundGreen else BeeColors.PollenOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "Details: ${log.foundDetails ?: "No details available."}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF5D4037)
            )

            if (expanded && log.verboseLogs != null) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = BeeColors.HoneyGold.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = log.verboseLogs,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF4E342E)
                )
            }

            if (log.errorMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text("⚠️ ${log.errorMessage}", color = BeeColors.NotFoundRed, fontSize = 12.sp)
            }
        }
    }
}

// ── Storage Helpers (unchanged) ───────────────────────────────────────────────
fun saveSources(context: Context, key: String, sources: List<String>) {
    context.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)
        .edit().putStringSet(key, sources.toSet()).apply()
}

fun loadSources(context: Context, key: String): List<String> {
    val prefs = context.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE)

    if (!prefs.contains(key)) {
        return when (key) {
            "shows" -> listOf(
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
            "movies" -> listOf(
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
            "api_sites" -> listOf(
                "v1:https://ww8.123moviesfree.net",
                "v1:https://ww4.fmovies.co",
                "stremio:https://v3-cinemeta.strem.io"
            )
            else -> emptyList()
        }
    }

    return prefs.getStringSet(key, null)?.toList() ?: emptyList()
}