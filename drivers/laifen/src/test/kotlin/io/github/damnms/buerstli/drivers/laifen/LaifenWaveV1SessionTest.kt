package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.BrushCommand.ChangeBrushingDuration
import io.github.damnms.buerstli.domain.BrushCommand.ChangeModeSettings
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.ModeSettings
import io.github.damnms.buerstli.domain.ParameterKind.RANGE
import io.github.damnms.buerstli.domain.ParameterKind.SPEED
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
import io.github.damnms.buerstli.domain.Validation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

class LaifenWaveV1SessionTest {

    /**
     * A pretend brush behind a [BrushLink]: answers reads with its status packet and,
     * if [acceptsWrites], applies mode-settings commands like the real one does.
     */
    private class FakeBrush(
        var status: ByteArray,
        val acceptsWrites: Boolean = true,
        /** Like the real brush: reads right after a write still return the old status. */
        val staleReadsAfterWrite: Int = 0,
    ) : BrushLink {
        val writes = mutableListOf<ByteArray>()
        val notificationsEnabled = mutableListOf<UUID>()
        var failWrites = false
        private var pending: ByteArray? = null
        private var staleLeft = 0

        override suspend fun read(characteristic: UUID): ByteArray {
            pending?.let {
                if (staleLeft-- <= 0) {
                    status = it
                    pending = null
                }
            }
            return status.copyOf()
        }

        override suspend fun write(characteristic: UUID, data: ByteArray) {
            if (failWrites) throw IOException("brush fell asleep")
            writes += data
            if (!acceptsWrites) return
            if (staleReadsAfterWrite == 0) {
                status = apply(status, data)
            } else {
                pending = apply(status, data)
                staleLeft = staleReadsAfterWrite
            }
        }

        fun applied(cmd: ByteArray) = apply(status, cmd)

        override suspend fun enableNotifications(characteristic: UUID) {
            notificationsEnabled += characteristic
        }

        private fun apply(status: ByteArray, cmd: ByteArray): ByteArray {
            val out = status.copyOf()
            when (cmd[1].toInt()) {
                0x04 -> { // mode settings
                    val base = 5 + 3 * cmd[4]
                    out[base] = cmd[5]; out[base + 1] = cmd[6]; out[base + 2] = cmd[7]
                }
                0x05 -> { // brushing duration
                    out[20] = cmd[4]; out[21] = cmd[5]
                }
            }
            var cs = 0
            for (i in 0 until out.lastIndex) cs = cs xor (out[i].toInt() and 0xFF)
            out[out.lastIndex] = cs.toByte()
            return out
        }
    }

    private fun settings(s: Int, r: Int, sp: Int) = ModeSettings.of(STRENGTH to s, RANGE to r, SPEED to sp)

    @Test
    fun `start subscribes to notifications and reads the current status`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = LaifenWaveV1Driver.open(brush)

        session.start()

        assertEquals(listOf(LaifenWaveV1Codec.CHARACTERISTIC), brush.notificationsEnabled)
        assertEquals(94, session.state.value.batteryPercent)
        assertEquals(1, session.state.value.activeModeIndex)
    }

    @Test
    fun `notifications from the brush update the state`() = runTest {
        val session = LaifenWaveV1Driver.open(FakeBrush(Packets.MODE2_ACTIVE)).apply { start() }

        session.onNotification(LaifenWaveV1Codec.CHARACTERISTIC, Packets.MODE3_ACTIVE)

        assertEquals(2, session.state.value.activeModeIndex)
    }

    @Test
    fun `garbage notifications are ignored`() = runTest {
        val session = LaifenWaveV1Driver.open(FakeBrush(Packets.MODE2_ACTIVE)).apply { start() }
        val before = session.state.value

        session.onNotification(LaifenWaveV1Codec.CHARACTERISTIC, Packets.hex("AA-04-01-01-00-AE"))

        assertEquals(before, session.state.value)
    }

    @Test
    fun `valid change is written, read back and confirmed`() = runTest {
        val brush = FakeBrush(Packets.MODE3_ACTIVE)
        val session = LaifenWaveV1Driver.open(brush).apply { start() }

        val result = session.execute(ChangeModeSettings(2, settings(6, 6, 5)))

        assertEquals(CommandResult.Confirmed, result)
        assertArrayEquals(Packets.SET_MODE3_666_TO_665, brush.writes.single())
        assertEquals(settings(6, 6, 5), session.state.value.modes[2])
    }

    @Test
    fun `out-of-range values never reach the brush`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = LaifenWaveV1Driver.open(brush).apply { start() }

        val result = session.execute(ChangeModeSettings(0, settings(15, 5, 5)))

        assertEquals(CommandResult.Invalid(Validation.OutOfRange(STRENGTH, 15, 1..10)), result)
        assertTrue(brush.writes.isEmpty())
    }

    @Test
    fun `unknown mode never reaches the brush`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = LaifenWaveV1Driver.open(brush).apply { start() }

        assertEquals(CommandResult.UnknownMode(7), session.execute(ChangeModeSettings(7, settings(5, 5, 5))))
        assertTrue(brush.writes.isEmpty())
    }

    @Test
    fun `a brush that ignores the write is reported as not applied`() = runTest {
        val session = LaifenWaveV1Driver.open(FakeBrush(Packets.MODE2_ACTIVE, acceptsWrites = false)).apply { start() }

        assertEquals(CommandResult.NotApplied, session.execute(ChangeModeSettings(0, settings(4, 1, 5))))
    }

    @Test
    fun `a lost connection during the write is reported as failure`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE).apply { failWrites = true }
        val session = LaifenWaveV1Driver.open(brush).apply { start() }

        val result = session.execute(ChangeModeSettings(0, settings(4, 1, 5)))

        assertTrue(result is CommandResult.Failed)
    }

    @Test
    fun `a brush that applies the change a moment later is still confirmed`() = runTest {
        // Found on the real LFTB01-P: the first reads after a write return the old values.
        val session = LaifenWaveV1Driver.open(FakeBrush(Packets.MODE3_ACTIVE, staleReadsAfterWrite = 3)).apply { start() }

        assertEquals(CommandResult.Confirmed, session.execute(ChangeModeSettings(2, settings(6, 6, 5))))
        assertEquals(settings(6, 6, 5), session.state.value.modes[2])
    }

    @Test
    fun `confirmation may also arrive as a notification`() = runTest {
        val brush = FakeBrush(Packets.MODE3_ACTIVE, acceptsWrites = false)
        val session = LaifenWaveV1Driver.open(brush).apply { start() }

        launch {
            delay(400)
            session.onNotification(LaifenWaveV1Codec.CHARACTERISTIC, brush.applied(Packets.SET_MODE3_666_TO_665))
        }

        assertEquals(CommandResult.Confirmed, session.execute(ChangeModeSettings(2, settings(6, 6, 5))))
    }

    @Test
    fun `brushing duration is written, confirmed and shown`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE, staleReadsAfterWrite = 2)
        val session = LaifenWaveV1Driver.open(brush).apply { start() }

        assertEquals(CommandResult.Confirmed, session.execute(ChangeBrushingDuration(150)))
        assertArrayEquals(Packets.SET_DURATION_150, brush.writes.single())
        assertEquals(150, session.state.value.brushingDurationSeconds)
    }

    @Test
    fun `invalid brushing duration never reaches the brush`() = runTest {
        val brush = FakeBrush(Packets.MODE2_ACTIVE)
        val session = LaifenWaveV1Driver.open(brush).apply { start() }

        assertEquals(
            CommandResult.Invalid(Validation.InvalidDuration(75, 60..300, 30)),
            session.execute(ChangeBrushingDuration(75)),
        )
        assertTrue(brush.writes.isEmpty())
    }
}
