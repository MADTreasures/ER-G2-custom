package ch.madtreasures.stoppuhr

import ch.madtreasures.g2watch.apps.Align
import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.Block
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.HeadingSize
import ch.madtreasures.g2watch.apps.Page
import java.util.Locale

/**
 * Start/stop stopwatch, the example of 03 §1 and the smallest app package (09). It ticks only while
 * visible to keep the link to the glasses quiet; the time itself comes from the clock, so nothing is
 * lost while it is hidden.
 */
class StopwatchApp(private val clock: () -> Long = System::currentTimeMillis) : G2App {

    override val manifest = AppManifest(
        id = "ch.madtreasures.stoppuhr",
        name = "Stoppuhr",
        version = "1.0.0",
        description = "Misst Zeit; zeigt Minuten, Sekunden und Zehntel.",
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
                "startstop" -> toggle(ui)
                "reset" -> {
                    startedAt = null
                    elapsed = 0
                    ui.cancelTimer(TICK)
                    render(ui)
                }
            }
            is AppEvent.Timer -> render(ui)
            AppEvent.Hidden -> ui.cancelTimer(TICK)
            AppEvent.Visible -> if (startedAt != null) {
                render(ui)
                ui.timer(TICK, TICK_MS, repeat = true)
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
        val total = elapsed + (startedAt?.let { clock() - it } ?: 0)
        ui.patch(PAGE) {
            text("zeit", format(total))
            text("startstop", if (startedAt == null) "Start" else "Stopp")
        }
    }

    private fun page() = Page(
        id = PAGE,
        name = "Stoppuhr",
        blocks = listOf(
            Block.Heading(id = "zeit", text = format(0), size = HeadingSize.GROSS, align = Align.CENTER),
            Block.Button(id = "startstop", text = "Start"),
            Block.Button(id = "reset", text = "Zurücksetzen"),
        ),
    )

    companion object {
        const val PAGE = "p_main"
        private const val TICK = "tick"
        private const val TICK_MS = 500L

        /** "m:ss,t", e.g. 1:05,3. */
        fun format(ms: Long): String = String.format(Locale.ROOT, "%d:%02d,%d", ms / 60_000, ms / 1000 % 60, ms / 100 % 10)
    }
}
