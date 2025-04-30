package com.demonstratorz

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.BaseFunction // Corrected import
import org.mozilla.javascript.Scriptable
import java.util.regex.Pattern

class KissasianProvider : MainAPI() {
    override var mainUrl = "https://kissasian.com.lv"
    override var name = "Kissasian"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
        // TvType.KShow
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
            Log.i("Kissasian", "Document: ${document.text()}")
            val dramas = document.select("#top > div > div.content > div.content-left > div > div.block.tab-container > div > ul > li").mapNotNull { dramaElement ->
                try {
                    val title = dramaElement.select("a").attr("title").trim()
                    val link = dramaElement.select("a.img").attr("href")
                    val posterUrl = dramaElement.select("img").attr("data-original")
                    val isMovie = url.contains("movie")
                    newAnimeSearchResponse(title, fixUrl(link), if (isMovie) TvType.Movie else TvType.TvSeries) {
                        this.posterUrl = fixUrl(posterUrl)
                    }
                } catch (e: Exception) {
                    Log.e("Kissasian", "Error processing drama element: ${dramaElement.text()} - ${e.message}")
                    null
                }
            }

            val hasNextPage = false
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
                    Log.i("Kissasian", "Found search result: Title=$title")
                    val link = searchElement.select("a").attr("href")
                    Log.i("Kissasian", "Found search result: Link=$link")
                    val posterUrl = searchElement.select("img").attr("src")
                    Log.i("Kissasian", "Found search result: Poster=$posterUrl")
                    val isMovie = searchUrl.contains("movie")
                    Log.i("Kissasian", "Found search result: IsMovie=$isMovie")

                    Log.i("Kissasian", "Found search result: Title=$title, Link=$link, Poster=$posterUrl")
                    newAnimeSearchResponse(title, fixUrl(link), if (isMovie) TvType.Movie else TvType.TvSeries) {
                        this.posterUrl = fixUrl(posterUrl)
                    }
                } catch (e: Exception) {
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
            val description = document.select(".block-watch p").text()

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

            val isMovie = false
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
        val vidmolyDocument = app.get(currentIframeUrl, referer = "$mainUrl/").document
        val vidmolyHtml = vidmolyDocument.text() // Get the full HTML content as a string
        Log.i("Kissasian", "Fetched Vidmoly HTML content.")

        // *** Apply the regex to find the video link ***
        // Updated regex based on the provided example URL structure
        val videoUrlRegex = Regex("(https?://[^/]+/hls/,(?:[^,]+?,){1,2}[^,]+?,?\\.urlset/master\\.m3u8)")
        val matchResult = videoUrlRegex.find(vidmolyHtml)

        val videoUrl = matchResult?.groups?.get(1)?.value

        if (videoUrl != null) {
            Log.i("Kissasian", "Extracted video URL: $videoUrl")
            // Assuming the extracted URL is a direct playable link (like M3U8)
            callback(
                ExtractorLink(
                    "Vidmoly", // Extractor name
                    "Vidmoly", // Quality (you might need to determine quality differently)
                    videoUrl,
                    "", // Referer
                    0, // Quality value - Changed from Qualities.Unknown.value to 0
                    true // IsM3u8
                )
            )
            return true
        } else {
            Log.w("Kissasian", "No video URL found in Vidmoly HTML using regex.")
            // Your existing JavaScript execution and fallback logic would go here
            // if the regex fails to find the link.
            // ... (rest of your existing loadLinks function for fallback)
        }

        return false // Return false if no links were found by any method

    } catch (e: Exception) {
        Log.e("Kissasian", "Error loading links for episode: $data - ${e.message}")
        return false
    }
}

fun extractUrlFromJavascript(scriptCode: String): String? {
    // *** AREA 4: URL Extraction from JavaScript Regex ***
    // This regex looks for window.location = { 'href': '...' };
    // The website's JavaScript might use a different way to store or reveal the URL.
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
    private fun Element.cleanUpDescription(): String {
        return this.text().replace("Dear user watch.*".toRegex(), "").trim()
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
        kissasianProvider.search("petri")
    }
}
