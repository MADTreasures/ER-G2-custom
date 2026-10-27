package ch.madtreasures.navi

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a search index build or a route in a JVM of its own with a small heap, as a watch has (HeapTest):
 *
 *     HeapProbe index <map> <index file>
 *     HeapProbe route <profile> <lat> <lon> <lat> <lon> <map>…
 */
object HeapProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val t0 = System.nanoTime()
        when (args[0]) {
            "index" -> MapFile(File(args[1])).use { SearchIndexBuilder.build(it, File(args[2])) }
            "route" -> {
                val maps = args.drop(6).map { MapSource(File(it)) }
                val router = Router(maps, headForGoal = System.getProperty("exact") == null)
                val route = router.route(LatLon(args[2].toDouble(), args[3].toDouble()), LatLon(args[4].toDouble(), args[5].toDouble()), Profile.valueOf(args[1]))
                println("route ${route.length.toInt()} m, ${route.size} points; expanded ${router.lastExpanded}, reached ${router.lastReached}, blocks built ${router.blocksBuilt}")
                maps.forEach { it.close() }
            }
        }
        val heap = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }.sumOf { it.peakUsage.used }
        println("done in ${(System.nanoTime() - t0) / 1_000_000} ms, peak heap ${heap shr 20} MB")
    }

    /** Runs [args] with at most [heapMb] of heap; the exit code and what it printed. */
    fun run(heapMb: Int, vararg args: String, jvm: List<String> = emptyList()): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(listOf(java, "-Xmx${heapMb}m") + jvm + listOf("-cp", System.getProperty("java.class.path"), HeapProbe::class.java.name) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.MINUTES)) process.destroyForcibly()
        return process.exitValue() to output
    }
}
