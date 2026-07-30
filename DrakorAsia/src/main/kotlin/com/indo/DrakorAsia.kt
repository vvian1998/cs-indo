package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Base64

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
        val url = if (request.data.contains("/list/")) {
            request.data.replace(Regex("/list/\\d+"), "/list/$page")
        } else request.data

        val document = app.get(url).document
        val items = document.select("article.movie-list-card a.movie-list-card__media").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val img = a.selectFirst("img.movie-list-card__img")
            val title = img?.attr("alt")?.ifBlank { null }
                ?: a.parent()?.selectFirst(".movie-list-card__title")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = img?.attr("src")?.ifBlank { null }
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("article.movie-list-card a.movie-list-card__media").mapNotNull { a ->
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val img = a.selectFirst("img.movie-list-card__img")
            val title = img?.attr("alt")?.ifBlank { null }
                ?: a.parent()?.selectFirst(".movie-list-card__title")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = img?.attr("src")?.ifBlank { null }
            newMovieSearchResponse(title, href, TvType.Movie) {
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
        val tags = document.select("a[href*=/kategori/]").map { it.text() }.filter { it.isNotBlank() }

        val mTipe = Regex("var mTipe\\s*=\\s*(\\d+)").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val isSeries = mTipe == 2

        return if (isSeries) {
            val totalEp = document.selectFirst(".episode-select-sub")?.text()
                ?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: 16
            val episodes = (1..totalEp).map { ep ->
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

        val hlsUrl = Regex("""src="[^"]*bunny\.php[^"]*\bv=([a-zA-Z0-9+/=]+)""").find(html)
            ?.groupValues?.getOrNull(1)
            ?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() }

        if (hlsUrl != null) {
            callback(newExtractorLink("DrakorAsia", "HD", hlsUrl, type = ExtractorLinkType.M3U8) {
                this.referer = watchUrl
            })
            val playlistUrl = hlsUrl.replace("mono.m3u8", "playlist.m3u8")
            if (playlistUrl != hlsUrl) {
                callback(newExtractorLink("DrakorAsia", "HD+", playlistUrl, type = ExtractorLinkType.M3U8) {
                    this.referer = watchUrl
                })
            }
        }

        return true
    }
}
