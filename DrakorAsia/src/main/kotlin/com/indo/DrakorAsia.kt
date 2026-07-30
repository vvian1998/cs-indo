package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Base64

class DrakorAsia : MainAPI() {
    override var mainUrl = "https://drakorid.co"
    override var name = "DrakorAsia"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/list/1" to "Drama Terbaru",
        "$mainUrl/drama-ongoing/" to "Ongoing",
        "$mainUrl/drama-populer/" to "Populer"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else {
            if (request.data.contains("/list/")) {
                request.data.replace(Regex("/list/\\d+"), "/list/$page")
            } else null
        } ?: return newHomePageResponse(request.name, emptyList())

        val document = app.get(url).document
        val items = document.select("a[href*=/nonton/]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.attr("title").ifBlank {
                a.selectFirst("h3, h4, strong, span")?.text()?.trim()
                    ?: return@mapNotNull null
            }
            val poster = a.selectFirst("img")?.attr("src")?.ifBlank { null }
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("a[href*=/nonton/]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = a.attr("title").ifBlank {
                a.text().trim().ifBlank { null } ?: return@mapNotNull null
            }
            val poster = a.selectFirst("img")?.attr("src")?.ifBlank { null }
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val document = response.document
        val html = response.text

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
            ?: document.selectFirst("h1, h2, h3")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
        val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val mTipe = Regex("var mTipe\\s*=\\s*(\\d+)").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val tags = document.select("a[href*=/kategori/]").map { it.text() }.filter { it.isNotBlank() }

        val isSeries = mTipe == 2
        return if (isSeries) {
            val episodes = (1..100).map { ep ->
                newEpisode("$url?ep=$ep") {
                    name = "Episode $ep"
                    episode = ep
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.substringAfter("/nonton/").substringBefore("/").substringBefore("?")
        val epNum = Regex("[?&]ep=(\\d+)").find(data)?.groupValues?.getOrNull(1) ?: "1"

        val watchUrl = "$mainUrl/watch-tonton/$slug/$epNum"
        val html = app.get(watchUrl).text

        val videoUrl = Regex("""src="[^"]*bunny\.php[^"]*\bv=([a-zA-Z0-9+/=]+)""").find(html)?.groupValues?.getOrNull(1)
            ?.let { runCatching { String(Base64.getDecoder().decode(it)) }.getOrNull() }

        if (videoUrl != null) {
            callback(newExtractorLink("DrakorAsia", "HD", videoUrl) {
                this.referer = watchUrl
                this.quality = Qualities.P1080.value
            })
        }

        return true
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return "$mainUrl$url"
    }
}
