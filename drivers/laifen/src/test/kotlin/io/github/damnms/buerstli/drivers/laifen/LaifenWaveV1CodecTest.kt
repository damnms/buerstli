package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.ModeSettings
import io.github.damnms.buerstli.domain.ParameterKind.RANGE
import io.github.damnms.buerstli.domain.ParameterKind.SPEED
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
import io.github.damnms.buerstli.domain.Switch
import io.github.damnms.buerstli.drivers.laifen.Packets.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaifenWaveV1CodecTest {

    private fun settings(s: Int, r: Int, sp: Int) = ModeSettings.of(STRENGTH to s, RANGE to r, SPEED to sp)

    @Test
    fun `decodes a real status packet`() {
        val state = LaifenWaveV1Codec.decodeStatus(Packets.MODE2_ACTIVE)!!
        assertEquals(1, state.activeModeIndex)
        assertEquals(94, state.batteryPercent)
        assertEquals(
            mapOf(
                Switch.FLIGHT_MODE to false, Switch.REMINDER_30S to false,
                Switch.HIGH_FREQUENCY to false, Switch.MOTOR to false,
            ),
            state.switches,
        )
        assertEquals(settings(3, 1, 5), state.modes[0])
        assertEquals(settings(4, 6, 6), state.modes[1])
        assertEquals(settings(6, 6, 6), state.modes[2])
        assertEquals(settings(11, 6, 6), state.modes[3])
    }

    @Test
    fun `follows a mode switch on the brush`() {
        assertEquals(2, LaifenWaveV1Codec.decodeStatus(Packets.MODE3_ACTIVE)!!.activeModeIndex)
    }

    @Test
    fun `sees settings written to the brush`() {
        assertEquals(settings(6, 6, 5), LaifenWaveV1Codec.decodeStatus(Packets.AFTER_WRITE)!!.modes[2])
    }

    @Test
    fun `rejects a packet with a broken checksum`() {
        val broken = Packets.MODE2_ACTIVE.copyOf().also { it[it.lastIndex] = 0 }
        assertNull(LaifenWaveV1Codec.decodeStatus(broken))
    }

    @Test
    fun `ignores acknowledgements and other short packets`() {
        assertNull(LaifenWaveV1Codec.decodeStatus(hex("AA-04-01-01-00-AE")))
        assertNull(LaifenWaveV1Codec.decodeStatus(ByteArray(0)))
    }

    @Test
    fun `encodes exactly the command that was verified on the brush`() {
        assertArrayEquals(Packets.SET_MODE3_666_TO_665, LaifenWaveV1Codec.encodeModeSettings(2, settings(6, 6, 5)))
    }

    @Test
    fun `decodes the brushing duration`() {
        assertEquals(120, LaifenWaveV1Codec.decodeStatus(Packets.MODE2_ACTIVE)!!.brushingDurationSeconds)
        assertEquals(150, LaifenWaveV1Codec.decodeStatus(Packets.DURATION_150)!!.brushingDurationSeconds)
    }

    @Test
    fun `encodes exactly the duration command that was verified on the brush`() {
        assertArrayEquals(Packets.SET_DURATION_150, LaifenWaveV1Codec.encodeBrushingDuration(150))
        assertArrayEquals(hex("AA-05-01-02-00-78-D4"), LaifenWaveV1Codec.encodeBrushingDuration(120))
    }

    @Test
    fun `asks for the state with the documented read command`() {
        assertArrayEquals(Packets.READ_STATE, LaifenWaveV1Codec.READ_STATE)
    }

    @Test
    fun `filler from a plain GATT read is not a status`() {
        assertNull(LaifenWaveV1Codec.decodeStatus(Packets.GATT_READ_FILLER))
    }

    @Test
    fun `decodes reminder and high frequency when they are on`() {
        val state = LaifenWaveV1Codec.decodeStatus(Packets.TOOLBOX_REMINDER_AND_HF_ON)!!
        assertEquals(true, state.switches[Switch.REMINDER_30S])
        assertEquals(true, state.switches[Switch.HIGH_FREQUENCY])
        assertEquals(false, state.switches[Switch.MOTOR])
        assertEquals(93, state.batteryPercent)
    }

    @Test
    fun `encodes the switch commands documented by laifen-toolbox`() {
        assertArrayEquals(hex("AA-0B-01-01-01-A0"), LaifenWaveV1Codec.encodeSwitch(Switch.REMINDER_30S, true))
        assertArrayEquals(hex("AA-0B-01-01-00-A1"), LaifenWaveV1Codec.encodeSwitch(Switch.REMINDER_30S, false))
        assertArrayEquals(hex("AA-0E-01-01-01-A5"), LaifenWaveV1Codec.encodeSwitch(Switch.HIGH_FREQUENCY, true))
        assertArrayEquals(hex("AA-0E-01-01-00-A4"), LaifenWaveV1Codec.encodeSwitch(Switch.HIGH_FREQUENCY, false))
        // Same bytes as the Home Assistant integration uses for start/stop:
        assertArrayEquals(hex("AA-0F-01-01-01-A4"), LaifenWaveV1Codec.encodeSwitch(Switch.MOTOR, true))
        assertArrayEquals(hex("AA-0F-01-01-00-A5"), LaifenWaveV1Codec.encodeSwitch(Switch.MOTOR, false))
    }
}
