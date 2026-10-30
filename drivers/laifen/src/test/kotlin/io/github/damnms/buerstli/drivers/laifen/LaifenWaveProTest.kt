package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.Access
import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.Switch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The long Laifen frame as described by laifen-toolbox (appendix, read from the vendor app) and
 * the Wave Pro status as confirmed on a real LFTB02-S by the Home Assistant integration
 * (UrbanTechIO/Laifen). There are no captures from a real Wave Pro here yet: frames are built
 * from that documentation. The first Bürstli report from a Wave Pro belongs in here.
 */
class LaifenWaveProTest {

    /** start | function<<14 | command (16 bit) | counter | length (16 bit) | payload | xor */
    private fun frame(start: Int, function: Int, command: Int, payload: ByteArray, counter: Int = 0): ByteArray {
        val word = (function shl 14) or command
        val head = byteArrayOf(
            start.toByte(), (word shr 8).toByte(), word.toByte(), counter.toByte(),
            (payload.size shr 8).toByte(), payload.size.toByte(),
        )
        val body = head + payload
        var cs = 0
        for (b in body) cs = cs xor (b.toInt() and 0xFF)
        return body + cs.toByte()
    }

    private fun status(
        battery: Int = 75, durationSeconds: Int = 120, running: Int = 0,
        deepClean: Int = 1, antiSplash: Int = 0, rampUp: Int = 1, spinDry: Int = 0,
        overPressure: Int = 1, bristle: Int = 1, liftToWake: Int = 0,
    ): ByteArray {
        val p = ByteArray(36)
        p[2] = battery.toByte()
        p[5] = durationSeconds.toByte(); p[6] = (durationSeconds shr 8).toByte() // little-endian
        p[13] = running.toByte()
        p[15] = deepClean.toByte(); p[16] = antiSplash.toByte()
        p[24] = rampUp.toByte(); p[25] = spinDry.toByte(); p[26] = overPressure.toByte()
        p[30] = bristle.toByte(); p[32] = liftToWake.toByte()
        return p
    }

    // --- frame ---

    @Test
    fun `decodes the long frame`() {
        val f = LaifenFrame.decode(frame(0x5A, 3, 0x0103, byteArrayOf(1, 2, 3), counter = 7))!!
        assertEquals(LaifenFrame.Function.ANSWER, f.function)
        assertEquals(0x0103, f.command)
        assertEquals(7, f.counter)
        assertArrayEquals(byteArrayOf(1, 2, 3), f.payload)
        assertTrue(f.checksumOk)
    }

    @Test
    fun `the Home Assistant Wave Pro packets are this frame format`() {
        // "5A C1 03 …" (periodic) and "5A 81 03 …" (on change) are answer and report of 0x0103,
        assertEquals(LaifenFrame.Function.ANSWER, LaifenFrame.decode(frame(0x5A, 3, 0x0103, ByteArray(36)))!!.function)
        assertEquals(LaifenFrame.Function.REPORT, LaifenFrame.decode(frame(0x5A, 2, 0x0103, ByteArray(36)))!!.function)
        // and its power command AA 01 08 00 00 01 01 [xor] is a write of 0x0108.
        val power = LaifenFrame.decode(byteArrayOf(0xAA.toByte(), 0x01, 0x08, 0x00, 0x00, 0x01, 0x01, 0xA3.toByte()))!!
        assertEquals(LaifenFrame.Function.WRITE, power.function)
        assertEquals(0x0108, power.command)
    }

    @Test
    fun `a wrong checksum is noted but not fatal, the device side was never checked`() {
        val bad = frame(0x5A, 2, 0x0103, ByteArray(36)).also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertFalse(LaifenFrame.decode(bad)!!.checksumOk)
    }

    @Test
    fun `truncated frames and the old Wave format are no long frames`() {
        assertNull(LaifenFrame.decode(byteArrayOf(0x5A, 0xC1.toByte(), 0x03)))
        assertNull(LaifenFrame.decode(frame(0x5A, 2, 0x0103, ByteArray(36)).copyOf(20)))
        assertNull(LaifenFrame.decode(Packets.MODE2_ACTIVE)) // AA 0A 02 14 … is the V1 state
    }

    // --- Wave Pro status ---

    @Test
    fun `reads battery, brushing time, activity and the Pro features`() {
        val s = LaifenWaveProCodec.decode(LaifenFrame.decode(frame(0x5A, 2, 0x0103, status()))!!)!!
        assertEquals(75, s.batteryPercent)
        assertEquals(120, s.brushingDurationSeconds)
        assertEquals(Activity.STANDBY, s.activity)
        assertEquals(true, s.switches[Switch.DEEP_CLEAN])
        assertEquals(false, s.switches[Switch.ANTI_SPLASH])
        assertEquals(true, s.switches[Switch.POWER_RAMP_UP])
        assertEquals(false, s.switches[Switch.SPIN_DRY])
        assertEquals(true, s.switches[Switch.PRESSURE_WARNING])
        assertEquals(true, s.switches[Switch.BRISTLE_PROTECTION])
        assertEquals(false, s.switches[Switch.LIFT_TO_WAKE])
    }

    @Test
    fun `running, idle and just stopped`() {
        fun activity(r: Int) = LaifenWaveProCodec.decode(LaifenFrame.decode(frame(0x5A, 2, 0x0103, status(running = r)))!!)!!.activity
        assertEquals(Activity.BRUSHING, activity(1))
        assertEquals(Activity.STANDBY, activity(0))
        assertEquals(Activity.STANDBY, activity(2))
    }

    @Test
    fun `brushing time is 16 bit little-endian, so 5 minutes work`() {
        val s = LaifenWaveProCodec.decode(LaifenFrame.decode(frame(0x5A, 2, 0x0103, status(durationSeconds = 300)))!!)!!
        assertEquals(300, s.brushingDurationSeconds)
    }

    @Test
    fun `pressure telemetry while brushing`() {
        fun pressure(flag: Int) = LaifenWaveProCodec.overPressure(
            LaifenFrame.decode(frame(0x5A, 2, 0x020C, byteArrayOf(3, 1, flag.toByte(), 0x55)))!!,
        )
        assertEquals(true, pressure(1))
        assertEquals(false, pressure(0))
    }

    @Test
    fun `other commands and short payloads give nothing`() {
        assertNull(LaifenWaveProCodec.decode(LaifenFrame.decode(frame(0x5A, 2, 0x0109, status()))!!))
        assertNull(LaifenWaveProCodec.decode(LaifenFrame.decode(frame(0x5A, 2, 0x0103, ByteArray(20)))!!))
    }

    // --- driver and session ---

    @Test
    fun `claims LFTB02 brushes only, experimental, features read-only`() {
        assertTrue(LaifenWaveProDriver.recognizes(Advertisement("LFTB02-S-412B")))
        assertFalse(LaifenWaveProDriver.recognizes(Advertisement("LFTB01-P-1DEE")))
        assertFalse(LaifenWaveProDriver.recognizes(Advertisement("LFTB01-SE-0001")))
        assertEquals(SupportLevel.EXPERIMENTAL, LaifenWaveProDriver.supportLevel)
        assertTrue(LaifenWaveProDriver.capabilities.switches.values.all { it == Access.READ_ONLY })
        assertTrue(LaifenWaveProDriver.capabilities.modes.isEmpty())
    }

    private class ListeningOnly : BrushLink {
        val subscribed = mutableListOf<UUID>()
        var writes = 0
        override suspend fun read(characteristic: UUID): ByteArray = ByteArray(0)
        override suspend fun write(characteristic: UUID, data: ByteArray) { writes++ }
        override suspend fun enableNotifications(characteristic: UUID) { subscribed += characteristic }
    }

    @Test
    fun `the session only subscribes and listens, it never writes`() = runTest {
        val link = ListeningOnly()
        val session = LaifenWaveProDriver.open(link).apply { start() }
        val ch = LaifenWaveV1Codec.CHARACTERISTIC

        assertEquals(listOf(ch), link.subscribed)
        session.onNotification(ch, frame(0x5A, 3, 0x0103, status(battery = 60, running = 1)))
        assertEquals(60, session.state.value.batteryPercent)
        assertEquals(Activity.BRUSHING, session.state.value.activity)

        session.onNotification(ch, frame(0x5A, 2, 0x020C, byteArrayOf(3, 1, 1, 0x40)))
        assertEquals(true, session.state.value.pressureHigh)
        assertEquals(60, session.state.value.batteryPercent) // telemetry keeps the rest

        session.onNotification(ch, Packets.MODE2_ACTIVE) // other formats: ignored
        assertEquals(60, session.state.value.batteryPercent)

        assertEquals(CommandResult.Unsupported, session.execute(BrushCommand.SetSwitch(Switch.DEEP_CLEAN, false)))
        assertEquals(0, link.writes)
    }
}
