package ch.madtreasures.g2watch.desktop

import ch.madtreasures.g2watch.apps.render.Shapes
import ch.madtreasures.g2watch.apps.render.TextFit

/**
 * Paints the desktop into a 640×480 gray raster: the top bar with time and batteries, and the app on
 * the glasses below it (the launcher at home). Lit pixels are what the wearer sees and what costs
 * power, so the look is outlines and text on black rather than filled areas. Nothing is lit outside
 * the layout's stage ([DesktopLayout.stage]).
 */
class DesktopRenderer(private val text: TextPainter) {

    fun render(desktop: Desktop, target: GrayRaster) {
        target.clear()
        draw(desktop, target)
        // The stage is a hard limit: text that would run past it must not reach the edge columns.
        target.clearOutside(desktop.layout.stage)
    }

    private fun draw(desktop: Desktop, target: GrayRaster) {
        val app = desktop.app
        if (app == null) {
            // Only until the app host shows the launcher (or after it crashed): the bar alone.
            drawTopBar(desktop.status, desktop.layout, target)
            return
        }
        drawApp(desktop, app, target)
    }

    private fun drawTopBar(status: DesktopStatus, layout: DesktopLayout, target: GrayRaster) {
        val bar = layout.topBar
        val y = drawBarBase(status, bar, target)
        text.draw(target, "G2 Watch", bar.x + DesktopLayout.BAR_MARGIN, y, SMALL, TEXT, bold = true)
    }

    /** The line under the bar, the time in the middle and the batteries on the right; returns the text row. */
    private fun drawBarBase(status: DesktopStatus, bar: Rect, target: GrayRaster): Int {
        target.fillRect(bar.x, bar.bottom - 1, bar.w, 1, DIM)
        val y = bar.y + (bar.h - text.lineHeight(SMALL)) / 2
        centered(target, status.time, Rect(bar.x, y, bar.w, bar.h), SMALL, TEXT, bold = true)
        val batteries = "Uhr ${percent(status.watchBattery)}   Brille ${percent(status.glassesBattery)}" +
            if (status.glassesCharging) " +" else ""
        text.draw(target, batteries, bar.right - DesktopLayout.BAR_MARGIN - text.measure(batteries, SMALL), y, SMALL, TEXT)
        return y
    }

    /**
     * An app on the glasses: the header with back arrow, app name and page name (the bar's time and
     * batteries stay), and the app area as the app host drew it. Full-screen pages have no header.
     * At home, the launcher, the header holds just its name, where the bar's margin is.
     */
    private fun drawApp(desktop: Desktop, app: AppView, target: GrayRaster) {
        val layout = desktop.layout
        if (!app.fullScreen) {
            val bar = layout.topBar
            val y = drawBarBase(desktop.status, bar, target)
            if (app.home) {
                text.draw(target, app.title, bar.x + DesktopLayout.BAR_MARGIN, y, SMALL, TEXT, bold = true)
            } else {
                drawHeader(desktop, app, y, target)
            }
        }
        val area = layout.appArea(app.fullScreen)
        val w = minOf(app.width, area.w)
        for (row in 0 until minOf(app.height, area.h)) {
            System.arraycopy(app.pixels, row * app.width, target.pixels, (area.y + row) * target.width + area.x, w)
        }
    }

    /** The back arrow, the app's name and its page's name in the bar, at text row [y]; bright under the pointer. */
    private fun drawHeader(desktop: Desktop, app: AppView, y: Int, target: GrayRaster) {
        val layout = desktop.layout
        val back = layout.appBack
        val backHovered = desktop.hover == Target.AppBack
        val cx = back.x + back.w / 2f
        val cy = back.y + back.h / 2f - 1
        Shapes.polyline(
            target, floatArrayOf(cx + 3, cy - 7, cx - 4, cy, cx + 3, cy + 7), if (backHovered) 3f else 2.4f,
            if (backHovered) BRIGHT else TEXT,
        )
        if (backHovered) target.strokeRect(back.inset(1), BRIGHT, 2)
        val title = layout.appTitle
        val titleHovered = desktop.hover == Target.AppTitle
        val name = TextFit.ellipsize(text, app.title, SMALL, true, title.w - 16)
        var x = title.x + 8
        text.draw(target, name, x, y, SMALL, if (titleHovered) BRIGHT else TEXT, bold = true)
        x += text.measure(name, SMALL, true)
        app.subtitle?.let { sub ->
            val rest = TextFit.ellipsize(text, " · $sub", SMALL, false, title.right - 8 - x)
            text.draw(target, rest, x, y, SMALL, FRAME)
        }
        if (titleHovered) target.strokeRect(title.inset(1), BRIGHT, 2)
    }

    private fun centered(target: GrayRaster, s: String, rect: Rect, size: Int, value: Int, bold: Boolean = false) {
        if (s.isEmpty()) return
        val x = rect.x + (rect.w - text.measure(s, size, bold)) / 2
        val y = rect.y + (rect.h - text.lineHeight(size)) / 2
        text.draw(target, s, x, y, size, value, bold)
    }

    private fun percent(value: Int?): String = value?.let { "$it %" } ?: "– %"

    companion object {
        const val SMALL = 18
        const val BRIGHT = 255
        const val TEXT = 210
        const val FRAME = 120
        const val DIM = 80
    }
}
