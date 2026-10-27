package ch.madtreasures.navi

/** Long → Int without boxing (open addressing); [MISSING] for absent keys. For the road graph and the search. */
class LongIntMap(expected: Int = 16) {
    private var keys: LongArray
    private var values: IntArray
    private var used: BooleanArray
    var size = 0
        private set

    init {
        var cap = 16
        while (cap < expected * 2) cap = cap shl 1
        keys = LongArray(cap)
        values = IntArray(cap)
        used = BooleanArray(cap)
    }

    operator fun get(key: Long): Int {
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return MISSING
    }

    operator fun set(key: Long, value: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) {
                values[i] = value
                return
            }
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = key
        values[i] = value
        size++
    }

    /** The value of [key], adding [value] first if it is not there. */
    fun getOrPut(key: Long, value: Int): Int {
        val v = get(key)
        if (v != MISSING) return v
        set(key, value)
        return value
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val oldUsed = used
        keys = LongArray(oldKeys.size * 2)
        values = IntArray(oldKeys.size * 2)
        used = BooleanArray(oldKeys.size * 2)
        size = 0
        for (i in oldKeys.indices) if (oldUsed[i]) set(oldKeys[i], oldValues[i])
    }

    /** Bytes this map takes, about. */
    val bytes: Long get() = keys.size * 13L

    companion object {
        const val MISSING = Int.MIN_VALUE

        fun mix(key: Long): Int {
            var h = key * -0x61c8864680b583ebL
            h = h xor (h ushr 29)
            return h.toInt()
        }
    }
}

/**
 * Long → cost and Long → Long side by side (open addressing), for the costs and ways back of a search, and
 * whether a node is done ([close]). Costs are floats and a free slot is marked by its key, so an entry takes
 * some 28 bytes: a watch has little memory.
 */
class LongCostMap(expected: Int = 1024) {
    private var keys: LongArray
    private var costs: FloatArray
    private var links: LongArray
    private var closed: BooleanArray
    var size = 0
        private set

    init {
        var cap = 16
        while (cap * 3 < expected * 4) cap = cap shl 1
        keys = LongArray(cap) { FREE }
        costs = FloatArray(cap)
        links = LongArray(cap)
        closed = BooleanArray(cap)
    }

    private fun slot(key: Long): Int {
        val mask = keys.size - 1
        var i = LongIntMap.mix(key) and mask
        while (keys[i] != FREE && keys[i] != key) i = (i + 1) and mask
        return i
    }

    fun has(key: Long): Boolean = keys[slot(key)] == key

    /** The cost of [key], or [Double.POSITIVE_INFINITY]. */
    fun cost(key: Long): Double {
        val i = slot(key)
        return if (keys[i] == key) costs[i].toDouble() else Double.POSITIVE_INFINITY
    }

    fun link(key: Long): Long {
        val i = slot(key)
        return if (keys[i] == key) links[i] else NO_LINK
    }

    /** Marks [key] (which must be there) as done: the search took it and will not take it again. */
    fun close(key: Long) {
        val i = slot(key)
        if (keys[i] == key) closed[i] = true
    }

    fun isClosed(key: Long): Boolean {
        val i = slot(key)
        return keys[i] == key && closed[i]
    }

    /** Sets [key]'s cost (kept as a float) and link. */
    fun put(key: Long, cost: Double, link: Long) {
        require(key != FREE)
        if ((size + 1) * 4 > keys.size * 3) grow()
        val i = slot(key)
        if (keys[i] != key) {
            keys[i] = key
            size++
        }
        costs[i] = cost.toFloat()
        links[i] = link
    }

    private fun grow() {
        val k = keys
        val c = costs
        val l = links
        val d = closed
        keys = LongArray(k.size * 2) { FREE }
        costs = FloatArray(k.size * 2)
        links = LongArray(k.size * 2)
        closed = BooleanArray(k.size * 2)
        size = 0
        for (i in k.indices) if (k[i] != FREE) {
            put(k[i], c[i].toDouble(), l[i])
            if (d[i]) close(k[i])
        }
    }

    companion object {
        const val NO_LINK = Long.MIN_VALUE

        /** The key of a free slot; no node has it (its latitude would be below −2147°). */
        private const val FREE = Long.MIN_VALUE
    }
}

/** A binary min-heap of (priority, key) pairs, priorities as floats; stale entries stay and are skipped by the caller. */
class MinHeap(capacity: Int = 1024) {
    private var prio = FloatArray(capacity)
    private var keys = LongArray(capacity)
    var size = 0
        private set

    fun isEmpty() = size == 0

    fun push(priority: Double, key: Long) = push(priority.toFloat(), key)

    private fun push(priority: Float, key: Long) {
        if (size == prio.size) {
            prio = prio.copyOf(size + (size shr 1))
            keys = keys.copyOf(size + (size shr 1))
        }
        var i = size++
        while (i > 0) {
            val p = (i - 1) / 2
            if (prio[p] <= priority) break
            prio[i] = prio[p]
            keys[i] = keys[p]
            i = p
        }
        prio[i] = priority
        keys[i] = key
    }

    /** The smallest priority (only valid while not empty). */
    fun peekPriority(): Double = prio[0].toDouble()

    /** Removes the entry with the smallest priority and returns its key. */
    fun pop(): Long {
        val top = keys[0]
        size--
        if (size > 0) {
            val lastP = prio[size]
            val lastK = keys[size]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && prio[c + 1] < prio[c]) c++
                if (prio[c] >= lastP) break
                prio[i] = prio[c]
                keys[i] = keys[c]
                i = c
            }
            prio[i] = lastP
            keys[i] = lastK
        }
        return top
    }
}

/** A growing list of ints. */
class IntList(capacity: Int = 64) {
    var data = IntArray(capacity)
        private set
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]

    fun toArray(): IntArray = data.copyOf(size)
}

/** A growing list of floats. */
class FloatList(capacity: Int = 64) {
    var data = FloatArray(capacity)
        private set
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
