package ch.madtreasures.g2watch.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Firmware reaches the glasses on exactly one path, and this guard fails as soon as app code
 * opens a second one:
 *
 * - Faceclaw's flashing flows are used only by [FirmwareJob].
 * - The firmware link is armed only in [FirmwareJob]'s transfer step (after the prompt on the
 *   glasses and the allow-list check), once.
 * - The installer is started only from the confirm page's two-second hold in MainActivity.
 */
class FlashingBoundaryTest {
    // Unit tests run in the module directory.
    private val sources = File("src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()

    private fun textOf(name: String) = sources.single { it.name == name }.readText()

    @Test
    fun `sources are found`() {
        assertTrue("no sources found in ${File("src/main").absolutePath}", sources.size > 10)
    }

    @Test
    fun `only the firmware job uses Faceclaw's flashing flows`() {
        val flows = listOf("OtaFlashFlow", "FlashPromptFlow", "FaceclawFirmwareFlasherListener", "FaceclawFlashPromptListener")
        for (file in sources) {
            if (file.name == "FirmwareJob.kt") continue
            val text = file.readText()
            for (name in flows) assertTrue("${file.path} uses $name", !text.contains(name))
        }
        val job = textOf("FirmwareJob.kt")
        assertEquals(1, Regex("""OtaFlashFlow\(""").findAll(job).count())
    }

    @Test
    fun `the firmware link is armed in one place, right before the transfer`() {
        for (file in sources) {
            var text = file.readText()
            if (file.name == "GuardedStockLink.kt") text = text.replace("fun arm()", "")
            // Also catches with(link) { arm() } and link.apply { arm() }.
            val calls = Regex("""\barm\(\)""").findAll(text).count()
            if (file.name == "FirmwareJob.kt") assertEquals("${file.path} arms the link", 1, calls)
            else assertEquals("${file.path} arms the link", 0, calls)
        }
    }

    @Test
    fun `only the guard talks to the update characteristic, on links it wraps`() {
        for (file in sources) {
            if (file.name == "GuardedStockLink.kt") continue
            assertTrue("${file.path} names the update characteristic", !file.readText().contains("OTA_DATA_WRITE_UUID"))
        }
        // A WatchStockLink is only ever made inside WatchStockLink.guarded().
        for (file in sources) {
            val made = Regex("""WatchStockLink\(""").findAll(file.readText()).count()
            if (file.name == "WatchStockLink.kt") assertEquals(1, Regex("""val link = WatchStockLink\(context\)""").findAll(file.readText()).count())
            else assertEquals("${file.path} makes an unguarded link", 0, made)
        }
        // And nothing else builds Faceclaw's own unguarded stock link.
        for (file in sources) assertTrue("${file.path} uses AndroidStockLink", !file.readText().contains("AndroidStockLink("))
        val job = textOf("FirmwareJob.kt")
        val transfer = job.substring(job.indexOf("private fun transfer("), job.indexOf("// --- 7."))
        // The allow-list check comes first in the same step (both must be there).
        val check = transfer.indexOf("if (env.images.kindOf(hash) != kind) throw Stop(")
        val arm = transfer.indexOf("link.arm()")
        assertTrue("allow-list check $check, arm $arm", check >= 0 && arm > check)
        // Arming happens after the confirmation on the glasses in the flow of run().
        val run = job.substring(job.indexOf("fun run(): FirmwareInstall"), job.indexOf("// --- 1. watch"))
        val confirm = run.indexOf("confirmOnGlasses()")
        val flash = run.indexOf("transfer(image)")
        assertTrue("confirm $confirm, transfer $flash", confirm >= 0 && flash > confirm)
    }

    @Test
    fun `the installer starts only from the confirm page`() {
        for (file in sources) {
            if (file.name == "MainActivity.kt") continue
            val text = file.readText()
            assertTrue("${file.path} starts an install", !Regex("""firmware\.install\(""").containsMatchIn(text))
        }
        val main = textOf("MainActivity.kt")
        assertEquals(1, Regex("""firmware\.install\(""").findAll(main).count())
        val confirm = main.substring(main.indexOf("onConfirm = {"))
        assertTrue(confirm.substring(0, confirm.indexOf("},")).contains("firmware.install(firmwareTarget)"))
    }
}
