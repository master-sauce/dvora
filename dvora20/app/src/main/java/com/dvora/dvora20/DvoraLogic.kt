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

// IMDb suggestion API models
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
    SHOW, MOVIE, MANUAL, API
}

data class WizdomResult(
    val title: String?,
    val title_en: String?,
    val imdb: String?,
    val type: String?
)

// Full release detail returned by /api/releases/{imdbId}
data class WizdomRelease(
    val imdb:         String?,
    val title:        String?,   // Hebrew title
    val title_en:     String?,   // English title
    val year:         Int?,
    val rating:       String?,
    val genres:       String?,
    val poster_small: String?,
    val type:         String?,   // "movie" or "tv"
    val subs:         List<Any>? // non-empty = subtitles exist
)

// Rich subtitle result — built from /api/releases/{imdbId} data
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

// Stremio API Models
data class StremioResponse(val metas: List<StremioMeta>?)
data class StremioMeta(
    val id: String,
    val imdb_id: String?,
    val type: String,
    val name: String,
    val releaseInfo: String?
)

// v1/Movie API Models
data class V1Response(val data: List<V1Item>?, val meta: V1Meta?)
data class V1Item(val t: String, val y: Int?, val d: String?)
data class V1Meta(val total_items: Int)

class DvoraScanner {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // No-redirect client for /api/releases/ — Wizdom returns 3xx with a JSON body for TV.
    // Following the redirect loses the response body, so we must NOT follow it.
    private val relClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    suspend fun scanSite(baseUrl: String, searchTerm: String): SearchResult = withContext(Dispatchers.IO) {
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

                val ignoredPatterns = listOf(
                    "addtoany.com", "facebook.com", "twitter.com", "reddit.com",
                    "pinterest.com", "whatsapp.com", "t.me", "mailto:",
                    "/login", "/register", "/signup", "/feed", "#", "/filter", "/search", "/browser", "/?s="
                )

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

    // ── Subtitle search ────────────────────────────────────────────────────────
    // Step 1 — collect candidate IMDb IDs from two sources:
    //   A. Wizdom's own search API, filtered to title-relevant matches
    //   B. IMDb suggestion API (adds IDs not already found via Wizdom)
    // Step 2 — verify each candidate via /api/releases/{imdbId}
    //   • 4xx / 5xx        → skip (no data / server error)
    //   • empty / null subs → skip (no Hebrew subtitles)
    //   • non-empty subs   → confirmed; build SubtitleResult from API data
    // The media type (movie/tv) is taken from the release, not from user selection.
    suspend fun scanSubtitles(searchTerm: String, searchType: SourceType): List<SubtitleResult> = withContext(Dispatchers.IO) {
        // URL path is driven by the user's toggle, not the API's type field
        val typePath     = if (searchType == SourceType.SHOW) "tv" else "movie"
        val query        = searchTerm.replace(" ", "+")
        val apiSearchUrl = "https://wizdom.xyz/api/search?search=$query&page=0"

        val wizdomMap = mutableMapOf<String, String>() // imdbId → display title

        // ── Source A: Wizdom search API ───────────────────────────────────────────
        try {
            val req = Request.Builder()
                .url(apiSearchUrl)
                .header("User-Agent", userAgent)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (body != null) {
                        val listType = object : TypeToken<List<WizdomResult>>() {}.type
                        val items: List<WizdomResult> = Gson().fromJson(body, listType)
                        items
                            .filter {
                                it.imdb != null && (
                                        it.title_en?.contains(searchTerm, ignoreCase = true) == true ||
                                                it.title?.contains(searchTerm, ignoreCase = true) == true
                                        )
                            }
                            .forEach { match ->
                                wizdomMap[match.imdb!!] = match.title_en ?: match.title ?: "Unknown"
                            }
                    }
                }
            }
        } catch (_: Exception) {}

        // ── Source B: IMDb suggestion API ─────────────────────────────────────────
        try {
            searchImdb(searchTerm).forEach { imdbResult ->
                if (!wizdomMap.containsKey(imdbResult.imdbId))
                    wizdomMap[imdbResult.imdbId] = imdbResult.title
            }
        } catch (_: Exception) {}

        if (wizdomMap.isEmpty()) return@withContext emptyList()

        // ── Verify each candidate via /api/releases/{imdbId} ─────────────────────
        // For TV shows the response is a seasons/episodes structure — NOT a flat "subs" array.
        // So we only check that the endpoint returns a non-empty, non-error body.
        // The URL path (/tv/ or /movie/) is always taken from the user toggle.
        val results = wizdomMap.keys.mapNotNull { imdbId ->
            val relUrl   = "https://wizdom.xyz/api/releases/$imdbId"
            val finalUrl = "https://wizdom.xyz/$typePath/$imdbId"
            try {
                val req = Request.Builder()
                    .url(relUrl)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .build()
                relClient.newCall(req).execute().use { resp ->
                    // Accept 2xx and 3xx (Wizdom returns 3xx with body for TV), skip 4xx/5xx
                    if (resp.code >= 400) return@mapNotNull null

                    val body = resp.body?.string()?.trim() ?: return@mapNotNull null
                    // Skip genuinely empty responses
                    if (body.isEmpty() || body == "null" || body == "[]" || body == "{}") return@mapNotNull null

                    // Parse for rich metadata — best effort, never blocks the result
                    val release = try { Gson().fromJson(body, WizdomRelease::class.java) }
                    catch (_: Exception) { null }

                    val displayTitle = wizdomMap[imdbId] ?: "Unknown"
                    val title        = release?.title_en ?: release?.title ?: displayTitle

                    SubtitleResult(
                        url       = finalUrl,
                        imdbId    = imdbId,
                        title     = title,
                        titleHe   = release?.title?.takeIf { it != title },
                        year      = release?.year,
                        rating    = release?.rating,
                        genres    = release?.genres,
                        posterUrl = release?.poster_small,
                        type      = typePath,           // from user toggle
                        subsCount = release?.subs?.size ?: 0
                    )
                }
            } catch (_: Exception) { null }
        }

        return@withContext results
    }

    // Checks whether a result title contains the search term,
    // trying space, dash, and plus as separators on both sides.
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

        for ((sep, label) in variants) {
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
                            allMatches.add(SearchResult(stremioUrl, true, foundDetails = "[$label] Match: ${item.name} (${item.releaseInfo ?: ""})"))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (allMatches.isNotEmpty()) return@withContext allMatches
        val fallbackUrl = "$baseUrl/catalog/$mediaType/top/search=${searchTerm.replace(" ", "+")}.json"
        return@withContext listOf(SearchResult(fallbackUrl, false, foundDetails = "No Stremio matches for '$searchTerm'"))
    }

    suspend fun scanV1(baseUrl: String, searchTerm: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val variants = listOf(" " to "space", "-" to "dash", "+" to "plus")

        val seenNames = mutableSetOf<String>()
        val allMatches = mutableListOf<SearchResult>()

        for ((sep, label) in variants) {
            val query = searchTerm.replace(" ", sep)
            val apiURL = "$baseUrl/searching?q=$query&limit=40&offset=0"
            try {
                val request = Request.Builder().url(apiURL).header("User-Agent", userAgent).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val v1Response = Gson().fromJson(body, V1Response::class.java)

                    v1Response.data?.forEach { item ->
                        if (allMatches.size >= 10) return@forEach
                        val key = item.t.lowercase()
                        if (key in seenNames) return@forEach
                        if (titleMatches(item.t, searchTerm)) {
                            seenNames.add(key)
                            val finalUrl = "$baseUrl/search/?q=$query"
                            allMatches.add(SearchResult(finalUrl, true, foundDetails = "[$label] Match: ${item.t} (${item.y ?: ""})"))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (allMatches.isNotEmpty()) return@withContext allMatches
        val fallbackUrl = "$baseUrl/searching?q=${searchTerm.replace(" ", "+")}&limit=40&offset=0"
        return@withContext listOf(SearchResult(fallbackUrl, false, foundDetails = "No v1 matches for '$searchTerm'"))
    }

    suspend fun searchImdb(searchTerm: String): List<ImdbResult> = withContext(Dispatchers.IO) {
        val query     = searchTerm.trim().lowercase().replace(" ", "_")
        val firstChar = query.firstOrNull { it.isLetter() } ?: 'a'
        val url       = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$query.json"

        return@withContext try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .build()

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
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getManualCheck(baseUrl: String, searchTerm: String): String {
        val formattedInput = when {
            baseUrl.startsWith("+") -> searchTerm.replace(" ", "+")
            baseUrl.startsWith("-") -> searchTerm.replace(" ", "-")
            else -> searchTerm
        }
        val cleanBaseUrl = if (baseUrl.startsWith("+") || baseUrl.startsWith("-")) baseUrl.substring(1) else baseUrl
        return cleanBaseUrl + formattedInput
    }
}