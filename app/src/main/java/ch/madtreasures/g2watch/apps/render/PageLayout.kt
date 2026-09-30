package ch.madtreasures.g2watch.apps.render

import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.HeadingSize
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.desktop.Rect
import ch.madtreasures.g2watch.desktop.TextPainter

/** Sizes and gray values of the pages, from 02 §4.2. A level n is drawn with gray n × 16 (15 = 255). */
object PageMetrics {
    /** The app area below the 28 px header, and without it. */
    const val WIDTH = 576
    const val HEIGHT = 260
    const val FULL_HEIGHT = 288

    const val MARGIN = 16
    const val GAP = 8
    const val PAD_TOP = 8
    const val PAD_BOTTOM = 8

    const val HEADING = 28
    const val HEADING_GROSS = 36
    const val BODY = 22
    const val CAPTION = 16
    const val BUTTON_HEIGHT = 40
    const val LIST_ROW = 30
    const val ROW = 36
    const val BAR = 8
    const val DIVIDER_SPACE = 6

    const val STRONG = 255
    const val TEXT = 13 * 16
    const val DIM = 9 * 16
    const val FAINT = 6 * 16
    const val SURFACE = 2 * 16
    const val BORDER = 10 * 16
    const val LINE = 3 * 16
}

/** What the pointer and the temple swipes can focus: a block, or one row of a `checks` list. */
data class FocusTarget(val blockId: String, val row: Int = -1)

/** A block's place in the content, which starts at y = 0 above the scrolled view. */
class BlockBox(
    val block: Block,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    /** Wrapped lines of headings and texts. */
    val lines: List<String> = emptyList(),
) {
    val rect: Rect get() = Rect(x, y, w, h)
}

/**
 * Where the blocks of a page go in an area of [width] × [height] (02 §4.2): margins of 16 px,
 * 8 px between blocks, stacked from the top. Taller content scrolls; see [maxScroll].
 */
class PageLayout(
    val width: Int,
    val height: Int,
    val boxes: List<BlockBox>,
    val contentHeight: Int,
) {
    val maxScroll: Int get() = (contentHeight - height).coerceAtLeast(0)

    fun box(blockId: String): BlockBox? = boxes.firstOrNull { it.block.id == blockId }

    /** The focusable targets from top to bottom. */
    val targets: List<FocusTarget> by lazy {
        boxes.flatMap { box ->
            val b = box.block
            when {
                !b.focusable -> emptyList()
                b is Block.List -> b.items.indices.map { FocusTarget(b.id, it) }
                else -> listOf(FocusTarget(b.id))
            }
        }
    }

    /** The target's area in content coordinates, or null if it is not on the page (any more). */
    fun rectOf(target: FocusTarget): Rect? {
        val box = box(target.blockId) ?: return null
        val b = box.block
        return when {
            b is Block.List && target.row >= 0 -> if (target.row < b.items.size) {
                Rect(box.x, box.y + target.row * PageMetrics.LIST_ROW, box.w, PageMetrics.LIST_ROW)
            } else {
                null
            }
            else -> box.rect
        }
    }

    /** The block under ([x], [contentY]), with the gaps split between neighbours. */
    fun blockAt(x: Int, contentY: Int): BlockBox? {
        if (x < 0 || x >= width) return null
        val half = PageMetrics.GAP / 2
        return boxes.firstOrNull { contentY >= it.y - half && contentY < it.y + it.h + half }
    }

    /** The focusable target under ([x], [contentY]), or null. */
    fun targetAt(x: Int, contentY: Int): FocusTarget? {
        val box = blockAt(x, contentY) ?: return null
        val b = box.block
        if (!b.focusable) return null
        if (b is Block.List) {
            val row = ((contentY - box.y) / PageMetrics.LIST_ROW).coerceIn(0, b.items.size - 1)
            return FocusTarget(b.id, row)
        }
        return FocusTarget(b.id)
    }

    /** The scroll offset that shows [target] with a little room, changing [scroll] as little as possible. */
    fun scrollToShow(target: FocusTarget, scroll: Int): Int {
        val r = rectOf(target) ?: return scroll.coerceIn(0, maxScroll)
        val room = PageMetrics.GAP
        var s = scroll
        if (r.bottom + room > s + height) s = r.bottom + room - height
        if (r.y - room < s) s = r.y - room
        return s.coerceIn(0, maxScroll)
    }

    companion object {
        fun of(page: Page, width: Int, height: Int, text: TextPainter): PageLayout {
            val m = PageMetrics
            val inner = width - 2 * m.MARGIN
            val first = page.blocks.firstOrNull()
            val last = page.blocks.lastOrNull()
            var y = if (first is Block.Image && first.bleed) 0 else m.PAD_TOP
            val boxes = ArrayList<BlockBox>(page.blocks.size)
            for ((i, b) in page.blocks.withIndex()) {
                if (i > 0) y += m.GAP
                val box = when (b) {
                    is Block.Heading -> {
                        val size = headingSize(b)
                        val lines = wrap(text, b.text, size, true, inner)
                        BlockBox(b, m.MARGIN, y, inner, lines.size * text.lineHeight(size), lines)
                    }
                    is Block.Text -> {
                        val lines = wrap(text, b.text, m.BODY, false, inner)
                        BlockBox(b, m.MARGIN, y, inner, lines.size * text.lineHeight(m.BODY), lines)
                    }
                    is Block.Button -> BlockBox(b, m.MARGIN, y, inner, m.BUTTON_HEIGHT)
                    is Block.List -> BlockBox(b, m.MARGIN, y, inner, maxOf(1, b.items.size) * m.LIST_ROW)
                    is Block.Toggle, is Block.Value -> BlockBox(b, m.MARGIN, y, inner, m.ROW)
                    is Block.Progress -> BlockBox(b, m.MARGIN, y, inner, text.lineHeight(m.CAPTION) + 4 + m.BAR)
                    is Block.Divider -> BlockBox(b, m.MARGIN, y, inner, 2 * m.DIVIDER_SPACE + 2)
                    is Block.Image -> {
                        val maxW = if (b.bleed) width else inner
                        val w = b.w.coerceIn(1, maxW)
                        val left = if (b.bleed) 0 else m.MARGIN
                        val x = when (b.align) {
                            Align.LEFT -> left
                            Align.CENTER -> left + (maxW - w) / 2
                        }
                        BlockBox(b, x, y, w, b.h.coerceAtLeast(1))
                    }
                }
                boxes += box
                y += box.h
            }
            if (!(last is Block.Image && last.bleed)) y += m.PAD_BOTTOM
            return PageLayout(width, height, boxes, y)
        }

        fun headingSize(b: Block.Heading): Int =
            if (b.size == HeadingSize.GROSS) PageMetrics.HEADING_GROSS else PageMetrics.HEADING

        /**
         * Breaks [s] into lines of at most [max] pixels: at `\n`, between words, and inside words
         * that are wider than a line on their own.
         */
        fun wrap(text: TextPainter, s: String, size: Int, bold: Boolean, max: Int): List<String> {
            val out = ArrayList<String>()
            for (para in s.split('\n')) {
                var line = ""
                for (word in para.split(' ').filter { it.isNotEmpty() }) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (text.measure(candidate, size, bold) <= max) {
                        line = candidate
                        continue
                    }
                    if (line.isNotEmpty()) out += line
                    line = word
                    // A single word wider than the line is split where it no longer fits.
                    while (text.measure(line, size, bold) > max && line.length > 1) {
                        var cut = line.length - 1
                        while (cut > 1 && text.measure(line.substring(0, cut), size, bold) > max) cut--
                        out += line.substring(0, cut)
                        line = line.substring(cut)
                    }
                }
                out += line
            }
            return out
        }

        /** [s] cut to at most [max] pixels, with "…" where it was cut. */
        fun ellipsize(text: TextPainter, s: String, size: Int, bold: Boolean, max: Int): String {
            if (max <= 0) return ""
            if (text.measure(s, size, bold) <= max) return s
            var lo = 0
            var hi = s.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (text.measure(s.substring(0, mid) + "…", size, bold) <= max) lo = mid else hi = mid - 1
            }
            return if (lo > 0) s.substring(0, lo).trimEnd() + "…" else ""
        }
    }
}
