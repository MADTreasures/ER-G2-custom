package ch.madtreasures.g2watch.apps.builtin.youtube

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.GestureKind
import ch.madtreasures.g2watch.apps.InputMode
import ch.madtreasures.g2watch.apps.InputSource
import ch.madtreasures.g2watch.apps.VideoAction
import ch.madtreasures.g2watch.apps.VideoItem
import ch.madtreasures.g2watch.apps.VideoProfile
import ch.madtreasures.g2watch.apps.VideoSearchResult
import ch.madtreasures.g2watch.apps.VideoState
import ch.madtreasures.g2watch.apps.builtin.youtube.YouTubeApp.Companion.PICTURE
import ch.madtreasures.g2watch.apps.builtin.youtube.YouTubeApp.Companion.STATUS
import ch.madtreasures.g2watch.apps.builtin.youtube.YouTubeApp.Companion.VIDEO
import ch.madtreasures.g2watch.apps.textOf
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The YouTube app against [FakeAppContext]: search, hits, player gestures, profiles, history. */
class YouTubeAppTest {
    private var app = YouTubeApp()
    private var ui = FakeAppContext.forApp(app)

    private val cats = listOf(
        VideoItem("https://www.youtube.com/watch?v=a1", "Katzen im Schnee", "Tierkanal", durationS = 296, views = 1_234_567),
        VideoItem("https://www.youtube.com/watch?v=b2", "Katze fängt Laserpunkt", "Lustiges", durationS = 61, views = 950),
        VideoItem("https://www.youtube.com/watch?v=live", "Katzencam", "Zoo", live = true),
    )

    private fun send(event: AppEvent) = app.onEvent(event, ui)

    private fun started() {
        send(AppEvent.Start)
        send(AppEvent.Visible)
    }

    private fun click(page: String, block: String) = send(AppEvent.Click(page, block))

    private fun searchCats() {
        click(YouTubeApp.START, YouTubeApp.SEARCH)
        send(AppEvent.TextInput(YouTubeApp.ASK_SEARCH, "katzen"))
        ui.answerSearch(VideoSearchResult(cats))
    }

    /** Plays hit [index] and lets the player report [state]. */
    private fun playHit(index: Int = 0, state: VideoState = VideoState.PLAYING, positionMs: Long = 0) {
        searchCats()
        ui.show(YouTubeApp.RESULTS)
        click(YouTubeApp.RESULTS, "${YouTubeApp.HIT}$index")
        send(AppEvent.Video(PICTURE, state, positionMs, cats[index].durationS * 1000))
    }

    private fun status() = ui.page(VIDEO).textOf(STATUS)

    @Test
    fun `the start page offers search, history, profile, sound and the test picture`() {
        started()
        val page = ui.current!!
        assertEquals(YouTubeApp.START, page.id)
        assertEquals(listOf("Suchen", "Verlauf", "Profil: Ausgewogen", "Ton auf der Uhr", "Testbild"), page.blocks.map { page.textOf(it.id) })
        assertEquals(listOf("Neue Suche", "Profil wechseln", "Ton an"), ui.menu.map { it.text })
    }

    @Test
    fun `search asks the watch for text, then lists the hits`() {
        started()
        click(YouTubeApp.START, YouTubeApp.SEARCH)
        assertEquals(YouTubeApp.ASK_SEARCH, ui.question!!.first)
        send(AppEvent.TextInput(YouTubeApp.ASK_SEARCH, " katzen "))
        assertEquals(YouTubeApp.RESULTS, ui.current!!.id)
        assertEquals("Suche läuft …", ui.page(YouTubeApp.RESULTS).textOf(YouTubeApp.RESULTS_STATUS))
        assertEquals("katzen", ui.searches.single().first)

        ui.answerSearch(VideoSearchResult(cats))
        val page = ui.page(YouTubeApp.RESULTS)
        assertEquals("„katzen“", page.textOf(YouTubeApp.RESULTS_TITLE))
        assertEquals("Katzen im Schnee", page.textOf("v0"))
        assertEquals("Tierkanal · 4:56 · 1,2 Mio. Aufrufe", page.textOf("vi0"))
        assertEquals("Lustiges · 1:01 · 950 Aufrufe", page.textOf("vi1"))
        assertEquals("Zoo · live", page.textOf("vi2"))
    }

    @Test
    fun `the last searches come back as suggestions, newest first`() {
        started()
        for (q in listOf("eins", "zwei", "Eins")) {
            click(YouTubeApp.START, YouTubeApp.SEARCH)
            send(AppEvent.TextInput(YouTubeApp.ASK_SEARCH, q))
        }
        click(YouTubeApp.START, YouTubeApp.SEARCH)
        assertEquals(listOf("Eins", "zwei"), ui.question!!.third)
    }

    @Test
    fun `a cancelled question searches nothing, a failed search says why`() {
        started()
        click(YouTubeApp.START, YouTubeApp.SEARCH)
        send(AppEvent.TextInput(YouTubeApp.ASK_SEARCH, null))
        assertTrue(ui.searches.isEmpty())
        assertEquals(YouTubeApp.START, ui.current!!.id)

        click(YouTubeApp.START, YouTubeApp.SEARCH)
        send(AppEvent.TextInput(YouTubeApp.ASK_SEARCH, "katzen"))
        ui.answerSearch(VideoSearchResult(emptyList(), "Keine Verbindung"))
        val page = ui.page(YouTubeApp.RESULTS)
        assertEquals("Suche ging nicht: Keine Verbindung", page.textOf(YouTubeApp.RESULTS_STATUS))
        click(YouTubeApp.RESULTS, YouTubeApp.AGAIN)
        assertEquals(YouTubeApp.ASK_SEARCH, ui.question!!.first)
    }

    @Test
    fun `a hit plays full screen with gestures, in the chosen profile`() {
        started()
        playHit(0)
        val page = ui.current!!
        assertEquals(VIDEO, page.id)
        assertEquals(false, page.statusBar)
        assertEquals(InputMode.GESTURES, page.input)
        val image = page.block(PICTURE) as Block.Image
        assertEquals(YouTubeApp.VIDEO_W, image.w)
        assertEquals(YouTubeApp.VIDEO_H, image.h)
        assertEquals(VideoAction.Play(cats[0].url, VideoProfile.BALANCED, sound = false), ui.videos.first { it.second is VideoAction.Play }.second)
        assertEquals("Katzen im Schnee", status())
    }

    @Test
    fun `tap pauses and plays, swipes jump 10 s, a long press switches the profile`() {
        started()
        playHit(0, positionMs = 30_000)
        send(AppEvent.Gesture(GestureKind.CLICK, InputSource.RIGHT))
        assertEquals(VideoAction.Pause, ui.lastVideo(PICTURE))
        send(AppEvent.Video(PICTURE, VideoState.PAUSED, 31_000, 296_000))
        assertEquals("Pause 0:31 / 4:56 – tippen: weiter", status())
        send(AppEvent.Gesture(GestureKind.CLICK, InputSource.RING))
        assertEquals(VideoAction.Resume, ui.lastVideo(PICTURE))

        send(AppEvent.Gesture(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN))
        assertEquals(VideoAction.Seek(41_000), ui.lastVideo(PICTURE))
        send(AppEvent.Gesture(GestureKind.SCROLL_UP, InputSource.UNKNOWN))
        send(AppEvent.Gesture(GestureKind.SCROLL_UP, InputSource.UNKNOWN))
        assertEquals(VideoAction.Seek(21_000), ui.lastVideo(PICTURE))
        assertEquals("−10 s · 0:21 / 4:56", ui.toasts.last())

        send(AppEvent.Gesture(GestureKind.LONG_PRESS, InputSource.RIGHT))
        assertEquals(VideoAction.Profile(VideoProfile.FAST), ui.lastVideo(PICTURE))
        assertEquals("Profil: Schnell", ui.page(YouTubeApp.START).textOf(YouTubeApp.PROFILE))
        assertEquals("Schnell: 4 Bilder pro Sekunde", ui.toasts.last())
    }

    @Test
    fun `seeking stays inside the video, and not at all in live streams`() {
        started()
        playHit(1, positionMs = 58_000)
        send(AppEvent.Gesture(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN))
        assertEquals(VideoAction.Seek(60_000), ui.lastVideo(PICTURE))
        send(AppEvent.Gesture(GestureKind.SCROLL_UP, InputSource.UNKNOWN))
        send(AppEvent.Gesture(GestureKind.SCROLL_UP, InputSource.UNKNOWN))
        send(AppEvent.Gesture(GestureKind.SCROLL_UP, InputSource.UNKNOWN))
        send(AppEvent.Gesture(GestureKind.SCROLL_UP, InputSource.UNKNOWN))
        send(AppEvent.Gesture(GestureKind.SCROLL_UP, InputSource.UNKNOWN))
        send(AppEvent.Gesture(GestureKind.SCROLL_UP, InputSource.UNKNOWN))
        send(AppEvent.Gesture(GestureKind.SCROLL_UP, InputSource.UNKNOWN))
        assertEquals(VideoAction.Seek(0), ui.lastVideo(PICTURE))

        app = YouTubeApp()
        ui = FakeAppContext.forApp(app)
        started()
        playHit(2)
        val before = ui.videos.size
        send(AppEvent.Gesture(GestureKind.SCROLL_DOWN, InputSource.UNKNOWN))
        assertEquals(before, ui.videos.size)
    }

    @Test
    fun `the status line follows the video`() {
        started()
        playHit(0, state = VideoState.LOADING)
        assertEquals("Lädt … Tippen = Pause, Wischen = ±10 s", status())
        send(AppEvent.Video(PICTURE, VideoState.BUFFERING, 65_000, 296_000))
        assertEquals("Lädt nach … 1:05 / 4:56", status())
        send(AppEvent.Video(PICTURE, VideoState.ENDED, 296_000, 296_000))
        assertEquals("Ende – tippen: nochmal", status())
        send(AppEvent.Gesture(GestureKind.CLICK, InputSource.RIGHT))
        assertEquals(VideoAction.Resume, ui.lastVideo(PICTURE))
        send(AppEvent.Video(PICTURE, VideoState.ERROR, 0, 0, "YouTube lässt das Video nicht laden (HTTP 403)"))
        assertEquals("YouTube lässt das Video nicht laden (HTTP 403)", status())
        assertEquals("Video geht nicht", ui.toasts.last())
        // After an error a tap tries again from the start.
        val plays = ui.videos.count { it.second is VideoAction.Play }
        send(AppEvent.Gesture(GestureKind.CLICK, InputSource.RIGHT))
        assertEquals(plays + 1, ui.videos.count { it.second is VideoAction.Play })
    }

    @Test
    fun `back from the video stops it, and it is in the history`() {
        started()
        playHit(1)
        send(AppEvent.Back(VIDEO))
        assertEquals(VideoAction.Stop, ui.lastVideo(PICTURE))
        val history = ui.page(YouTubeApp.HISTORY)
        assertEquals("Katze fängt Laserpunkt", history.textOf("h0"))
        assertEquals("Lustiges · 1:01 · 950 Aufrufe", history.textOf("hi0"))
        assertEquals(1, (ui.storage.get("verlauf") as JsonArray).size)

        // The history survives a restart and plays from there.
        val again = YouTubeApp()
        val ui2 = FakeAppContext.forApp(again)
        ui.storage.values.forEach { (k, v) -> ui2.storage.set(k, v) }
        again.onEvent(AppEvent.Start, ui2)
        again.onEvent(AppEvent.Navigate(YouTubeApp.START, YouTubeApp.HISTORY, YouTubeApp.HISTORY_BUTTON), ui2)
        again.onEvent(AppEvent.Click(YouTubeApp.HISTORY, "h0"), ui2)
        assertEquals(cats[1].url, (ui2.lastVideo(PICTURE) as VideoAction.Play).src)
        ui2.show(YouTubeApp.HISTORY)
        again.onEvent(AppEvent.Click(YouTubeApp.HISTORY, YouTubeApp.CLEAR_HISTORY), ui2)
        assertEquals("Noch nichts angesehen.", ui2.page(YouTubeApp.HISTORY).textOf(YouTubeApp.HISTORY_EMPTY))
    }

    @Test
    fun `in the video the menu offers no new search, back in the list it does`() {
        started()
        playHit(0)
        assertEquals(listOf("Profil wechseln", "Ton an"), ui.menu.map { it.text })
        send(AppEvent.Back(VIDEO))
        assertEquals(listOf("Neue Suche", "Profil wechseln", "Ton an"), ui.menu.map { it.text })
    }

    @Test
    fun `the test picture needs no internet and stays out of the history`() {
        started()
        click(YouTubeApp.START, YouTubeApp.TEST)
        assertEquals(VideoAction.Play("test:muster", VideoProfile.BALANCED), ui.lastVideo(PICTURE))
        send(AppEvent.Back(VIDEO))
        assertEquals("Noch nichts angesehen.", ui.page(YouTubeApp.HISTORY).textOf(YouTubeApp.HISTORY_EMPTY))
    }

    @Test
    fun `sound on starts the video again where it is, with sound`() {
        started()
        playHit(0, positionMs = 42_000)
        send(AppEvent.Toggle(YouTubeApp.START, YouTubeApp.SOUND, true))
        assertEquals(VideoAction.Play(cats[0].url, VideoProfile.BALANCED, sound = true, startMs = 42_000), ui.lastVideo(PICTURE))
        assertEquals("Ton aus", ui.menu.last().text)
        send(AppEvent.Menu(YouTubeApp.MENU_SOUND))
        assertEquals(VideoAction.Play(cats[0].url, VideoProfile.BALANCED, sound = false, startMs = 42_000), ui.lastVideo(PICTURE))
    }

    @Test
    fun `profile and sound are kept`() {
        started()
        click(YouTubeApp.START, YouTubeApp.PROFILE)
        click(YouTubeApp.START, YouTubeApp.PROFILE)
        send(AppEvent.Toggle(YouTubeApp.START, YouTubeApp.SOUND, true))
        val again = YouTubeApp()
        val ui2 = FakeAppContext.forApp(again)
        ui.storage.values.forEach { (k, v) -> ui2.storage.set(k, v) }
        again.onEvent(AppEvent.Start, ui2)
        assertEquals("Profil: Stabil", ui2.page(YouTubeApp.START).textOf(YouTubeApp.PROFILE))
        assertEquals(true, (ui2.page(YouTubeApp.START).block(YouTubeApp.SOUND) as Block.Toggle).on)
    }

    @Test
    fun `gestures without a video do nothing`() {
        started()
        send(AppEvent.Gesture(GestureKind.CLICK, InputSource.RIGHT))
        assertNull(ui.lastVideo(PICTURE))
    }

    @Test
    fun `titles show without emoji, which the glasses can only draw as blobs`() {
        assertEquals("These CATS are too FUNNY! | New Cat Videos", YouTubeApp.plain("These CATS are too FUNNY! 🤣 | New Cat Videos"))
        assertEquals("Funniest Cats and Dogs Clips 2025 Try Not To Laugh", YouTubeApp.plain("Funniest Cats and Dogs Clips 2025😼🐶Try Not To Laugh😜"))
        assertEquals("Liebe", YouTubeApp.plain("❤️ Liebe ⭐"))
        assertEquals("Schweiz", YouTubeApp.plain("🇨🇭 Schweiz"))
        assertEquals("Familie", YouTubeApp.plain("👨‍👩‍👧 Familie"))
        assertEquals("Grüße – „Test“ · 1:02 | ß", YouTubeApp.plain("Grüße – „Test“ · 1:02 | ß"))
        assertEquals("Ohne Titel", YouTubeApp.title(VideoItem("https://x", "🔥🔥🔥")))

        started()
        click(YouTubeApp.START, YouTubeApp.SEARCH)
        send(AppEvent.TextInput(YouTubeApp.ASK_SEARCH, "katzen"))
        ui.answerSearch(VideoSearchResult(listOf(VideoItem("https://www.youtube.com/watch?v=c", "Katzen 😂 TOP 10", "Tiere 🐾", durationS = 60))))
        assertEquals("Katzen TOP 10", ui.page(YouTubeApp.RESULTS).textOf("v0"))
        assertEquals("Tiere · 1:00", ui.page(YouTubeApp.RESULTS).textOf("vi0"))
    }

    @Test
    fun `numbers read the German way`() {
        assertEquals("0:07", YouTubeApp.clock(7_000))
        assertEquals("1:02:03", YouTubeApp.clock(3_723_000))
        assertEquals("12.345 Aufrufe", YouTubeApp.views(12_345))
        assertEquals("3 Mio. Aufrufe", YouTubeApp.views(3_000_000))
        assertEquals("1,5 Mrd. Aufrufe", YouTubeApp.views(1_500_000_000))
        assertEquals("1 Aufruf", YouTubeApp.views(1))
        assertEquals("Kanal", YouTubeApp.info(VideoItem("https://x", "t", "Kanal")))
        assertEquals("Ein sehr langer Titel…", YouTubeApp.shorten("Ein sehr langer Titel mit vielen Wörtern", 23))
        assertEquals("kurz", YouTubeApp.shorten("kurz", 23))
    }
}
