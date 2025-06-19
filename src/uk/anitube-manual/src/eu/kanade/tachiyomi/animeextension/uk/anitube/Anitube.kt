package eu.kanade.tachiyomi.animeextension.uk.anitube

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

abstract class Anitube(

) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val lang = "uk"
    override val name = "Anitube"
    override val supportsLatest = true
    private val animeSelector = "article.story"
    private val nextPageSelector = "div.navigation span.rcol a"

    override val baseUrl = "https://anitube.in.ua"

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        val animeInfo = document.select("div.rcol")

        anime.thumbnail_url = baseUrl + document.select("span.story_post img").attr("src")
        anime.title = animeInfo.select("h2").first()?.text().toString()
        val url = animeInfo.select("h2 > a").attr("href")
//        if we are parsing results we have h2 > a if we are already on the page we might get empty and replace the url
        anime.setUrlWithoutDomain(url.ifEmpty { document.location() })
        anime.description = animeInfo.select("div.my-text").text()

        val genresTag = document.select("strong:contains(Жанр:) + a").first()

        anime.genre = ""
        if (genresTag != null) {
            var current = genresTag.nextElementSibling()

            while (current != null && current.tagName() != "hr") {
                if (current.tagName() == "a") {
                    anime.genre += current.text() + ", "
                }
                current = current.nextElementSibling()
            }
            anime.genre?.trim()?.trimEnd(',')
        }

        anime.author = document.select("strong:contains(Режисер:)").first()?.nextSibling()?.toString()?.trim() ?: "N/A"
        anime.artist = document.select("strong:contains(Студія:)").first()?.nextSibling()?.toString()?.trim() ?: "N/A"
        val episodeText = document.select("strong:contains(Серій:)").first()?.nextSibling()?.toString()?.trim() ?: "N/A"
        val match = Regex("(\\d+)\\s+з\\s+(\\d+)").find(episodeText)

//        UNKNOWN = 0
//        ONGOING = 1
//        COMPLETED = 2
//        LICENSED = 3
//        PUBLISHING_FINISHED = 4
//        CANCELLED = 5
//        ON_HIATUS = 6

        anime.status = if (match != null && match.groupValues[1] == match.groupValues[2]) 2 else 1
        return anime
    }

    // ============================== Popular ===============================

    // override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun popularAnimeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun popularAnimeNextPageSelector() = throw UnsupportedOperationException()
    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()
    override fun popularAnimeSelector(): String = throw UnsupportedOperationException()

    // =============================== Latest ===============================

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()

//        this will always be h2 > a
        anime.setUrlWithoutDomain(element.select("h2 > a").attr("href"))
        anime.thumbnail_url = baseUrl + element.select("span.story_post > img").attr("src")
        anime.title = element.select("h2 > a").first()?.text()!!
        return anime
    }

    override fun latestUpdatesNextPageSelector() = nextPageSelector

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/page/$page", headers)

    override fun latestUpdatesSelector() = animeSelector

    // =============================== Search ===============================

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector() = nextPageSelector

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("full_search", "1")
            .add("result_from", "1")
            .add("story", query)
            .build()
        return POST("$baseUrl/anime/index.php?do=search", body = body)
    }

    override fun searchAnimeSelector() = animeSelector

    // ============================== Episode ===============================

//    override fun episodeFromElement(element: Element): SEpisode {
//        val episode = SEpisode.create()
//        Log.d("episodeListElement", element.toString())
//        episode.url = element.attr("data-file")
//        episode.name = element.text()
//        episode.episode_number = element.text().split(" ").first().toFloat()
//        Log.d("episodeListEpisode", episode.toString())
//        return episode
//    }
//    override fun episodeListSelector() = ".playlists-videos .playlists-items ul > li[style*=display: inline-block]"

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        val htmlScripts = document.select("script")
        val hashRegex = """var\s+dle_login_hash\s*=\s*'([a-f0-9]+)'""".toRegex()
        var dleLoginHash: String? = null
        for (script in htmlScripts) {
            val match = hashRegex.find(script.data())
            if (match != null) {
                dleLoginHash = match.groupValues[1]
                break
            }
        }
        Log.d("dleLoginHash", dleLoginHash.toString())

        val playerRegex = Regex("""https://anitube\.in\.ua/(\d+)-""")
        val animeId = playerRegex.find(document.location())?.groupValues?.get(1)
        Log.d("animeId", animeId.toString())

        val playerUrl =
            "$baseUrl/engine/ajax/playlists.php?news_id=$animeId&xfield=playlist&user_hash=$dleLoginHash"

        val player = Jsoup.parse(
            JSONObject(
                client.newCall(GET(playerUrl))
                    .execute().body.string(),
            )
                .getString("response"),
        )

        Log.d("player", player.toString())
        val episodeListElements = player.select(".playlists-videos .playlists-items ul > li").toList()

        Log.d("episodeListElementParse", episodeListElements.toString())

//        todo collate episodes
        val episodeList = player.select(".playlists-videos .playlists-items  ul > li").toList()
            .mapIndexed { index, element ->
                if (element.attr("data-file") ) { SEpisode.create().apply {
                    name = element.text()
                    url = element.attr("data-file")
                    episode_number = index + 1f
                } }
            }
            .reversed()
        Log.d("episodeListParse", episodeList.toString())
        return episodeList
    }

    // ============================ Video ===============================

//    todo figure out how to change sources
    override fun videoFromElement(element: Element): Video {
        val videoUrl = element.attr("src")
        return Video(
            url = videoUrl,
            quality = "Default",
            videoUrl = videoUrl,
        )
    }

    override fun videoListSelector(): String = "div.playlist-iframe iframe"

    override fun videoUrlParse(document: Document): String {
        return document.selectFirst("div.playlist-iframe iframe")?.absUrl("src") ?: ""
    }
}
