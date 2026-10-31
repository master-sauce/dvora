package com.dvora.dvora20

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// ═══════════════════════════════════════════════════════════════════════════════
// BROWSER HOME + SEARCH ENGINES
// The home tab is a Compose screen, not a search-engine page load: bee-themed,
// following the app's light/dark choice. Free-text input searches with whichever
// engine the browser settings tab (⚙ → browser) has selected.
// ═══════════════════════════════════════════════════════════════════════════════

const val ENGINE_GOOGLE = "google"
const val ENGINE_DUCKDUCKGO = "duckduckgo"

private const val PREFS = "dvora_prefs"
private const val KEY_ENGINE = "search_engine"

/** the configured engine — unknown / missing values fall back to Google. */
fun searchEngine(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_ENGINE, ENGINE_GOOGLE)
        ?.takeIf { it == ENGINE_GOOGLE || it == ENGINE_DUCKDUCKGO }
        ?: ENGINE_GOOGLE

fun setSearchEngine(context: Context, engine: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_ENGINE, if (engine == ENGINE_GOOGLE) ENGINE_GOOGLE else ENGINE_DUCKDUCKGO)
        .apply()
}

/** the search url of the configured engine for a free-text query. */
fun searchUrl(context: Context, query: String): String {
    val q = Uri.encode(query.trim(), "")
    return if (searchEngine(context) == ENGINE_GOOGLE)
        "https://www.google.com/search?q=$q"
    else
        "https://duckduckgo.com/?q=$q"
}

/**
 * Text direction of a typed field: the first strong directional character wins.
 * Hebrew / Arabic read right-to-left, so their text is right-aligned while
 * English keeps the usual left alignment.
 */
fun isRtlText(text: String): Boolean {
    for (ch in text) {
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return false
}

/**
 * Live search suggestions from the engine the user picked: Google's suggest
 * endpoint or DuckDuckGo's autocomplete, both answering the same
 * `[query, [suggestion, …]]` shape.
 */
private val suggestClient = OkHttpClient.Builder()
    .callTimeout(4, TimeUnit.SECONDS)
    .build()

private suspend fun fetchSuggestions(engine: String, query: String): List<String> =
    withContext(Dispatchers.IO) {
        val url = if (engine == ENGINE_GOOGLE)
            "https://suggestqueries.google.com/complete/search?client=firefox&hl=en&q=" + Uri.encode(query)
        else
            "https://duckduckgo.com/ac/?type=list&q=" + Uri.encode(query)
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) Dvora")
                .build()
            suggestClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string() ?: return@use emptyList()
                val arr = JsonParser.parseString(body).asJsonArray
                if (arr.size() < 2) return@use emptyList()
                val items = arr[1].asJsonArray
                items.mapNotNull { el -> runCatching { el.asString }.getOrNull() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(6)
            }
        }.getOrDefault(emptyList())
    }

/**
 * The browser's start page — logo above, one search field pinned to the bottom of
 * the viewport. With the keyboard up that bottom bar sits directly on top of it,
 * like an extra toolbar, so the field and its button are never covered.
 */
@Composable
fun BrowserHomePage(
    incognito: Boolean,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // read per composition — the settings tab writes the pref while this screen is away
    val engine = searchEngine(context)

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var barHeightPx by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    val bg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)
    val fg = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val sub = fg.copy(alpha = 0.6f)
    val fieldBg = beeAdapt(BeeColors.HoneycombYellow.copy(alpha = 0.4f), BeeColors.DarkCell)
    // Hebrew / Arabic input sits on the right, English on the left
    val rtl = isRtlText(query)

    fun go() {
        val q = query.trim()
        if (q.isNotEmpty()) {
            suggestions = emptyList()
            onSearch(q)
        }
    }

    // engine-aware live suggestions — debounced so typing does not spam the endpoint
    LaunchedEffect(engine, query) {
        val q = query.trim()
        if (q.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        suggestions = fetchSuggestions(engine, q)
    }

    Box(modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // ── hive mark — takes the space above the search bar ──────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 26.dp)
            ) {
                Text("🐝", fontSize = 46.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "DVORA",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 7.sp,
                    color = BeeColors.HoneyGold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    L(R.string.home_tagline),
                    fontSize = 11.sp,
                    color = sub,
                    textAlign = TextAlign.Center
                )

                if (incognito) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe),
                                RoundedCornerShape(50)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("🕵", fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            L(R.string.home_incognito),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = fg
                        )
                    }
                }
            }

            // ── search bar — locked to the bottom, i.e. right above the keyboard ──
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { barHeightPx = it.size.height }
                    .padding(horizontal = 26.dp)
                    .padding(top = 6.dp, bottom = 12.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = fg,
                        textAlign = if (rtl) TextAlign.End else TextAlign.Start
                    ),
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search, null,
                            tint = BeeColors.DeepAmber, modifier = Modifier.size(20.dp)
                        )
                    },
                    placeholder = { Text(L(R.string.home_search_hint), fontSize = 14.sp, color = sub) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = fg,
                        unfocusedTextColor = fg,
                        focusedBorderColor = BeeColors.HoneyGold,
                        unfocusedBorderColor = BeeColors.DeepAmber.copy(alpha = 0.5f),
                        focusedContainerColor = fieldBg,
                        unfocusedContainerColor = fieldBg,
                        cursorColor = BeeColors.HoneyGold
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(onSearch = { go() }, onGo = { go() }, onDone = { go() })
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { go() },
                    enabled = query.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BeeColors.HoneyGold,
                        contentColor = BeeColors.BeeBlack,
                        disabledContainerColor = BeeColors.HoneyGold.copy(alpha = 0.35f),
                        disabledContentColor = BeeColors.BeeBlack.copy(alpha = 0.55f)
                    )
                ) {
                    Text(L(R.string.home_search_go), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }

                Spacer(Modifier.height(8.dp))

                // which engine this searches with — the choice itself lives in the browser settings tab
                Text(
                    L(if (engine == ENGINE_GOOGLE) R.string.home_engine_google else R.string.home_engine_ddg),
                    fontSize = 10.sp,
                    color = sub,
                    textAlign = TextAlign.Center
                )
            }
        }

        // suggestions float ABOVE the pinned bar in the same keyboard frame — the field
        // never moves while typing, so the text being typed stays visible
        if (suggestions.isNotEmpty()) {
            Box(Modifier.fillMaxSize().imePadding()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = beeAdapt(Color.White, BeeColors.DarkCell),
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 26.dp)
                        .padding(bottom = with(density) { barHeightPx.toDp() })
                        .fillMaxWidth()
                ) {
                    Column(
                        Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        suggestions.forEach { s ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        query = s
                                        suggestions = emptyList()
                                        onSearch(s)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            ) {
                                Icon(
                                    Icons.Default.Search, null,
                                    tint = BeeColors.DeepAmber, modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    s,
                                    fontSize = 13.sp,
                                    color = fg,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = if (isRtlText(s)) TextAlign.End else TextAlign.Start,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
