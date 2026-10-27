package ch.madtreasures.navi

import ch.madtreasures.g2watch.apps.AppStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.ZoneOffset

/** A continent of the catalog. */
data class Group(val id: String, val name: String)

/**
 * A map to download: [id] is its path on the server without ".map" (`europe/switzerland`); a [parent] holds
 * the regions of a big country; a folder entry ([bytes] null) only groups regions.
 */
data class Region(
    val id: String,
    val name: String,
    val group: String,
    val parent: String?,
    val bytes: Long?,
    val date: String?,
    val box: Box,
) {
    val isFolder: Boolean get() = bytes == null

    /** The map's file name on the watch: `europe_switzerland`. */
    val file: String get() = id.replace('/', '_')
}

/**
 * All maps of download.mapsforge.org, from the catalog the package ships (assets catalog.json, made by
 * tools/catalog.py): every country of the world, big ones also in regions, with size, date and box.
 */
class Catalog(val generated: String, val mapBase: String, val groups: List<Group>, val regions: List<Region>) {
    private val byId = regions.associateBy { it.id }

    fun region(id: String): Region? = byId[id]

    /** The countries of [group] (no regions of a country). */
    fun countries(group: String): List<Region> = regions.filter { it.group == group && it.parent == null }

    fun children(parent: String): List<Region> = regions.filter { it.parent == parent }

    fun url(r: Region): String = mapBase + r.id + ".map"

    /**
     * The maps that hold [p], smallest first (a region before its country), skipping the world overview:
     * what to offer when the wearer stands where no installed map reaches.
     */
    fun holding(p: LatLon): List<Region> = regions
        .filter { !it.isFolder && it.group != "world" && it.box.contains(p.latE6, p.lonE6) }
        .sortedBy { it.bytes ?: Long.MAX_VALUE }

    companion object {
        fun parse(text: String): Catalog {
            val o = Json.parseToJsonElement(text).jsonObject
            val groups = o["groups"]!!.jsonArray.map { g -> Group(g.str("id")!!, g.str("name")!!) }
            val regions = o["regions"]!!.jsonArray.map { e ->
                val b = e.jsonObject["bbox"]!!.jsonArray.map { (it.jsonPrimitive.doubleOrNull ?: 0.0) }
                Region(
                    id = e.str("id")!!,
                    name = e.str("name")!!,
                    group = e.str("group")!!,
                    parent = e.str("parent"),
                    bytes = (e.jsonObject["bytes"] as? JsonPrimitive)?.longOrNull,
                    date = e.str("date"),
                    box = Box((b[0] * 1e6).toInt(), (b[1] * 1e6).toInt(), (b[2] * 1e6).toInt(), (b[3] * 1e6).toInt()),
                )
            }
            return Catalog(o.str("generated") ?: "", o.str("mapBase")!!, groups, regions)
        }

        private fun JsonElement.str(key: String): String? = (jsonObject[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    }
}

/** A line of the server's folder listing: when the map was made (UTC) and its size as listed ("319M"). */
data class Listed(val date: String, val size: String)

/**
 * The mapsforge server's folder listings (Apache), for the newest dates: `name.map` → [Listed]. The listing's
 * times are the server's (UTC+8); they become UTC dates like the catalog's.
 */
object Listing {
    private val ROW = Regex("""href="([^"/]+)\.map">.*?<td align="right">(\d{4}-\d\d-\d\d \d\d:\d\d)\s*</td><td align="right">\s*([0-9.]+[KMG]?)\s*</td>""")

    fun parse(html: String): Map<String, Listed> = ROW.findAll(html).associate { m ->
        val (name, time, size) = m.destructured
        val utc = LocalDateTime.parse(time.replace(' ', 'T')).atOffset(ZoneOffset.ofHours(SERVER_OFFSET)).withOffsetSameInstant(ZoneOffset.UTC)
        name to Listed(utc.toLocalDate().toString(), size)
    }

    /** The folder listing of [r]'s server folder. */
    fun folderOf(catalog: Catalog, r: Region): String = catalog.mapBase + r.id.substringBeforeLast('/') + "/"

    private const val SERVER_OFFSET = 8
}

/** A map on the watch: which region, how big, from when, and whether its search index is built. */
data class Installed(val id: String, val name: String, val bytes: Long, val date: String, val indexed: Boolean) {
    val file: String get() = id.replace('/', '_')
}

/**
 * What the app keeps across starts (its 256 KiB store): the installed maps, on foot or by bike, LTE allowed,
 * the last position and the last destinations. Files live in the app's folder: maps/<file>.map and
 * index/<file>.idx.
 */
class NaviStore(private val storage: AppStorage) {
    fun installed(): List<Installed> = (storage.get(MAPS) as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        Installed(
            (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
            (o["name"] as? JsonPrimitive)?.contentOrNull ?: "",
            (o["bytes"] as? JsonPrimitive)?.longOrNull ?: 0,
            (o["date"] as? JsonPrimitive)?.contentOrNull ?: "",
            (o["indexed"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    fun put(map: Installed) = saveMaps(installed().filter { it.id != map.id } + map)

    fun remove(id: String) = saveMaps(installed().filter { it.id != id })

    private fun saveMaps(maps: List<Installed>) = storage.set(
        MAPS,
        buildJsonArray {
            for (m in maps.sortedBy { it.name }) add(
                buildJsonObject {
                    put("id", m.id)
                    put("name", m.name)
                    put("bytes", m.bytes)
                    put("date", m.date)
                    put("indexed", m.indexed)
                },
            )
        },
    )

    var profile: Profile
        get() = Profile.of((storage.get(PROFILE) as? JsonPrimitive)?.contentOrNull)
        set(value) = storage.set(PROFILE, JsonPrimitive(value.name))

    var lte: Boolean
        get() = (storage.get(LTE) as? JsonPrimitive)?.booleanOrNull ?: false
        set(value) = storage.set(LTE, JsonPrimitive(value))

    var lastPosition: LatLon?
        get() = (storage.get(POSITION) as? JsonObject)?.let { o ->
            val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull
            val lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull
            if (lat != null && lon != null) LatLon(lat, lon) else null
        }
        set(value) = storage.set(POSITION, value?.let { buildJsonObject { put("lat", it.lat); put("lon", it.lon) } } ?: JsonNull)

    /** The last destinations, newest first: their names for the watch's suggestions. */
    fun recent(): List<Place> = (storage.get(RECENT) as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        Place(
            (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
            (o["context"] as? JsonPrimitive)?.contentOrNull,
            (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
            (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
            ((o["kind"] as? JsonPrimitive)?.longOrNull ?: Kind.POI.toLong()).toInt(),
        )
    }

    fun remember(place: Place) {
        val list = (listOf(place) + recent().filter { it.label != place.label }).take(MAX_RECENT)
        storage.set(
            RECENT,
            buildJsonArray {
                for (p in list) add(
                    buildJsonObject {
                        put("name", p.name)
                        p.context?.let { put("context", it) }
                        put("lat", p.lat)
                        put("lon", p.lon)
                        put("kind", p.kind)
                    },
                )
            },
        )
    }

    private companion object {
        const val MAPS = "karten"
        const val PROFILE = "profil"
        const val LTE = "lte"
        const val POSITION = "standort"
        const val RECENT = "ziele"
        const val MAX_RECENT = 5
    }
}
