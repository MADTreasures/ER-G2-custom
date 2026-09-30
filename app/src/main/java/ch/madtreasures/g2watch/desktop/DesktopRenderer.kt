package ch.madtreasures.g2watch.desktop

import ch.madtreasures.g2watch.apps.render.PageLayout
import ch.madtreasures.g2watch.apps.render.Shapes

/**
 * Paints the desktop into a 640×480 gray raster. Lit pixels are what the wearer sees and what
 * costs power, so the look is outlines and text on black rather than filled areas. While the apps
 * are open, the header shows "‹" and the page name, and the app host's picture fills the app area.
 */
class DesktopRenderer(private val text: TextPainter) {

    fun render(desktop: Desktop, target: GrayRaster) {
        target.clear()
        val layout = desktop.layout
        if (desktop.appsOpen) {
            drawApps(desktop, target)
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
        target.fillRect(bar.x, bar.bottom - 1, bar.w, 1, DIM)
        val y = bar.y + (bar.h - text.lineHeight(SMALL)) / 2
        text.draw(target, "G2 Watch", bar.x + 12, y, SMALL, TEXT, bold = true)
        drawClockAndBatteries(status, bar, y, target)
    }

    private fun drawClockAndBatteries(status: DesktopStatus, bar: Rect, y: Int, target: GrayRaster) {
        centered(target, status.time, Rect(bar.x, y, bar.w, bar.h), SMALL, TEXT, bold = true)
        val batteries = "Uhr ${percent(status.watchBattery)}   Brille ${percent(status.glassesBattery)}" +
            if (status.glassesCharging) " +" else ""
        text.draw(target, batteries, bar.right - 12 - text.measure(batteries, SMALL), y, SMALL, TEXT)
    }

    /** The header of the apps ("‹", page name, clock, batteries) and the app host's picture. */
    private fun drawApps(desktop: Desktop, target: GrayRaster) {
        val layout = desktop.layout
        val frame = desktop.appFrame
        if (frame == null || !frame.fullscreen) {
            val bar = layout.topBar
            target.fillRect(bar.x, bar.bottom - 1, bar.w, 1, DIM)
            val y = bar.y + (bar.h - text.lineHeight(SMALL)) / 2
            val back = layout.appBack
            val backHovered = desktop.hover == Target.AppBack
            val cx = back.x + 20f
            val cy = back.y + back.h / 2f
            Shapes.polyline(target, floatArrayOf(cx + 4f, cy - 7f, cx - 3f, cy, cx + 4f, cy + 7f), if (backHovered) 3f else 2.2f, if (backHovered) BRIGHT else TEXT)
            val titleBox = layout.appTitle
            val title = PageLayout.ellipsize(text, frame?.title ?: AppId.APPS.title, SMALL, true, titleBox.w - 8)
            val titleHovered = desktop.hover == Target.AppMenu
            text.draw(target, title, titleBox.x, y, SMALL, if (titleHovered) BRIGHT else TEXT, bold = true)
            if (titleHovered) target.fillRect(titleBox.x, bar.bottom - 5, text.measure(title, SMALL, true), 2, BRIGHT)
            drawClockAndBatteries(desktop.status, bar, y, target)
        }
        if (frame == null) return
        val area = desktop.appArea
        val src = frame.pixels
        for (row in 0 until minOf(src.height, area.h)) {
            System.arraycopy(src.pixels, row * src.width, target.pixels, (area.y + row) * target.width + area.x, minOf(src.width, area.w))
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
                "Tipp auf den Bügel: Klick",
                "Doppeltipp auf den Bügel: zurück",
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
