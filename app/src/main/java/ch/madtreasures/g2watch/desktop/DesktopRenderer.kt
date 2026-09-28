package ch.madtreasures.g2watch.desktop

import java.util.Locale

/**
 * Paints the desktop into a 640×480 gray raster. Lit pixels are what the wearer sees and what
 * costs power, so the look is outlines and text on black rather than filled areas.
 */
class DesktopRenderer(private val text: TextPainter) {

    fun render(desktop: Desktop, target: GrayRaster) {
        target.clear()
        val layout = desktop.layout
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
        centered(target, status.time, Rect(bar.x, y, bar.w, bar.h), SMALL, TEXT, bold = true)
        val batteries = "Uhr ${percent(status.watchBattery)}   Brille ${percent(status.glassesBattery)}" +
            if (status.glassesCharging) " +" else ""
        text.draw(target, batteries, bar.right - 12 - text.measure(batteries, SMALL), y, SMALL, TEXT)
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
            AppId.POINTER -> {
                val speed = String.format(Locale.GERMANY, "Tempo %.1f×", status.speed)
                centered(target, speed, Rect(body.x, body.y + 20, body.w, 60), 40, BRIGHT, bold = true)
            }
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
                "Finger auf der Uhr halten: Menü",
                "Tipp auf den Bügel: Klick",
                "Doppeltipp auf den Bügel: Fenster zu",
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
