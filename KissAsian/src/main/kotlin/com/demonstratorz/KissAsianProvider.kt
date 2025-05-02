package com.lagradost.cloudstream3.providers // Correct package assumed

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // Wildcard import kept for brevity
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.mapper.parseJson // Added import
import com.lagradost.cloudstream3.utils.Coroutines.apmapNotNull // Added import

class DramacoolProvider : MainAPI() {
    // Provider metadata
    override var mainUrl = "https://asianctv.co"
    override var name = "Dramacool"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
        TvType.TvShow // Correct usage
    )

    // Companion object for utilities
    companion object {
        // Cloudflare bypass utility
        private suspend fun cfKiller(url: String): NiceResponse {
            // No changes needed here, assuming app.get handles CF correctly
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
            val aTag = element?.selectFirst("a.img") ?: element?.selectFirst("a") // Use selectFirst
            val url = initialUrl ?: aTag?.attr("href") ?: return null
            if (url.isBlank()) return null

            val typeSpan = element?.selectFirst("span.type")?.text()?.uppercase()
            val h3Title = element?.selectFirst("h3.title")?.text()

            val type = when {
                url.contains("/movie/", ignoreCase = true) || url.contains("-movie-", ignoreCase = true) -> TvType.Movie
                url.contains("/kshow/", ignoreCase = true) || typeSpan == "KSHOW" -> TvType.TvShow // Correct usage
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
        // Pass 'provider' explicitly for fixUrl access if needed outside instance methods
        suspend fun getDetailUrlFromEpisode(episodeUrl: String, providerInstance: DramacoolProvider): String? {
            return try {
                val fixedEpisodeUrl = providerInstance.fixUrl(episodeUrl) // Use instance fixUrl
                Log.d(providerInstance.name, "Fetching episode page to find detail URL: $fixedEpisodeUrl")
                val episodeDoc = cfKiller(fixedEpisodeUrl).document
                val detailLink = episodeDoc.selectFirst("div.info-drama div.category a[href*='/drama-detail/']")?.attr("href")
                    ?: episodeDoc.select("script[type=\"application/ld+json\"]").mapNotNull { script -> // Explicit type Element -> JsonLdSchema?
                            try {
                                val json = script.data()
                                if (json.contains("BreadcrumbList") && json.contains("/drama-detail/")) {
                                    // Use parseJson with proper error handling
                                    val parsedList = mapper.readValue<List<JsonLdSchema>>(json)
                                    parsedList.find { schema -> schema.`@type` == "BreadcrumbList" }
                                        ?.itemListElement
                                        ?.find { listItem -> listItem.item?.contains("/drama-detail/") == true }
                                        ?.item
                                } else null
                            } catch (e: Exception) {
                                Log.e(providerInstance.name, "JSON-LD parsing failed for script: ${script.data().take(100)}...", e)
                                null // Ignore parsing errors
                            }
                        }.firstOrNull() // Take the first valid one found

                if (detailLink != null) {
                    Log.d(providerInstance.name, "Found detail URL: $detailLink from episode page: $fixedEpisodeUrl")
                } else {
                     Log.w(providerInstance.name, "Could not find detail URL on episode page: $fixedEpisodeUrl")
                }
                detailLink
            } catch (e: Exception) {
                // Use correct Log.e signature
                Log.e(providerInstance.name, "Failed to get detail URL from episode page $episodeUrl", e)
                null
            }
        }

        // Helper class for JSON-LD parsing
        // Add @JsonProperty if names differ significantly or have special characters
        data class JsonLdSchema(
            @com.fasterxml.jackson.annotation.JsonProperty("@context") val context: String? = null,
            @com.fasterxml.jackson.annotation.JsonProperty("@type") val type: String? = null,
            val itemListElement: List<ListItemSchema>? = null
        )

        data class ListItemSchema(
             @com.fasterxml.jackson.annotation.JsonProperty("@type") val type: String? = null,
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
            val items = document.select("ul.list-episode-item li, ul.list-drama li").apmapNotNull { element: Element -> // Explicit type
                try {
                    val initialLinkInfo = getLinkInfo(element) ?: return@apmapNotNull null
                    val posterUrl = element.selectFirst("img.lazy")?.attr("data-original") // Use selectFirst
                                    ?: element.selectFirst("img")?.attr("src")

                    val (finalTitle: String, detailUrl: String?) = if (initialLinkInfo.isDirectEpisodeLink) {
                        // Pass 'this' instance to the companion function
                        val canonicalUrl = getDetailUrlFromEpisode(initialLinkInfo.url, this)
                        val showTitle = element.selectFirst("h3.title")?.text() // Use selectFirst
                            ?.replace(Regex("""\s+Episode\s+\d+$""", RegexOption.IGNORE_CASE), "")
                            ?.replace(Regex("""\s+EP\s+\d+$""", RegexOption.IGNORE_CASE), "")
                            ?.trim() ?: "Unknown Show"
                        Pair(showTitle, canonicalUrl)
                    } else {
                        val showTitle = element.selectFirst("h3.title")?.text()?.trim() ?: "Unknown" // Use selectFirst
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
                        // Use the imported newTvShowSearchResponse
                        else -> newTvShowSearchResponse(finalTitle, fixUrl(detailUrl), initialLinkInfo.type) {
                            this.posterUrl = fixUrlNull(posterUrl) // 'this' refers to the response being built
                        }
                    }
                } catch (e: Exception) {
                    // Use correct Log.e signature
                    Log.e(name, "Error processing main page element: ${element.html()?.take(100)}...", e)
                    null
                }
            }

            val hasNextPage = document.selectFirst("ul.pagination li.next a, div.pagination a.next, li.next > a") != null // Use selectFirst

            newHomePageResponse(list = HomePageList(request.name, items), hasNext = hasNextPage)
        } catch (e: Exception) {
            // Use correct Log.e signature
            Log.e(name, "Error getting main page: $pageUrl", e)
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
                     val posterUrl = element.selectFirst("img.lazy")?.attr("data-original") // Use selectFirst
                                    ?: element.selectFirst("img")?.attr("src")

                     val (finalTitle: String, detailUrl: String?) = if (initialLinkInfo.isDirectEpisodeLink) {
                         Log.w(name, "Search result link is direct episode: ${initialLinkInfo.url}. Resolving...")
                         val canonicalUrl = getDetailUrlFromEpisode(initialLinkInfo.url, this) // Pass 'this' instance
                         val showTitle = element.selectFirst("h3.title")?.text() // Use selectFirst
                            ?.replace(Regex("""\s+Episode\s+\d+$""", RegexOption.IGNORE_CASE), "")
                            ?.replace(Regex("""\s+EP\s+\d+$""", RegexOption.IGNORE_CASE), "")
                            ?.trim() ?: "Unknown Show"
                         Pair(showTitle, canonicalUrl)
                     } else {
                         val showTitle = element.selectFirst("h3.title")?.text()?.trim() ?: "Unknown" // Use selectFirst
                         Pair(showTitle, initialLinkInfo.url)
                     }

                    if (detailUrl.isNullOrBlank()) {
                        Log.w(name, "Could not resolve detail URL for search result ${initialLinkInfo.url}")
                        return@apmapNotNull null
                    }

                    Log.d(name, "Found Search Result: Title='$finalTitle', URL='$detailUrl', Type=${initialLinkInfo.type}")

                     when (initialLinkInfo.type) {
                         TvType.Movie -> newMovieSearchResponse(finalTitle, fixUrl(detailUrl), initialLinkInfo.type) { this.posterUrl = fixUrlNull(posterUrl) }
                         // Use imported newTvShowSearchResponse
                         else -> newTvShowSearchResponse(finalTitle, fixUrl(detailUrl), initialLinkInfo.type) { this.posterUrl = fixUrlNull(posterUrl) } // Correct usage
                     }
                } catch (e: Exception) {
                    // Use correct Log.e signature
                    Log.e(name, "Error processing search result element: ${element.html()?.take(100)}...", e)
                    null
                }
            }
        } catch (e: Exception) {
            // Use correct Log.e signature
            Log.e(name, "Error during search for '$query'", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.i(name, "Attempting to load details page: $url")
        val detailUrl = if (!url.contains("/drama-detail/", ignoreCase = true)) {
             Log.w(name, "Load function called with non-detail URL: $url. Attempting to recover...")
             val recovered = getDetailUrlFromEpisode(url, this) // Pass 'this' instance
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

            val title = document.selectFirst(".details .info h1")?.text()?.trim() // Use selectFirst
                        ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
                        ?: return null

            val posterUrl = document.selectFirst(".details .thumbnail img")?.attr("src") // Use selectFirst
                         ?: document.selectFirst(".details .img img")?.attr("src") // Use selectFirst
                         ?: document.selectFirst("meta[property='og:image']")?.attr("content")

            val plot = document.selectFirst("div.info p:has(strong:containsOwn(Description)) + p")?.text()?.trim() // Use selectFirst
                       ?: document.selectFirst(".details .info p:not(:has(strong)):not(:contains(Other name))")?.text() // Use selectFirst
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

            val recommendations = document.select("ul.list-episode-item li").mapNotNull { recElement -> // Use mapNotNull (parallel not needed here)
                 try {
                    val linkInfoRec = getLinkInfo(recElement) ?: return@mapNotNull null
                    if (linkInfoRec.isDirectEpisodeLink) return@mapNotNull null
                    val recTitle = recElement.selectFirst("h3.title")?.text()?.trim() ?: return@mapNotNull null
                    val recPoster = recElement.selectFirst("img.lazy")?.attr("data-original") ?: recElement.selectFirst("img")?.attr("src")

                    when (linkInfoRec.type) {
                        TvType.Movie -> newMovieSearchResponse(recTitle, fixUrl(linkInfoRec.url), linkInfoRec.type) { this.posterUrl = fixUrlNull(recPoster) }
                        else -> newTvShowSearchResponse(recTitle, fixUrl(linkInfoRec.url), linkInfoRec.type) { this.posterUrl = fixUrlNull(recPoster) } // Correct usage
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
                    this.showStatus = status // Correct field name
                    this.tags = genres
                    this.recommendations = recommendations
                }
            } else {
                val type = getLinkInfo(null, detailUrl)?.type ?: TvType.AsianDrama
                Log.i(name, "$title identified as ${type.name}")

                val episodes = episodeElements.mapNotNull { epElement ->
                    try {
                        val aTag = epElement.selectFirst("a") ?: return@mapNotNull null // Use selectFirst
                        val epLink = aTag.attr("href")
                        if (epLink.isNullOrBlank()) return@mapNotNull null

                        val epTitle = aTag.selectFirst("h3.title")?.text()?.trim() // Use selectFirst
                        val epNum = epTitle.extractEpisodeNumber()

                        Episode(
                            data = fixUrl(epLink),
                            name = epTitle ?: "Episode ${epNum ?: '?'}",
                            episode = epNum,
                        )
                    } catch (e: Exception) {
                         // Use correct Log.e signature
                        Log.e(name, "Error processing episode element: ${epElement.html()?.take(100)}...", e)
                        null
                    }
                }.reversed()

                Log.i(name, "Total episodes found: ${episodes.size}")

                newTvSeriesLoadResponse(title, detailUrl, type, episodes) {
                    this.posterUrl = fixUrlNull(posterUrl)
                    this.plot = plot
                    this.year = year
                    this.showStatus = status // Correct field name
                    this.tags = genres
                    this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
             // Use correct Log.e signature
            Log.e(name, "Error loading details page: $detailUrl", e)
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
                servers.apmapNotNull { serverElement : Element -> // Explicit type, use apmapNotNull
                    try {
                        val serverName = serverElement.ownText().trim().ifBlank { serverElement.text().replace("Choose this server", "").trim() }
                        var videoUrl = serverElement.attr("data-video")

                        if (videoUrl.isBlank()) {
                             Log.w(name, "Server '$serverName' has blank data-video. Skipping.")
                             return@apmapNotNull false // Indicate failure for this server
                        }

                        if (!videoUrl.startsWith("http")) videoUrl = fixUrl(videoUrl)

                        Log.i(name, "Processing Server: '$serverName', Embed URL: '$videoUrl'")

                        // Return the result of loadExtractor directly
                        loadExtractor(videoUrl, fixedDataUrl, subtitleCallback, callback)
                    } catch (e: Exception) {
                         // Use correct Log.e signature
                         Log.e(name, "Error processing server ${serverElement.text()}", e)
                         false // Indicate failure
                    }
                }.any { it }.also { sourcesLoaded = it } // Check if *any* server loading succeeded
            } else {
                 Log.d(name, "No server tabs found in div.muti_link, looking for default player iframe")
                 val iframe = document.selectFirst("div.watch_video.watch-iframe iframe[src], #block-tab-video iframe[src]") // Use selectFirst
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
                 Log.w(name, "No extractor links were successfully loaded for $fixedDataUrl")
            }

        } catch (e: Exception) {
            // Use correct Log.e signature
            Log.e(name, "Error in loadLinks for $data", e)
        }
        return sourcesLoaded
    }

    // --- Helper Functions ---
    private fun fixUrlNull(url: String?): String? {
        return url?.let { fixUrl(it) }
    }

    // No override keyword needed here
    fun fixUrl(url: String): String {
        val currentDomain = Regex("""^(https?://[^/]+)""").find(mainUrl)?.groupValues?.get(1) ?: mainUrl
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> currentDomain + url
            !url.startsWith("http") -> "$currentDomain/$url"
            else -> url
        }.trim()
    }
}

// ======================================================================
// IMPORTANT: You also need a plugin registration file.
// Create a file named DramacoolProviderPlugin.kt (or similar)
// in the same directory or a parent directory recognised by your build setup.
// ======================================================================

/* Example: DramacoolProviderPlugin.kt */
package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DramacoolProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner
        registerMainAPI(DramacoolProvider()) // Register the fixed provider
    }
}
