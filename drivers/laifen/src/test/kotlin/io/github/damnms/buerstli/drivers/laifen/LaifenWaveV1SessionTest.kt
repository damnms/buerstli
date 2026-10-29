package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.BrushCommand.ChangeBrushingDuration
import io.github.damnms.buerstli.domain.BrushCommand.ChangeModeSettings
import io.github.damnms.buerstli.domain.BrushCommand.SetSwitch
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.ModeSettings
import io.github.damnms.buerstli.domain.ParameterKind.RANGE
import io.github.damnms.buerstli.domain.ParameterKind.SPEED
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
import io.github.damnms.buerstli.domain.Switch
import io.github.damnms.buerstli.domain.Validation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

class LaifenWaveV1SessionTest {

    /**
     * A pretend LFTB01-P, modelled on the diagnostics log of the real one:
     * - a plain GATT read returns filler, never the state;
     * - the state arrives as a notification when asked with READ_STATE;
     * - after a mode-settings write the brush pushes its state by itself,
     *   after a brushing-time write it only sends an acknowledgement;
     * - with [staleStates], the first states after a write still show the old values.
     */
    private class FakeBrush(
        var status: ByteArray,
        val acceptsWrites: Boolean = true,
        val staleStates: Int = 0,
    ) : BrushLink {
        val writes = mutableListOf<ByteArray>()
        val notificationsEnabled = mutableListOf<UUID>()
        var failWrites = false
        var session: BrushSession? = null
        private var pending: ByteArray? = null
        private var staleLeft = 0

        val commands get() = writes.filterNot { it.contentEquals(Packets.READ_STATE) }

        override suspend fun read(characteristic: UUID): ByteArray = Packets.GATT_READ_FILLER

        override suspend fun enableNotifications(characteristic: UUID) {
            notificationsEnabled += characteristic
        }

        override suspend fun write(characteristic: UUID, data: ByteArray) {
            if (failWrites) throw IOException("brush fell asleep")
            writes += data
            // As documented: 0x04, 0x0E and 0x0F push the state, 0x05 and 0x0B only acknowledge.
            when (data[1].toInt()) {
                0x0A -> pushState()
                0x04, 0x0E, 0x0F -> { accept(data); pushState() }
                0x05, 0x0B -> { accept(data); notify(ack(data[1])) }
            }
        }

        private fun accept(cmd: ByteArray) {
            if (!acceptsWrites) return
            pending = apply(status, cmd)
            staleLeft = staleStates
        }

        private fun pushState() {
            pending?.let {
                if (staleLeft-- <= 0) {
                    status = it
                    pending = null
                }
            }
            notify(status)
        }

        fun notify(value: ByteArray) = session!!.onNotification(LaifenWaveV1Codec.CHARACTERISTIC, value.copyOf())

        fun applied(cmd: ByteArray) = apply(status, cmd)

        private fun ack(code: Byte): ByteArray {
            val body = byteArrayOf(0xAA.toByte(), code, 0x02, 0x01, 0x00)
            var cs = 0
            for (b in body) cs = cs xor (b.toInt() and 0xFF)
            return body + cs.toByte()
        }

        private fun apply(status: ByteArray, cmd: ByteArray): ByteArray {
            val out = status.copyOf()
            when (cmd[1].toInt()) {
                0x04 -> { val base = 5 + 3 * cmd[4]; out[base] = cmd[5]; out[base + 1] = cmd[6]; out[base + 2] = cmd[7] }
                0x05 -> { out[20] = cmd[4]; out[21] = cmd[5] }
                0x0B -> out[4 + 15] = cmd[4]
                0x0E -> out[4 + 18] = cmd[4]
                0x0F -> out[4 + 19] = cmd[4]
            }
            var cs = 0
            for (i in 0 until out.lastIndex) cs = cs xor (out[i].toInt() and 0xFF)
            out[out.lastIndex] = cs.toByte()
            return out
        }
    }

    private fun settings(s: Int, r: Int, sp: Int) = ModeSettings.of(STRENGTH to s, RANGE to r, SPEED to sp)

    private suspend fun connect(brush: FakeBrush): BrushSession =
        LaifenWaveV1Driver.open(brush).also { brush.session = it; it.start() }

    @Test
    fun `start subscribes and asks for the state instead of waiting for it`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = connect(brush)

        assertEquals(listOf(LaifenWaveV1Codec.CHARACTERISTIC), brush.notificationsEnabled)
        assertArrayEquals(Packets.READ_STATE, brush.writes.first())
        assertEquals(94, session.state.value.batteryPercent)
        assertEquals(1, session.state.value.activeModeIndex)
    }

    @Test
    fun `notifications from the brush update the state`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = connect(brush)

        brush.notify(Packets.MODE3_ACTIVE)

        assertEquals(2, session.state.value.activeModeIndex)
    }

    @Test
    fun `acknowledgements and other packets are ignored`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = connect(brush)
        val before = session.state.value

        brush.notify(Packets.ACK_DURATION)
        brush.notify(Packets.hex("AA-01-02-09-02-00-08-59-04-04-03-00-03-F3"))

        assertEquals(before, session.state.value)
    }

    @Test
    fun `valid mode change is written and confirmed`() = runTest {
        val brush = FakeBrush(Packets.MODE3_ACTIVE)
        val session = connect(brush)

        assertEquals(CommandResult.Confirmed, session.execute(ChangeModeSettings(2, settings(6, 6, 5))))
        assertArrayEquals(Packets.SET_MODE3_666_TO_665, brush.commands.single())
        assertEquals(settings(6, 6, 5), session.state.value.modes[2])
    }

    @Test
    fun `a brush that applies the change a moment later is still confirmed`() = runTest {
        val brush = FakeBrush(Packets.MODE3_ACTIVE, staleStates = 2)
        val session = connect(brush)

        assertEquals(CommandResult.Confirmed, session.execute(ChangeModeSettings(2, settings(6, 6, 5))))
    }

    @Test
    fun `brushing time is confirmed by asking for the state, since the brush only acknowledges`() = runTest {
        // Found in the app's log: after AA-05 the brush sends AA-05-02-01-00-AC and no state.
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = connect(brush)

        assertEquals(CommandResult.Confirmed, session.execute(ChangeBrushingDuration(150)))
        assertArrayEquals(Packets.SET_DURATION_150, brush.commands.single())
        assertEquals(150, session.state.value.brushingDurationSeconds)
    }

    @Test
    fun `confirmation may also arrive as a pushed notification`() = runTest {
        val brush = FakeBrush(Packets.MODE3_ACTIVE, acceptsWrites = false)
        val session = connect(brush)

        launch {
            delay(400)
            brush.notify(brush.applied(Packets.SET_MODE3_666_TO_665))
        }

        assertEquals(CommandResult.Confirmed, session.execute(ChangeModeSettings(2, settings(6, 6, 5))))
    }

    @Test
    fun `a brush that ignores the write is reported as not applied, not as a lost connection`() = runTest {
        val session = connect(FakeBrush(Packets.MODE2_ACTIVE, acceptsWrites = false))

        assertEquals(CommandResult.NotApplied, session.execute(ChangeModeSettings(0, settings(4, 1, 5))))
    }

    @Test
    fun `out-of-range values never reach the brush`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = connect(brush)

        assertEquals(
            CommandResult.Invalid(Validation.OutOfRange(STRENGTH, 15, 1..10)),
            session.execute(ChangeModeSettings(0, settings(15, 5, 5))),
        )
        assertTrue(brush.commands.isEmpty())
    }

    @Test
    fun `unknown mode never reaches the brush`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = connect(brush)

        assertEquals(CommandResult.UnknownMode(7), session.execute(ChangeModeSettings(7, settings(5, 5, 5))))
        assertTrue(brush.commands.isEmpty())
    }

    @Test
    fun `invalid brushing time never reaches the brush`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = connect(brush)

        assertEquals(
            CommandResult.Invalid(Validation.InvalidDuration(75, 60..300, 30)),
            session.execute(ChangeBrushingDuration(75)),
        )
        assertTrue(brush.commands.isEmpty())
    }

    @Test
    fun `a lost connection during the write is reported as failure`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = connect(brush)
        brush.failWrites = true

        assertTrue(session.execute(ChangeModeSettings(0, settings(4, 1, 5))) is CommandResult.Failed)
    }

    @Test
    fun `30-second reminder is confirmed by asking, since the brush only acknowledges`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = connect(brush)

        assertEquals(CommandResult.Confirmed, session.execute(SetSwitch(Switch.REMINDER_30S, true)))
        assertArrayEquals(Packets.hex("AA-0B-01-01-01-A0"), brush.commands.single())
        assertEquals(true, session.state.value.switches[Switch.REMINDER_30S])
    }

    @Test
    fun `high frequency and motor are confirmed by the pushed state`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = connect(brush)

        assertEquals(CommandResult.Confirmed, session.execute(SetSwitch(Switch.HIGH_FREQUENCY, true)))
        assertEquals(CommandResult.Confirmed, session.execute(SetSwitch(Switch.MOTOR, true)))
        assertEquals(CommandResult.Confirmed, session.execute(SetSwitch(Switch.MOTOR, false)))
        assertEquals(true, session.state.value.switches[Switch.HIGH_FREQUENCY])
        assertEquals(false, session.state.value.switches[Switch.MOTOR])
    }

    @Test
    fun `flight mode is never written, it is read-only`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = connect(brush)

        assertEquals(CommandResult.Unsupported, session.execute(SetSwitch(Switch.FLIGHT_MODE, true)))
        assertTrue(brush.commands.isEmpty())
    }
}
