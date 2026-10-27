package ch.madtreasures.navi

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** On foot or by bike, the two ways Even's navigation knows; with what each kind of way costs. */
enum class Profile(
    val label: String,
    val speedKmh: Double,
    /** The lowest cost per metre of any way (for the search's estimate), and what a metre usually costs. */
    internal val minFactor: Double,
    internal val farFactor: Double,
) {
    FOOT("Zu Fuss", 4.8, 0.95, 1.2),
    BIKE("Velo", 16.0, 0.85, 1.4),
    ;

    val speedMs: Double get() = speedKmh / 3.6

    /**
     * Cost per metre on a way of [kind] with [flags], travelled along its points ([forward]) or against
     * them; null where the route may not go (motorways, against a one-way street by bike).
     */
    fun factor(kind: Int, flags: Int, forward: Boolean): Double? {
        val base = when (this) {
            FOOT -> when (kind) {
                Kind.PRIMARY -> 1.4
                Kind.SECONDARY -> 1.25
                Kind.TERTIARY -> 1.1
                Kind.MINOR -> 1.0
                Kind.SERVICE -> 1.05
                Kind.PEDESTRIAN -> 0.95
                Kind.TRACK -> 1.05
                Kind.PATH -> 1.0
                Kind.CYCLEWAY -> 1.15
                Kind.STEPS -> 1.3
                Kind.FERRY -> 1.0
                else -> null
            }
            BIKE -> when (kind) {
                Kind.PRIMARY -> 1.6
                Kind.SECONDARY -> 1.35
                Kind.TERTIARY -> 1.15
                Kind.MINOR -> 1.0
                Kind.SERVICE -> 1.1
                Kind.PEDESTRIAN -> 2.0
                Kind.TRACK -> 1.4
                Kind.PATH -> 1.6
                Kind.CYCLEWAY -> 0.85
                Kind.STEPS -> 8.0
                Kind.FERRY -> 1.0
                else -> null
            }
        } ?: return null
        if (this == BIKE && kind != Kind.PATH && kind != Kind.PEDESTRIAN && kind != Kind.STEPS && kind != Kind.FERRY) {
            if (flags and Flag.ONEWAY != 0 && !forward) return null
            if (flags and Flag.ONEWAY_BACK != 0 && forward) return null
        }
        return if (flags and Flag.PRIVATE != 0) base * 6 else base
    }

    companion object {
        fun of(name: String?): Profile = entries.firstOrNull { it.name == name } ?: FOOT
    }
}

/** A route could not be found; [message] says why, in German, for the wearer. */
class RouteException(message: String) : Exception(message)

/**
 * What one block of a map offers a route: the junctions, the ends of ways and the points near the block's
 * edge (where it meets its neighbours) as nodes; the stretches of way between them as edges in both
 * directions, with their length, kind, flags, name and points.
 */
class RoadBlock private constructor(
    val keys: LongArray,
    private val index: LongIntMap,
    private val first: IntArray,
    val to: IntArray,
    val length: FloatArray,
    val kind: ByteArray,
    private val flags: IntArray,
    private val nameOf: IntArray,
    private val geomStart: IntArray,
    private val geomCount: IntArray,
    private val names: Array<String?>,
    private val pool: IntArray,
) {
    /** The node of [key] in this block, or [LongIntMap.MISSING]. */
    fun vertex(key: Long): Int = index[key]

    val vertexCount: Int get() = keys.size

    fun edgesFrom(v: Int): IntRange = first[v] until first[v + 1]

    fun forward(e: Int): Boolean = flags[e] and FORWARD != 0

    fun wayFlags(e: Int): Int = flags[e] and FORWARD.inv()

    fun name(e: Int): String? = names[nameOf[e]]

    fun points(e: Int): Int = geomCount[e]

    /** Point [j] of edge [e] in the direction the edge runs. */
    fun lat(e: Int, j: Int): Int = pool[2 * index(e, j)]

    fun lon(e: Int, j: Int): Int = pool[2 * index(e, j) + 1]

    private fun index(e: Int, j: Int): Int = if (forward(e)) geomStart[e] + j else geomStart[e] + geomCount[e] - 1 - j

    /** Memory this block holds, about. */
    val bytes: Long = keys.size * 8L + index.bytes + first.size * 4L + to.size * 25L + pool.size * 4L

    companion object {
        const val FORWARD = 1 shl 30

        fun key(lat: Int, lon: Int): Long = (lat.toLong() shl 32) or (lon.toLong() and 0xFFFFFFFFL)

        fun latOf(key: Long): Int = (key shr 32).toInt()

        fun lonOf(key: Long): Int = key.toInt()

        fun routable(kind: Int) = Kind.isRoad(kind) || kind == Kind.FERRY

        /**
         * Builds the road block from [block] of a map with [tags]; points within [margin] (microdegrees of
         * latitude) of the block's [bounds], or beyond them, stay nodes so neighbouring blocks connect there.
         */
        fun build(block: MapBlock, tags: MapTags, bounds: Box, margin: Int): RoadBlock {
            val rings = ArrayList<IntArray>()
            val ringKind = IntList()
            val ringFlags = IntList()
            val ringName = IntList()
            val names = ArrayList<String?>().apply { add(null) }
            val nameIndex = HashMap<String, Int>()
            for (way in block.ways) {
                val kind = tags.kind(way)
                if (!routable(kind)) continue
                // Below ground (layer under 0, stored as under 5): station floors, passages.
                val flags = tags.flags(way) or (if (way.layer < 5) Flag.UNDERGROUND else 0)
                val name = MapFile.localName(way.name) ?: way.ref
                val n = if (name == null) 0 else nameIndex.getOrPut(name) { names.add(name); names.size - 1 }
                for (part in way.parts) for (ring in part) {
                    if (ring.size < 4) continue
                    rings += ring
                    ringKind.add(kind)
                    ringFlags.add(flags)
                    ringName.add(n)
                }
            }
            val use = LongIntMap(rings.sumOf { it.size / 2 })
            for (ring in rings) {
                val m = ring.size / 2
                for (i in 0 until m) {
                    val k = key(ring[2 * i], ring[2 * i + 1])
                    val c = use[k]
                    // The ends of a way count twice, so they are always nodes.
                    val add = if (i == 0 || i == m - 1) 2 else 1
                    use[k] = (if (c == LongIntMap.MISSING) 0 else c) + add
                }
            }
            val lonMargin = (margin / max(0.2, cos(Math.toRadians(bounds.center.lat)))).toInt()
            fun kept(lat: Int, lon: Int): Boolean =
                use[key(lat, lon)] >= 2 || lat < bounds.minLat + margin || lat > bounds.maxLat - margin ||
                    lon < bounds.minLon + lonMargin || lon > bounds.maxLon - lonMargin

            val vertex = LongIntMap(1024)
            val vkeys = ArrayList<Long>()
            fun vid(lat: Int, lon: Int): Int {
                val k = key(lat, lon)
                val v = vertex[k]
                if (v != LongIntMap.MISSING) return v
                vertex[k] = vkeys.size
                vkeys += k
                return vkeys.size - 1
            }
            val pool = IntList(rings.sumOf { it.size } + 16)
            val eFrom = IntList()
            val eTo = IntList()
            val eLen = FloatList()
            val eKind = IntList()
            val eFlags = IntList()
            val eName = IntList()
            val eStart = IntList()
            val eCount = IntList()
            for ((r, ring) in rings.withIndex()) {
                val m = ring.size / 2
                val cosLat = cos(Math.toRadians(ring[0] / 1e6))
                var startPool = pool.size / 2
                pool.add(ring[0])
                pool.add(ring[1])
                var startNode = 0
                var acc = 0.0
                for (i in 1 until m) {
                    val lat = ring[2 * i]
                    val lon = ring[2 * i + 1]
                    pool.add(lat)
                    pool.add(lon)
                    val dy = (lat - ring[2 * i - 2]) * Geo.M_PER_DEG / 1e6
                    val dx = (lon - ring[2 * i - 1]) * Geo.M_PER_DEG / 1e6 * cosLat
                    acc += sqrt(dx * dx + dy * dy)
                    if (i == m - 1 || kept(lat, lon)) {
                        val u = vid(ring[2 * startNode], ring[2 * startNode + 1])
                        val v = vid(lat, lon)
                        val count = i - startNode + 1
                        if (u != v || acc > 0.5) {
                            for (forward in listOf(true, false)) {
                                eFrom.add(if (forward) u else v)
                                eTo.add(if (forward) v else u)
                                eLen.add(acc.toFloat())
                                eKind.add(ringKind[r])
                                eFlags.add(ringFlags[r] or (if (forward) FORWARD else 0))
                                eName.add(ringName[r])
                                eStart.add(startPool)
                                eCount.add(count)
                            }
                        }
                        startNode = i
                        startPool = pool.size / 2 - 1
                        acc = 0.0
                    }
                }
            }
            // Edges sorted by their start node (counting sort).
            val n = vkeys.size
            val e = eFrom.size
            val first = IntArray(n + 1)
            for (i in 0 until e) first[eFrom[i] + 1]++
            for (i in 0 until n) first[i + 1] += first[i]
            val at = first.copyOf()
            val order = IntArray(e)
            for (i in 0 until e) order[at[eFrom[i]]++] = i
            return RoadBlock(
                keys = LongArray(n) { vkeys[it] },
                index = vertex,
                first = first,
                to = IntArray(e) { eTo[order[it]] },
                length = FloatArray(e) { eLen.data[order[it]] },
                kind = ByteArray(e) { eKind[order[it]].toByte() },
                flags = IntArray(e) { eFlags[order[it]] },
                nameOf = IntArray(e) { eName[order[it]] },
                geomStart = IntArray(e) { eStart[order[it]] },
                geomCount = IntArray(e) { eCount[order[it]] },
                names = names.toTypedArray(),
                pool = pool.toArray(),
            )
        }
    }
}

/**
 * Finds routes on foot or by bike over the ways of the installed maps. Each map's blocks of zoom 14 become
 * road blocks when a search first needs them ([RoadBlock]) and stay for the next search while there is
 * room ([budgetBytes]). A* with the straight distance as estimate; Thread.interrupted() stops it.
 */
class Router(
    private val sources: List<MapSource>,
    private val budgetBytes: Long = BUDGET_BYTES,
    /** Whether long routes may be a little longer for a far smaller search (see [route]); off finds the shortest. */
    private val headForGoal: Boolean = true,
) {
    private class Place(val src: Int, val x: Int, val y: Int)

    private val cache = LinkedHashMap<Long, RoadBlock>(64, 0.75f, true)
    private var cachedBytes = 0L

    /** How the last search went: nodes taken from the queue, nodes reached, road blocks built. */
    var lastExpanded = 0
        private set
    var lastReached = 0
        private set
    var blocksBuilt = 0
        private set

    /** Where a point lies on an edge: [seg] between points seg and seg + 1, [along] metres from its start. */
    class Snap(val block: RoadBlock, val edge: Int, val seg: Int, val t: Double, val along: Double, val lat: Int, val lon: Int, val distance: Double)

    /** The route from [from] to [to]; throws [RouteException] with the reason. */
    fun route(from: LatLon, to: LatLon, profile: Profile, maxSnapM: Double = SNAP_M): Route {
        val start = snap(from, profile, maxSnapM) ?: throw RouteException("Beim Start ist kein Weg in der Nähe")
        val goal = snap(to, profile, maxSnapM) ?: throw RouteException("Beim Ziel ist kein Weg in der Nähe")
        val sb = start.block
        val se = start.edge
        val gb = goal.block
        val ge = goal.edge
        val sKind = sb.kind[se].toInt()
        val sFlags = sb.wayFlags(se)
        val gKind = gb.kind[ge].toInt()
        val gFlags = gb.wayFlags(ge)
        val sLen = sb.length[se].toDouble()
        val gLen = gb.length[ge].toDouble()
        val uKey = RoadBlock.key(sb.lat(se, 0), sb.lon(se, 0))
        val vKey = sb.keys[sb.to[se]]
        val u2 = RoadBlock.key(gb.lat(ge, 0), gb.lon(ge, 0))
        val v2 = gb.keys[gb.to[ge]]
        // Start and destination on the same stretch, and it may be taken that way: straight along it.
        if (sb === gb && se == ge) {
            val fwd = goal.along >= start.along
            profile.factor(sKind, sFlags, fwd == sb.forward(se))?.let { return straight(start, goal, profile) }
        }
        val targetLat = goal.lat / 1e6
        val targetLon = goal.lon / 1e6
        val cosT = cos(Math.toRadians(targetLat))
        // The straight distance as estimate, at the profile's lowest cost per metre. On long routes it counts
        // up to what a metre usually costs, so the search heads for the goal instead of filling the land around
        // the start: the route may come out a few per cent longer, with a fraction of the nodes.
        val straightM = Geo.distanceE6(start.lat, start.lon, goal.lat, goal.lon)
        val far = if (headForGoal) ((straightM - FAR_FROM_M) / FAR_SPAN_M).coerceIn(0.0, 1.0) else 0.0
        val estimate = profile.minFactor + (profile.farFactor - profile.minFactor) * far
        fun h(key: Long): Double {
            val dy = (RoadBlock.latOf(key) / 1e6 - targetLat) * Geo.M_PER_DEG
            val dx = (RoadBlock.lonOf(key) / 1e6 - targetLon) * Geo.M_PER_DEG * cosT
            return sqrt(dx * dx + dy * dy) * estimate
        }
        val g = LongCostMap(4096)
        val heap = MinHeap(4096)
        // From the start point to the two ends of its stretch: back along it to u, on to v.
        // Costs are kept as floats (a centimetre on 100 km); the queue gets exactly what the map keeps.
        profile.factor(sKind, sFlags, !sb.forward(se))?.let { f ->
            val c = (start.along * f).toFloat().toDouble()
            g.put(uKey, c, SEED_U)
            heap.push(c + h(uKey), uKey)
        }
        profile.factor(sKind, sFlags, sb.forward(se))?.let { f ->
            val c = ((sLen - start.along) * f).toFloat().toDouble()
            if (c < g.cost(vKey)) {
                g.put(vKey, c, SEED_V)
                heap.push(c + h(vKey), vKey)
            }
        }
        // From the ends of the destination's stretch to the destination point.
        val toGoalFromU = profile.factor(gKind, gFlags, gb.forward(ge))?.let { goal.along * it }
        val toGoalFromV = profile.factor(gKind, gFlags, !gb.forward(ge))?.let { (gLen - goal.along) * it }
        var best = Double.POSITIVE_INFINITY
        var bestEnd = 0L
        var expanded = 0
        while (!heap.isEmpty()) {
            val priority = heap.peekPriority()
            if (priority >= best) break
            val k = heap.pop()
            // Each node is taken once, at its lowest cost (with the weighted estimate a later, slightly cheaper
            // way there is not worth taking it again).
            if (g.isClosed(k)) continue
            g.close(k)
            val gk = g.cost(k)
            if (++expanded % 4096 == 0 && Thread.interrupted()) throw InterruptedException()
            if (expanded > MAX_EXPANDED) throw RouteException("Die Route ist zu lang für die Uhr")
            if (k == u2 && toGoalFromU != null && gk + toGoalFromU < best) {
                best = gk + toGoalFromU
                bestEnd = u2
            }
            if (k == v2 && toGoalFromV != null && gk + toGoalFromV < best) {
                best = gk + toGoalFromV
                bestEnd = v2
            }
            for (b in blocksAt(k)) {
                val v = b.vertex(k)
                if (v == LongIntMap.MISSING) continue
                for (e in b.edgesFrom(v)) {
                    val f = profile.factor(b.kind[e].toInt(), b.wayFlags(e), b.forward(e)) ?: continue
                    val nk = b.keys[b.to[e]]
                    if (g.isClosed(nk)) continue
                    val ng = (gk + b.length[e] * f).toFloat().toDouble()
                    if (ng < g.cost(nk)) {
                        g.put(nk, ng, k)
                        heap.push(ng + h(nk), nk)
                    }
                }
            }
        }
        lastExpanded = expanded
        lastReached = g.size
        if (best == Double.POSITIVE_INFINITY) throw RouteException("Keine Route gefunden: Start und Ziel sind nicht verbunden")
        // Back from the end over the nodes to the seed. Only the nodes are kept during the search (so the road
        // blocks it passed may go); the edges between them are looked up again, the cheapest as the search took it.
        val path = ArrayList<Long>()
        var k = bestEnd
        var seed: Long
        while (true) {
            path += k
            val link = g.link(k)
            if (link == SEED_U || link == SEED_V) {
                seed = link
                break
            }
            k = link
        }
        path.reverse()
        val builder = RouteBuilder(profile)
        // The start point and the piece of its stretch.
        builder.point(start.lat, start.lon)
        if (seed == SEED_U) {
            for (j in start.seg downTo 0) builder.point(sb.lat(se, j), sb.lon(se, j))
        } else {
            for (j in start.seg + 1 until sb.points(se)) builder.point(sb.lat(se, j), sb.lon(se, j))
        }
        builder.segment(sb.name(se), sKind)
        for (i in 0 until path.size - 1) {
            val (b, e) = edgeBetween(path[i], path[i + 1], profile) ?: throw RouteException("Die Route ließ sich nicht zusammensetzen")
            builder.markJunction(degreeAt(path[i]))
            for (j in 1 until b.points(e)) builder.point(b.lat(e, j), b.lon(e, j))
            builder.segment(b.name(e), b.kind[e].toInt())
        }
        builder.markJunction(degreeAt(bestEnd))
        if (bestEnd == u2) {
            for (j in 1..goal.seg) builder.point(gb.lat(ge, j), gb.lon(ge, j))
        } else {
            for (j in gb.points(ge) - 2 downTo goal.seg + 1) builder.point(gb.lat(ge, j), gb.lon(ge, j))
        }
        builder.point(goal.lat, goal.lon)
        builder.segment(gb.name(ge), gKind)
        return builder.build()
    }

    /** The cheapest edge [profile] may take from node [from] to node [to], with its road block. */
    private fun edgeBetween(from: Long, to: Long, profile: Profile): Pair<RoadBlock, Int>? {
        var best: Pair<RoadBlock, Int>? = null
        var bestCost = Double.MAX_VALUE
        for (b in blocksAt(from)) {
            val v = b.vertex(from)
            if (v == LongIntMap.MISSING) continue
            for (e in b.edgesFrom(v)) {
                if (b.keys[b.to[e]] != to) continue
                val f = profile.factor(b.kind[e].toInt(), b.wayFlags(e), b.forward(e)) ?: continue
                val c = b.length[e] * f
                if (c < bestCost) {
                    bestCost = c
                    best = b to e
                }
            }
        }
        return best
    }

    /** A route that stays on the start's stretch. */
    private fun straight(start: Snap, goal: Snap, profile: Profile): Route {
        val b = start.block
        val e = start.edge
        val builder = RouteBuilder(profile)
        builder.point(start.lat, start.lon)
        if (goal.along >= start.along) {
            for (j in start.seg + 1..goal.seg) builder.point(b.lat(e, j), b.lon(e, j))
        } else {
            for (j in start.seg downTo goal.seg + 1) builder.point(b.lat(e, j), b.lon(e, j))
        }
        builder.point(goal.lat, goal.lon)
        builder.segment(b.name(e), b.kind[e].toInt())
        return builder.build()
    }

    /**
     * The nearest point to [p] on a way [profile] may take, at most [maxM] away, on a way that belongs to the
     * road network: a piece the map shows on its own (a platform, the shops under a station) is skipped for
     * the next nearest way. Tunnels and ways below ground count [TUNNEL_PENALTY_M] farther than they are.
     */
    fun snap(p: LatLon, profile: Profile, maxM: Double = SNAP_M): Snap? {
        val frame = LocalFrame(p)
        val best = HashMap<Long, Snap>()
        for (b in blocksNear(p.latE6, p.lonE6, maxM)) {
            for (v in 0 until b.vertexCount) for (e in b.edgesFrom(v)) {
                if (!b.forward(e)) continue
                val kind = b.kind[e].toInt()
                val flags = b.wayFlags(e)
                if (profile.factor(kind, flags, true) == null && profile.factor(kind, flags, false) == null) continue
                val penalty = if (flags and (Flag.TUNNEL or Flag.UNDERGROUND) != 0) TUNNEL_PENALTY_M else 0.0
                var along = 0.0
                var ax = frame.x(b.lon(e, 0) / 1e6)
                var ay = frame.y(b.lat(e, 0) / 1e6)
                var hitBest: Snap? = null
                for (j in 0 until b.points(e) - 1) {
                    val bx = frame.x(b.lon(e, j + 1) / 1e6)
                    val by = frame.y(b.lat(e, j + 1) / 1e6)
                    val segLen = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                    if (min(ax, bx) - maxM <= 0 && max(ax, bx) + maxM >= 0 && min(ay, by) - maxM <= 0 && max(ay, by) + maxM >= 0) {
                        val hit = nearestOnSegment(0.0, 0.0, ax, ay, bx, by)
                        val d = hit.distance + penalty
                        if (hit.distance <= maxM && (hitBest == null || d < hitBest.distance)) {
                            val lat = frame.lat(ay + (by - ay) * hit.t)
                            val lon = frame.lon(ax + (bx - ax) * hit.t)
                            hitBest = Snap(b, e, j, hit.t, along + segLen * hit.t, (lat * 1e6).toInt(), (lon * 1e6).toInt(), d)
                        }
                    }
                    along += segLen
                    ax = bx
                    ay = by
                }
                // One candidate per stretch of way: the same stretch can lie in two blocks.
                hitBest?.let { h ->
                    val id = RoadBlock.key(b.lat(e, 0), b.lon(e, 0)) * 31 + b.keys[b.to[e]]
                    if ((best[id]?.distance ?: Double.MAX_VALUE) > h.distance) best[id] = h
                }
            }
        }
        val candidates = best.values.sortedBy { it.distance }
        // The nearest stretch that leads into the network; pieces found apart are not tried twice.
        val apart = HashSet<Long>()
        for (c in candidates) {
            val a = RoadBlock.key(c.block.lat(c.edge, 0), c.block.lon(c.edge, 0))
            if (a in apart || c.block.keys[c.block.to[c.edge]] in apart) continue
            val piece = pieceApart(c, profile) ?: return rescale(c)
            apart += piece
        }
        return candidates.firstOrNull()?.let(::rescale)
    }

    /** The edge's length is the sum of its pieces; keep the snapped position on that scale. */
    private fun rescale(s: Snap): Snap {
        val total = s.block.length[s.edge].toDouble()
        var measured = 0.0
        for (j in 0 until s.block.points(s.edge) - 1) {
            measured += Geo.distanceE6(s.block.lat(s.edge, j), s.block.lon(s.edge, j), s.block.lat(s.edge, j + 1), s.block.lon(s.edge, j + 1))
        }
        val along = if (measured > 0) s.along * total / measured else 0.0
        return Snap(s.block, s.edge, s.seg, s.t, along.coerceIn(0.0, total), s.lat, s.lon, s.distance)
    }

    /**
     * Null when the stretch of [s] leads into the road network ([NETWORK_NODES] nodes, or [NETWORK_M] away);
     * otherwise the nodes of the piece it belongs to.
     */
    private fun pieceApart(s: Snap, profile: Profile): Set<Long>? {
        val seen = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        val a = RoadBlock.key(s.block.lat(s.edge, 0), s.block.lon(s.edge, 0))
        val b = s.block.keys[s.block.to[s.edge]]
        seen += a
        seen += b
        queue += a
        queue += b
        while (queue.isNotEmpty()) {
            if (seen.size >= NETWORK_NODES) return null
            val k = queue.removeFirst()
            if (Geo.distanceE6(RoadBlock.latOf(k), RoadBlock.lonOf(k), s.lat, s.lon) > NETWORK_M) return null
            for (block in blocksAt(k)) {
                val v = block.vertex(k)
                if (v == LongIntMap.MISSING) continue
                for (e in block.edgesFrom(v)) {
                    val kind = block.kind[e].toInt()
                    val flags = block.wayFlags(e)
                    if (profile.factor(kind, flags, true) == null && profile.factor(kind, flags, false) == null) continue
                    val nk = block.keys[block.to[e]]
                    if (seen.add(nk)) queue += nk
                }
            }
        }
        return seen
    }

    /** How many ways meet at node [key]: different neighbours over all blocks that hold it. */
    private fun degreeAt(key: Long): Int {
        val seen = HashSet<Long>()
        for (b in blocksAt(key)) {
            val v = b.vertex(key)
            if (v == LongIntMap.MISSING) continue
            for (e in b.edgesFrom(v)) seen += b.keys[b.to[e]]
        }
        return seen.size
    }

    /** The road blocks that may hold node [key]: its own block in every map, and neighbours when it lies near their edge. */
    private fun blocksAt(key: Long): List<RoadBlock> = blocksNear(RoadBlock.latOf(key), RoadBlock.lonOf(key), BORDER_M)

    private fun blocksNear(lat: Int, lon: Int, radiusM: Double): List<RoadBlock> {
        val out = ArrayList<RoadBlock>(2)
        val dLat = (radiusM / Geo.M_PER_DEG * 1e6).toInt()
        val dLon = (dLat / max(0.2, cos(Math.toRadians(lat / 1e6)))).toInt()
        for ((i, src) in sources.withIndex()) {
            val box = src.box
            if (lat < box.minLat - dLat || lat > box.maxLat + dLat || lon < box.minLon - dLon || lon > box.maxLon + dLon) continue
            val sub = src.map.subFileFor(ROUTE_ZOOM)
            val z = sub.baseZoom
            val x0 = Geo.tileX((lon - dLon) / 1e6, z)
            val x1 = Geo.tileX((lon + dLon) / 1e6, z)
            val y0 = Geo.tileY((lat + dLat) / 1e6, z)
            val y1 = Geo.tileY((lat - dLat) / 1e6, z)
            for (y in y0..y1) for (x in x0..x1) block(i, src, sub, x, y)?.let { out += it }
        }
        return out
    }

    private fun block(i: Int, src: MapSource, sub: SubFile, x: Int, y: Int): RoadBlock? {
        val key = (i.toLong() shl 56) or (x.toLong() shl 28) or y.toLong()
        synchronized(cache) { cache[key]?.let { return it } }
        val mapBlock = src.map.block(sub, x, y, sub.maxZoom) ?: return null
        val bounds = MapRenderer.blockBox(x, y, sub.baseZoom)
        val margin = ((bounds.maxLat - bounds.minLat) * BORDER_SHARE).toInt()
        val road = RoadBlock.build(mapBlock, src.tags, bounds, margin)
        synchronized(cache) {
            blocksBuilt++
            cache[key]?.let { return it }
            cache[key] = road
            cachedBytes += road.bytes
            val it = cache.entries.iterator()
            while (cachedBytes > budgetBytes && cache.size > MIN_CACHED && it.hasNext()) {
                val old = it.next()
                cachedBytes -= old.value.bytes
                it.remove()
            }
        }
        return road
    }

    companion object {
        /** The zoom whose blocks hold every way (zoom 14 on the mapsforge server's maps). */
        const val ROUTE_ZOOM = 21

        /** How far start and destination may lie from a way. */
        const val SNAP_M = 300.0

        /** Points this close to a block's edge may be in the neighbour too. */
        const val BORDER_M = 60.0

        /** Share of a block's height near its edge whose points stay nodes. */
        const val BORDER_SHARE = 0.04

        /** Snapped ways that must lead into the network: this many nodes, or this far. */
        const val NETWORK_NODES = 4_000
        const val NETWORK_M = 1_500.0

        /** Tunnels and ways below ground count this much farther when snapping: a street beats the passage beneath it. */
        const val TUNNEL_PENALTY_M = 25.0

        /** A search ends after this many nodes: its queue and costs then take some 20 MB. */
        const val MAX_EXPANDED = 400_000

        /** Road blocks kept for the next search. */
        const val BUDGET_BYTES = 16L shl 20

        /** Routes longer than this (straight) head for the goal, fully from this much more on. */
        const val FAR_FROM_M = 3_000.0
        const val FAR_SPAN_M = 30_000.0
        const val MIN_CACHED = 4
        private const val SEED_U = -2L
        private const val SEED_V = -3L
    }
}
