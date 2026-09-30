package ch.madtreasures.g2watch.apps

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.roundToInt

/**
 * A G2 Baukasten export (`g2-baukasten@1`, designer/README.md): the pages of an app, drawn in the
 * browser. [normalize] accepts hand-written or partial JSON the way the Baukasten's own import does,
 * with the additions of 02 §4: the `image` block and the button target `@back`.
 */
data class BaukastenProject(
    val name: String,
    /** Id of the page the app starts with. */
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
        putJsonArray("pages") { pages.forEach { add(AppJson.encodePage(it)) } }
    }

    companion object {
        const val FORMAT = "g2-baukasten@1"

        /**
         * Parses and normalizes a Baukasten export. Throws [IllegalArgumentException] with a German
         * message the log can show when the text is no usable project.
         */
        fun parse(text: String, warn: (String) -> Unit = {}): BaukastenProject {
            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "Das Projekt ist leer." }
            val json = try {
                Json.parseToJsonElement(trimmed)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Das ist kein gültiges JSON (${e.message}).", e)
            }
            return normalize(json, warn)
        }

        /**
         * Completes [input] to a valid project, like the Baukasten's `normalize`: missing fields get
         * defaults, unknown blocks are dropped, button targets that name no page become null
         * (`@back` stays). Ids must match [Block.ID_PATTERN] and be unique across pages and blocks;
         * other characters become `_`, and a missing or duplicate id gets a fresh one (`b_1`, `p_2`, …).
         * Unlike the Baukasten, fresh ids are deterministic and every change is reported to [warn].
         */
        fun normalize(input: JsonElement, warn: (String) -> Unit = {}): BaukastenProject {
            val obj = input as? JsonObject ?: throw IllegalArgumentException("Erwartet wird ein JSON-Objekt mit „pages“.")
            val format = obj["format"]
            if (format != null && format !is JsonNull) {
                val f = format.stringOrNull()
                require(f == FORMAT) { "Unbekanntes Format „$f“ – erwartet wird „$FORMAT“." }
            }
            val pagesIn: kotlin.collections.List<JsonElement> = when {
                obj["pages"] is JsonArray -> obj["pages"] as JsonArray
                obj["blocks"] is JsonArray -> listOf(obj)
                else -> emptyList()
            }
            require(pagesIn.isNotEmpty()) {
                "Keine Seiten gefunden – „pages“ muss eine Liste mit mindestens einer Seite sein."
            }
            val ids = IdAllocator(warn)
            val pages = pagesIn.filterIsInstance<JsonObject>().mapIndexed { i, p ->
                val id = ids.claim(p["id"], "p")
                Page(
                    id = id,
                    name = p["name"].stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "Seite ${i + 1}",
                    statusBar = (p["statusBar"] as? JsonPrimitive)?.booleanOrNull != false,
                    notes = p["notes"].stringOrNull().orEmpty(),
                    blocks = (p["blocks"] as? JsonArray).orEmpty().mapNotNull { normalizeBlock(it, ids) },
                )
            }
            require(pages.isNotEmpty()) { "Keine brauchbare Seite gefunden – jede Seite muss ein Objekt sein." }
            val pageIds = pages.map { it.id }.toSet()
            val fixed = pages.map { page ->
                page.copy(
                    blocks = page.blocks.map { b ->
                        if (b is Block.Button && b.target != null && b.target != Block.BACK && b.target !in pageIds) {
                            warn("Knopf „${b.id}“: Ziel „${b.target}“ gibt es nicht")
                            b.copy(target = null)
                        } else {
                            b
                        }
                    },
                )
            }
            val start = obj["start"].stringOrNull()
            return BaukastenProject(
                name = obj["name"].stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "Mein Brillen-UI",
                start = if (start != null && start in pageIds) start else fixed.first().id,
                pages = fixed,
                updatedAt = obj["updatedAt"].stringOrNull().orEmpty(),
            )
        }

        /** One block, or null for anything that is no known block. */
        internal fun normalizeBlock(element: JsonElement, ids: IdAllocator): Block? {
            val b = element as? JsonObject ?: return null
            val type = b["type"].stringOrNull() ?: return null
            if (type !in Block.TYPES) return null
            val id = ids.claim(b["id"], "b")
            val text = b["text"].stringOrNull().orEmpty()
            val align = Align.of(b["align"].stringOrNull(), if (type == "image") Align.CENTER else Align.LEFT)
            return when (type) {
                "heading" -> Block.Heading(id, text, align, HeadingSize.of(b["size"].stringOrNull()))
                "text" -> Block.Text(id, text, align)
                "button" -> Block.Button(
                    id,
                    text,
                    target = (b["target"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    action = b["action"].stringOrNull().orEmpty(),
                )
                "list" -> Block.List(
                    id,
                    ListStyle.of(b["style"].stringOrNull()),
                    (b["items"] as? JsonArray).orEmpty().mapNotNull(::normalizeItem),
                )
                "toggle" -> Block.Toggle(id, text, b["on"].truthy())
                "value" -> Block.Value(id, text, b["value"].stringOrNull().orEmpty())
                "progress" -> Block.Progress(id, text, percent(b["value"]))
                "divider" -> Block.Divider(id)
                else -> {
                    val bleed = b["bleed"].truthy()
                    val maxW = if (bleed) Block.Image.BLEED_W else Block.Image.MAX_W
                    val maxH = if (bleed) Block.Image.BLEED_H else Block.Image.MAX_H
                    Block.Image(
                        id,
                        src = b["src"].stringOrNull()?.takeIf { it.isNotEmpty() },
                        w = b["w"].intOrNull()?.coerceIn(1, maxW) ?: maxW,
                        h = b["h"].intOrNull()?.coerceIn(1, maxH) ?: 120.coerceAtMost(maxH),
                        align = align,
                        bleed = bleed,
                    )
                }
            }
        }

        private fun normalizeItem(element: JsonElement): ListItem? = when (element) {
            is JsonPrimitive -> if (element is JsonNull) null else ListItem(element.content)
            is JsonObject -> ListItem(element["text"].stringOrNull().orEmpty(), element["done"].truthy())
            else -> null
        }

        /** 0–100 from a number or a text like "80 %" or "12,5". */
        private fun percent(element: JsonElement?): Int {
            val p = element as? JsonPrimitive ?: return 0
            val v = if (p.isString) p.content.replace("%", "").replace(',', '.').trim().toDoubleOrNull() else p.doubleOrNull
            return if (v == null || v.isNaN()) 0 else v.coerceIn(0.0, 100.0).roundToInt()
        }
    }
}

/**
 * Hands out ids that match [Block.ID_PATTERN] and are unique across a project; see
 * [BaukastenProject.normalize]. Also used when an app defines pages from code.
 */
internal class IdAllocator(private val warn: (String) -> Unit = {}, taken: Collection<String> = emptyList()) {
    private val seen = HashSet<String>(taken)
    private var counter = 0

    fun claim(raw: JsonElement?, prefix: String): String {
        val given = (raw as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim().orEmpty()
        val cleaned = given.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(40)
        if (cleaned.isNotEmpty() && seen.add(cleaned)) {
            if (cleaned != given) warn("Kennung „$given“ wird zu „$cleaned“")
            return cleaned
        }
        var fresh: String
        do {
            fresh = "${prefix}_${++counter}"
        } while (!seen.add(fresh))
        warn(if (given.isEmpty()) "Kennung fehlt, vergeben: „$fresh“" else "Kennung „$given“ doppelt, vergeben: „$fresh“")
        return fresh
    }
}

internal fun JsonElement?.stringOrNull(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

internal fun JsonElement?.intOrNull(): Int? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.toDoubleOrNull()?.takeIf { !it.isNaN() }?.roundToInt()
}

/** JavaScript's `!!value` for the JSON the Baukasten writes. */
internal fun JsonElement?.truthy(): Boolean {
    val p = this as? JsonPrimitive ?: return this != null && this !is JsonNull
    if (p is JsonNull) return false
    p.booleanOrNull?.let { return it }
    if (p.isString) return p.content.isNotEmpty()
    return p.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: false
}
