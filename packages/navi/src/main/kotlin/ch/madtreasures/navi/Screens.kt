package ch.madtreasures.navi

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The app's pictures for the glasses, each a whole full-screen page (576 × 288): the map to look around, the
 * navigation in the manner of Even's own (hint bottom left, a small map turning with the direction of travel
 * on the right, what is left top right; a tap shows the whole map north up), the route from above, and
 * plain notes.
 */
class Screens(private val fonts: Fonts, private val renderer: MapRenderer) {

    /** The map around [center] with [overlay]; [status] top left (GPS), a scale bottom right. */
    fun map(sources: List<MapSource>, center: LatLon, zoom: Double, overlay: Overlay, status: String?): Raster {
        val r = Raster(W, H)
        val view = MapView(center, zoom, W, H)
        renderer.render(r, view, sources, overlay)
        status?.let { fonts.small.draw(r, it, 10.0, 22.0, Ink.level(12), halo = 2) }
        scaleBar(r, view)
        return r
    }

    /** The navigation screen for [state] on [route]; [detail] as in [Overlay]. */
    fun navigation(
        sources: List<MapSource>,
        route: Route,
        state: NavState,
        full: Boolean,
        zoom: Double,
        nowMs: Long,
        note: String? = null,
        detail: Int = 2,
    ): Raster {
        val r = Raster(W, H)
        val left = "${Format.distance(state.remaining)} · ${Format.duration(state.secondsLeft)} · ${Format.clock(nowMs + (state.secondsLeft * 1000).toLong())}"
        if (full) {
            renderer.render(
                r, MapView(state.position, zoom - 0.5, W, H), sources,
                Overlay(route.line(state.along), state.position, state.heading, state.accuracyM, destination = route.end, detail = detail),
            )
            // A small hint top left, on black.
            roundRect(r, 8.0, 8.0, 262.0, 92.0, 14.0, Ink.ERASE, fill = true)
            roundRect(r, 8.0, 8.0, 262.0, 92.0, 14.0, Ink.level(6), fill = false)
            TurnIcon.draw(r, state.next.turn, 50.0, 54.0, 62.0, Ink.level(15))
            fonts.text.draw(r, if (state.arrived) "Ziel" else Format.distance(state.toNext), 92.0, 44.0, Ink.level(15))
            fonts.small.draw(r, fonts.small.fit(hintText(state), 170.0), 92.0, 76.0, Ink.level(11))
            fonts.label.drawRight(r, left, W - 10.0, 26.0, Ink.level(13), halo = 2)
            return r
        }
        // The small map on the right, turning with the direction of travel.
        val panel = Raster(PANEL, PANEL)
        val rotation = state.heading ?: 0.0
        renderer.render(
            panel, MapView(state.position, zoom, PANEL, PANEL, rotation, PANEL / 2.0, PANEL * 0.68), sources,
            Overlay(
                route.line(state.along), state.position, rotation.takeIf { state.heading != null }, null,
                destination = route.end, labels = false, attribution = false, detail = detail,
            ),
        )
        fonts.small.draw(panel, "© OSM", 10.0, PANEL - 10.0, Ink.level(5), halo = 1)
        clipRound(panel, CORNER)
        r.blit(panel, PANEL_X, PANEL_Y)
        roundRect(r, PANEL_X + 0.5, PANEL_Y + 0.5, PANEL - 1.0, PANEL - 1.0, CORNER, Ink.level(6), fill = false)
        // What is left, top right.
        fonts.label.drawRight(r, left, W - 10.0, 24.0, Ink.level(13))
        // The hint, bottom left.
        when {
            note != null || state.offRoute -> {
                TurnIcon.draw(r, Turn.UTURN, 72.0, 118.0, 92.0, Ink.level(9))
                drawLines(r, fonts.text, note ?: "Neue Route wird gesucht …", 16.0, 222.0, 290.0, 2, Ink.level(13))
            }
            state.arrived -> {
                TurnIcon.draw(r, Turn.ARRIVE, 72.0, 118.0, 100.0, Ink.level(15))
                fonts.big.draw(r, "Ziel", 140.0, 136.0, Ink.level(15))
                drawLines(r, fonts.text, "erreicht", 16.0, 214.0, 290.0, 1, Ink.level(12))
            }
            else -> {
                TurnIcon.draw(r, state.next.turn, 72.0, 118.0, 100.0, Ink.level(15))
                fonts.big.draw(r, Format.distance(state.toNext), 138.0, 136.0, Ink.level(15))
                drawLines(r, fonts.text, hintText(state), 16.0, 210.0, 292.0, 2, Ink.level(13))
                state.then?.let { t ->
                    if (state.next.turn != Turn.ARRIVE) fonts.label.draw(r, "Dann ${thenText(t.turn)}", 16.0, 272.0, Ink.level(9))
                }
            }
        }
        return r
    }

    /** The route from above, fitted into [w] × [h], with start and destination; [detail] as in [Overlay]. */
    fun overview(sources: List<MapSource>, route: Route, w: Int, h: Int, detail: Int = 2): Raster {
        val r = Raster(w, h)
        val box = route.box()
        val zoom = fitZoom(box, w, h, 24.0)
        renderer.render(r, MapView(box.center, zoom, w, h), sources, Overlay(route.line(0.0), start = route.start, destination = route.end, detail = detail))
        return r
    }

    /** A note alone on the screen: [title] big, [lines] below. */
    fun message(title: String, lines: List<String>, icon: Turn? = null): Raster {
        val r = Raster(W, H)
        var y = if (icon != null) 150.0 else 110.0
        icon?.let { TurnIcon.draw(r, it, W / 2.0, 70.0, 70.0, Ink.level(13)) }
        fonts.text.drawCentered(r, title, W / 2.0, y, Ink.level(15))
        y += 40
        for (line in lines) {
            fonts.label.drawCentered(r, fonts.label.fit(line, W - 40.0), W / 2.0, y, Ink.level(11))
            y += 30
        }
        return r
    }

    private fun hintText(state: NavState): String {
        val street = state.next.street
        return when (state.next.turn) {
            Turn.ARRIVE -> "Zum Ziel"
            Turn.START -> street?.let { "Auf $it" } ?: "Los"
            else -> if (street != null) "${state.next.turn.text}: $street" else state.next.turn.text
        }
    }

    private fun thenText(turn: Turn) = when (turn) {
        Turn.ARRIVE -> "Ziel"
        Turn.LEFT -> "links"
        Turn.RIGHT -> "rechts"
        Turn.SLIGHT_LEFT -> "leicht links"
        Turn.SLIGHT_RIGHT -> "leicht rechts"
        Turn.SHARP_LEFT -> "scharf links"
        Turn.SHARP_RIGHT -> "scharf rechts"
        Turn.UTURN -> "wenden"
        else -> "geradeaus"
    }

    /** [text] in at most [maxLines] lines of [width] from ([x], [baseline]) on, words kept whole. */
    private fun drawLines(r: Raster, font: Font, text: String, x: Double, baseline: Double, width: Double, maxLines: Int, ink: Ink) {
        val lines = ArrayList<String>()
        var current = ""
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (font.width(candidate) <= width || current.isEmpty()) current = candidate else {
                lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        val shown = lines.take(maxLines).toMutableList()
        if (lines.size > maxLines) shown[maxLines - 1] = font.fit(lines.drop(maxLines - 1).joinToString(" "), width)
        shown.forEachIndexed { i, line -> font.draw(r, font.fit(line, width), x, baseline + i * (font.lineHeight + 2), ink) }
    }

    /** A scale bar of a round length, bottom right. */
    private fun scaleBar(r: Raster, view: MapView) {
        val mpp = view.metresPerPixel
        val target = mpp * 90
        val exp = 10.0.pow(floor(log10(target)))
        val metres = listOf(1.0, 2.0, 5.0).map { it * exp }.last { it <= target * 1.0001 }
        val px = metres / mpp
        val x1 = W - 14.0
        val x0 = x1 - px
        val y = H - 14.0
        r.line(x0, y, x1, y, 2.0, Ink.level(10))
        r.line(x0, y - 5, x0, y, 2.0, Ink.level(10))
        r.line(x1, y - 5, x1, y, 2.0, Ink.level(10))
        fonts.small.drawCentered(r, Format.distance(metres).replace(",0 ", " "), (x0 + x1) / 2, y - 8, Ink.level(10), halo = 1)
    }

    companion object {
        const val W = 576
        const val H = 288

        /** The small map: its size, where it sits, how round its corners are. */
        const val PANEL = 252
        const val PANEL_X = 316
        const val PANEL_Y = 34
        const val CORNER = 20.0

        /** The zoom at which [box] fills [w] × [h] but for [margin] px. */
        fun fitZoom(box: Box, w: Int, h: Int, margin: Double): Double {
            val dx = Geo.pixelX(box.maxLon / 1e6, 0.0) - Geo.pixelX(box.minLon / 1e6, 0.0)
            val dy = Geo.pixelY(box.minLat / 1e6, 0.0) - Geo.pixelY(box.maxLat / 1e6, 0.0)
            val zx = if (dx > 0) ln((w - 2 * margin) / dx) / ln(2.0) else 18.0
            val zy = if (dy > 0) ln((h - 2 * margin) / dy) / ln(2.0) else 18.0
            return min(zx, zy).coerceIn(3.0, 17.5)
        }

        /** Black outside a rectangle with round corners of [radius]. */
        fun clipRound(r: Raster, radius: Double) {
            for (y in 0 until r.h) for (x in 0 until r.w) {
                val cx = when {
                    x < radius -> radius
                    x > r.w - 1 - radius -> r.w - 1 - radius
                    else -> x.toDouble()
                }
                val cy = when {
                    y < radius -> radius
                    y > r.h - 1 - radius -> r.h - 1 - radius
                    else -> y.toDouble()
                }
                val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                if (d > radius) r.darken(x, y, min(1.0, d - radius))
            }
        }

        /** A rectangle with round corners, outlined (2 px) or filled. */
        fun roundRect(r: Raster, x: Double, y: Double, w: Double, h: Double, radius: Double, ink: Ink, fill: Boolean) {
            val pts = ArrayList<Double>()
            fun arc(cx: Double, cy: Double, from: Double) {
                for (i in 0..8) {
                    val a = from + i * PI / 16
                    pts += cx + radius * cos(a)
                    pts += cy + radius * sin(a)
                }
            }
            arc(x + w - radius, y + radius, -PI / 2)
            arc(x + w - radius, y + h - radius, 0.0)
            arc(x + radius, y + h - radius, PI / 2)
            arc(x + radius, y + radius, PI)
            val ring = pts.toDoubleArray()
            if (fill) {
                r.polygon(listOf(ring), ink)
            } else {
                val n = ring.size / 2
                val xs = DoubleArray(n + 1) { ring[2 * (it % n)] }
                val ys = DoubleArray(n + 1) { ring[2 * (it % n) + 1] }
                r.polyline(xs, ys, n + 1, 2.0, ink)
            }
        }
    }
}

/** Turn arrows as the glasses show them: a stem from below and an arrow head where the way goes. */
object TurnIcon {
    /** The icon for [turn], [size] px tall, centred on ([cx], [cy]). */
    fun draw(r: Raster, turn: Turn, cx: Double, cy: Double, size: Double, ink: Ink) {
        val w = max(3.0, size * 0.11)
        val bottom = cy + size * 0.46
        val joint = cy + size * 0.05
        when (turn) {
            Turn.ARRIVE -> {
                // A flag on its pole.
                val pole = cx - size * 0.18
                r.line(pole, cy + size * 0.46, pole, cy - size * 0.44, w * 0.8, ink)
                r.polygon(listOf(doubleArrayOf(pole, cy - size * 0.44, pole + size * 0.5, cy - size * 0.26, pole, cy - size * 0.08)), ink)
                return
            }
            Turn.UTURN -> {
                val rad = size * 0.2
                val right = cx + rad
                val left = cx - rad
                r.line(right, bottom, right, cy - size * 0.1, w, ink)
                val n = 16
                for (i in 0 until n) {
                    val a0 = PI * i / n
                    val a1 = PI * (i + 1) / n
                    r.line(cx + rad * cos(a0), cy - size * 0.1 - rad * sin(a0), cx + rad * cos(a1), cy - size * 0.1 - rad * sin(a1), w, ink)
                }
                r.line(left, cy - size * 0.1, left, cy + size * 0.2, w, ink)
                head(r, left, cy + size * 0.32, 180.0, size * 0.26, ink)
                return
            }
            else -> Unit
        }
        val angle = when (turn) {
            Turn.SLIGHT_LEFT -> -45.0
            Turn.LEFT -> -90.0
            Turn.SHARP_LEFT -> -135.0
            Turn.SLIGHT_RIGHT -> 45.0
            Turn.RIGHT -> 90.0
            Turn.SHARP_RIGHT -> 135.0
            else -> 0.0
        }
        if (angle == 0.0) {
            r.line(cx, bottom, cx, cy - size * 0.24, w, ink)
            head(r, cx, cy - size * 0.36, 0.0, size * 0.28, ink)
            return
        }
        // Stem up to the joint, then the new direction; the stem moves aside so the whole icon stays centred.
        val shift = -sin(Math.toRadians(angle)) * size * 0.16
        val sx = cx + shift
        r.line(sx, bottom, sx, joint, w, ink)
        val len = size * 0.34
        val a = Math.toRadians(angle)
        val ex = sx + sin(a) * len
        val ey = joint - cos(a) * len
        r.line(sx, joint, ex, ey, w, ink)
        head(r, sx + sin(a) * (len + size * 0.1), joint - cos(a) * (len + size * 0.1), angle, size * 0.28, ink)
    }

    /** An arrow head with its tip at ([x], [y]) pointing in [direction] (degrees, 0 = up). */
    private fun head(r: Raster, x: Double, y: Double, direction: Double, size: Double, ink: Ink) {
        val a = Math.toRadians(direction)
        fun pt(dx: Double, dy: Double) = doubleArrayOf(x + dx * cos(a) - dy * sin(a), y + dx * sin(a) + dy * cos(a))
        val tip = pt(0.0, 0.0)
        val l = pt(-size * 0.55, size * 0.75)
        val rr = pt(size * 0.55, size * 0.75)
        r.polygon(listOf(doubleArrayOf(tip[0], tip[1], rr[0], rr[1], l[0], l[1])), ink)
    }
}
