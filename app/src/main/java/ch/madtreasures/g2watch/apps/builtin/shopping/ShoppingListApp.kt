package ch.madtreasures.g2watch.apps.builtin.shopping

import ch.madtreasures.g2watch.apps.AppContext
import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.AppManifest
import ch.madtreasures.g2watch.apps.G2App
import ch.madtreasures.g2watch.apps.ListItem
import ch.madtreasures.g2watch.apps.MenuItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The shopping list of 02 §10, pages from the Baukasten (`assets/apps/<id>/ui.json`). Ticked rows
 * are kept in the app's storage; "Erledigte löschen" removes them, the app menu restores the example.
 */
class ShoppingListApp : G2App {

    override val manifest = AppManifest(
        id = ID,
        name = "Einkauf",
        version = "1.0.0",
        ui = "apps/$ID/ui.json",
        description = "Einkaufsliste zum Abhaken, gespeichert auf der Uhr.",
    )

    private var items: List<ListItem> = EXAMPLE

    override fun onEvent(event: AppEvent, ui: AppContext) {
        when (event) {
            AppEvent.Start -> {
                items = load(ui) ?: EXAMPLE
                ui.menu(listOf(MenuItem(MENU_EXAMPLE, "Beispiel-Liste")))
                render(ui)
                ui.show(PAGE)
            }
            is AppEvent.Check -> if (event.block == LIST && event.index in items.indices) {
                // The host already shows the tick; keep the model in step and store it.
                items = items.toMutableList().also { it[event.index] = it[event.index].copy(done = event.done) }
                save(ui)
                ui.patch(PAGE) { value(OPEN, open().toString()) }
            }
            is AppEvent.Click -> if (event.block == CLEAR) {
                val removed = items.count { it.done }
                items = items.filterNot { it.done }
                save(ui)
                render(ui)
                ui.toast(if (removed == 0) "Nichts abgehakt" else "$removed gelöscht")
            }
            is AppEvent.Menu -> if (event.item == MENU_EXAMPLE) {
                items = EXAMPLE
                save(ui)
                render(ui)
            }
            else -> Unit
        }
    }

    private fun open(): Int = items.count { !it.done }

    private fun render(ui: AppContext) {
        ui.patch(PAGE) {
            items(LIST, items)
            value(OPEN, open().toString())
        }
    }

    private fun load(ui: AppContext): List<ListItem>? {
        val stored = ui.storage.get(KEY) as? JsonArray ?: return null
        return stored.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val text = (o["text"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            ListItem(text, (o["done"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }
    }

    private fun save(ui: AppContext) {
        ui.storage.set(KEY, buildJsonArray { items.forEach { add(buildJsonObject { put("text", it.text); put("done", it.done) }) } })
    }

    companion object {
        const val ID = "ch.madtreasures.einkauf"
        const val PAGE = "p_liste"
        const val LIST = "items"
        const val OPEN = "offen"
        const val CLEAR = "leeren"
        const val KEY = "liste"
        const val MENU_EXAMPLE = "beispiel"
        val EXAMPLE = listOf(ListItem("Milch"), ListItem("Brot"), ListItem("Äpfel"), ListItem("Kaffee"), ListItem("Tomaten"))
    }
}
