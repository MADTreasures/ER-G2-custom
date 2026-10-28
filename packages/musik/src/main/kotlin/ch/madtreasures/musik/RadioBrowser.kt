package ch.madtreasures.musik

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URLEncoder
import java.util.Locale

/**
 * The open station database radio-browser.info (https://api.radio-browser.info, free, without an account):
 * stations by name from all over the world, one station by its id, and the click a station gets when it is
 * played, which the database asks its clients to count. Several servers carry the same data; the app tries
 * them in turn ([SERVERS]), and every request names the app ([USER_AGENT]), as the database asks.
 */
object RadioBrowser {
    /** The name that spreads over all servers, then two of them directly, for when that name does not answer. */
    val SERVERS = listOf(
        "https://all.api.radio-browser.info",
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
    )

    const val USER_AGENT = "G2-Musik/1.0"

    /** Results shown for a search; the database is asked for more, as duplicates fall away. */
    const val MAX_RESULTS = 20
    private const val ASKED = 60

    /** Stations whose name contains [query], those listened to most first, without the ones that do not work. */
    fun searchPath(query: String): String =
        "/json/stations/search?name=${encode(query.trim())}&hidebroken=true&order=clickcount&reverse=true&limit=$ASKED"

    /** The station with this id, with its address as the database knows it now. */
    fun stationPath(uuid: String): String = "/json/stations/byuuid/${encode(uuid)}"

    /** Counts a click for the station (and answers with its address, which the app does not need). */
    fun clickPath(uuid: String): String = "/json/url/${encode(uuid)}"

    /**
     * The stations of an answer, in its order: those with a name and an http(s) address, each name of a
     * country once (the database often has a station several times, in different qualities), at most
     * [MAX_RESULTS]. Anything that is not a list of stations gives none.
     */
    fun stations(body: String): List<Station> {
        val list = try {
            Json.parseToJsonElement(body) as? JsonArray
        } catch (e: IllegalArgumentException) {
            null
        } ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Station>()
        for (e in list) {
            val s = station(e) ?: continue
            if (!seen.add(s.name.lowercase(Locale.ROOT) + "|" + s.country)) continue
            out += s
            if (out.size == MAX_RESULTS) break
        }
        return out
    }

    private fun station(e: JsonElement): Station? {
        val o = e as? JsonObject ?: return null
        fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
        val name = SPACES.replace(text("name"), " ").take(MAX_NAME)
        val url = text("url_resolved").ifEmpty { text("url") }
        if (name.isEmpty() || !Station.isStream(url)) return null
        val codec = text("codec").takeUnless { it.equals("UNKNOWN", ignoreCase = true) }.orEmpty()
        return Station(
            name = name,
            url = url,
            uuid = text("stationuuid").ifEmpty { null },
            country = text("countrycode").uppercase(Locale.ROOT),
            codec = codec,
            bitrate = (o["bitrate"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0,
        )
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private val SPACES = Regex("\\s+")
    private const val MAX_NAME = 80
}
