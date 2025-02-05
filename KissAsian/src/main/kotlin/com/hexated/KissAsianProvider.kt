package com.hexated // Update package name if needed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
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
        TvType.KShow // Corrected type
    )

    override val mainPage = mainPageOf(
        "$mainUrl/recently-added-drama/" to "Recently Drama",
        "$mainUrl/recently-added-movie/" to "Recently Movie",
        "$mainUrl/recently-added-kshow/" to "Recently Kshow"
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
        val document = app.get(url).document

        val title = document.select(".watch-drama h1").text().trim()
        val posterUrl = document.select(".watch-drama img").attr("src")
        val description = document.select(".block-watch p").firstOrNull()?.cleanUpDescription()?: "" // Corrected call
        val episodes = document.select(".all-episode li").map { episodeElement ->
            val episodeNum = episodeElement.select("h3.title").text().extractEpisodeNumber()
            val episodeUrl = episodeElement.select("a").attr("href")
            Episode(
                data = episodeUrl,
                episode = episodeNum
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries, // Or TvType.Movie if it's a movie
            episodes
        ) {
            this.posterUrl = posterUrl
            this.plot = description
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
                1080 // Or a suitable quality value
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
