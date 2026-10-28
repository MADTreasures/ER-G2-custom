package ch.madtreasures.musik

import ch.madtreasures.g2watch.apps.media.GrayPng
import ch.madtreasures.g2watch.desktop.GrayRaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The station list, the answers of radio-browser.info, the stations as the app keeps them, the cut texts. */
class RadioBrowserTest {
    @Test
    fun `a search answer gives each station of a country once, with a playable address`() {
        val found = RadioBrowser.stations(JAZZ)
        assertEquals(
            listOf(
                Station("Radio Swiss Jazz", "http://stream.srg-ssr.ch/m/rsj/mp3_128", "961ac56b-0601-11e8-ae97-52543be04c81", "CH", "MP3", 128),
                Station("Jazz Radio", "http://jazz-wr04.ice.infomaniak.ch/jazz-wr04-128.mp3", "9617a958-0601-11e8-ae97-52543be04c81", "FR", "", 0),
            ),
            found,
        )
        assertTrue(RadioBrowser.stations("Service Unavailable").isEmpty())
        assertTrue(RadioBrowser.stations("""{"error":"nope"}""").isEmpty())
    }

    @Test
    fun `at most twenty results`() {
        val many = (1..40).joinToString(",", "[", "]") { """{"name":"Sender $it","url_resolved":"https://example.ch/$it.mp3","countrycode":"ch"}""" }
        val found = RadioBrowser.stations(many)
        assertEquals(RadioBrowser.MAX_RESULTS, found.size)
        assertEquals("CH", found.first().country)
    }

    @Test
    fun `the requests name what they ask, the search encoded`() {
        assertEquals(
            "/json/stations/search?name=Radio+Z%C3%BCrisee+%26+Co&hidebroken=true&order=clickcount&reverse=true&limit=60",
            RadioBrowser.searchPath(" Radio Zürisee & Co "),
        )
        assertEquals("/json/stations/byuuid/abc-1", RadioBrowser.stationPath("abc-1"))
        assertEquals("/json/url/abc-1", RadioBrowser.clickPath("abc-1"))
        assertTrue(RadioBrowser.SERVERS.all { it.startsWith("https://") })
    }

    @Test
    fun `the Swiss list holds the SRG SSR stations and the big private ones, each once, over https`() {
        val all = SwissStations.all
        assertEquals(44, all.size)
        assertEquals(listOf("SRG SSR", "Deutschschweiz", "Romandie"), SwissStations.groups.map { it.label })
        assertTrue(all.all { it.url.startsWith("https://") && it.country == "CH" && it.format.isNotEmpty() })
        assertEquals(all.size, all.map { it.key }.toSet().size)
        assertEquals(all.size, all.map { it.name }.toSet().size)
        assertTrue(all.mapNotNull { it.uuid }.all { UUID.matches(it) })
        assertEquals(
            listOf("SRF 1", "SRF 2 Kultur", "SRF 3", "SRF 4 News", "SRF Musikwelle", "SRF Virus"),
            all.filter { it.name.startsWith("SRF") }.map { it.name },
        )
        assertEquals("https://stream.srg-ssr.ch/srgssr/srf3/mp3/128", all.first { it.name == "SRF 3" }.url)
    }

    @Test
    fun `a station is kept as it was found, and what is not a station is not read as one`() {
        val s = Station("Radio Top", "https://radiotop.ice.infomaniak.ch/radiotop96.aac", "3e7dcb14-de8b-4c9c-85de-272148ff92a1", "CH", "AAC+", 96)
        assertEquals(s, Station.fromJson(s.toJson()))
        assertEquals("AAC+ 96 kbit/s", s.format)
        val plain = Station("Lokalradio", "http://lokal.example/stream")
        assertEquals(plain, Station.fromJson(plain.toJson()))
        assertEquals("http://lokal.example/stream", plain.key)
        assertNull(Station.fromJson(Station("Ftp", "ftp://example.ch/x").toJson()))
        assertNull(Station.fromJson(kotlinx.serialization.json.JsonPrimitive("SRF 3")))
    }

    @Test
    fun `texts are cut to their lines with an ellipsis, short ones stay`() {
        assertEquals("SRF 3", TextFit.fit("  SRF   3 ", 28, bold = true, maxLines = 1))
        val song = "MÄNI MATTER - DR EINSIEDLERKREBS ".repeat(6).trim()
        val two = TextFit.fit(song, 22, bold = false, maxLines = 2)
        assertTrue(two, two.endsWith("…"))
        assertEquals(2, TextFit.lines(two, 22, bold = false))
        assertTrue(TextFit.lines(song, 22, bold = false) > 2)
        // One word longer than a line is cut too.
        val word = "Donaudampfschifffahrtsgesellschaftskapitänswitwenrentenversicherung"
        assertEquals(1, TextFit.lines(TextFit.fit(word, 28, bold = true, maxLines = 1), 28, bold = true))
        assertEquals("", TextFit.fit(song, 22, bold = false, maxLines = 0))
    }

    @Test
    fun `the size of a cover is read from its header`() {
        assertEquals(120 to 90, pngSize(GrayPng.dataUrl(GrayRaster(120, 90))))
        assertNull(pngSize("data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDA"))
        assertNull(pngSize("data:image/png;base64,kurz"))
        assertNull(pngSize("asset:note.png"))
    }

    companion object {
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** A shortened answer of radio-browser.info to a search for "jazz": a station twice, one without a usable address. */
        val JAZZ = """
            [
              {"changeuuid":"a8e525f7-badf-41d4-b770-d6374e1d5f86","stationuuid":"961ac56b-0601-11e8-ae97-52543be04c81","name":"Radio Swiss Jazz",
               "url":"http://stream.srg-ssr.ch/m/rsj/mp3_128","url_resolved":"http://stream.srg-ssr.ch/m/rsj/mp3_128","homepage":"http://www.radioswissjazz.ch/",
               "favicon":"","tags":"jazz,public radio,srg ssr","country":"Switzerland","countrycode":"CH","codec":"MP3","bitrate":128,"hls":0,"lastcheckok":1,
               "clickcount":86,"geo_lat":null},
              {"stationuuid":"96466f91-0601-11e8-ae97-52543be04c81","name":"Radio Swiss Jazz ","url":"http://stream.srg-ssr.ch/m/rsj/aacp_96",
               "url_resolved":"http://stream.srg-ssr.ch/m/rsj/aacp_96","countrycode":"CH","codec":"AAC","bitrate":88,"lastcheckok":1},
              {"stationuuid":"0d4bc1b2-0000-0000-0000-000000000000","name":"Jazz im Keller","url":"rtsp://keller.example/jazz","url_resolved":"",
               "countrycode":"DE","codec":"MP3","bitrate":64},
              {"stationuuid":"9617a958-0601-11e8-ae97-52543be04c81","name":"  Jazz   Radio ","url":"http://jazz-wr04.ice.infomaniak.ch/jazz-wr04-128.mp3",
               "url_resolved":"http://jazz-wr04.ice.infomaniak.ch/jazz-wr04-128.mp3","countrycode":"fr","codec":"UNKNOWN","bitrate":null}
            ]
        """.trimIndent()
    }
}
