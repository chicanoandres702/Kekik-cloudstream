package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class KissasianProvider: MainAPI() {
    override var mainUrl = "https://kissasian.com.lv"
    override var name = "Kissasian" 
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
        TvType.KShow
    )

    override val mainPage = mainPageOf(
        "$mainUrl/most-popular-drama/" to "Popular Drama",
        "$mainUrl/country/south-korea/" to "Korean Drama",
        "$mainUrl/country/thailand/" to "Thailand Drama"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data
        val document = app.get(url).document
        val dramas = document.select(".item-list").mapNotNull { dramaElement ->
            val title = dramaElement.select("h2.title").text().trim()
            val link = dramaElement.select("a.img").attr("href")
            val posterUrl = dramaElement.select("img").attr("data-original")
            newAnimeSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = dramas,
                isHorizontalImages = true
            ),
            hasNext = false // No pagination on the recent pages
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select(".item-list").mapNotNull { searchElement ->
            val title = searchElement.select("h2.title").text().trim()
            val link = searchElement.select("a.img").attr("href")
            val posterUrl = searchElement.select("img").attr("data-original")
            newAnimeSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
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
        val document = app.get(data).document
        val iframeUrl = document.select(".watch-iframe iframe").attr("src")

        // Further processing of iframeUrl to extract video links
        // For now, assuming the iframeUrl is the video URL
        callback.invoke(
            ExtractorLink(
                name,
                name,
                iframeUrl,
                data, // Referer
                true
            )
        )

        return true
    }

    private fun Element.cleanUpDescription(): String {
        return this.text().replace("Dear user watch.*".toRegex(), "").trim()
    }

    private fun String.extractEpisodeNumber(): Int {
        return this.substringAfter("Episode ").substringBefore(" ").toIntOrNull()?: 0
    }
}
