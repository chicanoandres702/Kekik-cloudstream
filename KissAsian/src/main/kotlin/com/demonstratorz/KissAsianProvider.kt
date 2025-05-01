package com.demonstratorz

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import android.widget.Toast

class KissasianProvider : MainAPI() {
    override var mainUrl = "https://kissasian.com.lv"
    override var name = "Kissasian"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie
    )

    companion object {
        private suspend fun cfKiller(url: String): NiceResponse {
            var doc = app.get(url)
            if (doc.document.select("title").text() == "Just a moment...") {
                doc = app.get(url, interceptor = CloudflareKiller())
            }
            return doc
        }
    }

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
            val document = cfKiller(url).document
            val dramas = document.select("div.content div.block div.tab-content ul.list-episode-item li").mapNotNull { dramaElement ->
                try {
                    val title = dramaElement.select("a").attr("title").trim()
                    val link = dramaElement.select("a.img").attr("href")
                    val posterUrl = dramaElement.select("img").attr("data-original")
                    val isMovie = url.contains("movie")

                    newAnimeSearchResponse(title, fixUrl(link), if (isMovie) TvType.Movie else TvType.AsianDrama) {
                        this.posterUrl = fixUrlNull(posterUrl)
                    }
                } catch (e: Exception) {
                    Log.e("Kissasian", "Error processing drama element: ${dramaElement.text()} - ${e.message}")
                    null
                }
            }

            val hasNextPage = document.select("div.pagination a.next").isNotEmpty()
            newHomePageResponse(
                list = listOf(HomePageList(request.name, dramas)),
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
            val document = cfKiller(searchUrl).document
            Log.i("Kissasian", "Search document loaded successfully.")

            document.select("div.content div.block div.tab-content ul.list-episode-item li").mapNotNull { element ->
                try {
                    val title = element.select("a").attr("title").trim()
                    val href = element.select("a").attr("href")
                    val posterUrl = element.select("img").attr("src")
                    
                    val type = if (href.contains("/movie/") || title.contains("Movie", true)) {
                        TvType.Movie
                    } else {
                        TvType.AsianDrama
                    }

                    Log.i("Kissasian", "Found: Title=$title, URL=$href, Type=$type")
                    
                    if (title.isNotEmpty() && href.isNotEmpty()) {
                        newAnimeSearchResponse(
                            title,
                            fixUrl(href),
                            type
                        ) {
                            this.posterUrl = fixUrlNull(posterUrl)
                        }
                    } else null
                } catch (e: Exception) {
                    Log.e("Kissasian", "Error processing search result: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("Kissasian", "Error during search: ${e.message}")
            emptyList()
        }
    }

override suspend fun load(url: String): LoadResponse? {
    Log.i("Kissasian", "Loading details page: $url")
    return try {
        val document = cfKiller(url).document

        val title = document.select("#top > div.container > div.content > div.content-left > div.block > div.details > div.img > img").attr("alt").trim()
        val posterUrl = document.select("#top > div.container > div.content > div.content-left > div.block > div.details > div.img > img").attr("src")
        val description = document.select(".block-watch p").text()

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

        val isMovie = url.contains("/movie/") || title.contains("Movie", true)
        if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(posterUrl)
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = fixUrlNull(posterUrl)
                this.plot = description
            }
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
    Log.i("Kissasian", "Loading links for: $data")
    return try {
        val document = cfKiller(data).document
        val iframeUrl = document.select("#block-tab-video iframe").attr("src")
        Log.i("Kissasian", "Found iframe URL: $iframeUrl")
        
        app.activity?.runOnUiThread {
            Toast.makeText(app.context, "Found iframe: $iframeUrl", Toast.LENGTH_LONG).show()
        }

        if (iframeUrl.isNotEmpty()) {
            val fixedUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            
            when {
                fixedUrl.contains("vidmoly.to") -> {
                    app.activity?.runOnUiThread {
                        Toast.makeText(app.context, "Processing Vidmoly link", Toast.LENGTH_LONG).show()
                    }
                    handleVidmolySource(fixedUrl, subtitleCallback, callback)
                }
                else -> {
                    app.activity?.runOnUiThread {
                        Toast.makeText(app.context, "Using default extractor for: $fixedUrl", Toast.LENGTH_LONG).show()
                    }
                    loadExtractor(fixedUrl, data, subtitleCallback, callback)
                }
            }
            true
        } else {
            app.activity?.runOnUiThread {
                Toast.makeText(app.context, "No iframe URL found", Toast.LENGTH_LONG).show()
            }
            Log.w("Kissasian", "No iframe URL found")
            false
        }
    } catch (e: Exception) {
        app.activity?.runOnUiThread {
            Toast.makeText(app.context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
        Log.e("Kissasian", "Error loading links: ${e.message}")
        false
    }
}

private suspend fun handleVidmolySource(
    url: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return try {
        val response = cfKiller(url)
        val document = response.document

        // Extract video URL from JWPlayer setup
        val videoSourcesPattern = """sources:\s*$$\{file:"([^"]+)"\}$$""".toRegex()
        val match = videoSourcesPattern.find(document.html())
        val videoUrl = match?.groupValues?.get(1)

        app.activity?.runOnUiThread {
            Toast.makeText(app.context, "Found video URL: $videoUrl", Toast.LENGTH_LONG).show()
        }

        if (!videoUrl.isNullOrEmpty()) {
            if (videoUrl.contains(".m3u8")) {
                app.activity?.runOnUiThread {
                    Toast.makeText(app.context, "Processing M3U8 stream", Toast.LENGTH_LONG).show()
                }
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = videoUrl,
                    referer = url
                ).forEach(callback)
            } else {
                app.activity?.runOnUiThread {
                    Toast.makeText(app.context, "Processing direct video link", Toast.LENGTH_LONG).show()
                }
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl
                    ) {
                        this.quality = 720
                        this.referer = url
                    }
                )
            }

            // Extract subtitles
            document.select("tracks").forEach { track ->
                val subtitleUrl = track.attr("file")
                val label = track.attr("label")
                if (subtitleUrl.isNotEmpty() && subtitleUrl.endsWith(".vtt")) {
                    app.activity?.runOnUiThread {
                        Toast.makeText(app.context, "Found subtitle: $label", Toast.LENGTH_LONG).show()
                    }
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = label,
                            url = subtitleUrl
                        )
                    )
                }
            }
            true
        } else {
            app.activity?.runOnUiThread {
                Toast.makeText(app.context, "No video URL found in player setup", Toast.LENGTH_LONG).show()
            }
            Log.w("Vidmoly", "No video URL found")
            false
        }
    } catch (e: Exception) {
        app.activity?.runOnUiThread {
            Toast.makeText(app.context, "Vidmoly error: ${e.message}", Toast.LENGTH_LONG).show()
        }
        Log.e("Vidmoly", "Error handling Vidmoly source: ${e.message}")
        false
    }
}
    private fun fixUrlNull(url: String?): String? {
        if (url == null) return null
        return if (url.startsWith("//")) {
            "https:$url"
        } else if (url.startsWith("/")) {
            mainUrl + url
        } else if (url.isNotEmpty() && !url.startsWith("http")) {
            "$mainUrl/$url"
        } else {
            url
        }
    }

    private fun String.extractEpisodeNumber(): Int {
        val episodeRegex = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val matchResult = episodeRegex.find(this)
        return matchResult?.groups?.get(1)?.value?.toIntOrNull() ?: 0
    }
}
