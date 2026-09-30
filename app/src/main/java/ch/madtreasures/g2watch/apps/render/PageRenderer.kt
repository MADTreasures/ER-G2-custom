package ch.madtreasures.g2watch.apps.render

import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.HeadingSize
import ch.madtreasures.g2watch.apps.ListStyle
import ch.madtreasures.g2watch.apps.Page
import ch.madtreasures.g2watch.desktop.GrayRaster
import ch.madtreasures.g2watch.desktop.Rect
import ch.madtreasures.g2watch.desktop.TextPainter

/** How a laid-out page is shown right now. */
class PageLook(
    /** Pixels the page is scrolled down by, 0…[PageLayout.maxScroll]. */
    val scroll: Int = 0,
    /** Highlighted target (focus, and the target under the pointer). */
    val focus: FocusTarget? = null,
    /** A short note over the bottom of the page. */
    val toast: String? = null,
    /** Pixels of image blocks by block id, each exactly the block's size (w × h). */
    val images: Map<String, GrayRaster> = emptyMap(),
)

/**
 * Draws a page into the app area (02 §4.2): text and outlines on black, because black is see-through on
 * the lens and bright areas dazzle. The focused target gets a brighter, thicker outline.
 */
class PageRenderer(private val text: TextPainter) {

    fun layout(page: Page): PageLayout = PageLayout.of(page, text)

    /** Draws [layout] into [target], which must be the viewport ([PageLayout.width] × [PageLayout.height]). */
    fun render(target: GrayRaster, layout: PageLayout, look: PageLook = PageLook()) {
        require(target.width == layout.width && target.height == layout.height) { "raster does not match the viewport" }
        target.clear()
        val scroll = look.scroll.coerceIn(0, layout.maxScroll)
        for (box in layout.boxes) {
            val r = box.rect
            val top = r.y - scroll
            if (top + r.h < 0 || top >= layout.height) continue
            draw(target, box, Rect(r.x, top, r.w, r.h), look)
        }
        if (layout.maxScroll > 0) drawScrollbar(target, layout, scroll)
        look.toast?.let { drawToast(target, it) }
    }

    private fun draw(target: GrayRaster, box: BlockBox, r: Rect, look: PageLook) {
        val m = PageMetrics
        val focused = look.focus?.takeIf { it.block == box.block.id }
        when (val b = box.block) {
            is Block.Heading -> {
                val size = if (b.size == HeadingSize.GROSS) m.HEADING_GROSS else m.HEADING
                lines(target, box.lines, r, size, bold = true, Levels.STRONG, b.align)
            }
            is Block.Text -> lines(target, box.lines, r, m.BODY, bold = false, Levels.TEXT, b.align)
            is Block.Button -> button(target, b, r, focused != null)
            is Block.List -> list(target, b, r, focused?.row)
            is Block.Toggle -> toggle(target, b, r, focused != null)
            is Block.Value -> {
                val value = TextFit.ellipsize(text, b.value, m.BODY, false, r.w / 2)
                val vw = if (value.isEmpty()) 0 else text.measure(value, m.BODY)
                if (value.isNotEmpty()) textIn(target, value, r.right - vw, r, m.BODY, false, Levels.DIM)
                val label = TextFit.ellipsize(text, b.text, m.BODY, false, r.w - vw - 16)
                textIn(target, label, r.x, r, m.BODY, false, Levels.TEXT)
            }
            is Block.Progress -> progress(target, b, r)
            is Block.Divider -> Shapes.fillRoundRect(
                target, r.x.toFloat(), (r.y + m.DIVIDER_SPACE).toFloat(), r.w.toFloat(), m.DIVIDER.toFloat(), 1f, Levels.LINE,
            )
            is Block.Image -> look.images[b.id]?.let { blit(target, it, r) }
        }
    }

    private fun lines(target: GrayRaster, lines: List<String>, r: Rect, size: Int, bold: Boolean, value: Int, align: Align) {
        val lh = text.lineHeight(size)
        lines.forEachIndexed { i, line ->
            if (line.isEmpty()) return@forEachIndexed
            val x = if (align == Align.CENTER) r.x + (r.w - text.measure(line, size, bold)) / 2 else r.x
            text.draw(target, line, x, r.y + i * lh, size, value, bold)
        }
    }

    /** One line of text, vertically centred in [row]. */
    private fun textIn(target: GrayRaster, s: String, x: Int, row: Rect, size: Int, bold: Boolean, value: Int) {
        if (s.isEmpty()) return
        text.draw(target, s, x, row.y + (row.h - text.lineHeight(size)) / 2, size, value, bold)
    }

    private fun button(target: GrayRaster, b: Block.Button, r: Rect, focused: Boolean) {
        val radius = r.h / 2f
        Shapes.fillRoundRect(target, r.x.toFloat(), r.y.toFloat(), r.w.toFloat(), r.h.toFloat(), radius, Levels.SURFACE)
        Shapes.strokeRoundRect(
            target, r.x.toFloat(), r.y.toFloat(), r.w.toFloat(), r.h.toFloat(), radius,
            if (focused) 3f else 2f, if (focused) Levels.STRONG else Levels.BORDER,
        )
        var right = r.right - 20
        if (b.target != null) {
            val cy = r.y + r.h / 2f
            val chevron = if (b.target == Block.BACK) {
                floatArrayOf(right - 0f, cy - 7f, right - 8f, cy, right - 0f, cy + 7f)
            } else {
                floatArrayOf(right - 8f, cy - 7f, right - 0f, cy, right - 8f, cy + 7f)
            }
            Shapes.polyline(target, chevron, 2.5f, if (focused) Levels.STRONG else Levels.TEXT)
            right -= 22
        }
        val label = TextFit.ellipsize(text, b.text, PageMetrics.BODY, true, right - r.x - 20)
        textIn(target, label, r.x + 20, r, PageMetrics.BODY, true, if (focused) Levels.STRONG else Levels.TEXT)
    }

    private fun list(target: GrayRaster, b: Block.List, r: Rect, focusedRow: Int?) {
        val m = PageMetrics
        if (b.items.isEmpty()) return
        b.items.forEachIndexed { i, item ->
            val row = Rect(r.x, r.y + i * m.LIST_ROW, r.w, m.LIST_ROW)
            val cy = row.y + row.h / 2f
            var tx = r.x + 36
            var level = Levels.TEXT
            when (b.style) {
                ListStyle.BULLETS -> {
                    Shapes.fillCircle(target, r.x + 7f, cy, 3.5f, Levels.DIM)
                    tx = r.x + 24
                }
                ListStyle.NUMBERS -> {
                    val n = "${i + 1}."
                    textIn(target, n, r.x + 26 - text.measure(n, m.BODY), row, m.BODY, false, Levels.DIM)
                }
                ListStyle.CHECKS -> {
                    val bx = r.x.toFloat()
                    val by = cy - 11f
                    if (item.done) {
                        Shapes.fillRoundRect(target, bx, by, 22f, 22f, 5f, Levels.TEXT)
                        Shapes.polyline(target, floatArrayOf(bx + 5.5f, by + 11.5f, bx + 9.5f, by + 15.5f, bx + 16.5f, by + 7f), 2.6f, 0)
                        level = Levels.DIM
                    } else {
                        Shapes.strokeRoundRect(target, bx, by, 22f, 22f, 5f, 2f, Levels.DIM)
                    }
                    if (focusedRow == i) focusRing(target, row)
                }
            }
            textIn(target, TextFit.ellipsize(text, item.text, m.BODY, false, r.right - tx), tx, row, m.BODY, false, level)
        }
    }

    private fun toggle(target: GrayRaster, b: Block.Toggle, r: Rect, focused: Boolean) {
        val w = 46f
        val h = 26f
        val x = r.right - w
        val cy = r.y + r.h / 2f
        val y = cy - h / 2
        if (b.on) {
            Shapes.fillRoundRect(target, x, y, w, h, h / 2, Levels.TEXT)
            Shapes.fillCircle(target, x + w - h / 2, cy, h / 2 - 4, 0)
        } else {
            Shapes.strokeRoundRect(target, x, y, w, h, h / 2, 2f, Levels.FAINT)
            Shapes.fillCircle(target, x + h / 2, cy, h / 2 - 6, Levels.FAINT)
        }
        val label = TextFit.ellipsize(text, b.text, PageMetrics.BODY, false, (x - r.x - 14).toInt())
        textIn(target, label, r.x, r, PageMetrics.BODY, false, if (focused) Levels.STRONG else Levels.TEXT)
        if (focused) focusRing(target, r)
    }

    private fun progress(target: GrayRaster, b: Block.Progress, r: Rect) {
        val m = PageMetrics
        val v = b.value.coerceIn(0, 100)
        val caption = Rect(r.x, r.y, r.w, text.lineHeight(m.CAPTION))
        val percent = "$v %"
        val pw = text.measure(percent, m.CAPTION)
        textIn(target, percent, r.right - pw, caption, m.CAPTION, false, Levels.DIM)
        textIn(target, TextFit.ellipsize(text, b.text, m.CAPTION, false, r.w - pw - 16), r.x, caption, m.CAPTION, false, Levels.DIM)
        val barY = (r.bottom - m.PROGRESS_BAR).toFloat()
        val bar = m.PROGRESS_BAR.toFloat()
        Shapes.fillRoundRect(target, r.x.toFloat(), barY, r.w.toFloat(), bar, bar / 2, Levels.LINE)
        if (v > 0) Shapes.fillRoundRect(target, r.x.toFloat(), barY, maxOf(bar, r.w * v / 100f), bar, bar / 2, Levels.TEXT)
    }

    /** The outline that marks a focused row. */
    private fun focusRing(target: GrayRaster, row: Rect) {
        Shapes.strokeRoundRect(target, row.x - 8f, row.y + 1f, row.w + 16f, row.h - 2f, 8f, 2f, Levels.STRONG)
    }

    private fun blit(target: GrayRaster, image: GrayRaster, r: Rect) {
        val w = minOf(image.width, r.w)
        val h = minOf(image.height, r.h)
        for (row in 0 until h) {
            val ty = r.y + row
            if (ty !in 0 until target.height) continue
            val x0 = maxOf(0, r.x)
            val x1 = minOf(target.width, r.x + w)
            if (x0 >= x1) continue
            System.arraycopy(image.pixels, row * image.width + (x0 - r.x), target.pixels, ty * target.width + x0, x1 - x0)
        }
    }

    private fun drawScrollbar(target: GrayRaster, layout: PageLayout, scroll: Int) {
        val x = layout.width - PageMetrics.MARGIN / 2 - PageMetrics.SCROLLBAR / 2f
        val trackTop = 6f
        val trackH = layout.height - 12f
        val thumbH = maxOf(24f, trackH * layout.height / layout.contentHeight)
        val thumbY = trackTop + (trackH - thumbH) * scroll / layout.maxScroll
        val w = PageMetrics.SCROLLBAR.toFloat()
        Shapes.fillRoundRect(target, x, trackTop, w, trackH, w / 2, Levels.LINE)
        Shapes.fillRoundRect(target, x, thumbY, w, thumbH, w / 2, Levels.DIM)
    }

    private fun drawToast(target: GrayRaster, message: String) {
        val size = 18
        val s = TextFit.ellipsize(text, message, size, false, target.width - 120)
        val w = text.measure(s, size) + 40f
        val h = 36f
        val x = (target.width - w) / 2
        val y = target.height - h - 10f
        // Opaque, so the page below does not shine through the note.
        Shapes.fillRoundRect(target, x, y, w, h, h / 2, 0)
        Shapes.fillRoundRect(target, x, y, w, h, h / 2, Levels.SURFACE)
        Shapes.strokeRoundRect(target, x, y, w, h, h / 2, 2f, Levels.BORDER)
        text.draw(target, s, (x + 20).toInt(), (y + (h - text.lineHeight(size)) / 2).toInt(), size, Levels.TEXT)
    }
}
