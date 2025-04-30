package com.demonstratorz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KissAsianProvider : MainAPI() {
    override var name = "KissAsian"
    override var mainUrl = "https://kissasian.com.lv"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama)
    
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
        val document = app.get(request.data, headers = headers).document
        
        val shows = document.select("#top > div > div.content > div.content-left > div > div.block.tab-container > div > ul > li").mapNotNull {
            val title = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterUrl = it.selectFirst("img")?.attr("src")
            
            newTvSeriesSearchResponse(
                name = title,
                url = href,
                type = TvType.AsianDrama,
            ) {
                this.posterUrl = posterUrl
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = shows
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = headers).document
        
        return document.select("#top > div > div.content > div.content-left > div > div.block.tab-container > div > ul > li").mapNotNull {
            val title = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterUrl = it.selectFirst("img")?.attr("src")
            
            newTvSeriesSearchResponse(
                name = title,
                url = href,
                type = TvType.AsianDrama,
            ) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        
        val title = document.selectFirst("h1")?.text()?.substringBefore("Episode")?.trim() ?: return null
        val poster = document.selectFirst(".img img")?.attr("src")
        val plot = document.selectFirst(".block-watch p")?.text()?.cleanDescription()
        
        val episodes = document.select(".list-episode-item-2 li").mapNotNull { element ->
            val epTitle = element.selectFirst(".title")?.text() ?: return@mapNotNull null
            val epHref = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epNum = epTitle.substringAfter("Episode ").toIntOrNull() ?: return@mapNotNull null
            
            Episode(
                data = epHref,
                episode = epNum,
                name = epTitle
            )
        }.reversed()

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.AsianDrama,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        var sourceFound = false
        
        document.select("#block-tab-video iframe").forEach { iframe ->
            val iframeUrl = iframe.attr("src").let { 
                if (it.startsWith("//")) "https:$it" else it 
            }
            
            when {
                iframeUrl.contains("vidmoly") -> {
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                    sourceFound = true
                }
                iframeUrl.contains(".m3u8") -> {
                    M3u8Helper.generateM3u8(
                        source = this.name,
                        streamUrl = iframeUrl,
                        referer = data
                    ).forEach(callback)
                    sourceFound = true
                }
                else -> {
                    try {
                        val iframeDoc = app.get(iframeUrl, headers = headers).document
                        
                        // Direct video sources
                        iframeDoc.select("video source").forEach { source ->
                            val videoUrl = source.attr("src")
                            if (videoUrl.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "Direct",
                                        url = videoUrl,
                                        referer = iframeUrl,
                                        quality = Qualities.Unknown.value,
                                        isM3u8 = videoUrl.contains(".m3u8")
                                    )
                                )
                                sourceFound = true
                            }
                        }

                        // M3u8 in scripts
                        iframeDoc.select("script").forEach { script ->
                            val m3u8Regex = """["'](?:https?:)?//[^"']+\.m3u8[^"']*["']""".toRegex()
                            m3u8Regex.find(script.data())?.value?.trim('"', '\'')?.let { m3u8Url ->
                                val fixedUrl = if (m3u8Url.startsWith("//")) "https:$m3u8Url" else m3u8Url
                                M3u8Helper.generateM3u8(
                                    source = this.name,
                                    streamUrl = fixedUrl,
                                    referer = iframeUrl
                                ).forEach(callback)
                                sourceFound = true
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        
        return sourceFound
    }

    private fun String.cleanDescription(): String {
        return this.replace("""Dear user watch.*""".toRegex(), "").trim()
    }
}
