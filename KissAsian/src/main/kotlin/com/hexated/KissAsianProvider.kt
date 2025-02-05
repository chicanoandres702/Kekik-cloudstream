package com.hexated

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
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/recently-added-drama/" to "Recently Drama",
        "$mainUrl/recently-added-movie/" to "Recently Movie",
        "$mainUrl/recently-added-kshow/" to "Recently Kshow" // If this page exists
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestUrl = request.data // Store request data in a variable

        // Mock Data (Replace with actual scraping later)
        val mockDramas = listOf(
            newAnimeSearchResponse("Drama Title 1 (Mock)", "drama-link-1", TvType.TvSeries) { posterUrl = "poster-url-1" },
            newAnimeSearchResponse("Drama Title 2 (Mock)", "drama-link-2", TvType.TvSeries) { posterUrl = "poster-url-2" },
            // Add more mock dramas as needed
        )

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = mockDramas, // Use the mock data
                isHorizontalImages = true
            ),
            hasNext = false
        )


        /*
        // Commented out scraping code
        val document = app.get(requestUrl).document // Use the stored URL
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
            hasNext = false
        )
        */
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Mock Search Results
        val mockSearchResults = listOf(
            newAnimeSearchResponse("Search Result 1 (Mock)", "search-link-1", TvType.TvSeries) { posterUrl = "search-poster-1" },
            newAnimeSearchResponse("Search Result 2 (Mock)", "search-link-2", TvType.TvSeries) { posterUrl = "search-poster-2" }
        )
        return mockSearchResults


        /*
        // Commented out scraping code
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
        */
    }

    override suspend fun load(url: String): LoadResponse? {
        // Mock Load Response
        val mockEpisodes = listOf(
            Episode("episode-link-1", 1),
            Episode("episode-link-2", 2)
        )
        val mockLoadResponse = newTvSeriesLoadResponse("Mock Load Title", url, TvType.TvSeries, mockEpisodes) {
            posterUrl = "mock-load-poster"
            plot = "Mock Load Description"
        }
        return mockLoadResponse

        /*
        // Commented out scraping code
        val document = app.get(url).document

        val title = document.select(".watch-drama h1").text().trim()
        val posterUrl = document.select(".watch-drama img").attr("src")
        val description = document.select(".block-watch p").firstOrNull()?.cleanUpDescription()?: ""
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
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = posterUrl
            this.plot = description
        }
        */
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Mock Load Links
        callback.invoke(
            ExtractorLink(
                name,
                name,
                "mock-iframe-url",
                data,
                1080
            )
        )
        return true

        /*
        // Commented out scraping code
        val document = app.get(data).document
        val iframeUrl = document.select(".watch-iframe iframe").attr("src")

        callback.invoke(
            ExtractorLink(
                name,
                name,
                iframeUrl,
                data,
                1080
            )
        )

        return true
        */
    }

    private fun Element.cleanUpDescription(): String {
        return this.text().replace("Dear user watch.*".toRegex(), "").trim()
    }

    private fun String.extractEpisodeNumber(): Int {
        return this.substringAfter("Episode ").substringBefore(" ").toIntOrNull()?: 0
    }
}
