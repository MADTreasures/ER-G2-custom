package ch.madtreasures.navi

import java.io.File

/** Test data of the Navi package (src/test/fixtures, README). Unit tests run in the app module's folder. */
object Fixtures {
    private val dir = File("../packages/navi/src/test/fixtures")

    /**
     * Two blocks of zoom 14 around Vaduz (47.130–47.160 N, 9.514–9.536 E), cut byte for byte out of
     * download.mapsforge.org's liechtenstein.map of 2026-09-11 with tools/cut_map.py. Map data © OpenStreetMap
     * contributors (ODbL).
     */
    val vaduz = File(dir, "vaduz.map")

    /** The whole maps in the folder of -PnaviData, if given (e.g. switzerland.map); otherwise none. */
    fun realMaps(): List<File> =
        System.getProperty("naviData")?.let { File(it).listFiles { f -> f.name.endsWith(".map") }?.sortedBy { it.length() } }.orEmpty()

    /** A real map of that folder by name, or null. */
    fun realMap(name: String): File? = System.getProperty("naviData")?.let { File(it, name) }?.takeIf { it.isFile }
}
