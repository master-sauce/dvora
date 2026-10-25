package com.dvora.dvora20

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════════════════════════════════════════════
// BROWSER HOME + SEARCH ENGINES
// The home tab is a Compose screen, not a duckduckgo.com page load: bee-themed,
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
 * The browser's start page — logo, one search field, one action. Rendered over
 * the WebView while the tab sits on [BROWSER_HOME]; submitting a query hands the
 * resolved search url back to the caller, which navigates the very same tab.
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

    val bg = beeAdapt(BeeColors.WaxWhite, BeeColors.DarkComb)
    val fg = beeAdapt(BeeColors.BeeBlack, BeeColors.DarkOnSurface)
    val sub = fg.copy(alpha = 0.6f)
    val fieldBg = beeAdapt(BeeColors.HoneycombYellow.copy(alpha = 0.4f), BeeColors.DarkCell)
    // Hebrew / Arabic input sits on the right, English on the left
    val rtl = isRtlText(query)

    fun go() {
        val q = query.trim()
        if (q.isNotEmpty()) onSearch(q)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp)
    ) {
        Spacer(Modifier.height(64.dp))

        // ── hive mark ─────────────────────────────────────────────────────────
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
                    .background(beeAdapt(BeeColors.HoneycombYellow, BeeColors.DarkStripe), RoundedCornerShape(50))
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

        Spacer(Modifier.height(34.dp))

        // ── search ────────────────────────────────────────────────────────────
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }, onGo = { go() }, onDone = { go() })
        )

        Spacer(Modifier.height(10.dp))

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

        Spacer(Modifier.height(12.dp))

        // which engine this searches with — the choice itself lives in the browser settings tab
        Text(
            L(if (engine == ENGINE_GOOGLE) R.string.home_engine_google else R.string.home_engine_ddg),
            fontSize = 10.sp,
            color = sub,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(56.dp))
    }
}
