import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class KissasianProvider : MainAPI() {
    override var mainUrl = "https://kissasian.com.lv" // Use .lv domain
    override var name = "Kissasian"
    override val hasMainPage = true
    override val hasDownloadSupport = true // Check if it's really supported
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie
        //TvType.KShow // Corrected type, be sure this exists!
    )

    override val mainPage = mainPageOf(
        "$mainUrl/recently-added-drama/" to "Recently Drama",
        "$mainUrl/recently-added-movie/" to "Recently Movie",
        "$mainUrl/recently-added-kshow/" to "Recently Kshow" //Kshow probably need to be verified.
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data
        // val document = app.get(url).document
        var title = "testing"
        var link = "https://google.com"
        var posterUrl = "https://google.com"
        newAnimeSearchResponse(title, fixUrl(link), TvType.TvSeries) { // use fixUrl
            this.posterUrl = fixUrl(posterUrl) // use fixUrl
        }
        // val dramas = document.select(".item-list").mapNotNull { dramaElement ->
        //     val title = dramaElement.select("h2.title").text().trim()
        //     val link = dramaElement.select("a.img").attr("href")
        //     val posterUrl = dramaElement.select("img").attr("data-original")
        //     newAnimeSearchResponse(title, fixUrl(link), TvType.TvSeries) { // use fixUrl
        //         this.posterUrl = fixUrl(posterUrl) // use fixUrl
        //     }
        // }

        return newHomePageResponse(
            list = dramas, // Use dramas list
            name = request.name,
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
            newAnimeSearchResponse(title, fixUrl(link), TvType.TvSeries) { // use fixUrl
                this.posterUrl = fixUrl(posterUrl) // use fixUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.select(".watch-drama h1").text().trim()
        val posterUrl = document.select(".watch-drama img").attr("src")
        val description = document.select(".block-watch p").firstOrNull()?.cleanUpDescription() ?: "" // Corrected call
        val episodes = document.select(".all-episode li").mapNotNull { episodeElement -> // use mapNotNull
            val episodeUrl = episodeElement.select("a").attr("href")
            val episodeNum = episodeElement.select("h3.title").text().extractEpisodeNumber()
            Episode(
                data = fixUrl(episodeUrl), // fix url
                episode = episodeNum
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries, // Or TvType.Movie if it's a movie
            episodes
        ) {
            this.posterUrl = fixUrl(posterUrl) // fix url
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

        if (iframeUrl.isNullOrEmpty()) {
            log("No iframe URL found for episode: $data")
            return false
        }

        val fullIframeUrl = fixUrl(iframeUrl) // Very Important!

        loadExtractor(fullIframeUrl, subtitleCallback, callback) //lets cloudstream handle this

        return true
    }

    private fun Element.cleanUpDescription(): String {
        return this.text().replace("Dear user watch.*".toRegex(), "").trim()
    }

    private fun String.extractEpisodeNumber(): Int {
        return this.substringAfter("Episode ").substringBefore(" ").toIntOrNull() ?: 0
    }
}
