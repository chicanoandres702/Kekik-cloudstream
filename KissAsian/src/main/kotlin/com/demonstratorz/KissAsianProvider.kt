package com.lagradost.cloudstream3.providers // Use a standard provider package

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
// Necessary imports
import com.lagradost.cloudstream3.utils.* // Using wildcard for utils
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.M3u8Helper // Keep for reference, though not used directly here
import com.lagradost.cloudstream3.utils.ExtractorLink // Explicit import for clarity
import com.lagradost.cloudstream3.utils.loadExtractor // Explicit import for clarity

class DramacoolProvider : MainAPI() {
    // Provider metadata
    override var mainUrl = "https://asianctv.co"
    override var name = "Dramacool"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
        TvType.TvShow // For KShows
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
                url.contains("/kshow/", ignoreCase = true) || typeSpan == "KSHOW" -> TvType.TvShow
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
        suspend fun getDetailUrlFromEpisode(episodeUrl: String, provider: DramacoolProvider): String? {
            return try {
                val fixedEpisodeUrl = provider.fixUrl(episodeUrl)
                Log.d(provider.name, "Fetching episode page to find detail URL: $fixedEpisodeUrl")
                val episodeDoc = cfKiller(fixedEpisodeUrl).document
                // Look in "Category:" link first
                val detailLink = episodeDoc.selectFirst("div.info-drama div.category a[href*='/drama-detail/']")?.attr("href")
                    // Fallback to JSON-LD Breadcrumb schema
                    ?: episodeDoc.select("script[type=\"application/ld+json\"]").let { scripts ->
                            scripts.mapNotNull { script ->
                                try {
                                    val json = script.data()
                                    // Check if it's the BreadcrumbList schema
                                    if (json.contains("BreadcrumbList") && json.contains("/drama-detail/")) {
                                         parseJson<List<JsonLdSchema>>(json).find { it.`@type` == "BreadcrumbList" }
                                            ?.itemListElement
                                            // Find the element whose item contains /drama-detail/
                                            ?.find { it.item?.contains("/drama-detail/") == true }
                                            ?.item
                                    } else null
                                } catch (e: Exception) { null } // Ignore parsing errors
                            }.firstOrNull() // Take the first valid one found
                        }

                if (detailLink != null) {
                    Log.d(provider.name, "Found detail URL: $detailLink from episode page: $fixedEpisodeUrl")
                } else {
                     Log.w(provider.name, "Could not find detail URL on episode page: $fixedEpisodeUrl")
                }
                detailLink // Return the found link or null
            } catch (e: Exception) {
                Log.e(provider.name, "Failed to get detail URL from episode page $episodeUrl: ${e.message}", e)
                null
            }
        }

        // Helper class for JSON-LD parsing (simplified)
        data class JsonLdSchema(
            val `@context`: String? = null,
            val `@type`: String? = null,
            val itemListElement: List<ListItemSchema>? = null
        )

        data class ListItemSchema(
             val `@type`: String? = null,
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
            // Use apmapNotNull for potentially faster lookups when needing extra page fetches
            val items = document.select("ul.list-episode-item li, ul.list-drama li").apmapNotNull { element ->
                try {
                    val initialLinkInfo = getLinkInfo(element) ?: return@apmapNotNull null
                    val posterUrl = element.selectFirst("img.lazy")?.attr("data-original")
                                    ?: element.selectFirst("img")?.attr("src")

                    // Resolve the link to the main detail page if necessary
                    val (finalTitle: String, detailUrl: String?) = if (initialLinkInfo.isDirectEpisodeLink) {
                        val canonicalUrl = getDetailUrlFromEpisode(initialLinkInfo.url, this)
                        // Try to clean the title
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

                    // Create the appropriate SearchResponse pointing to the detail page
                    when (initialLinkInfo.type) {
                        TvType.Movie -> newMovieSearchResponse(finalTitle, fixUrl(detailUrl), initialLinkInfo.type) {
                            this.posterUrl = fixUrlNull(posterUrl)
                        }
                        else -> newTvShowSearchResponse(finalTitle, fixUrl(detailUrl), initialLinkInfo.type) {
                            this.posterUrl = fixUrlNull(posterUrl)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(name, "Error processing main page element: ${element.html()?.take(100)}... - ${e.message}", e)
                    null
                }
            }

            val hasNextPage = document.selectFirst("ul.pagination li.next a, div.pagination a.next, li.next > a") != null

            newHomePageResponse(list = HomePageList(request.name, items), hasNext = hasNextPage)
        } catch (e: Exception) {
            Log.e(name, "Error getting main page: $pageUrl - ${e.message}", e)
             HomePageResponse(emptyList(), false) // Fallback
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?keyword=${query}&type=movies"
        Log.i(name, "Searching for: '$query', URL: $searchUrl")

        return try {
            val document = cfKiller(searchUrl).document
            document.select("ul.list-episode-item li").apmapNotNull { element ->
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
                    Log.e(name, "Error processing search result element: ${element.html()?.take(100)}... - ${e.message}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error during search for '$query': ${e.message}", e)
            emptyList()
        }
    }

    // Load details - expects a /drama-detail/ URL
    override suspend fun load(url: String): LoadResponse? {
        Log.i(name, "Attempting to load details page: $url")
        // Ensure we have the correct detail page URL
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
                       ?: document.select(".details .info p:not(:has(strong)):not(:contains(Other name))").text()
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
                    if (linkInfoRec.isDirectEpisodeLink) return@mapNotNull null // Should link to detail page
                    val recTitle = recElement.selectFirst("h3.title")?.text()?.trim() ?: return@mapNotNull null
                    val recPoster = recElement.selectFirst("img.lazy")?.attr("data-original") ?: recElement.selectFirst("img")?.attr("src")

                    when (linkInfoRec.type) {
                        TvType.Movie -> newMovieSearchResponse(recTitle, fixUrl(linkInfoRec.url), linkInfoRec.type) { this.posterUrl = fixUrlNull(recPoster) }
                        else -> newTvShowSearchResponse(recTitle, fixUrl(linkInfoRec.url), linkInfoRec.type) { this.posterUrl = fixUrlNull(recPoster) }
                    }
                 } catch (e: Exception) { null }
            }.take(15)

            // Episode list selector confirmed from detail page HTML
            val episodeElements = document.select("ul.list-episode-item-2.all-episode li")

            if (episodeElements.isEmpty()) {
                // Assume MOVIE if no episodes found on the detail page
                Log.i(name, "$title identified as Movie (no episode list found)")
                // Use detailUrl for the data field in MovieLoadResponse
                newMovieLoadResponse(title, detailUrl, TvType.Movie, detailUrl) {
                    this.posterUrl = fixUrlNull(posterUrl)
                    this.plot = plot
                    this.year = year
                    this.showStatus = status
                    this.tags = genres
                    this.recommendations = recommendations
                }
            } else {
                // Assume TV Show / Drama / KShow if episodes are found
                val type = getLinkInfo(null, detailUrl)?.type ?: TvType.AsianDrama
                Log.i(name, "$title identified as ${type.name}")

                val episodes = episodeElements.mapNotNull { epElement ->
                    try {
                        val aTag = epElement.selectFirst("a") ?: return@mapNotNull null
                        val epLink = aTag.attr("href") // This is the crucial episode watch page URL
                        if (epLink.isNullOrBlank()) return@mapNotNull null

                        val epTitle = aTag.selectFirst("h3.title")?.text()?.trim()
                        val epNum = epTitle.extractEpisodeNumber()

                        // Create Episode object with the *watch page URL* in 'data'
                        Episode(
                            data = fixUrl(epLink),
                            name = epTitle ?: "Episode ${epNum ?: '?'}",
                            episode = epNum,
                        )
                    } catch (e: Exception) {
                        Log.e(name, "Error processing episode element: ${epElement.html()?.take(100)}... - ${e.message}", e)
                        null
                    }
                }.reversed() // Reverse to get chronological order (Ep1, Ep2...)

                Log.i(name, "Total episodes found: ${episodes.size}")

                // Create the TvSeriesLoadResponse
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
            Log.e(name, "Error loading details page: $detailUrl - ${e.message}", e)
            null
        }
    }

    // Load video links - expects an EPISODE watch page URL (like /...-episode-X.html)
    override suspend fun loadLinks(
        data: String, // This 'data' is the Episode.data field, which we set to the episode watch page URL in load()
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit // This callback expects ExtractorLink objects
    ): Boolean {
        Log.i(name, "Loading links for episode watch page: $data")
        // Safety check: Ensure this isn't accidentally a detail page URL
        if (data.contains("/drama-detail/", ignoreCase = true)) {
             Log.e(name, "loadLinks received a detail page URL instead of an episode page URL: $data. Aborting.")
             return false
        }

        var sourcesLoaded = false
        try {
            val fixedDataUrl = fixUrl(data) // The episode watch page URL
            val document = cfKiller(fixedDataUrl).document

            // Find server list using the selector from the episode watch page HTML
            val servers = document.select("div.muti_link ul li[data-video]")

            if (servers.isNotEmpty()) {
                Log.d(name, "Found ${servers.size} server tabs from div.muti_link")
                servers.apmap { serverElement -> // Process servers potentially in parallel
                    try {
                        val serverName = serverElement.ownText().trim().ifBlank { serverElement.text().replace("Choose this server", "").trim() }
                        var videoUrl = serverElement.attr("data-video") // This is the embed URL (iframe src)

                        if (videoUrl.isBlank()) {
                             Log.w(name, "Server '$serverName' has blank data-video. Skipping.")
                             return@apmap // Continue to next server
                        }

                        // Ensure the embed URL is absolute
                        if (!videoUrl.startsWith("http")) videoUrl = fixUrl(videoUrl)

                        Log.i(name, "Processing Server: '$serverName', Embed URL: '$videoUrl'")

                        // === HERE is where loadExtractor is used ===
                        // Pass the embed URL, the episode page URL as referer, and the callbacks.
                        // loadExtractor will find the actual stream links and call the 'callback'
                        // function with ExtractorLink objects (created internally using newExtractorLink or similar).
                        loadExtractor(videoUrl, fixedDataUrl, subtitleCallback, callback).also { extractorAttempted ->
                            if (extractorAttempted) {
                                // We don't know yet if it *succeeded*, but an attempt was made.
                                // Success is indicated by the 'callback' being invoked by loadExtractor.
                                Log.d(name, "loadExtractor attempted for $videoUrl")
                                // We set sourcesLoaded = true broadly if any extractor runs,
                                // assuming at least one might succeed. A more precise approach
                                // would involve tracking if 'callback' was actually invoked.
                                sourcesLoaded = true
                            } else {
                                 Log.w(name, "No extractor found for $videoUrl")
                            }
                        }
                    } catch (e: Exception) {
                         Log.e(name, "Error processing server ${serverElement.text()}: ${e.message}", e)
                    }
                }
            } else {
                 // Fallback: Look for a single player iframe if no server tabs were found
                 Log.d(name, "No server tabs found in div.muti_link, looking for default player iframe")
                 val iframe = document.selectFirst("div.watch_video.watch-iframe iframe[src], #block-tab-video iframe[src]")
                 val iframeUrl = iframe?.attr("src")

                 if (!iframeUrl.isNullOrBlank()) {
                    val fixedIframeUrl = fixUrl(iframeUrl)
                    Log.i(name, "Found default player iframe URL: $fixedIframeUrl")
                    // Use loadExtractor for the fallback iframe as well
                    loadExtractor(fixedIframeUrl, fixedDataUrl, subtitleCallback, callback).also { extractorAttempted ->
                        if(extractorAttempted) sourcesLoaded = true
                         Log.d(name, "loadExtractor attempted for fallback iframe $fixedIframeUrl")
                    }
                 } else {
                     Log.w(name, "No servers or default player iframe found on episode page $fixedDataUrl")
                 }
            }

            if (!sourcesLoaded) {
                 Log.w(name, "No extractor links were successfully loaded (or attempted) for $fixedDataUrl")
            }

        } catch (e: Exception) {
            Log.e(name, "Error in loadLinks for $data: ${e.message}", e)
        }
        // Return true if we attempted to load sources from *any* server/iframe found.
        // Cloudstream handles the case where the callback is never invoked (no links found).
        return sourcesLoaded
    }

    // --- Helper Functions ---
    private fun fixUrlNull(url: String?): String? {
        return url?.let { fixUrl(it) }
    }

    // Fix potential relative URLs
    override fun fixUrl(url: String): String {
        val currentDomain = Regex("""^(https?://[^/]+)""").find(mainUrl)?.groupValues?.get(1) ?: mainUrl
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> currentDomain + url
            !url.startsWith("http") -> "$currentDomain/$url" // Handle cases like "vidbasic.top/embed/..."
            else -> url
        }.trim()
    }
}
