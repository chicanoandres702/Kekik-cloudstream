package com.demonstratorz

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper // Keep if needed elsewhere, removed direct usage in loadLinks
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Scriptable
import java.util.regex.Pattern
import kotlinx.coroutines.launch // Import for coroutineScope and launch
import kotlinx.coroutines.coroutineScope // Import for coroutineScope


class KissasianProvider : MainAPI() {
    override var mainUrl = "https://kissasian.com.lv"
    override var name = "Kissasian"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
        // TvType.KShow // Commented out in original code
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trending",
        "$mainUrl/most-popular-drama/" to "Popular Drama",
        "$mainUrl/country/south-korea/" to "South Korea",
        "$mainUrl/country/thailand/" to "Thailand"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data
        Log.i("Kissasian", "Getting main page: $url, page: $page, request: $request")
        return try {
            val document = app.get(url).document
            Log.i("Kissasian", "Main page document loaded successfully.")
            // Log.i("Kissasian", "Document: ${document.text()}") // Keep commented out for cleaner logs unless debugging

            val dramas = document.select("#top > div > div.content > div.content-left > div > div.block.tab-container > div > ul > li").mapNotNull { dramaElement ->
                try {
                    val title = dramaElement.select("a").attr("title").trim()
                    val link = dramaElement.select("a.img").attr("href")
                    val posterUrl = dramaElement.select("img").attr("data-original")
                    val isMovie = url.contains("movie") // This might not be a reliable indicator for TvType
                    newAnimeSearchResponse(title, fixUrl(link), if (isMovie) TvType.Movie else TvType.TvSeries) {
                        this.posterUrl = fixUrl(posterUrl)
                    }
                } catch (e: Exception) {
                    // Corrected Log.e call
                    Log.e("Kissasian", "Error processing drama element: ${dramaElement.text()} - ${e.message}")
                    null
                }
            }

            val hasNextPage = false // Check if there's a next page button/link if pagination exists
            Log.i("Kissasian", "Main page scraping complete. Found ${dramas.size} dramas.")
            newHomePageResponse(
                list = dramas,
                name = request.name,
                hasNext = hasNextPage
            )
        } catch (e: Exception) {
            Log.e("Kissasian", "Error getting main page: $url - ${e.message}")
            HomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        Log.i("Kissasian", "Searching for: $query, URL: $searchUrl")
        return try {
            val document = app.get(searchUrl).document
            Log.i("Kissasian", "Search document loaded successfully.")

            val searchResults: List<SearchResponse> = document.select("#top > div > div.content > div.content-left > div > div.block.tab-container > div > ul > li").mapNotNull { searchElement ->
                try {
                    val title = searchElement.select("a").attr("title")
                    // Log.i("Kissasian", "Found search result: Title=$title") // Keep commented out for cleaner logs
                    val link = searchElement.select("a").attr("href")
                    // Log.i("Kissasian", "Found search result: Link=$link") // Keep commented out for cleaner logs
                    val posterUrl = searchElement.select("img").attr("src")
                    // Log.i("Kissasian", "Found search result: Poster=$posterUrl") // Keep commented out for cleaner logs
                    val isMovie = searchUrl.contains("movie") // This might not be a reliable indicator for TvType
                    // Log.i("Kissasian", "Found search result: IsMovie=$isMovie") // Keep commented out for cleaner logs

                    // Log.i("Kissasian", "Found search result: Title=$title, Link=$link, Poster=$posterUrl") // Keep commented out for cleaner logs
                    newAnimeSearchResponse(title, fixUrl(link), if (isMovie) TvType.Movie else TvType.TvSeries) {
                        this.posterUrl = fixUrl(posterUrl)
                    }
                } catch (e: Exception) {
                    // Corrected Log.e call
                    Log.e("Kissasian", "Error processing search element: ${searchElement.text()} - ${e.message}")
                    null
                }
            }
            Log.i("Kissasian", "Search complete. Found ${searchResults.size} results.")
            return searchResults
        } catch (e: Exception) {
            Log.e("Kissasian", "Error during search for: $query - ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.i("Kissasian", "Loading details page: $url")
        return try {
            val document = app.get(url).document
            Log.i("Kissasian", "Details page document loaded successfully.")

            val title = document.select("#top > div.container > div.content > div.content-left > div.block > div.details > div.img > img").attr("alt").trim()
            val posterUrl = document.select("#top > div.container > div.content > div.content-left > div.block > div.details > div.img > img").attr("src")
            // Corrected usage of cleanUpDescriptionString
            val description = document.select(".block-watch p").text().cleanUpDescriptionString()


            Log.i("Kissasian", "Details: Title=$title, Poster=$posterUrl, Description=$description")

            val episodes = document.select(".all-episode li").mapNotNull { episodeElement ->
                try {
                    val episodeUrl = episodeElement.select("a").attr("href")
                    val episodeNum = episodeElement.select("h3.title").text().extractEpisodeNumber()
                    Log.i("Kissasian", "Found episode: URL=$episodeUrl, Number=$episodeNum")

                    Episode(
                        data = fixUrl(episodeUrl),
                        episode = episodeNum
                    )
                } catch (e: Exception) {
                    Log.e("Kissasian", "Error processing episode element: ${episodeElement.text()} - ${e.message}")
                    null
                }
            }

            val isMovie = false // Determine if it's a movie more reliably if possible
            newTvSeriesLoadResponse(
                title,
                url,
                if (isMovie) TvType.Movie else TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = fixUrl(posterUrl)
                this.plot = description
            }

        } catch (e: Exception) {
            Log.e("Kissasian", "Error loading details page: $url - ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i("Kissasian", "Loading links for episode: $data")
        return try {
            val document = app.get(data).document
            val iframeUrl = document.select("#block-tab-video > div > div > iframe").attr("src")
            Log.i("Kissasian", "Iframe URL: $iframeUrl")
            if (iframeUrl.isNullOrEmpty()) {
                Log.w("Kissasian", "No iframe URL found for episode: $data")
                return false
            }

            var currentIframeUrl = fixUrl(iframeUrl)
            Log.i("Kissasian", "Initial Iframe URL: $currentIframeUrl")

            // Fetch the content of the vidmoly.to embed page
            // Removed the domain replacement as the user specified the vidmoly.to URL comes from the iframe
            val vidmolyDocument = app.get(currentIframeUrl, referer = "$mainUrl/").document
            val vidmolyHtml = vidmolyDocument.text() // Get the full HTML content as a string
            Log.i("Kissasian", "Fetched Vidmoly HTML content.")

            // *** Apply the regex to find the video link from Vidmoly HTML ***
            // Regex based on the provided example URL structure
            val videoUrlRegex = Regex("(https?://[^/]+/hls/,(?:[^,]+?,){1,2}[^,]+?,?\\.urlset/master\\.m3u8)")
            val matchResult = videoUrlRegex.find(vidmolyHtml)

            val videoUrl = matchResult?.groups?.get(1)?.value

            if (videoUrl != null) {
                Log.i("Kissasian", "Extracted video URL: $videoUrl")
                // Assuming the extracted URL is a direct playable link (like M3u8)
                // *** Use newExtractorLink instead of the deprecated constructor ***
                callback(
                    newExtractorLink(videoUrl, "Vidmoly") { // Pass URL and Quality Label
                        name = "Vidmoly" // Extractor Name
                        quality = 0 // Quality Value (using 0 as before)
                        isM3u8 = true
                        referer = currentIframeUrl // Set referer to the vidmoly page URL
                    }
                )
                return true
            } else {
                Log.w("Kissasian", "No video URL found in Vidmoly HTML using regex.")

                // *** Fallback Method (Corrected for Suspension functions) ***
                val document = app.get(currentIframeUrl).document // Re-fetch if needed, or use vidmolyDocument
                val body = document.body() // Use the body of the vidmoly page

                // Collect potential video URLs from data-video attributes from the vidmoly page
                val fallbackVideoUrls = body.select("li")
                    .mapNotNull { video ->
                        video.attr("data-video").takeIf { it.isNotEmpty() } // Get data-video if not empty
                    }

                // Process each fallback URL using loadExtractor within a coroutine context
                // Using forEach inside coroutineScope to handle suspend calls
                coroutineScope {
                    fallbackVideoUrls.forEach { videoUrl ->
                        launch { // Launch a new coroutine for each URL
                            try {
                                // Use loadExtractor directly with the fallback URL
                                loadExtractor(videoUrl, subtitleCallback, callback)
                            } catch (e: Exception) {
                                // Corrected Log.e call
                                Log.e("Kissasian", "Error loading links from fallback URL $videoUrl: ${e.message}")
                            }
                        }
                    }
                }
                 // If fallback urls were found and processed, return true, otherwise false
                 return fallbackVideoUrls.isNotEmpty()

                // Note: The original JavaScript execution logic has been removed
                // in favor of the regex extraction and the data-video fallback
                // based on your latest information. If the regex fails and
                // data-video fallback is not sufficient, you might need to re-introduce
                // or modify the JavaScript execution based on how the vidmoly page works.

            }


        } catch (e: Exception) {
            Log.e("Kissasian", "Error loading links for episode: $data - ${e.message}")
            return false
        }
    }

    fun extractUrlFromJavascript(scriptCode: String): String? {
        // This function might not be needed anymore if regex extraction is sufficient for Vidmoly
        // but keeping it in case the JavaScript execution becomes relevant again for other scenarios.
        val regex = "window\\.location(?:\\s*)=(?:\\s*)\\{(?:\\s*)['\"]href['\"](?:\\s*):(?:\\s*)['\"](.*?)(?:'|\")[\\s\\r\\n]*\\};" // Non-greedy matching
        val pattern = Pattern.compile(regex, Pattern.MULTILINE)
        val matcher = pattern.matcher(scriptCode)

        return if (matcher.find()) {
            val url = matcher.group(1)
            Log.i("UrlExtractor", "Found URL: $url")
            url
        } else {
            Log.i("UrlExtractor", "No URL found in JavaScript code.")
            null
        }
    }

    // Removed the old cleanUpDescription extension function for Element

    // Added new extension function for String based on error fix
    private fun String.cleanUpDescriptionString(): String {
        return this.replace("Dear user watch.*".toRegex(), "").trim()
    }


    private fun String.extractEpisodeNumber(): Int {
        val episodeRegex = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val matchResult = episodeRegex.find(this)
        return matchResult?.groups?.get(1)?.value?.toIntOrNull() ?: 0
    }
}

fun main() {
    val kissasianProvider = KissasianProvider()
    runBlocking {
        // Example usage, you might need to provide a real episode URL for loadLinks testing
        kissasianProvider.search("petri")
        // kissasianProvider.loadLinks("some_episode_url", false, { subtitle -> }, { link -> })
    }
}
