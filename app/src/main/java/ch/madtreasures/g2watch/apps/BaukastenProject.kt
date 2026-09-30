package ch.madtreasures.g2watch.apps

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * A project of the G2 Baukasten (format `g2-baukasten@1`, designer/README.md): the pages an app can
 * ship as `ui.json` instead of building them in code.
 */
data class BaukastenProject(
    val name: String,
    /** Id of the page an app starts with. */
    val start: String,
    val pages: List<Page>,
    val updatedAt: String = "",
) {
    fun page(id: String): Page? = pages.firstOrNull { it.id == id }

    fun toJson(): JsonObject = buildJsonObject {
        put("format", FORMAT)
        put("name", name)
        put("start", start)
        put("updatedAt", updatedAt)
        put("pages", JsonArray(pages.map(AppJson::encodePage)))
    }

    companion object {
        const val FORMAT = "g2-baukasten@1"

        /** Reads a project from JSON text; see [normalize]. */
        fun parse(text: String): BaukastenProject {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) throw BaukastenFormatException("Das Feld ist leer – füge zuerst das JSON ein.")
            val element = try {
                AppJson.json.parseToJsonElement(trimmed)
            } catch (e: IllegalArgumentException) {
                throw BaukastenFormatException("Das ist kein gültiges JSON (${e.message}).")
            }
            return normalize(element)
        }

        /**
         * Accepts hand-written or partial JSON and returns a complete project, like the Baukasten's own
         * `normalize`: missing fields get defaults, unknown blocks are dropped, a button target that is
         * neither a page nor [Block.BACK] becomes null. Ids stay unique in the whole project (pages and
         * blocks share one namespace): an empty, invalid or repeated id gets a fresh one.
         */
        fun normalize(input: JsonElement): BaukastenProject {
            val o = input as? JsonObject ?: throw BaukastenFormatException("Erwartet wird ein JSON-Objekt mit „pages“.")
            val format = o["format"]?.takeIf { it !is JsonNull }?.let { str(it) }
            if (format != null && format != FORMAT) {
                if (format.startsWith("faceclaw-edit")) {
                    throw BaukastenFormatException("Das ist ein Entwurf aus dem alten Designer. Der Baukasten liest nur „$FORMAT“.")
                }
                throw BaukastenFormatException("Unbekanntes Format „$format“ – erwartet wird „$FORMAT“.")
            }
            val pagesIn = (o["pages"] as? JsonArray)
                ?: (o["blocks"] as? JsonArray)?.let { JsonArray(listOf(o)) }
                ?: throw BaukastenFormatException("Keine Seiten gefunden – „pages“ muss eine Liste mit mindestens einer Seite sein.")
            if (pagesIn.isEmpty()) {
                throw BaukastenFormatException("Keine Seiten gefunden – „pages“ muss eine Liste mit mindestens einer Seite sein.")
            }

            val ids = FreshIds()
            val pages = pagesIn.filterIsInstance<JsonObject>().mapIndexed { i, p ->
                Page(
                    id = ids.fresh(p["id"], "p"),
                    name = str(p["name"]).trim().ifEmpty { "Seite ${i + 1}" },
                    statusBar = !(p["statusBar"] is JsonPrimitive && (p["statusBar"] as JsonPrimitive).booleanOrNull == false),
                    notes = str(p["notes"]),
                    blocks = ((p["blocks"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { normBlock(it, ids) },
                )
            }
            if (pages.isEmpty()) throw BaukastenFormatException("Keine brauchbare Seite gefunden – jede Seite muss ein Objekt sein.")

            val pageIds = pages.map { it.id }.toSet()
            val linked = pages.map { page ->
                page.copy(
                    blocks = page.blocks.map { b ->
                        if (b is Block.Button && b.target != null && b.target != Block.BACK && b.target !in pageIds) b.copy(target = null) else b
                    },
                )
            }
            val start = str(o["start"]).takeIf { it in pageIds } ?: linked.first().id
            return BaukastenProject(
                name = str(o["name"]).trim().ifEmpty { "Mein Brillen-UI" },
                start = start,
                pages = linked,
                updatedAt = str(o["updatedAt"]),
            )
        }

        private val TYPES = setOf("heading", "text", "button", "list", "toggle", "value", "progress", "divider", "image")

        private fun normBlock(e: JsonElement, ids: FreshIds): Block? {
            val b = e as? JsonObject ?: return null
            val type = str(b["type"])
            if (type !in TYPES) return null
            val id = ids.fresh(b["id"], "b")
            val text = str(b["text"])
            val align = Align.of(str(b["align"])) ?: Align.LEFT
            return when (type) {
                "heading" -> Block.Heading(id, text, align, HeadingSize.of(str(b["size"])) ?: HeadingSize.NORMAL)
                "text" -> Block.Text(id, text, align)
                "button" -> Block.Button(
                    id, text,
                    target = (b["target"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    action = str(b["action"]),
                )
                "list" -> Block.List(
                    id,
                    ((b["items"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { item ->
                        when {
                            item is JsonPrimitive && item !is JsonNull && (item.isString || item.doubleOrNull != null) -> ListItem(item.content)
                            item is JsonObject -> ListItem(str(item["text"]), truthy(item["done"]))
                            else -> null
                        }
                    },
                    ListStyle.of(str(b["style"])) ?: ListStyle.BULLETS,
                )
                "toggle" -> Block.Toggle(id, text, truthy(b["on"]))
                "value" -> Block.Value(id, text, str(b["value"]))
                "progress" -> Block.Progress(id, text, percent(b["value"]))
                "image" -> Block.Image(
                    id,
                    src = (b["src"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    w = size(b["w"], Block.BLEED_MAX_W),
                    h = size(b["h"], Block.BLEED_MAX_H),
                    align = Align.of(str(b["align"])) ?: Align.CENTER,
                    bleed = truthy(b["bleed"]),
                )
                else -> Block.Divider(id)
            }
        }

        /** Like the Baukasten's `str`: text as is, a number as text, anything else [default]. */
        private fun str(e: JsonElement?, default: String = ""): String {
            val p = e as? JsonPrimitive ?: return default
            if (p is JsonNull) return default
            return if (p.isString || p.doubleOrNull != null) p.content else default
        }

        /** JavaScript truthiness, as the Baukasten reads `!!value`. */
        private fun truthy(e: JsonElement?): Boolean = when (e) {
            null, JsonNull -> false
            is JsonPrimitive -> when {
                e.isString -> e.content.isNotEmpty()
                e.booleanOrNull != null -> e.booleanOrNull == true
                else -> e.doubleOrNull.let { it != null && it != 0.0 && !it.isNaN() }
            }
            else -> true
        }

        /** 0–100 from a number or text such as "80 %" or "12,5". */
        private fun percent(e: JsonElement?): Int {
            val p = e as? JsonPrimitive ?: return 0
            val v = if (p.isString) p.content.replace("%", "").replace(',', '.').trim().toDoubleOrNull() else p.doubleOrNull
            return if (v == null || v.isNaN() || v.isInfinite()) 0 else v.coerceIn(0.0, 100.0).roundToInt()
        }

        private fun size(e: JsonElement?, max: Int): Int {
            val v = (e as? JsonPrimitive)?.doubleOrNull ?: return max / 2
            return if (v.isNaN()) max / 2 else v.roundToInt().coerceIn(1, max)
        }
    }

    /** Hands out ids that are valid and unused so far; repeated or invalid ones are replaced. */
    private class FreshIds {
        private val seen = HashSet<String>()
        private var counter = 0

        fun fresh(e: JsonElement?, prefix: String): String {
            var id = str(e).trim().replace(Regex("[^A-Za-z0-9_.-]"), "_").take(40)
            while (id.isEmpty() || id in seen) id = "${prefix}_${++counter}"
            seen += id
            return id
        }

        private fun str(e: JsonElement?): String {
            val p = e as? JsonPrimitive ?: return ""
            if (p is JsonNull) return ""
            return if (p.isString || p.doubleOrNull != null) p.content else ""
        }
    }
}

/** A Baukasten project could not be read; the message is German and says why. */
class BaukastenFormatException(message: String) : IllegalArgumentException(message)
