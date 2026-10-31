package ch.madtreasures.fahrplan

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// What the drawn pages show, as plain data: the app builds it from its state, the tests read it, and [Painter]
// turns it into the picture for the glasses. The layout follows the SBB app: the connections as cards with the
// departure left, the arrival right and the journey bar between them, "Ihre Reise" as a line from stop to stop.

/** Small pictograms beside names and lines, as in the SBB app. */
enum class Icon { TRAIN, TRAM, BUS, SHIP, CABLE, HERE, PLACE, CLOCK, HISTORY, NEAR, BOARD, WALK, TYPE }

/** One row of a drawn list: a stop, a departure, a stop of a trip, an action. */
data class Row(
    val text: String,
    val icon: Icon? = null,
    val time: String? = null,
    val delay: String? = null,
    val badge: String? = null,
    val sub: String? = null,
    val right: String? = null,
    /** [right] is a changed platform: framed, as the SBB app marks it. */
    val changed: Boolean = false,
)

/** A part of the journey bar, from [start] to [end] (0–1 of the whole time): a ride, or a walk drawn dotted. */
data class Span(val start: Double, val end: Double, val walk: Boolean)

sealed interface Screen

/**
 * Planen: from and to, one above the other with the line between them and the swap sign beside them, when, and
 * below the searches used last and the departures. [focus] counts in that order: 0 from, 1 to, 2 swap, 3 when,
 * then [rows].
 */
data class PlanScreen(
    val from: String,
    val fromHere: Boolean,
    val to: String?,
    val time: String,
    val rows: List<Row>,
    val focus: Int,
) : Screen

/** One connection in the list. */
data class Card(
    val badges: List<String>,
    val direction: String,
    val departure: String,
    val departureDelay: String?,
    val arrival: String,
    val arrivalDelay: String?,
    val spans: List<Span>,
    val platform: String?,
    val platformChanged: Boolean,
    val info: String,
)

/** The connections: [title] the route, [subtitle] the day and time, [selected] the card with the frame. */
data class ConnectionsScreen(
    val title: String,
    val subtitle: String,
    val cards: List<Card>,
    val selected: Int,
    val note: String?,
    val earlier: String?,
    val later: String?,
) : Screen

/** A line of "Ihre Reise". */
sealed interface JourneyItem {
    /** Where a ride starts or ends; [end]: the first or last stop of the whole connection; [id] for its departures. */
    data class Stop(
        val time: String,
        val delay: String?,
        val name: String,
        val platform: String?,
        val changed: Boolean,
        val end: Boolean,
        val id: String? = null,
    ) : JourneyItem

    /** A ride; [open] when its stops show, [stops] how many lie between. */
    data class Ride(val badge: String, val direction: String, val open: Boolean, val stops: Int) : JourneyItem

    /** A stop on the way of an open ride. */
    data class Pass(val time: String, val name: String, val platform: String?, val id: String? = null) : JourneyItem

    data class Walk(val text: String) : JourneyItem

    data class Change(val text: String) : JourneyItem
}

/** Ihre Reise: the bar of the whole connection on top, then the stops and rides; [selected] is an index into [items]. */
data class JourneyScreen(
    val departure: String,
    val arrival: String,
    val spans: List<Span>,
    val info: String,
    val badges: List<String>,
    val items: List<JourneyItem>,
    val selected: Int,
) : Screen

/** A list: the stops for a typed name (with the [field] being typed), the stops nearby, departures, a trip. */
data class ListScreen(
    val title: String?,
    val fieldLabel: String?,
    val field: String?,
    val typing: Boolean,
    val rows: List<Row>,
    val selected: Int,
    val note: String?,
) : Screen

/** A picture for an image block, and the scroll it was drawn at (the app keeps it for the next one). */
class Painted(val dataUrl: String, val scroll: Int)

/**
 * Draws the [Screen]s into pictures of [W] × [H], the app area under the header, in the glasses' greens. Long
 * pages are drawn whole and cut to the window that keeps the selected line in view; headers stay. Runs in
 * background work.
 */
class Painter(private val fonts: Fonts) {
    fun paint(screen: Screen, scroll: Int): Painted = when (screen) {
        is PlanScreen -> plan(screen, scroll)
        is ConnectionsScreen -> connections(screen, scroll)
        is JourneyScreen -> journey(screen, scroll)
        is ListScreen -> list(screen, scroll)
    }

    // --- Planen -------------------------------------------------------------------------------------------

    private fun plan(s: PlanScreen, scroll: Int): Painted {
        val rowsTop = 132
        val content = Raster(W, max(H, rowsTop + s.rows.size * ROW + 8))
        val label = fonts.label
        // From and to, joined by the line, as in the SBB app.
        val x = 46.0
        if (s.fromHere) icon(content, Icon.HERE, 20.0, 24.0, BRIGHT) else stopDot(content, 20.0, 24.0, filled = false)
        content.line(20.0, 34.0, 20.0, 64.0, 2.5, Ink.level(9))
        stopDot(content, 20.0, 74.0, filled = true)
        label.draw(content, label.fit(s.from, 430.0), x, 31.0, Ink.level(if (s.fromHere) 14 else 15))
        label.draw(content, label.fit(s.to ?: "Nach", 430.0), x, 81.0, Ink.level(if (s.to == null) 8 else 15))
        swapSign(content, 516.0, 49.0)
        content.line(8.0, 100.0, W - 8.0, 100.0, 1.0, Ink.level(5))
        icon(content, Icon.CLOCK, 20.0, 116.0, Ink.level(12))
        fonts.label.draw(content, s.time, x, 123.0, Ink.level(14))
        val focusRects = mutableListOf(
            box(4, 6, 488, 44), box(4, 56, 488, 94), box(494, 26, 540, 72), box(4, 102, 540, 130),
        )
        s.rows.forEachIndexed { i, r ->
            val y = rowsTop + i * ROW
            row(content, r, y)
            focusRects += box(4, y, 540, y + ROW - 2)
        }
        val f = focusRects.getOrNull(s.focus)
        f?.let { frame(content, it) }
        val top = keepInView(scroll, f, content.h, 0, lines = s.rows.indices.map { rowsTop + it * ROW })
        return Painted(cut(content, top, 0).dataUrl(), top)
    }

    private fun swapSign(r: Raster, cx: Double, cy: Double) {
        val ink = Ink.level(12)
        r.line(cx - 6, cy - 14, cx - 6, cy + 12, 2.5, ink)
        r.line(cx - 6, cy - 14, cx - 11, cy - 8, 2.5, ink)
        r.line(cx - 6, cy - 14, cx - 1, cy - 8, 2.5, ink)
        r.line(cx + 6, cy + 14, cx + 6, cy - 12, 2.5, ink)
        r.line(cx + 6, cy + 14, cx + 11, cy + 8, 2.5, ink)
        r.line(cx + 6, cy + 14, cx + 1, cy + 8, 2.5, ink)
    }

    // --- Verbindungen ---------------------------------------------------------------------------------------

    private fun connections(s: ConnectionsScreen, scroll: Int): Painted {
        val head = HEAD
        val out = Raster(W, H)
        val label = fonts.label
        val small = fonts.small
        val subtitleWidth = small.width(s.subtitle)
        label.draw(out, label.fit(s.title, W - 24 - subtitleWidth - 12), 8.0, 22.0, BRIGHT)
        small.drawRight(out, s.subtitle, W - 8.0, 22.0, Ink.level(9))
        out.line(4.0, head - 3.0, W - 4.0, head - 3.0, 1.0, Ink.level(5))
        val hint = 22
        val listTop = if (s.earlier != null) hint else 0
        val body = Raster(W, max(H - head, listTop + s.cards.size * CARD + 48))
        s.earlier?.let { arrowHint(body, it, 14.0, up = true) }
        if (s.cards.isEmpty()) {
            s.note?.let { small.drawCentered(body, small.fit(it, W - 32.0), W / 2.0, 60.0, Ink.level(11)) }
        }
        s.cards.forEachIndexed { i, c -> card(body, c, listTop + i * CARD, i == s.selected) }
        val end = listTop + s.cards.size * CARD
        s.later?.let { arrowHint(body, it, end + 14.0, up = false) }
        if (s.cards.isNotEmpty()) source(body, end + 36.0)
        val sel = s.cards.indices.firstOrNull { it == s.selected }?.let { box(0, listTop + it * CARD, W, listTop + it * CARD + CARD - 6) }
        // On the last card the way to later ones and the source show below it.
        val top = keepInView(
            scroll, sel, body.h, H - head,
            reveal = if (s.selected == s.cards.lastIndex) end + 42 else null,
            lines = s.cards.indices.map { listTop + it * CARD },
        )
        out.blit(cut(body, top, H - head), 0, head)
        return Painted(out.dataUrl(), top)
    }

    private fun card(r: Raster, c: Card, y: Int, selected: Boolean) {
        val small = fonts.small
        val text = fonts.text
        if (selected) frame(r, box(2, y, W - 2, y + CARD - 8))
        var x = 16.0
        for (b in c.badges.take(4)) x = badge(r, b, x, y + 8.0) + 6
        small.draw(r, small.fit(c.direction, W - 16 - x), x + 2, y + 23.0, Ink.level(12))
        // Departure left, arrival right, and the bar between them with a ring at every change.
        val timeBase = y + 59.0
        text.draw(r, c.departure, 16.0, timeBase, BRIGHT)
        var left = 16.0 + text.width(c.departure)
        c.departureDelay?.let {
            small.draw(r, it, left + 5, timeBase - 8, BRIGHT)
            left += 5 + small.width(it)
        }
        val arrivalWidth = text.width(c.arrival)
        var right = W - 16.0 - arrivalWidth
        text.draw(r, c.arrival, right, timeBase, BRIGHT)
        c.arrivalDelay?.let {
            right -= small.width(it) + 5
            small.draw(r, it, right, timeBase - 8, BRIGHT)
        }
        bar(r, c.spans, left + 16, right - 16, timeBase - 9, if (selected) 14 else 11)
        c.platform?.let { platform(r, it, 16.0, y + 85.0, c.platformChanged) }
        small.drawRight(r, c.info, W - 16.0, y + 85.0, Ink.level(10))
    }

    // --- Ihre Reise ----------------------------------------------------------------------------------------

    private fun journey(s: JourneyScreen, scroll: Int): Painted {
        val head = 70
        val out = Raster(W, H)
        val text = fonts.text
        val small = fonts.small
        text.draw(out, s.departure, 8.0, 28.0, BRIGHT)
        text.drawRight(out, s.arrival, W - 8.0, 28.0, BRIGHT)
        bar(out, s.spans, 8 + text.width(s.departure) + 16, W - 8 - text.width(s.arrival) - 16, 19.0, 14)
        // The rides one after the other at the right, as many as leave the day and the duration room at the left;
        // those left out are counted.
        val widths = s.badges.map { badgeWidth(it) }
        var shown = 0
        var total = 0.0
        while (shown < widths.size) {
            val w = widths[shown] + if (shown > 0) CHEVRON else 0.0
            val rest = if (shown + 1 < widths.size) small.width("+${widths.size - shown - 1}") + 8 else 0.0
            if (total + w + rest > W - 16 - INFO) break
            total += w
            shown++
        }
        val more = (s.badges.size - shown).takeIf { it > 0 }?.let { "+$it" }
        more?.let { total += small.width(it) + 8 }
        var x = W - 8.0 - total
        for (i in 0 until shown) {
            if (i > 0) {
                chevron(out, x + CHEVRON / 2, 49.0)
                x += CHEVRON
            }
            x = badge(out, s.badges[i], x, 39.0)
        }
        more?.let { small.draw(out, it, x + 8, 54.0, Ink.level(10)) }
        small.draw(out, small.fit(s.info, W - 16 - total - 16), 8.0, 54.0, Ink.level(10))
        out.line(4.0, head - 4.0, W - 4.0, head - 4.0, 1.0, Ink.level(5))
        // The items with their heights; nodes on the line at the left.
        val heights = s.items.map { item ->
            when (item) {
                is JourneyItem.Stop -> 32
                is JourneyItem.Ride -> 32
                is JourneyItem.Pass -> 24
                is JourneyItem.Walk -> 26
                is JourneyItem.Change -> 26
            }
        }
        val tops = heights.runningFold(4) { acc, h -> acc + h }
        val body = Raster(W, max(H - head, tops.last() + 34))
        val lx = 20.0
        // The line first, piece by piece between the stops: solid along a ride, dotted on foot or when changing.
        var lastNode: Double? = null
        var solid = false
        s.items.forEachIndexed { i, item ->
            val mid = tops[i] + heights[i] / 2.0
            when (item) {
                is JourneyItem.Stop -> {
                    lastNode?.let { from -> segment(body, lx, from, mid, solid) }
                    lastNode = mid
                    solid = false
                }
                is JourneyItem.Ride -> solid = true
                else -> Unit
            }
        }
        s.items.forEachIndexed { i, item ->
            val top = tops[i]
            val mid = top + heights[i] / 2.0
            when (item) {
                is JourneyItem.Stop -> {
                    stopDot(body, lx, mid, filled = item.end)
                    val label = fonts.label
                    label.draw(body, item.time, 42.0, mid + 7, BRIGHT)
                    var nx = 42.0 + label.width("00:00") + 10
                    item.delay?.let {
                        small.draw(body, it, nx - 6, mid + 6, BRIGHT)
                        nx += small.width(it) + 2
                    }
                    val pw = item.platform?.let { small.width(it) + 22 } ?: 0.0
                    label.draw(body, label.fit(item.name, W - nx - pw - 12), nx, mid + 7, Ink.level(14))
                    item.platform?.let { platform(body, it, W - 8.0 - small.width(it), mid + 6, item.changed) }
                }
                is JourneyItem.Ride -> {
                    val bx = badge(body, item.badge, 42.0, mid - 10)
                    // As in the SBB app: how many stops lie between, which a tap shows or hides.
                    val more = when {
                        item.stops == 0 -> null
                        item.open -> "Zwischenhalte ausblenden"
                        item.stops == 1 -> "1 Zwischenhalt"
                        else -> "${item.stops} Zwischenhalte"
                    }
                    val mw = more?.let { small.width(it) + 16 } ?: 0.0
                    small.draw(body, small.fit(item.direction, W - bx - mw - 20), bx + 8, mid + 5, Ink.level(11))
                    more?.let { small.drawRight(body, it, W - 8.0, mid + 5, Ink.level(8)) }
                }
                is JourneyItem.Pass -> {
                    body.circle(lx, mid, 3.0, Ink.level(12))
                    small.draw(body, item.time, 42.0, mid + 5, Ink.level(11))
                    small.draw(body, small.fit(item.name, W - 160.0), 42 + small.width("00:00") + 12, mid + 5, Ink.level(11))
                    item.platform?.let { small.drawRight(body, it, W - 8.0, mid + 5, Ink.level(9)) }
                }
                is JourneyItem.Walk -> {
                    icon(body, Icon.WALK, 50.0, mid, Ink.level(11))
                    small.draw(body, small.fit(item.text, W - 90.0), 66.0, mid + 5, Ink.level(11))
                }
                is JourneyItem.Change -> small.draw(body, small.fit(item.text, W - 60.0), 42.0, mid + 5, Ink.level(11))
            }
        }
        source(body, tops.last() + 22.0)
        val sel = s.items.getOrNull(s.selected)?.let {
            frame(body, box(34, tops[s.selected] + 1, W - 2, tops[s.selected] + heights[s.selected] - 1))
            box(0, tops[s.selected], W, tops[s.selected] + heights[s.selected])
        }
        // An open ride brings its stops into view, as unfolding does on a phone; the last line the source below it.
        val passes = (s.items.getOrNull(s.selected) as? JourneyItem.Ride)?.takeIf { it.open }?.let {
            var last = s.selected
            while (s.items.getOrNull(last + 1) is JourneyItem.Pass) last++
            tops[last + 1]
        }
        val reveal = if (s.selected == s.items.lastIndex) tops.last() + 28 else passes
        val top = keepInView(scroll, sel, body.h, H - head, reveal, tops)
        out.blit(cut(body, top, H - head), 0, head)
        return Painted(out.dataUrl(), top)
    }

    private fun segment(r: Raster, x: Double, from: Double, to: Double, solid: Boolean) {
        val a = from + 8
        val b = to - 8
        if (b <= a) return
        if (solid) {
            r.line(x, a, x, b, 4.0, Ink.level(13))
        } else {
            r.polyline(doubleArrayOf(x, x), doubleArrayOf(a, b), 2, 2.5, Ink.level(9), doubleArrayOf(3.0, 5.0))
        }
    }

    // --- Lists ---------------------------------------------------------------------------------------------

    private fun list(s: ListScreen, scroll: Int): Painted {
        val out = Raster(W, H)
        var head = 0
        if (s.title != null) {
            fonts.label.draw(out, fonts.label.fit(s.title, W - 16.0), 8.0, 22.0, BRIGHT)
            out.line(4.0, 31.0, W - 4.0, 31.0, 1.0, Ink.level(5))
            head = 34
        }
        if (s.field != null || s.fieldLabel != null) {
            val y0 = head + 2
            roundRect(out, 2.0, y0.toDouble(), W - 2.0, y0 + 40.0, 10.0, Ink.level(9), 2.0)
            icon(out, Icon.TYPE, 22.0, y0 + 20.0, Ink.level(11))
            val label = s.fieldLabel.orEmpty()
            fonts.small.draw(out, label, 40.0, y0 + 26.0, Ink.level(9))
            val tx = 40.0 + fonts.small.width(label) + if (label.isEmpty()) 0 else 10
            val typed = s.field.orEmpty()
            fonts.label.draw(out, fonts.label.fit(typed, W - tx - 24), tx, y0 + 27.0, BRIGHT)
            if (s.typing) {
                val cx = tx + fonts.label.width(fonts.label.fit(typed, W - tx - 24)) + 2
                out.line(cx, y0 + 10.0, cx, y0 + 31.0, 2.0, BRIGHT)
            }
            head = y0 + 46
        }
        val heights = s.rows.map { if (it.sub != null) ROW + 14 else ROW }
        val tops = heights.runningFold(0) { acc, h -> acc + h }
        // Below the rows a note (loading, an error) and the source of the data.
        val end = tops.last() + if (s.note != null) 26 else 0
        val body = Raster(W, max(H - head, end + (if (s.rows.isEmpty()) 8 else 30)))
        s.note?.let { fonts.small.drawCentered(body, fonts.small.fit(it, W - 32.0), W / 2.0, tops.last() + 22.0, Ink.level(10)) }
        if (s.rows.isNotEmpty()) source(body, end + 22.0)
        s.rows.forEachIndexed { i, r -> row(body, r, tops[i]) }
        val sel = s.rows.indices.firstOrNull { it == s.selected }?.let {
            val b = box(4, tops[it], W - 4, tops[it] + heights[it] - 2)
            frame(body, b)
            b
        }
        val top = keepInView(scroll, sel, body.h, H - head, reveal = if (s.selected == s.rows.lastIndex) end + 28 else null, lines = tops)
        out.blit(cut(body, top, H - head), 0, head)
        return Painted(out.dataUrl(), top)
    }

    /** A row: pictogram, time and delay, line badge, text (with a second line), the platform at the right. */
    private fun row(r: Raster, row: Row, y: Int) {
        val label = fonts.label
        val small = fonts.small
        val base = y + 23.0
        row.icon?.let { icon(r, it, 22.0, y + 16.0, Ink.level(12)) }
        var x = if (row.icon != null) 44.0 else 14.0
        row.time?.let {
            label.draw(r, it, x, base, BRIGHT)
            x += label.width(it) + 6
            row.delay?.let { d ->
                small.draw(r, d, x - 2, base - 7, BRIGHT)
                x += small.width(d) + 4
            }
            x += 6
        }
        row.badge?.let { x = badge(r, it, x, y + 6.0) + 10 }
        val rightWidth = row.right?.let { small.width(it) + if (row.changed) 14 else 0 } ?: 0.0
        label.draw(r, label.fit(row.text, W - x - rightWidth - 20), x, base, Ink.level(14))
        row.sub?.let { small.draw(r, small.fit(it, W - x - 16), x, base + 19, Ink.level(9)) }
        row.right?.let { platform(r, it, W - 12.0 - small.width(it) - if (row.changed) 6 else 0, base - 1, row.changed) }
    }

    // --- Pieces --------------------------------------------------------------------------------------------

    /** The journey bar from [x0] to [x1] at [y]: rides solid, walks dotted, a ring at each end and change. */
    private fun bar(r: Raster, spans: List<Span>, x0: Double, x1: Double, y: Double, level: Int) {
        if (x1 <= x0 + 10) return
        val ink = Ink.level(level)
        val parts = spans.ifEmpty { listOf(Span(0.0, 1.0, false)) }
        for (p in parts) {
            val a = x0 + (x1 - x0) * p.start
            val b = x0 + (x1 - x0) * p.end
            if (p.walk) {
                r.polyline(doubleArrayOf(a, b), doubleArrayOf(y, y), 2, 2.5, Ink.level(level - 3), doubleArrayOf(2.0, 4.0))
            } else {
                r.line(a, y, b, y, 3.5, ink)
            }
        }
        val rides = parts.filter { !it.walk }
        val points = (listOf(0.0, 1.0) + rides.flatMap { listOf(it.start, it.end) }).distinct()
        for (t in points) {
            val cx = x0 + (x1 - x0) * t
            r.circle(cx, y, 5.5, Ink.ERASE)
            r.ring(cx, y, 4.5, 2.5, ink)
        }
    }

    /** A line badge as in the SBB app ("IC 8", "Bus 31"), bright with dark letters; returns its right edge. */
    private fun badge(r: Raster, text: String, x: Double, y: Double): Double {
        val w = badgeWidth(text)
        roundRect(r, x, y, x + w, y + 21, 4.0, Ink.level(13))
        fonts.small.draw(r, text, x + 6, y + 15.5, Ink.ERASE)
        return x + w
    }

    private fun badgeWidth(text: String) = fonts.small.width(text) + 12

    /** A platform; a changed one is framed, as the SBB app marks it. */
    private fun platform(r: Raster, text: String, x: Double, baseline: Double, changed: Boolean) {
        val small = fonts.small
        if (changed) {
            val w = small.width(text)
            roundRect(r, x - 5, baseline - 15, x + w + 5, baseline + 5, 4.0, BRIGHT, 2.0)
            small.draw(r, text, x, baseline, BRIGHT)
        } else {
            small.draw(r, text, x, baseline, Ink.level(12))
        }
    }

    private fun arrowHint(r: Raster, text: String, baseline: Double, up: Boolean) {
        val small = fonts.small
        val w = small.width(text)
        val cx = W / 2.0
        small.drawCentered(r, text, cx + 9, baseline, Ink.level(8))
        val ax = cx - w / 2 - 6
        val ay = baseline - 5
        if (up) {
            r.line(ax - 5, ay + 3, ax, ay - 3, 2.0, Ink.level(8))
            r.line(ax, ay - 3, ax + 5, ay + 3, 2.0, Ink.level(8))
        } else {
            r.line(ax - 5, ay - 3, ax, ay + 3, 2.0, Ink.level(8))
            r.line(ax, ay + 3, ax + 5, ay - 3, 2.0, Ink.level(8))
        }
    }

    /** Where the data comes from, small at the end of every page that shows it, as the service asks. */
    private fun source(r: Raster, baseline: Double) = fonts.small.drawCentered(r, Timetable.SOURCE, W / 2.0, baseline, Ink.level(7))

    private fun chevron(r: Raster, cx: Double, cy: Double) {
        r.line(cx - 2, cy - 5, cx + 3, cy, 2.0, Ink.level(9))
        r.line(cx + 3, cy, cx - 2, cy + 5, 2.0, Ink.level(9))
    }

    private fun stopDot(r: Raster, cx: Double, cy: Double, filled: Boolean) {
        r.circle(cx, cy, 7.0, Ink.ERASE)
        if (filled) r.circle(cx, cy, 6.0, BRIGHT) else r.ring(cx, cy, 5.5, 2.5, BRIGHT)
    }

    /** The frame of the selected line. */
    private fun frame(r: Raster, b: IntArray) = roundRect(r, b[0].toDouble(), b[1].toDouble(), b[2].toDouble(), b[3].toDouble(), 10.0, BRIGHT, 2.5)

    /** Pictograms of about 20 px around ([cx], [cy]). */
    private fun icon(r: Raster, icon: Icon, cx: Double, cy: Double, ink: Ink) {
        when (icon) {
            Icon.TRAIN -> {
                roundRect(r, cx - 7, cy - 10, cx + 7, cy + 6, 4.0, ink, 2.0)
                r.line(cx - 4, cy - 5, cx + 4, cy - 5, 2.0, ink)
                r.circle(cx - 3.5, cy + 2, 1.5, ink)
                r.circle(cx + 3.5, cy + 2, 1.5, ink)
                r.line(cx - 5, cy + 7, cx - 8, cy + 11, 2.0, ink)
                r.line(cx + 5, cy + 7, cx + 8, cy + 11, 2.0, ink)
            }
            Icon.TRAM -> {
                roundRect(r, cx - 7, cy - 6, cx + 7, cy + 8, 3.0, ink, 2.0)
                r.line(cx - 4, cy - 12, cx + 4, cy - 12, 2.0, ink)
                r.line(cx, cy - 12, cx, cy - 6, 2.0, ink)
                r.line(cx - 4, cy - 1, cx + 4, cy - 1, 2.0, ink)
            }
            Icon.BUS -> {
                roundRect(r, cx - 8, cy - 9, cx + 8, cy + 6, 3.0, ink, 2.0)
                r.line(cx - 5, cy - 3, cx + 5, cy - 3, 2.0, ink)
                r.circle(cx - 4.5, cy + 9, 2.0, ink)
                r.circle(cx + 4.5, cy + 9, 2.0, ink)
            }
            Icon.SHIP -> {
                r.polygon(listOf(doubleArrayOf(cx - 10, cy + 1, cx + 10, cy + 1, cx + 6, cy + 8, cx - 6, cy + 8)), ink)
                r.line(cx - 3, cy - 8, cx - 3, cy + 1, 2.0, ink)
                r.line(cx - 3, cy - 8, cx + 4, cy - 3, 2.0, ink)
            }
            Icon.CABLE -> {
                r.line(cx - 10, cy - 10, cx + 10, cy - 4, 2.0, ink)
                r.line(cx, cy - 7, cx, cy - 3, 2.0, ink)
                roundRect(r, cx - 6, cy - 3, cx + 6, cy + 9, 2.0, ink, 2.0)
            }
            Icon.HERE -> {
                r.ring(cx, cy, 7.0, 2.0, ink)
                r.circle(cx, cy, 2.5, ink)
                r.line(cx, cy - 11, cx, cy - 8, 2.0, ink)
                r.line(cx, cy + 8, cx, cy + 11, 2.0, ink)
                r.line(cx - 11, cy, cx - 8, cy, 2.0, ink)
                r.line(cx + 8, cy, cx + 11, cy, 2.0, ink)
            }
            Icon.PLACE -> {
                r.ring(cx, cy - 3, 5.5, 2.0, ink)
                r.line(cx - 4, cy + 1, cx, cy + 9, 2.0, ink)
                r.line(cx + 4, cy + 1, cx, cy + 9, 2.0, ink)
            }
            Icon.CLOCK -> {
                r.ring(cx, cy, 8.0, 2.0, ink)
                r.line(cx, cy, cx, cy - 5, 2.0, ink)
                r.line(cx, cy, cx + 4, cy + 2, 2.0, ink)
            }
            Icon.HISTORY -> {
                // A clock with an arrow running back round it: searched before.
                arc(r, cx, cy, 8.0, ink, 1.25 * PI, 2.95 * PI)
                val tx = cx + 8 * cos(1.25 * PI)
                val ty = cy + 8 * sin(1.25 * PI)
                r.line(tx, ty, tx + 4.5, ty, 2.0, ink)
                r.line(tx, ty, tx, ty - 4.5, 2.0, ink)
                r.line(cx, cy, cx, cy - 4.5, 2.0, ink)
                r.line(cx, cy, cx + 3.5, cy + 1.5, 2.0, ink)
            }
            Icon.NEAR -> {
                r.circle(cx, cy + 4, 2.5, ink)
                arc(r, cx, cy + 4, 7.0, ink)
                arc(r, cx, cy + 4, 12.0, ink)
            }
            Icon.BOARD -> {
                roundRect(r, cx - 9, cy - 8, cx + 9, cy + 8, 2.0, ink, 2.0)
                r.line(cx - 5, cy - 3, cx + 5, cy - 3, 1.5, ink)
                r.line(cx - 5, cy + 1, cx + 5, cy + 1, 1.5, ink)
                r.line(cx - 5, cy + 5, cx + 2, cy + 5, 1.5, ink)
            }
            Icon.WALK -> {
                r.circle(cx + 1, cy - 9, 2.2, ink)
                r.line(cx, cy - 5, cx - 1, cy + 2, 2.2, ink)
                r.line(cx - 1, cy + 2, cx - 5, cy + 9, 2.2, ink)
                r.line(cx - 1, cy + 2, cx + 3, cy + 9, 2.2, ink)
                r.line(cx, cy - 4, cx - 5, cy - 1, 2.0, ink)
                r.line(cx, cy - 4, cx + 4, cy, 2.0, ink)
            }
            Icon.TYPE -> {
                r.ring(cx - 2, cy - 2, 6.0, 2.0, ink)
                r.line(cx + 2.5, cy + 2.5, cx + 8, cy + 8, 2.5, ink)
            }
        }
    }

    /** A piece of a circle from angle [from] to [to] (radians, clockwise from the right); by default the top. */
    private fun arc(r: Raster, cx: Double, cy: Double, radius: Double, ink: Ink, from: Double = 1.15 * PI, to: Double = 1.85 * PI) {
        val n = max(10, ((to - from) / PI * 14).toInt())
        val xs = DoubleArray(n + 1)
        val ys = DoubleArray(n + 1)
        for (i in 0..n) {
            val a = from + (to - from) * i / n
            xs[i] = cx + radius * cos(a)
            ys[i] = cy + radius * sin(a)
        }
        r.polyline(xs, ys, n + 1, 2.0, ink)
    }

    // --- Geometry ------------------------------------------------------------------------------------------

    private fun box(x0: Int, y0: Int, x1: Int, y1: Int) = intArrayOf(x0, y0, x1, y1)

    /**
     * A scroll that keeps [b] (x0, y0, x1, y1) inside a window of [height] on content of [contentHeight], and what
     * follows it down to [reveal] as far as there is room; the top of [b] comes first. A line (tops in [lines], from
     * the top down) that the top edge would cut goes out of the window, as long as [b] stays in it.
     */
    private fun keepInView(scroll: Int, b: IntArray?, contentHeight: Int, height: Int, reveal: Int? = null, lines: List<Int> = emptyList()): Int {
        val window = if (height > 0) height else H
        val maxScroll = max(0, contentHeight - window)
        var top = scroll.coerceIn(0, maxScroll)
        if (b != null) {
            val bottom = max(b[3], reveal ?: b[3])
            if (bottom + 6 > top + window) top = bottom + 6 - window
            if (b[1] - 6 < top) top = b[1] - 6
            top = top.coerceIn(0, maxScroll)
            if (top > 0) lines.map { it - 4 }.firstOrNull { it >= top && it <= b[1] - 2 && it <= maxScroll }?.let { top = it }
        }
        return top.coerceIn(0, maxScroll)
    }

    /** The part of [content] from [top] on, [height] high (all of [H] when 0). */
    private fun cut(content: Raster, top: Int, height: Int): Raster {
        val h = if (height > 0) height else H
        val out = Raster(W, h)
        for (y in 0 until h) {
            val sy = y + top
            if (sy !in 0 until content.h) continue
            System.arraycopy(content.px, sy * W, out.px, y * W, W)
        }
        return out
    }

    /** A rounded rectangle, filled, or with [width] its outline. */
    private fun roundRect(r: Raster, x0: Double, y0: Double, x1: Double, y1: Double, radius: Double, ink: Ink, width: Double? = null) {
        fun ring(inset: Double): DoubleArray {
            val rr = max(0.5, radius - inset)
            val ax = x0 + inset
            val ay = y0 + inset
            val bx = x1 - inset
            val by = y1 - inset
            val pts = ArrayList<Double>()
            val corners = listOf(
                Triple(bx - rr, ay + rr, -PI / 2), Triple(bx - rr, by - rr, 0.0),
                Triple(ax + rr, by - rr, PI / 2), Triple(ax + rr, ay + rr, PI),
            )
            for ((cx, cy, start) in corners) {
                for (i in 0..6) {
                    val a = start + PI / 2 * i / 6
                    pts += cx + rr * cos(a)
                    pts += cy + rr * sin(a)
                }
            }
            return pts.toDoubleArray()
        }
        if (width == null) {
            r.polygon(listOf(ring(0.0)), ink)
        } else {
            r.polygon(listOf(ring(0.0), ring(min(width, (y1 - y0) / 2))), ink)
        }
    }

    companion object {
        /** The app area under the header, inside its margins (Block.IMAGE_MAX_W × IMAGE_MAX_H). */
        const val W = 544
        const val H = 260

        private const val HEAD = 34
        private const val CARD = 104
        private const val ROW = 36

        /** Room between two line badges for the chevron, and the least room for the day and duration beside them. */
        private const val CHEVRON = 14.0
        private const val INFO = 210.0

        private val BRIGHT = Ink.level(15)
    }
}
