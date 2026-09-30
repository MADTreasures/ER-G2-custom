package ch.madtreasures.g2watch.apps.render

import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.ListStyle
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.apps.render.PageMetrics.BODY
import ch.madtreasures.g2watch.apps.render.PageMetrics.BORDER
import ch.madtreasures.g2watch.apps.render.PageMetrics.CAPTION
import ch.madtreasures.g2watch.apps.render.PageMetrics.DIM
import ch.madtreasures.g2watch.apps.render.PageMetrics.FAINT
import ch.madtreasures.g2watch.apps.render.PageMetrics.LINE
import ch.madtreasures.g2watch.apps.render.PageMetrics.STRONG
import ch.madtreasures.g2watch.apps.render.PageMetrics.SURFACE
import ch.madtreasures.g2watch.apps.render.PageMetrics.TEXT
import ch.madtreasures.g2watch.desktop.GrayRaster
import ch.madtreasures.g2watch.desktop.TextPainter

/** How a page is looked at: how far it is scrolled and which target has the focus (= the pointer's). */
data class PageView(val scroll: Int = 0, val focus: FocusTarget? = null)

/**
 * Paints a page into the app area (02 §4.2): lit outlines and text on black, because black is
 * see-through on the lens and large lit areas dazzle. The focused target gets a bright frame.
 */
class PageRenderer(private val text: TextPainter) {

    fun layout(page: Page, width: Int, height: Int): PageLayout = PageLayout.of(page, width, height, text)

    /**
     * Draws [page] into [target] (cleared first), shifted by the scroll of [view], plus the scroll
     * bar and [toast]. [image] gives the pixels of an image block by id.
     */
    fun render(
        page: Page,
        layout: PageLayout,
        view: PageView,
        target: GrayRaster,
        image: (String) -> GrayRaster? = { null },
        toast: String? = null,
    ) {
        target.clear()
        val scroll = view.scroll.coerceIn(0, layout.maxScroll)
        for (box in layout.boxes) {
            val top = box.y - scroll
            if (top + box.h < 0 || top >= target.height) continue
            drawBlock(target, box, top, view.focus?.takeIf { it.blockId == box.block.id }, image)
        }
        if (page.blocks.isEmpty()) {
            centered(target, "Leere Seite", 0, 0, target.width, target.height, CAPTION, FAINT, bold = false)
        }
        if (layout.maxScroll > 0) drawScrollBar(target, layout, scroll)
        if (!toast.isNullOrEmpty()) drawToast(target, toast)
    }

    private fun drawBlock(t: GrayRaster, box: BlockBox, top: Int, focus: FocusTarget?, image: (String) -> GrayRaster?) {
        val x = box.x
        val w = box.w
        when (val b = box.block) {
            is Block.Heading -> lines(t, box, top, PageLayout.headingSize(b), b.align, STRONG, bold = true)
            is Block.Text -> lines(t, box, top, BODY, b.align, TEXT, bold = false)
            is Block.Button -> drawButton(t, b, x, top, w, focused = focus != null)
            is Block.List -> drawList(t, b, x, top, w, focus?.row)
            is Block.Toggle -> {
                val switchLeft = drawSwitch(t, x + w, top + PageMetrics.ROW / 2, b.on)
                val label = PageLayout.ellipsize(text, b.text, BODY, false, switchLeft - x - 14)
                inRow(t, label, x, top, PageMetrics.ROW, BODY, TEXT, bold = false)
                if (focus != null) focusFrame(t, x, top, w, PageMetrics.ROW)
            }
            is Block.Value -> {
                val value = PageLayout.ellipsize(text, b.value, BODY, false, w / 2)
                val vw = if (value.isEmpty()) 0 else text.measure(value, BODY)
                if (value.isNotEmpty()) inRow(t, value, x + w - vw, top, PageMetrics.ROW, BODY, DIM, bold = false)
                val label = PageLayout.ellipsize(text, b.text, BODY, false, w - vw - 16)
                inRow(t, label, x, top, PageMetrics.ROW, BODY, TEXT, bold = false)
            }
            is Block.Progress -> {
                val v = b.value.coerceIn(0, 100)
                val captionH = text.lineHeight(CAPTION)
                val percent = "$v %"
                val pw = text.measure(percent, CAPTION)
                inRow(t, percent, x + w - pw, top, captionH, CAPTION, DIM, bold = false)
                inRow(t, PageLayout.ellipsize(text, b.text, CAPTION, false, w - pw - 16), x, top, captionH, CAPTION, DIM, bold = false)
                val barY = (top + captionH + 4).toFloat()
                Shapes.fillRoundRect(t, x.toFloat(), barY, w.toFloat(), PageMetrics.BAR.toFloat(), 4f, LINE)
                if (v > 0) {
                    val fill = maxOf(PageMetrics.BAR.toFloat(), w * v / 100f)
                    Shapes.fillRoundRect(t, x.toFloat(), barY, fill, PageMetrics.BAR.toFloat(), 4f, TEXT)
                }
            }
            is Block.Divider -> t.fillRect(x, top + PageMetrics.DIVIDER_SPACE, w, 2, LINE)
            is Block.Image -> image(b.id)?.let { blit(t, it, x, top, box.w, box.h) }
        }
    }

    private fun lines(t: GrayRaster, box: BlockBox, top: Int, size: Int, align: Align, value: Int, bold: Boolean) {
        val lh = text.lineHeight(size)
        for ((i, line) in box.lines.withIndex()) {
            if (line.isEmpty()) continue
            val x = if (align == Align.CENTER) box.x + (box.w - text.measure(line, size, bold)) / 2 else box.x
            text.draw(t, line, x, top + i * lh, size, value, bold)
        }
    }

    private fun drawButton(t: GrayRaster, b: Block.Button, x: Int, top: Int, w: Int, focused: Boolean) {
        val h = PageMetrics.BUTTON_HEIGHT
        Shapes.fillRoundRect(t, x.toFloat(), top.toFloat(), w.toFloat(), h.toFloat(), h / 2f, SURFACE)
        Shapes.strokeRoundRect(t, x.toFloat(), top.toFloat(), w.toFloat(), h.toFloat(), h / 2f, if (focused) 3f else 2f, if (focused) STRONG else BORDER)
        val cy = top + h / 2f
        var right = x + w - 18
        val ink = if (focused) STRONG else TEXT
        if (b.target != null && b.target != Block.BACK) {
            Shapes.polyline(t, floatArrayOf(right - 7f, cy - 7f, right.toFloat(), cy, right - 7f, cy + 7f), 2.5f, ink)
            right -= 22
        }
        b.badge?.let { badge ->
            val bw = text.measure(badge, CAPTION)
            inRow(t, badge, right - bw, top, h, CAPTION, DIM, bold = false)
            right -= bw + 12
        }
        val label = PageLayout.ellipsize(text, b.text, BODY, true, right - (x + 20))
        inRow(t, label, x + 20, top, h, BODY, ink, bold = true)
    }

    private fun drawList(t: GrayRaster, b: Block.List, x: Int, top: Int, w: Int, focusRow: Int?) {
        val rowH = PageMetrics.LIST_ROW
        if (b.items.isEmpty()) {
            inRow(t, "Leere Liste", x, top, rowH, BODY, FAINT, bold = false)
            return
        }
        for ((i, item) in b.items.withIndex()) {
            val rowTop = top + i * rowH
            if (rowTop + rowH < 0 || rowTop >= t.height) continue
            val cy = rowTop + rowH / 2f
            var tx = x + 36
            var ink = TEXT
            when (b.style) {
                ListStyle.BULLETS -> {
                    Shapes.fillCircle(t, x + 7f, cy, 3.5f, DIM)
                    tx = x + 24
                }
                ListStyle.NUMBERS -> {
                    val n = "${i + 1}."
                    inRow(t, n, x + 26 - text.measure(n, BODY), rowTop, rowH, BODY, DIM, bold = false)
                }
                ListStyle.CHECKS -> {
                    val bx = x.toFloat()
                    val by = cy - 10f
                    if (item.done) {
                        Shapes.fillRoundRect(t, bx, by, 20f, 20f, 4f, TEXT)
                        Shapes.polyline(t, floatArrayOf(bx + 5f, by + 10.5f, bx + 8.5f, by + 14f, bx + 15f, by + 6.5f), 2.4f, 0)
                        ink = DIM
                    } else {
                        Shapes.strokeRoundRect(t, bx, by, 20f, 20f, 4f, 2f, DIM)
                    }
                    tx = x + 34
                }
            }
            inRow(t, PageLayout.ellipsize(text, item.text, BODY, false, x + w - tx), tx, rowTop, rowH, BODY, ink, bold = false)
            if (focusRow == i) focusFrame(t, x, rowTop, w, rowH)
        }
    }

    /** The switch at the right end of a row; returns its left edge. */
    private fun drawSwitch(t: GrayRaster, right: Int, cy: Int, on: Boolean): Int {
        val w = 44f
        val h = 24f
        val x = right - w
        val y = cy - h / 2
        if (on) {
            Shapes.fillRoundRect(t, x, y, w, h, h / 2, TEXT)
            Shapes.fillCircle(t, x + w - h / 2, cy.toFloat(), h / 2 - 4, 0)
        } else {
            Shapes.strokeRoundRect(t, x, y, w, h, h / 2, 2f, FAINT)
            Shapes.fillCircle(t, x + h / 2, cy.toFloat(), h / 2 - 6, FAINT)
        }
        return x.toInt()
    }

    private fun focusFrame(t: GrayRaster, x: Int, y: Int, w: Int, h: Int) {
        Shapes.strokeRoundRect(t, x - 8f, y.toFloat(), w + 16f, h.toFloat(), 10f, 2f, STRONG)
    }

    private fun drawScrollBar(t: GrayRaster, layout: PageLayout, scroll: Int) {
        val trackTop = 6f
        val trackH = t.height - 12f
        val x = t.width - 6f
        val thumbH = maxOf(20f, trackH * layout.height / layout.contentHeight)
        val thumbY = trackTop + (trackH - thumbH) * scroll / layout.maxScroll
        Shapes.fillRoundRect(t, x, trackTop, 3f, trackH, 1.5f, LINE)
        Shapes.fillRoundRect(t, x, thumbY, 3f, thumbH, 1.5f, DIM)
    }

    private fun drawToast(t: GrayRaster, message: String) {
        val size = CAPTION + 2
        val label = PageLayout.ellipsize(text, message, size, true, t.width - 120)
        val w = text.measure(label, size, true) + 40
        val h = 36
        val x = (t.width - w) / 2
        val y = t.height - h - 10
        // A dark plate first, so the note stays readable over whatever lies beneath.
        t.fillRect(x, y, w, h, 0)
        Shapes.fillRoundRect(t, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), h / 2f, LINE)
        Shapes.strokeRoundRect(t, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), h / 2f, 2f, BORDER)
        centered(t, label, x, y, w, h, size, STRONG, bold = true)
    }

    private fun blit(t: GrayRaster, src: GrayRaster, x: Int, y: Int, w: Int, h: Int) {
        // Centred in the block's box when the picture is smaller (decoded with its aspect ratio kept).
        val ox = x + (w - src.width).coerceAtLeast(0) / 2
        val oy = y + (h - src.height).coerceAtLeast(0) / 2
        val cw = minOf(src.width, w)
        val ch = minOf(src.height, h)
        for (row in 0 until ch) {
            val ty = oy + row
            if (ty < 0 || ty >= t.height) continue
            for (col in 0 until cw) {
                val tx = ox + col
                if (tx < 0 || tx >= t.width) continue
                t.pixels[ty * t.width + tx] = src.pixels[row * src.width + col]
            }
        }
    }

    private fun inRow(t: GrayRaster, s: String, x: Int, top: Int, rowH: Int, size: Int, value: Int, bold: Boolean) {
        if (s.isEmpty()) return
        text.draw(t, s, x, top + (rowH - text.lineHeight(size)) / 2, size, value, bold)
    }

    private fun centered(t: GrayRaster, s: String, x: Int, y: Int, w: Int, h: Int, size: Int, value: Int, bold: Boolean) {
        if (s.isEmpty()) return
        text.draw(t, s, x + (w - text.measure(s, size, bold)) / 2, y + (h - text.lineHeight(size)) / 2, size, value, bold)
    }
}
