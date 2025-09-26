package com.pelisplushd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.metaproviders.TraktProvider
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONArray
import org.json.JSONObject

class Pelisplushd : TraktProvider() {
    override var name = "Pelisplushd"
    override var mainUrl = "https://embed69.org" //https://pelisplushd.bz/
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    override var lang = "mx"
    override val supportedSyncNames = setOf(SyncIdName.Trakt)
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val traktApiUrl = base64Decode("aHR0cHM6Ly9hcGl6LnRyYWt0LnR2")

    override val mainPage =
        mainPageOf(
            "$traktApiUrl/movies/trending?extended=cloud9,full&limit=25" to "Trending Movies",
            "$traktApiUrl/movies/popular?extended=cloud9,full&limit=25" to "Popular Movies",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25" to "Trending Shows",
            "$traktApiUrl/shows/popular?extended=cloud9,full&limit=25" to "Popular Shows",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=53,1465" to "Netflix",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=47,2385" to "Amazon Prime Video",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=256" to "Apple TV+",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=41,2018,2566,2567,2597" to "Disney+",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=87" to "Hulu",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=1623" to "Paramount+",
            "$traktApiUrl/shows/trending?extended=cloud9,full&limit=25&network_ids=550,3027" to "Peacock",
        )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataObj = AppUtils.parseJson<LinkData>(data)
        val season = dataObj.season
        val episode = dataObj.episode
        val id = dataObj.imdbId
        val iframe=if(season==null) { "$mainUrl/f/$id" } else { "$mainUrl/f/$id-${season}x0$episode" }
        val res= app.get(iframe).document
        val jsonString = res.selectFirst("script:containsData(dataLink)")?.data()?.substringAfter("dataLink = ")?.substringBefore(";")
        Log.d("Phisher",jsonString.toString())
        if (jsonString != null) {
            val jsonArray = JSONArray(jsonString)
            val resultsByLanguage = mutableMapOf<String, MutableList<JSONObject>>()
            for (i in 0 until jsonArray.length()) {
                val fileObject = jsonArray.getJSONObject(i)
                val language = fileObject.getString("video_language")
                val embeds = fileObject.getJSONArray("sortedEmbeds")

                val listForLang = resultsByLanguage.getOrPut(language) { mutableListOf() }
                for (j in 0 until embeds.length()) {
                    val embedObj = embeds.getJSONObject(j)

                    val result = JSONObject()
                    result.put("servername", embedObj.getString("servername"))
                    result.put("link", embedObj.getString("link"))
                    listForLang.add(result)
                }
            }

            resultsByLanguage.forEach { (lang, servers) ->
                println("Language: $lang")
                servers.forEach { serverObj ->
                    val servername=serverObj.getString("servername")
                    val encryptedLink = serverObj.getString("link")
                    try {
                        val decrypted = decryptLink(encryptedLink)
                        loadCustomExtractor("$servername $lang".capitalize(),decrypted,"",subtitleCallback,callback)
                        println("${serverObj.getString("servername")} -> $decrypted")
                    } catch (e: Exception) {
                        println("[ERROR] ${serverObj.getString("servername")} -> ${e.message}")
                    }
                }
            }


        } else {
            println("dataLink not found in response")
        }
        // Subtitles
        val subApiUrl = "https://opensubtitles-v3.strem.io"
        val url = if (season == null) "$subApiUrl/subtitles/movie/$id.json"
        else "$subApiUrl/subtitles/series/$id:$season:$episode.json"

        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        )

        app.get(url, headers = headers, timeout = 100L)
            .parsedSafe<Subtitles>()?.subtitles?.amap {
                val lan = getLanguage(it.lang) ?: it.lang
                subtitleCallback(
                    SubtitleFile(
                        lan,
                        it.url
                    )
                )
            }

        return true
    }
}

data class Subtitles(
    val subtitles: List<Subtitle>,
    val cacheMaxAge: Long,
)

data class Subtitle(
    val id: String,
    val url: String,
    @JsonProperty("SubEncoding")
    val subEncoding: String,
    val lang: String,
    val m: String,
    val g: String,
)
