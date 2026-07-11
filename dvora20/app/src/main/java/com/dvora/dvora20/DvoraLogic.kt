package com.dvora.dvora20

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class SearchResult(
    val url: String,
    val found: Boolean,
    val errorMessage: String? = null,
    val foundDetails: String? = null,
    val verboseLogs: String? = null
)

data class ImdbResult(
    val imdbId: String,
    val title: String,
    val year: String?,
    val mediaType: String?,
    val posterUrl: String?,
    val imdbUrl: String
)

data class ImdbSuggestionResponse(val d: List<ImdbSuggestionItem>?)
data class ImdbSuggestionItem(
    val id: String?,
    val l:  String?,
    val y:  Int?,
    val q:  String?,
    val i:  ImdbImage?
)
data class ImdbImage(
    val imageUrl: String?,
    val height: Int?,
    val width: Int?
)

enum class SourceType {
    SHOW, MOVIE, MANUAL, API, EXCLUSION
}

data class WizdomResult(
    val title: String?,
    val title_en: String?,
    val imdb: String?,
    val type: String?
)

data class WizdomRelease(
    val imdb:         String?,
    val title:        String?,
    val title_en:     String?,
    val year:         Int?,
    val rating:       String?,
    val genres:       String?,
    val poster_small: String?,
    val type:         String?,
    val subs:         List<Any>?
)

data class SubtitleResult(
    val url:       String,
    val imdbId:    String,
    val title:     String,
    val titleHe:   String?,
    val year:      Int?,
    val rating:    String?,
    val genres:    String?,
    val posterUrl: String?,
    val type:      String?,
    val subsCount: Int
)

data class StremioResponse(val metas: List<StremioMeta>?)
data class StremioMeta(
    val id: String,
    val imdb_id: String?,
    val type: String,
    val name: String,
    val releaseInfo: String?
)

data class V1Response(val data: List<V1Item>?, val meta: V1Meta?)
data class V1Item(val t: String, val y: Int?, val d: String?)
data class V1Meta(val total_items: Int)

class DvoraScanner {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val relClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    suspend fun scanSite(baseUrl: String, searchTerm: String, exclusions: List<String> = emptyList()): SearchResult = withContext(Dispatchers.IO) {
        var formattedInput: String
        var cleanBaseUrl: String

        when {
            baseUrl.startsWith("+") -> {
                formattedInput = searchTerm.replace(" ", "+")
                cleanBaseUrl = baseUrl.substring(1)
            }
            baseUrl.startsWith("-") -> {
                formattedInput = searchTerm.replace(" ", "-")
                cleanBaseUrl = baseUrl.substring(1)
            }
            else -> {
                formattedInput = searchTerm
                cleanBaseUrl = baseUrl
            }
        }

        val fullUrl = cleanBaseUrl + formattedInput

        try {
            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", userAgent)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext SearchResult(fullUrl, false, errorMessage = "HTTP ${response.code}")

                val body = response.body?.string() ?: return@withContext SearchResult(fullUrl, false, errorMessage = "Empty body")
                val doc = Jsoup.parse(body, fullUrl)
                val links = doc.select("a[href]")

                val searchWords = searchTerm.split(" ").filter { it.isNotBlank() }
                if (searchWords.isEmpty()) return@withContext SearchResult(fullUrl, false)

                val patternBuilder = StringBuilder()
                searchWords.forEachIndexed { index, word ->
                    if (index > 0) patternBuilder.append("[\\s\\-\\+\\.\\/]+")
                    patternBuilder.append(Pattern.quote(word))
                }
                val searchPattern = Pattern.compile(patternBuilder.toString(), Pattern.CASE_INSENSITIVE)

                val ignoredPatterns = exclusions.ifEmpty {
                    listOf(
                        "addtoany.com", "facebook.com", "twitter.com", "reddit.com",
                        "pinterest.com", "whatsapp.com", "t.me", "mailto:",
                        "/login", "/register", "/signup", "/feed", "#", "/filter", "/search", "/browser", "/?s="
                    )
                }

                val matchedLinks = mutableListOf<String>()
                val skipCounts = mutableMapOf<String, Int>()

                for (linkObj in links) {
                    val href = linkObj.attr("abs:href")
                    if (href.isBlank()) continue

                    val linkLower = href.lowercase()
                    val textLower = linkObj.text().lowercase()

                    var ignored = false
                    for (pattern in ignoredPatterns) {
                        if (linkLower.contains(pattern)) {
                            skipCounts[pattern] = (skipCounts[pattern] ?: 0) + 1
                            ignored = true
                            break
                        }
                    }
                    if (ignored) continue

                    if (linkLower.contains("/search/") || linkLower.contains("search/") || linkLower.contains("/search?")) {
                        skipCounts["search/pagination"] = (skipCounts["search/pagination"] ?: 0) + 1
                        continue
                    }

                    if (searchPattern.matcher(linkLower).find() || searchPattern.matcher(textLower).find()) {
                        if (!matchedLinks.contains(href)) matchedLinks.add(href)
                    }
                }

                val logBuilder = StringBuilder()
                logBuilder.append("Matches found: ${matchedLinks.size}\n")
                if (matchedLinks.isNotEmpty()) {
                    logBuilder.append("Matching links (up to 10):\n")
                    matchedLinks.take(10).forEach { logBuilder.append("- $it\n") }
                }
                logBuilder.append("\nSkip statistics (Blocked Patterns):\n")
                if (skipCounts.isEmpty()) {
                    logBuilder.append("No links were skipped.\n")
                } else {
                    skipCounts.forEach { (pattern, count) ->
                        logBuilder.append("- Blocked '$pattern': $count times\n")
                    }
                }
                val verbose = logBuilder.toString()

                if (matchedLinks.isNotEmpty()) {
                    return@withContext SearchResult(
                        url = fullUrl,
                        found = true,
                        foundDetails = "Matches found: ${matchedLinks.size}",
                        verboseLogs = verbose
                    )
                }

                val pageContent = doc.text().lowercase()
                val noResultsIndicators = listOf(
                    "no result found.", "no result found", "no results found",
                    "no results", "nothing found", "not found", "no matches",
                    "0 results", "could not find", "couldn't find",
                    "search returned no results", "sorry, no results",
                    "no items found", "your search did not match",
                    "did not match any", "no search results"
                )
                val detectedIndicator = noResultsIndicators.find { pageContent.contains(it) }
                return@withContext SearchResult(
                    url = fullUrl,
                    found = false,
                    foundDetails = if (detectedIndicator != null) "Detected: $detectedIndicator" else "No matches found",
                    verboseLogs = verbose
                )
            }
        } catch (e: Exception) {
            SearchResult(fullUrl, false, errorMessage = e.message)
        }
    }

    suspend fun scanSubtitles(searchTerm: String, searchType: SourceType): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val typePath     = if (searchType == SourceType.SHOW) "tv" else "movie"
        val query        = searchTerm.replace(" ", "+")
        val apiSearchUrl = "https://wizdom.xyz/api/search?search=$query&page=0"

        val wizdomMap = mutableMapOf<String, String>()

        try {
            val req = Request.Builder().url(apiSearchUrl).header("User-Agent", userAgent).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (body != null) {
                        val listType = object : TypeToken<List<WizdomResult>>() {}.type
                        val items: List<WizdomResult> = Gson().fromJson(body, listType)
                        items.filter {
                            it.imdb != null && (
                                    it.title_en?.contains(searchTerm, ignoreCase = true) == true ||
                                            it.title?.contains(searchTerm, ignoreCase = true) == true
                                    )
                        }.forEach { match ->
                            wizdomMap[match.imdb!!] = match.title_en ?: match.title ?: "Unknown"
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            searchImdb(searchTerm).forEach { imdbResult ->
                if (!wizdomMap.containsKey(imdbResult.imdbId))
                    wizdomMap[imdbResult.imdbId] = imdbResult.title
            }
        } catch (_: Exception) {}

        if (wizdomMap.isEmpty()) return@withContext emptyList()

        val results = wizdomMap.keys.mapNotNull { imdbId ->
            val relUrl   = "https://wizdom.xyz/api/releases/$imdbId"
            val finalUrl = "https://wizdom.xyz/$typePath/$imdbId"
            try {
                val req = Request.Builder().url(relUrl).header("User-Agent", userAgent).header("Accept", "application/json").build()
                relClient.newCall(req).execute().use { resp ->
                    if (resp.code >= 400) return@mapNotNull null
                    val body = resp.body?.string()?.trim() ?: return@mapNotNull null
                    if (body.isEmpty() || body == "null" || body == "[]" || body == "{}") return@mapNotNull null

                    val release = try { Gson().fromJson(body, WizdomRelease::class.java) } catch (_: Exception) { null }
                    val displayTitle = wizdomMap[imdbId] ?: "Unknown"
                    val title = release?.title_en ?: release?.title ?: displayTitle

                    SubtitleResult(
                        url       = finalUrl,
                        imdbId    = imdbId,
                        title     = title,
                        titleHe   = release?.title?.takeIf { it != title },
                        year      = release?.year,
                        rating    = release?.rating,
                        genres    = release?.genres,
                        posterUrl = release?.poster_small,
                        type      = typePath,
                        subsCount = release?.subs?.size ?: 0
                    )
                }
            } catch (_: Exception) { null }
        }
        return@withContext results
    }

    private fun titleMatches(title: String, searchTerm: String): Boolean {
        val seps = listOf(" ", "-", "+")
        val t = title.lowercase()
        for (qs in seps) {
            val q = searchTerm.lowercase().replace(" ", qs)
            for (ts in seps) {
                val norm = t.replace(ts, qs)
                if (norm.contains(q)) return true
            }
        }
        return false
    }

    suspend fun scanStremio(baseUrl: String, searchTerm: String, searchType: SourceType): List<SearchResult> = withContext(Dispatchers.IO) {
        val mediaType = if (searchType == SourceType.SHOW) "series" else "movie"
        val variants = listOf(" " to "space", "-" to "dash", "+" to "plus")
        val seenNames = mutableSetOf<String>()
        val allMatches = mutableListOf<SearchResult>()

        for ((sep, _) in variants) {
            val query = searchTerm.replace(" ", sep)
            val apiURL = "$baseUrl/catalog/$mediaType/top/search=$query.json"
            try {
                val request = Request.Builder().url(apiURL).header("User-Agent", userAgent).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val stremioResponse = Gson().fromJson(body, StremioResponse::class.java)
                    stremioResponse.metas?.forEach { item ->
                        if (allMatches.size >= 10) return@forEach
                        val key = item.name.lowercase()
                        if (key in seenNames) return@forEach
                        if (titleMatches(item.name, searchTerm)) {
                            seenNames.add(key)
                            val id = item.imdb_id ?: item.id
                            val stremioUrl = "https://web.stremio.com/#/detail/${item.type}/$id/$id"
                            allMatches.add(SearchResult(stremioUrl, true, foundDetails = "Match: ${item.name} (${item.releaseInfo ?: ""})"))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (allMatches.isNotEmpty()) return@withContext allMatches
        val fallbackUrl = "$baseUrl/catalog/$mediaType/top/search=${searchTerm.replace(" ", "+")}.json"
        return@withContext listOf(SearchResult(fallbackUrl, false, foundDetails = "No Stremio matches for '$searchTerm'"))
    }

    /**
     * Scan a V1 JSON API site using full URL templates.
     * Put "DVORA" anywhere in the URL where the search query should go.
     *
     * [apiUrlTemplate]     – e.g. "https://ww1.yesmovies.ag/searching?q=DVORA&limit=40&offset=0"
     * [landingUrlTemplate] – e.g. "https://yesmovies.ag/search/?q=DVORA"  (fully independent)
     *                        Null → reuse the API URL template as the result link.
     * [matchKeys]          – Optional list of JSON key paths (e.g. "data.title") to extract
     *                        candidate titles from the response. When provided, the scanner
     *                        dynamically reads these fields instead of the hardcoded V1 shape.
     */
    suspend fun scanV1(
        apiUrlTemplate:     String,
        searchTerm:         String,
        landingUrlTemplate: String? = null,
        matchKeys:          List<String> = emptyList()
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val hasApiPlaceholder     = apiUrlTemplate.contains("DVORA")
        val hasLandingPlaceholder = landingUrlTemplate?.contains("DVORA") == true
        val useDynamicKeys        = matchKeys.isNotEmpty()

        val separators = listOf("+", "-", " ")
        val seenNames  = mutableSetOf<String>()
        val allMatches = mutableListOf<SearchResult>()

        for (sep in separators) {
            val query = searchTerm.replace(" ", sep)

            // Build the API search URL
            val apiURL = if (hasApiPlaceholder)
                apiUrlTemplate.replace("DVORA", query)
            else
                "$apiUrlTemplate/searching?q=$query&limit=40&offset=0"   // legacy bare-base fallback

            try {
                val request = Request.Builder().url(apiURL).header("User-Agent", userAgent).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use

                    if (useDynamicKeys) {
                        // ── Dynamic mode: extract candidate titles from selected key paths ──
                        val candidates = extractDynamicTitles(body, matchKeys)
                        candidates.forEach { (title, year) ->
                            if (allMatches.size >= 10) return@forEach
                            val key = title.lowercase()
                            if (key in seenNames) return@forEach
                            if (titleMatches(title, searchTerm)) {
                                seenNames.add(key)
                                val finalUrl = when {
                                    hasLandingPlaceholder  -> landingUrlTemplate!!.replace("DVORA", query)
                                    landingUrlTemplate != null -> "$landingUrlTemplate/search/?q=$query"
                                    hasApiPlaceholder      -> apiUrlTemplate.replace("DVORA", query)
                                    else                   -> "$apiUrlTemplate/search/?q=$query"
                                }
                                allMatches.add(SearchResult(finalUrl, true, foundDetails = "Match: $title ($year)"))
                            }
                        }
                    } else {
                        // ── Legacy mode: hardcoded V1 JSON shape ──
                        val v1Response = Gson().fromJson(body, V1Response::class.java)
                        v1Response.data?.forEach { item ->
                            if (allMatches.size >= 10) return@forEach
                            val key = item.t.lowercase()
                            if (key in seenNames) return@forEach
                            if (titleMatches(item.t, searchTerm)) {
                                seenNames.add(key)
                                val finalUrl = when {
                                    hasLandingPlaceholder  -> landingUrlTemplate!!.replace("DVORA", query)
                                    landingUrlTemplate != null -> "$landingUrlTemplate/search/?q=$query"  // legacy
                                    hasApiPlaceholder      -> apiUrlTemplate.replace("DVORA", query)      // reuse api template
                                    else                   -> "$apiUrlTemplate/search/?q=$query"          // legacy
                                }
                                allMatches.add(SearchResult(finalUrl, true, foundDetails = "Match: ${item.t} (${item.y ?: ""})"))
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (allMatches.isNotEmpty()) return@withContext allMatches

        // No matches — point at the landing URL so user can check manually
        val fbQuery = searchTerm.replace(" ", "+")
        val fallbackUrl = when {
            hasLandingPlaceholder  -> landingUrlTemplate!!.replace("DVORA", fbQuery)
            landingUrlTemplate != null -> "$landingUrlTemplate/search/?q=$fbQuery"
            hasApiPlaceholder      -> apiUrlTemplate.replace("DVORA", fbQuery)
            else                   -> "$apiUrlTemplate/searching?q=$fbQuery&limit=40&offset=0"
        }
        return@withContext listOf(SearchResult(fallbackUrl, false, foundDetails = "No v1 matches for '$searchTerm'"))
    }

    /**
     * Extract candidate (title, year) pairs from a JSON response using the given key paths.
     * Key paths use dot notation, e.g. "data.title", "data[0].name", "results[0].title".
     * For array paths, all elements are scanned. Year is best-effort from a sibling "year"/"y" key.
     */
    private fun extractDynamicTitles(body: String, matchKeys: List<String>): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        try {
            val root = com.google.gson.JsonParser.parseString(body) ?: return emptyList()
            matchKeys.forEach { keyPath ->
                // Normalize any explicit array index (e.g. "data[0].title") into "data[].title"
                // so we scan EVERY item in the array, not just the first one. The picker only
                // shows the first item's structure, but at scan time we want all results.
                val normalizedPath = keyPath.replace(Regex("\\[\\d+]"), "[]")
                val titleVals = resolveJsonPath(root, normalizedPath)
                titleVals.forEach { titleEl ->
                    val title = titleEl.asString ?: return@forEach
                    // Try to find a sibling year field
                    val year = guessYearForKey(root, normalizedPath, titleEl)
                    results.add(title to (year ?: ""))
                }
            }
        } catch (_: Exception) {}
        return results
    }

    /**
     * Resolve a dotted/bracket JSON path against a root element.
     * Returns all matching JsonElements (arrays are expanded).
     */
    private fun resolveJsonPath(root: com.google.gson.JsonElement, path: String): List<com.google.gson.JsonElement> {
        val tokens = tokenizeJsonPath(path)
        var current = listOf(root)
        for (token in tokens) {
            val next = mutableListOf<com.google.gson.JsonElement>()
            when (token) {
                is PathToken.Key -> {
                    current.forEach { el ->
                        if (el.isJsonObject && el.asJsonObject.has(token.name)) {
                            next.add(el.asJsonObject.get(token.name)!!)
                        }
                    }
                }
                is PathToken.Index -> {
                    current.forEach { el ->
                        if (el.isJsonArray && token.idx < el.asJsonArray.size()) {
                            next.add(el.asJsonArray.get(token.idx))
                        }
                    }
                }
                is PathToken.AllItems -> {
                    current.forEach { el ->
                        if (el.isJsonArray) {
                            el.asJsonArray.forEach { next.add(it) }
                        }
                    }
                }
            }
            current = next
        }
        // Expand any trailing arrays into their string/primitive leaves
        val leaves = mutableListOf<com.google.gson.JsonElement>()
        current.forEach { collectLeafStrings(it, leaves) }
        return leaves
    }

    private fun collectLeafStrings(el: com.google.gson.JsonElement, out: MutableList<com.google.gson.JsonElement>) {
        if (el.isJsonPrimitive) {
            out.add(el)
        } else if (el.isJsonArray) {
            el.asJsonArray.forEach { collectLeafStrings(it, out) }
        }
    }

    private sealed class PathToken {
        data class Key(val name: String) : PathToken()
        data class Index(val idx: Int) : PathToken()
        object AllItems : PathToken()
    }

    private fun tokenizeJsonPath(path: String): List<PathToken> {
        val tokens = mutableListOf<PathToken>()
        // Split on dots, then handle bracket notation within each segment
        path.split(".").forEach { segment ->
            if (segment.isEmpty()) return@forEach
            // e.g. "results[0]" or "results[]"
            val bracketIdx = segment.indexOf("[")
            if (bracketIdx == -1) {
                tokens.add(PathToken.Key(segment))
            } else {
                val keyName = segment.substring(0, bracketIdx)
                if (keyName.isNotEmpty()) tokens.add(PathToken.Key(keyName))
                // Parse all bracket groups in this segment
                var rest = segment.substring(bracketIdx)
                while (rest.startsWith("[")) {
                    val close = rest.indexOf("]")
                    if (close == -1) break
                    val inside = rest.substring(1, close)
                    rest = if (rest.length > close + 1) rest.substring(close + 1) else ""
                    if (inside.isEmpty() || inside == "*") {
                        tokens.add(PathToken.AllItems)
                    } else {
                        tokens.add(PathToken.Index(inside.toIntOrNull() ?: 0))
                    }
                }
            }
        }
        return tokens
    }

    /**
     * Best-effort: look for a sibling "year" or "y" field next to the title key.
     */
    private fun guessYearForKey(root: com.google.gson.JsonElement, titlePath: String, titleEl: com.google.gson.JsonElement): String? {
        // Replace the last key segment with "year" / "y" and try to resolve
        val lastDot = titlePath.lastIndexOf(".")
        if (lastDot == -1) return null
        val parent = titlePath.substring(0, lastDot)
        val yearCandidates = listOf("year", "y", "releaseYear", "release_date", "releaseInfo")
        for (yKey in yearCandidates) {
            val yearVals = resolveJsonPath(root, "$parent.$yKey")
            if (yearVals.isNotEmpty()) {
                val yv = yearVals[0]
                if (yv.isJsonPrimitive) {
                    val s = yv.asString ?: continue
                    // Extract a 4-digit year if present
                    val m = Pattern.compile("\\d{4}").matcher(s)
                    if (m.find()) return m.group()
                    return s
                }
            }
        }
        return null
    }

    suspend fun searchImdb(searchTerm: String): List<ImdbResult> = withContext(Dispatchers.IO) {
        val query     = searchTerm.trim().lowercase().replace(" ", "_")
        val firstChar = query.firstOrNull { it.isLetter() } ?: 'a'
        val url       = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$query.json"

        return@withContext try {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                val parsed = Gson().fromJson(body, ImdbSuggestionResponse::class.java)
                val items  = parsed.d ?: return@use emptyList()

                items.filter { it.id?.startsWith("tt") == true }
                    .take(10)
                    .mapNotNull { item ->
                        val imdbId = item.id ?: return@mapNotNull null
                        val title  = item.l  ?: return@mapNotNull null
                        ImdbResult(
                            imdbId    = imdbId,
                            title     = title,
                            year      = item.y?.toString(),
                            mediaType = item.q,
                            posterUrl = item.i?.imageUrl,
                            imdbUrl   = "https://www.imdb.com/title/$imdbId/"
                        )
                    }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun getManualCheck(baseUrl: String): String {
        return if (baseUrl.startsWith("+") || baseUrl.startsWith("-")) baseUrl.substring(1) else baseUrl
    }
}