package ch.madtreasures.g2watch.firmware

import ch.madtreasures.g2watch.glasses.FirmwareInstall
import ch.madtreasures.g2watch.glasses.FirmwareTarget
import com.faceclaw.app.BleProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole transfer against simulated lenses: our job around Faceclaw's real probe, prompt and
 * OTA flows. What matters most is what the lenses received, and that nothing reached the update
 * channel whenever the job stopped early.
 */
class FirmwareJobTest {
    private val fast = JobPolicy(settleMs = 0, verifyDelayMs = 0, verifyAttempts = 2, verifyIntervalMs = 0)

    @After
    fun resetAllowList() {
        TestImages.allowList = { sha ->
            when (sha) {
                TestFirmware.stockSha -> FirmwareKind.Stock
                TestFirmware.customSha -> FirmwareKind.Custom
                else -> null
            }
        }
    }

    private fun run(
        env: FakeFirmwareEnvironment,
        target: FirmwareTarget = FirmwareTarget.CUSTOM,
        testRun: Boolean = false,
        pair: LensPair = LensPair(RIGHT, LEFT),
        steps: MutableList<FirmwareInstall.Running> = ArrayList(),
    ): FirmwareInstall =
        FirmwareJob(target, testRun, pair, env, { steps += it }, { env.log += it }, fast).run()

    private fun FakeFirmwareEnvironment.promptPages() =
        glasses.writes.count { it.sid == BleProtocol.SID_EVENHUB && BleProtocol.readVarintFieldValue(it.pb, 1, -1) == 0 }

    private fun assertNothingFlashed(env: FakeFirmwareEnvironment) {
        assertEquals("OTA writes: " + env.glasses.otaWrites.map { it.sid }, 0, env.glasses.otaWrites.size)
        // Not even armed for a moment.
        assertTrue(env.links.all { it.armCount == 0 })
    }

    private fun message(result: FirmwareInstall) = when (result) {
        is FirmwareInstall.Failed -> result.message
        is FirmwareInstall.Done -> result.message
        else -> result.toString()
    }

    // --- the good paths ---------------------------------------------------------------------------

    @Test
    fun `installs the custom firmware on both lenses and confirms it after the reboot`() {
        val env = FakeFirmwareEnvironment()
        val steps = ArrayList<FirmwareInstall.Running>()
        val result = run(env, steps = steps)

        assertTrue(message(result), result is FirmwareInstall.Done)
        assertTrue(message(result), message(result).contains("Beide Gläser melden Faceclaw/35"))
        // Each lens received exactly the custom image's components, in order.
        val expected = TestFirmware.payloads(TestFirmware.custom)
        for (lens in listOf(LEFT, RIGHT)) {
            val got = env.glasses.received[lens]!!
            assertEquals(expected.size, got.size)
            expected.zip(got).forEach { (e, g) -> assertTrue(e.contentEquals(g)) }
        }
        // Left lens first, then right.
        val ota = env.glasses.otaWrites
        assertEquals(LEFT, ota.first().address)
        assertEquals(RIGHT, ota.last().address)
        // The glasses asked first, and the transfer came only after that.
        val prompt = env.glasses.writes.indexOfFirst { it.sid == BleProtocol.SID_EVENHUB }
        val firstOta = env.glasses.writes.indexOfFirst { it.uuid == BleProtocol.OTA_DATA_WRITE_UUID }
        assertTrue(prompt in 0 until firstOta)
        // The app let go of the glasses, kept the watch awake, and let it sleep again.
        assertEquals(1, env.released)
        assertTrue(env.awake.isNotEmpty())
        assertEquals(1, env.asleep)
        assertTrue(env.links.none { it.isArmed })
        // Exactly one link was armed, once: the transfer's.
        assertEquals(listOf(1), env.links.map { it.armCount }.filter { it > 0 })
        // For the case the app dies: "interrupted" before the first byte, "check interrupted" once
        // both lenses accepted everything, nothing left once the check confirmed it.
        assertEquals(listOf(FirmwareJob.INTERRUPTED, FirmwareJob.VERIFY_INTERRUPTED, null), env.noticeHistory)
        assertEquals(null, env.notice)
        // Progress went up to 100 % and says which lens.
        assertTrue(steps.any { it.step.startsWith("Linkes Glas") })
        assertTrue(steps.any { it.step.startsWith("Rechtes Glas") })
        assertEquals(100, steps.mapNotNull { it.percent }.max())
    }

    @Test
    fun `the check after the transfer does not need a prelude answer from the left arm`() {
        val env = FakeFirmwareEnvironment(SimulatedGlasses(leftAnswersPrelude = false))
        val result = run(env)
        assertTrue(message(result), result is FirmwareInstall.Done)
        assertTrue(env.glasses.writes.none { it.address == LEFT && it.sid == BleProtocol.PRELUDE_ACK_SID })
    }

    @Test
    fun `puts the original firmware back`() {
        val env = FakeFirmwareEnvironment(SimulatedGlasses(extension = "Faceclaw/35", firmwareAfterFlash = ""))
        val result = run(env, target = FirmwareTarget.ORIGINAL)
        assertTrue(message(result), result is FirmwareInstall.Done)
        assertTrue(message(result).contains("Original-Firmware"))
        val expected = TestFirmware.payloads(TestFirmware.stock)
        assertTrue(expected.zip(env.glasses.received[LEFT]!!).all { (e, g) -> e.contentEquals(g) })
        assertTrue(expected.zip(env.glasses.received[RIGHT]!!).all { (e, g) -> e.contentEquals(g) })
    }

    @Test
    fun `a lens that still runs the old firmware is not reported as done`() {
        // Right switched, left still reports the custom firmware after going back to the original.
        val glasses = SimulatedGlasses(extension = "Faceclaw/35", firmwareAfterFlash = "")
        glasses.armExtension[LEFT] = "Faceclaw/35"
        val env = FakeFirmwareEnvironment(glasses)
        val result = run(env, target = FirmwareTarget.ORIGINAL)
        assertTrue(result is FirmwareInstall.Failed)
        val text = message(result)
        assertTrue(text, text.contains("nicht auf beiden"))
        assertTrue(text, text.contains("links: Faceclaw/35"))
        assertTrue(text, text.contains("rechts: Original 2.3.0.24"))
    }

    @Test
    fun `a transfer the lenses do not confirm is reported as such`() {
        val env = FakeFirmwareEnvironment(SimulatedGlasses(firmwareAfterFlash = ""))
        val result = run(env)
        // Not a green "done": the transfer ended, but the glasses do not show the new firmware.
        assertTrue(result is FirmwareInstall.Failed)
        assertTrue(message(result), message(result).contains("Beide Gläser sind übertragen"))
    }

    @Test
    fun `a test run pairs, reads the batteries and checks the update channel, but writes nothing`() {
        val env = FakeFirmwareEnvironment()
        val result = run(env, testRun = true)
        assertTrue(message(result), result is FirmwareInstall.Done)
        result as FirmwareInstall.Done
        assertTrue(result.testRun)
        assertTrue(result.message.contains("nichts auf die Brille geschrieben"))
        assertTrue(result.message.contains("MTU L 247, R 247"))
        assertNothingFlashed(env)
        // No question on the glasses either: nothing is going to be written.
        assertEquals(0, env.promptPages())
        assertTrue(env.noticeHistory.isEmpty())
    }

    // --- stops before the glasses are touched ----------------------------------------------------

    @Test
    fun `a missing left arm stops at once`() {
        val env = FakeFirmwareEnvironment()
        val result = run(env, pair = LensPair(RIGHT, ""))
        assertTrue(result is FirmwareInstall.Failed)
        assertTrue(message(result).contains("beide Bügel"))
        assertEquals(0, env.glasses.writes.size)
        assertEquals(0, env.released)
    }

    @Test
    fun `a weak watch battery stops before anything happens, unless it charges`() {
        val env = FakeFirmwareEnvironment()
        env.power = WatchPower(49, charging = false)
        val result = run(env)
        assertTrue(message(result).contains("Akku der Uhr"))
        assertEquals(0, env.glasses.writes.size)

        val charging = FakeFirmwareEnvironment()
        charging.power = WatchPower(20, charging = true)
        assertTrue(run(charging) is FirmwareInstall.Done)
    }

    @Test
    fun `no image means no contact with the glasses`() {
        val env = FakeFirmwareEnvironment()
        env.stockError = FirmwareBuildException("Die Original-Firmware ließ sich nicht laden (offline).")
        val result = run(env)
        assertTrue(result is FirmwareInstall.Failed)
        assertTrue(message(result).contains("Nichts wurde an der Brille verändert"))
        assertEquals(0, env.glasses.writes.size)
        assertEquals(0, env.released)
    }

    @Test
    fun `an image off the allow-list is never sent`() {
        TestImages.allowList = { null }
        val env = FakeFirmwareEnvironment()
        val result = run(env)
        assertTrue(result is FirmwareInstall.Failed)
        assertTrue(message(result).contains("nicht auf der Liste"))
        assertNothingFlashed(env)
    }

    @Test
    fun `the allow-list is checked again right before the first byte`() {
        // Accepted while the image is prepared, refused on the second look before the transfer.
        var looks = 0
        TestImages.allowList = { sha ->
            looks++
            if (looks == 1 && sha == TestFirmware.customSha) FirmwareKind.Custom else null
        }
        val env = FakeFirmwareEnvironment()
        val result = run(env)
        assertTrue(result is FirmwareInstall.Failed)
        assertTrue(message(result), message(result).contains("nicht auf der Liste"))
        assertTrue(message(result).contains("Nichts wurde an der Brille verändert"))
        assertEquals(2, looks)
        assertNothingFlashed(env)
    }

    @Test
    fun `newer stock firmware on the glasses is not overwritten`() {
        val env = FakeFirmwareEnvironment(SimulatedGlasses(leftVersion = "2.3.1.0", rightVersion = "2.3.1.0"))
        val result = run(env)
        assertTrue(result is FirmwareInstall.Failed)
        assertTrue(message(result).contains("neuere Firmware (2.3.1.0)"))
        assertNothingFlashed(env)
        assertEquals(0, env.promptPages())
    }

    // --- stops at the glasses' own question ------------------------------------------------------

    @Test
    fun `declining on the glasses writes nothing`() {
        val env = FakeFirmwareEnvironment(SimulatedGlasses(promptAnswer = 0))
        val result = run(env)
        assertTrue(message(result).contains("Auf der Brille abgelehnt"))
        assertNothingFlashed(env)
        // Nothing sent, nothing to remember across a restart.
        assertTrue(env.noticeHistory.isEmpty())
    }

    @Test
    fun `a link lost mid-component is reported as such`() {
        val glasses = SimulatedGlasses()
        var dropped = false
        glasses.beforeAnswer = { w ->
            if (!dropped && w.sid == SimulatedGlasses.SID_DATA && w.address == LEFT) {
                dropped = true
                glasses.drop(LEFT)
                false
            } else {
                true
            }
        }
        val env = FakeFirmwareEnvironment(glasses)
        val result = run(env)
        assertTrue(result is FirmwareInstall.Failed)
        val text = message(result)
        assertTrue(text, !text.contains("nicht angenommen"))
        assertTrue(text, !text.contains("MTU"))
    }

    @Test
    fun `no answer on the glasses writes nothing`() {
        val env = FakeFirmwareEnvironment(SimulatedGlasses(promptAnswer = null))
        val result = run(env)
        assertTrue(message(result).contains("nichts gewählt"))
        assertNothingFlashed(env)
    }

    @Test
    fun `silent mode is explained in German and writes nothing`() {
        val env = FakeFirmwareEnvironment(SimulatedGlasses(silentMode = true))
        val result = run(env)
        assertTrue(message(result), message(result).contains("Lautlos-Modus"))
        assertNothingFlashed(env)
        assertEquals(0, env.promptPages())
    }

    @Test
    fun `the test run finds silent mode too, so the real prompt will not fail on it`() {
        val env = FakeFirmwareEnvironment(SimulatedGlasses(silentMode = true))
        val result = run(env, testRun = true)
        assertTrue(result is FirmwareInstall.Failed)
        assertTrue(message(result), message(result).contains("Lautlos-Modus"))
        assertNothingFlashed(env)
    }

    @Test
    fun `failures from Faceclaw's flows reach the wearer in German`() {
        // The right lens does not come back after the left one's reboot: Faceclaw says
        // "could not reach right lens: …".
        val glasses = SimulatedGlasses()
        glasses.connectResult = { address -> !(address == RIGHT && glasses.otaWrites.any { it.address == LEFT }) }
        val env = FakeFirmwareEnvironment(glasses)
        val result = run(env)
        assertTrue(result is FirmwareInstall.Failed)
        val text = message(result)
        assertTrue(text, !text.contains("could not") && !text.contains("phone"))
        assertTrue(text, text.contains("Die Brille war nicht erreichbar"))
        assertTrue(text, text.contains("Details im Protokoll"))
        assertTrue(env.log.any { it.contains("could not reach right lens") })
    }

    @Test
    fun `a weak or unreadable lens battery writes nothing`() {
        val low = FakeFirmwareEnvironment(SimulatedGlasses(battery = mutableMapOf(RIGHT to 80, LEFT to 49)))
        val lowResult = run(low)
        assertTrue(message(lowResult), message(lowResult).contains("Akku der Brille zu schwach"))
        assertTrue(message(lowResult).contains("L 49 %"))
        assertTrue(message(lowResult).contains("mindestens 50 %"))
        assertNothingFlashed(low)

        val unknown = FakeFirmwareEnvironment(SimulatedGlasses(battery = mutableMapOf(RIGHT to 80)))
        val unknownResult = run(unknown)
        assertTrue(message(unknownResult), message(unknownResult).contains("nicht lesbar"))
        assertNothingFlashed(unknown)
    }

    // --- the link ------------------------------------------------------------------------------

    @Test
    fun `a narrow Bluetooth link stops at the first firmware write`() {
        val env = FakeFirmwareEnvironment(SimulatedGlasses(mtu = 185))
        val result = run(env)
        assertTrue(result is FirmwareInstall.Failed)
        assertTrue(message(result), message(result).contains("MTU"))
        // The guard dropped the narrow connections before BEGIN: no firmware frame went out.
        assertEquals(0, env.glasses.otaWrites.size)
        assertTrue(env.links.any { it.mtuRefusalCount > 0 })
        assertTrue(message(result).contains("Nichts wurde an der Brille verändert"))
    }

    @Test
    fun `a lens that comes back narrow after the reboot is reconnected, not given up`() {
        // The right lens negotiates a small MTU on its first connection after the left one's reboot.
        val glasses = SimulatedGlasses()
        var rightConnects = 0
        glasses.connectResult = { address ->
            if (address == RIGHT && glasses.otaWrites.any { it.address == LEFT }) {
                rightConnects++
                glasses.mtu = if (rightConnects == 1) 23 else 247
            }
            true
        }
        val env = FakeFirmwareEnvironment(glasses)
        val result = run(env)
        assertTrue(message(result), result is FirmwareInstall.Done)
        assertTrue(rightConnects >= 2)
        // It recovered, so a later failure would not be blamed on the MTU.
        assertTrue(env.links.none { it.mtuTooNarrow })
        val expected = TestFirmware.payloads(TestFirmware.custom)
        assertTrue(expected.zip(glasses.received[RIGHT]!!).all { (e, g) -> e.contentEquals(g) })
    }

    @Test
    fun `the test run finds a narrow link too`() {
        val env = FakeFirmwareEnvironment(SimulatedGlasses(mtu = 185))
        val result = run(env, testRun = true)
        assertTrue(result is FirmwareInstall.Failed)
        assertTrue(message(result), message(result).contains("MTU-Aushandlung ergab nur 185"))
        assertNothingFlashed(env)
    }

    // --- failures during the transfer -----------------------------------------------------------

    @Test
    fun `a rejected block is sent again in place and the transfer completes`() {
        val glasses = SimulatedGlasses()
        var nak = true
        glasses.beforeAnswer = { w ->
            if (nak && w.sid == SimulatedGlasses.SID_DATA && w.address == LEFT) {
                // The lens answers "rejected" instead of storing the block.
                nak = false
                ackNak(glasses, w.address)
                false
            } else {
                true
            }
        }
        val env = FakeFirmwareEnvironment(glasses)
        val result = run(env)
        assertTrue(message(result), result is FirmwareInstall.Done)
        val expected = TestFirmware.payloads(TestFirmware.custom)
        assertTrue(expected.zip(glasses.received[LEFT]!!).all { (e, g) -> e.contentEquals(g) })
    }

    @Test
    fun `a left lens that never verifies stops the transfer before the right one`() {
        val glasses = SimulatedGlasses()
        glasses.beforeAnswer = { w ->
            val isEnd = w.sid == SimulatedGlasses.SID_CTRL && w.pb.firstOrNull()?.toInt() == SimulatedGlasses.OP_END
            if (isEnd && w.address == LEFT) {
                endStatus(glasses, w.address, 7) // CHECK_FAIL
                false
            } else {
                true
            }
        }
        val env = FakeFirmwareEnvironment(glasses)
        val result = run(env)
        assertTrue(result is FirmwareInstall.Failed)
        assertTrue(glasses.otaWrites.none { it.address == RIGHT })
        assertTrue(message(result), message(result).contains("unklar"))
        // The real cause, not Faceclaw's generic "failed after 3 attempts".
        assertTrue(message(result), message(result).contains("nicht angenommen"))
        // Saved until the wearer confirms it, in case Android ends the app first.
        assertEquals(message(result), env.notice?.second)
    }

    @Test
    fun `a right lens that fails after the left one says so`() {
        val glasses = SimulatedGlasses()
        glasses.beforeAnswer = { w ->
            val isEnd = w.sid == SimulatedGlasses.SID_CTRL && w.pb.firstOrNull()?.toInt() == SimulatedGlasses.OP_END
            if (isEnd && w.address == RIGHT) {
                endStatus(glasses, w.address, 7)
                false
            } else {
                true
            }
        }
        val env = FakeFirmwareEnvironment(glasses)
        val result = run(env)
        assertTrue(result is FirmwareInstall.Failed)
        assertTrue(message(result), message(result).contains("Das linke Glas hat die neue Firmware, das rechte nicht"))
    }

    private fun ackNak(glasses: SimulatedGlasses, address: String) = otaNotify(glasses, address, SimulatedGlasses.OP_BLOCK, 1)

    private fun endStatus(glasses: SimulatedGlasses, address: String, status: Int) = otaNotify(glasses, address, SimulatedGlasses.OP_END, status)

    private fun otaNotify(glasses: SimulatedGlasses, address: String, op: Int, status: Int) {
        val frame = BleProtocol.framePb(byteArrayOf(op.toByte(), status.toByte()), SimulatedGlasses.SID_DATA, 0, 1).single()
        glasses.listenerForTests?.onNotification(address, BleProtocol.OTA_DATA_NOTIFY_UUID, frame)
    }
}
