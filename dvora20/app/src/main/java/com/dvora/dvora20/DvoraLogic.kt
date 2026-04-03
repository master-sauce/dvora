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
    val verboseLogs: String? = null // For detailed logs only
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
    val id: String?,   // "tt1234567" for titles, "nm..." for people
    val l:  String?,   // title / name
    val y:  Int?,      // year
    val q:  String?,   // "TV series", "TV mini-series", "video game", etc. null = movie
    val i:  ImdbImage? // poster
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
                    "/login", "/register", "/signup", "/feed", "#"
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
                        if (!matchedLinks.contains(href)) {
                            matchedLinks.add(href)
                        }
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

    suspend fun scanSubtitles(searchTerm: String, searchType: SourceType): List<SearchResult> = withContext(Dispatchers.IO) {
        val typePath     = if (searchType == SourceType.SHOW) "tv" else "movie"
        val query        = searchTerm.replace(" ", "+")
        val apiSearchUrl = "https://wizdom.xyz/api/search?search=$query&page=0"

        // Collect IMDb IDs from two sources in parallel:
        // 1. Wizdom own search API (by title name)
        // 2. IMDb suggestion API (by name -> imdb IDs)
        // Then hit wizdom.xyz/<type>/<imdbId> for each unique ID found.

        val wizdomIds = mutableMapOf<String, String>() // imdbId -> display title

        // Source 1: Wizdom search
        try {
            val request = Request.Builder()
                .url(apiSearchUrl)
                .header("User-Agent", userAgent)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val listType = object : TypeToken<List<WizdomResult>>() {}.type
                        val wizdomResults: List<WizdomResult> = Gson().fromJson(body, listType)
                        wizdomResults
                            .filter {
                                it.imdb != null && (
                                        it.title_en?.contains(searchTerm, ignoreCase = true) == true ||
                                                it.title?.contains(searchTerm, ignoreCase = true) == true
                                        )
                            }
                            .forEach { match ->
                                val displayTitle = match.title_en ?: match.title ?: "Unknown"
                                wizdomIds[match.imdb!!] = displayTitle
                            }
                    }
                }
            }
        } catch (_: Exception) {}

        // Source 2: IMDb suggestion API -> extract IMDb IDs -> look up on Wizdom
        try {
            val imdbResults = searchImdb(searchTerm)
            imdbResults.forEach { imdbResult ->
                // Only add if not already found via Wizdom search
                if (!wizdomIds.containsKey(imdbResult.imdbId)) {
                    wizdomIds[imdbResult.imdbId] = imdbResult.title
                }
            }
        } catch (_: Exception) {}

        if (wizdomIds.isEmpty()) {
            return@withContext listOf(
                SearchResult(apiSearchUrl, false, foundDetails = "No matching subtitles found on Wizdom.")
            )
        }

        // Build one result per unique IMDb ID
        return@withContext wizdomIds.map { (imdbId, displayTitle) ->
            val finalUrl = "https://wizdom.xyz/$typePath/$imdbId"
            SearchResult(finalUrl, true, foundDetails = "Match: $displayTitle")
        }
    }

    suspend fun scanStremio(baseUrl: String, searchTerm: String, searchType: SourceType): List<SearchResult> = withContext(Dispatchers.IO) {
        val mediaType = if (searchType == SourceType.SHOW) "series" else "movie"
        val query = searchTerm.replace(" ", "+")
        val apiURL = "$baseUrl/catalog/$mediaType/top/search=$query.json"

        try {
            val request = Request.Builder().url(apiURL).header("User-Agent", userAgent).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext listOf(SearchResult(apiURL, false, errorMessage = "HTTP ${response.code}"))
                val body = response.body?.string() ?: return@withContext listOf(SearchResult(apiURL, false, errorMessage = "Empty body"))
                val stremioResponse = Gson().fromJson(body, StremioResponse::class.java)

                val matches = stremioResponse.metas?.filter { it.name.contains(searchTerm, ignoreCase = true) } ?: emptyList()
                if (matches.isEmpty()) return@withContext listOf(SearchResult(apiURL, false, foundDetails = "No Stremio matches for '$searchTerm'"))

                return@withContext matches.take(10).map { item ->
                    val id = item.imdb_id ?: item.id
                    val stremioUrl = "https://web.stremio.com/#/detail/${item.type}/$id/$id"
                    SearchResult(stremioUrl, true, foundDetails = "Match: ${item.name} (${item.releaseInfo ?: ""})")
                }
            }
        } catch (e: Exception) {
            listOf(SearchResult(apiURL, false, errorMessage = e.message))
        }
    }

    suspend fun scanV1(baseUrl: String, searchTerm: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val query = searchTerm.replace(" ", "+")
        val apiURL = "$baseUrl/searching?q=$query&limit=40&offset=0"

        try {
            val request = Request.Builder().url(apiURL).header("User-Agent", userAgent).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext listOf(SearchResult(apiURL, false, errorMessage = "HTTP ${response.code}"))
                val body = response.body?.string() ?: return@withContext listOf(SearchResult(apiURL, false, errorMessage = "Empty body"))
                val v1Response = Gson().fromJson(body, V1Response::class.java)

                val matches = v1Response.data?.filter { it.t.contains(searchTerm, ignoreCase = true) } ?: emptyList()
                if (matches.isEmpty()) return@withContext listOf(SearchResult(apiURL, false, foundDetails = "No v1 matches for '$searchTerm'"))

                return@withContext matches.map { item ->
                    val finalUrl = "$baseUrl/search/?q=$query"
                    SearchResult(finalUrl, true, foundDetails = "Match: ${item.t} (${item.y ?: ""})")
                }
            }
        } catch (e: Exception) {
            listOf(SearchResult(apiURL, false, errorMessage = e.message))
        }
    }


    // IMDb search
    // Uses IMDb's suggestion/autocomplete API - returns real JSON, no JS rendering needed,
    // no API key required. Same pattern as Go version: fetch JSON, parse, filter, return.
    // Endpoint: https://v3.sg.media-imdb.com/suggestion/x/<query>.json
    // Rating is taken from the suggestion API's own 's' field when present,
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

                // Filter to titles only (tt prefix), skip people (nm prefix)
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