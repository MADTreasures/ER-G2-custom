package ch.madtreasures.g2watch.apps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JSON forms of 02 §4 and §6 and 04 §5.3, both ways. */
class AppJsonTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    private val events = listOf(
        AppEvent.Start, AppEvent.Visible, AppEvent.Hidden, AppEvent.Stop,
        AppEvent.Click("p_start", "radio"),
        AppEvent.Toggle("p_set", "leise", true),
        AppEvent.Check("p_liste", "items", 2, true),
        AppEvent.Navigate("p_start", "p_liste", "zur_liste"),
        AppEvent.Back("p_liste"),
        AppEvent.Menu("sortieren"),
        AppEvent.Gesture(GestureKind.SCROLL_DOWN, InputSource.RIGHT),
        AppEvent.Timer("tick"),
        AppEvent.Imu(0.02f, -0.98f, 0.11f, 1727600000123),
        AppEvent.Compass(213.5f, 1727600000123),
        AppEvent.Location(47.37, 8.54, 12f, 1727600000123),
        AppEvent.Error("patch", "unknown_block", "Unbekannter Baustein"),
        AppEvent.TextInput("suche", "Katzen"),
        AppEvent.TextInput("suche", null),
        AppEvent.Video("bild", VideoState.PLAYING, 12_000, 245_000),
        AppEvent.Video("bild", VideoState.ERROR, 0, 0, "Keine Verbindung"),
        AppEvent.Web("seite", WebState.LOADING, "https://www.srf.ch/news", progress = 40),
        AppEvent.Web(
            "seite", WebState.READY, "https://de.m.wikipedia.org/wiki/Brille", "Brille – Wikipedia", 100,
            canBack = true, canForward = false, reader = true, readable = true,
            field = WebField("Wikipedia durchsuchen", "Brillen", password = false, multiline = false),
        ),
        AppEvent.Web("seite", WebState.ERROR, "https://nirgends.example", message = "Adresse nicht gefunden"),
        AppEvent.ImageClick("p_seite", "seite", 120, 80),
        AppEvent.ImageScroll("p_seite", "seite", -195),
        AppEvent.Key(Keys.ENTER),
        AppEvent.Key("é", "é"),
        AppEvent.Key("l", ctrl = true),
        AppEvent.Key(Keys.ARROW_RIGHT, alt = true, repeat = true),
        AppEvent.Key("A", "A", shift = true, meta = false),
        AppEvent.Phone(PhoneState.CONNECTED),
        AppEvent.Phone(PhoneState.MESSAGE, """{"t":"utt","text":"Grüezi"}"""),
        AppEvent.Phone(PhoneState.DISCONNECTED, message = "Fluency wurde auf dem Handy beendet"),
        AppEvent.Location(47.3769, 8.5417, 4.5f, 1727600000123, speed = 1.4f, bearing = 271.5f, alt = 408.25),
        AppEvent.Download("schweiz", DownloadState.RUNNING, 1_048_576, 334_512_345),
        AppEvent.Download("schweiz", DownloadState.FAILED, 2_000, -1, "kein WLAN erreichbar"),
        AppEvent.Media(
            listOf(
                MediaInfo(
                    "com.spotify.music", "Spotify", MediaState.PLAYING, "Bohemian Rhapsody", "Queen", "A Night at the Opera",
                    durationMs = 354_000, positionMs = 61_500, positionAt = 1727600000123, speed = 1f,
                    artwork = "data:image/png;base64,iVBORw0KGgo=", canNext = true, canPrevious = false, canSeek = true, canSearch = true,
                ),
                MediaInfo("com.google.android.apps.youtube.music", "YouTube Music", MediaState.PAUSED),
            ),
            volume = 7, maxVolume = 15, output = "Kopfhörer: Pixel Buds",
        ),
        AppEvent.Media(emptyList(), 0, 15, null, "Musik der Uhr: Zugriff noch nicht erlaubt"),
        AppEvent.Player(PlayerState.PLAYING, "https://stream.srg-ssr.ch/srf3/mp3_128", "Queen - Bohemian Rhapsody", "SRF 3"),
        AppEvent.Player(PlayerState.ERROR, "http://radio.example/stream", message = "Keine Verbindung zum Sender"),
    )

    @Test
    fun `every event survives the round trip`() {
        for (event in events) assertEquals(event, AppJson.decodeEvent(AppJson.encodeEvent(event)))
    }

    @Test
    fun `events have the documented shape`() {
        assertEquals(json("""{ "kind": "click", "page": "p_start", "block": "radio" }"""), AppJson.encodeEvent(AppEvent.Click("p_start", "radio")))
        assertEquals(json("""{ "kind": "start" }"""), AppJson.encodeEvent(AppEvent.Start))
        assertEquals(
            json("""{ "kind": "gesture", "gesture": "scrollDown", "source": "right" }"""),
            AppJson.encodeEvent(AppEvent.Gesture(GestureKind.SCROLL_DOWN, InputSource.RIGHT)),
        )
        assertEquals(json("""{ "kind": "text", "tag": "suche", "text": null }"""), AppJson.encodeEvent(AppEvent.TextInput("suche", null)))
        assertEquals(
            json("""{ "kind": "video", "block": "bild", "state": "paused", "position": 83000, "duration": 245000, "message": null }"""),
            AppJson.encodeEvent(AppEvent.Video("bild", VideoState.PAUSED, 83_000, 245_000)),
        )
        assertEquals(
            AppEvent.Video("bild", VideoState.ENDED, 0, 0),
            AppJson.decodeEvent(json("""{ "kind": "video", "block": "bild", "state": "ended" }""")),
        )
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "video", "block": "bild", "state": "fliegt" }""")) }
    }

    @Test
    fun `sensor events are told apart by the sensor field`() {
        assertEquals(
            AppEvent.Compass(213.5f, 1727600000123),
            AppJson.decodeEvent(json("""{ "kind": "sensor", "sensor": "compass", "heading": 213.5, "t": 1727600000123 }""")),
        )
        assertEquals(
            AppEvent.Imu(0.02f, -0.98f, 0.11f, 1727600000123),
            AppJson.decodeEvent(json("""{ "kind": "sensor", "sensor": "imu", "x": 0.02, "y": -0.98, "z": 0.11, "t": 1727600000123 }""")),
        )
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "sensor", "sensor": "light", "t": 1 }""")) }
    }

    @Test
    fun `an unknown kind is ignored, a broken event is an error`() {
        assertNull(AppJson.decodeEvent(json("""{ "kind": "teleport", "to": "mars" }""")))
        val missing = assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "click", "page": "p" }""")) }
        assertEquals(CommandException.BAD_VALUE, missing.code)
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""[1, 2]""")) }
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "toggle", "page": "p", "block": "b", "on": "ja" }""")) }
    }

    @Test
    fun `audio never travels as JSON`() {
        assertThrows(Exception::class.java) { AppJson.encodeEvent(AppEvent.Audio(ShortArray(800), 1)) }
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "audio", "seq": 1, "pcm": "" }""")) }
    }

    private val page = Page(
        "p_status", "PC-Status",
        listOf(
            Block.Heading("h", "Status", Align.CENTER, HeadingSize.GROSS),
            Block.Text("t", "Zwei\nZeilen"),
            Block.Button("b", "Weiter", target = "p_next", action = "öffnet"),
            Block.Button("zurueck", "Zurück", target = Block.BACK),
            Block.List("l", listOf(ListItem("Milch", true), ListItem("Brot")), ListStyle.CHECKS),
            Block.Toggle("s", "Leise", on = true),
            Block.Value("cpu", "Prozessor", "12 %"),
            Block.Progress("last", "Last", 42),
            Block.Divider("d"),
            Block.Image("bild", "data:image/png;base64,AAAA", 120, 80, Align.LEFT),
            Block.Image("karte", null, 576, 288, bleed = true),
        ),
        statusBar = false,
        notes = "Notiz",
        input = InputMode.GESTURES,
    )

    private val commands = listOf(
        AppCommand.DefinePages(listOf(page)),
        AppCommand.Show("p_status"),
        AppCommand.Replace("p_status"),
        AppCommand.Patch("p_status", buildJsonObject { put("cpu", buildJsonObject { put("value", "12 %") }) }),
        AppCommand.SetBlocks("p_status", page.blocks.take(3)),
        AppCommand.Toast("Gespeichert", 2000),
        AppCommand.Vibrate(Vibration.DOUBLE),
        AppCommand.Menu(listOf(MenuItem("sortieren", "Sortieren"))),
        AppCommand.Buzz(listOf(BuzzNote(880, 50, 120), BuzzNote(0, 0, 60))),
        AppCommand.Subscribe(Sensor.IMU, 100),
        AppCommand.Unsubscribe(Sensor.IMU),
        AppCommand.Audio(true),
        AppCommand.AskText("suche", "YouTube durchsuchen", listOf("Katzen", "Musik")),
        AppCommand.Video("bild", VideoAction.Play("https://www.youtube.com/watch?v=abc", VideoProfile.FAST, sound = true, startMs = 5_000)),
        AppCommand.Video("bild", VideoAction.Play("test:muster")),
        AppCommand.Video("bild", VideoAction.Pause),
        AppCommand.Video("bild", VideoAction.Resume),
        AppCommand.Video("bild", VideoAction.Seek(90_000)),
        AppCommand.Video("bild", VideoAction.Profile(VideoProfile.STABLE)),
        AppCommand.Video("bild", VideoAction.Stop),
        AppCommand.Web("seite", WebAction.Open("https://www.srf.ch/news", reader = true)),
        AppCommand.Web("seite", WebAction.Open("http://example.org/")),
        AppCommand.Web("seite", WebAction.Back),
        AppCommand.Web("seite", WebAction.Forward),
        AppCommand.Web("seite", WebAction.Reload),
        AppCommand.Web("seite", WebAction.Scroll(-195)),
        AppCommand.Web("seite", WebAction.Tap(120, 80)),
        AppCommand.Web("seite", WebAction.Type("Katzen im Schnee", enter = false)),
        AppCommand.Web("seite", WebAction.Reader(false)),
        AppCommand.Web("seite", WebAction.Contrast(WebContrast.HALO)),
        AppCommand.Web("seite", WebAction.Key(Keys.BACKSPACE)),
        AppCommand.Web("seite", WebAction.Key(Keys.TAB, shift = true)),
        AppCommand.Web("seite", WebAction.Key("a", ctrl = true, alt = false)),
        AppCommand.Web("seite", WebAction.Insert("Grüezi")),
        AppCommand.Subscribe(Sensor.KEYS),
        AppCommand.Web("seite", WebAction.Stop),
        AppCommand.Phone(PhoneAction.Connect("ch.madtreasures.fluency")),
        AppCommand.Phone(PhoneAction.Send("""{"t":"hello"}""")),
        AppCommand.Phone(PhoneAction.Microphone(true)),
        AppCommand.Phone(PhoneAction.Disconnect),
        AppCommand.Media(MediaAction.Follow),
        AppCommand.Media(MediaAction.Unfollow),
        AppCommand.Media(MediaAction.Play()),
        AppCommand.Media(MediaAction.Pause("com.spotify.music")),
        AppCommand.Media(MediaAction.PlayPause()),
        AppCommand.Media(MediaAction.Next()),
        AppCommand.Media(MediaAction.Previous("com.spotify.music")),
        AppCommand.Media(MediaAction.Seek(90_000)),
        AppCommand.Media(MediaAction.Search("Queen", "com.spotify.music")),
        AppCommand.Media(MediaAction.Open("com.spotify.music")),
        AppCommand.Media(MediaAction.Volume(-2)),
        AppCommand.Player(PlayerAction.Play("https://stream.srg-ssr.ch/srf3/mp3_128", "SRF 3")),
        AppCommand.Player(PlayerAction.Pause),
        AppCommand.Player(PlayerAction.Resume),
        AppCommand.Player(PlayerAction.Stop),
        AppCommand.Close,
    )

    @Test
    fun `every command survives the round trip`() {
        for (command in commands) assertEquals(command, AppJson.decodeCommand(AppJson.encodeCommand(command)))
    }

    @Test
    fun `commands read as written in the protocol chapter`() {
        assertEquals(
            AppCommand.Patch("p_status", json("""{ "cpu": { "value": "12 %" } }""") as JsonObject),
            AppJson.decodeCommand(json("""{ "c": "patch", "page": "p_status", "changes": { "cpu": { "value": "12 %" } } }""")),
        )
        assertEquals(
            AppCommand.Buzz(listOf(BuzzNote(880, 50, 120), BuzzNote(0, 0, 60), BuzzNote(1320, 50, 120))),
            AppJson.decodeCommand(json("""{ "c": "buzz", "notes": [[880, 50, 120], [0, 0, 60], [1320, 50, 120]] }""")),
        )
        assertEquals(AppCommand.Toast("Gespeichert", 2000), AppJson.decodeCommand(json("""{ "c": "toast", "text": "Gespeichert" }""")))
        assertEquals(AppCommand.Vibrate(Vibration.TICK), AppJson.decodeCommand(json("""{ "c": "vibrate" }""")))
        assertEquals(AppCommand.Close, AppJson.decodeCommand(json("""{ "c": "close" }""")))
        assertEquals(AppCommand.AskText("suche", ""), AppJson.decodeCommand(json("""{ "c": "askText", "tag": "suche" }""")))
        assertEquals(
            AppCommand.Video("bild", VideoAction.Play("https://example.org/film.mp4")),
            AppJson.decodeCommand(json("""{ "c": "video", "block": "bild", "action": "play", "src": "https://example.org/film.mp4" }""")),
        )
        assertEquals(
            json("""{ "c": "video", "block": "bild", "action": "seek", "position": 90000 }"""),
            AppJson.encodeCommand(AppCommand.Video("bild", VideoAction.Seek(90_000))),
        )
    }

    @Test
    fun `web pages read as written in the app model chapter`() {
        // 02 §6: the event and the command of interface version 2.
        assertEquals(
            json(
                """{ "kind": "web", "block": "seite", "state": "ready", "url": "https://srf.ch/", "title": "SRF", "progress": 100,
                     "back": true, "forward": false, "reader": false, "readable": true,
                     "field": { "label": "Suche", "value": "", "password": false, "multiline": false }, "message": null }""",
            ),
            AppJson.encodeEvent(AppEvent.Web("seite", WebState.READY, "https://srf.ch/", "SRF", 100, canBack = true, readable = true, field = WebField("Suche"))),
        )
        assertEquals(
            AppEvent.Web("seite", WebState.LOADING, "https://srf.ch/"),
            AppJson.decodeEvent(json("""{ "kind": "web", "block": "seite", "state": "loading", "url": "https://srf.ch/" }""")),
        )
        assertEquals(json("""{ "kind": "imageClick", "page": "p", "block": "seite", "x": 3, "y": 4 }"""), AppJson.encodeEvent(AppEvent.ImageClick("p", "seite", 3, 4)))
        assertEquals(AppEvent.ImageScroll("p", "seite", 195), AppJson.decodeEvent(json("""{ "kind": "imageScroll", "page": "p", "block": "seite", "dy": 195 }""")))
        assertEquals(
            AppCommand.Web("seite", WebAction.Type("Brille", enter = true)),
            AppJson.decodeCommand(json("""{ "c": "web", "block": "seite", "action": "type", "text": "Brille" }""")),
        )
        assertEquals(
            json("""{ "c": "web", "block": "seite", "action": "open", "url": "https://srf.ch/", "reader": true }"""),
            AppJson.encodeCommand(AppCommand.Web("seite", WebAction.Open("https://srf.ch/", reader = true))),
        )
        assertThrows(CommandException::class.java) { AppJson.decodeCommand(json("""{ "c": "web", "block": "seite", "action": "fliegen" }""")) }
        assertThrows(CommandException::class.java) { AppJson.decodeCommand(json("""{ "c": "web", "block": "seite", "action": "tap", "x": 3 }""")) }
        assertThrows(CommandException::class.java) { AppJson.decodeCommand(json("""{ "c": "web", "block": "seite", "action": "contrast", "contrast": "neon" }""")) }
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "web", "block": "seite", "state": "schwebt", "url": "" }""")) }
    }

    @Test
    fun `keys read as written in the app model chapter`() {
        // 02 §6: the event and the web actions of interface version 3.
        assertEquals(
            json("""{ "kind": "key", "key": "l", "text": "", "shift": false, "ctrl": true, "alt": false, "meta": false, "repeat": false }"""),
            AppJson.encodeEvent(AppEvent.Key("l", ctrl = true)),
        )
        assertEquals(AppEvent.Key("é", "é"), AppJson.decodeEvent(json("""{ "kind": "key", "key": "é", "text": "é" }""")))
        assertEquals(AppEvent.Key(Keys.ARROW_DOWN), AppJson.decodeEvent(json("""{ "kind": "key", "key": "ArrowDown" }""")))
        assertEquals(
            AppCommand.Web("seite", WebAction.Key("Enter")),
            AppJson.decodeCommand(json("""{ "c": "web", "block": "seite", "action": "key", "key": "Enter" }""")),
        )
        assertEquals(
            json("""{ "c": "web", "block": "seite", "action": "insert", "text": "ä" }"""),
            AppJson.encodeCommand(AppCommand.Web("seite", WebAction.Insert("ä"))),
        )
        assertEquals(AppCommand.Subscribe(Sensor.KEYS), AppJson.decodeCommand(json("""{ "c": "subscribe", "sensor": "keys" }""")))
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "key", "key": "" }""")) }
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "key" }""")) }
        assertThrows(CommandException::class.java) { AppJson.decodeCommand(json("""{ "c": "web", "block": "seite", "action": "key" }""")) }
        assertThrows(CommandException::class.java) { AppJson.decodeCommand(json("""{ "c": "web", "block": "seite", "action": "insert" }""")) }
    }

    @Test
    fun `GPS and downloads read as written in the app model chapter`() {
        // 02 §6.1: interface version 5. A position without speed, direction and height keeps the old JSON.
        assertEquals(
            json("""{ "kind": "sensor", "sensor": "location", "lat": 47.37, "lon": 8.54, "acc": 12.0, "t": 1727600000123 }"""),
            AppJson.encodeEvent(AppEvent.Location(47.37, 8.54, 12f, 1727600000123)),
        )
        assertEquals(
            AppEvent.Location(47.37, 8.54, 12f, 1727600000123, speed = 3.5f, bearing = 90f, alt = 412.0),
            AppJson.decodeEvent(
                json("""{ "kind": "sensor", "sensor": "location", "lat": 47.37, "lon": 8.54, "acc": 12, "t": 1727600000123, "speed": 3.5, "bearing": 90, "alt": 412 }"""),
            ),
        )
        assertEquals(
            json("""{ "kind": "download", "tag": "schweiz", "state": "done", "bytes": 334512345, "total": 334512345, "message": null }"""),
            AppJson.encodeEvent(AppEvent.Download("schweiz", DownloadState.DONE, 334_512_345, 334_512_345)),
        )
        assertEquals(
            AppEvent.Download("schweiz", DownloadState.RUNNING, 0, -1),
            AppJson.decodeEvent(json("""{ "kind": "download", "tag": "schweiz", "state": "running" }""")),
        )
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "download", "tag": "x", "state": "fliegt" }""")) }
        assertThrows(CommandException::class.java) {
            AppJson.decodeEvent(json("""{ "kind": "sensor", "sensor": "location", "lat": 1, "lon": 2, "acc": 3, "t": 4, "speed": "schnell" }"""))
        }
    }

    @Test
    fun `file names stay inside the app's folder`() {
        for (good in listOf("a.map", "maps/switzerland.map", "index/ch-2026_09.idx", "a-b_c.d")) assertTrue(good, AppFiles.isValidName(good))
        for (bad in listOf("", "..", ".", "../a", "a/../b", "/a", "a/", "a//b", "a b", "ä.map", "a\\b", "x".repeat(201))) {
            assertFalse(bad, AppFiles.isValidName(bad))
        }
    }

    @Test
    fun `music reads as written in the app model chapter`() {
        assertEquals(
            json("""{ "c": "media", "action": "next", "app": "com.spotify.music" }"""),
            AppJson.encodeCommand(AppCommand.Media(MediaAction.Next("com.spotify.music"))),
        )
        assertEquals(
            AppCommand.Player(PlayerAction.Play("http://radio.example/stream", "")),
            AppJson.decodeCommand(json("""{ "c": "player", "action": "play", "url": "http://radio.example/stream" }""")),
        )
        assertEquals(
            json("""{ "kind": "player", "state": "paused", "url": "https://r.example/s", "title": null, "station": null, "message": "Eine andere App spielt" }"""),
            AppJson.encodeEvent(AppEvent.Player(PlayerState.PAUSED, "https://r.example/s", message = "Eine andere App spielt")),
        )
    }

    @Test
    fun `the phone reads as written in the app model chapter`() {
        // 02 §6: the command and the event of interface version 4.
        assertEquals(
            json("""{ "c": "phone", "action": "connect", "partner": "ch.madtreasures.fluency" }"""),
            AppJson.encodeCommand(AppCommand.Phone(PhoneAction.Connect("ch.madtreasures.fluency"))),
        )
        assertEquals(
            AppCommand.Phone(PhoneAction.Microphone(false)),
            AppJson.decodeCommand(json("""{ "c": "phone", "action": "microphone", "on": false }""")),
        )
        assertEquals(AppCommand.Phone(PhoneAction.Send("")), AppJson.decodeCommand(json("""{ "c": "phone", "action": "send", "text": "" }""")))
        assertEquals(
            json("""{ "kind": "phone", "state": "message", "text": "{}", "message": null }"""),
            AppJson.encodeEvent(AppEvent.Phone(PhoneState.MESSAGE, "{}")),
        )
        assertEquals(AppEvent.Phone(PhoneState.CONNECTED), AppJson.decodeEvent(json("""{ "kind": "phone", "state": "connected" }""")))
        assertThrows(CommandException::class.java) { AppJson.decodeEvent(json("""{ "kind": "phone", "state": "ringing" }""")) }
        assertThrows(CommandException::class.java) { AppJson.decodeCommand(json("""{ "c": "phone", "action": "call" }""")) }
        assertThrows(CommandException::class.java) { AppJson.decodeCommand(json("""{ "c": "phone", "action": "connect" }""")) }
        assertThrows(CommandException::class.java) { AppJson.decodeCommand(json("""{ "c": "phone", "action": "send" }""")) }
        assertThrows(CommandException::class.java) { AppJson.decodeCommand(json("""{ "c": "phone", "action": "microphone" }""")) }
    }

    @Test
    fun `a page may choose its input mode, without it the page keeps the old JSON`() {
        val gestures = AppJson.encodePage(Page("p_video", "Video", emptyList(), input = InputMode.GESTURES))
        assertEquals(JsonPrimitive("gestures"), gestures["input"])
        assertNull(AppJson.encodePage(Page("p", "P", emptyList()))["input"])
        assertNull(AppJson.decodePage(json("""{ "id": "p", "blocks": [] }""")).input)
        assertThrows(CommandException::class.java) { AppJson.decodePage(json("""{ "id": "p", "input": "maus" }""")) }
    }

    @Test
    fun `definePages also takes a whole Baukasten project`() {
        val command = AppJson.decodeCommand(
            json("""{ "c": "definePages", "project": { "format": "g2-baukasten@1", "pages": [ { "id": "p", "name": "P", "blocks": [ { "id": "x", "type": "divider" } ] } ] } }"""),
        ) as AppCommand.DefinePages
        assertEquals(listOf(Page("p", "P", listOf(Block.Divider("x")))), command.pages)
        assertThrows(CommandException::class.java) { AppJson.decodeCommand(json("""{ "c": "definePages", "project": { "format": "anderes@1", "pages": [] } }""")) }
    }

    @Test
    fun `broken commands are errors, never dropped`() {
        for (text in listOf(
            """{ "c": "fly" }""",
            """{ "c": "show" }""",
            """{ "c": "patch", "page": "p", "changes": [] }""",
            """{ "c": "buzz", "notes": [[880, 50]] }""",
            """{ "c": "subscribe", "sensor": "light" }""",
            """{ "c": "vibrate", "pattern": "brumm" }""",
            """{ "c": "video", "block": "bild", "action": "rewind" }""",
            """{ "c": "video", "block": "bild", "action": "play" }""",
            """{ "c": "video", "block": "bild", "action": "play", "src": "https://x", "profile": "turbo" }""",
            """{ "c": "video", "block": "bild", "action": "seek" }""",
            """{ "c": "askText" }""",
            """{ "c": "askText", "tag": "t", "suggestions": [1, 2] }""",
            """{ "page": "p" }""",
        )) {
            val e = assertThrows(text, CommandException::class.java) { AppJson.decodeCommand(json(text)) }
            assertEquals(CommandException.BAD_VALUE, e.code)
        }
    }

    @Test
    fun `pages and blocks keep every field`() {
        assertEquals(page, AppJson.decodePage(AppJson.encodePage(page)))
        val button = AppJson.encodeBlock(Block.Button("b", "Weiter"))
        assertEquals(JsonPrimitive("button"), button["type"])
        assertTrue(button.containsKey("target"))
    }

    @Test
    fun `missing optional fields of a block get their defaults`() {
        assertEquals(Block.Heading("h", "", Align.LEFT, HeadingSize.NORMAL), AppJson.decodeBlock(json("""{ "id": "h", "type": "heading" }""")))
        assertEquals(Block.List("l", listOf(ListItem("a"), ListItem("b", true))), AppJson.decodeBlock(json("""{ "id": "l", "type": "list", "items": ["a", { "text": "b", "done": true }] }""")))
        assertEquals(Block.Image("i", null, 10, 20), AppJson.decodeBlock(json("""{ "id": "i", "type": "image", "w": 10, "h": 20 }""")))
    }

    @Test
    fun `wrong blocks are errors`() {
        for (text in listOf(
            """{ "id": "x", "type": "karussell" }""",
            """{ "id": "mit leerzeichen", "type": "divider" }""",
            """{ "type": "divider" }""",
            """{ "id": "h", "type": "heading", "align": "right" }""",
            """{ "id": "i", "type": "image", "w": 10 }""",
            """{ "id": "l", "type": "list", "items": "Milch" }""",
        )) {
            assertThrows(text, CommandException::class.java) { AppJson.decodeBlock(json(text)) }
        }
    }

    @Test
    fun `the serializers speak the same JSON`() {
        val text = Json.encodeToString(AppEventSerializer, AppEvent.Check("p", "l", 1, false))
        assertEquals(json("""{ "kind": "check", "page": "p", "block": "l", "index": 1, "done": false }"""), json(text))
        assertEquals(AppEvent.Timer("tick"), Json.decodeFromString(AppEventSerializer, """{ "kind": "timer", "tag": "tick" }"""))
        assertEquals(AppCommand.Show("p"), Json.decodeFromString(AppCommandSerializer, Json.encodeToString(AppCommandSerializer, AppCommand.Show("p"))))
    }
}
