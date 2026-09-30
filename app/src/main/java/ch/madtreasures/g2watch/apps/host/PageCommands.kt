package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.CommandError
import ch.madtreasures.g2watch.apps.CommandException
import ch.madtreasures.g2watch.apps.ListItem
import ch.madtreasures.g2watch.apps.Page
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

/**
 * The checks of 02 §6.2 for the page commands, and `patch` applied to a block. Everything that is
 * wrong throws [CommandException]; nothing is changed then.
 */
internal object PageCommands {

    private fun bad(message: String): Nothing = throw CommandException(CommandError.BAD_VALUE, message)

    /** `definePages`: ids valid and unique across the app's other pages and blocks ([known]). */
    fun checkDefine(known: Map<String, Page>, pages: List<Page>) {
        if (pages.isEmpty()) bad("keine Seiten")
        val replaced = pages.map { it.id }
        if (replaced.toSet().size != replaced.size) bad("Seiten-Kennung doppelt")
        val taken = idsExcept(known, replaced)
        for (page in pages) {
            claim(taken, page.id)
            for (b in page.blocks) {
                claim(taken, b.id)
                checkBlock(b)
            }
        }
    }

    /** `setBlocks`: the page exists, the new ids are valid and unique. */
    fun checkSetBlocks(known: Map<String, Page>, pageId: String, blocks: List<Block>) {
        if (pageId !in known) throw CommandException(CommandError.UNKNOWN_PAGE, "Seite „$pageId“ gibt es nicht")
        val taken = idsExcept(known, listOf(pageId)).apply { add(pageId) }
        for (b in blocks) {
            claim(taken, b.id)
            checkBlock(b)
        }
    }

    /** Page ids and block ids share one namespace (02 §4.1): all of them but those of [pageIds]. */
    private fun idsExcept(known: Map<String, Page>, pageIds: Collection<String>): MutableSet<String> {
        val taken = HashSet<String>()
        for ((id, page) in known) {
            if (id in pageIds) continue
            taken += id
            page.blocks.forEach { taken += it.id }
        }
        return taken
    }

    /** [page] with [changes] applied; checks everything before changing anything. */
    fun patch(page: Page, changes: Map<String, JsonObject>, pageIds: Set<String>): Page {
        for (id in changes.keys) {
            if (page.block(id) == null) throw CommandException(CommandError.UNKNOWN_BLOCK, "Baustein „$id“ gibt es auf „${page.id}“ nicht")
        }
        return page.copy(blocks = page.blocks.map { b -> changes[b.id]?.let { patchBlock(b, it, pageIds) } ?: b })
    }

    private fun claim(taken: MutableSet<String>, id: String) {
        if (!Block.ID_PATTERN.matches(id)) bad("Kennung „$id“: erlaubt sind A–Z, a–z, 0–9, _ . - (1–40 Zeichen)")
        if (!taken.add(id)) bad("Kennung „$id“ ist schon vergeben")
    }

    private fun checkBlock(b: Block) {
        when (b) {
            is Block.Progress -> if (b.value !in 0..100) bad("„${b.id}“: Wert 0–100")
            is Block.Image -> {
                val maxW = if (b.bleed) Block.Image.BLEED_W else Block.Image.MAX_W
                val maxH = if (b.bleed) Block.Image.BLEED_H else Block.Image.MAX_H
                if (b.w !in 1..maxW || b.h !in 1..maxH) bad("„${b.id}“: Bild höchstens $maxW × $maxH")
                checkSource(b.id, b.src)
            }
            else -> Unit
        }
    }

    fun checkSource(blockId: String, src: String?) {
        if (src == null) return
        when {
            src.startsWith("data:") -> {
                if (src.length > Block.Image.MAX_DATA_URL) bad("„$blockId“: data-URL länger als 48 KiB – als asset: oder Raster schicken")
                if (!src.startsWith("data:image/") || !src.contains(";base64,")) bad("„$blockId“: erwartet data:image/…;base64,…")
            }
            src.startsWith("asset:") -> {
                val path = src.removePrefix("asset:")
                if (path.isEmpty() || path.contains("..") || !Regex("[A-Za-z0-9_./-]+").matches(path)) bad("„$blockId“: ungültiger Asset-Pfad")
            }
            else -> bad("„$blockId“: Quelle muss data: oder asset: sein")
        }
    }

    private fun patchBlock(b: Block, f: JsonObject, pageIds: Set<String>): Block {
        val allowed = when (b) {
            is Block.Heading, is Block.Text -> setOf("text", "align")
            is Block.Button -> setOf("text", "target")
            is Block.List -> setOf("items", "item")
            is Block.Toggle -> setOf("text", "on")
            is Block.Value, is Block.Progress -> setOf("text", "value")
            is Block.Image -> setOf("src", "w", "h")
            is Block.Divider -> emptySet()
        }
        val unknown = f.keys - allowed
        if (unknown.isNotEmpty()) bad("„${b.id}“ (${b.type}) hat kein Feld ${unknown.joinToString { "„$it“" }}")

        fun text(key: String, old: String): String {
            val v = f[key] ?: return old
            val p = v as? JsonPrimitive ?: bad("„${b.id}“: „$key“ muss Text sein")
            if (p is JsonNull) bad("„${b.id}“: „$key“ muss Text sein")
            return p.content
        }

        fun align(old: Align): Align {
            val v = f["align"] ?: return old
            return Align.entries.firstOrNull { it.json == (v as? JsonPrimitive)?.content } ?: bad("„${b.id}“: align ist left oder center")
        }

        return when (b) {
            is Block.Heading -> b.copy(text = text("text", b.text), align = align(b.align))
            is Block.Text -> b.copy(text = text("text", b.text), align = align(b.align))
            is Block.Button -> {
                val target = when (val t = f["target"]) {
                    null -> b.target
                    is JsonNull -> null
                    is JsonPrimitive -> t.content.also {
                        if (it != Block.BACK && it !in pageIds) throw CommandException(CommandError.UNKNOWN_PAGE, "Ziel „$it“ gibt es nicht")
                    }
                    else -> bad("„${b.id}“: target ist eine Seiten-Kennung, @back oder null")
                }
                b.copy(text = text("text", b.text), target = target)
            }
            is Block.List -> {
                var items = b.items
                f["items"]?.let { v ->
                    val array = v as? JsonArray ?: bad("„${b.id}“: items muss eine Liste sein")
                    items = array.map { e ->
                        when (e) {
                            is JsonObject -> ListItem(
                                (e["text"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty(),
                                (e["done"] as? JsonPrimitive)?.booleanOrNull ?: false,
                            )
                            is JsonPrimitive -> if (e is JsonNull) bad("„${b.id}“: leerer Eintrag") else ListItem(e.content)
                            else -> bad("„${b.id}“: Eintrag muss Text oder {text, done} sein")
                        }
                    }
                }
                f["item"]?.let { v ->
                    val o = v as? JsonObject ?: bad("„${b.id}“: item muss {index, text?, done?} sein")
                    val index = (o["index"] as? JsonPrimitive)?.intOrNull ?: bad("„${b.id}“: item.index fehlt")
                    if (index !in items.indices) bad("„${b.id}“: Zeile $index gibt es nicht")
                    val old = items[index]
                    val newText = o["text"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content ?: bad("„${b.id}“: item.text muss Text sein") }
                    val newDone = o["done"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: bad("„${b.id}“: item.done muss true/false sein") }
                    items = items.toMutableList().also { it[index] = ListItem(newText ?: old.text, newDone ?: old.done) }
                }
                b.copy(items = items)
            }
            is Block.Toggle -> {
                val on = f["on"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: bad("„${b.id}“: on muss true/false sein") } ?: b.on
                b.copy(text = text("text", b.text), on = on)
            }
            is Block.Value -> b.copy(text = text("text", b.text), value = text("value", b.value))
            is Block.Progress -> {
                val value = f["value"]?.let {
                    val d = (it as? JsonPrimitive)?.doubleOrNull ?: bad("„${b.id}“: value muss eine Zahl sein")
                    if (d.isNaN() || d < 0 || d > 100) bad("„${b.id}“: value 0–100")
                    d.roundToInt()
                } ?: b.value
                b.copy(text = text("text", b.text), value = value)
            }
            is Block.Image -> {
                val src = when (val v = f["src"]) {
                    null -> b.src
                    is JsonNull -> null
                    is JsonPrimitive -> v.content
                    else -> bad("„${b.id}“: src muss Text oder null sein")
                }
                val maxW = if (b.bleed) Block.Image.BLEED_W else Block.Image.MAX_W
                val maxH = if (b.bleed) Block.Image.BLEED_H else Block.Image.MAX_H
                val w = f["w"]?.let { (it as? JsonPrimitive)?.intOrNull?.takeIf { v -> v in 1..maxW } ?: bad("„${b.id}“: w 1–$maxW") } ?: b.w
                val h = f["h"]?.let { (it as? JsonPrimitive)?.intOrNull?.takeIf { v -> v in 1..maxH } ?: bad("„${b.id}“: h 1–$maxH") } ?: b.h
                checkSource(b.id, src)
                b.copy(src = src, w = w, h = h)
            }
            is Block.Divider -> b
        }
    }
}
