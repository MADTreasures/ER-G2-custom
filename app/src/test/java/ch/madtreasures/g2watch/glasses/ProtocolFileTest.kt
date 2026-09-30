package ch.madtreasures.g2watch.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executor

/** The protocol as a file for Android Studio's Device Explorer. */
class ProtocolFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val direct = Executor { it.run() }
    private val day = LocalDate.of(2026, 10, 1)

    @Test
    fun `lines get the date and are appended`() {
        val dir = File(tmp.root, "protokoll")
        val file = ProtocolFile(dir, direct, today = { day }).apply { enabled = true }
        file.append("14:05:01 Verbunden")
        file.append("14:05:09 YouTube gestartet")
        assertEquals(
            listOf("2026-10-01 14:05:01 Verbunden", "2026-10-01 14:05:09 YouTube gestartet"),
            File(dir, ProtocolFile.FILE).readLines(),
        )
    }

    @Test
    fun `a full file becomes the old one, only two are kept`() {
        val dir = tmp.newFolder("protokoll")
        val file = ProtocolFile(dir, direct, maxBytes = 40, today = { day }).apply { enabled = true }
        repeat(6) { file.append("14:05:0$it Zeile $it") }
        val current = File(dir, ProtocolFile.FILE).readLines()
        val old = File(dir, ProtocolFile.OLD).readLines()
        assertTrue(current.last().endsWith("Zeile 5"))
        assertTrue(old.isNotEmpty() && old.size < 6)
        assertEquals(setOf(ProtocolFile.FILE, ProtocolFile.OLD), dir.list()!!.toSet())
    }

    @Test
    fun `off by default, switching on writes what the watch already shows`() {
        val dir = File(tmp.root, "protokoll")
        val file = ProtocolFile(dir, direct, today = { day })
        file.append("14:05:01 nicht gespeichert")
        assertFalse(File(dir, ProtocolFile.FILE).exists())

        file.start(listOf("14:04:00 Verbunden", "14:05:01 Rechtes Glas abgebrochen"))
        file.append("14:06:00 danach")
        file.enabled = false
        file.append("14:07:00 wieder aus")
        assertEquals(
            listOf(
                "2026-10-01 — Protokoll-Datei eingeschaltet —",
                "2026-10-01 14:04:00 Verbunden",
                "2026-10-01 14:05:01 Rechtes Glas abgebrochen",
                "2026-10-01 14:06:00 danach",
            ),
            File(dir, ProtocolFile.FILE).readLines(),
        )
    }

    @Test
    fun `without storage nothing happens`() {
        ProtocolFile(null, direct).apply { enabled = true }.append("14:05:01 ohne Speicher")
        val blocked = tmp.newFile("kein-ordner")
        ProtocolFile(blocked, direct).apply { enabled = true }.append("14:05:01 kein Ordner")
        assertFalse(File(blocked, ProtocolFile.FILE).exists())
    }
}
