package ch.madtreasures.g2watch.glasses

import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The watch's protocol (Settings → Protokoll) as a file that Android Studio's Device Explorer or adb can
 * fetch: `Android/data/ch.madtreasures.g2watch/files/protokoll/protokoll.txt`. Optional: nothing is
 * written until the wearer switches it on in the settings ([enabled]); switching on first writes the
 * lines the watch already shows ([start]). Every line gets the date in front of the time; past [maxBytes]
 * the file becomes `protokoll-alt.txt` and a new one starts, so at most twice that is kept. Writing runs
 * on [io]; a failed write loses the line, never the app.
 */
class ProtocolFile(
    private val dir: File?,
    private val io: Executor = Executors.newSingleThreadExecutor { Thread(it, "G2Watch-protocol") },
    private val maxBytes: Long = 512L * 1024,
    private val today: () -> LocalDate = LocalDate::now,
) {
    /** Whether lines go into the file; off by default. */
    @Volatile
    var enabled: Boolean = false

    /** Switches the file on and writes [earlier] first, the protocol lines the watch shows already. */
    fun start(earlier: List<String>) {
        enabled = true
        append("— Protokoll-Datei eingeschaltet —")
        earlier.forEach(::append)
    }

    /** Appends [line] (already with its time, as the watch shows it), if the file is switched on. */
    fun append(line: String) {
        if (!enabled) return
        val d = dir ?: return
        val text = "${today()} $line\n"
        io.execute { write(d, text) }
    }

    private fun write(d: File, text: String) {
        try {
            d.mkdirs()
            val file = File(d, FILE)
            if (file.length() > maxBytes) {
                val old = File(d, OLD)
                old.delete()
                file.renameTo(old)
            }
            file.appendText(text)
        } catch (e: IOException) {
            // No room or no storage: the protocol on the watch screen still has the line.
        }
    }

    companion object {
        const val FILE = "protokoll.txt"
        const val OLD = "protokoll-alt.txt"

        /** Where the file lies, as Device Explorer shows it. */
        const val PATH = "Android/data/ch.madtreasures.g2watch/files/protokoll/$FILE"
    }
}
