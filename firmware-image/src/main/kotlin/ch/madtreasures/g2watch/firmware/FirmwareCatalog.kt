package ch.madtreasures.g2watch.firmware

/** What can be installed on the glasses. */
enum class FirmwareKind {
    /** Even Realities' unmodified firmware (removes the custom firmware). */
    Stock,

    /** Faceclaw's custom firmware: the stock image plus g2flash's patch set. */
    Custom,
}

/**
 * The firmware images this app will ever flash, pinned by SHA-256.
 *
 * Even's firmware is never shipped with the app: the stock image is downloaded from Even's CDN
 * (or imported by the wearer) and the custom image is produced on the watch by applying the
 * bundled patch set (`/firmware/cfw_patches.json`, from g2flash, GPLv3) to it. Both ends of the
 * patch are pinned, so the result is either the reviewed image bit for bit or nothing.
 *
 * The custom revision must match the Faceclaw core vendored in `:faceclaw-core`: the core speaks
 * exactly one firmware contract (see faceclaw-core/UPSTREAM.md).
 */
object FirmwareCatalog {
    const val STOCK_VERSION = "2.3.0.24"
    const val STOCK_URL = "https://cdn.evenreal.co/firmware/1dbdf37b03a1169c384945e94d671371.bin"

    /** The CDN names files by MD5; it doubles as a quick check of a download. */
    const val STOCK_MD5 = "1dbdf37b03a1169c384945e94d671371"
    const val STOCK_SHA256 = "187ccf2bcc5c17a212106e8a376745511e8289c4232b634a7ea94b9bf25a0979"
    const val STOCK_SIZE = 4_537_963

    /** Revision the custom firmware reports in settings field 100 ("Faceclaw/<n>"). */
    const val CUSTOM_REVISION = 35
    const val CUSTOM_EXTENSION = "Faceclaw/$CUSTOM_REVISION"
    const val CUSTOM_SHA256 = "d7971b68add0c5187fec81d1cb1ef8cc12322db426a98cb445f1b3a274272817"
    const val CUSTOM_SIZE = 4_609_823

    /** Where the bundled patch set comes from (g2flash `patches/cfw_patches.json`). */
    const val PATCH_SET_ORIGIN = "g2flash 9079f994760d7b8f91eab1a0e8c4a8ebca9fd753"
    const val PATCH_SET_RESOURCE = "/firmware/cfw_patches.json"

    val patchSet: PatchSet by lazy {
        val p = PatchSet.fromResource(PATCH_SET_RESOURCE)
        check(p.baseSha256 == STOCK_SHA256 && p.outputSha256 == CUSTOM_SHA256) { "bundled patch set does not match the catalog" }
        p
    }

    /** The allow-list: which firmware [sha256] is, or null when it must never be flashed. */
    fun kindOf(sha256: String): FirmwareKind? = when (sha256.lowercase()) {
        STOCK_SHA256 -> FirmwareKind.Stock
        CUSTOM_SHA256 -> FirmwareKind.Custom
        else -> null
    }

    fun fileName(kind: FirmwareKind): String = when (kind) {
        FirmwareKind.Stock -> "g2_$STOCK_VERSION.bin"
        FirmwareKind.Custom -> "g2_${STOCK_VERSION}_cfw.bin"
    }

    /** A few words for the watch: what [kind] puts on the glasses. */
    fun describe(kind: FirmwareKind): String = when (kind) {
        FirmwareKind.Stock -> "Even Realities $STOCK_VERSION"
        FirmwareKind.Custom -> "$CUSTOM_EXTENSION · Basis $STOCK_VERSION"
    }

    /** The exact size of the image for [kind], known before it is built. */
    fun sizeOf(kind: FirmwareKind): Int = when (kind) {
        FirmwareKind.Stock -> STOCK_SIZE
        FirmwareKind.Custom -> CUSTOM_SIZE
    }

    /**
     * Turns the verified stock image into the image for [kind]: the stock image itself, or the
     * patched custom image. The result is fully validated and on the allow-list.
     */
    fun prepare(kind: FirmwareKind, stock: ByteArray, onPatch: (applied: Int, total: Int) -> Unit = { _, _ -> }): EvenOtaImage {
        val stockHash = Digests.sha256(stock)
        if (stockHash != STOCK_SHA256) {
            throw FirmwareBuildException("The stock firmware failed verification.\nexpected $STOCK_SHA256\ngot      $stockHash")
        }
        val bytes = when (kind) {
            FirmwareKind.Stock -> stock
            FirmwareKind.Custom -> patchSet.apply(stock, onPatch)
        }
        val image = EvenOtaImage.parse(bytes)
        if (kindOf(image.sha256) != kind) throw FirmwareBuildException("The prepared image is not the expected firmware")
        return image
    }
}
