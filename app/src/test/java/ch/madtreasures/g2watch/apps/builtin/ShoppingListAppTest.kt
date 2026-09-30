package ch.madtreasures.g2watch.apps.builtin

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.ListItem
import ch.madtreasures.g2watch.apps.MenuItem
import ch.madtreasures.g2watch.apps.builtin.shopping.ShoppingListApp
import ch.madtreasures.g2watch.apps.itemsOf
import ch.madtreasures.g2watch.apps.valueOf
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ShoppingListAppTest {
    private val app = ShoppingListApp()
    private val ui = FakeAppContext.forApp(app)

    private val page get() = ui.page(ShoppingListApp.PAGE)

    private fun start() = app.onEvent(AppEvent.Start, ui)

    @Test
    fun `starts with the example list from its Baukasten page`() {
        start()
        assertEquals(ShoppingListApp.PAGE, ui.current?.id)
        assertEquals(ShoppingListApp.EXAMPLE, page.itemsOf("items"))
        assertEquals("5", page.valueOf("offen"))
        assertEquals(listOf(MenuItem("beispiel", "Beispielliste")), ui.menu)
    }

    @Test
    fun `ticking counts down and is stored`() {
        start()
        // The host already ticked the row on the glasses before the event arrives.
        ui.patch(ShoppingListApp.PAGE) { item("items", 1, done = true) }
        app.onEvent(AppEvent.Check(ShoppingListApp.PAGE, "items", 1, true), ui)
        assertEquals("4", page.valueOf("offen"))

        // A new session reads the stored list.
        val again = ShoppingListApp()
        val ui2 = FakeAppContext.forApp(again)
        ui.storage.values.forEach { (k, v) -> ui2.storage.set(k, v) }
        again.onEvent(AppEvent.Start, ui2)
        assertEquals(ListItem("Brot", true), ui2.page(ShoppingListApp.PAGE).itemsOf("items")[1])
        assertEquals("4", ui2.page(ShoppingListApp.PAGE).valueOf("offen"))
    }

    @Test
    fun `clearing removes the ticked rows, the menu brings the example back`() {
        start()
        app.onEvent(AppEvent.Check(ShoppingListApp.PAGE, "items", 0, true), ui)
        app.onEvent(AppEvent.Check(ShoppingListApp.PAGE, "items", 2, true), ui)
        app.onEvent(AppEvent.Click(ShoppingListApp.PAGE, "leeren"), ui)
        assertEquals(listOf("Brot", "Kaffee", "Haferflocken"), page.itemsOf("items").map { it.text })
        assertEquals("3", page.valueOf("offen"))
        assertEquals("2 gelöscht", ui.toasts.last())

        app.onEvent(AppEvent.Menu("beispiel"), ui)
        assertEquals(ShoppingListApp.EXAMPLE, page.itemsOf("items"))
    }

    @Test
    fun `an unreadable store falls back to the example`() {
        ui.storage.set("liste", JsonPrimitive("kaputt"))
        start()
        assertEquals(ShoppingListApp.EXAMPLE, page.itemsOf("items"))
    }
}
