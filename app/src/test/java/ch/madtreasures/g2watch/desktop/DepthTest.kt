package ch.madtreasures.g2watch.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class DepthTest {

    @Test
    fun `three levels, middle by default, all multiples of 4`() {
        assertEquals(listOf("Nah", "Mittel", "Fern"), Depth.entries.map { it.label })
        assertEquals(listOf(16, 0, -16), Depth.entries.map { it.disparity })
        assertEquals(Depth.MIDDLE, Depth.DEFAULT)
        // Both lens shifts even: the firmware's fast copy.
        for (level in Depth.entries) assertEquals(level.name, 0, level.disparity % 4)
    }

    @Test
    fun `per lens it is exactly Even's placement`() {
        // Faceclaw's shift (left floor(d / 2), right -floor((d + 1) / 2)) from x = 32, where depth 0 puts both lenses.
        val left = Depth.entries.map { 32 + Math.floorDiv(it.disparity, 2) }
        val right = Depth.entries.map { 32 - Math.floorDiv(it.disparity + 1, 2) }
        // Even's tables for levels 0, 1, 2 (0x7c8254 left, 0x7c8250 right).
        assertEquals(listOf(40, 32, 24), left)
        assertEquals(listOf(24, 32, 40), right)
    }

    @Test
    fun `the stored name comes back, anything else gives the default`() {
        for (level in Depth.entries) assertEquals(level, Depth.of(level.name))
        for (stored in listOf(null, "", "near", "Nah", "16", "MIDDLE ")) assertEquals("$stored", Depth.MIDDLE, Depth.of(stored))
    }
}
