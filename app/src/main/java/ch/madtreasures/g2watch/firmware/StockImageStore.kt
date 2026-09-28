package ch.madtreasures.g2watch.firmware

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Where the watch gets Even's stock image. */
fun interface StockImageSource {
    /**
     * Even's stock image, verified against [FirmwareCatalog.STOCK_SHA256].
     * Throws [FirmwareBuildException] with a message for the wearer when it is not available.
     */
    fun load(onProgress: (done: Long, total: Long) -> Unit): ByteArray
}

/**
 * Even's stock image on the watch, in this order:
 *
 * 1. the verified copy from an earlier run ([cacheDir]);
 * 2. a file the wearer put into [importDir], e.g. `adb push g2_2.3.0.24.bin
 *    /sdcard/Android/data/ch.madtreasures.g2watch/files/firmware/` (any name ending in `.bin`),
 *    for a watch without internet;
 * 3. a download from Even's CDN ([FirmwareCatalog.STOCK_URL]).
 *
 * Every candidate must have the exact size and SHA-256 of the catalog; anything else is ignored
 * (import) or rejected (download). Only verified bytes are ever written to the cache, and
 * atomically. Even's firmware stays on the watch and is never part of this app.
 */
class StockImageStore(
    private val cacheDir: File,
    private val importDir: File?,
    private val fetch: (url: String, maxBytes: Int, onProgress: (Long, Long) -> Unit) -> ByteArray = ::httpGet,
    private val log: (String) -> Unit = {},
    /** The one image this store accepts; tests use a small synthetic one. */
    private val expected: Expected = Expected(),
) : StockImageSource {

    data class Expected(
        val url: String = FirmwareCatalog.STOCK_URL,
        val size: Int = FirmwareCatalog.STOCK_SIZE,
        val sha256: String = FirmwareCatalog.STOCK_SHA256,
        val fileName: String = FirmwareCatalog.fileName(FirmwareKind.Stock),
    )

    private val cached = File(cacheDir, expected.fileName)

    override fun load(onProgress: (done: Long, total: Long) -> Unit): ByteArray {
        verifiedOrNull(cached)?.let {
            log("stock image from the watch's cache")
            return it
        }
        if (cached.exists() && !cached.delete()) log("could not delete a damaged cached image")

        importDir?.listFiles { f -> f.isFile && f.name.endsWith(".bin", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.forEach { candidate ->
                val bytes = verifiedOrNull(candidate)
                if (bytes != null) {
                    log("stock image imported from ${candidate.name}")
                    save(bytes)
                    return bytes
                }
                log("ignored ${candidate.name}: not Even's firmware ${FirmwareCatalog.STOCK_VERSION}")
            }

        val bytes = try {
            fetch(expected.url, expected.size, onProgress)
        } catch (e: IOException) {
            throw FirmwareBuildException(
                "Die Original-Firmware ließ sich nicht laden (${e.message}). Die Uhr braucht dafür WLAN " +
                    "oder die Internetverbindung des Handys. Nichts wurde an der Brille verändert.",
            )
        }
        val hash = Digests.sha256(bytes)
        if (bytes.size != expected.size || hash != expected.sha256) {
            throw FirmwareBuildException(
                "Die geladene Datei ist nicht Evens Firmware ${FirmwareCatalog.STOCK_VERSION} " +
                    "(${bytes.size} Bytes, SHA-256 ${hash.take(12)}…). Nichts wurde an der Brille verändert.",
            )
        }
        log("stock image downloaded and verified")
        save(bytes)
        return bytes
    }

    /** Deletes the cached copy, e.g. to force a fresh download. */
    fun clear() {
        cached.delete()
    }

    private fun verifiedOrNull(file: File): ByteArray? {
        if (!file.isFile || file.length() != expected.size.toLong()) return null
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return null
        }
        return bytes.takeIf { Digests.sha256(it) == expected.sha256 }
    }

    private fun save(bytes: ByteArray) {
        try {
            cacheDir.mkdirs()
            val part = File(cacheDir, cached.name + ".part")
            part.writeBytes(bytes)
            if (!part.renameTo(cached)) {
                part.delete()
                log("could not cache the stock image")
            }
        } catch (e: IOException) {
            // Only a cache: the next run downloads again.
            log("could not cache the stock image: ${e.message}")
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Plain HTTPS GET with a hard size cap; [maxBytes] is the exact size expected. */
        fun httpGet(url: String, maxBytes: Int, onProgress: (Long, Long) -> Unit): ByteArray {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "G2Watch")
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                val declared = connection.contentLengthLong
                if (declared > maxBytes) throw IOException("unexpected size $declared")
                return connection.inputStream.use { readCapped(it, maxBytes, declared.takeIf { it > 0 } ?: maxBytes.toLong(), onProgress) }
            } finally {
                connection.disconnect()
            }
        }

        internal fun readCapped(input: InputStream, maxBytes: Int, total: Long, onProgress: (Long, Long) -> Unit): ByteArray {
            val out = ByteArray(maxBytes)
            var size = 0
            val probe = ByteArray(1)
            while (true) {
                if (size == maxBytes) {
                    // One more byte means the file is larger than any image we accept.
                    if (input.read(probe) >= 0) throw IOException("file larger than $maxBytes bytes")
                    break
                }
                val n = input.read(out, size, maxBytes - size)
                if (n < 0) break
                size += n
                onProgress(size.toLong(), total)
            }
            return if (size == maxBytes) out else out.copyOf(size)
        }
    }
}
