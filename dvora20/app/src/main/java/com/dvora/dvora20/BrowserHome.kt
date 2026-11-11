package com.dvora.dvora20

import android.content.Context
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
private const val KEY_SUGGEST = "search_suggestions"

/** live suggestions are on unless the user turned them off in the browser settings tab. */
fun suggestionsEnabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SUGGEST, true)

fun setSuggestionsEnabled(context: Context, on: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_SUGGEST, on)
        .apply()
}

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
    val suggestionsOn = suggestionsEnabled(context)

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var fieldFocused by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    val bg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)
    val fg = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val sub = fg.copy(alpha = 0.6f)
    val fieldBg = beeAdapt(BeeColors.HoneycombYellow.copy(alpha = 0.4f), BeeColors.DarkCell)

    fun go() {
        val q = query.trim()
        if (q.isNotEmpty()) {
            suggestions = emptyList()
            onSearch(q)
        }
    }

    // engine-aware live suggestions — debounced so typing does not spam the endpoint;
    // they disappear with the keyboard, so closing it doesn't reflow the page twice
    LaunchedEffect(engine, query, suggestionsOn, fieldFocused) {
        val q = query.trim()
        if (!fieldFocused || !suggestionsOn || q.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        suggestions = fetchSuggestions(engine, q)
    }

    BoxWithConstraints(modifier.fillMaxSize().background(bg)) {
        // The IME inset is animated by hand: padding that snaps from 0 to the keyboard
        // height makes the whole page bounce when the keyboard closes.
        val density = LocalDensity.current
        val imeTarget = with(density) { WindowInsets.ime.getBottom(density).toDp() }
        val imeDp by animateDpAsState(
            targetValue = imeTarget,
            animationSpec = tween(durationMillis = 220),
            label = "ime"
        )
        // the keyboard toolbar can grow the IME a lot — the suggestion list shrinks with
        // the remaining space instead of squashing the field / button
        val usable = (maxHeight - imeDp).coerceAtLeast(0.dp)
        val tipsMax = (usable * 0.32f).coerceIn(72.dp, 170.dp)
        Column(Modifier.fillMaxSize().padding(bottom = imeDp)) {
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
                    .padding(horizontal = 26.dp)
                    .padding(top = 6.dp, bottom = 12.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { fieldFocused = it.isFocused },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = fg,
                        textAlign = TextAlign.Start,
                        // direction follows the typed script: empty/English stay LTR,
                        // Hebrew/Arabic switch the paragraph so the caret sits at the
                        // real end of the text instead of the wrong side
                        textDirection = TextDirection.Content
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

                // suggestions drop in ABOVE the button, never over it
                if (suggestionsOn && suggestions.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = beeAdapt(Color.White, BeeColors.DarkCell),
                        tonalElevation = 2.dp,
                        shadowElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth().animateContentSize()
                    ) {
                        Column(
                            Modifier
                                .heightIn(max = tipsMax)
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
                                        .padding(horizontal = 12.dp, vertical = 9.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Search, null,
                                        tint = BeeColors.DeepAmber, modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(9.dp))
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
                    Spacer(Modifier.height(8.dp))
                }

                // the SEARCH button is the bottom-most control: locked right above the
                // keyboard like an extra toolbar, whatever the list does
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
                Spacer(Modifier.height(6.dp))
                // which engine this searches with — the choice lives in the browser settings tab
                Text(
                    L(if (engine == ENGINE_GOOGLE) R.string.home_engine_google else R.string.home_engine_ddg),
                    fontSize = 10.sp,
                    color = sub,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Shown when a NEW tab is requested: the current tab stays untouched behind a
 * bee-themed prompt that asks what to search or open. Same engine + suggestion
 * rules as the start page; cancelling creates nothing.
 */
@Composable
fun NewTabSearchDialog(
    engine: String,
    suggestionsOn: Boolean,
    onDismiss: () -> Unit,
    onGo: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var tips by remember { mutableStateOf<List<String>>(emptyList()) }
    val focus = remember { FocusRequester() }

    val cardBg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)
    val rowBg = beeAdapt(BeeColors.HoneycombYellow.copy(alpha = 0.35f), BeeColors.DarkCell)
    val fg = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val sub = fg.copy(alpha = 0.6f)
    val fieldBg = beeAdapt(BeeColors.HoneycombYellow.copy(alpha = 0.4f), BeeColors.DarkCell)

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    LaunchedEffect(engine, text, suggestionsOn) {
        val q = text.trim()
        if (!suggestionsOn || q.length < 2) {
            tips = emptyList()
            return@LaunchedEffect
        }
        delay(220)
        tips = fetchSuggestions(engine, q)
    }

    fun submit(value: String) {
        val v = value.trim()
        if (v.isNotEmpty()) {
            tips = emptyList()
            onGo(v)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(cardBg)
                .border(1.5.dp, BeeColors.HoneyGold.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            // ── hive header ───────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(BeeColors.HoneyGold.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) { Text("🐝", fontSize = 17.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        L(R.string.newtab_badge),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 3.sp,
                        color = BeeColors.HoneyGold
                    )
                    Text(
                        L(R.string.newtab_search_title),
                        fontSize = 10.sp,
                        color = sub,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Close, L(R.string.cancel),
                        tint = BeeColors.DeepAmber, modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = fg,
                    textAlign = TextAlign.Start,
                    textDirection = TextDirection.Content
                ),
                leadingIcon = {
                    Icon(
                        Icons.Default.Search, null,
                        tint = BeeColors.DeepAmber, modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(
                            onClick = { text = ""; tips = emptyList() },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Close, L(R.string.cancel),
                                tint = sub, modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                placeholder = { Text(L(R.string.home_search_hint), fontSize = 13.sp, color = sub) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = fg,
                    unfocusedTextColor = fg,
                    focusedBorderColor = BeeColors.HoneyGold,
                    unfocusedBorderColor = BeeColors.DeepAmber.copy(alpha = 0.45f),
                    focusedContainerColor = fieldBg,
                    unfocusedContainerColor = fieldBg,
                    cursorColor = BeeColors.HoneyGold
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { submit(text) }, onGo = { submit(text) })
            )

            if (tips.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(rowBg)
                        .heightIn(max = 186.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    tips.forEachIndexed { idx, s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { submit(s) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                Icons.Default.Search, null,
                                tint = BeeColors.DeepAmber.copy(alpha = 0.8f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(9.dp))
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
                        if (idx != tips.lastIndex) {
                            HorizontalDivider(
                                color = BeeColors.HoneyGold.copy(alpha = 0.12f),
                                thickness = 1.dp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.42f).height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe),
                        contentColor = beeAdapt(BeeColors.BeeBlack, BeeColors.HoneyGold)
                    )
                ) {
                    Text(L(R.string.cancel), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { submit(text) },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.weight(0.58f).height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BeeColors.HoneyGold,
                        contentColor = BeeColors.BeeBlack,
                        disabledContainerColor = BeeColors.HoneyGold.copy(alpha = 0.35f),
                        disabledContentColor = BeeColors.BeeBlack.copy(alpha = 0.55f)
                    )
                ) {
                    Text(
                        L(R.string.home_search_go),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                }
            }
        }
    }
}