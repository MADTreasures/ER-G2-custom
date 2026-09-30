package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.AppJson
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.CommandException
import ch.madtreasures.g2watch.apps.ListItem
import ch.madtreasures.g2watch.apps.Page
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The pages of one session as the wearer sees them: definitions, every patch, the toggles and ticks
 * done on the glasses, and the history for back. Shared by the host and `FakeAppContext`, so tests of
 * an app check against exactly what the host would show.
 *
 * Every change is checked first and throws [CommandException] without changing anything.
 */
class PageState {
    private val pages = LinkedHashMap<String, Page>()

    /** Block id → id of the page it is on. */
    private val blockPage = HashMap<String, String>()

    private val history = ArrayList<String>()

    val current: Page? get() = history.lastOrNull()?.let { pages[it] }

    /** Page ids, oldest first; the last one is shown. */
    val historyIds: List<String> get() = history.toList()

    val isEmpty: Boolean get() = pages.isEmpty()

    fun page(id: String): Page? = pages[id]

    fun pageIds(): Set<String> = pages.keys.toSet()

    /** The page [blockId] is on, or null. */
    fun pageOf(blockId: String): String? = blockPage[blockId]

    fun block(blockId: String): Block? = blockPage[blockId]?.let { pages[it] }?.block(blockId)

    /** Adds [defined] pages; a page with an existing id replaces that page. */
    fun define(defined: List<Page>) {
        val incoming = HashSet<String>()
        for (page in defined) {
            validate(page)
            if (!incoming.add(page.id)) throw CommandException.badValue("Seite „${page.id}“ kommt doppelt vor")
        }
        // Ids already used elsewhere: every page that is not replaced, and its blocks.
        val taken = HashMap<String, String>()
        for ((id, page) in pages) {
            if (id in incoming) continue
            taken[id] = id
            for (b in page.blocks) taken[b.id] = id
        }
        val seen = HashSet<String>()
        for (page in defined) {
            for (id in listOf(page.id) + page.blocks.map { it.id }) {
                taken[id]?.let { owner -> throw CommandException.badValue("Kennung „$id“ gibt es schon (Seite „$owner“)") }
                if (!seen.add(id)) throw CommandException.badValue("Kennung „$id“ kommt doppelt vor")
            }
        }
        for (page in defined) put(page)
    }

    /** Replaces all blocks of [pageId]. */
    fun setBlocks(pageId: String, blocks: List<Block>) {
        val old = pages[pageId] ?: throw CommandException.unknownPage(pageId)
        val next = old.copy(blocks = blocks)
        validate(next)
        val seen = HashSet<String>()
        for (b in blocks) {
            val owner = blockPage[b.id]
            if (b.id == pageId || (b.id in pages && b.id != pageId) || (owner != null && owner != pageId)) {
                throw CommandException.badValue("Kennung „${b.id}“ gibt es schon")
            }
            if (!seen.add(b.id)) throw CommandException.badValue("Kennung „${b.id}“ kommt doppelt vor")
        }
        put(next)
    }

    /** Shows [pageId] on top of the history. */
    fun show(pageId: String) {
        requirePage(pageId)
        if (history.lastOrNull() != pageId) history += pageId
    }

    /** Shows [pageId] in place of the current page. */
    fun replace(pageId: String) {
        requirePage(pageId)
        if (history.isEmpty()) history += pageId else history[history.lastIndex] = pageId
    }

    /** Sets the history to [ids], oldest first; e.g. to undo what an app changed while going back. */
    fun resetHistory(ids: List<String>) {
        require(ids.isNotEmpty() && ids.all { it in pages }) { "history $ids" }
        history.clear()
        history.addAll(ids)
    }

    /** Leaves the current page; false if it was the first one (then the app ends). */
    fun back(): Boolean {
        if (history.size <= 1) return false
        history.removeAt(history.lastIndex)
        return true
    }

    /** Applies a patch: block id → fields, as described in 02 §6.2. */
    fun patch(pageId: String, changes: JsonObject) {
        val page = requirePage(pageId)
        val updated = LinkedHashMap<String, Block>()
        for ((blockId, fields) in changes) {
            val block = page.block(blockId) ?: throw CommandException.unknownBlock(blockId, pageId)
            val f = fields as? JsonObject ?: throw CommandException.badValue("Änderungen für „$blockId“ müssen ein Objekt sein")
            updated[blockId] = applyFields(updated[blockId] ?: block, f)
        }
        val next = page.copy(blocks = page.blocks.map { updated[it.id] ?: it })
        validate(next)
        put(next)
    }

    /** Replaces one block in place, for what the host itself changes (toggles, ticks). */
    fun update(block: Block) {
        val pageId = blockPage[block.id] ?: throw CommandException.unknownBlock(block.id, "?")
        val page = pages.getValue(pageId)
        put(page.copy(blocks = page.blocks.map { if (it.id == block.id) block else it }))
    }

    private fun put(page: Page) {
        pages[page.id]?.blocks?.forEach { blockPage.remove(it.id) }
        pages[page.id] = page
        for (b in page.blocks) blockPage[b.id] = page.id
    }

    private fun requirePage(id: String): Page = pages[id] ?: throw CommandException.unknownPage(id)

    private fun applyFields(block: Block, f: JsonObject): Block {
        fun bad(field: String): Nothing = throw CommandException.badValue("„$field“ passt nicht zu „${block.id}“")
        fun text(): String = f.string("text") ?: bad("text")
        var b = block
        for (field in f.keys) {
            b = when {
                field == "text" && b is Block.Heading -> b.copy(text = text())
                field == "text" && b is Block.Text -> b.copy(text = text())
                field == "text" && b is Block.Button -> b.copy(text = text())
                field == "text" && b is Block.Toggle -> b.copy(text = text())
                field == "text" && b is Block.Value -> b.copy(text = text())
                field == "text" && b is Block.Progress -> b.copy(text = text())
                field == "align" && b is Block.Heading -> b.copy(align = align(f) ?: bad("align"))
                field == "align" && b is Block.Text -> b.copy(align = align(f) ?: bad("align"))
                field == "target" && b is Block.Button -> b.copy(
                    target = when (val t = f["target"]) {
                        null, JsonNull -> null
                        else -> (t as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("target")
                    },
                )
                field == "on" && b is Block.Toggle -> b.copy(on = f.bool("on") ?: bad("on"))
                field == "value" && b is Block.Value -> b.copy(value = f.string("value") ?: bad("value"))
                field == "value" && b is Block.Progress -> b.copy(value = f.int("value") ?: bad("value"))
                field == "items" && b is Block.List -> b.copy(
                    items = (f["items"] as? JsonArray ?: bad("items")).map { AppJson.decodeItem(it) },
                )
                field == "item" && b is Block.List -> {
                    val item = f["item"] as? JsonObject ?: bad("item")
                    val index = item.int("index") ?: bad("item.index")
                    if (index !in b.items.indices) {
                        throw CommandException.badValue("Zeile $index gibt es in „${b.id}“ nicht (${b.items.size} Zeilen)")
                    }
                    val old = b.items[index]
                    val row = ListItem(
                        text = if (item.containsKey("text")) item.string("text") ?: bad("item.text") else old.text,
                        done = if (item.containsKey("done")) item.bool("done") ?: bad("item.done") else old.done,
                    )
                    b.copy(items = b.items.toMutableList().also { it[index] = row })
                }
                field == "src" && b is Block.Image -> b.copy(
                    src = when (val s = f["src"]) {
                        null, JsonNull -> null
                        else -> (s as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("src")
                    },
                )
                field == "w" && b is Block.Image -> b.copy(w = f.int("w") ?: bad("w"))
                field == "h" && b is Block.Image -> b.copy(h = f.int("h") ?: bad("h"))
                else -> bad(field)
            }
        }
        return b
    }

    private fun align(f: JsonObject) = f.string("align")?.let { Align.of(it) }

    companion object {
        /** Checks a page on its own: ids, value ranges, image sizes. */
        fun validate(page: Page) {
            if (!Block.ID.matches(page.id)) throw CommandException.badValue("Seiten-Kennung „${page.id}“ ist ungültig")
            for (b in page.blocks) {
                if (!Block.ID.matches(b.id)) throw CommandException.badValue("Kennung „${b.id}“ ist ungültig")
                when (b) {
                    is Block.Progress -> if (b.value !in 0..100) {
                        throw CommandException.badValue("Fortschritt „${b.id}“ muss 0 bis 100 sein, nicht ${b.value}")
                    }
                    is Block.Image -> {
                        val maxW = if (b.bleed) Block.BLEED_MAX_W else Block.IMAGE_MAX_W
                        val maxH = if (b.bleed) Block.BLEED_MAX_H else Block.IMAGE_MAX_H
                        if (b.w !in 1..maxW || b.h !in 1..maxH) {
                            throw CommandException.badValue("Bild „${b.id}“: ${b.w} × ${b.h} ist zu groß (höchstens $maxW × $maxH)")
                        }
                        val src = b.src
                        if (src != null && src.startsWith("data:") && src.length > Block.DATA_URL_MAX) {
                            throw CommandException.badValue("Bild „${b.id}“: data-URL länger als 48 KiB")
                        }
                        if (src != null && !src.startsWith("data:image/") && !src.startsWith("asset:")) {
                            throw CommandException.badValue("Bild „${b.id}“: src muss data:image/… oder asset:… sein")
                        }
                    }
                    else -> Unit
                }
            }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    }
}
