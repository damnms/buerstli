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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The kids' TC51 status as read by the official app. There are no captures from a real TC51 yet:
 * frames are built from that layout. The first Bürstli report from a TC51 belongs in here.
 */
class LaifenTc51Test {

    /** start | function<<14 | command (16 bit) | counter | length (16 bit) | payload | xor */
    private fun frame(command: Int, payload: ByteArray, function: Int = 2): ByteArray {
        val word = (function shl 14) or command
        val head = byteArrayOf(0x5A, (word shr 8).toByte(), word.toByte(), 0, (payload.size shr 8).toByte(), payload.size.toByte())
        val body = head + payload
        var cs = 0
        for (b in body) cs = cs xor (b.toInt() and 0xFF)
        return body + cs.toByte()
    }

    private fun status(
        size: Int = 43, mode: Int = 2, flight: Int = 0, battery: Int = 64,
        durations: List<Int> = listOf(60, 90, 120, 150), power: Int = 0, childLock: Int = 1,
        rampUp: Int = 1, spin: Int = 0, pressure: Int = 1, reminder: Int = 1, hf: Int = 0,
    ): ByteArray {
        val p = ByteArray(size)
        fun set(i: Int, v: Int) { if (i < size) p[i] = v.toByte() }
        set(0, mode); set(1, flight); set(2, battery)
        durations.forEachIndexed { m, s -> set(4 + 2 * m, s shr 8); set(5 + 2 * m, s) }
        set(13, power); set(15, childLock)
        set(24, rampUp); set(25, spin); set(26, pressure)
        set(36, reminder); set(41, hf)
        return p
    }

    private fun decode(payload: ByteArray, command: Int = LaifenTc51Codec.STATUS) =
        LaifenTc51Codec.decode(LaifenFrame.decode(frame(command, payload))!!)

    // --- codec ---

    @Test
    fun `decodes the TC51 status`() {
        val s = decode(status())!!
        assertEquals(64, s.batteryPercent)
        assertEquals(120, s.brushingDurationSeconds) // the active mode's own duration
        assertEquals(Activity.STANDBY, s.activity)
        assertEquals(
            mapOf(
                Switch.FLIGHT_MODE to false, Switch.CHILD_LOCK to true,
                Switch.POWER_RAMP_UP to true, Switch.SPIN_DRY to false, Switch.PRESSURE_WARNING to true,
                Switch.REMINDER_30S to true, Switch.HIGH_FREQUENCY to false,
            ),
            s.switches,
        )
        assertNull(s.activeModeIndex) // no mode values to show
    }

    @Test
    fun `durations are 16 bit big-endian, unknown modes give none`() {
        assertEquals(300, decode(status(mode = 0, durations = listOf(300, 0, 0, 0)))!!.brushingDurationSeconds)
        assertNull(decode(status(mode = 4))!!.brushingDurationSeconds)
    }

    @Test
    fun `brushing, idle and just stopped`() {
        assertEquals(Activity.BRUSHING, decode(status(power = 1))!!.activity)
        assertEquals(Activity.STANDBY, decode(status(power = 0))!!.activity)
        assertEquals(Activity.STANDBY, decode(status(power = 2))!!.activity)
    }

    @Test
    fun `a boolean is true only for exactly 01`() {
        val s = decode(status(flight = 2, childLock = 0xFF, hf = 1))!!
        assertEquals(false, s.switches[Switch.FLIGHT_MODE])
        assertEquals(false, s.switches[Switch.CHILD_LOCK])
        assertEquals(true, s.switches[Switch.HIGH_FREQUENCY])
    }

    @Test
    fun `a short status without the optional tail still decodes`() {
        val s = decode(status(size = 29))!!
        assertEquals(64, s.batteryPercent)
        assertFalse(Switch.REMINDER_30S in s.switches)
        assertFalse(Switch.HIGH_FREQUENCY in s.switches)
        assertEquals(true, s.switches[Switch.CHILD_LOCK])
    }

    @Test
    fun `other commands and too short payloads are not a status`() {
        assertNull(decode(status(), command = 0x0104))
        assertNull(decode(status(size = 28)))
    }

    // --- driver and session ---

    @Test
    fun `claims LFTB01-C brushes only, experimental, read-only`() {
        assertTrue(LaifenTc51Driver.recognizes(Advertisement("LFTB01-C-0001")))
        assertFalse(LaifenTc51Driver.recognizes(Advertisement("TC51BB-0001")))
        assertFalse(LaifenTc51Driver.recognizes(Advertisement("LFTB01-P-1DEE")))
        assertFalse(LaifenWaveV1Driver.recognizes(Advertisement("LFTB01-C-0001")))
        assertEquals(SupportLevel.EXPERIMENTAL, LaifenTc51Driver.supportLevel)
        assertTrue(LaifenTc51Driver.capabilities.switches.values.all { it == Access.READ_ONLY })
        assertTrue(LaifenTc51Driver.capabilities.modes.isEmpty())
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
        val session = LaifenTc51Driver.open(link).apply { start() }
        val ch = LaifenWaveV1Codec.CHARACTERISTIC

        assertEquals(listOf(ch), link.subscribed)
        session.onNotification(ch, frame(LaifenTc51Codec.STATUS, status(battery = 33, power = 1)))
        assertEquals(33, session.state.value.batteryPercent)
        assertEquals(Activity.BRUSHING, session.state.value.activity)

        session.onNotification(ch, frame(0x0104, ByteArray(40)))
        session.onNotification(UUID.randomUUID(), frame(LaifenTc51Codec.STATUS, status(battery = 1)))
        assertEquals(33, session.state.value.batteryPercent)

        assertEquals(CommandResult.Unsupported, session.execute(BrushCommand.SetSwitch(Switch.CHILD_LOCK, false)))
        assertEquals(0, link.writes)
    }
}
