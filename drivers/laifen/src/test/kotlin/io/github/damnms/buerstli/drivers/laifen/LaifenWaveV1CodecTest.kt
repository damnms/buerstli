package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.ModeSettings
import io.github.damnms.buerstli.domain.ParameterKind.RANGE
import io.github.damnms.buerstli.domain.ParameterKind.SPEED
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
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
        assertEquals(false, state.flightMode)
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
}
