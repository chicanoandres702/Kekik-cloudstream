package com.lagradost.cloudstream3.providers // Ensure this is your correct package

import com.fasterxml.jackson.annotation.JsonProperty // Import for JSON annotation
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // Wildcard import kept for brevity
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.mapper.readValue // Use mapper's readValue
import com.lagradost.cloudstream3.utils.AppUtils.mapper // Import the mapper object
import com.lagradost.cloudstream3.utils.Coroutines.apmapNotNull // Correct import

class DramacoolProvider : MainAPI() {
    // Provider metadata
    override var mainUrl = "https://asianctv.co"
    override var name = "Dramacool"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
        TvType.TvShow // Corrected: Use TvType.TvShow
    )

    // Companion object for utilities
    companion object {
        // Cloudflare bypass utility
        private suspend fun cfKiller(url: String): NiceResponse {
            val response = app.get(url)
            Log.d("DramacoolProvider", "cfKiller: Initial GET for $url returned status code ${response.code}")
            return if (response.document.selectFirst("title")?.text() == "Just a moment...") {
                Log.d("DramacoolProvider", "Cloudflare detected for $url. Retrying with CloudflareKiller.")
                val cfResponse = app.get(url, interceptor = CloudflareKiller())
                Log.d("DramacoolProvider", "cfKiller: CloudflareKiller GET for $url returned status code ${cfResponse.code}")
                cfResponse
            } else {
                response
            }
        }

        // Helper to extract episode number
        private fun String?.extractEpisodeNumber(): Int? {
            return this?.let {
                Regex("""(?:ep(?:isode)?\s*)(\d+)""", RegexOption.IGNORE_CASE)
                    .find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        }

        // Helper class for link analysis
        data class LinkInfo(val url: String, val type: TvType, val isDirectEpisodeLink: Boolean)

        // Analyze link type and structure
        fun getLinkInfo(element: Element?, initialUrl: String? = null): LinkInfo? {
            val aTag = element?.selectFirst("a.img") ?: element?.selectFirst("a")
            val url = initialUrl ?: aTag?.attr("href") ?: return null
            if (url.isBlank()) return null

            val typeSpan = element?.selectFirst("span.type")?.text()?.uppercase()
            val h3Title = element?.selectFirst("h3.title")?.text()

            val type = when {
                url.contains("/movie/", ignoreCase = true) || url.contains("-movie-", ignoreCase = true) -> TvType.Movie
                url.contains("/kshow/", ignoreCase = true) || typeSpan == "KSHOW" -> TvType.TvShow // Corrected: Use TvType.TvShow
                url.contains("/drama-detail/", ignoreCase = true) -> TvType.AsianDrama
                !url.contains("/drama-detail/", ignoreCase = true) && (url.endsWith(".html") || url.contains("-episode-")) -> TvType.AsianDrama
                h3Title?.contains("Movie", ignoreCase = true) == true || typeSpan == "MOVIE" -> TvType.Movie
                else -> TvType.AsianDrama
            }

            val isDirectEpisodeLink = !url.contains("/drama-detail/", ignoreCase = true) &&
                                      (url.endsWith(".html") || url.contains("-episode-", ignoreCase = true)) &&
                                      type != TvType.Movie

            return LinkInfo(url, type, isDirectEpisodeLink)
        }

        // Get the main show detail URL from an episode page
        suspend fun getDetailUrlFromEpisode(episodeUrl: String, providerInstance: DramacoolProvider): String? {
            return try {
                val fixedEpisodeUrl = providerInstance.fixUrl(episodeUrl)
                Log.d(providerInstance.name, "Fetching episode page to find detail URL: $fixedEpisodeUrl")
                val episodeDoc = cfKiller(fixedEpisodeUrl).document
                val detailLink = episodeDoc.selectFirst("div.info-drama div.category a[href*='/drama-detail/']")?.attr("href")
                    ?: episodeDoc.select("script[type=\"application/ld+json\"]").mapNotNull { script ->
                            try {
                                val json = script.data()
                                if (json.contains("BreadcrumbList") && json.contains("/drama-detail/")) {
                                    // Use AppUtils.mapper and correct generic type
                                    val parsedList = mapper.readValue<List<JsonLdSchema>>(json)
                                    parsedList.find { schema -> schema.type == "BreadcrumbList" } // Access fixed type field
                                        ?.itemListElement
                                        ?.find { listItem -> listItem.item?.contains("/drama-detail/") == true }
                                        ?.item
                                } else null
                            } catch (e: Exception) {
                                Log.e(providerInstance.name, "JSON-LD parsing failed", e)
                                null
                            }
                        }.firstOrNull()

                if (detailLink != null) {
                    Log.d(providerInstance.name, "Found detail URL: $detailLink from episode page: $fixedEpisodeUrl")
                } else {
                     Log.w(providerInstance.name, "Could not find detail URL on episode page: $fixedEpisodeUrl")
                }
                detailLink
            } catch (e: Exception) {
                Log.e(providerInstance.name, "Failed to get detail URL from episode page $episodeUrl", e) // Correct Log.e
                null
            }
        }

        // Helper class for JSON-LD parsing with @JsonProperty
        data class JsonLdSchema(
            @JsonProperty("@context") val context: String? = null,
            @JsonProperty("@type") val type: String? = null, // Renamed for consistency, access via .type
            val itemListElement: List<ListItemSchema>? = null
        )

        data class ListItemSchema(
             @JsonProperty("@type") val type: String? = null, // Renamed for consistency
             val position: Int? = null,
             val name: String? = null,
             val item: String? = null
        )
    } // End Companion Object

    // --- Main API Overrides ---

    override val mainPage = mainPageOf(
        "/" to "Recently Drama",
        "/recently-added-movie" to "Recently Movie",
        "/recently-added-kshow" to "Recently KShow",
        "/most-popular-drama" to "Popular Drama",
        "/country/korean-drama" to "Korean Drama",
        "/country/japanese-drama" to "Japanese Drama",
        "/country/taiwanese-drama" to "Taiwanese Drama",
        "/country/hong-kong-drama" to "Hong Kong Drama",
        "/country/chinese-drama" to "Chinese Drama",
        "/country/thailand-drama" to "Thailand Drama",
        "/country/indian-drama" to "Indian Drama",
        "/country/american-drama" to "American Drama",
        "/country/other-asia-drama" to "Other Asia Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        Log.i(name, "getMainPage: Url: $pageUrl, Page: $page, Request: ${request.name}")

        return try {
            val document = cfKiller(fixUrl(pageUrl)).document
            val items = document.select("ul.list-episode-item li, ul.list-drama li").apmapNotNull { element: Element -> // Use apmapNotNull with explicit type
                try {
                    val initialLinkInfo = getLinkInfo(element) ?: return@apmapNotNull null
                    val posterUrl = element.selectFirst("img.lazy")?.attr("data-original")
                                    ?: element.selectFirst("img")?.attr("src")

                    val (finalTitle: String, detailUrl: String?) = if (initialLinkInfo.isDirectEpisodeLink) {
                        val canonicalUrl = getDetailUrlFromEpisode(initialLinkInfo.url, this)
                        val showTitle = element.selectFirst("h3.title")?.text()
                            ?.replace(Regex("""\s+Episode\s+\d+$""", RegexOption.IGNORE_CASE), "")
                            ?.replace(Regex("""\s+EP\s+\d+$""", RegexOption.IGNORE_CASE), "")
                            ?.trim() ?: "Unknown Show"
                        Pair(showTitle, canonicalUrl)
                    } else {
                        val showTitle = element.selectFirst("h3.title")?.text()?.trim() ?: "Unknown"
                        Pair(showTitle, initialLinkInfo.url)
                    }

                    if (detailUrl.isNullOrBlank()) {
                        Log.w(name, "Could not resolve detail URL for ${initialLinkInfo.url}")
                        return@apmapNotNull null
                    }

                    when (initialLinkInfo.type) {
                        TvType.Movie -> newMovieSearchResponse(finalTitle, fixUrl(detailUrl), initialLinkInfo.type) {
                            this.posterUrl = fixUrlNull(posterUrl)
                        }
                        else -> newTvShowSearchResponse(finalTitle, fixUrl(detailUrl), initialLinkInfo.type) {
                            this.posterUrl = fixUrlNull(posterUrl)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(name, "Error processing main page element: ${element.html()?.take(100)}...", e) // Correct Log.e
                    null
                }
            }

            val hasNextPage = document.selectFirst("ul.pagination li.next a, div.pagination a.next, li.next > a") != null

            newHomePageResponse(list = HomePageList(request.name, items), hasNext = hasNextPage)
        } catch (e: Exception) {
            Log.e(name, "Error getting main page: $pageUrl", e) // Correct Log.e
             HomePageResponse(emptyList(), false) // Fallback
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?keyword=${query}&type=movies"
        Log.i(name, "Searching for: '$query', URL: $searchUrl")

        return try {
            val document = cfKiller(searchUrl).document
            document.select("ul.list-episode-item li").apmapNotNull { element: Element -> // Explicit type
                try {
                     val initialLinkInfo = getLinkInfo(element) ?: return@apmapNotNull null
                     val posterUrl = element.selectFirst("img.lazy")?.attr("data-original")
                                    ?: element.selectFirst("img")?.attr("src")

                     val (finalTitle: String, detailUrl: String?) = if (initialLinkInfo.isDirectEpisodeLink) {
                         Log.w(name, "Search result link is direct episode: ${initialLinkInfo.url}. Resolving...")
                         val canonicalUrl = getDetailUrlFromEpisode(initialLinkInfo.url, this)
                         val showTitle = element.selectFirst("h3.title")?.text()
                            ?.replace(Regex("""\s+Episode\s+\d+$""", RegexOption.IGNORE_CASE), "")
                            ?.replace(Regex("""\s+EP\s+\d+$""", RegexOption.IGNORE_CASE), "")
                            ?.trim() ?: "Unknown Show"
                         Pair(showTitle, canonicalUrl)
                     } else {
                         val showTitle = element.selectFirst("h3.title")?.text()?.trim() ?: "Unknown"
                         Pair(showTitle, initialLinkInfo.url)
                     }

                    if (detailUrl.isNullOrBlank()) {
                        Log.w(name, "Could not resolve detail URL for search result ${initialLinkInfo.url}")
                        return@apmapNotNull null
                    }

                    Log.d(name, "Found Search Result: Title='$finalTitle', URL='$detailUrl', Type=${initialLinkInfo.type}")

                     when (initialLinkInfo.type) {
                         TvType.Movie -> newMovieSearchResponse(finalTitle, fixUrl(detailUrl), initialLinkInfo.type) { this.posterUrl = fixUrlNull(posterUrl) }
                         else -> newTvShowSearchResponse(finalTitle, fixUrl(detailUrl), initialLinkInfo.type) { this.posterUrl = fixUrlNull(posterUrl) }
                     }
                } catch (e: Exception) {
                    Log.e(name, "Error processing search result element: ${element.html()?.take(100)}...", e) // Correct Log.e
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error during search for '$query'", e) // Correct Log.e
            emptyList()
        }
    }


    override suspend fun load(url: String): LoadResponse? {
        Log.i(name, "Attempting to load details page: $url")
        val detailUrl = if (!url.contains("/drama-detail/", ignoreCase = true)) {
             Log.w(name, "Load function called with non-detail URL: $url. Attempting to recover...")
             val recovered = getDetailUrlFromEpisode(url, this)
             if (recovered == null) {
                 Log.e(name, "Could not recover detail URL from $url. Aborting load.")
                 return null
             }
             Log.i(name, "Recovered detail URL: $recovered")
             fixUrl(recovered)
        } else {
            fixUrl(url)
        }
        Log.i(name, "Loading resolved details page: $detailUrl")


        return try {
            val document = cfKiller(detailUrl).document

            val title = document.selectFirst(".details .info h1")?.text()?.trim()
                        ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
                        ?: return null

            val posterUrl = document.selectFirst(".details .thumbnail img")?.attr("src")
                         ?: document.selectFirst(".details .img img")?.attr("src")
                         ?: document.selectFirst("meta[property='og:image']")?.attr("content")

            val plot = document.selectFirst("div.info p:has(strong:containsOwn(Description)) + p")?.text()?.trim()
                       ?: document.selectFirst(".details .info p:not(:has(strong)):not(:contains(Other name))")?.text()
                       ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

            Log.i(name, "Title: $title")
            Log.d(name, "Poster: $posterUrl")
            Log.d(name, "Plot: ${plot?.take(100)}...")

            val statusText = document.selectFirst("p:contains(Status:) span")?.text() ?: document.selectFirst("p:contains(Status:)")?.ownText()
            val status = when {
                 statusText?.contains("Ongoing", ignoreCase = true) == true -> ShowStatus.Ongoing
                 statusText?.contains("Completed", ignoreCase = true) == true -> ShowStatus.Completed
                 else -> null
             }
            val year = (document.selectFirst("p:contains(Released:) a")?.text()?.trim()
                        ?: document.selectFirst("p:contains(Released:)")?.ownText()?.trim())?.toIntOrNull()
            val genres = document.select("p:contains(Genre:) a").mapNotNull { it.text() }

            val recommendations = document.select("ul.list-episode-item li").mapNotNull { recElement ->
                 try {
                    val linkInfoRec = getLinkInfo(recElement) ?: return@mapNotNull null
                    if (linkInfoRec.isDirectEpisodeLink) return@mapNotNull null
                    val recTitle = recElement.selectFirst("h3.title")?.text()?.trim() ?: return@mapNotNull null
                    val recPoster = recElement.selectFirst("img.lazy")?.attr("data-original") ?: recElement.selectFirst("img")?.attr("src")

                    when (linkInfoRec.type) {
                        TvType.Movie -> newMovieSearchResponse(recTitle, fixUrl(linkInfoRec.url), linkInfoRec.type) { this.posterUrl = fixUrlNull(recPoster) }
                        else -> newTvShowSearchResponse(recTitle, fixUrl(linkInfoRec.url), linkInfoRec.type) { this.posterUrl = fixUrlNull(recPoster) }
                    }
                 } catch (e: Exception) { null }
            }.take(15)

            val episodeElements = document.select("ul.list-episode-item-2.all-episode li")

            if (episodeElements.isEmpty()) {
                Log.i(name, "$title identified as Movie (no episode list found)")
                newMovieLoadResponse(title, detailUrl, TvType.Movie, detailUrl) {
                    this.posterUrl = fixUrlNull(posterUrl)
                    this.plot = plot
                    this.year = year
                    this.showStatus = status
                    this.tags = genres
                    this.recommendations = recommendations
                }
            } else {
                val type = getLinkInfo(null, detailUrl)?.type ?: TvType.AsianDrama
                Log.i(name, "$title identified as ${type.name}")

                val episodes = episodeElements.mapNotNull { epElement ->
                    try {
                        val aTag = epElement.selectFirst("a") ?: return@mapNotNull null
                        val epLink = aTag.attr("href")
                        if (epLink.isNullOrBlank()) return@mapNotNull null

                        val epTitle = epElement.selectFirst("h3.title")?.text()?.trim()
                        val epNum = epTitle.extractEpisodeNumber()

                        Episode(
                            data = fixUrl(epLink),
                            name = epTitle ?: "Episode ${epNum ?: '?'}",
                            episode = epNum,
                        )
                    } catch (e: Exception) {
                        Log.e(name, "Error processing episode element: ${epElement.html()?.take(100)}...", e) // Correct Log.e
                        null
                    }
                }.reversed()

                Log.i(name, "Total episodes found: ${episodes.size}")

                newTvSeriesLoadResponse(title, detailUrl, type, episodes) {
                    this.posterUrl = fixUrlNull(posterUrl)
                    this.plot = plot
                    this.year = year
                    this.showStatus = status
                    this.tags = genres
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error loading details page: $detailUrl", e) // Correct Log.e
            null
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(name, "Loading links for episode watch page: $data")
        if (data.contains("/drama-detail/", ignoreCase = true)) {
             Log.e(name, "loadLinks received a detail page URL instead of an episode page URL: $data. Aborting.")
             return false
        }

        var sourcesLoaded = false
        try {
            val fixedDataUrl = fixUrl(data)
            val document = cfKiller(fixedDataUrl).document

            val servers = document.select("div.muti_link ul li[data-video]")

            if (servers.isNotEmpty()) {
                Log.d(name, "Found ${servers.size} server tabs from div.muti_link")
                // Use apmapNotNull, specify lambda parameter type, return Boolean result of loadExtractor
                val results = servers.apmapNotNull { serverElement: Element ->
                    try {
                        val serverName = serverElement.ownText().trim().ifBlank { serverElement.text().replace("Choose this server", "").trim() }
                        var videoUrl = serverElement.attr("data-video")

                        if (videoUrl.isBlank()) {
                             Log.w(name, "Server '$serverName' has blank data-video. Skipping.")
                             return@apmapNotNull false // Indicate failure for this server
                        }

                        if (!videoUrl.startsWith("http")) videoUrl = fixUrl(videoUrl)

                        Log.i(name, "Processing Server: '$serverName', Embed URL: '$videoUrl'")

                        // Return the result of loadExtractor directly (Boolean: true if attempted)
                        loadExtractor(videoUrl, fixedDataUrl, subtitleCallback, callback)
                    } catch (e: Exception) {
                         Log.e(name, "Error processing server ${serverElement.text()}", e) // Correct Log.e
                         false // Indicate failure
                    }
                }
                // Check if *any* of the loadExtractor calls returned true (meaning they attempted extraction)
                sourcesLoaded = results.any { it }

            } else {
                 Log.d(name, "No server tabs found in div.muti_link, looking for default player iframe")
                 val iframe = document.selectFirst("div.watch_video.watch-iframe iframe[src], #block-tab-video iframe[src]")
                 val iframeUrl = iframe?.attr("src")

                 if (!iframeUrl.isNullOrBlank()) {
                    val fixedIframeUrl = fixUrl(iframeUrl)
                    Log.i(name, "Found default player iframe URL: $fixedIframeUrl")
                    loadExtractor(fixedIframeUrl, fixedDataUrl, subtitleCallback, callback).also { sourcesLoaded = it }
                 } else {
                     Log.w(name, "No servers or default player iframe found on episode page $fixedDataUrl")
                 }
            }

            if (!sourcesLoaded) {
                 Log.w(name, "No sources were successfully processed or found for $fixedDataUrl")
            }

        } catch (e: Exception) {
            Log.e(name, "Error in loadLinks for $data", e) // Correct Log.e
        }
        return sourcesLoaded
    }

    // --- Helper Functions ---
    private fun fixUrlNull(url: String?): String? {
        return url?.let { fixUrl(it) }
    }

    // No override keyword needed for helper functions within the class
    fun fixUrl(url: String): String {
        // Extract the base domain (protocol + host) from mainUrl
        val currentDomain = Regex("""^(https?://[^/]+)""").find(mainUrl)?.groupValues?.get(1) ?: mainUrl
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> currentDomain + url
            !url.startsWith("http") -> "$currentDomain/$url" // Assume relative path if no scheme
            else -> url
        }.trim()
    }
} // End DramacoolProvider Class
