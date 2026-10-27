package ch.madtreasures.navi

/**
 * What the tags of one map file mean for drawing, routing and searching: for every tag of the header a
 * kind and flags, looked up once, so an item is classified with a few array reads.
 */
class MapTags(header: MapHeader) {
    private val wayKind = IntArray(header.wayTags.size)
    private val wayFlag = IntArray(header.wayTags.size)
    private val poiKind = IntArray(header.poiTags.size)

    init {
        header.wayTags.forEachIndexed { i, t ->
            wayKind[i] = wayKindOf(t.key, t.value)
            wayFlag[i] = wayFlagOf(t.key, t.value)
        }
        header.poiTags.forEachIndexed { i, t -> poiKind[i] = poiKindOf(t.key, t.value) }
    }

    /** The way's kind: a road beats a railway, beats water, an area or a building. */
    fun kind(way: MapWay): Int {
        var best = Kind.NONE
        for (t in way.tags) {
            val k = wayKind[t]
            if (k != Kind.NONE && (best == Kind.NONE || Kind.rank(k) < Kind.rank(best))) best = k
        }
        return best
    }

    fun flags(way: MapWay): Int {
        var f = 0
        for (t in way.tags) f = f or wayFlag[t]
        return f
    }

    fun kind(poi: MapPoi): Int {
        var best = Kind.NONE
        for (t in poi.tags) {
            val k = poiKind[t]
            if (k != Kind.NONE && (best == Kind.NONE || k < best)) best = k
        }
        return best
    }

    companion object {
        private fun wayKindOf(key: String, value: String): Int = when (key) {
            "highway" -> when (value) {
                "motorway", "motorway_link" -> Kind.MOTORWAY
                "trunk", "trunk_link" -> Kind.TRUNK
                "primary", "primary_link" -> Kind.PRIMARY
                "secondary", "secondary_link" -> Kind.SECONDARY
                "tertiary", "tertiary_link" -> Kind.TERTIARY
                "unclassified", "residential", "living_street", "road" -> Kind.MINOR
                "service", "services" -> Kind.SERVICE
                "pedestrian" -> Kind.PEDESTRIAN
                "track" -> Kind.TRACK
                "path", "footway", "bridleway" -> Kind.PATH
                "cycleway" -> Kind.CYCLEWAY
                "steps" -> Kind.STEPS
                else -> Kind.NONE
            }
            "railway" -> when (value) {
                "rail", "light_rail", "narrow_gauge", "preserved", "monorail" -> Kind.RAIL
                "tram" -> Kind.TRAM
                "subway" -> Kind.SUBWAY
                "funicular" -> Kind.RAIL
                else -> Kind.NONE
            }
            "waterway" -> when (value) {
                "river", "canal" -> Kind.RIVER
                "stream", "ditch", "drain", "brook" -> Kind.STREAM
                "riverbank", "dock" -> Kind.WATER
                else -> Kind.NONE
            }
            "natural" -> when (value) {
                "water", "glacier" -> if (value == "water") Kind.WATER else Kind.GLACIER
                "sea", "issea" -> Kind.SEA
                "nosea" -> Kind.LAND
                "wood" -> Kind.FOREST
                else -> Kind.NONE
            }
            "landuse" -> when (value) {
                "reservoir", "basin" -> Kind.WATER
                "forest" -> Kind.FOREST
                else -> Kind.NONE
            }
            "building" -> Kind.BUILDING
            "route" -> if (value == "ferry") Kind.FERRY else Kind.NONE
            "aerialway" -> Kind.AERIALWAY
            else -> Kind.NONE
        }

        private fun wayFlagOf(key: String, value: String): Int = when (key) {
            "tunnel" -> if (value != "no") Flag.TUNNEL else 0
            "bridge" -> if (value != "no") Flag.BRIDGE else 0
            "oneway" -> when (value) {
                "yes", "true", "1" -> Flag.ONEWAY
                "-1" -> Flag.ONEWAY_BACK
                else -> 0
            }
            "access" -> when (value) {
                "private", "no" -> Flag.PRIVATE
                else -> 0
            }
            "area" -> if (value == "yes") Flag.AREA else 0
            "admin_level" -> if (value == "2") Flag.ADMIN_2 else 0
            "boundary" -> if (value == "administrative") Flag.BOUNDARY else 0
            else -> 0
        }

        private fun poiKindOf(key: String, value: String): Int = when (key) {
            "place" -> when (value) {
                "city" -> Kind.CITY
                "town" -> Kind.TOWN
                "village" -> Kind.VILLAGE
                "suburb", "quarter", "borough" -> Kind.SUBURB
                "hamlet", "neighbourhood", "isolated_dwelling", "locality", "farm" -> Kind.HAMLET
                else -> Kind.NONE
            }
            "railway" -> if (value == "station" || value == "halt") Kind.STATION else Kind.NONE
            "aeroway" -> if (value == "aerodrome") Kind.STATION else Kind.NONE
            "natural" -> if (value == "peak" || value == "volcano") Kind.PEAK else Kind.NONE
            "mountain_pass" -> Kind.PEAK
            "amenity", "tourism", "shop", "historic", "leisure", "man_made", "station" -> Kind.POI
            else -> Kind.NONE
        }
    }
}

/** Kinds of ways and points. Roads are ordered from the biggest to the smallest. */
object Kind {
    const val NONE = 0
    const val MOTORWAY = 1
    const val TRUNK = 2
    const val PRIMARY = 3
    const val SECONDARY = 4
    const val TERTIARY = 5
    const val MINOR = 6
    const val SERVICE = 7
    const val PEDESTRIAN = 8
    const val TRACK = 9
    const val PATH = 10
    const val CYCLEWAY = 11
    const val STEPS = 12
    const val RAIL = 20
    const val TRAM = 21
    const val SUBWAY = 22
    const val RIVER = 30
    const val STREAM = 31
    const val WATER = 40
    const val SEA = 41
    const val LAND = 42
    const val GLACIER = 43
    const val FOREST = 50
    const val BUILDING = 60
    const val FERRY = 80
    const val AERIALWAY = 81

    // Points, the most important first.
    const val CITY = 101
    const val TOWN = 102
    const val STATION = 103
    const val VILLAGE = 104
    const val SUBURB = 105
    const val PEAK = 106
    const val HAMLET = 107
    const val POI = 120

    fun isRoad(k: Int) = k in MOTORWAY..STEPS

    /** What to call a way without a name. */
    fun label(k: Int): String = when (k) {
        PATH -> "Fussweg"
        PEDESTRIAN -> "Fussgängerzone"
        CYCLEWAY -> "Veloweg"
        STEPS -> "Treppe"
        TRACK -> "Feldweg"
        SERVICE -> "Zufahrt"
        FERRY -> "Fähre"
        else -> "Strasse"
    }

    fun isPlace(k: Int) = k in CITY..HAMLET && k != STATION && k != PEAK

    /** Which kind wins when a way has several: roads, then railways, water, areas, buildings. */
    fun rank(k: Int) = when {
        isRoad(k) -> k
        k == FERRY -> 15
        k in RAIL..SUBWAY -> 20 + k
        k in RIVER..STREAM -> 40 + k
        k in WATER..GLACIER -> 60 + k
        k == FOREST -> 120
        k == AERIALWAY -> 130
        k == BUILDING -> 140
        else -> 200
    }
}

/** Flags of a way. */
object Flag {
    const val TUNNEL = 1
    const val BRIDGE = 2
    const val ONEWAY = 4
    const val ONEWAY_BACK = 8
    const val PRIVATE = 16
    const val AREA = 32
    const val ADMIN_2 = 64
    const val BOUNDARY = 128

    /** Below ground (layer under 0); set by the router from the way's layer, not by a tag. */
    const val UNDERGROUND = 256
}
