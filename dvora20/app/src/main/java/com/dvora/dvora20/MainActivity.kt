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
import androidx.compose.ui.window.Dialog
import com.dvora.dvora20.ui.theme.Dvora20Theme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Dvora20Theme {
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
                title = { Text("DVORA 2.0") },
                actions = {
                    IconButton(onClick = { 
                        showSubtitles = true 
                        showSettings = false
                    }) {
                        Icon(Icons.Default.Subtitles, contentDescription = "Subtitles")
                    }
                    IconButton(onClick = { 
                        showSettings = true 
                        showSubtitles = false
                    }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
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
                            SourceType.SHOW -> { shows = newList; saveSources(context, "shows", newList) }
                            SourceType.MOVIE -> { movies = newList; saveSources(context, "movies", newList) }
                            SourceType.MANUAL -> { manualChecks = newList; saveSources(context, "manual_checks", newList) }
                            SourceType.API -> { apiSites = newList; saveSources(context, "api_sites", newList) }
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
                    OutlinedTextField(
                        value = searchTerm,
                        onValueChange = { searchTerm = it },
                        label = { Text("Search Movie or Show") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        RadioButton(selected = searchType == SourceType.SHOW, onClick = { searchType = SourceType.SHOW })
                        Text("Shows")
                        Spacer(Modifier.width(16.dp))
                        RadioButton(selected = searchType == SourceType.MOVIE, onClick = { searchType = SourceType.MOVIE })
                        Text("Movies")
                    }

                    Button(
                        onClick = {
                            if (searchTerm.isBlank()) return@Button
                            isSearching = true
                            results = emptyList()

                            scope.launch {
                                val currentResults = mutableListOf<SearchResult>()
                                
                                // 1. Scan Sites (Shows/Movies)
                                val activeSources = if (searchType == SourceType.SHOW) shows else movies
                                activeSources.forEach { url ->
                                    currentResults.add(scanner.scanSite(url, searchTerm))
                                }

                                // 2. Scan APIs (Stremio/v1)
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
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSearching
                    ) {
                        if (isSearching) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                        else Text("Search")
                    }

                    Spacer(Modifier.height(16.dp))

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (results.isNotEmpty()) {
                            item { Text("RESULTS:", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                            items(results) { result -> ResultItem(result, showDetails = true) }
                        }
                        if (manualLinks.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(16.dp))
                                Text("MANUAL CHECKS:", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            items(manualLinks) { link ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openUrl(context, link) },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Text(link, modifier = Modifier.padding(8.dp), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
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

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Search Subtitles", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = searchTerm,
            onValueChange = { searchTerm = it },
            label = { Text("Movie or Show Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            RadioButton(selected = searchType == SourceType.SHOW, onClick = { searchType = SourceType.SHOW })
            Text("Shows")
            Spacer(Modifier.width(16.dp))
            RadioButton(selected = searchType == SourceType.MOVIE, onClick = { searchType = SourceType.MOVIE })
            Text("Movies")
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
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSearching,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            if (isSearching) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
            else Text("Search Subtitles")
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(results) { result ->
                ResultItem(result, showDetails = true)
            }
        }
    }
}

@Composable
fun ResultItem(result: SearchResult, showDetails: Boolean = false) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openUrl(context, result.url) },
        colors = CardDefaults.cardColors(containerColor = if (result.found) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (result.found) Icons.Default.CheckCircle else Icons.Default.Close,
                    null,
                    tint = if (result.found) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (result.found) "Found!" else "Not Found",
                    fontWeight = FontWeight.Bold,
                    color = if (result.found) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
            }
            Text(result.url, fontSize = 12.sp, color = Color.Gray)
            if (showDetails && result.foundDetails != null) {
                Text(result.foundDetails, fontSize = 12.sp, color = Color.DarkGray, fontWeight = FontWeight.Medium)
            }
            if (result.errorMessage != null) {
                Text("Error: ${result.errorMessage}", color = Color.Red, fontSize = 12.sp)
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

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 16.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }

        when (selectedTab) {
            0 -> SourceEditor(shows) { onUpdate(SourceType.SHOW, it) }
            1 -> SourceEditor(movies) { onUpdate(SourceType.MOVIE, it) }
            2 -> SourceEditor(apiSites) { onUpdate(SourceType.API, it) }
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

    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newItem,
                onValueChange = { newItem = it },
                label = { Text(if (editingIndex == -1) "Add Item" else "Edit Item") },
                modifier = Modifier.weight(1f)
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
            }) { Icon(if (editingIndex == -1) Icons.Default.Add else Icons.Default.Check, null) }
            
            if (editingIndex == -1) {
                IconButton(onClick = { filePickerLauncher.launch("text/plain") }) { Icon(Icons.Default.FileUpload, null) }
            } else {
                IconButton(onClick = { editingIndex = -1; newItem = "" }) { Icon(Icons.Default.Close, null) }
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn {
            itemsIndexed(list) { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(item, modifier = Modifier.weight(1f).clickable {
                        newItem = item
                        editingIndex = index
                    }, fontSize = 14.sp)
                    IconButton(onClick = { onUpdate(list - item) }) { Icon(Icons.Default.Delete, null, tint = Color.Gray) }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun VerboseLogsScreen() {
    val logs = SearchLogs.lastLogs
    if (logs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No logs from last search.") }
    } else {
        LazyColumn(Modifier.padding(16.dp)) {
            items(logs) { log ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(log.url, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text("Status: ", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(if (log.found) "FOUND" else "NOT FOUND", color = if (log.found) Color(0xFF2E7D32) else Color(0xFFC62828), fontSize = 12.sp)
                        }
                        Text("Details: ${log.foundDetails ?: "No details available."}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        if (log.errorMessage != null) {
                            Text("Error: ${log.errorMessage}", color = Color.Red, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// Storage Helpers
fun saveSources(context: Context, key: String, sources: List<String>) {
    context.getSharedPreferences("dvora_prefs", Context.MODE_PRIVATE).edit().putStringSet(key, sources.toSet()).apply()
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
