package ch.madtreasures.g2watch.apps.render

import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.HeadingSize
import ch.madtreasures.g2watch.apps.ListStyle
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.desktop.Rect
import ch.madtreasures.g2watch.desktop.TextPainter

/** Sizes of the page layout in pixels of the app area (02 §4.2). */
object PageMetrics {
    /** App area below the header, and without it (full screen). */
    const val WIDTH = 576
    const val HEIGHT = 260
    const val FULL_HEIGHT = 288

    /** Page margin left and right, space between blocks, space above the first and below the last. */
    const val MARGIN = 16
    const val GAP = 8
    const val PAD = 8

    const val HEADING = 28
    const val HEADING_GROSS = 36
    const val BODY = 22
    const val CAPTION = 16
    const val BUTTON = 40
    const val LIST_ROW = 30
    const val ROW = 36
    const val PROGRESS_BAR = 8
    const val DIVIDER = 2
    const val DIVIDER_SPACE = 6
    const val SCROLLBAR = 3
}

/**
 * Grey values of the 16 levels the glasses show: level n is drawn as n × 16 (level 15 as 255), which
 * the core rounds back to exactly n with `min(15, (v + 8) >> 4)`.
 */
object Levels {
    fun of(level: Int): Int = if (level >= 15) 255 else level.coerceAtLeast(0) * 16

    val STRONG = of(15)
    val TEXT = of(13)
    val BORDER = of(10)
    val DIM = of(9)
    val FAINT = of(6)
    val LINE = of(3)
    val SURFACE = of(2)
}

/** What the host can focus: a block, or one row of a checklist. */
data class FocusTarget(val block: String, val row: Int = -1)

/** A focusable target and where it is, in page coordinates. */
data class Focusable(val target: FocusTarget, val rect: Rect)

/** A block and its place on the page; [lines] is the wrapped text of headings and text blocks. */
class BlockBox(val block: Block, val rect: Rect, val lines: List<String> = emptyList())

/**
 * A page laid out for a viewport of [width] × [height]: every block's box in page coordinates (y = 0 at
 * the top of the content, before scrolling), the focusable targets in reading order and the content
 * height. The same layout serves drawing ([PageRenderer]) and pointer hits ([hit]).
 */
class PageLayout(
    val page: Page,
    val width: Int,
    val height: Int,
    val boxes: List<BlockBox>,
    val focusables: List<Focusable>,
    val contentHeight: Int,
) {
    val maxScroll: Int get() = (contentHeight - height).coerceAtLeast(0)

    fun focusable(target: FocusTarget?): Focusable? = target?.let { t -> focusables.firstOrNull { it.target == t } }

    fun box(blockId: String): BlockBox? = boxes.firstOrNull { it.block.id == blockId }

    companion object {
        /** Lays out [page] for the app area: [PageMetrics.HEIGHT] high with the header, else full height. */
        fun of(page: Page, text: TextPainter, width: Int = PageMetrics.WIDTH, height: Int = viewportHeight(page)): PageLayout {
            val m = PageMetrics
            val inner = width - 2 * m.MARGIN
            val boxes = ArrayList<BlockBox>()
            val focusables = ArrayList<Focusable>()
            var y = m.PAD
            for (block in page.blocks) {
                val x = m.MARGIN
                val box = when (block) {
                    is Block.Heading -> {
                        val size = if (block.size == HeadingSize.GROSS) m.HEADING_GROSS else m.HEADING
                        val lines = TextFit.wrap(text, block.text, size, bold = true, max = inner)
                        BlockBox(block, Rect(x, y, inner, lines.size * text.lineHeight(size)), lines)
                    }
                    is Block.Text -> {
                        val lines = TextFit.wrap(text, block.text, m.BODY, bold = false, max = inner)
                        BlockBox(block, Rect(x, y, inner, lines.size * text.lineHeight(m.BODY)), lines)
                    }
                    is Block.Button -> BlockBox(block, Rect(x, y, inner, m.BUTTON)).also {
                        focusables += Focusable(FocusTarget(block.id), it.rect)
                    }
                    is Block.List -> {
                        val rows = maxOf(1, block.items.size)
                        if (block.style == ListStyle.CHECKS) {
                            block.items.indices.forEach { i ->
                                focusables += Focusable(FocusTarget(block.id, i), Rect(x, y + i * m.LIST_ROW, inner, m.LIST_ROW))
                            }
                        }
                        BlockBox(block, Rect(x, y, inner, rows * m.LIST_ROW))
                    }
                    is Block.Toggle -> BlockBox(block, Rect(x, y, inner, m.ROW)).also {
                        focusables += Focusable(FocusTarget(block.id), it.rect)
                    }
                    is Block.Value -> BlockBox(block, Rect(x, y, inner, m.ROW))
                    is Block.Progress -> BlockBox(block, Rect(x, y, inner, text.lineHeight(m.CAPTION) + 4 + m.PROGRESS_BAR))
                    is Block.Divider -> BlockBox(block, Rect(x, y, inner, 2 * m.DIVIDER_SPACE + m.DIVIDER))
                    is Block.Image -> {
                        // Without margins: full width, and flush with the top as the first block.
                        val area = if (block.bleed) width else inner
                        val w = block.w.coerceAtMost(area)
                        val h = block.h.coerceAtMost(height)
                        val left = if (block.bleed) 0 else x
                        val ix = if (block.align == Align.CENTER) left + (area - w) / 2 else left
                        BlockBox(block, Rect(ix, if (block.bleed && boxes.isEmpty()) 0 else y, w, h))
                    }
                }
                boxes += box
                y = box.rect.bottom + m.GAP
            }
            val last = boxes.lastOrNull()
            val content = when {
                last == null -> 0
                // A borderless picture at the end also ends the page: no space below it.
                last.block is Block.Image && last.block.bleed -> last.rect.bottom
                else -> last.rect.bottom + m.PAD
            }
            return PageLayout(page, width, height, boxes, focusables, content)
        }

        fun viewportHeight(page: Page): Int = if (page.statusBar) PageMetrics.HEIGHT else PageMetrics.FULL_HEIGHT
    }
}

/** Fitting text into a width: word wrap and "…". */
object TextFit {
    /** Greedy word wrap; `\n` starts a new line; a word wider than [max] is cut. Empty text is one empty line. */
    fun wrap(text: TextPainter, s: String, size: Int, bold: Boolean, max: Int): List<String> {
        val out = ArrayList<String>()
        for (paragraph in s.split('\n')) {
            var line = ""
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (text.measure(candidate, size, bold) <= max) {
                    line = candidate
                    continue
                }
                if (line.isNotEmpty()) out += line
                // A single word that does not fit: cut it into pieces that do.
                var rest = word
                while (rest.isNotEmpty() && text.measure(rest, size, bold) > max) {
                    var n = rest.length - 1
                    while (n > 1 && text.measure(rest.take(n), size, bold) > max) n--
                    out += rest.take(n)
                    rest = rest.drop(n)
                }
                line = rest
            }
            out += line
        }
        return out
    }

    /** [s] shortened with "…" to fit [max]. */
    fun ellipsize(text: TextPainter, s: String, size: Int, bold: Boolean, max: Int): String {
        if (max <= 0) return ""
        if (text.measure(s, size, bold) <= max) return s
        var lo = 0
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (text.measure(s.take(mid) + "…", size, bold) <= max) lo = mid else hi = mid - 1
        }
        return if (lo > 0) s.take(lo).trimEnd() + "…" else ""
    }
}
