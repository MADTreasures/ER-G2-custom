package ch.madtreasures.musik

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * A radio station: its name and the address of its stream. [uuid] is its entry at radio-browser.info, if it
 * has one: the app counts a click there when it plays the station and asks there for a new address when the
 * stream fails ([RadioBrowser]).
 */
data class Station(
    val name: String,
    val url: String,
    val uuid: String? = null,
    /** ISO country code, e.g. "CH"; empty when unknown. */
    val country: String = "",
    val codec: String = "",
    /** kbit/s; 0 when unknown. */
    val bitrate: Int = 0,
) {
    /** The same station, wherever it was found: its entry at radio-browser.info, else its address. */
    val key: String get() = uuid ?: url

    /** "MP3 128 kbit/s", or as much of it as is known. */
    val format: String
        get() = when {
            codec.isBlank() -> ""
            bitrate <= 0 -> codec
            else -> "$codec $bitrate kbit/s"
        }

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("url", url)
        uuid?.let { put("uuid", it) }
        if (country.isNotEmpty()) put("country", country)
        if (codec.isNotEmpty()) put("codec", codec)
        if (bitrate > 0) put("bitrate", bitrate)
    }

    companion object {
        /** A station as [toJson] wrote it; null for anything else. */
        fun fromJson(e: JsonElement?): Station? {
            val o = e as? JsonObject ?: return null
            fun text(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val name = text("name")?.takeIf { it.isNotBlank() } ?: return null
            val url = text("url")?.takeIf { isStream(it) } ?: return null
            return Station(
                name = name,
                url = url,
                uuid = text("uuid"),
                country = text("country").orEmpty(),
                codec = text("codec").orEmpty(),
                bitrate = (o["bitrate"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 0,
            )
        }

        /** The addresses the watch app plays (interface 6): http or https. */
        fun isStream(url: String) = (url.startsWith("https://") || url.startsWith("http://")) && url.length <= 2000
    }
}

/** Stations under a label, as the station list shows them. */
data class StationGroup(val label: String, val stations: List<Station>)

/**
 * The Swiss stations one hears on DAB+, here as their internet streams, all over https. Every address was
 * checked on 28 October 2026: it answered with MP3 or AAC and the station's name. A station whose address
 * stops working asks radio-browser.info for its new one ([uuid][Station.uuid]).
 */
object SwissStations {
    val groups: List<StationGroup> = listOf(
        StationGroup(
            "SRG SSR",
            listOf(
                srg("SRF 1", "srf1", "960e57c5-0601-11e8-ae97-52543be04c81"),
                srg("SRF 2 Kultur", "srf2", "960bbed9-0601-11e8-ae97-52543be04c81"),
                srg("SRF 3", "srf3", "961f1da4-0601-11e8-ae97-52543be04c81"),
                srg("SRF 4 News", "srf4news", "960ed987-0601-11e8-ae97-52543be04c81"),
                srg("SRF Musikwelle", "srfmw", "960dae40-0601-11e8-ae97-52543be04c81"),
                srg("SRF Virus", "srfvirus", "960b6cd6-0601-11e8-ae97-52543be04c81"),
                srg("Radio Swiss Pop", "rsp", "961ac635-0601-11e8-ae97-52543be04c81"),
                srg("Radio Swiss Jazz", "rsj", "961ac56b-0601-11e8-ae97-52543be04c81"),
                srg("Radio Swiss Classic", "rsc_de", "96077079-0601-11e8-ae97-52543be04c81"),
                srg("RTR Radio Rumantsch", "rr", "9608933a-0601-11e8-ae97-52543be04c81"),
                srg("RTS La Première", "la-1ere", "9608efd5-0601-11e8-ae97-52543be04c81"),
                srg("RTS Espace 2", "espace-2", "9607cf45-0601-11e8-ae97-52543be04c81"),
                srg("RTS Couleur 3", "couleur3", "9607cdda-0601-11e8-ae97-52543be04c81"),
                srg("RTS Option Musique", "option-musique", "9607cc5d-0601-11e8-ae97-52543be04c81"),
                srg("RSI Rete Uno", "reteuno", "96127e90-0601-11e8-ae97-52543be04c81"),
                srg("RSI Rete Due", "retedue", "96127f58-0601-11e8-ae97-52543be04c81"),
                srg("RSI Rete Tre", "retetre", "96127dce-0601-11e8-ae97-52543be04c81"),
            ),
        ),
        StationGroup(
            "Deutschschweiz",
            listOf(
                mp3("Energy Basel", "https://energybasel.ice.infomaniak.ch/energybasel-high.mp3", "1095cda6-6898-11ea-9d09-52543be04c81"),
                mp3("Energy Bern", "https://energybern.ice.infomaniak.ch/energybern-high.mp3", "489f3786-3ff8-46c1-ad14-2a7c5d9cea99"),
                mp3("Energy Luzern", "https://energyluzern.ice.infomaniak.ch/energyluzern-high.mp3", null),
                mp3("Energy Zürich", "https://energyzuerich.ice.infomaniak.ch/energyzuerich-high.mp3", "96069a5d-0601-11e8-ae97-52543be04c81"),
                mp3("Radio 1", "https://stream.radio1.ch/128k", "c815d308-da70-4e4e-b9a9-8aa1edb09951"),
                chMedia("Radio 24", "radio24", "3a19f974-53ee-4805-8432-4a592be49cd1"),
                chMedia("Radio 32", "radio32", "980d4891-994c-46d9-9096-4ed0eb556aa8"),
                chMedia("Radio Argovia", "argovia", "3bb2d57f-f7a6-49e7-afb5-89daf251de9d"),
                mp3("Radio Basilisk", "https://radiobasilisk-64.ice.infomaniak.ch/radiobasilisk-64-128-2.mp3", "9606b4de-0601-11e8-ae97-52543be04c81"),
                mp3("Radio BeO", "https://streaming.radiobeo.ch/beo/mp3_192/web", "6500ca13-51b8-4521-b35b-60bff718ef0a", 192),
                chMedia("Radio Bern1", "radiobern1", "95142051-a845-4523-a11a-2521e9ba0971"),
                chMedia("Radio Central", "rcentral", "a80bd23d-9bd2-43f7-8e2c-205695451589"),
                chMedia("Radio FM1", "radiofm1", "19aefe88-e993-4621-822e-53e131486611"),
                mp3("Radio Grischa", "https://somedia-4.streaming.deliver.media/radiogrischa_mp3_128", "db6893f4-cead-4749-83e6-a1b1dfb6f61b"),
                mp3("Radio Munot", "https://munot.ch-central-4.streaming.deliver.media/munot_mp3_128", "7f32184f-e7f0-46e6-a5e4-516daa062ec7"),
                mp3("Radio Neo1", "https://neo1.ice.infomaniak.ch/neo1.mp3", "4cde8f3b-17fc-4802-ba40-e8feae5395a1", 192),
                chMedia("Radio Pilatus", "radiopilatus", "df4c9476-1d7c-4cc8-8a63-d47148152dcf"),
                mp3("Radio Südostschweiz", "https://somedia-1.streaming.deliver.media/rso_mp3_128", "8c0913c9-063c-4d18-a8e7-eed8dd86c948"),
                chMedia("Radio Sunshine", "rsunshine", "6cb03e29-0706-445d-9a28-282e3c21dcd6"),
                Station("Radio Top", "https://radiotop.ice.infomaniak.ch/radiotop96.aac", "3e7dcb14-de8b-4c9c-85de-272148ff92a1", "CH", "AAC+", 96),
                mp3("Radio Zürisee", "https://mp3.radio.ch/radiozuerisee256k", "495a3710-7aae-4122-83b5-a49757c0a812", 256),
                chMedia("Virgin Radio", "vrock", "c1d4b62b-cb16-43ca-ae3a-41c2eb31b6e7"),
            ),
        ),
        StationGroup(
            "Romandie",
            listOf(
                mp3("LFM", "https://lausannefm.ice.infomaniak.ch/lausannefm-high.mp3", "52d101dc-3a83-4e4e-8505-8b235065fb0b"),
                mp3("One FM", "https://onefm.ice.infomaniak.ch/onefm-high.mp3", "96091d4c-0601-11e8-ae97-52543be04c81"),
                mp3("Radio Fribourg", "https://radiofribourg.ice.infomaniak.ch/radiofribourg-high.mp3", "e2af125a-58df-4264-9d82-589d96a7ab39"),
                mp3("Radio Lac", "https://radiolac.ice.infomaniak.ch/radiolac-high.mp3", "3718f163-5868-11e9-a622-52543be04c81"),
                mp3("Rhône FM", "https://rhonefm.ice.infomaniak.ch/rhonefm-high.mp3", "96055c03-0601-11e8-ae97-52543be04c81"),
            ),
        ),
    )

    /** All of them, in the order of the list. */
    val all: List<Station> = groups.flatMap { it.stations }

    /** The SRG SSR stream servers; they pass the request on to one of their nodes. */
    private fun srg(name: String, id: String, uuid: String) =
        Station(name, "https://stream.srg-ssr.ch/srgssr/$id/mp3/128", uuid, "CH", "MP3", 128)

    /** CH Media's stations, through streambase.ch. */
    private fun chMedia(name: String, id: String, uuid: String) =
        Station(name, "https://stream.streambase.ch/$id/mp3-192/direct/", uuid, "CH", "MP3", 192)

    private fun mp3(name: String, url: String, uuid: String?, bitrate: Int = 128) = Station(name, url, uuid, "CH", "MP3", bitrate)
}
