package ch.madtreasures.g2watch.apps

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Apps and their host stay away from the firmware path and from Bluetooth (03 §3): everything they do
 * on the glasses goes through the desktop. [ch.madtreasures.g2watch.firmware.FlashingBoundaryTest]
 * guards the firmware side itself.
 */
class AppsBoundaryTest {
    // Unit tests run in the module directory.
    private val sources = File("src/main/java/ch/madtreasures/g2watch/apps").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `sources are found`() {
        assertTrue(sources.size > 10)
    }

    @Test
    fun `no app code imports firmware, Bluetooth or the glasses connection`() {
        val forbidden = listOf(
            "import ch.madtreasures.g2watch.firmware",
            "import ch.madtreasures.g2watch.glasses",
            "import ch.madtreasures.g2watch.ble",
            "import android.bluetooth",
            "import ch.madtreasures.g2watch.firmware",
        )
        for (file in sources) {
            val text = file.readText()
            for (f in forbidden) assertTrue("${file.path}: $f", !text.contains(f))
            // Of Faceclaw's core only the input codes of the protocol.
            val faceclaw = Regex("""import com\.faceclaw\.[\w.]+""").findAll(text).map { it.value }.toList()
            assertTrue("${file.path}: $faceclaw", faceclaw.all { it == "import com.faceclaw.app.BleProtocol" })
        }
    }
}
