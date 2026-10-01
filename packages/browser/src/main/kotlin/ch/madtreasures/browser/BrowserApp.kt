package ch.madtreasures.browser

import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.Permission
import ch.madtreasures.g2watch.apps.WebAction
import ch.madtreasures.g2watch.apps.WebContrast
import ch.madtreasures.g2watch.apps.WebField
import ch.madtreasures.g2watch.apps.WebState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.text.BreakIterator

/**
 * A web browser on the glasses (05 §10, M7). The address comes from the watch (keyboard or voice); the
 * watch's browser engine paints the page and turns it into the glasses' picture, which fills the app
 * area. On the page the pointer is the finger: a double tap on the watch, or a tap on the temple with
 * the pointer on the page, clicks there; temple swipes and the pointer pushed past the edge scroll. A
 * text field that gets the cursor asks for its text on the watch. Back goes back through the pages
 * seen, then to the start page with the bookmarks. Articles show in reading mode (switchable).
 *
 * An app package (09): this class holds the pages, bookmarks and history; GeckoView, capturing and
 * rasterizing are part of the watch app (`apps/web`), reached through [AppContext.web].
 */
class BrowserApp : G2App {

    override val manifest = AppManifest(
        id = "ch.madtreasures.browser",
        name = "Browser",
        version = "1.0.0",
        permissions = setOf(Permission.NETWORK),
        description = "Web-Seiten auf der Brille: Adresse auf der Uhr eingeben, Lesezeichen, Lesemodus.",
    )

    private class Link(val url: String, val title: String)

    private var bookmarks: List<Link> = emptyList()
    private var history: List<Link> = emptyList()
    private var inputs: List<String> = emptyList()
    private var reader = true
    private var contrast = WebContrast.OUTLINE

    /** The page in [PICTURE] and what it last reported. */
    private var open = false
    private var state = WebState.LOADING
    private var url = ""
    private var title = ""
    private var progress = 0
    private var canForward = false
    private var readerShown = false
    private var readable = false
    private var field: WebField? = null

    /** The wearer switched reading mode on for this page: say so if the page has none. */
    private var readerAsked = false

    /** The wearer tapped the page: a text field that gets the cursor now asks for its text. */
    private var tapped = false
    private var shownMenu: List<MenuItem>? = null

    /** Bookmark armed for removal (index), until another click. */
    private var armed = -1
    private var shownName = ""

    override fun onEvent(event: AppEvent, ui: AppContext) {
        when (event) {
            AppEvent.Start -> start(ui)
            is AppEvent.Click -> click(event.block, ui)
            is AppEvent.Toggle -> if (event.block == READER) setReader(event.on, ui)
            is AppEvent.TextInput -> answer(event.tag, event.text, ui)
            is AppEvent.Web -> if (event.block == PICTURE) pageChanged(event, ui)
            is AppEvent.ImageClick -> if (event.block == PICTURE) tap(event.x, event.y, ui)
            is AppEvent.ImageScroll -> if (event.block == PICTURE) ui.web(PICTURE, WebAction.Scroll(event.dy))
            is AppEvent.Back -> if (event.page == WEB) close(ui) else disarm(ui)
            is AppEvent.Navigate -> disarm(ui)
            is AppEvent.Menu -> menu(event.item, ui)
            else -> Unit
        }
    }

    // --- Start page -----------------------------------------------------------------------------

    private fun start(ui: AppContext) {
        load(ui)
        ui.definePages(listOf(startPage(), historyPage(), removePage()))
        updateMenu(ui)
        ui.show(START)
    }

    private fun startPage(): Page {
        val blocks = ArrayList<Block>()
        blocks += Block.Button(ADDRESS, "Adresse oder Suche")
        blocks += Block.Heading(BOOKMARKS_TITLE, "Lesezeichen")
        if (bookmarks.isEmpty()) blocks += Block.Text(BOOKMARKS_EMPTY, "Noch keine. Auf einer Seite im Menü: „Lesezeichen setzen“.")
        bookmarks.forEachIndexed { i, b -> blocks += Block.Button("$BOOKMARK$i", shorten(b.title, 40)) }
        blocks += Block.Button(HISTORY_BUTTON, "Verlauf", target = HISTORY)
        blocks += Block.Toggle(READER, "Lesemodus", on = reader)
        blocks += Block.Button(CONTRAST, contrastText())
        if (bookmarks.isNotEmpty()) blocks += Block.Button(REMOVE_BUTTON, "Lesezeichen entfernen", target = REMOVE)
        return Page(START, "Browser", blocks)
    }

    private fun contrastText() = "Schrift auf Bildern: ${contrast.label}"

    private fun historyPage(): Page {
        val blocks = ArrayList<Block>()
        blocks += Block.Heading(HISTORY_TITLE, "Zuletzt besucht")
        if (history.isEmpty()) {
            blocks += Block.Text(HISTORY_EMPTY, "Noch nichts besucht.")
        } else {
            history.forEachIndexed { i, h ->
                blocks += Block.Button("$SEEN$i", shorten(h.title, 40))
                blocks += Block.Text("$SEEN_INFO$i", Address.host(h.url))
            }
            blocks += Block.Button(CLEAR_HISTORY, "Verlauf löschen")
        }
        return Page(HISTORY, "Verlauf", blocks)
    }

    private fun removePage(): Page {
        val blocks = ArrayList<Block>()
        if (bookmarks.isEmpty()) {
            blocks += Block.Text(REMOVE_HINT, "Keine Lesezeichen mehr.")
        } else {
            blocks += Block.Text(REMOVE_HINT, "Zweimal antippen entfernt ein Lesezeichen.")
            bookmarks.forEachIndexed { i, b -> blocks += Block.Button("$REMOVE_ITEM$i", (if (i == armed) "Entfernen? " else "") + shorten(b.title, 34)) }
        }
        return Page(REMOVE, "Entfernen", blocks)
    }

    private fun click(block: String, ui: AppContext) {
        val wasArmed = armed
        if (!block.startsWith(REMOVE_ITEM)) armed = -1
        when {
            block == ADDRESS -> askAddress(ui)
            block == CONTRAST -> nextContrast(ui)
            block == CLEAR_HISTORY -> {
                history = emptyList()
                save(ui)
                ui.definePages(listOf(historyPage()))
                ui.toast("Verlauf gelöscht")
            }
            block.startsWith(REMOVE_ITEM) -> block.removePrefix(REMOVE_ITEM).toIntOrNull()?.let { removeClicked(it, wasArmed, ui) }
            block.startsWith(SEEN_INFO) -> Unit
            block.startsWith(BOOKMARK) -> block.removePrefix(BOOKMARK).toIntOrNull()?.let { bookmarks.getOrNull(it) }?.let { go(it.url, ui) }
            block.startsWith(SEEN) -> block.removePrefix(SEEN).toIntOrNull()?.let { history.getOrNull(it) }?.let { go(it.url, ui) }
        }
    }

    /** Leaving the removal page forgets a bookmark armed there. */
    private fun disarm(ui: AppContext) {
        if (armed < 0) return
        armed = -1
        ui.definePages(listOf(removePage()))
    }

    private fun removeClicked(i: Int, wasArmed: Int, ui: AppContext) {
        if (i !in bookmarks.indices) return
        if (wasArmed != i) {
            armed = i
            ui.definePages(listOf(removePage()))
            return
        }
        val gone = bookmarks[i]
        bookmarks = bookmarks.filterIndexed { k, _ -> k != i }
        armed = -1
        save(ui)
        ui.definePages(listOf(startPage(), removePage()))
        updateMenu(ui)
        ui.toast("Entfernt: ${shorten(gone.title, 30)}")
    }

    private fun setReader(on: Boolean, ui: AppContext) {
        reader = on
        save(ui)
        ui.patch(START) { on(READER, on) }
        if (open) {
            readerAsked = on
            ui.web(PICTURE, WebAction.Reader(on))
        }
        updateMenu(ui)
    }

    private fun nextContrast(ui: AppContext) {
        contrast = WebContrast.entries[(contrast.ordinal + 1) % WebContrast.entries.size]
        save(ui)
        ui.patch(START) { text(CONTRAST, contrastText()) }
        if (open) ui.web(PICTURE, WebAction.Contrast(contrast))
        ui.toast(
            when (contrast) {
                WebContrast.OUTLINE -> "Umriss: dunkle Schrift mit hellem Rand"
                WebContrast.HALO -> "Leuchtschrift mit dunklem Rand"
                WebContrast.PLATE -> "Platte: helle Fläche hinter der Schrift"
            },
        )
    }

    // --- Address and text from the watch --------------------------------------------------------

    private fun askAddress(ui: AppContext) = ui.askText(ASK_ADDRESS, "Adresse oder Suchbegriff", inputs.take(5).map { shorten(it, 40) })

    private fun askField(ui: AppContext) {
        val f = field ?: return
        val prompt = f.label.ifBlank { "Text für das Feld" }.take(100)
        val suggestions = if (f.password) {
            emptyList()
        } else {
            (listOf(f.value) + inputs).map { it.trim() }.filter { it.isNotEmpty() && it.length <= 40 }.distinct().take(5)
        }
        ui.askText(ASK_FIELD, prompt, suggestions)
    }

    private fun answer(tag: String, text: String?, ui: AppContext) {
        when (tag) {
            ASK_ADDRESS -> {
                val typed = text?.trim().orEmpty()
                val address = Address.of(typed) ?: return
                remember(typed, ui)
                go(address, ui)
            }
            ASK_FIELD -> {
                val f = field
                if (text == null || !open) return
                if (f?.password != true) remember(text.trim(), ui)
                ui.web(PICTURE, WebAction.Type(text, enter = f?.multiline != true))
            }
        }
    }

    /** Typed addresses and search words, newest first, as suggestions. */
    private fun remember(input: String, ui: AppContext) {
        if (input.isEmpty() || input.length > 200) return
        inputs = (listOf(input) + inputs.filter { !it.equals(input, ignoreCase = true) }).take(MAX_INPUTS)
        save(ui)
    }

    // --- The page -------------------------------------------------------------------------------

    private fun webPage(name: String) = Page(
        WEB, name,
        listOf(Block.Image(PICTURE, null, PICTURE_W, PICTURE_H, bleed = true)),
    )

    private fun tap(x: Int, y: Int, ui: AppContext) {
        if (!open) return
        tapped = true
        ui.web(PICTURE, WebAction.Tap(x, y))
    }

    /** Opens [address] in the page, which joins its history if one is open already. */
    private fun go(address: String, ui: AppContext) {
        armed = -1
        if (!open) {
            shownName = "Lädt …"
            ui.definePages(listOf(webPage(shownName)))
            open = true
            state = WebState.LOADING
            url = ""
            title = ""
            progress = 0
            canForward = false
            field = null
            readerShown = false
            readable = false
            readerAsked = false
            tapped = false
            ui.web(PICTURE, WebAction.Open(address, reader))
            if (contrast != WebContrast.OUTLINE) ui.web(PICTURE, WebAction.Contrast(contrast))
        } else {
            ui.web(PICTURE, WebAction.Open(address, reader))
        }
        ui.show(WEB)
        updateMenu(ui)
    }

    /** Back on the page with no page before it: the page ends, the start page shows. */
    private fun close(ui: AppContext) {
        if (!open) return
        ui.web(PICTURE, WebAction.Stop)
        open = false
        field = null
        ui.definePages(listOf(historyPage()))
        updateMenu(ui)
    }

    private fun pageChanged(e: AppEvent.Web, ui: AppContext) {
        if (!open) return
        val before = state
        val hadField = field
        if (e.state == WebState.LOADING && e.url != url) {
            // Another page: what was asked of the old one is over.
            readerAsked = false
            tapped = false
        }
        state = e.state
        url = e.url
        title = e.title
        progress = e.progress
        canForward = e.canForward
        readable = e.readable
        readerShown = e.reader
        field = e.field
        when (e.state) {
            WebState.READY -> {
                visited(ui)
                if (readerAsked) {
                    if (!e.reader && !e.readable) ui.toast("Diese Seite hat keinen Lesemodus")
                    if (e.reader || !e.readable) readerAsked = false
                }
            }
            WebState.ERROR -> if (before != WebState.ERROR) ui.toast(e.message ?: "Die Seite lädt nicht")
            WebState.LOADING -> Unit
        }
        // A note of the watch: a link it does not follow, a message of the page.
        if (e.state != WebState.ERROR) e.message?.let { ui.toast(it) }
        rename(ui)
        // Only a field the wearer tapped asks at once; one the page focused by itself waits for the menu.
        val f = field
        if (f != null && tapped && (hadField == null || hadField.label != f.label)) {
            tapped = false
            askField(ui)
        }
        updateMenu(ui)
    }

    /** The header shows what the page is: loading, its title (📖 in reading mode), or the error. */
    private fun rename(ui: AppContext) {
        val name = when (state) {
            WebState.LOADING -> if (progress in 1..99) "Lädt … $progress %" else "Lädt … ${Address.host(url)}"
            WebState.READY -> (if (readerShown) "📖 " else "") + shorten(title.ifBlank { Address.host(url) }, 32)
            WebState.ERROR -> "Fehler"
        }
        if (name == shownName) return
        shownName = name
        ui.definePages(listOf(webPage(name)))
    }

    /** A loaded page goes to the top of the history (its title may come later). */
    private fun visited(ui: AppContext) {
        if (url.isEmpty()) return
        val name = title.ifBlank { Address.host(url) }
        val first = history.firstOrNull()
        if (first != null && first.url == url && first.title == name) return
        history = (listOf(Link(url, name)) + history.filter { it.url != url }).take(MAX_HISTORY)
        save(ui)
        ui.definePages(listOf(historyPage()))
    }

    // --- Menu -----------------------------------------------------------------------------------

    /** The app menu, sent only when it changed. */
    private fun updateMenu(ui: AppContext) {
        val items = menuItems()
        if (items == shownMenu) return
        shownMenu = items
        ui.menu(items)
    }

    private fun menuItems(): List<MenuItem> {
        val items = ArrayList<MenuItem>()
        items += MenuItem(MENU_ADDRESS, "Adresse eingeben")
        if (!open) return items
        if (field != null) items += MenuItem(MENU_TYPE, "Text eingeben")
        items += if (bookmarks.any { it.url == url }) MenuItem(MENU_UNMARK, "Lesezeichen entfernen") else MenuItem(MENU_MARK, "Lesezeichen setzen")
        items += MenuItem(MENU_RELOAD, "Neu laden")
        if (canForward) items += MenuItem(MENU_FORWARD, "Vorwärts")
        if (readerShown) items += MenuItem(MENU_READER, "Lesemodus aus")
        else if (readable) items += MenuItem(MENU_READER, "Lesemodus an")
        // The bookmarks, to jump there from the page.
        bookmarks.take(MAX_MENU - items.size).forEachIndexed { i, b -> items += MenuItem("$MENU_BOOKMARK$i", menuText("★ " + b.title)) }
        return items
    }

    private fun menu(item: String, ui: AppContext) {
        when {
            item == MENU_ADDRESS -> askAddress(ui)
            item == MENU_TYPE -> askField(ui)
            item == MENU_MARK -> mark(ui)
            item == MENU_UNMARK -> unmark(ui)
            item == MENU_RELOAD -> if (open) ui.web(PICTURE, WebAction.Reload)
            item == MENU_FORWARD -> if (open) ui.web(PICTURE, WebAction.Forward)
            item == MENU_READER -> setReader(!readerShown, ui)
            item.startsWith(MENU_BOOKMARK) -> item.removePrefix(MENU_BOOKMARK).toIntOrNull()?.let { bookmarks.getOrNull(it) }?.let { go(it.url, ui) }
        }
    }

    private fun mark(ui: AppContext) {
        if (!open || url.isEmpty() || bookmarks.any { it.url == url }) return
        if (bookmarks.size >= MAX_BOOKMARKS) {
            ui.toast("Höchstens $MAX_BOOKMARKS Lesezeichen")
            return
        }
        val name = title.ifBlank { Address.host(url) }
        bookmarks = bookmarks + Link(url, name)
        save(ui)
        ui.definePages(listOf(startPage(), removePage()))
        updateMenu(ui)
        ui.toast("Lesezeichen: ${shorten(name, 30)}")
    }

    private fun unmark(ui: AppContext) {
        bookmarks = bookmarks.filter { it.url != url }
        save(ui)
        ui.definePages(listOf(startPage(), removePage()))
        updateMenu(ui)
        ui.toast("Lesezeichen entfernt")
    }

    // --- Store ----------------------------------------------------------------------------------

    private fun load(ui: AppContext) {
        bookmarks = links(ui.storage.get(KEY_BOOKMARKS)) ?: DEFAULT_BOOKMARKS
        history = links(ui.storage.get(KEY_HISTORY)).orEmpty()
        inputs = (ui.storage.get(KEY_INPUTS) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        reader = (ui.storage.get(KEY_READER) as? JsonPrimitive)?.booleanOrNull ?: true
        contrast = (ui.storage.get(KEY_CONTRAST) as? JsonPrimitive)?.contentOrNull?.let { WebContrast.of(it) } ?: WebContrast.OUTLINE
    }

    private fun links(stored: JsonElement?): List<Link>? = (stored as? JsonArray)?.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
        Link(url, (o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { Address.host(url) })
    }

    private fun save(ui: AppContext) {
        fun json(list: List<Link>) = JsonArray(list.map { buildJsonObject { put("url", it.url); put("title", it.title) } })
        ui.storage.set(KEY_BOOKMARKS, json(bookmarks))
        ui.storage.set(KEY_HISTORY, json(history))
        ui.storage.set(KEY_INPUTS, JsonArray(inputs.map { JsonPrimitive(it) }))
        ui.storage.set(KEY_READER, JsonPrimitive(reader))
        ui.storage.set(KEY_CONTRAST, JsonPrimitive(contrast.json))
    }

    companion object {
        const val START = "p_start"
        const val WEB = "p_seite"
        const val HISTORY = "p_verlauf"
        const val REMOVE = "p_entfernen"

        const val ADDRESS = "adresse"
        const val BOOKMARKS_TITLE = "lz_titel"
        const val BOOKMARKS_EMPTY = "lz_leer"
        const val HISTORY_BUTTON = "verlauf"
        const val READER = "lesemodus"
        const val CONTRAST = "schrift"
        const val REMOVE_BUTTON = "lz_entfernen"
        const val HISTORY_TITLE = "verlauf_titel"
        const val HISTORY_EMPTY = "verlauf_leer"
        const val CLEAR_HISTORY = "verlauf_loeschen"
        const val REMOVE_HINT = "entfernen_hinweis"
        const val PICTURE = "seite"

        /** Bookmark buttons, history entries and their info lines, removal buttons: prefix + index. */
        const val BOOKMARK = "lz"
        const val SEEN = "h"
        const val SEEN_INFO = "hi"
        const val REMOVE_ITEM = "ent"

        const val ASK_ADDRESS = "adresse"
        const val ASK_FIELD = "feld"

        const val MENU_ADDRESS = "adresse"
        const val MENU_TYPE = "text"
        const val MENU_MARK = "merken"
        const val MENU_UNMARK = "vergessen"
        const val MENU_RELOAD = "neu"
        const val MENU_FORWARD = "vor"
        const val MENU_READER = "lesemodus"
        const val MENU_BOOKMARK = "lz"

        /** The page fills the app area below the header. */
        const val PICTURE_W = 576
        const val PICTURE_H = 260

        const val MAX_BOOKMARKS = 30
        const val MAX_HISTORY = 20
        const val MAX_INPUTS = 8
        const val MAX_MENU = 10

        /** Bookmarks of a fresh start, to try the browser at once. */
        private val DEFAULT_BOOKMARKS = listOf(
            Link("https://de.m.wikipedia.org/", "Wikipedia"),
            Link("https://www.srf.ch/news", "SRF News"),
            Link("https://lite.duckduckgo.com/lite/", "DuckDuckGo"),
        )

        private const val KEY_BOOKMARKS = "lesezeichen"
        private const val KEY_HISTORY = "verlauf"
        private const val KEY_INPUTS = "eingaben"
        private const val KEY_READER = "lesemodus"
        private const val KEY_CONTRAST = "schrift"

        /** [s] cut to at most [max] chars with "…", between user-perceived characters (an emoji stays whole). */
        fun shorten(s: String, max: Int): String {
            if (s.length <= max) return s
            val clusters = BreakIterator.getCharacterInstance()
            clusters.setText(s)
            val end = clusters.preceding(max).coerceAtLeast(0)
            return s.substring(0, end).trimEnd() + "…"
        }

        /** A menu text fits in 32 bytes of UTF-8. */
        fun menuText(s: String): String {
            var t = s
            while (t.toByteArray(Charsets.UTF_8).size > 32) t = shorten(t, t.length - 1)
            return t
        }
    }
}
