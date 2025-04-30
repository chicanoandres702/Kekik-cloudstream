package com.demonstratorz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Data Models
data class KissasianEpisodeData(
    @JsonProperty("url") val url: String,
    @JsonProperty("episodeNumber") val episodeNumber: Int,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("type") val type: String = "sub",
    @JsonProperty("quality") val quality: Int? = null,
    @JsonProperty("posterUrl") val posterUrl: String? = null
)

data class VideoSource(
    @JsonProperty("file") val file: String,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("type") val type: String? = null
)

object KissasianUtils {
    private val supportedStreams = listOf(
        "vidmoly", "streamtape", "mycloud"
    )

    private val embedBlacklist = listOf(
        "mp4upload.com",
        "streamsb.net",
        "dood.to",
        "videobin.co",
        "ok.ru"
    )

    fun getVideoType(url: String): String {
        return when {
            url.contains(".m3u8") -> "hls"
            url.contains(".mp4") -> "mp4"
            else -> "iframe"
        }
    }

    fun String.getHost(): String {
        return try {
            URI(this).host.substringBeforeLast(".").substringAfterLast(".")
        } catch (e: Exception) {
            this
        }
    }

    fun String.fixUrl(): String {
        if (this.isEmpty()) return ""
        return when {
            this.startsWith("//") -> "https:$this"
            this.startsWith("/") -> "https://kissasian.com.lv$this"
            !this.startsWith("http") -> "https://$this"
            else -> this
        }
    }

    fun String.cleanTitle(): String {
        return this.replace(Regex("""Episode \d+"""), "")
            .replace(Regex("""[$$$$].*?[$$$$]"""), "")
            .trim()
    }

    suspend fun extractVideos(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val host = url.getHost()

        when {
            url.contains(".m3u8") -> {
                M3u8Helper.generateM3u8(
                    source = host,
                    streamUrl = url,
                    referer = referer
                ).forEach { link ->
                    callback.invoke(link)
                    found = true
                }
            }
            supportedStreams.any { url.contains(it) } -> {
                loadExtractor(url, referer, subtitleCallback, callback)
                found = true
            }
            else -> {
                // Try direct link
                callback.invoke(
                    ExtractorLink(
                        source = host,
                        name = host,
                        url = url,
                        referer = referer,
                        quality = Qualities.Unknown.value
                    )
                )
                found = true
            }
        }
        return found
    }

    suspend fun extractFromIframe(
        document: org.jsoup.nodes.Document,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Look for direct video sources
        document.select("video source").forEach { source ->
            val videoUrl = source.attr("src").fixUrl()
            if (videoUrl.isNotEmpty() && !embedBlacklist.any { videoUrl.contains(it) }) {
                extractVideos(videoUrl, referer, subtitleCallback, callback)
                found = true
            }
        }

        // Look for m3u8 sources in scripts
        document.select("script").forEach { script ->
            val scriptData = script.data()
            val m3u8Regex = """["'](?:https?:)?//[^"']+\.m3u8[^"']*["']""".toRegex()
            m3u8Regex.findAll(scriptData).forEach { match ->
                val m3u8Url = match.value.trim('"', '\'').fixUrl()
                if (!embedBlacklist.any { m3u8Url.contains(it) }) {
                    extractVideos(m3u8Url, referer, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // Look for JSON data in scripts
        document.select("script").forEach { script ->
            val scriptData = script.data()
            if (scriptData.contains("sources")) {
                tryParseJson<List<VideoSource>>(
                    scriptData.substringAfter("sources = ").substringBefore("];") + "]"
                )?.forEach { source ->
                    extractVideos(source.file.fixUrl(), referer, subtitleCallback, callback)
                    found = true
                }
            }
        }

        return found
    }

    suspend fun searchAnime(
        query: String,
        headers: Map<String, String>
    ): List<SearchResponse> = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://kissasian.com.lv/?s=$encoded"
            
            app.get(searchUrl, headers = headers).document
                .select("#top > div > div.content > div.content-left > div > div.block.tab-container > div > ul > li")
                .mapNotNull { element ->
                    val title = element.selectFirst("a")?.attr("title") ?: return@mapNotNull null
                    val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val poster = element.selectFirst("img")?.attr("src")

                    newAnimeSearchResponse(
                        name = title.cleanTitle(),
                        url = href,
                        type = TvType.AsianDrama,
                    ) {
                        this.posterUrl = poster
                        addDubStatus(false, false)
                    }
                }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
