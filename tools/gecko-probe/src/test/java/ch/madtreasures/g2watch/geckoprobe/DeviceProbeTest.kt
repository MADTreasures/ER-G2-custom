package ch.madtreasures.g2watch.geckoprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceProbeTest {

    @Test
    fun `reads PSS from smaps_rollup`() {
        val rollup = """
            12c00000-7ffd4000 ---p 00000000 00:00 0                                  [rollup]
            Rss:              187204 kB
            Pss:              121633 kB
            Pss_Anon:          61012 kB
            Shared_Clean:      80232 kB
        """.trimIndent()
        assertEquals(121_633, DeviceProbe.pssKb(rollup.lineSequence()))
        assertNull(DeviceProbe.pssKb(sequenceOf("Rss: 1 kB")))
        assertNull(DeviceProbe.pssKb(sequenceOf("Pss: viel kB")))
    }
}
