package ch.madtreasures.musik

import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.PatchBuilder
import java.util.Base64

/**
 * What the glasses show of each page, so that the app can build its pages from its state as often as it likes
 * and only changes go out: a patch when the same blocks only got other texts, values or pictures (a song moves
 * on every second), the page anew when blocks came, went or changed their kind.
 */
class Shown {
    private val pages = HashMap<String, Page>()

    fun sync(ui: AppContext, wanted: List<Page>) {
        val fresh = ArrayList<Page>()
        val patches = ArrayList<Pair<Page, Page>>()
        for (page in wanted) {
            val old = pages[page.id]
            when {
                old == page -> Unit
                old == null || !patchable(old, page) -> fresh += page
                else -> patches += old to page
            }
        }
        if (fresh.isNotEmpty()) ui.definePages(fresh)
        for ((old, page) in patches) {
            ui.patch(page.id) { old.blocks.zip(page.blocks).forEach { (o, n) -> if (o != n) patch(o, n) } }
        }
        for (page in fresh) pages[page.id] = page
        for ((_, page) in patches) pages[page.id] = page
    }

    /** What the glasses show of [pageId] now (as sent). */
    fun page(pageId: String): Page? = pages[pageId]

    private fun patchable(old: Page, new: Page): Boolean =
        old.name == new.name && old.statusBar == new.statusBar && old.input == new.input && old.notes == new.notes &&
            old.blocks.size == new.blocks.size && old.blocks.zip(new.blocks).all { (o, n) -> o.id == n.id && patchable(o, n) }

    private fun patchable(o: Block, n: Block): Boolean = when {
        o is Block.Heading && n is Block.Heading -> o.size == n.size
        o is Block.Text && n is Block.Text -> true
        o is Block.Button && n is Block.Button -> o.action == n.action
        o is Block.Toggle && n is Block.Toggle -> true
        o is Block.Value && n is Block.Value -> true
        o is Block.Progress && n is Block.Progress -> true
        o is Block.Image && n is Block.Image -> o.align == n.align && o.bleed == n.bleed
        o is Block.List && n is Block.List -> o.style == n.style
        o is Block.Divider && n is Block.Divider -> true
        else -> false
    }

    private fun PatchBuilder.patch(o: Block, n: Block) {
        val id = n.id
        when (n) {
            is Block.Heading -> {
                o as Block.Heading
                if (o.text != n.text) text(id, n.text)
                if (o.align != n.align) align(id, n.align)
            }
            is Block.Text -> {
                o as Block.Text
                if (o.text != n.text) text(id, n.text)
                if (o.align != n.align) align(id, n.align)
            }
            is Block.Button -> {
                o as Block.Button
                if (o.text != n.text) text(id, n.text)
                if (o.target != n.target) target(id, n.target)
            }
            is Block.Toggle -> {
                o as Block.Toggle
                if (o.text != n.text) text(id, n.text)
                if (o.on != n.on) on(id, n.on)
            }
            is Block.Value -> {
                o as Block.Value
                if (o.text != n.text) text(id, n.text)
                if (o.value != n.value) value(id, n.value)
            }
            is Block.Progress -> {
                o as Block.Progress
                if (o.text != n.text) text(id, n.text)
                if (o.value != n.value) progress(id, n.value)
            }
            is Block.Image -> image(id, n.src, n.w, n.h)
            is Block.List -> items(id, n.items)
            is Block.Divider -> Unit
        }
    }
}

/** Width and height of a PNG in a `data:image/png;base64,…` URL, from its header; null for anything else. */
fun pngSize(dataUrl: String): Pair<Int, Int>? {
    val comma = dataUrl.indexOf(',')
    if (comma < 0 || !dataUrl.startsWith("data:image/png;base64") || dataUrl.length < comma + 1 + 32) return null
    // 32 characters are 24 bytes: the signature, the length and name of the first chunk (IHDR), width and height.
    val head = try {
        Base64.getDecoder().decode(dataUrl.substring(comma + 1, comma + 1 + 32))
    } catch (e: IllegalArgumentException) {
        return null
    }
    if (!head.copyOfRange(0, 8).contentEquals(PNG) || String(head, 12, 4, Charsets.US_ASCII) != "IHDR") return null
    fun int(at: Int) = (head[at].toInt() and 0xFF shl 24) or (head[at + 1].toInt() and 0xFF shl 16) or
        (head[at + 2].toInt() and 0xFF shl 8) or (head[at + 3].toInt() and 0xFF)
    val w = int(16)
    val h = int(20)
    return if (w > 0 && h > 0) w to h else null
}

private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
