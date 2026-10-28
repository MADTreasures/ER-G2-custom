package ch.madtreasures.g2watch.apps.media

import ch.madtreasures.g2watch.desktop.GrayRaster
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Grey pictures as small PNGs for image blocks (8-bit grey, rounded to the 16 levels the glasses show, so
 * they pack well and come back exactly), e.g. a song's cover for [ch.madtreasures.g2watch.apps.MediaInfo.artwork].
 */
internal object GrayPng {
    fun encode(r: GrayRaster): ByteArray {
        val w = r.width
        val h = r.height
        val raw = ByteArray((w + 1) * h)
        var o = 0
        for (y in 0 until h) {
            raw[o++] = 0 // no filter
            for (x in 0 until w) raw[o++] = level(r.pixels[y * w + x]).toByte()
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val zipped = ByteArrayOutputStream()
        try {
            deflater.setInput(raw)
            deflater.finish()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) zipped.write(buffer, 0, deflater.deflate(buffer))
        } finally {
            deflater.end()
        }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(w)
            writeInt(h)
            writeByte(8) // bits per sample
            writeByte(0) // grey
            writeByte(0)
            writeByte(0)
            writeByte(0)
        }
        chunk(out, "IHDR", header.toByteArray())
        chunk(out, "IDAT", zipped.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    fun dataUrl(r: GrayRaster): String = "data:image/png;base64," + Base64.getEncoder().encodeToString(encode(r))

    /** The grey value of the glasses' level nearest to [v]: n × 16, the brightest 255. */
    private fun level(v: Byte): Int {
        val n = minOf(15, ((v.toInt() and 0xFF) + 8) shr 4)
        return if (n == 15) 255 else n * 16
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        val name = type.toByteArray(Charsets.US_ASCII)
        d.writeInt(data.size)
        d.write(name)
        d.write(data)
        val crc = CRC32()
        crc.update(name)
        crc.update(data)
        d.writeInt(crc.value.toInt())
    }

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
