package ch.madtreasures.g2watch.apps.host

import ch.madtreasures.g2watch.apps.AppJson
import ch.madtreasures.g2watch.apps.AppStorage
import ch.madtreasures.g2watch.apps.CommandException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/**
 * An app's store as one JSON file (`store.json` in [dir]), read on first use and written on every change
 * (write to a temporary file, then rename, so a crash never leaves half a file). A change that would make
 * the file larger than [AppStorage.MAX_BYTES] is refused.
 */
class FileAppStorage(private val dir: File) : AppStorage {
    private var values: LinkedHashMap<String, JsonElement>? = null

    private val file get() = File(dir, "store.json")

    override fun get(key: String): JsonElement? = load()[key]

    override fun set(key: String, value: JsonElement) {
        val next = LinkedHashMap(load())
        if (value is JsonNull) next.remove(key) else next[key] = value
        val text = JsonObject(next).toString()
        val size = text.toByteArray(Charsets.UTF_8).size
        if (size > AppStorage.MAX_BYTES) {
            throw CommandException.badValue("Speicher voll: $size Byte, erlaubt sind ${AppStorage.MAX_BYTES}")
        }
        try {
            dir.mkdirs()
            val tmp = File(dir, "store.json.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) throw IOException("rename failed")
        } catch (e: IOException) {
            throw CommandException.badValue("Speichern fehlgeschlagen: ${e.message}")
        }
        values = next
    }

    private fun load(): LinkedHashMap<String, JsonElement> {
        values?.let { return it }
        val loaded = LinkedHashMap<String, JsonElement>()
        if (file.isFile) {
            try {
                (AppJson.json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject)?.let { loaded.putAll(it) }
            } catch (e: IOException) {
                // Unreadable: start empty; the next write replaces the file.
            } catch (e: IllegalArgumentException) {
                // Not JSON: likewise.
            }
        }
        values = loaded
        return loaded
    }
}
