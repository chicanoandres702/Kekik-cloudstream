package com.demonstratorz

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.BaseFunction
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
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to mainUrl
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
            val document = app.get(url, headers = headers).document
            Log.i("Kissasian", "Main page document loaded successfully.")
            Log.i("Kissasian", "Document: ${document.text()}")
            val dramas = document.select("#top > div > div.content > div.content-left > div > div.block.tab-container > div > ul > li").mapNotNull { dramaElement ->
                try {
                    val title = dramaElement.select("a").attr("title").trim()
                    val link = dramaElement.select("a.img").attr("href")
                    val posterUrl = dramaElement.select("img").attr("data-original")
                    val isMovie = url.contains("movie")
                    newTvSeriesSearchResponse(
                        title,
                        fixUrl(link),
                        TvType.AsianDrama,
                    ) {
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
            val document = app.get(searchUrl, headers = headers).document
            Log.i("Kissasian", "Search document loaded successfully.")

            document.select("#top > div > div.content > div.content-left > div > div.block.tab-container > div > ul > li").mapNotNull { searchElement ->
                try {
                    val title = searchElement.select("a").attr("title")
                    val link = searchElement.select("a").attr("href")
                    val posterUrl = searchElement.select("img").attr("src")
                    val isMovie = searchUrl.contains("movie")

                    Log.i("Kissasian", "Found search result: Title=$title, Link=$link, Poster=$posterUrl")
                    newTvSeriesSearchResponse(
                        title,
                        fixUrl(link),
                        TvType.AsianDrama,
                    ) {
                        this.posterUrl = fixUrl(posterUrl)
                    }
                } catch (e: Exception) {
                    Log.e("Kissasian", "Error processing search element: ${searchElement.text()} - ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("Kissasian", "Error during search for: $query - ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.i("Kissasian", "Loading details page: $url")
        return try {
            val document = app.get(url, headers = headers).document
            Log.i("Kissasian", "Details page document loaded successfully.")

            val title = document.select("#top > div.container > div.content > div.content-left > div.block > div.details > div.img > img").attr("alt").trim()
            val posterUrl = document.select("#top > div.container > div.content > div.content-left > div.block > div.details > div.img > img").attr("src")
            val description = document.select(".block-watch p").text().cleanDescription()

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

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.AsianDrama,
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
            val document = app.get(data, headers = headers).document
            val iframeUrl = document.select("#block-tab-video > div > div > iframe").attr("src")
            Log.i("Kissasian", "Iframe URL: $iframeUrl")
            if (iframeUrl.isNullOrEmpty()) {
                Log.w("Kissasian", "No iframe URL found for episode: $data")
                return false
            }

            var currentIframeUrl = fixUrl(iframeUrl)
            Log.i("Kissasian", "Initial Iframe URL: $currentIframeUrl")

            // Try multiple domains
            val domains = listOf("embasic.pro", "asianbxkiun.pro")
            var success = false

            for (domain in domains) {
                try {
                    currentIframeUrl = currentIframeUrl.replace(domains.first(), domain)
                    val iframeDoc = app.get(
                        currentIframeUrl,
                        referer = "$mainUrl/"
                    ).document

                    // Try to find direct video sources
                    iframeDoc.select("video source").forEach { source ->
                        val videoUrl = source.attr("src")
                        if (videoUrl.isNotEmpty()) {
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "Direct",
                                    videoUrl,
                                    currentIframeUrl,
                                    Qualities.Unknown.value
                                )
                            )
                            success = true
                        }
                    }

                    // Try to find m3u8 sources
                    val m3u8Pattern = Pattern.compile("[\"'](?:https?:)?//[^\"']+\\.m3u8[^\"']*[\"']")
                    val html = iframeDoc.html()
                    val matcher = m3u8Pattern.matcher(html)
                    while (matcher.find()) {
                        val m3u8Url = matcher.group().trim('\'', '"')
                        val fixedM3u8Url = if (m3u8Url.startsWith("//")) "https:$m3u8Url" else m3u8Url
                        M3u8Helper.generateM3u8(
                            this.name,
                            fixedM3u8Url,
                            currentIframeUrl
                        ).forEach { m3u8Link ->
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "${this.name} - M3U8",
                                    m3u8Link.url,
                                    currentIframeUrl,
                                    m3u8Link.quality
                                )
                            )
                            success = true
                        }
                    }

                    // Try JavaScript extraction
                    val scriptCode = iframeDoc.select("script[data-cfasync=false]").joinToString("\n") { it.data() }
                    extractUrlFromJavascript(scriptCode)?.let { jsUrl ->
                        when {
                            jsUrl.contains(".m3u8") -> {
                                M3u8Helper.generateM3u8(
                                    this.name,
                                    jsUrl,
                                    currentIframeUrl
                                ).forEach { m3u8Link ->
                                    callback.invoke(
                                        newExtractorLink(
                                            this.name,
                                            "${this.name} - M3U8",
                                            m3u8Link.url,
                                            currentIframeUrl,
                                            m3u8Link.quality
                                        )
                                    )
                                    success = true
                                }
                            }
                            jsUrl.contains(".mp4") -> {
                                callback.invoke(
                                    newExtractorLink(
                                        this.name,
                                        "Direct",
                                        jsUrl,
                                        currentIframeUrl,
                                        Qualities.Unknown.value
                                    )
                                )
                                success = true
                            }
                            else -> {
                                loadExtractor(jsUrl, currentIframeUrl, subtitleCallback, callback)
                                success = true
                            }
                        }
                    }

                    // Check for data-video attributes
                    iframeDoc.select("li[data-video]").forEach { video ->
                        val videoLink = video.attr("data-video")
                        if (videoLink.isNotEmpty()) {
                            try {
                                val response = app.get(videoLink)
                                if (response.isSuccessful) {
                                    loadExtractor(response.url, currentIframeUrl, subtitleCallback, callback)
                                    success = true
                                }
                            } catch (e: Exception) {
                                Log.e("Kissasian", "Error loading data-video link: $videoLink - ${e.message}")
                            }
                        }
                    }

                    if (success) break
                } catch (e: Exception) {
                    Log.e("Kissasian", "Error processing domain $domain: ${e.message}")
                }
            }

            return success
        } catch (e: Exception) {
            Log.e("Kissasian", "Error loading links for episode: $data - ${e.message}")
            return false
        }
    }

    private fun extractUrlFromJavascript(scriptCode: String): String? {
        val regex = "window\\.location(?:\\s*)=(?:\\s*)\\{(?:\\s*)['\"]href['\"](?:\\s*):(?:\\s*)['\"](.*?)(?:'|\")[\\s\\r\\n]*\\};"
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

    private fun fixUrl(url: String): String {
        if (url.isEmpty()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            !url.startsWith("http") -> "$mainUrl/$url"
            else -> url
        }
    }

    private fun getQualityFromName(qualityName: String): Int {
        return when (qualityName.lowercase()) {
            "4k" -> Qualities.P2160.value
            "1080p" -> Qualities.P1080.value
            "720p" -> Qualities.P720.value
            "480p" -> Qualities.P480.value
            "360p" -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanDescription(): String {
        return this.replace("""Dear user watch.*""".toRegex(), "").trim()
    }

    private fun String.extractEpisodeNumber(): Int {
        val episodeRegex = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val matchResult = episodeRegex.find(this)
        return matchResult?.groups?.get(1)?.value?.toIntOrNull() ?: 0
    }
}

// Plugin class for registering the provider
class KissAsianProviderPlugin : Plugin {
    override fun load(context: Context) {
        registerMainAPI(KissasianProvider())
    }
}

// For testing purposes
fun main() {
    val kissasianProvider = KissasianProvider()
    runBlocking {
        kissasianProvider.search("petri")
    }
}
