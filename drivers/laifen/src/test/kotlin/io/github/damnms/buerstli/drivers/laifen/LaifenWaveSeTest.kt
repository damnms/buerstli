package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.Access
import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.ModeSettings
import io.github.damnms.buerstli.domain.ParameterKind.RANGE
import io.github.damnms.buerstli.domain.ParameterKind.SPEED
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
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
 * The Wave SE status as read by the official app. There are no captures from a real SE yet:
 * frames are built from that layout. The first Bürstli report from an SE belongs in here.
 */
class LaifenWaveSeTest {

    /** start | function<<14 | command (16 bit) | counter | length (16 bit) | payload | xor */
    private fun frame(command: Int, payload: ByteArray, function: Int = 2, start: Int = 0x5A): ByteArray {
        val word = (function shl 14) or command
        val head = byteArrayOf(
            start.toByte(), (word shr 8).toByte(), word.toByte(), 0,
            (payload.size shr 8).toByte(), payload.size.toByte(),
        )
        val body = head + payload
        var cs = 0
        for (b in body) cs = cs xor (b.toInt() and 0xFF)
        return body + cs.toByte()
    }

    private fun status(
        active: Int = 1,
        flight: Int = 0, battery: Int = 80, reminder: Int = 1,
        durations: List<Int> = listOf(120, 150, 180, 90),
        hf: Int = 0, motor: Int = 0,
    ): ByteArray {
        val p = ByteArray(29)
        p[0] = active.toByte()
        for (m in 0 until 4) {
            p[1 + 3 * m] = (m + 1).toByte(); p[2 + 3 * m] = (m + 4).toByte(); p[3 + 3 * m] = (m + 7).toByte()
        }
        p[10] = 15 // mode 4 (high frequency) has strength 11-20
        p[13] = flight.toByte(); p[14] = battery.toByte(); p[15] = reminder.toByte()
        durations.forEachIndexed { m, s -> p[16 + 2 * m] = (s shr 8).toByte(); p[17 + 2 * m] = s.toByte() }
        p[24] = hf.toByte(); p[25] = motor.toByte()
        return p
    }

    private fun decode(payload: ByteArray, command: Int = LaifenWaveSeCodec.STATUS) =
        LaifenWaveSeCodec.decode(LaifenFrame.decode(frame(command, payload))!!)

    private fun settings(s: Int, r: Int, sp: Int) = ModeSettings.of(STRENGTH to s, RANGE to r, SPEED to sp)

    // --- codec ---

    @Test
    fun `decodes the SE status`() {
        val s = decode(status())!!
        assertEquals(80, s.batteryPercent)
        assertEquals(1, s.activeModeIndex)
        assertEquals(settings(1, 4, 7), s.modes[0])
        assertEquals(settings(15, 7, 10), s.modes[3])
        assertEquals(150, s.brushingDurationSeconds) // the active mode's own duration
        assertEquals(Activity.STANDBY, s.activity)
        assertEquals(
            mapOf(Switch.FLIGHT_MODE to false, Switch.REMINDER_30S to true, Switch.HIGH_FREQUENCY to false),
            s.switches,
        )
    }

    @Test
    fun `durations are 16 bit big-endian`() {
        assertEquals(300, decode(status(active = 0, durations = listOf(300, 0, 0, 0)))!!.brushingDurationSeconds)
    }

    @Test
    fun `motor, flight mode and high frequency`() {
        val s = decode(status(flight = 1, hf = 1, motor = 1))!!
        assertEquals(Activity.BRUSHING, s.activity)
        assertEquals(true, s.switches[Switch.FLIGHT_MODE])
        assertEquals(true, s.switches[Switch.HIGH_FREQUENCY])
    }

    @Test
    fun `a boolean is true only for exactly 01`() {
        val s = decode(status(flight = 2, reminder = 0xFF, motor = 2))!!
        assertEquals(false, s.switches[Switch.FLIGHT_MODE])
        assertEquals(false, s.switches[Switch.REMINDER_30S])
        assertEquals(Activity.STANDBY, s.activity)
    }

    @Test
    fun `an unknown active mode shows no mode and no duration`() {
        val s = decode(status(active = 7))!!
        assertNull(s.activeModeIndex)
        assertNull(s.brushingDurationSeconds)
        assertEquals(80, s.batteryPercent)
    }

    @Test
    fun `battery is clamped`() {
        assertEquals(100, decode(status(battery = 0xC8))!!.batteryPercent)
    }

    @Test
    fun `other commands and short payloads are not a status`() {
        assertNull(decode(status(), command = 0x0104))
        assertNull(decode(status().copyOf(28)))
    }

    // --- driver and session ---

    @Test
    fun `claims LFTB01-SE brushes only, experimental, read-only`() {
        assertTrue(LaifenWaveSeDriver.recognizes(Advertisement("LFTB01-SE-0001")))
        assertFalse(LaifenWaveSeDriver.recognizes(Advertisement("LFTB01-P-1DEE")))
        assertFalse(LaifenWaveSeDriver.recognizes(Advertisement("LFTB01-C-0001")))
        assertFalse(LaifenWaveSeDriver.recognizes(Advertisement("LFTB02-S-412B")))
        assertFalse(LaifenWaveV1Driver.recognizes(Advertisement("LFTB01-SE-0001")))
        assertEquals(SupportLevel.EXPERIMENTAL, LaifenWaveSeDriver.supportLevel)
        assertTrue(LaifenWaveSeDriver.capabilities.switches.values.all { it == Access.READ_ONLY })
        assertNull(LaifenWaveSeDriver.capabilities.brushingDuration)
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
        val session = LaifenWaveSeDriver.open(link).apply { start() }
        val ch = LaifenWaveV1Codec.CHARACTERISTIC

        assertEquals(listOf(ch), link.subscribed)
        session.onNotification(ch, frame(LaifenWaveSeCodec.STATUS, status(battery = 55, motor = 1)))
        assertEquals(55, session.state.value.batteryPercent)
        assertEquals(Activity.BRUSHING, session.state.value.activity)

        // Unknown frames and V1 states change nothing.
        session.onNotification(ch, frame(0x0104, ByteArray(30)))
        session.onNotification(ch, Packets.MODE2_ACTIVE)
        assertEquals(55, session.state.value.batteryPercent)

        assertEquals(CommandResult.Unsupported, session.execute(BrushCommand.SetSwitch(Switch.FLIGHT_MODE, true)))
        assertEquals(0, link.writes)
    }
}
