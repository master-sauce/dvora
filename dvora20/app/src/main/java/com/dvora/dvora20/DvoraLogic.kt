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
    val foundDetails: String? = null // To store what was detected
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
                val doc = Jsoup.parse(body)
                
                val links = doc.select("[href]").map { it.attr("href") }
                
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
                    "/login", "/register", "/signup", "/feed"
                )

                var matchedLink: String? = null
                for (link in links) {
                    val linkLower = link.lowercase()
                    if (ignoredPatterns.any { linkLower.contains(it) }) continue
                    
                    // New check: skip links that are search results themselves or pagination
                    if (linkLower.startsWith("/search/") || linkLower.startsWith("search/") || linkLower.startsWith("/search?")) continue

                    if (searchPattern.matcher(linkLower).find()) {
                        matchedLink = link
                        break
                    }
                }

                if (matchedLink != null) {
                    return@withContext SearchResult(fullUrl, true, foundDetails = "Matched link: $matchedLink")
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
                if (detectedIndicator != null) {
                    return@withContext SearchResult(fullUrl, false, foundDetails = "Detected 'No Results' text: '$detectedIndicator'")
                }

                return@withContext SearchResult(fullUrl, false, foundDetails = "No matching links and no specific failure indicators found.")
            }
        } catch (e: Exception) {
            SearchResult(fullUrl, false, errorMessage = e.message)
        }
    }

    suspend fun scanSubtitles(searchTerm: String, searchType: SourceType): List<SearchResult> = withContext(Dispatchers.IO) {
        val query = searchTerm.replace(" ", "+")
        val apiSearchUrl = "https://wizdom.xyz/api/search?search=$query&page=0"
        
        try {
            val request = Request.Builder()
                .url(apiSearchUrl)
                .header("User-Agent", userAgent)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext listOf(SearchResult(apiSearchUrl, false, errorMessage = "HTTP ${response.code}"))
                
                val body = response.body?.string() ?: return@withContext listOf(SearchResult(apiSearchUrl, false, errorMessage = "Empty body"))
                val listType = object : TypeToken<List<WizdomResult>>() {}.type
                val wizdomResults: List<WizdomResult> = Gson().fromJson(body, listType)

                val matches = wizdomResults.filter { 
                    it.title_en?.contains(searchTerm, ignoreCase = true) == true || 
                    it.title?.contains(searchTerm, ignoreCase = true) == true
                }
                
                if (matches.isNotEmpty()) {
                    return@withContext matches.filter { it.imdb != null }.map { match ->
                        val typePath = if (searchType == SourceType.SHOW) "tv" else "movie"
                        val finalUrl = "https://wizdom.xyz/$typePath/${match.imdb}"
                        val displayTitle = match.title_en ?: match.title ?: "Unknown"
                        SearchResult(finalUrl, true, foundDetails = "Match: $displayTitle")
                    }
                }
                
                return@withContext listOf(SearchResult(apiSearchUrl, false, foundDetails = "No matching subtitles found on Wizdom."))
            }
        } catch (e: Exception) {
            listOf(SearchResult(apiSearchUrl, false, errorMessage = e.message) )
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
