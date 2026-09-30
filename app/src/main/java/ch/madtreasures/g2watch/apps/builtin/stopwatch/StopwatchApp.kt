package ch.madtreasures.g2watch.apps.builtin.stopwatch

import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.HeadingSize
import ch.madtreasures.g2watch.apps.Page

/**
 * Start/stop stopwatch, pages from code (03 §1). It ticks only while visible, twice a second and
 * in whole seconds, to keep the link to the glasses quiet; stopped, it shows tenths.
 */
class StopwatchApp(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) : G2App {

    override val manifest = AppManifest(
        id = "ch.madtreasures.stoppuhr",
        name = "Stoppuhr",
        version = "1.0.0",
        description = "Start, Stopp und Zurücksetzen; läuft im Hintergrund weiter.",
    )

    private var startedAt: Long? = null
    private var elapsed = 0L

    override fun onEvent(event: AppEvent, ui: AppContext) {
        when (event) {
            AppEvent.Start -> {
                ui.definePages(listOf(page()))
                ui.show(PAGE)
            }
            is AppEvent.Click -> when (event.block) {
                START_STOP -> toggle(ui)
                RESET -> {
                    startedAt = startedAt?.let { clock() }
                    elapsed = 0
                    render(ui)
                }
            }
            is AppEvent.Timer -> render(ui)
            AppEvent.Hidden -> ui.cancelTimer(TICK)
            AppEvent.Visible -> if (startedAt != null) {
                ui.timer(TICK, TICK_MS, repeat = true)
                render(ui)
            }
            else -> Unit
        }
    }

    private fun toggle(ui: AppContext) {
        val since = startedAt
        if (since == null) {
            startedAt = clock()
            ui.timer(TICK, TICK_MS, repeat = true)
        } else {
            elapsed += clock() - since
            startedAt = null
            ui.cancelTimer(TICK)
        }
        render(ui)
    }

    private fun render(ui: AppContext) {
        val running = startedAt != null
        val total = elapsed + (startedAt?.let { clock() - it } ?: 0)
        ui.patch(PAGE) {
            text(TIME, format(total, tenths = !running))
            text(START_STOP, if (running) "Stopp" else if (total > 0) "Weiter" else "Start")
        }
    }

    private fun page() = Page(
        id = PAGE,
        name = "Stoppuhr",
        blocks = listOf(
            Block.Heading(id = TIME, text = format(0, tenths = true), size = HeadingSize.GROSS, align = Align.CENTER),
            Block.Button(id = START_STOP, text = "Start"),
            Block.Button(id = RESET, text = "Zurücksetzen"),
        ),
    )

    companion object {
        const val PAGE = "p_main"
        const val TIME = "zeit"
        const val START_STOP = "startstop"
        const val RESET = "reset"
        const val TICK = "tick"
        const val TICK_MS = 500L

        /** 1:05 while running, 1:05,3 when stopped; hours only when needed. */
        fun format(ms: Long, tenths: Boolean): String {
            val seconds = ms / 1000
            val h = seconds / 3600
            val m = seconds / 60 % 60
            val s = seconds % 60
            val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
            return if (tenths) "$base,${ms / 100 % 10}" else base
        }
    }
}
