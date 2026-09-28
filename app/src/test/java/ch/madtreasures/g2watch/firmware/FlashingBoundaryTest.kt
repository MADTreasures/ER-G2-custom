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
 * - The installer is started only from the confirm page's two-second hold (install) and the
 *   test-run button (testRun) in MainActivity.
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
            val calls = Regex("""\.arm\(\)""").findAll(file.readText()).count()
            if (file.name == "FirmwareJob.kt") assertEquals("${file.path} arms the link", 1, calls)
            else assertEquals("${file.path} arms the link", 0, calls)
        }
        val job = textOf("FirmwareJob.kt")
        val transfer = job.substring(job.indexOf("private fun transfer("), job.indexOf("// --- 7."))
        assertTrue(transfer.contains("link.arm()"))
        // The allow-list check comes first in the same step.
        assertTrue(transfer.indexOf("images.kindOf(hash)") < transfer.indexOf("link.arm()"))
        // Arming happens after the confirmation on the glasses in the flow of run().
        val run = job.substring(job.indexOf("fun run(): FirmwareInstall"), job.indexOf("// --- 1. watch"))
        assertTrue(run.indexOf("confirmOnGlasses()") < run.indexOf("transfer(image)"))
    }

    @Test
    fun `the installer starts only from the confirm page and the test-run button`() {
        for (file in sources) {
            if (file.name == "MainActivity.kt") continue
            val text = file.readText()
            assertTrue("${file.path} starts an install", !Regex("""firmware\.install\(""").containsMatchIn(text))
            assertTrue("${file.path} starts a test run", !Regex("""firmware\.testRun\(""").containsMatchIn(text))
        }
        val main = textOf("MainActivity.kt")
        assertEquals(1, Regex("""firmware\.install\(""").findAll(main).count())
        assertEquals(1, Regex("""firmware\.testRun\(""").findAll(main).count())
        val confirm = main.substring(main.indexOf("onConfirm = {"))
        assertTrue(confirm.substring(0, confirm.indexOf("},")).contains("firmware.install(firmwareTarget)"))
    }
}
