package ch.madtreasures.g2watch.glasses

import ch.madtreasures.g2watch.firmware.FirmwareCatalog

/** What the firmware-extension slot of the glasses' settings response says about the firmware. */
sealed interface FirmwareExtension {
    /** Nothing in the slot: Even Realities' stock firmware. */
    data object Stock : FirmwareExtension

    /** Faceclaw's custom firmware ("Faceclaw/<revision>"). */
    data class Faceclaw(val revision: Int) : FirmwareExtension

    /** Faceclaw firmware from before revisions existed ("EVENCFW…"). */
    data class LegacyFaceclaw(val text: String) : FirmwareExtension

    /** Custom firmware from some other project. */
    data class Other(val text: String) : FirmwareExtension
}

enum class FirmwareKind {
    /** Faceclaw firmware at exactly the required revision: the app may start a session. */
    COMPATIBLE,
    OLDER_FACECLAW,

    /** Faceclaw firmware at a later revision than the vendored core speaks. */
    NEWER_FACECLAW,
    OTHER_CUSTOM,
    STOCK,

    /** The glasses reported neither versions nor an extension. */
    UNKNOWN,
}

data class FirmwareVerdict(
    val kind: FirmwareKind,
    val extension: FirmwareExtension,
    val leftVersion: String,
    val rightVersion: String,
    /** A few words for the desktop's info window, e.g. "Faceclaw/35 · Basis 2.3.0.24". */
    val summary: String,
    /** Why the app will not start a session, in German; null when it will. */
    val message: String?,
) {
    val compatible: Boolean get() = kind == FirmwareKind.COMPATIBLE
}

/**
 * Which glasses firmware this app can drive: a port of Faceclaw's app/g2/firmware-compat.ts.
 *
 * The firmware names itself in the "firmware extension" slot of the settings response
 * (protobuf field 100): "Faceclaw/<n>" is Faceclaw's custom firmware at revision n, "EVENCFW…"
 * an older Faceclaw build, any other text custom firmware from somewhere else, and an empty
 * slot the stock firmware.
 *
 * Faceclaw's session core speaks the contract of exactly one firmware revision: revision 35, for
 * example, changed how draw calls encode coordinates, so a core for 34 cannot draw on 35 and the
 * other way round. Like Faceclaw 0.8.0 itself, the app therefore starts a session only after the
 * glasses positively reported exactly that revision. Anything else, including a response without
 * any data, stops at the check.
 */
object FirmwareRequirement {
    /**
     * The revision the vendored Faceclaw core speaks (faceclaw-core/UPSTREAM.md). It is the
     * revision of the custom image the watch installs, so both always move together.
     */
    const val REQUIRED_REVISION = FirmwareCatalog.CUSTOM_REVISION

    /** The stock release the custom image is built from; newer stock is a downgrade nobody tested. */
    const val BASE_STOCK_VERSION = FirmwareCatalog.STOCK_VERSION

    private const val FACECLAW_PREFIX = "Faceclaw/"
    private const val LEGACY_PREFIX = "EVENCFW"

    fun parseExtension(extension: String?): FirmwareExtension {
        val text = extension?.trim().orEmpty()
        if (text.isEmpty()) return FirmwareExtension.Stock
        if (text.startsWith(FACECLAW_PREFIX)) {
            // Like JavaScript's parseInt: leading digits count, anything after them is ignored.
            val digits = text.substring(FACECLAW_PREFIX.length).trimStart().removePrefix("+").takeWhile { it.isDigit() }
            val revision = digits.toIntOrNull()
            return if (revision != null) FirmwareExtension.Faceclaw(revision) else FirmwareExtension.Other(text)
        }
        if (text.startsWith(LEGACY_PREFIX)) return FirmwareExtension.LegacyFaceclaw(text)
        return FirmwareExtension.Other(text)
    }

    /** The higher of the two arms' dotted versions, or "" if neither reported one. */
    fun reportedVersion(left: String?, right: String?): String {
        val versions = listOfNotNull(left?.trim(), right?.trim()).filter { it.isNotEmpty() }
        return versions.maxWithOrNull { a, b -> compareVersions(parseDotted(a), parseDotted(b)) } ?: ""
    }

    fun check(left: String?, right: String?, extension: String?): FirmwareVerdict {
        val l = left?.trim().orEmpty()
        val r = right?.trim().orEmpty()
        val ext = parseExtension(extension)
        val version = reportedVersion(l, r)
        val versions = "L=${l.ifEmpty { "?" }} R=${r.ifEmpty { "?" }}"
        val base = if (version.isEmpty()) "" else " · Basis $version"
        val required = "Faceclaw-Firmware Revision $REQUIRED_REVISION"

        fun verdict(kind: FirmwareKind, summary: String, message: String?) =
            FirmwareVerdict(kind, ext, l, r, summary, message)

        return when (ext) {
            is FirmwareExtension.Faceclaw -> when {
                ext.revision == REQUIRED_REVISION -> verdict(FirmwareKind.COMPATIBLE, "Faceclaw/${ext.revision}$base", null)
                ext.revision < REQUIRED_REVISION -> verdict(
                    FirmwareKind.OLDER_FACECLAW,
                    "Faceclaw/${ext.revision} (zu alt)$base",
                    "Die Brille hat Faceclaw-Firmware Revision ${ext.revision} ($versions). " +
                        "Diese App braucht $required.",
                )
                else -> verdict(
                    FirmwareKind.NEWER_FACECLAW,
                    "Faceclaw/${ext.revision} (neuer)$base",
                    "Die Brille hat Faceclaw-Firmware Revision ${ext.revision} ($versions). " +
                        "Diese App spricht genau $required: die Custom-Firmware dieser App aufspielen oder die App aktualisieren.",
                )
            }
            is FirmwareExtension.LegacyFaceclaw -> verdict(
                FirmwareKind.OLDER_FACECLAW,
                "alte Faceclaw-Firmware$base",
                "Die Brille hat eine ältere Faceclaw-Firmware (meldet „${ext.text}“, $versions). " +
                    "Diese App braucht $required.",
            )
            is FirmwareExtension.Other -> verdict(
                FirmwareKind.OTHER_CUSTOM,
                "fremde Firmware$base",
                "Die Brille hat eine eigene Firmware aus einer anderen Quelle (meldet „${ext.text}“, $versions). " +
                    "Diese App braucht $required.",
            )
            FirmwareExtension.Stock ->
                if (version.isEmpty()) {
                    verdict(FirmwareKind.UNKNOWN, "unbekannt", "Die Brille hat keine Firmware-Version gemeldet.")
                } else {
                    verdict(
                        FirmwareKind.STOCK,
                        "Original $version",
                        "Die Brille hat die Original-Firmware ($versions). Diese App braucht $required.",
                    )
                }
        }
    }

    /** True when [version] is a dotted version above [BASE_STOCK_VERSION]. */
    fun isNewerThanBase(version: String): Boolean =
        version.isNotBlank() && compareVersions(parseDotted(version), parseDotted(BASE_STOCK_VERSION)) > 0

    private fun parseDotted(version: String): List<Int> =
        version.trim().split(".").map { part -> part.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** Component-wise; missing components count as 0. */
    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val delta = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (delta != 0) return delta
        }
        return 0
    }
}
