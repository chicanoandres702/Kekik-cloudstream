package com.demonstratorz

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor // Keep this
import com.lagradost.cloudstream3.utils.Qualities // For quality hints

// Removed unused Rhino imports for now, add back if needed for Attempt 4
// import org.mozilla.javascript.Context
// import org.mozilla.javascript.ScriptableObject
// import org.mozilla.javascript.BaseFunction
// import org.mozilla.javascript.Scriptable
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class KissasianProvider : MainAPI() {
    override var mainUrl = "https://kissasian.com.lv" // Ensure this is the current working domain
    override var name = "Kissasian"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
        // TvType.KShow // Uncomment if KShows are reliably found
    )

    override val mainPage = mainPageOf(
        // Removed trailing slashes as they might cause issues depending on server config
        "$mainUrl" to "Trending", // Often the homepage itself is trending
        "$mainUrl/most-popular-drama" to "Popular Drama",
        "$mainUrl/country/south-korea" to "South Korea",
        "$mainUrl/country/thailand" to "Thailand",
        "$mainUrl/movie" to "Movies" // Added a movie category if it exists
    )

    // Helper to extract from common list item structure
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a.img") ?: this.selectFirst("a") ?: return null
        val title = linkElement.attr("title").ifBlank { linkElement.text() }.trim()
        val href = linkElement.attr("href")
        val posterUrl = this.selectFirst("img")?.attr("data-original")?.ifBlank { this.selectFirst("img")?.attr("src") }

        if (href.isBlank() || title.isBlank()) return null

        // Basic type detection based on URL structure (might need refinement)
        val tvType = when {
            href.contains("/movie/", ignoreCase = true) -> TvType.Movie
            href.contains("/drama/", ignoreCase = true) -> TvType.AsianDrama
            else -> TvType.TvSeries // Default assumption
        }

        return newAnimeSearchResponse(title, fixUrl(href), tvType) {
            this.posterUrl = posterUrl?.let { fixUrl(it) }
        }
    }


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Kissasian usually doesn't have pagination on these main sections
        if (page > 1) return HomePageResponse(emptyList())

        val url = request.data
        Log.i("Kissasian", "Getting main page: $url, page: $page")
        return try {
            val document = app.get(url).document
            Log.i("Kissasian", "Main page document loaded successfully for ${request.name}.")

            // Try different selectors based on potential layouts
            val items = document.select("ul.listing > li, div.tab-container ul > li, div.items > ul > li").mapNotNull {
                it.toSearchResult()
            }

            Log.i("Kissasian", "Found ${items.size} items for ${request.name}")
            newHomePageResponse(request.name, items, false) // hasNext = false assumed

        } catch (e: Exception) {
            Log.e("Kissasian", "Error getting main page: $url - ${e.message}", e)
            HomePageResponse(emptyList()) // Return empty on error
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Use the search endpoint Kissasian actually uses (check network tab in browser)
        // It might be /Search/SearchSuggest or similar AJAX, or a standard query param
        val searchUrl = "$mainUrl/Search/SearchSuggest" // Common pattern, adjust if needed
        val params = mapOf("type" to "drama", "keyword" to query) // Adjust params as needed

        Log.i("Kissasian", "Searching for: $query, URL: $searchUrl, Params: $params")

        return try {
            // Perform POST request if it's an AJAX endpoint
             val response = app.post(searchUrl, data = params, referer = "$mainUrl/").document
            // If it's a GET request:
            // val response = app.get("$mainUrl/?s=$query", referer = "$mainUrl/").document

            Log.i("Kissasian", "Search document loaded successfully.")
            // Adjust selector based on actual search results page structure
            response.select("ul.listing > li, div.items > ul > li, a.bigChar").mapNotNull { element ->
                 // Handle different result structures if necessary (e.g., AJAX might return JSON or simple <a> tags)
                 if (element.tagName() == "a") {
                    val title = element.text()
                    val href = element.attr("href")
                    if (href.isNotBlank() && title.isNotBlank()) {
                        newAnimeSearchResponse(title, fixUrl(href), TvType.TvSeries) // Assume TvSeries, refine if possible
                    } else null
                 } else {
                    element.toSearchResult()
                 }
            }
        } catch (e: Exception) {
            Log.e("Kissasian", "Error during search for: $query - ${e.message}", e)
            emptyList()
        }
    }


    override suspend fun load(url: String): LoadResponse? {
        Log.i("Kissasian", "Loading details page: $url")
        return try {
            val document = app.get(url).document
            Log.i("Kissasian", "Details page document loaded successfully.")

            // Use more robust selectors and handle missing elements
            val title = document.selectFirst("div.details h1, div.details h2")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: return null // Title is essential

            val posterUrl = document.selectFirst("div.img > img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")

            // Combine multiple potential description paragraphs
            val description = document.select("div.info p, div.summary p, .block-watch p").joinToString("\n\n") { it.text() }.cleanUpDescription()

            Log.i("Kissasian", "Details: Title=$title")

            // Episode list selector might change
            val episodeElements = document.select(".all-episode li, #list_episodes li, ul.list_episode > li")

            val episodes = episodeElements.mapNotNull { episodeElement ->
                try {
                    val aTag = episodeElement.selectFirst("a") ?: return@mapNotNull null
                    val episodeUrl = aTag.attr("href")
                    // Try extracting number from title/text first, then fallback to regex on URL if needed
                    val episodeTitle = episodeElement.selectFirst("h3.title")?.text()
                        ?: aTag.attr("title").ifBlank { aTag.text() }

                    val episodeNum = episodeTitle.extractEpisodeNumber()
                        ?: episodeUrl.extractEpisodeNumberFromUrl() // Fallback

                    Log.d("Kissasian", "Found episode: URL=$episodeUrl, Title='$episodeTitle', Number=$episodeNum")

                    if (episodeUrl.isBlank() || episodeNum == null) return@mapNotNull null

                    Episode(
                        data = fixUrl(episodeUrl),
                        name = episodeTitle.trim().ifBlank { "Episode $episodeNum" }, // Provide a default name
                        episode = episodeNum
                    )
                } catch (e: Exception) {
                    Log.e("Kissasian", "Error processing episode element: ${episodeElement.html()} - ${e.message}")
                    null
                }
            }.distinctBy { it.episode }.sortedBy { it.episode } // Ensure uniqueness and order


            // Determine type based on URL or presence of episodes
            val tvType = if (episodes.size > 1 || url.contains("/drama/", true)) {
                 TvType.TvSeries // Or TvType.AsianDrama
            } else if (url.contains("/movie/", true)) {
                TvType.Movie
            } else {
                // Default based on episodes: if only 1 "episode", might be a movie
                if (episodes.size == 1) TvType.Movie else TvType.TvSeries
            }

            Log.i("Kissasian", "Loaded ${episodes.size} episodes for $title")

            when (tvType) {
                TvType.Movie -> newMovieLoadResponse(
                    title,
                    url,
                    tvType,
                    episodes.firstOrNull()?.data ?: return null // Movie needs the single episode data URL
                ) {
                    this.posterUrl = posterUrl?.let { fixUrl(it) }
                    this.plot = description
                }
                else -> newTvSeriesLoadResponse(
                    title,
                    url,
                    tvType,
                    episodes
                ) {
                    this.posterUrl = posterUrl?.let { fixUrl(it) }
                    this.plot = description
                }
            }

        } catch (e: Exception) {
            Log.e("Kissasian", "Error loading details page: $url - ${e.message}", e)
            null
        }
    }

    // Shared extractor loading logic
    private suspend fun invokeLoadExtractor(
        url: String,
        referer: String?, // Allow passing referer
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("Kissasian", "Loading extractor for URL: $url with referer: $referer")
        return loadExtractor(url, referer, subtitleCallback, callback) // Use the variant accepting referer
    }

    override suspend fun loadLinks(
        data: String, // Episode URL
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i("Kissasian", "Loading links for episode: $data")
        var foundLinks = false

        try {
            val episodeDocument = app.get(data, referer = "$mainUrl/").document // Use base URL as referer for episode page

            // --- Strategy 1: Find the primary iframe ---
            // Prioritize known good iframe IDs/selectors if any, fallback to generic
            val iframe = episodeDocument.selectFirst("#player_container iframe")
                ?: episodeDocument.selectFirst("#block-tab-video iframe")
                ?: episodeDocument.selectFirst("div.watch_container iframe")
                ?: episodeDocument.selectFirst("div.play-video iframe")
                ?: episodeDocument.selectFirst("iframe[src*='player']") // More generic

            if (iframe == null) {
                Log.w("Kissasian", "No video iframe found on episode page: $data")
                // Maybe try finding links directly on the episode page? (Less common)
                // return false // Or continue to other strategies if any
            } else {
                val iframeUrl = fixUrl(iframe.attr("src"))
                Log.i("Kissasian", "Found iframe URL: $iframeUrl")
                if (iframeUrl.isBlank()) {
                    Log.w("Kissasian", "Iframe URL is blank for episode: $data")
                    // return false // Or continue
                } else {
                    // Fetch iframe content, using the episode page URL as referer
                    val iframeReferer = data
                    val iframeDocument = app.get(iframeUrl, referer = iframeReferer).document
                    Log.d("Kissasian", "Iframe content loaded from: $iframeUrl")
                    // Log HTML for debugging structure: Log.v("Kissasian", "Iframe HTML: ${iframeDocument.html()}")

                    // --- Strategy 2: Look for Server List within iframe ---
                    // Common pattern: ul > li with data-video attributes
                    val serverListItems = iframeDocument.select("ul.list-server-items > li[data-video], ul.servers-list > li[data-embed]")
                    Log.d("Kissasian", "Found ${serverListItems.size} server list items in iframe")

                    if (serverListItems.isNotEmpty()) {
                        serverListItems.forEach { item ->
                            val serverUrl = fixUrl(item.attr("data-video").ifBlank { item.attr("data-embed") })
                            val serverName = item.text().trim().ifBlank { null } // Optional server name
                            if (serverUrl.isNotBlank()) {
                                Log.i("Kissasian", "Found server: $serverName -> $serverUrl")
                                if (invokeLoadExtractor(serverUrl, iframeUrl, subtitleCallback, callback)) {
                                    foundLinks = true
                                    // You might want to `return true` here if you only want the first working server
                                    // Or continue to gather all servers by just setting foundLinks = true
                                }
                            }
                        }
                    } else {
                         Log.w("Kissasian", "No server list found in iframe.")
                    }

                     // --- Strategy 3: Try loadExtractor on the iframe URL itself ---
                     // Sometimes the iframe URL itself is directly handled by an extractor (e.g., Vidstream embed URL)
                     if (!foundLinks) {
                         Log.i("Kissasian", "Trying loadExtractor directly on iframe URL: $iframeUrl")
                         if (invokeLoadExtractor(iframeUrl, iframeReferer, subtitleCallback, callback)) {
                             foundLinks = true
                         }
                     }

                    // --- Strategy 4: Look for M3U8/MP4 in script tags within iframe ---
                    if (!foundLinks) {
                        Log.i("Kissasian", "Looking for direct M3U8/MP4 in script tags...")
                        iframeDocument.select("script").forEach { script ->
                            val scriptData = script.data()
                            if (scriptData.contains("sources:") || scriptData.contains(".m3u8") || scriptData.contains(".mp4")) {
                                Log.d("Kissasian", "Found potential script: ${scriptData.take(100)}...") // Log snippet
                                // Example M3U8 extraction (adapt regex as needed)
                                M3u8Helper.findM3u8Uris(scriptData).forEach { uri ->
                                    Log.i("Kissasian", "Found potential M3U8 URI in script: $uri")
                                    // Basic quality estimation
                                    val quality = Qualities.Unknown.value // Placeholder
                                    M3u8Helper.generateM3u8(
                                        name, // Source name
                                        uri, // M3U8 URL
                                        iframeUrl, // Referer
                                        quality = quality
                                    ).forEach { link ->
                                        callback(link)
                                        foundLinks = true
                                    }
                                }
                                // Add similar logic for MP4 if needed
                                // val mp4Regex = Regex("""["'](http[^"']+\.mp4)["']""") ...
                            }
                        }
                    }

                     // --- Strategy 5: Fallback to Rhino JS Execution (Original approach, less reliable) ---
                    /* // Uncomment this block if other methods fail and you want to try JS execution
                    if (!foundLinks) {
                        Log.w("Kissasian", "Falling back to JavaScript execution attempt...")
                        // Implement the Rhino execution logic here, similar to your original code
                        // But fetch the script from iframeDocument, not a hardcoded domain
                        val scriptCode = iframeDocument.select("script[data-cfasync=false]").joinToString("\n") { it.data() }
                        val cryptoValue = iframeDocument.select("script[data-name=crypto]").firstOrNull()?.attr("data-value") ?: ""

                        if (scriptCode.isNotBlank() && cryptoValue.isNotBlank()) {
                            // ... (Setup Rhino Context, Scope, evaluateString) ...
                            // Try to get a calculated URL or trigger loadExtractor from within JS eval if possible
                            Log.w("Kissasian", "Rhino execution needs careful implementation and debugging.")
                        } else {
                           Log.w("Kissasian", "Required script or crypto value not found for JS execution.")
                        }
                    }
                    */

                } // End if iframeUrl is blank
            } // End if iframe is null

        } catch (e: Exception) {
            Log.e("Kissasian", "Error loading links for episode: $data - ${e.message}", e)
            return false // Indicate failure
        }

        Log.i("Kissasian", "Finished link loading for $data. Found links: $foundLinks")
        return foundLinks // Return true if callback was successfully invoked at least once
    }

    // ================== Helper Functions ==================

    private fun Element.cleanUpDescription(): String {
        // Remove promotional text, keep the core plot summary
        return this.text()
            .replace("(?i)Watch episodes online.*".toRegex(), "")
            .replace("(?i)Dear user.*".toRegex(), "")
            .replace("(?i)Kissasian.*".toRegex(), "")
            .trim()
    }

    // Extracts episode number from text like "Episode 123", "Ep 5"
    private fun String?.extractEpisodeNumber(): Int? {
        return this?.let {
            Regex("""(?:episode|ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    // Extracts episode number from URL like /drama/xyz/episode-12.html
     private fun String?.extractEpisodeNumberFromUrl(): Int? {
        return this?.let {
            Regex("""(?:episode-|/|-)(\d+)(?:\.html|\?|$)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}

// Remove the main function for production plugin
/*
fun main() {
    val kissasianProvider = KissasianProvider()
    runBlocking {
        // Test Search
        println("--- Searching for 'Goblin' ---")
        val searchResults = kissasianProvider.search("Goblin")
        searchResults.forEach { println("${it.name} - ${it.url}") }

        // Test Load (use a result from search)
        val testUrl = searchResults.firstOrNull()?.url
        if (testUrl != null) {
            println("\n--- Loading details for $testUrl ---")
            val loadData = kissasianProvider.load(testUrl)
            if (loadData != null) {
                println("Title: ${loadData.name}")
                println("Type: ${loadData.type}")
                println("Plot: ${loadData.plot}")
                if (loadData is TvSeriesLoadResponse) {
                    println("Episodes: ${loadData.episodes.size}")
                    val firstEpisodeUrl = loadData.episodes.firstOrNull()?.data
                    if (firstEpisodeUrl != null) {
                         println("\n--- Loading links for episode $firstEpisodeUrl ---")
                        kissasianProvider.loadLinks(firstEpisodeUrl, false, { sub ->
                            println("Subtitle found: ${sub.lang} - ${sub.url}")
                        }) { link ->
                            println("ExtractorLink found: ${link.name} - ${link.url} - Quality: ${link.quality}")
                        }
                    }
                } else if (loadData is MovieLoadResponse) {
                    println("\n--- Loading links for movie ${loadData.dataUrl} ---")
                     kissasianProvider.loadLinks(loadData.dataUrl, false, { sub ->
                            println("Subtitle found: ${sub.lang} - ${sub.url}")
                        }) { link ->
                            println("ExtractorLink found: ${link.name} - ${link.url} - Quality: ${link.quality}")
                        }
                }
            } else {
                println("Failed to load details.")
            }
        } else {
            println("No search results to test loading.")
        }
    }
}
*/
