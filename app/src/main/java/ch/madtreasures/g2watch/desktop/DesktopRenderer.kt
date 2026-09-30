package ch.madtreasures.g2watch.desktop

import ch.madtreasures.g2watch.apps.render.Shapes
import ch.madtreasures.g2watch.apps.render.TextFit

/**
 * Paints the desktop into a 640×480 gray raster. Lit pixels are what the wearer sees and what
 * costs power, so the look is outlines and text on black rather than filled areas.
 */
class DesktopRenderer(private val text: TextPainter) {

    fun render(desktop: Desktop, target: GrayRaster) {
        target.clear()
        val layout = desktop.layout
        desktop.app?.let {
            drawApp(desktop, it, target)
            return
        }
        drawTopBar(desktop.status, layout, target)
        val open = desktop.openApp
        if (open == null) {
            for ((app, rect) in layout.tiles) {
                drawBox(target, rect, app.title, TILE_TEXT, hovered = desktop.hover == Target.Tile(app))
            }
        } else {
            drawWindow(desktop, open, target)
        }
    }

    private fun drawTopBar(status: DesktopStatus, layout: DesktopLayout, target: GrayRaster) {
        val bar = layout.topBar
        val y = drawBarBase(status, bar, target)
        text.draw(target, "G2 Watch", bar.x + 12, y, SMALL, TEXT, bold = true)
    }

    /** The line under the bar, the time in the middle and the batteries on the right; returns the text row. */
    private fun drawBarBase(status: DesktopStatus, bar: Rect, target: GrayRaster): Int {
        target.fillRect(bar.x, bar.bottom - 1, bar.w, 1, DIM)
        val y = bar.y + (bar.h - text.lineHeight(SMALL)) / 2
        centered(target, status.time, Rect(bar.x, y, bar.w, bar.h), SMALL, TEXT, bold = true)
        val batteries = "Uhr ${percent(status.watchBattery)}   Brille ${percent(status.glassesBattery)}" +
            if (status.glassesCharging) " +" else ""
        text.draw(target, batteries, bar.right - 12 - text.measure(batteries, SMALL), y, SMALL, TEXT)
        return y
    }

    /**
     * An app on the glasses: the header with back arrow, app name and page name (the bar's time and
     * batteries stay), and the app area as the app host drew it. Full-screen pages have no header.
     */
    private fun drawApp(desktop: Desktop, app: AppView, target: GrayRaster) {
        val layout = desktop.layout
        if (!app.fullScreen) {
            val bar = layout.topBar
            val y = drawBarBase(desktop.status, bar, target)
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
        val area = layout.appArea(app.fullScreen)
        val w = minOf(app.width, area.w)
        for (row in 0 until minOf(app.height, area.h)) {
            System.arraycopy(app.pixels, row * app.width, target.pixels, (area.y + row) * target.width + area.x, w)
        }
    }

    private fun drawWindow(desktop: Desktop, app: AppId, target: GrayRaster) {
        val layout = desktop.layout
        target.fillRect(layout.window, 0)
        target.strokeRect(layout.window, FRAME, 2)
        val title = layout.titleBar
        target.fillRect(title.x, title.bottom - 1, title.w, 1, DIM)
        text.draw(target, app.title, title.x + 12, title.y + (title.h - text.lineHeight(MEDIUM)) / 2, MEDIUM, TEXT, bold = true)
        drawBox(target, layout.closeButton, "×", MEDIUM, hovered = desktop.hover == Target.Close)

        val body = layout.windowBody
        val status = desktop.status
        when (app) {
            AppId.CLOCK -> {
                centered(target, status.time, Rect(body.x, body.y + 20, body.w, 90), 72, BRIGHT, bold = true)
                centered(target, status.date, Rect(body.x, body.y + 120, body.w, 30), MEDIUM, TEXT)
            }
            AppId.NOTE -> lines(
                target, body,
                "Die Uhr ist der Rechner,",
                "die Brille ist der Bildschirm.",
                "",
                "Hier entstehen später Notizen per Diktat.",
            )
            AppId.COUNTER -> centered(target, desktop.counter.toString(), Rect(body.x, body.y + 10, body.w, 80), 64, BRIGHT, bold = true)
            // Opens the launcher instead of a window.
            AppId.APPS -> Unit
            AppId.INFO -> lines(
                target, body,
                "Verbindung: ${status.connection}",
                "Firmware: ${status.firmware}",
                "Brille: ${percent(status.glassesBattery)}" + if (status.glassesCharging) " (lädt)" else "",
                "Uhr: ${percent(status.watchBattery)}",
            )
            AppId.HELP -> lines(
                target, body,
                "Finger auf der Uhr: Zeiger bewegen",
                "Doppeltipp auf der Uhr: Klick",
                "Zahnrad auf der Uhr halten: Einstellungen",
                "Bügel: Tipp = Klick, Doppeltipp = zurück",
                "In Apps: Wischen am Bügel = nächster Knopf",
                "Tippen, dann halten = App-Menü",
            )
        }
        for ((id, rect) in layout.buttons(app)) {
            drawBox(target, rect, id.label, MEDIUM, hovered = desktop.hover == Target.Button(id))
        }
    }

    /** A framed box with a centred label; the hovered one gets a thick, bright frame. */
    private fun drawBox(target: GrayRaster, rect: Rect, label: String, size: Int, hovered: Boolean) {
        target.strokeRect(rect, if (hovered) BRIGHT else FRAME, if (hovered) 4 else 2)
        centered(target, label, rect, size, if (hovered) BRIGHT else TEXT, bold = hovered)
    }

    private fun centered(target: GrayRaster, s: String, rect: Rect, size: Int, value: Int, bold: Boolean = false) {
        if (s.isEmpty()) return
        val x = rect.x + (rect.w - text.measure(s, size, bold)) / 2
        val y = rect.y + (rect.h - text.lineHeight(size)) / 2
        text.draw(target, s, x, y, size, value, bold)
    }

    private fun lines(target: GrayRaster, body: Rect, vararg lines: String) {
        var y = body.y + 6
        for (line in lines) {
            if (line.isNotEmpty()) text.draw(target, line, body.x + 8, y, MEDIUM, TEXT)
            y += text.lineHeight(MEDIUM) + 4
        }
    }

    private fun percent(value: Int?): String = value?.let { "$it %" } ?: "– %"

    companion object {
        const val SMALL = 18
        const val MEDIUM = 22
        const val TILE_TEXT = 26
        const val BRIGHT = 255
        const val TEXT = 210
        const val FRAME = 120
        const val DIM = 80
    }
}
