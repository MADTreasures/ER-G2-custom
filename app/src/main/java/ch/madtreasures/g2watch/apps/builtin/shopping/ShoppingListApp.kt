package ch.madtreasures.g2watch.apps.builtin.shopping

import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppJson
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.CommandException
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.ListItem
import ch.madtreasures.g2watch.apps.MenuItem
import kotlinx.serialization.json.JsonArray

/**
 * Shopping list with ticks, the example of 02 §10. Its page comes from the Baukasten
 * (`assets/apps/ch.madtreasures.einkauf/ui.json`); the app only fills in the list, which it keeps in
 * its store so it survives restarts.
 */
class ShoppingListApp : G2App {

    override val manifest = AppManifest(
        id = "ch.madtreasures.einkauf",
        name = "Einkauf",
        version = "1.0.0",
        ui = "apps/ch.madtreasures.einkauf/ui.json",
        description = "Einkaufsliste zum Abhaken, gespeichert auf der Uhr.",
    )

    private var items: List<ListItem> = EXAMPLE

    override fun onEvent(event: AppEvent, ui: AppContext) {
        when (event) {
            AppEvent.Start -> {
                items = load(ui)
                ui.menu(listOf(MenuItem(RESET, "Beispielliste")))
                render(ui)
                ui.show(PAGE)
            }
            is AppEvent.Check -> if (event.block == LIST && event.index in items.indices) {
                items = items.toMutableList().also { it[event.index] = it[event.index].copy(done = event.done) }
                save(ui)
                ui.patch(PAGE) { value(OPEN, open()) }
            }
            is AppEvent.Click -> if (event.block == CLEAR) {
                val before = items.size
                items = items.filterNot { it.done }
                save(ui)
                render(ui)
                ui.toast(if (items.isEmpty()) "Alles erledigt" else "${before - items.size} gelöscht")
            }
            is AppEvent.Menu -> if (event.item == RESET) {
                items = EXAMPLE
                save(ui)
                render(ui)
            }
            else -> Unit
        }
    }

    private fun open(): String = items.count { !it.done }.toString()

    private fun render(ui: AppContext) = ui.patch(PAGE) {
        items(LIST, items)
        value(OPEN, open())
    }

    private fun load(ui: AppContext): List<ListItem> {
        val stored = ui.storage.get(KEY) as? JsonArray ?: return EXAMPLE
        return try {
            stored.map { AppJson.decodeItem(it) }
        } catch (e: CommandException) {
            ui.log("Gespeicherte Liste unlesbar, nehme die Beispielliste: ${e.message}")
            EXAMPLE
        }
    }

    private fun save(ui: AppContext) = ui.storage.set(KEY, JsonArray(items.map { AppJson.encodeItem(it) }))

    companion object {
        const val PAGE = "p_liste"
        const val LIST = "items"
        const val OPEN = "offen"
        const val CLEAR = "leeren"
        private const val KEY = "liste"
        private const val RESET = "beispiel"

        val EXAMPLE = listOf(ListItem("Milch"), ListItem("Brot"), ListItem("Äpfel"), ListItem("Kaffee"), ListItem("Haferflocken"))
    }
}
