package ch.madtreasures.g2watch.apps.builtin

import ch.madtreasures.g2watch.apps.AppEvent
import ch.madtreasures.g2watch.apps.BaukastenProject
import ch.madtreasures.g2watch.apps.FakeAppContext
import ch.madtreasures.g2watch.apps.ListItem
import ch.madtreasures.g2watch.apps.builtin.shopping.ShoppingListApp
import ch.madtreasures.g2watch.apps.itemsOf
import ch.madtreasures.g2watch.apps.valueOf
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ShoppingListAppTest {
    private val app = ShoppingListApp()
    private val ui = FakeAppContext()

    /** Like the host: the Baukasten asset is defined before `start`. */
    private fun start() {
        ui.definePages(BaukastenProject.parse(File("src/main/assets/${app.manifest.ui}").readText()))
        app.onEvent(AppEvent.Start, ui)
    }

    private fun page() = ui.page(ShoppingListApp.PAGE)

    /** Like the host: the tick is shown at once, then the app hears about it. */
    private fun check(index: Int, done: Boolean) {
        ui.patch(ShoppingListApp.PAGE) { item(ShoppingListApp.LIST, index, done = done) }
        app.onEvent(AppEvent.Check(ShoppingListApp.PAGE, ShoppingListApp.LIST, index, done), ui)
    }

    @Test
    fun `starts with the example list and its own menu entry`() {
        start()
        assertEquals(ShoppingListApp.PAGE, ui.currentPage?.id)
        assertEquals(ShoppingListApp.EXAMPLE, page().itemsOf(ShoppingListApp.LIST))
        assertEquals("5", page().valueOf(ShoppingListApp.OPEN))
        assertEquals(listOf("Beispiel-Liste"), ui.menuItems.map { it.text })
    }

    @Test
    fun `ticking counts down and is stored`() {
        start()
        check(1, true)
        assertEquals("4", page().valueOf(ShoppingListApp.OPEN))
        val stored = ui.storage.get(ShoppingListApp.KEY).toString()
        assertEquals(true, stored.contains("""{"text":"Brot","done":true}"""))
    }

    @Test
    fun `clearing removes ticked rows, the menu restores the example`() {
        start()
        check(0, true)
        check(2, true)
        app.onEvent(AppEvent.Click(ShoppingListApp.PAGE, ShoppingListApp.CLEAR), ui)
        assertEquals(listOf("Brot", "Kaffee", "Tomaten"), page().itemsOf(ShoppingListApp.LIST).map { it.text })
        assertEquals(listOf("2 gelöscht"), ui.toasts)
        app.onEvent(AppEvent.Menu(ShoppingListApp.MENU_EXAMPLE), ui)
        assertEquals(ShoppingListApp.EXAMPLE, page().itemsOf(ShoppingListApp.LIST))
    }

    @Test
    fun `a stored list comes back on the next start`() {
        start()
        check(4, true)
        val restarted = ShoppingListApp()
        val ui2 = FakeAppContext()
        ui.storage.values.forEach { (k, v) -> ui2.storage.set(k, v) }
        ui2.definePages(BaukastenProject.parse(File("src/main/assets/${app.manifest.ui}").readText()))
        restarted.onEvent(AppEvent.Start, ui2)
        assertEquals(ListItem("Tomaten", true), ui2.page(ShoppingListApp.PAGE).itemsOf(ShoppingListApp.LIST)[4])
        assertEquals("4", ui2.page(ShoppingListApp.PAGE).valueOf(ShoppingListApp.OPEN))
    }
}
